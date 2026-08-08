import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * SEM 0.1.6 only has SimpleEnemyMod(FMLJavaModLoadingContext). Forge 47.2.0 on 1.20.1 only
 * constructs mods via a no-arg constructor. Add: public SimpleEnemyMod() { this(FMLJavaModLoadingContext.get()); }
 */
public class PatchSemCtor {
    private static final String TARGET = "net/nekoyuni/SimpleEnemyMod/SimpleEnemyMod.class";
    private static final String OWNER = "net/nekoyuni/SimpleEnemyMod/SimpleEnemyMod";
    private static final String CTX = "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext";
    private static final String CTX_DESC = "L" + CTX + ";";

    public static void main(String[] args) throws Exception {
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        try (JarFile jar = new JarFile(in.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out))) {
            jar.stream().forEach(entry -> {
                try {
                    ZipEntry outEntry = new ZipEntry(entry.getName());
                    jos.putNextEntry(outEntry);
                    try (InputStream is = jar.getInputStream(entry)) {
                        if (TARGET.equals(entry.getName())) {
                            jos.write(patch(is.readAllBytes()));
                        } else {
                            is.transferTo(jos);
                        }
                    }
                    jos.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        System.out.println("Patched " + in + " -> " + out);
    }

    private static byte[] patch(byte[] input) {
        ClassReader cr = new ClassReader(input);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            boolean hasNoArg = false;

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ("<init>".equals(name) && "()V".equals(descriptor)) {
                    hasNoArg = true;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                if (!hasNoArg) {
                    MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, CTX, "get", "()" + CTX_DESC, false);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, OWNER, "<init>", "(" + CTX_DESC + ")V", false);
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(2, 1);
                    mv.visitEnd();
                    System.out.println("Added no-arg constructor delegating to FMLJavaModLoadingContext.get()");
                } else {
                    System.out.println("No-arg constructor already present; left unchanged");
                }
                super.visitEnd();
            }
        }, 0);
        return cw.toByteArray();
    }
}
