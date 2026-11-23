import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.classfile.constantpool.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Checks every reference from a built mod jar to classes outside it.
 *  - references into scanned jars (Distant Horizons, Big Globe, Fabric API, ...) must resolve exactly
 *    against the real class files, walking superclasses and interfaces;
 *  - references to classes that are not available (Minecraft, libraries) must match a reference that some
 *    scanned mod makes with the same owner, name and descriptor (proof the member exists in 26.1.2),
 *    otherwise they are reported for manual review.
 * Also checks @Inject targets and @Shadow members of mixins against the real target classes.
 * <p>
 * Members a class inherits are referenced through that class (javac writes {@code this.getY()} in a subclass of a
 * Minecraft widget as {@code MyEntry.getY}). Since 1.3.1 such references, in SeedGenGlobe and in the scanned mods
 * alike, are followed up the class chain to the first class outside the jar that defines the subclass, so a member a
 * SeedGenGlobe widget inherits from Minecraft is verified when another mod's widget uses it the same way.
 *
 * usage: LinkCheck <mod.jar> <surfaceDir> <scannedJar...>
 */
public class LinkCheck {

    record Member(String owner, String name, String desc) {}

    static final Map<String, ClassModel> real = new HashMap<>();
    static final Set<String> seenRefs = new HashSet<>();      // owner name desc (from scanned bytecode)
    static final Map<String, Set<String>> seenKinds = new HashMap<>(); // owner name desc -> {opcode/itf}
    static final Map<String, Set<String>> seenByNameDesc = new HashMap<>(); // name+desc -> owners
    static int errors = 0, unverified = 0, ok = 0, inherited = 0;
    /** Classes the scanned mods define: superclass, interfaces, and their own members ("class name desc"). */
    static final Map<String, String> surfaceSuper = new HashMap<>();
    static final Map<String, List<String>> surfaceInterfaces = new HashMap<>();
    static final Set<String> surfaceMembers = new HashSet<>();
    static final Set<String> reported = new TreeSet<>();

    public static void main(String[] args) throws Exception {
        Path modJar = Path.of(args[0]);
        Path surface = Path.of(args[1]);
        for (int i = 2; i < args.length; i++) loadJar(Files.readAllBytes(Path.of(args[i])));
        Path definedFile = surface.resolve("defined.tsv");
        if (Files.isRegularFile(definedFile)) {
            for (String line : Files.readAllLines(definedFile)) {
                String[] t = line.split("\t", -1);
                if (t.length < 4) continue;
                if (t[0].equals("C")) {
                    surfaceSuper.putIfAbsent(t[1], t[3]);
                    if (t.length > 4 && !t[4].isEmpty()) surfaceInterfaces.putIfAbsent(t[1], List.of(t[4].split(",")));
                }
                else if (t[0].equals("M") || t[0].equals("F")) surfaceMembers.add(t[1] + " " + t[2] + " " + t[3]);
            }
        }
        for (String line : Files.readAllLines(surface.resolve("refs.tsv"))) {
            String[] t = line.split("\t");
            if (t.length < 4) continue;
            List<String> owners = new ArrayList<>(2);
            owners.add(t[1]);
            // a member another mod's class inherits from outside its jar also counts for that outside class
            String base = inheritedFrom(t[1], t[2], t[3]);
            if (base != null && !base.equals(t[1])) owners.add(base);
            for (String owner : owners) {
                seenRefs.add(owner + " " + t[2] + " " + t[3]);
                if (t.length >= 5) seenKinds.computeIfAbsent(owner + " " + t[2] + " " + t[3], k -> new TreeSet<>()).add(t[0] + "/" + t[4]);
                seenByNameDesc.computeIfAbsent(t[2] + " " + t[3], k -> new TreeSet<>()).add(owner);
            }
        }
        Map<String, ClassModel> mine = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(modJar))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().endsWith(".class")) {
                    ClassModel cm = ClassFile.of().parse(zin.readAllBytes());
                    mine.put(cm.thisClass().asInternalName(), cm);
                }
            }
        }
        for (ClassModel cm : mine.values()) checkClass(cm, mine);
        for (String r : reported) System.out.println(r);
        System.out.printf("%nresolved: %d (%d of them inherited through SeedGenGlobe's own subclasses), unverified: %d, errors: %d%n", ok, inherited, unverified, errors);
        if (errors > 0) System.exit(1);
    }

    static void loadJar(byte[] bytes) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                if (n.endsWith(".class") && !n.startsWith("META-INF/versions/")) {
                    try {
                        ClassModel cm = ClassFile.of().parse(zin.readAllBytes());
                        real.putIfAbsent(cm.thisClass().asInternalName(), cm);
                    } catch (Throwable ignored) {}
                } else if (n.endsWith(".jar")) loadJar(zin.readAllBytes());
            }
        }
    }

    static boolean jdk(String owner) {
        if (owner.startsWith("[")) return true;
        return owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/") || owner.startsWith("sun/");
    }

    static void checkClass(ClassModel cm, Map<String, ClassModel> mine) {
        String self = cm.thisClass().asInternalName();
        cm.superclass().ifPresent(sc -> checkType(self, sc.asInternalName(), mine));
        for (ClassEntry i : cm.interfaces()) checkType(self, i.asInternalName(), mine);
        for (MethodModel m : cm.methods()) {
            m.code().ifPresent(code -> {
                for (CodeElement el : code) {
                    if (el instanceof InvokeInstruction ii) {
                        checkMember(self, ii.owner().asInternalName(), ii.name().stringValue(), ii.type().stringValue(), true, ii.opcode() == Opcode.INVOKESTATIC, mine);
                        checkKind(self, ii.owner().asInternalName(), ii.name().stringValue(), ii.type().stringValue(), ii.opcode().name().toLowerCase() + "/" + ii.isInterface(), mine);
                    } else if (el instanceof FieldInstruction fi) {
                        checkMember(self, fi.owner().asInternalName(), fi.name().stringValue(), fi.type().stringValue(), false, fi.opcode() == Opcode.GETSTATIC || fi.opcode() == Opcode.PUTSTATIC, mine);
                    } else if (el instanceof NewObjectInstruction no) {
                        checkType(self, no.className().asInternalName(), mine);
                    } else if (el instanceof TypeCheckInstruction tc) {
                        checkType(self, tc.type().asInternalName(), mine);
                    } else if (el instanceof InvokeDynamicInstruction indy) {
                        for (ConstantDesc arg : indy.bootstrapArgs()) {
                            if (arg instanceof DirectMethodHandleDesc mh) {
                                String owner = mh.owner().descriptorString();
                                owner = owner.substring(1, owner.length() - 1);
                                checkMember(self, owner, mh.methodName(), mh.lookupDescriptor(), true, mh.kind() == DirectMethodHandleDesc.Kind.STATIC || mh.kind() == DirectMethodHandleDesc.Kind.INTERFACE_STATIC, mine);
                            }
                        }
                    }
                }
            });
        }
        checkMixin(cm);
    }

    static void checkKind(String from, String owner, String name, String desc, String kind, Map<String, ClassModel> mine) {
        if (jdk(owner) || mine.containsKey(owner)) return;
        if (real.containsKey(owner)) {
            ClassModel cm = real.get(owner);
            boolean itf = (cm.flags().flagsMask() & ClassFile.ACC_INTERFACE) != 0;
            boolean usesItf = kind.endsWith("/true");
            if (itf != usesItf) { errors++; reported.add("ERROR   " + kind + " on " + (itf ? "interface " : "class ") + owner + "." + name + desc + "   (from " + from + ")"); }
            return;
        }
        Set<String> kinds = seenKinds.get(owner + " " + name + " " + desc);
        if (kinds == null) return; // reported as unverified elsewhere
        String op = kind.substring(0, kind.indexOf('/'));
        String itf = kind.substring(kind.indexOf('/') + 1);
        boolean match = false;
        for (String k : kinds) {
            String kop = k.substring(0, k.indexOf('/'));
            String kitf = k.substring(k.indexOf('/') + 1);
            boolean kinvoke = kop.startsWith("invoke") || kop.startsWith("mh_");
            if (!kinvoke) continue;
            boolean staticBoth = op.equals("invokestatic") == (kop.equals("invokestatic") || kop.equals("mh_static") || kop.equals("mh_interface_static"));
            if (staticBoth && itf.equals(kitf)) match = true;
        }
        if (!match) { errors++; reported.add("ERROR   kind mismatch " + kind + " vs seen " + kinds + " for " + owner + "." + name + desc + "   (from " + from + ")"); }
    }

    static void checkType(String from, String owner, Map<String, ClassModel> mine) {
        if (jdk(owner) || mine.containsKey(owner)) return;
        if (real.containsKey(owner)) { ok++; return; }
        boolean seen = false;
        for (String key : seenRefs) if (key.startsWith(owner + " ")) { seen = true; break; }
        if (seen) ok++;
        else { unverified++; reported.add("UNVERIFIED type " + owner + "   (from " + from + ")"); }
    }

    /**
     * For a reference through a class a scanned mod defines: the first class up its superclass chain that the scanned
     * mods do not define, when no class on the way declares the member itself; null otherwise.
     */
    static String inheritedFrom(String owner, String name, String desc) {
        String current = owner;
        for (int depth = 0; depth < 32; depth++) {
            String parent = surfaceSuper.get(current);
            if (parent == null) return depth == 0 ? null : current;
            if (surfaceMembers.contains(current + " " + name + " " + desc)) return null;
            List<String> interfaces = surfaceInterfaces.getOrDefault(current, List.of());
            for (String i : interfaces) if (surfaceMembers.contains(i + " " + name + " " + desc)) return null;
            if (parent.isEmpty() || jdk(parent)) return null;
            current = parent;
        }
        return null;
    }

    /** Whether one of SeedGenGlobe's classes or its interfaces (or theirs) declares the member. */
    static boolean declaredInMine(ClassModel cm, String name, String desc, boolean method, Map<String, ClassModel> mine, Set<String> visited) {
        if (!visited.add(cm.thisClass().asInternalName())) return false;
        if (method) {
            for (MethodModel m : cm.methods()) if (m.methodName().equalsString(name) && m.methodType().equalsString(desc)) return true;
        } else {
            for (FieldModel f : cm.fields()) if (f.fieldName().equalsString(name) && f.fieldType().equalsString(desc)) return true;
        }
        for (ClassEntry i : cm.interfaces()) {
            ClassModel im = mine.get(i.asInternalName());
            if (im != null && declaredInMine(im, name, desc, method, mine, visited)) return true;
        }
        return false;
    }

    static void checkMember(String from, String owner, String name, String desc, boolean method, boolean isStatic, Map<String, ClassModel> mine) {
        if (jdk(owner)) return;
        if (mine.containsKey(owner)) {
            // a member of SeedGenGlobe's own class, or one it inherits: check the latter on the class it comes from
            String current = owner;
            Set<String> visited = new HashSet<>();
            while (mine.containsKey(current)) {
                ClassModel cm = mine.get(current);
                if (declaredInMine(cm, name, desc, method, mine, visited)) return;
                if (cm.superclass().isEmpty()) return;
                current = cm.superclass().get().asInternalName();
            }
            if (jdk(current)) return;
            int before = ok;
            checkMember(from, current, name, desc, method, isStatic, mine);
            if (ok > before) inherited++;
            return;
        }
        if (real.containsKey(owner)) {
            String found = resolve(owner, name, desc, method, new HashSet<>());
            if (found == null) { errors++; reported.add("ERROR   " + owner + "." + name + desc + " does not exist   (from " + from + ")"); return; }
            if (found.startsWith("external:")) {
                String ext = found.substring(9);
                if (seenRefs.contains(ext + " " + name + " " + desc)) { ok++; return; }
                Set<String> others = seenByNameDesc.getOrDefault(name + " " + desc, Set.of());
                if (!others.isEmpty()) { ok++; return; }
                unverified++;
                reported.add("UNVERIFIED " + owner + "." + name + desc + " (inherited from unavailable " + ext + ")   (from " + from + ")");
                return;
            }
            ok++;
            return;
        }
        // owner unavailable (Minecraft or library): needs proof from scanned bytecode
        if (seenRefs.contains(owner + " " + name + " " + desc)) { ok++; return; }
        Set<String> others = seenByNameDesc.getOrDefault(name + " " + desc, Set.of());
        unverified++;
        reported.add("UNVERIFIED " + owner + "." + name + desc + (others.isEmpty() ? "  (never referenced by other mods)" : "  (seen on " + String.join(", ", others) + ")") + "   (from " + from + ")");
    }

    /** @return declaring class, "external:<class>" if resolution reached an unavailable class, or null if absent. */
    static String resolve(String owner, String name, String desc, boolean method, Set<String> visited) {
        if (!visited.add(owner)) return null;
        ClassModel cm = real.get(owner);
        // up to 1.4.0 any member reached java/lang/Object and counted as found there, so a member missing from a
        // Distant Horizons or Big Globe class was never reported: a JDK class has only the members reflection finds
        if (cm == null) {
            if (!jdk(owner)) return "external:" + owner;
            return jdkHas(owner, name, desc, method) ? owner : null;
        }
        if (method) {
            for (MethodModel m : cm.methods()) if (m.methodName().equalsString(name) && m.methodType().equalsString(desc)) return owner;
        } else {
            for (FieldModel f : cm.fields()) if (f.fieldName().equalsString(name) && f.fieldType().equalsString(desc)) return owner;
        }
        String external = null;
        if (cm.superclass().isPresent()) {
            String r = resolve(cm.superclass().get().asInternalName(), name, desc, method, visited);
            if (r != null && !r.startsWith("external:")) return r;
            if (r != null) external = r;
        }
        for (ClassEntry i : cm.interfaces()) {
            String r = resolve(i.asInternalName(), name, desc, method, visited);
            if (r != null && !r.startsWith("external:")) return r;
            if (r != null && external == null) external = r;
        }
        return external;
    }

    /** Whether a JDK class declares or inherits the member (constructors: declares), public or not. */
    static boolean jdkHas(String owner, String name, String desc, boolean method) {
        try {
            Class<?> c = Class.forName(owner.replace('/', '.'), false, LinkCheck.class.getClassLoader());
            if (method && name.equals("<init>")) {
                for (var k : c.getDeclaredConstructors()) if (descriptor(k.getParameterTypes(), void.class).equals(desc)) return true;
                return false;
            }
            List<Class<?>> chain = new ArrayList<>();
            for (Class<?> k = c; k != null; k = k.getSuperclass()) chain.add(k);
            for (int i = 0; i < chain.size(); i++) {
                for (Class<?> face : chain.get(i).getInterfaces()) if (!chain.contains(face)) chain.add(face);
            }
            if (c.isInterface()) chain.add(Object.class);
            for (Class<?> k : chain) {
                if (method) {
                    for (var m : k.getDeclaredMethods()) if (m.getName().equals(name) && descriptor(m.getParameterTypes(), m.getReturnType()).equals(desc)) return true;
                } else {
                    for (var f : k.getDeclaredFields()) if (f.getName().equals(name) && f.getType().descriptorString().equals(desc)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static String descriptor(Class<?>[] parameters, Class<?> result) {
        StringBuilder builder = new StringBuilder("(");
        for (Class<?> p : parameters) builder.append(p.descriptorString());
        return builder.append(')').append(result.descriptorString()).toString();
    }

    // ------------------------------------------------------------------ mixins

    static void checkMixin(ClassModel cm) {
        Optional<RuntimeInvisibleAnnotationsAttribute> inv = cm.findAttribute(Attributes.runtimeInvisibleAnnotations());
        if (inv.isEmpty()) return;
        List<String> targets = new ArrayList<>();
        for (Annotation a : inv.get().annotations()) {
            if (!a.className().equalsString("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            for (AnnotationElement el : a.elements()) {
                if (el.name().equalsString("value") && el.value() instanceof AnnotationValue.OfArray arr) {
                    for (AnnotationValue v : arr.values()) if (v instanceof AnnotationValue.OfClass c) {
                        String d = c.className().stringValue();
                        targets.add(d.substring(1, d.length() - 1));
                    }
                }
            }
        }
        if (targets.isEmpty()) return;
        String self = cm.thisClass().asInternalName();
        for (String target : targets) {
            ClassModel t = real.get(target);
            if (t == null && target.startsWith("net/minecraft/")) { checkMinecraftMixin(cm, self, target); continue; }
            if (t == null) { errors++; reported.add("ERROR   mixin " + self + " targets missing class " + target); continue; }
            // @Shadow fields
            for (FieldModel f : cm.fields()) {
                if (hasVisible(f.findAttribute(Attributes.runtimeVisibleAnnotations()), "Lorg/spongepowered/asm/mixin/Shadow;")) {
                    String found = resolve(target, f.fieldName().stringValue(), f.fieldType().stringValue(), false, new HashSet<>());
                    boolean declared = t.fields().stream().anyMatch(x -> x.fieldName().equalsString(f.fieldName().stringValue()) && x.fieldType().equalsString(f.fieldType().stringValue()));
                    if (!declared) { errors++; reported.add("ERROR   mixin " + self + " @Shadow field " + f.fieldName() + " " + f.fieldType() + " not declared in " + target + (found != null ? " (inherited from " + found + ")" : "")); }
                    else { ok++; reported.add("OK      mixin " + self + " @Shadow " + f.fieldName() + " " + f.fieldType()); }
                }
            }
            checkWrapOperations(cm, self, target, t);
            // @Inject handlers
            for (MethodModel m : cm.methods()) {
                Optional<RuntimeVisibleAnnotationsAttribute> vis = m.findAttribute(Attributes.runtimeVisibleAnnotations());
                if (vis.isEmpty()) continue;
                for (Annotation a : vis.get().annotations()) {
                    if (!a.className().equalsString("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                    List<String> selectors = new ArrayList<>();
                    for (AnnotationElement el : a.elements()) {
                        if (el.name().equalsString("method") && el.value() instanceof AnnotationValue.OfArray arr) {
                            for (AnnotationValue v : arr.values()) if (v instanceof AnnotationValue.OfString s) selectors.add(s.stringValue());
                        }
                    }
                    for (String selector : selectors) {
                        String selName = selector.contains("(") ? selector.substring(0, selector.indexOf('(')) : selector;
                        String selDesc = selector.contains("(") ? selector.substring(selector.indexOf('(')) : null;
                        List<MethodModel> matches = t.methods().stream().filter(x -> x.methodName().equalsString(selName) && (selDesc == null || x.methodType().equalsString(selDesc))).toList();
                        if (matches.isEmpty()) { errors++; reported.add("ERROR   mixin " + self + "." + m.methodName() + " injects into missing " + target + "." + selector); continue; }
                        if (matches.size() > 1) { reported.add("WARN    mixin " + self + "." + m.methodName() + " selector " + selector + " matches " + matches.size() + " methods"); }
                        for (MethodModel tm : matches) {
                            // handler params must be target params + CallbackInfo(Returnable)
                            MethodTypeDesc td = tm.methodTypeSymbol();
                            MethodTypeDesc hd = m.methodTypeSymbol();
                            boolean returns = !td.returnType().descriptorString().equals("V");
                            String ci = returns ? "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;" : "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
                            // Mixin accepts the target's parameters followed by the callback, or the callback alone
                            boolean good = hd.parameterCount() == td.parameterCount() + 1 && hd.parameterType(td.parameterCount()).descriptorString().equals(ci);
                            for (int i = 0; good && i < td.parameterCount(); i++) good = hd.parameterType(i).equals(td.parameterType(i));
                            if (!good && hd.parameterCount() == 1 && hd.parameterType(0).descriptorString().equals(ci)) good = true;
                            if (!good) { errors++; reported.add("ERROR   mixin " + self + "." + m.methodName() + hd.descriptorString() + " does not fit " + target + "." + tm.methodName() + td.descriptorString()); }
                            else { ok++; reported.add("OK      mixin " + self + "." + m.methodName() + " -> " + target + "." + tm.methodName() + td.descriptorString()); }
                        }
                    }
                }
            }
        }
    }

    /**
     * A mixin into a Minecraft class, which is not available: each @Inject selector with a full descriptor must match
     * a method other mods call with that owner, name and descriptor (OK), or it is listed for a manual look (UNVERIFIED).
     * The handler may take the target's parameters and the callback, or the callback alone.
     */
    static void checkMinecraftMixin(ClassModel cm, String self, String target) {
        for (MethodModel m : cm.methods()) {
            Optional<RuntimeVisibleAnnotationsAttribute> vis = m.findAttribute(Attributes.runtimeVisibleAnnotations());
            if (vis.isEmpty()) continue;
            for (Annotation a : vis.get().annotations()) {
                if (!a.className().equalsString("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                for (AnnotationElement el : a.elements()) {
                    if (!el.name().equalsString("method") || !(el.value() instanceof AnnotationValue.OfArray arr)) continue;
                    for (AnnotationValue v : arr.values()) {
                        if (!(v instanceof AnnotationValue.OfString str)) continue;
                        String selector = str.stringValue();
                        int paren = selector.indexOf('(');
                        if (paren < 0) { unverified++; reported.add("UNVERIFIED mixin " + self + "." + m.methodName() + " injects into " + target + "." + selector + " (no descriptor)"); continue; }
                        String name = selector.substring(0, paren), desc = selector.substring(paren);
                        MethodTypeDesc td = MethodTypeDesc.ofDescriptor(desc), hd = m.methodTypeSymbol();
                        String ci = td.returnType().descriptorString().equals("V") ? "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;" : "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
                        boolean fits = hd.parameterCount() == 1 && hd.parameterType(0).descriptorString().equals(ci);
                        if (!fits && hd.parameterCount() == td.parameterCount() + 1 && hd.parameterType(td.parameterCount()).descriptorString().equals(ci)) {
                            fits = true;
                            for (int i = 0; fits && i < td.parameterCount(); i++) fits = hd.parameterType(i).equals(td.parameterType(i));
                        }
                        if (!fits) { errors++; reported.add("ERROR   mixin " + self + "." + m.methodName() + hd.descriptorString() + " does not fit " + target + "." + selector); continue; }
                        if (seenRefs.contains(target + " " + name + " " + desc)) { ok++; reported.add("OK      mixin " + self + "." + m.methodName() + " -> " + target + "." + selector + " (called by other mods)"); }
                        else { unverified++; reported.add("UNVERIFIED mixin " + self + "." + m.methodName() + " -> " + target + "." + selector + " (Minecraft method nothing else calls)"); }
                    }
                }
            }
        }
    }

    /**
     * MixinExtras @WrapOperation handlers: each selected target method must exist and contain the wrapped call
     * (@At INVOKE target), and the handler must take the call's receiver (unless static) and arguments plus an
     * Operation, and return what the call returns.
     */
    static void checkWrapOperations(ClassModel cm, String self, String target, ClassModel t) {
        for (MethodModel m : cm.methods()) {
            Optional<RuntimeVisibleAnnotationsAttribute> vis = m.findAttribute(Attributes.runtimeVisibleAnnotations());
            if (vis.isEmpty()) continue;
            for (Annotation a : vis.get().annotations()) {
                if (!a.className().equalsString("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")) continue;
                List<String> selectors = new ArrayList<>();
                List<String> calls = new ArrayList<>();
                List<Integer> ordinals = new ArrayList<>();
                for (AnnotationElement el : a.elements()) {
                    if (el.name().equalsString("method") && el.value() instanceof AnnotationValue.OfArray arr) {
                        for (AnnotationValue v : arr.values()) if (v instanceof AnnotationValue.OfString str) selectors.add(str.stringValue());
                    }
                    if (el.name().equalsString("at") && el.value() instanceof AnnotationValue.OfArray arr) {
                        for (AnnotationValue v : arr.values()) {
                            if (!(v instanceof AnnotationValue.OfAnnotation at)) continue;
                            String kind = null, call = null;
                            int ordinal = -1;
                            for (AnnotationElement ae : at.annotation().elements()) {
                                if (ae.name().equalsString("value") && ae.value() instanceof AnnotationValue.OfString str) kind = str.stringValue();
                                if (ae.name().equalsString("target") && ae.value() instanceof AnnotationValue.OfString str) call = str.stringValue();
                                if (ae.name().equalsString("ordinal") && ae.value() instanceof AnnotationValue.OfInt i) ordinal = i.intValue();
                            }
                            if ("INVOKE".equals(kind) && call != null) {
                                calls.add(call);
                                ordinals.add(ordinal);
                            }
                            else { errors++; reported.add("ERROR   mixin " + self + "." + m.methodName() + " @WrapOperation needs @At(value = \"INVOKE\", target = ...)"); }
                        }
                    }
                }
                for (int c = 0; c < calls.size(); c++) {
                    String call = calls.get(c);
                    int ordinal = ordinals.get(c);
                    // "Lowner;name(args)ret"
                    int semi = call.indexOf(';'), paren = call.indexOf('(');
                    if (!call.startsWith("L") || semi < 0 || paren < semi) { errors++; reported.add("ERROR   mixin " + self + " bad @At target " + call); continue; }
                    String owner = call.substring(1, semi), name = call.substring(semi + 1, paren), desc = call.substring(paren);
                    for (String selector : selectors) {
                        String selName = selector.contains("(") ? selector.substring(0, selector.indexOf('(')) : selector;
                        String selDesc = selector.contains("(") ? selector.substring(selector.indexOf('(')) : null;
                        List<MethodModel> matches = t.methods().stream().filter(x -> x.methodName().equalsString(selName) && (selDesc == null || x.methodType().equalsString(selDesc))).toList();
                        if (matches.isEmpty()) { errors++; reported.add("ERROR   mixin " + self + "." + m.methodName() + " wraps a call in missing " + target + "." + selector); continue; }
                        for (MethodModel tm : matches) {
                            int seen = 0;
                            boolean isStatic = false, found = false;
                            if (tm.code().isPresent()) {
                                for (CodeElement e : tm.code().get()) {
                                    if (e instanceof InvokeInstruction inv && inv.owner().asInternalName().equals(owner) && inv.name().equalsString(name) && inv.type().equalsString(desc)) {
                                        if (ordinal < 0 || seen == ordinal) { found = true; isStatic = inv.opcode() == Opcode.INVOKESTATIC; }
                                        seen++;
                                    }
                                }
                            }
                            if (!found) { errors++; reported.add("ERROR   mixin " + self + "." + m.methodName() + ": " + target + "." + tm.methodName() + " has no call " + call + (ordinal >= 0 ? " #" + ordinal : "") + " (" + seen + " found)"); continue; }
                            MethodTypeDesc cd = MethodTypeDesc.ofDescriptor(desc);
                            List<ClassDesc> expected = new ArrayList<>();
                            if (!isStatic) expected.add(ClassDesc.ofInternalName(owner));
                            expected.addAll(cd.parameterList());
                            expected.add(ClassDesc.of("com.llamalad7.mixinextras.injector.wrapoperation.Operation"));
                            MethodTypeDesc hd = m.methodTypeSymbol();
                            boolean good = hd.returnType().equals(cd.returnType()) && hd.parameterList().equals(expected);
                            if (!good) { errors++; reported.add("ERROR   mixin " + self + "." + m.methodName() + hd.descriptorString() + " does not fit the wrapped call " + call); }
                            else { ok++; reported.add("OK      mixin " + self + "." + m.methodName() + " wraps " + owner + "." + name + desc + (ordinal >= 0 ? " #" + ordinal : "") + " in " + target + "." + tm.methodName()); }
                        }
                    }
                }
            }
        }
    }

    static boolean hasVisible(Optional<RuntimeVisibleAnnotationsAttribute> attr, String desc) {
        return attr.isPresent() && attr.get().annotations().stream().anyMatch(a -> a.className().equalsString(desc));
    }
}
