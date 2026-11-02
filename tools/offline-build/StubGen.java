import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.constant.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Generates compile-only stub class files for external classes (Minecraft & libraries) that are
 * referenced by the scanned jars but not defined in them. Members come from the observed references,
 * generic arity from observed Signature attributes, hierarchy from a hand-maintained file.
 *
 * usage: StubGen <outDir> <hierarchy.txt> <prefixes,comma> <jar...>
 */
public class StubGen {
    static final Map<String, ClassInfo> classes = new TreeMap<>();
    static final Set<String> definedInJars = new HashSet<>();
    static final Map<String, Integer> arity = new HashMap<>();
    static final Map<String, String> customSig = new HashMap<>();
    static final Set<String> bare = new HashSet<>();
    static final Map<String, List<String[]>> extraMembers = new HashMap<>();
    static String[] prefixes;

    static class ClassInfo {
        String name;
        Boolean isInterface; // null = unknown
        String superName;
        List<String> interfaces = new ArrayList<>();
        Map<String, Integer> fields = new TreeMap<>(); // name+desc -> flags
        Map<String, Integer> methods = new TreeMap<>(); // name+desc -> flags
        boolean isEnumLike, isAnnotation;
        String outer;
        List<String> inners = new ArrayList<>();
        ClassInfo(String n) { name = n; }
    }

    static boolean wanted(String internal) {
        if (internal.startsWith("[")) return false;
        for (String p : prefixes) if (internal.startsWith(p)) return true;
        return false;
    }

    static ClassInfo info(String n) { return classes.computeIfAbsent(n, ClassInfo::new); }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Path hierarchy = Path.of(args[1]);
        prefixes = args[2].split(",");
        List<byte[]> all = new ArrayList<>();
        for (int i = 3; i < args.length; i++) collect(Files.readAllBytes(Path.of(args[i])), all);
        // pass 1: defined classes
        List<ClassModel> models = new ArrayList<>();
        for (byte[] b : all) { try { ClassModel cm = ClassFile.of().parse(b); models.add(cm); definedInJars.add(cm.thisClass().asInternalName()); } catch (Throwable t) {} }
        // pass 2: references
        for (ClassModel cm : models) scan(cm);
        // hierarchy overrides
        for (String line : Files.readAllLines(hierarchy)) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // format: [interface|class] name [extends super] [implements a,b]
            String[] t = line.split("\\s+");
            if (t[0].equals("method") || t[0].equals("field")) {
                ClassInfo owner = info(t[1]);
                int flags = ClassFile.ACC_PUBLIC | (t.length > 4 && t[4].equals("static") ? ClassFile.ACC_STATIC : 0);
                if (t[0].equals("method")) owner.methods.merge(t[2] + t[3], flags, (a, b) -> a | b);
                else owner.fields.merge(t[2] + ":" + t[3], flags, (a, b) -> a | b);
                continue;
            }
            ClassInfo ci = info(t[1]);
            ci.isInterface = t[0].equals("interface");
            for (int i = 2; i < t.length; i++) {
                if (t[i].equals("extends")) {
                    if (ci.isInterface) { for (String s : t[++i].split(",")) ci.interfaces.add(s); }
                    else ci.superName = t[++i];
                } else if (t[i].equals("implements")) { for (String s : t[++i].split(",")) ci.interfaces.add(s); }
                else if (t[i].startsWith("arity=")) arity.put(t[1], Integer.parseInt(t[i].substring(6)));
                else if (t[i].startsWith("sig=")) customSig.put(t[1], t[i].substring(4));
                else if (t[i].equals("bare")) bare.add(t[1]);
                else if (t[i].equals("enum")) ci.isEnumLike = true;
            }
        }
        // nested classes need InnerClasses attributes on both sides, or javac cannot resolve Outer.Inner
        for (ClassInfo ci : new ArrayList<>(classes.values())) {
            if (!wanted(ci.name) || definedInJars.contains(ci.name)) continue;
            int dollar = ci.name.lastIndexOf('$');
            if (dollar < 0) continue;
            String simple = ci.name.substring(dollar + 1);
            if (simple.isEmpty() || Character.isDigit(simple.charAt(0))) continue;
            String outer = ci.name.substring(0, dollar);
            ci.outer = outer;
            ClassInfo outerInfo = info(outer);
            outerInfo.inners.add(ci.name);
        }
        int count = 0;
        for (ClassInfo ci : classes.values()) {
            if (!wanted(ci.name) || definedInJars.contains(ci.name)) continue;
            byte[] bytes = build(ci);
            Path p = out.resolve(ci.name + ".class");
            Files.createDirectories(p.getParent());
            Files.write(p, bytes);
            count++;
        }
        System.out.println("stubs written: " + count);
    }

    static void collect(byte[] jar, List<byte[]> all) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/versions/")) all.add(zin.readAllBytes());
                else if (e.getName().endsWith(".jar")) collect(zin.readAllBytes(), all);
            }
        }
    }

    static void noteDesc(String desc) {
        // register class names in descriptors so they get stubbed
        int i = 0;
        while ((i = desc.indexOf('L', i)) >= 0) {
            int semi = desc.indexOf(';', i);
            if (semi < 0) break;
            String n = desc.substring(i + 1, semi);
            if (wanted(n) && !n.contains("<")) info(n);
            i = semi + 1;
        }
    }

    static void noteSignature(String sig) {
        // count type args for every class type in a generic signature
        parseSig(sig, 0);
    }

    // returns index after parsing; records arity of class types
    static int parseSig(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == 'L') {
                int j = i + 1;
                StringBuilder name = new StringBuilder();
                while (j < s.length() && s.charAt(j) != ';' && s.charAt(j) != '<') name.append(s.charAt(j++));
                String cls = name.toString();
                int args = 0;
                if (j < s.length() && s.charAt(j) == '<') {
                    j++;
                    while (s.charAt(j) != '>') {
                        if (s.charAt(j) == '*') { j++; args++; continue; }
                        if (s.charAt(j) == '+' || s.charAt(j) == '-') j++;
                        j = parseOne(s, j);
                        args++;
                    }
                    j++; // '>'
                }
                // inner class continuation '.Name<...>' -> treat simply
                while (j < s.length() && s.charAt(j) == '.') {
                    StringBuilder inner = new StringBuilder();
                    j++;
                    while (j < s.length() && s.charAt(j) != ';' && s.charAt(j) != '<' && s.charAt(j) != '.') inner.append(s.charAt(j++));
                    cls = cls + "$" + inner;
                    int a2 = 0;
                    if (j < s.length() && s.charAt(j) == '<') {
                        j++;
                        while (s.charAt(j) != '>') {
                            if (s.charAt(j) == '*') { j++; a2++; continue; }
                            if (s.charAt(j) == '+' || s.charAt(j) == '-') j++;
                            j = parseOne(s, j);
                            a2++;
                        }
                        j++;
                    }
                    args = a2;
                }
                if (wanted(cls)) { info(cls); if (args > 0) arity.merge(cls, args, Math::max); }
                i = j + 1; // skip ';'
            } else i++;
        }
        return i;
    }

    static int parseOne(String s, int j) {
        char c = s.charAt(j);
        if (c == 'L') {
            int depth = 0;
            while (true) {
                char d = s.charAt(j);
                if (d == '<') depth++;
                else if (d == '>') depth--;
                else if (d == ';' && depth == 0) { j++; break; }
                j++;
            }
            // record arity for nested by recursive parse of the substring
            return j;
        } else if (c == 'T') { return s.indexOf(';', j) + 1; }
        else if (c == '[') { return parseOne(s, j + 1); }
        else return j + 1; // primitive
    }

    static void scan(ClassModel cm) {
        String self = cm.thisClass().asInternalName();
        cm.superclass().ifPresent(sc -> { String n = sc.asInternalName(); if (wanted(n)) { ClassInfo ci = info(n); if (ci.isInterface == null) ci.isInterface = false; } });
        for (var i : cm.interfaces()) { String n = i.asInternalName(); if (wanted(n)) info(n).isInterface = true; }
        cm.findAttribute(Attributes.signature()).ifPresent(sa -> noteSignature(sa.signature().stringValue()));
        for (FieldModel f : cm.fields()) {
            noteDesc(f.fieldType().stringValue());
            f.findAttribute(Attributes.signature()).ifPresent(sa -> noteSignature(sa.signature().stringValue()));
        }
        for (MethodModel m : cm.methods()) {
            noteDesc(m.methodType().stringValue());
            m.findAttribute(Attributes.signature()).ifPresent(sa -> noteSignature(sa.signature().stringValue()));
            // methods overriding external supertypes are evidence of those methods
            m.code().ifPresent(code -> {
                for (CodeElement el : code) {
                    if (el instanceof java.lang.classfile.instruction.InvokeInstruction ii) {
                        String owner = ii.owner().asInternalName();
                        noteDesc(ii.type().stringValue());
                        if (!wanted(owner)) continue;
                        ClassInfo ci = info(owner);
                        if (ii.isInterface()) ci.isInterface = true;
                        else if (ii.opcode() == Opcode.INVOKEVIRTUAL && ci.isInterface == null) ci.isInterface = false;
                        int flags = ClassFile.ACC_PUBLIC;
                        if (ii.opcode() == Opcode.INVOKESTATIC) flags |= ClassFile.ACC_STATIC;
                        ci.methods.merge(ii.name().stringValue() + ii.type().stringValue(), flags, (a, b) -> a | b);
                    } else if (el instanceof java.lang.classfile.instruction.FieldInstruction fi) {
                        String owner = fi.owner().asInternalName();
                        noteDesc(fi.type().stringValue());
                        if (!wanted(owner)) continue;
                        ClassInfo ci = info(owner);
                        int flags = ClassFile.ACC_PUBLIC;
                        if (fi.opcode() == Opcode.GETSTATIC || fi.opcode() == Opcode.PUTSTATIC) flags |= ClassFile.ACC_STATIC;
                        ci.fields.merge(fi.name().stringValue() + ":" + fi.type().stringValue(), flags, (a, b) -> a | b);
                    } else if (el instanceof java.lang.classfile.instruction.TypeCheckInstruction tc) {
                        String n = tc.type().asInternalName(); if (wanted(n)) info(n);
                    } else if (el instanceof java.lang.classfile.instruction.NewObjectInstruction no) {
                        String n = no.className().asInternalName(); if (wanted(n)) { ClassInfo ci = info(n); if (ci.isInterface == null) ci.isInterface = false; }
                    }
                }
            });
        }
    }

    static byte[] build(ClassInfo ci) {
        boolean itf = Boolean.TRUE.equals(ci.isInterface);
        ClassDesc self = ClassDesc.ofInternalName(ci.name);
        return ClassFile.of().build(self, cb -> {
            int flags = ClassFile.ACC_PUBLIC | (itf ? ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT : 0);
            cb.withFlags(flags);
            cb.withSuperclass(ClassDesc.ofInternalName(itf || ci.superName == null ? "java/lang/Object" : ci.superName));
            if (!ci.interfaces.isEmpty()) cb.withInterfaceSymbols(ci.interfaces.stream().map(ClassDesc::ofInternalName).toList());
            Integer ar = arity.get(ci.name);
            if (customSig.containsKey(ci.name)) {
                cb.with(SignatureAttribute.of(ClassSignature.parseFrom(customSig.get(ci.name))));
            }
            else if (ar != null && ar > 0) {
                StringBuilder sig = new StringBuilder("<");
                for (int i = 0; i < ar; i++) sig.append("T").append(i).append(":Ljava/lang/Object;");
                sig.append(">Ljava/lang/Object;");
                for (String s : ci.interfaces) sig.append("L").append(s).append(";");
                if (!itf && ci.superName != null) {
                    // replace superclass in signature
                    sig = new StringBuilder(sig.toString().replace(">Ljava/lang/Object;", ">L" + ci.superName + ";"));
                }
                if (itf) {
                    // interface signature: superclass is Object, then superinterfaces
                }
                cb.with(SignatureAttribute.of(ClassSignature.parseFrom(sig.toString())));
            }
            List<InnerClassInfo> innerInfos = new ArrayList<>();
            if (ci.outer != null) {
                ClassInfo self2 = ci;
                boolean selfItf = Boolean.TRUE.equals(self2.isInterface);
                innerInfos.add(InnerClassInfo.of(ClassDesc.ofInternalName(ci.name), java.util.Optional.of(ClassDesc.ofInternalName(ci.outer)), java.util.Optional.of(ci.name.substring(ci.name.lastIndexOf('$') + 1)),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | (selfItf ? ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT : 0)));
            }
            for (String inner : ci.inners) {
                ClassInfo ii = classes.get(inner);
                boolean innerItf = ii != null && Boolean.TRUE.equals(ii.isInterface);
                innerInfos.add(InnerClassInfo.of(ClassDesc.ofInternalName(inner), java.util.Optional.of(ClassDesc.ofInternalName(ci.name)), java.util.Optional.of(inner.substring(inner.lastIndexOf('$') + 1)),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | (innerItf ? ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT : 0)));
            }
            if (!innerInfos.isEmpty()) cb.with(InnerClassesAttribute.of(innerInfos));
            if (bare.contains(ci.name)) { ci.fields.clear(); ci.methods.keySet().removeIf(k -> !k.startsWith("<init>(")); }
            for (var e : ci.fields.entrySet()) {
                String[] nd = e.getKey().split(":", 2);
                int ff = e.getValue() | (itf ? ClassFile.ACC_STATIC | ClassFile.ACC_FINAL : 0);
                cb.withField(nd[0], ClassDesc.ofDescriptor(nd[1]), ff);
            }
            for (var e : ci.methods.entrySet()) {
                String key = e.getKey();
                int paren = key.indexOf('(');
                String name = key.substring(0, paren);
                MethodTypeDesc mtd = MethodTypeDesc.ofDescriptor(key.substring(paren));
                int mf = e.getValue();
                boolean isStatic = (mf & ClassFile.ACC_STATIC) != 0;
                if (name.equals("<init>")) { if (itf) continue; mf = ClassFile.ACC_PUBLIC; }
                if (itf && !isStatic) {
                    // default methods: compile-only stubs must never force implementors to override anything
                    cb.withMethodBody(name, mtd, ClassFile.ACC_PUBLIC, code -> { code.aconst_null(); code.athrow(); });
                } else {
                    final int fmf = mf;
                    cb.withMethodBody(name, mtd, fmf, code -> { code.aconst_null(); code.athrow(); });
                }
            }
            if (!itf && !ci.methods.keySet().stream().anyMatch(k -> k.startsWith("<init>("))) {
                // give classes a protected no-arg constructor so subclasses can compile if needed
                cb.withMethodBody("<init>", MethodTypeDesc.ofDescriptor("()V"), ClassFile.ACC_PUBLIC, code -> { code.aconst_null(); code.athrow(); });
            }
        });
    }
}
