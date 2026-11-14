package com.attc.opencode;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Replaces the left half of opencode's block-art logo ("open") with "ATTC".
 *
 * The compiled Bun binary keeps the art twice: as escaped text in the embedded
 * JS source and as decoded UTF-16LE strings in the bytecode string table the
 * TUI actually renders. Only the bytecode table is used at runtime, so only
 * that copy is patched here. Each row stays exactly 19 characters (38 bytes),
 * which keeps the string table offsets intact: the three rows sit 48 bytes
 * apart, and nothing is ever shifted.
 *
 * Four generations are recognised so an already-branded binary (1.4, 1.5) and
 * a fresh upstream binary can all be upgraded to the current artwork:
 *   - OPEN  the upstream "open" word,
 *   - V14   the 1.4 artwork (closed T feet),
 *   - V15   the 1.5 artwork (I-beam T),
 *   - ATTC  the current artwork.
 *
 * The patch is idempotent: a sidecar marker records a version, the binary size
 * and mtime, and content checks keep it safe when the user is already branded
 * or when a future release changes the art.
 */
public final class Branding {

    /**
     * Logo rows 1-3 as they are rendered by opencode. The word is 19 cells
     * wide: A is 4 cells, T/T/C are 3 cells, with two spaces between letters
     * (4 + 2 + 3 + 2 + 3 + 2 + 3 = 19). Only full blocks and half blocks are
     * used, so the background stays black; {@code _} is not used because the
     * TUI paints it as a visible dark shim. Each glyph is a 4x5 (A) or 3x5
     * half-block bitmap with the same one-cell stroke as "code":
     *
     *   A          T          T          C
     *   .##.       ###        ###        ###
     *   #..#       .#.        .#.        #..
     *   ####       .#.        .#.        #..
     *   #..#       .#.        .#.        #..
     *   #..#       .#.        .#.        ###
     *   ....       ...        ...        ...
     */
    private static final String[] ATTC = {
            "\u2584\u2580\u2580\u2584  \u2580\u2588\u2580  "
                    + "\u2580\u2588\u2580 \u2588\u2580\u2580\u2580",
            "\u2588\u2580\u2580\u2588   \u2588    \u2588  \u2588   ",
            "\u2580  \u2580   \u2580    \u2580  \u2580\u2580\u2580\u2580"
    };

    /** 3.4 artwork (long C, bottom bar below the baseline), kept for upgrades. */
    private static final String[] V34 = {
            "\u2584\u2580\u2580\u2584  \u2580\u2588\u2580  "
                    + "\u2580\u2588\u2580  \u2588\u2580\u2580",
            "\u2588\u2580\u2580\u2588   \u2588    \u2588   \u2588  ",
            "\u2580  \u2580   \u2580    \u2580   \u2588\u2584\u2584"
    };

    /** 3.1 artwork (thick C bars), kept so upgrades re-patch correctly. */
    private static final String[] V31 = {
            "\u2584\u2580\u2580\u2584  \u2580\u2588\u2580  "
                    + "\u2580\u2588\u2580  \u2588\u2588\u2588",
            "\u2588\u2580\u2580\u2588   \u2588    \u2588   \u2588\u2584\u2584",
            "\u2580  \u2580   \u2580    \u2580   \u2580\u2580\u2580"
    };

    /** 3.0 artwork (thin C bars), kept so upgrades re-patch correctly. */
    private static final String[] V18 = {
            "\u2584\u2580\u2580\u2584  \u2580\u2588\u2580  "
                    + "\u2580\u2588\u2580  \u2588\u2580\u2580",
            "\u2588\u2580\u2580\u2588   \u2588    \u2588   \u2588  ",
            "\u2580  \u2580   \u2580    \u2580   \u2580\u2580\u2580"
    };

    /** 1.7 artwork (thin T stems, dark shims), kept for upgrades. */
    private static final String[] V17 = {
            "\u2584\u2580\u2580\u2584  \u2580\u2588\u2580  "
                    + "\u2580\u2588\u2580  \u2584\u2580\u2580",
            "\u2588\u2584\u2584\u2588  _\u2588_  _\u2588_  \u2588__",
            "\u2580__\u2580  _\u2580_  _\u2580_  \u2580\u2580\u2580"
    };

    /** 1.6 artwork (2-cell T stems), kept for upgrades. */
    private static final String[] V16 = {
            "\u2584\u2580\u2580\u2584 \u2580\u2588\u2588\u2580 "
                    + "\u2580\u2588\u2588\u2580 \u2584\u2580\u2580\u2580",
            "\u2588\u2584\u2584\u2588 _\u2588\u2588_ _\u2588\u2588_ \u2588___",
            "\u2580__\u2580 _\u2580\u2580_ _\u2580\u2580_ \u2580\u2580\u2580\u2580"
    };

    /** Upstream "open" artwork. */
    private static final String[] OPEN = {
            "\u2588\u2580\u2580\u2588 \u2588\u2580\u2580\u2588 "
                    + "\u2588\u2580\u2580\u2588 \u2588\u2580\u2580\u2584",
            "\u2588__\u2588 \u2588__\u2588 \u2588^^^ \u2588__\u2588",
            "\u2580\u2580\u2580\u2580 \u2588\u2580\u2580\u2580 "
                    + "\u2580\u2580\u2580\u2580 \u2580~~\u2580"
    };

    /** 1.4 artwork, kept so an already-branded binary can be upgraded. */
    private static final String[] V14 = {
            "\u2588\u2580\u2580\u2588 \u2580\u2580\u2580\u2580 "
                    + "\u2580\u2580\u2580\u2580 \u2588\u2580\u2580\u2580",
            "\u2588\u2580\u2580\u2588 _\u2588__ _\u2588__ \u2588___",
            "\u2580\u2580\u2580\u2580 _\u2588\u2588\u2580 _\u2588\u2588\u2580 "
                    + "\u2580\u2580\u2580\u2580"
    };

    /** 1.5 artwork (T feet cleaned up). */
    private static final String[] V15 = {
            "\u2588\u2580\u2580\u2588 \u2580\u2580\u2580\u2580 "
                    + "\u2580\u2580\u2580\u2580 \u2588\u2580\u2580\u2580",
            "\u2588\u2580\u2580\u2588 _\u2588__ _\u2588__ \u2588___",
            "\u2580\u2580\u2580\u2580 \u2580\u2580\u2580^ \u2580\u2580\u2580^ "
                    + "\u2580\u2580\u2580\u2580"
    };

    /** Bump when the artwork changes so old markers are re-checked. */
    private static final String SIGNATURE_VERSION = "11";

    /** Distance between consecutive UTF-16 logo rows in the string table. */
    private static final int ROW_STRIDE = 48;

    private static final String MARKER_FILE = ".attc-brand";

    private Branding() {
    }

    /**
     * Patches the binary if needed. Never throws: a branding failure must not
     * stop opencode from starting.
     */
    public static void apply(File binary) {
        if (binary == null || !binary.isFile() || binary.length() < 1024) {
            return;
        }
        try {
            if (upToDate(binary)) {
                return;
            }
            String result = patch(binary);
            Logger.i("branding: " + result);
        } catch (Exception e) {
            Logger.i("branding: failed " + e);
        }
    }

    // --------------------------------------------------------------- patching

    private static String patch(File binary) throws IOException {
        byte[][] attc = pack(ATTC);
        byte[][] open = pack(OPEN);
        byte[][] v14 = pack(V14);
        byte[][] v15 = pack(V15);
        byte[][] v16 = pack(V16);
        byte[][] v17 = pack(V17);
        byte[][] v18 = pack(V18);
        byte[][] v31 = pack(V31);
        byte[][] v34 = pack(V34);

        try (RandomAccessFile raf = new RandomAccessFile(binary, "rw")) {
            long length = raf.length();

            long[] row1 = findFirst(raf, length, new byte[][]{
                    open[0], v14[0], v15[0], v16[0], v17[0], v18[0], v31[0], v34[0], attc[0]
            });
            long base = -1;
            for (long hit : row1) {
                if (hit >= 0) {
                    base = hit;
                    break;
                }
            }
            int ok = 0;
            boolean missing = false;
            for (int row = 0; row < 3; row++) {
                long at = base >= 0 ? base + (long) row * ROW_STRIDE : -1;
                if (at < 0 || at + attc[row].length > length) {
                    missing = true;
                    continue;
                }
                byte[] current = new byte[attc[row].length];
                raf.seek(at);
                raf.readFully(current);
                if (same(current, attc[row])) {
                    ok++;
                } else if (same(current, open[row])
                        || same(current, v14[row])
                        || same(current, v15[row])
                        || same(current, v16[row])
                        || same(current, v17[row])
                        || same(current, v18[row])
                        || same(current, v31[row])
                        || same(current, v34[row])) {
                    raf.seek(at);
                    raf.write(attc[row]);
                    ok++;
                } else {
                    missing = true;
                }
            }

            if (raf.length() != length) {
                return "skipped (length changed)";
            }
            if (ok != 3 || missing) {
                // Never record a partial patch: the marker would hide the
                // mismatch on the next start.
                forget(binary);
                return "utf16=" + ok + "/3 (incomplete)";
            }
            remember(binary);
            return "utf16=" + ok + "/3 applied";
        }
    }

    private static boolean same(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ file scan

    /** Returns the first offset of each needle, or -1. Stops early when all are found. */
    private static long[] findFirst(RandomAccessFile raf, long length, byte[][] needles)
            throws IOException {
        long[] found = new long[needles.length];
        for (int i = 0; i < found.length; i++) {
            found[i] = -1;
        }
        int maxLen = 1;
        for (byte[] needle : needles) {
            maxLen = Math.max(maxLen, needle.length);
        }
        int overlap = maxLen - 1;
        byte[] buf = new byte[1 << 20];
        long pos = 0;
        while (pos < length) {
            int want = (int) Math.min(buf.length, length - pos);
            raf.seek(pos);
            raf.readFully(buf, 0, want);
            boolean all = true;
            for (int i = 0; i < needles.length; i++) {
                if (found[i] < 0) {
                    int idx = indexOf(buf, want, needles[i]);
                    if (idx >= 0) {
                        found[i] = pos + idx;
                    } else {
                        all = false;
                    }
                }
            }
            if (all || want <= overlap) {
                break;
            }
            pos += want - overlap;
        }
        return found;
    }

    private static int indexOf(byte[] haystack, int haystackLen, byte[] needle) {
        byte first = needle[0];
        int last = haystackLen - needle.length;
        for (int i = 0; i <= last; i++) {
            if (haystack[i] != first) {
                continue;
            }
            boolean match = true;
            for (int j = 1; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------- markers

    private static void forget(File binary) {
        //noinspection ResultOfMethodCallIgnored
        marker(binary).delete();
    }

    private static boolean upToDate(File binary) {
        File marker = marker(binary);
        if (!marker.isFile()) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(marker, "r")) {
            if (raf.length() > 128) {
                return false;
            }
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.ISO_8859_1)
                    .trim().equals(signature(binary));
        } catch (Exception e) {
            return false;
        }
    }

    private static void remember(File binary) {
        try (RandomAccessFile raf = new RandomAccessFile(marker(binary), "rw")) {
            raf.setLength(0);
            raf.write(signature(binary).getBytes(StandardCharsets.ISO_8859_1));
            raf.write('\n');
        } catch (Exception ignored) {
        }
    }

    private static String signature(File binary) {
        return SIGNATURE_VERSION + ":" + binary.length() + ":" + binary.lastModified();
    }

    private static File marker(File binary) {
        File parent = binary.getParentFile();
        return parent == null ? new File(MARKER_FILE) : new File(parent, MARKER_FILE);
    }

    // ------------------------------------------------------------ encoding

    private static byte[][] pack(String[] rows) {
        byte[][] out = new byte[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            out[i] = rows[i].getBytes(StandardCharsets.UTF_16LE);
        }
        return out;
    }
}
