package com.attc.opencode;

import android.system.Os;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Minimal tar.gz extractor: ustar + GNU longname + pax, regular files,
 *  directories, symlinks and hardlinks. No external tar binary needed. */
public final class TarGz {

    private TarGz() {
    }

    public static void extract(File tgz, File dest) throws IOException {
        try (InputStream in = new GZIPInputStream(
                new BufferedInputStream(new FileInputStream(tgz), 1 << 16), 1 << 16)) {
            extract(in, dest);
        }
    }

    private static void extract(InputStream in, File dest) throws IOException {
        byte[] header = new byte[512];
        String longName = null;
        String longLink = null;
        Map<String, String> pax = new HashMap<>();

        while (true) {
            if (!readFully(in, header)) {
                return;
            }
            if (isZero(header)) {
                return;
            }
            String name = str(header, 0, 100);
            int mode = (int) octal(header, 100, 8);
            long size = octal(header, 124, 12);
            char type = (char) (header[156] & 0xff);
            String link = str(header, 157, 100);
            String prefix = str(header, 345, 155);

            if (type == 'L') {
                longName = readBody(in, size);
                continue;
            }
            if (type == 'K') {
                longLink = readBody(in, size);
                continue;
            }
            if (type == 'x' || type == 'X') {
                parsePax(readBody(in, size), pax);
                continue;
            }
            if (type == 'g') {
                skip(in, size);
                skipPadding(in, size);
                continue;
            }

            if (pax.containsKey("path")) {
                name = pax.get("path");
            } else if (longName != null) {
                name = longName;
            } else if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
            if (pax.containsKey("linkpath")) {
                link = pax.get("linkpath");
            } else if (longLink != null) {
                link = longLink;
            }
            longName = null;
            longLink = null;
            pax.clear();

            File out = safe(dest, name);
            if (out == null) {
                skip(in, size);
                continue;
            }

            if (type == '5') {
                //noinspection ResultOfMethodCallIgnored
                out.mkdirs();
                chmod(out, (mode & 0777) == 0 ? 0755 : mode & 0777);
            } else if (type == '2') {
                File parent = out.getParentFile();
                if (parent != null) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                try {
                    if (out.exists() || isSymlink(out)) {
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                    }
                    Os.symlink(link, out.getAbsolutePath());
                } catch (Exception e) {
                    // A missing link target is not fatal for our use.
                }
            } else if (type == '1') {
                File target = safe(dest, link);
                if (target != null && target.exists()) {
                    try {
                        if (out.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            out.delete();
                        }
                        Os.link(target.getAbsolutePath(), out.getAbsolutePath());
                    } catch (Exception e) {
                        copy(target, out);
                    }
                }
                skip(in, size);
            } else if (type == '0' || type == '\0' || type == '7') {
                File parent = out.getParentFile();
                if (parent != null) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                copyN(in, out, size);
                chmod(out, (mode & 0777) == 0 ? 0644 : mode & 0777);
            } else {
                skip(in, size);
            }

            skipPadding(in, size);
        }
    }

    // --------------------------------------------------------------- helpers

    private static void chmod(File f, int mode) {
        try {
            Os.chmod(f.getAbsolutePath(), mode);
        } catch (Exception ignored) {
            //noinspection ResultOfMethodCallIgnored
            f.setExecutable((mode & 0111) != 0, false);
        }
    }

    private static File safe(File dest, String name) throws IOException {
        String clean = name.replace('\\', '/');
        while (clean.startsWith("./")) {
            clean = clean.substring(2);
        }
        if (clean.startsWith("/") || clean.contains("..")) {
            return null;
        }
        File f = new File(dest, clean);
        String root = dest.getCanonicalPath();
        if (!f.getCanonicalPath().startsWith(root)) {
            return null;
        }
        return f;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off).trim();
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xff;
            if (c == 0 || c == ' ') {
                continue;
            }
            if (c < '0' || c > '7') {
                return v;
            }
            v = (v << 3) + (c - '0');
        }
        return v;
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSymlink(File f) {
        try {
            return !f.getCanonicalPath().equals(f.getAbsolutePath());
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return false;
            }
            off += n;
        }
        return true;
    }

    private static String readBody(InputStream in, long size) throws IOException {
        byte[] body = new byte[(int) size];
        int off = 0;
        while (off < body.length) {
            int n = in.read(body, off, body.length - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        skipPadding(in, size);
        return new String(body, 0, off, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void parsePax(String body, Map<String, String> out) {
        int i = 0;
        while (i < body.length()) {
            int space = body.indexOf(' ', i);
            if (space < 0) {
                return;
            }
            int len;
            try {
                len = Integer.parseInt(body.substring(i, space));
            } catch (NumberFormatException e) {
                return;
            }
            if (len <= 0 || i + len > body.length()) {
                return;
            }
            String record = body.substring(space + 1, i + len).trim();
            int eq = record.indexOf('=');
            if (eq > 0) {
                out.put(record.substring(0, eq), record.substring(eq + 1));
            }
            i += len;
        }
    }

    private static void copyN(InputStream in, File out, long size) throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            long left = size;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    break;
                }
                os.write(buf, 0, n);
                left -= n;
            }
        }
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from);
             OutputStream os = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
    }

    private static void skip(InputStream in, long size) throws IOException {
        long left = size;
        while (left > 0) {
            long n = in.skip(left);
            if (n <= 0) {
                if (in.read() < 0) {
                    return;
                }
                n = 1;
            }
            left -= n;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        skip(in, pad);
    }
}
