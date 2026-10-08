import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Injects compile-only stubs for hidden framework methods used by Dialer
 * into the SDK android.jar.
 *
 * <p>Dialer is a platform app (Soong {@code sdk_version: system_current}) and
 * calls a few hidden APIs absent from the public SDK stubs. The standalone
 * Gradle CI build compiles against the stock android.jar, so those symbols
 * are missing there. This tool adds trivial bodies (never executed: the CI
 * APK links against the real framework at runtime) and skips any method
 * already present, making it idempotent across SDK levels.
 *
 * <p>Currently injected:
 * <ul>
 *   <li>{@code android.net.LinkProperties#isReachable(java.net.InetAddress)}
 *   <li>{@code android.app.Notification$Builder#setRequestPromotedOngoing(boolean)}
 * </ul>
 *
 * <p>Usage: {@code java -cp patcher.jar:asm.jar PatchHiddenApis
 * <path-to>/platforms/android-37/android.jar}
 */
public class PatchHiddenApis {

    private static final class Spec {
        final String internalClass;
        final String name;
        final String descriptor;
        final boolean returnsThis;

        Spec(String internalClass, String name, String descriptor, boolean returnsThis) {
            this.internalClass = internalClass;
            this.name = name;
            this.descriptor = descriptor;
            this.returnsThis = returnsThis;
        }
    }

    private static final Spec[] SPECS = {
        new Spec("android/net/LinkProperties",
                "isReachable", "(Ljava/net/InetAddress;)Z", false),
        new Spec("android/app/Notification$Builder",
                "setRequestPromotedOngoing", "(Z)Landroid/app/Notification$Builder;", true),
    };

    private static final class Adder extends ClassVisitor {
        private final Spec spec;
        private boolean found;

        Adder(ClassVisitor delegate, Spec spec) {
            super(Opcodes.ASM9, delegate);
            this.spec = spec;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            if (name.equals(spec.name) && descriptor.equals(spec.descriptor)) {
                found = true;
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }

        @Override
        public void visitEnd() {
            if (!found) {
                MethodVisitor mv = super.visitMethod(
                        Opcodes.ACC_PUBLIC, spec.name, spec.descriptor, null, null);
                mv.visitCode();
                if (spec.returnsThis) {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitInsn(Opcodes.ARETURN);
                } else {
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                }
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                System.out.println("injected " + spec.internalClass.replace('/', '.')
                        + "#" + spec.name + spec.descriptor);
            } else {
                System.out.println("present, skipped " + spec.internalClass.replace('/', '.')
                        + "#" + spec.name + spec.descriptor);
            }
            super.visitEnd();
        }
    }

    public static void main(String[] args) throws Exception {
        File jar = new File(args[0]);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile jarFile = new JarFile(jar)) {
            java.util.Enumeration<JarEntry> e = jarFile.entries();
            while (e.hasMoreElements()) {
                JarEntry entry = e.nextElement();
                try (InputStream in = jarFile.getInputStream(entry);
                        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                    entries.put(entry.getName(), out.toByteArray());
                }
            }
        }
        boolean changed = false;
        for (Spec spec : SPECS) {
            String path = spec.internalClass + ".class";
            byte[] original = entries.get(path);
            if (original == null) {
                throw new IllegalStateException("class not found in " + jar + ": " + path);
            }
            ClassReader reader = new ClassReader(original);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            Adder adder = new Adder(writer, spec);
            reader.accept(adder, 0);
            if (!adder.found) {
                entries.put(path, writer.toByteArray());
                changed = true;
            }
        }
        if (!changed) {
            System.out.println("android.jar already complete, nothing patched");
            return;
        }
        File tmp = new File(jar.getAbsolutePath() + ".patched");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(tmp))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        if (!jar.delete() || !tmp.renameTo(jar)) {
            throw new IllegalStateException("cannot replace " + jar);
        }
        System.out.println("patched " + jar);
    }
}
