import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.constantpool.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Scans jars (recursively including nested jars) and writes two tables:
 *  defined.tsv : C <class> <flags> <super> <ifaces,...>   /  F|M <class> <name> <desc> <flags>
 *  refs.tsv    : <kind> <owner> <name> <desc> <source-jar>   (kind = invokestatic, invokevirtual, ..., getfield, ...)
 * Used to build a "known API surface" for classes we cannot download (Minecraft),
 * from the bytecode of mods that were compiled against the real jars.
 */
public class SurfaceScan {
    static PrintWriter defined, refs;
    static Set<String> seenRefs = new HashSet<>();

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        defined = new PrintWriter(Files.newBufferedWriter(out.resolve("defined.tsv")));
        refs = new PrintWriter(Files.newBufferedWriter(out.resolve("refs.tsv")));
        for (int i = 1; i < args.length; i++) {
            byte[] bytes = Files.readAllBytes(Path.of(args[i]));
            scanJar(Path.of(args[i]).getFileName().toString(), bytes);
        }
        defined.close();
        refs.close();
    }

    static void scanJar(String name, byte[] bytes) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                if (n.endsWith(".class") && !n.startsWith("META-INF/versions/")) {
                    byte[] cls = zin.readAllBytes();
                    try { scanClass(name, cls); } catch (Throwable t) { System.err.println("skip " + n + " in " + name + ": " + t); }
                } else if (n.endsWith(".jar")) {
                    scanJar(name + "!" + n, zin.readAllBytes());
                }
            }
        }
    }

    static String cn(ClassEntry c) { return c.asInternalName(); }

    static void scanClass(String jar, byte[] bytes) {
        ClassModel cm = ClassFile.of().parse(bytes);
        String self = cm.thisClass().asInternalName();
        StringBuilder ifs = new StringBuilder();
        for (ClassEntry i : cm.interfaces()) { if (ifs.length() > 0) ifs.append(','); ifs.append(i.asInternalName()); }
        defined.println("C\t" + self + "\t" + cm.flags().flagsMask() + "\t" + cm.superclass().map(SurfaceScan::cn).orElse("-") + "\t" + ifs + "\t" + jar);
        for (FieldModel f : cm.fields()) defined.println("F\t" + self + "\t" + f.fieldName().stringValue() + "\t" + f.fieldType().stringValue() + "\t" + f.flags().flagsMask());
        for (MethodModel m : cm.methods()) {
            defined.println("M\t" + self + "\t" + m.methodName().stringValue() + "\t" + m.methodType().stringValue() + "\t" + m.flags().flagsMask());
            m.code().ifPresent(code -> {
                for (CodeElement el : code) {
                    if (el instanceof InvokeInstruction ii) {
                        ref(ii.opcode().name().toLowerCase(), ii.owner().asInternalName(), ii.name().stringValue(), ii.type().stringValue(), jar, ii.isInterface());
                    } else if (el instanceof FieldInstruction fi) {
                        ref(fi.opcode().name().toLowerCase(), fi.owner().asInternalName(), fi.name().stringValue(), fi.type().stringValue(), jar, false);
                    } else if (el instanceof TypeCheckInstruction tc) {
                        ref("type", tc.type().asInternalName(), "-", "-", jar, false);
                    } else if (el instanceof NewObjectInstruction no) {
                        ref("new", no.className().asInternalName(), "-", "-", jar, false);
                    } else if (el instanceof InvokeDynamicInstruction indy) {
                        // record method handles in bootstrap args (lambdas / method refs)
                        for (ConstantDesc arg : indy.bootstrapArgs()) {
                            if (arg instanceof DirectMethodHandleDesc mh) {
                                String owner = mh.owner().descriptorString();
                                owner = owner.substring(1, owner.length() - 1);
                                ref("mh_" + mh.kind().name().toLowerCase(), owner, mh.methodName(), mh.lookupDescriptor(), jar, mh.isOwnerInterface());
                            }
                        }
                    }
                }
            });
        }
    }

    static void ref(String kind, String owner, String name, String desc, String jar, boolean itf) {
        String key = kind + "\t" + owner + "\t" + name + "\t" + desc + "\t" + itf;
        if (seenRefs.add(key)) refs.println(key + "\t" + jar);
    }
}
