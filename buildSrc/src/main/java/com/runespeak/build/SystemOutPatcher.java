package com.runespeak.build;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import org.objectweb.asm.*;

public class SystemOutPatcher {

    private static final String SYSTEM_CLASS = "java/lang/System";
    private static final String NOOP_CLASS = "com/runespeak/_NoOp";
    public static void patchJar(Path jarPath) throws IOException {
        Path tmp = jarPath.resolveSibling(jarPath.getFileName() + ".patched");
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(jarPath.toFile()));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(tmp.toFile()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                zout.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().endsWith(".class")) {
                    byte[] bytes = readAll(zin);
                    zout.write(transform(bytes));
                } else {
                    transfer(zin, zout);
                }
                zin.closeEntry();
                zout.closeEntry();
            }
        }
        Files.move(tmp, jarPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] transform(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        if (owner.equals(SYSTEM_CLASS)
                                && (name.equals("out") || name.equals("err") || name.equals("in"))) {
                            super.visitFieldInsn(opcode, NOOP_CLASS, name, descriptor);
                        } else {
                            super.visitFieldInsn(opcode, owner, name, descriptor);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        if (name.equals("printStackTrace")) {
                            int argsSize = Type.getArgumentsAndReturnSizes(descriptor) >> 2;
                            for (int i = 0; i < argsSize; i++) {
                                super.visitInsn(Opcodes.POP);
                            }
                            int pad = 3 - argsSize;
                            for (int i = 0; i < pad; i++) {
                                super.visitInsn(Opcodes.NOP);
                            }
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        transfer(in, buf);
        return buf.toByteArray();
    }

    private static void transfer(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
    }
}
