package com.runespeak.build;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class SystemOutPatcher {
    static final int CP_UTF8 = 1, CP_INT = 3, CP_FLOAT = 4, CP_LONG = 5, CP_DOUBLE = 6;
    static final int CP_CLASS = 7, CP_STRING = 8, CP_FIELDREF = 9, CP_METHODREF = 10;
    static final int CP_IFACE_METHODREF = 11, CP_NAT = 12, CP_MH = 15, CP_MT = 16;
    static final int CP_DYNAMIC = 17, CP_INVOKE_DYNAMIC = 18, CP_MODULE = 19, CP_PACKAGE = 20;

    public static void patchJar(Path jarPath) throws IOException {
        Path tmp = jarPath.resolveSibling(jarPath.getFileName() + ".patched");
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(jarPath.toFile()));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(tmp.toFile()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                zout.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().endsWith(".class")) {
                    zout.write(patch(readAll(zin)));
                } else {
                    transfer(zin, zout);
                }
                zin.closeEntry();
                zout.closeEntry();
            }
        }
        Files.move(tmp, jarPath, StandardCopyOption.REPLACE_EXISTING);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        transfer(in, buf);
        return buf.toByteArray();
    }

    static void transfer(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }

    static byte[] patch(byte[] in) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(in));
        if (dis.readInt() != 0xCAFEBABE) return in;

        int minor = dis.readUnsignedShort(), major = dis.readUnsignedShort();
        int cpCount = dis.readUnsignedShort();

        List<byte[]> entries = new ArrayList<>();
        entries.add(null);
        for (int i = 1; i < cpCount; i++) {
            int tag = dis.readUnsignedByte();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(tag);
            switch (tag) {
                case CP_UTF8: {
                    int len = dis.readUnsignedShort();
                    byte[] b = new byte[len];
                    dis.readFully(b);
                    buf.write(len >> 8); buf.write(len); buf.write(b);
                    break;
                }
                case CP_INT: case CP_FLOAT: {
                    int v = dis.readInt();
                    buf.write(v >> 24); buf.write(v >> 16); buf.write(v >> 8); buf.write(v);
                    break;
                }
                case CP_LONG: case CP_DOUBLE: {
                    int h = dis.readInt(), l = dis.readInt();
                    buf.write(h >> 24); buf.write(h >> 16); buf.write(h >> 8); buf.write(h);
                    buf.write(l >> 24); buf.write(l >> 16); buf.write(l >> 8); buf.write(l);
                    i++; entries.add(null);
                    break;
                }
                case CP_CLASS: case CP_STRING: case CP_MT: case CP_MODULE: case CP_PACKAGE: {
                    int idx = dis.readUnsignedShort();
                    buf.write(idx >> 8); buf.write(idx);
                    break;
                }
                case CP_FIELDREF: case CP_METHODREF: case CP_IFACE_METHODREF: {
                    int ci = dis.readUnsignedShort(), ni = dis.readUnsignedShort();
                    buf.write(ci >> 8); buf.write(ci); buf.write(ni >> 8); buf.write(ni);
                    break;
                }
                case CP_NAT: {
                    int ni = dis.readUnsignedShort(), di = dis.readUnsignedShort();
                    buf.write(ni >> 8); buf.write(ni); buf.write(di >> 8); buf.write(di);
                    break;
                }
                case CP_MH: {
                    int k = dis.readUnsignedByte(), idx = dis.readUnsignedShort();
                    buf.write(k); buf.write(idx >> 8); buf.write(idx);
                    break;
                }
                case CP_DYNAMIC: case CP_INVOKE_DYNAMIC: {
                    int bi = dis.readUnsignedShort(), ni = dis.readUnsignedShort();
                    buf.write(bi >> 8); buf.write(bi); buf.write(ni >> 8); buf.write(ni);
                    break;
                }
                default: throw new IOException("Unknown CP tag: " + tag);
            }
            entries.add(buf.toByteArray());
        }

        byte[] rest = dis.readAllBytes();

        String[] utf8 = new String[entries.size()];
        String[] classNames = new String[entries.size()];
        for (int i = 1; i < entries.size(); i++) {
            byte[] d = entries.get(i);
            if (d == null) continue;
            int t = d[0] & 0xFF;
            if (t == CP_UTF8) {
                int len = (d[1] & 0xFF) << 8 | (d[2] & 0xFF);
                utf8[i] = new String(d, 3, len, "UTF-8");
            }
        }
        for (int i = 1; i < entries.size(); i++) {
            byte[] d = entries.get(i);
            if (d != null && (d[0] & 0xFF) == CP_CLASS) {
                int ni = (d[1] & 0xFF) << 8 | (d[2] & 0xFF);
                classNames[i] = ni < utf8.length ? utf8[ni] : null;
            }
        }

        boolean needsPatch = false;
        for (int i = 1; i < entries.size(); i++) {
            byte[] d = entries.get(i);
            if (d == null) continue;
            if ((d[0] & 0xFF) == CP_FIELDREF) {
                int ci = (d[1] & 0xFF) << 8 | (d[2] & 0xFF);
                int ni = (d[3] & 0xFF) << 8 | (d[4] & 0xFF);
                if (!"java/lang/System".equals(ci < classNames.length ? classNames[ci] : null)) continue;
                if (ni >= entries.size()) continue;
                byte[] nat = entries.get(ni);
                if (nat == null || (nat[0] & 0xFF) != CP_NAT) continue;
                int nameIdx = (nat[1] & 0xFF) << 8 | (nat[2] & 0xFF);
                String fn = nameIdx < utf8.length ? utf8[nameIdx] : null;
                if ("out".equals(fn) || "err".equals(fn) || "in".equals(fn)) needsPatch = true;
            }
        }

        if (!needsPatch) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(in.length);
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(0xCAFEBABE);
            dos.writeShort(minor); dos.writeShort(major);
            dos.writeShort(cpCount);
            for (int i = 1; i < cpCount; i++) {
                if (entries.get(i) != null) dos.write(entries.get(i));
            }
            dos.write(rest);
            return baos.toByteArray();
        }

        int noopUtf8 = -1;
        for (int i = 1; i < entries.size(); i++) {
            if ("com/runespeak/_NoOp".equals(utf8[i])) { noopUtf8 = i; break; }
        }
        if (noopUtf8 == -1) {
            byte[] b = "com/runespeak/_NoOp".getBytes("UTF-8");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(CP_UTF8); buf.write(b.length >> 8); buf.write(b.length); buf.write(b);
            entries.add(buf.toByteArray());
            noopUtf8 = entries.size() - 1;
        }

        int noopClass = -1;
        for (int i = 1; i < classNames.length; i++) {
            if ("com/runespeak/_NoOp".equals(classNames[i])) { noopClass = i; break; }
        }
        if (noopClass == -1) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(CP_CLASS); buf.write(noopUtf8 >> 8); buf.write(noopUtf8);
            entries.add(buf.toByteArray());
            noopClass = entries.size() - 1;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(in.length + 16);
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(0xCAFEBABE);
        dos.writeShort(minor); dos.writeShort(major);
        dos.writeShort(entries.size());

        for (int i = 1; i < entries.size(); i++) {
            byte[] d = entries.get(i);
            if (d == null) continue;
            if ((d[0] & 0xFF) == CP_FIELDREF) {
                int ci = (d[1] & 0xFF) << 8 | (d[2] & 0xFF);
                int ni = (d[3] & 0xFF) << 8 | (d[4] & 0xFF);
                if ("java/lang/System".equals(ci < classNames.length ? classNames[ci] : null)) {
                    if (ni < entries.size()) {
                        byte[] nat = entries.get(ni);
                        if (nat != null && (nat[0] & 0xFF) == CP_NAT) {
                            int nameIdx = (nat[1] & 0xFF) << 8 | (nat[2] & 0xFF);
                            String fn = nameIdx < utf8.length ? utf8[nameIdx] : null;
                            if ("out".equals(fn) || "err".equals(fn) || "in".equals(fn)) {
                                dos.write(d[0]);
                                dos.write(noopClass >> 8); dos.write(noopClass);
                                dos.write(d[3]); dos.write(d[4]);
                                continue;
                            }
                        }
                    }
                }
            }
            dos.write(d);
        }

        dos.write(rest);
        return baos.toByteArray();
    }
}
