import com.attc.opencode.TerminalBuffer;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Off-device regression checks for the VT screen model.
 *
 * <p>{@link com.attc.opencode.TerminalBuffer} has no Android dependencies, so
 * it runs on a normal JVM. These checks pin down visible-row addressing after
 * scrollback exists, DECSTBM scroll regions, insert/delete line inside a
 * region and the alternate screen.</p>
 *
 * <pre>
 * javac -source 8 -target 8 -cp "$ANDROID_JAR" \
 *     -d /tmp/tbtest tools/TerminalBufferTest.java \
 *     app/src/com/attc/opencode/TerminalBuffer.java
 * java -cp "/tmp/tbtest:$ANDROID_JAR" TerminalBufferTest
 * </pre>
 */
public final class TerminalBufferTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        testCupAfterScrollback();
        testScrollRegion();
        testScrollCommands();
        testInsertDeleteLines();
        testAltScreen();

        if (failures == 0) {
            System.out.println("ALL TERMINAL BUFFER TESTS PASSED");
        } else {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
    }

    // ----------------------------------------------------------------- tests

    /**
     * After the screen scrolled, CUP must address the visible row at the
     * bottom of the viewport, not an early row of the scrollback list.
     */
    private static void testCupAfterScrollback() throws Exception {
        TerminalBuffer b = new TerminalBuffer(5, 20);
        for (int i = 0; i < 12; i++) {
            feed(b, "L" + i + "\r\n");
        }
        check("12 lines leave 8 rows of scrollback", b.historySize() == 8);

        feed(b, "\u001b[5;1HXXXX");
        check("CUP row 5 writes the visible last row",
                b.text(4, 0, 4, 19, 0).startsWith("XXXX"));
        check("CUP row 5 writes no other visible row",
                !b.text(0, 0, 3, 19, 0).contains("XXXX"));
    }

    /**
     * DECSTBM 2;4 keeps rows 1 and 5 untouched while lines scroll inside the
     * region. Rows above the region must not scroll into scrollback.
     */
    private static void testScrollRegion() throws Exception {
        TerminalBuffer b = new TerminalBuffer(5, 20);
        feed(b, "\u001b[2;4r\u001b[H");
        feed(b, "A\r\nB\r\nC\r\nD\r\nE\r\nF\r\n");

        check("region leaves the row above untouched",
                "A".equals(b.text(0, 0, 0, 19, 0)));
        check("region has scrolled to E", "E".equals(b.text(1, 0, 1, 19, 0)));
        check("region has scrolled to F", "F".equals(b.text(2, 0, 2, 19, 0)));
        check("region bottom is blank", b.text(3, 0, 3, 19, 0).isEmpty());
        check("row below the region is untouched",
                b.text(4, 0, 4, 19, 0).isEmpty());
        check("scroll region adds no scrollback", b.historySize() == 0);
    }

    /** SU/SD scroll only the active region on the normal screen. */
    private static void testScrollCommands() throws Exception {
        TerminalBuffer b = new TerminalBuffer(5, 20);
        feed(b, "S0\r\nS1\r\nS2\r\nS3\r\nS4");
        int total = lineCount(b);

        feed(b, "\u001b[2;4r\u001b[S");
        check("SU keeps the row above the region",
                "S0".equals(b.text(0, 0, 0, 19, 0)));
        check("SU pulls the region up",
                "S2".equals(b.text(1, 0, 1, 19, 0))
                        && "S3".equals(b.text(2, 0, 2, 19, 0)));
        check("SU blanks the region bottom", b.text(3, 0, 3, 19, 0).isEmpty());
        check("SU keeps the row below the region",
                "S4".equals(b.text(4, 0, 4, 19, 0)));
        check("SU keeps the line count", lineCount(b) == total);

        feed(b, "\u001b[T");
        check("SD blanks the region top", b.text(1, 0, 1, 19, 0).isEmpty());
        check("SD pushes the region down",
                "S2".equals(b.text(2, 0, 2, 19, 0))
                        && "S3".equals(b.text(3, 0, 3, 19, 0)));
        check("SD keeps the rows outside the region",
                "S0".equals(b.text(0, 0, 0, 19, 0))
                        && "S4".equals(b.text(4, 0, 4, 19, 0)));
        check("SD keeps the line count", lineCount(b) == total);
    }

    /**
     * IL and DL only move rows between the cursor and the region bottom. The
     * number of lines in the buffer, and the rows outside the region, stay
     * exactly the same.
     */
    private static void testInsertDeleteLines() throws Exception {
        TerminalBuffer b = new TerminalBuffer(5, 20);
        feed(b, "R0\r\nR1\r\nR2\r\nR3\r\nR4");
        int total = lineCount(b);
        check("full screen starts without scrollback", b.historySize() == 0);

        feed(b, "\u001b[2;4r\u001b[2;1H\u001b[L");
        check("IL leaves the row above the region",
                "R0".equals(b.text(0, 0, 0, 19, 0)));
        check("IL opens a blank line at the cursor",
                b.text(1, 0, 1, 19, 0).isEmpty());
        check("IL pulls the region up",
                "R1".equals(b.text(2, 0, 2, 19, 0))
                        && "R2".equals(b.text(3, 0, 3, 19, 0)));
        check("IL leaves the row below the region",
                "R4".equals(b.text(4, 0, 4, 19, 0)));
        check("IL keeps the line count", lineCount(b) == total);

        feed(b, "\u001b[M");
        check("DL pulls the region up",
                "R1".equals(b.text(1, 0, 1, 19, 0))
                        && "R2".equals(b.text(2, 0, 2, 19, 0)));
        check("DL blanks the region bottom",
                b.text(3, 0, 3, 19, 0).isEmpty());
        check("DL leaves the row below the region",
                "R4".equals(b.text(4, 0, 4, 19, 0)));
        check("DL keeps the line count", lineCount(b) == total);

        // ESC M is reverse index, not delete-line: at the region top it
        // scrolls the region down and drops its bottom line.
        feed(b, "\u001b[2;1H\u001bM");
        check("RI blanks the region top", b.text(1, 0, 1, 19, 0).isEmpty());
        check("RI scrolls the region down",
                "R1".equals(b.text(2, 0, 2, 19, 0))
                        && "R2".equals(b.text(3, 0, 3, 19, 0)));
        check("RI leaves the row below the region",
                "R4".equals(b.text(4, 0, 4, 19, 0)));
        check("RI keeps the line count", lineCount(b) == total);
        check("RI adds no scrollback", b.historySize() == 0);
    }

    /** Writes on the alternate screen address alt rows and never scroll history. */
    private static void testAltScreen() throws Exception {
        TerminalBuffer b = new TerminalBuffer(5, 20);
        feed(b, "MAIN");
        feed(b, "\u001b[?1049h");
        feed(b, "\u001b[2;3HALT");

        check("alt write lands on the alt row",
                "ALT".equals(b.text(1, 2, 1, 4, 0)));
        check("alt screen has no scrollback", b.historySize() == 0);

        feed(b, "\u001b[?1049l");
        check("main screen is restored",
                "MAIN".equals(b.text(0, 0, 0, 3, 0)));
        check("main screen has no new scrollback", b.historySize() == 0);
    }

    // --------------------------------------------------------------- helpers

    private static void feed(TerminalBuffer b, String s) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        b.feed(data, data.length);
    }

    /** Total lines in the scrollback list (visible rows included). */
    private static int lineCount(TerminalBuffer b) throws Exception {
        Field f = TerminalBuffer.class.getDeclaredField("lines");
        f.setAccessible(true);
        return ((List<?>) f.get(b)).size();
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        } else {
            System.out.println("ok   " + name);
        }
    }
}
