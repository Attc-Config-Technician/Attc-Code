package com.attc.opencode;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * A small VT100/xterm screen model: grid, scrollback, cursor, SGR colours.
 *
 * Deliberately not a complete emulator. It covers what a shell and a TUI like
 * opencode actually emit: CUP/CUU/CUD/CUF/CUB, ED/EL, IL/DL, ICH/DCH/ECH,
 * SGR (16/256/truecolour), DEC private modes 25 and 1049, DECSTBM scroll
 * regions, reverse index and scrolling.
 */
public final class TerminalBuffer {

    public static final int DEFAULT_FG = 0xFFE6EDF3;
    public static final int DEFAULT_BG = 0xFF0B0F14;
    private static final int MAX_LINES = 5000;

    public static final class Line {
        public final char[] ch;
        public final int[] fg;
        public final int[] bg;

        Line(int cols) {
            ch = new char[cols];
            fg = new int[cols];
            bg = new int[cols];
            clear(0, cols);
        }

        void clear(int from, int to) {
            for (int i = from; i < to && i < ch.length; i++) {
                ch[i] = ' ';
                fg[i] = DEFAULT_FG;
                bg[i] = DEFAULT_BG;
            }
        }

        void copyFrom(Line other, int n) {
            System.arraycopy(other.ch, 0, ch, 0, Math.min(n, Math.min(ch.length, other.ch.length)));
            System.arraycopy(other.fg, 0, fg, 0, Math.min(n, Math.min(fg.length, other.fg.length)));
            System.arraycopy(other.bg, 0, bg, 0, Math.min(n, Math.min(bg.length, other.bg.length)));
        }
    }

    private int cols;
    private int rows;

    private final ArrayList<Line> lines = new ArrayList<>();
    private final ArrayList<Line> alt = new ArrayList<>();
    private boolean altActive;

    private int cx;
    private int cy;
    private boolean wrapPending;
    private boolean cursorVisible = true;
    private boolean mouseMode;
    private boolean mouseSgr;

    // DECSTBM scroll region, in visible row coordinates and inclusive.
    private int scrollTop;
    private int scrollBottom;

    private int fg = DEFAULT_FG;
    private int bg = DEFAULT_BG;
    private boolean bold;
    private boolean inverse;

    // Most recently shown cursor cell. opencode's TUI hides the hardware
    // cursor while it repaints and shows it again on the input caret at the
    // end of the frame; latching the shown cell keeps a steady caret instead
    // of a flickering one.
    private int shownX = -1;
    private int shownY = -1;

    /** Events the terminal emits for sounds and notifications. */
    public interface Listener {
        /** A real terminal bell arrived (a ground BEL, not an OSC terminator). */
        void onBell();

        /** opencode reported blocked (attention) or working -> idle (done). */
        void onAttention(boolean blocked);
    }

    private volatile Listener listener;
    private final StringBuilder osc = new StringBuilder();
    private String programState = "";

    private int savedX;
    private int savedY;

    // parser
    private static final int GROUND = 0;
    private static final int ESC = 1;
    private static final int CSI = 2;
    private static final int OSC = 3;
    private int state = GROUND;
    private final StringBuilder csi = new StringBuilder();

    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private ByteBuffer pending = ByteBuffer.allocate(8192);

    public TerminalBuffer(int rows, int cols) {
        this.rows = Math.max(2, rows);
        this.cols = Math.max(8, cols);
        reset();
    }

    public synchronized void reset() {
        lines.clear();
        for (int i = 0; i < rows; i++) {
            lines.add(new Line(cols));
        }
        alt.clear();
        for (int i = 0; i < rows; i++) {
            alt.add(new Line(cols));
        }
        altActive = false;
        scrollTop = 0;
        scrollBottom = rows - 1;
        cx = 0;
        cy = 0;
        wrapPending = false;
        cursorVisible = true;
        mouseMode = false;
        mouseSgr = false;
        fg = DEFAULT_FG;
        bg = DEFAULT_BG;
        bold = false;
        inverse = false;
        shownX = -1;
        shownY = -1;
        state = GROUND;
        csi.setLength(0);
        osc.setLength(0);
        programState = "";
    }

    public synchronized int rows() {
        return rows;
    }

    public synchronized int cols() {
        return cols;
    }

    public synchronized int cursorRow() {
        return cy;
    }

    public synchronized int cursorCol() {
        return cx;
    }

    /** Row to paint the caret on; in the TUI this is the last shown cell. */
    public synchronized int cursorDrawRow() {
        return altActive && shownY >= 0 ? shownY : cy;
    }

    /** Column to paint the caret on; in the TUI this is the last shown cell. */
    public synchronized int cursorDrawCol() {
        return altActive && shownX >= 0 ? shownX : cx;
    }

    public synchronized boolean cursorVisible() {
        return cursorVisible;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** True while the running program asked for mouse reporting. */
    public synchronized boolean mouseEnabled() {
        return mouseMode;
    }

    /** True when mouse coordinates use the SGR (1006) encoding. */
    public synchronized boolean mouseSgr() {
        return mouseSgr;
    }

    /** Number of lines above the viewport that can be scrolled back to. */
    public synchronized int historySize() {
        return altActive ? 0 : Math.max(0, lines.size() - rows);
    }

    /** Visible row 0..rows-1, offset lines up from the bottom. */
    public synchronized Line lineAt(int visibleRow, int scroll) {
        if (altActive) {
            return alt.get(clamp(visibleRow, 0, alt.size() - 1));
        }
        int base = lines.size() - rows - scroll + visibleRow;
        if (base < 0 || base >= lines.size()) {
            return null;
        }
        return lines.get(base);
    }

    /**
     * Copies the characters between two cells in visible coordinates.
     * Rows are inclusive; the order of the endpoints does not matter.
     */
    public synchronized String text(int row1, int col1, int row2, int col2, int scroll) {
        int r1;
        int c1;
        int r2;
        int c2;
        if (row1 < row2 || (row1 == row2 && col1 <= col2)) {
            r1 = row1;
            c1 = col1;
            r2 = row2;
            c2 = col2;
        } else {
            r1 = row2;
            c1 = col2;
            r2 = row1;
            c2 = col1;
        }
        r1 = clamp(r1, 0, rows - 1);
        r2 = clamp(r2, 0, rows - 1);
        StringBuilder sb = new StringBuilder();
        for (int r = r1; r <= r2; r++) {
            Line line = lineAt(r, scroll);
            if (line == null) {
                continue;
            }
            int from = clamp(r == r1 ? c1 : 0, 0, cols - 1);
            int to = clamp(r == r2 ? c2 : cols - 1, 0, cols - 1);
            StringBuilder rowText = new StringBuilder();
            for (int c = from; c <= to && c < line.ch.length; c++) {
                char ch = line.ch[c];
                rowText.append(ch == 0 ? ' ' : ch);
            }
            int len = rowText.length();
            while (len > 0 && rowText.charAt(len - 1) == ' ') {
                len--;
            }
            sb.append(rowText, 0, len);
            if (r < r2) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public synchronized void resize(int newRows, int newCols) {
        newRows = Math.max(2, newRows);
        newCols = Math.max(8, newCols);
        if (newRows == rows && newCols == cols) {
            return;
        }
        // Keep the top of the visible screen anchored while the window changes
        // size. The old model pinned the viewport to the last `rows` lines, so
        // closing the soft keyboard (shrink) pushed the live screen into
        // scrollback and left the display blank. TUIs that only repaint dirty
        // cells, such as Kimi Code, never recovered from that.
        int base = Math.max(0, lines.size() - rows);
        int target = base + newRows;
        int start = Math.max(0, target - MAX_LINES);
        int limit = Math.min(target, lines.size());
        ArrayList<Line> rebuilt = new ArrayList<>();
        for (int i = start; i < limit; i++) {
            Line fresh = new Line(newCols);
            fresh.copyFrom(lines.get(i), Math.min(newCols, cols));
            rebuilt.add(fresh);
        }
        while (rebuilt.size() < target - start) {
            rebuilt.add(new Line(newCols));
        }
        lines.clear();
        lines.addAll(rebuilt);

        ArrayList<Line> newAlt = new ArrayList<>();
        for (int i = 0; i < newRows; i++) {
            Line fresh = new Line(newCols);
            if (i < alt.size()) {
                fresh.copyFrom(alt.get(i), Math.min(newCols, cols));
            }
            newAlt.add(fresh);
        }
        alt.clear();
        alt.addAll(newAlt);

        rows = newRows;
        cols = newCols;
        scrollTop = 0;
        scrollBottom = rows - 1;
        cx = Math.min(cx, cols - 1);
        cy = Math.min(cy, rows - 1);
        if (shownX >= cols) {
            shownX = cols - 1;
        }
        if (shownY >= rows) {
            shownY = rows - 1;
        }
        wrapPending = false;
    }

    // ------------------------------------------------------------------ input

    public synchronized void feed(byte[] data, int len) {
        if (len <= 0) {
            return;
        }
        if (pending.remaining() < len) {
            ByteBuffer grown = ByteBuffer.allocate(pending.position() + len + 4096);
            pending.flip();
            grown.put(pending);
            pending = grown;
        }
        pending.put(data, 0, len);
        pending.flip();
        CharBuffer out = CharBuffer.allocate(pending.remaining() + 1);
        decoder.reset();
        decoder.decode(pending, out, false);
        pending.compact();
        out.flip();
        while (out.hasRemaining()) {
            consume(out.get());
        }
    }

    // ------------------------------------------------------------- rendering

    private ArrayList<Line> active() {
        return altActive ? alt : lines;
    }

    /**
     * Maps a visible row (0 is the top of the screen) to its line. The normal
     * screen keeps scrollback in front of the viewport, so the visible screen
     * starts at {@code lines.size() - rows}; indexing the list directly would
     * address an old scrollback row instead.
     */
    private Line row(int y) {
        if (altActive) {
            if (alt.isEmpty()) {
                return null;
            }
            return alt.get(clamp(y, 0, alt.size() - 1));
        }
        int idx = lines.size() - rows + clamp(y, 0, rows - 1);
        if (idx < 0 || idx >= lines.size()) {
            return null;
        }
        return lines.get(idx);
    }

    private int effFg() {
        return inverse ? bg : fg;
    }

    private int effBg() {
        return inverse ? fg : bg;
    }

    // ---------------------------------------------------------------- parser

    private void consume(char c) {
        switch (state) {
            case GROUND:
                ground(c);
                break;
            case ESC:
                escape(c);
                break;
            case CSI:
                csi(c);
                break;
            case OSC:
                if (c == 0x07) {
                    // OSC terminator BEL is not a real bell.
                    finishOsc();
                    state = GROUND;
                } else if (c == 0x9c) {
                    finishOsc();
                    state = GROUND;
                } else if (c == 0x1b) {
                    // String terminator (ESC \) or the start of a new sequence.
                    finishOsc();
                    state = ESC;
                } else if (osc.length() < 4096) {
                    osc.append(c);
                }
                break;
            default:
                state = GROUND;
        }
    }

    private void ground(char c) {
        switch (c) {
            case 0x07:
                notifyBell();
                return;
            case 0x00:
                return;
            case 0x08:
                if (cx > 0) {
                    cx--;
                }
                wrapPending = false;
                return;
            case 0x09:
                cx = Math.min(cols - 1, ((cx / 8) + 1) * 8);
                return;
            case 0x0a:
            case 0x0b:
            case 0x0c:
                lineFeed();
                return;
            case 0x0d:
                cx = 0;
                wrapPending = false;
                return;
            case 0x1b:
                state = ESC;
                csi.setLength(0);
                return;
            default:
                if (c < 0x20) {
                    return;
                }
                put(c);
        }
    }

    private void escape(char c) {
        switch (c) {
            case '[':
                state = CSI;
                csi.setLength(0);
                return;
            case ']':
                state = OSC;
                osc.setLength(0);
                return;
            case '7':
                savedX = cx;
                savedY = cy;
                state = GROUND;
                return;
            case '8':
                cx = savedX;
                cy = savedY;
                state = GROUND;
                return;
            case 'M':
                // Reverse index: at the top of the scroll region the screen
                // scrolls down, otherwise the cursor moves up one row.
                wrapPending = false;
                if (cy == scrollTop) {
                    scrollDown(1);
                } else {
                    cy--;
                }
                state = GROUND;
                return;
            case 'c':
                reset();
                return;
            case '(':
            case ')':
            case '#':
            case '=':
            case '>':
                state = GROUND;
                return;
            default:
                state = GROUND;
        }
    }

    // -------------------------------------------------------------- OSC events

    /**
     * Handles a completed OSC string. opencode announces its UI state through
     * OSC 7501 ({@code state=idle|working|blocked}) and desktop notifications
     * through OSC 9 / OSC 777. They become Android sounds because the guest's
     * own audio engine cannot reach the phone's audio output.
     */
    private void finishOsc() {
        String payload = osc.toString();
        osc.setLength(0);
        if (payload.isEmpty()) {
            return;
        }
        int semi = payload.indexOf(';');
        String code = semi < 0 ? payload : payload.substring(0, semi);
        String data = semi < 0 ? "" : payload.substring(semi + 1);

        if ("7501".equals(code)) {
            String st = oscValue(data, "state");
            if (st == null || st.isEmpty() || "clear".equals(st)) {
                return;
            }
            String prev = programState;
            if (st.equals(prev)) {
                return;
            }
            programState = st;
            if ("blocked".equals(st)) {
                notifyAttention(true);
            } else if ("idle".equals(st)
                    && ("working".equals(prev) || "busy".equals(prev))) {
                notifyAttention(false);
            }
        } else if ("9".equals(code) || "777".equals(code)) {
            // A notification request means the session wants attention.
            if (data.length() > 0) {
                notifyAttention(true);
            }
        }
    }

    private static String oscValue(String data, String key) {
        for (String part : data.split(":")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(key)) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private void notifyBell() {
        Listener l = listener;
        if (l != null) {
            try {
                l.onBell();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void notifyAttention(boolean blocked) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onAttention(blocked);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void csi(char c) {        if (c >= 0x20 && c <= 0x3f) {
            csi.append(c);
            return;
        }
        String params = csi.toString();
        csi.setLength(0);
        state = GROUND;
        boolean priv = params.startsWith("?");
        String body = priv ? params.substring(1) : params;
        int[] p = parseInts(body);

        switch (c) {
            case 'A':
                cy = clamp(cy - n(p, 0, 1), 0, rows - 1);
                break;
            case 'B':
            case 'e':
                cy = clamp(cy + n(p, 0, 1), 0, rows - 1);
                break;
            case 'C':
                cx = clamp(cx + n(p, 0, 1), 0, cols - 1);
                break;
            case 'D':
                cx = clamp(cx - n(p, 0, 1), 0, cols - 1);
                break;
            case 'E':
                cy = clamp(cy + n(p, 0, 1), 0, rows - 1);
                cx = 0;
                break;
            case 'F':
                cy = clamp(cy - n(p, 0, 1), 0, rows - 1);
                cx = 0;
                break;
            case 'G':
                cx = clamp(n(p, 0, 1) - 1, 0, cols - 1);
                break;
            case 'd':
                cy = clamp(n(p, 0, 1) - 1, 0, rows - 1);
                break;
            case 'r': {
                // DECSTBM: top and bottom are 1-based visible rows. An invalid
                // pair restores the full-screen region.
                int top = n(p, 0, 1);
                int bottom = n(p, 1, rows);
                if (top >= 1 && bottom <= rows && top < bottom) {
                    scrollTop = top - 1;
                    scrollBottom = bottom - 1;
                } else {
                    scrollTop = 0;
                    scrollBottom = rows - 1;
                }
                cx = 0;
                cy = 0;
                break;
            }
            case 'S':
                scrollUp(n(p, 0, 1));
                break;
            case 'T':
                scrollDown(n(p, 0, 1));
                break;
            case 'H':
            case 'f':
                cy = clamp(n(p, 0, 1) - 1, 0, rows - 1);
                cx = clamp(n(p, 1, 1) - 1, 0, cols - 1);
                break;
            case 'J':
                eraseDisplay(n(p, 0, 0));
                break;
            case 'K':
                eraseLine(n(p, 0, 0));
                break;
            case 'm':
                sgr(p, body);
                break;
            case 'h':
            case 'l':
                if (priv) {
                    mode(p, c == 'h');
                }
                break;
            case 'L':
                insertLines(n(p, 0, 1));
                break;
            case 'M':
                deleteLines(n(p, 0, 1));
                break;
            case 'P':
                deleteChars(n(p, 0, 1));
                break;
            case '@':
                insertChars(n(p, 0, 1));
                break;
            case 'X':
                eraseChars(n(p, 0, 1));
                break;
            default:
                break;
        }
        wrapPending = false;
    }

    private void mode(int[] p, boolean on) {
        for (int v : p) {
            if (v == 25) {
                if (on) {
                    cursorVisible = true;
                    // opencode shows the caret exactly on the input cell at
                    // the end of a repaint; remember that cell.
                    shownX = cx;
                    shownY = cy;
                } else {
                    // The TUI hides the cursor while it repaints. Keep the
                    // latched caret visible there instead of blinking off.
                    cursorVisible = !altActive;
                }
            } else if (v == 1049 || v == 1047 || v == 47) {
                switchScreen(on);
            } else if (v == 1000 || v == 1002 || v == 1003) {
                mouseMode = on;
            } else if (v == 1006) {
                mouseSgr = on;
            }
        }
    }

    private void switchScreen(boolean toAlt) {
        if (toAlt == altActive) {
            return;
        }
        altActive = toAlt;
        scrollTop = 0;
        scrollBottom = rows - 1;
        wrapPending = false;
        if (toAlt) {
            for (Line l : alt) {
                l.clear(0, cols);
            }
            cx = 0;
            cy = 0;
            shownX = -1;
            shownY = -1;
        }
    }

    // ---------------------------------------------------------------- edits

    private void put(char c) {
        if (wrapPending) {
            wrapPending = false;
            cx = 0;
            lineFeed();
        }
        Line l = row(cy);
        if (l == null) {
            return;
        }
        if (cx >= 0 && cx < cols) {
            l.ch[cx] = c;
            l.fg[cx] = effFg();
            l.bg[cx] = effBg();
        }
        if (cx >= cols - 1) {
            cx = cols - 1;
            wrapPending = true;
        } else {
            cx++;
        }
    }

    private void lineFeed() {
        wrapPending = false;
        if (cy == scrollBottom) {
            scrollUp(1);
        } else if (cy < rows - 1) {
            cy++;
        }
    }

    /**
     * Scrolls the active region up by {@code n} rows. Outside the normal
     * screen's full-height region this shifts visible rows only; the full
     * region appends to scrollback instead so earlier output stays reachable.
     */
    private void scrollUp(int n) {
        boolean full = scrollTop == 0 && scrollBottom == rows - 1;
        if (altActive || !full) {
            ArrayList<Line> list = active();
            int base = altActive ? 0 : lines.size() - rows;
            int top = base + scrollTop;
            int bottom = base + scrollBottom;
            for (int i = 0; i < n; i++) {
                list.remove(top);
                list.add(bottom, new Line(cols));
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            lines.add(new Line(cols));
        }
        while (lines.size() > MAX_LINES) {
            lines.remove(0);
        }
    }

    /** Scrolls the active region down by {@code n} rows, dropping its bottom. */
    private void scrollDown(int n) {
        ArrayList<Line> list = active();
        int base = altActive ? 0 : lines.size() - rows;
        int top = base + scrollTop;
        int bottom = base + scrollBottom;
        for (int i = 0; i < n; i++) {
            list.remove(bottom);
            list.add(top, new Line(cols));
        }
    }

    /**
     * IL: opens blank rows at the cursor and pushes the region's tail off its
     * bottom. Only rows from the cursor to the region bottom move.
     */
    private void insertLines(int n) {
        ArrayList<Line> list = active();
        int base = altActive ? 0 : lines.size() - rows;
        int at = base + clamp(cy, 0, rows - 1);
        int bottom = base + scrollBottom;
        int count = Math.min(n, bottom - at + 1);
        for (int i = 0; i < count; i++) {
            list.remove(bottom);
            list.add(at, new Line(cols));
        }
    }

    /**
     * DL: removes rows at the cursor and pulls the rest of the region up,
     * leaving blanks at the region bottom.
     */
    private void deleteLines(int n) {
        ArrayList<Line> list = active();
        int base = altActive ? 0 : lines.size() - rows;
        int at = base + clamp(cy, 0, rows - 1);
        int bottom = base + scrollBottom;
        int count = Math.min(n, bottom - at + 1);
        for (int i = 0; i < count; i++) {
            list.remove(at);
            list.add(bottom, new Line(cols));
        }
    }

    private void deleteChars(int n) {
        Line l = row(cy);
        if (l == null) {
            return;
        }
        for (int i = cx; i < cols; i++) {
            int src = i + n;
            l.ch[i] = src < cols ? l.ch[src] : ' ';
            l.fg[i] = src < cols ? l.fg[src] : DEFAULT_FG;
            l.bg[i] = src < cols ? l.bg[src] : DEFAULT_BG;
        }
    }

    private void insertChars(int n) {
        Line l = row(cy);
        if (l == null) {
            return;
        }
        for (int i = cols - 1; i >= cx; i--) {
            int src = i - n;
            l.ch[i] = src >= cx ? l.ch[src] : ' ';
            l.fg[i] = src >= cx ? l.fg[src] : DEFAULT_FG;
            l.bg[i] = src >= cx ? l.bg[src] : DEFAULT_BG;
        }
    }

    private void eraseChars(int n) {
        Line l = row(cy);
        if (l == null) {
            return;
        }
        l.clear(cx, Math.min(cols, cx + n));
    }

    private void eraseLine(int mode) {
        Line l = row(cy);
        if (l == null) {
            return;
        }
        if (mode == 0) {
            l.clear(cx, cols);
        } else if (mode == 1) {
            l.clear(0, cx + 1);
        } else {
            l.clear(0, cols);
        }
    }

    private void eraseDisplay(int mode) {
        if (mode == 3) {
            if (!altActive && lines.size() > rows) {
                lines.subList(0, lines.size() - rows).clear();
            }
            return;
        }
        ArrayList<Line> list = active();
        int base = list == lines ? lines.size() - rows : 0;
        if (mode == 0) {
            eraseLine(0);
            for (int y = cy + 1; y < rows; y++) {
                list.get(base + y).clear(0, cols);
            }
        } else if (mode == 1) {
            eraseLine(1);
            for (int y = 0; y < cy; y++) {
                list.get(base + y).clear(0, cols);
            }
        } else {
            for (int y = 0; y < rows; y++) {
                list.get(base + y).clear(0, cols);
            }
        }
    }

    // ------------------------------------------------------------------ SGR

    private void sgr(int[] p, String body) {
        if (body.isEmpty()) {
            resetAttrs();
            return;
        }
        for (int i = 0; i < p.length; i++) {
            int v = p[i];
            switch (v) {
                case 0:
                    resetAttrs();
                    break;
                case 1:
                    bold = true;
                    break;
                case 2:
                case 22:
                    bold = false;
                    break;
                case 7:
                    inverse = true;
                    break;
                case 27:
                    inverse = false;
                    break;
                case 39:
                    fg = DEFAULT_FG;
                    break;
                case 49:
                    bg = DEFAULT_BG;
                    break;
                case 38:
                    i = extended(p, i, true);
                    break;
                case 48:
                    i = extended(p, i, false);
                    break;
                default:
                    if (v >= 30 && v <= 37) {
                        fg = palette(v - 30);
                    } else if (v >= 90 && v <= 97) {
                        fg = palette(v - 90 + 8);
                    } else if (v >= 40 && v <= 47) {
                        bg = palette(v - 40);
                    } else if (v >= 100 && v <= 107) {
                        bg = palette(v - 100 + 8);
                    }
            }
        }
    }

    private int extended(int[] p, int i, boolean isFg) {
        if (i + 1 >= p.length) {
            return i;
        }
        int kind = p[i + 1];
        if (kind == 5 && i + 2 < p.length) {
            int color = xterm(p[i + 2]);
            if (isFg) {
                fg = color;
            } else {
                bg = color;
            }
            return i + 2;
        }
        if (kind == 2 && i + 4 < p.length) {
            int color = 0xFF000000 | (p[i + 2] << 16) | (p[i + 3] << 8) | p[i + 4];
            if (isFg) {
                fg = color;
            } else {
                bg = color;
            }
            return i + 4;
        }
        return i + 1;
    }

    private void resetAttrs() {
        fg = DEFAULT_FG;
        bg = DEFAULT_BG;
        bold = false;
        inverse = false;
    }

    private static int palette(int i) {
        return xterm(i);
    }

    private static int xterm(int idx) {
        if (idx < 16) {
            int[] base = {
                    0xFF1B1F27, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B,
                    0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFABB2BF,
                    0xFF5C6370, 0xFFFF7B86, 0xFFB5E890, 0xFFFFD68A,
                    0xFF7CC4FF, 0xFFE29BF0, 0xFF6FD6E2, 0xFFFFFFFF
            };
            return base[idx & 15];
        }
        if (idx >= 16 && idx <= 231) {
            int n = idx - 16;
            int r = n / 36;
            int g = (n % 36) / 6;
            int b = n % 6;
            return 0xFF000000
                    | (r == 0 ? 0 : 55 + r * 40) << 16
                    | (g == 0 ? 0 : 55 + g * 40) << 8
                    | (b == 0 ? 0 : 55 + b * 40);
        }
        int gray = 8 + Math.max(0, Math.min(23, idx - 232)) * 10;
        return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
    }

    // ----------------------------------------------------------------- utils

    private static int[] parseInts(String body) {
        if (body.isEmpty()) {
            return new int[0];
        }
        String[] parts = body.split(";", -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i];
            if (s.isEmpty()) {
                out[i] = 0;
                continue;
            }
            try {
                out[i] = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static int n(int[] p, int idx, int def) {
        if (p == null || idx >= p.length) {
            return def;
        }
        return p[idx] == 0 ? def : p[idx];
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
