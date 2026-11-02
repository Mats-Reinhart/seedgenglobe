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
 *
 * usage: LinkCheck <mod.jar> <surfaceDir> <scannedJar...>
 */
public class LinkCheck {

    record Member(String owner, String name, String desc) {}

    static final Map<String, ClassModel> real = new HashMap<>();
    static final Set<String> seenRefs = new HashSet<>();      // owner name desc (from scanned bytecode)
    static final Map<String, Set<String>> seenKinds = new HashMap<>(); // owner name desc -> {opcode/itf}
    static final Map<String, Set<String>> seenByNameDesc = new HashMap<>(); // name+desc -> owners
    static int errors = 0, unverified = 0, ok = 0;
    static final Set<String> reported = new TreeSet<>();

    public static void main(String[] args) throws Exception {
        Path modJar = Path.of(args[0]);
        Path surface = Path.of(args[1]);
        for (int i = 2; i < args.length; i++) loadJar(Files.readAllBytes(Path.of(args[i])));
        for (String line : Files.readAllLines(surface.resolve("refs.tsv"))) {
            String[] t = line.split("\t");
            if (t.length < 4) continue;
            seenRefs.add(t[1] + " " + t[2] + " " + t[3]);
            if (t.length >= 5) seenKinds.computeIfAbsent(t[1] + " " + t[2] + " " + t[3], k -> new TreeSet<>()).add(t[0] + "/" + t[4]);
            seenByNameDesc.computeIfAbsent(t[2] + " " + t[3], k -> new TreeSet<>()).add(t[1]);
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
        System.out.printf("%nresolved: %d, unverified: %d, errors: %d%n", ok, unverified, errors);
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

    static void checkMember(String from, String owner, String name, String desc, boolean method, boolean isStatic, Map<String, ClassModel> mine) {
        if (jdk(owner)) return;
        if (mine.containsKey(owner)) return;
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
        if (cm == null) return jdk(owner) ? (owner.equals("java/lang/Object") || jdkHas(owner, name, desc, method) ? owner : null) : "external:" + owner;
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

    static boolean jdkHas(String owner, String name, String desc, boolean method) {
        if (!method) return false;
        try {
            Class<?> c = Class.forName(owner.replace('/', '.'), false, LinkCheck.class.getClassLoader());
            for (var m : c.getMethods()) {
                if (m.getName().equals(name) && MethodTypeDesc.of(ClassDesc.ofDescriptor(m.getReturnType().descriptorString()), Arrays.stream(m.getParameterTypes()).map(p -> ClassDesc.ofDescriptor(p.descriptorString())).toArray(ClassDesc[]::new)).descriptorString().equals(desc)) return true;
            }
            if (method && name.equals("<init>")) return true;
        } catch (Throwable ignored) {}
        return false;
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
                            boolean good = hd.parameterCount() == td.parameterCount() + 1 && hd.parameterType(td.parameterCount()).descriptorString().equals(ci);
                            for (int i = 0; good && i < td.parameterCount(); i++) good = hd.parameterType(i).equals(td.parameterType(i));
                            if (!good) { errors++; reported.add("ERROR   mixin " + self + "." + m.methodName() + hd.descriptorString() + " does not fit " + target + "." + tm.methodName() + td.descriptorString()); }
                            else { ok++; reported.add("OK      mixin " + self + "." + m.methodName() + " -> " + target + "." + tm.methodName() + td.descriptorString()); }
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
