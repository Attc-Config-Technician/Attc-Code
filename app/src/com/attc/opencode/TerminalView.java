package com.attc.opencode;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/**
 * Terminal surface: fixed-width grid renderer plus a real input connection.
 *
 * A tap does two things on purpose: requestFocus() and showSoftInput(). Without
 * the explicit showSoftInput the IME only opens on the second tap for a custom
 * view, which is exactly the bug the spec calls out.
 */
public final class TerminalView extends View implements Hub.Listener {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final RectF rect = new RectF();

    private float charW = 8f;
    private float charH = 16f;
    private float ascent;
    private int cols = 80;
    private int rows = 24;
    private int scroll;

    private TerminalBuffer buffer;

    private final SharedPreferences settings;
    private final ScaleGestureDetector scaleDetector;
    private float textSizeSp;
    private boolean pinching;
    private boolean ctrlArmed;
    private boolean altArmed;
    private boolean shiftArmed;
    private Runnable modifierListener;

    // A pinch fires dozens of onScale callbacks. Reflowing the grid and
    // sending SIGWINCH on every one makes a TUI render at a stale size, which
    // pushes its UI into scrollback and leaves fragments behind at extreme
    // zoom levels. Change the font metrics live, but apply the new grid once
    // on release.
    private boolean scaling;
    private int pendingRows = -1;
    private int pendingCols = -1;

    public interface SelectionListener {
        /**
         * @param active  a selection exists
         * @param hasText the selection contains non-space text
         * @param topY    top edge of the selection, in this view's pixels
         * @param bottomY bottom edge of the selection, in this view's pixels
         */
        void onSelection(boolean active, boolean hasText, int topY, int bottomY);
    }

    private static final long LONG_PRESS_MS = 380;

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private SelectionListener selectionListener;
    private boolean hasSelection;
    private boolean selecting;
    private boolean selectionDragged;
    private int selRow1;
    private int selCol1;
    private int selRow2;
    private int selCol2;
    private int selScroll;
    private int draggingPin; // 0 = none, 1 = anchor, 2 = focus
    private final Paint pinFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float downX;
    private boolean moved;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (buffer == null || moved || pinching) {
                return;
            }
            beginSelection(downX, downY);
        }
    };

    public TerminalView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(TerminalBuffer.DEFAULT_BG);
        settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        textSizeSp = settings.getFloat("font_sp", 13f);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setColor(TerminalBuffer.DEFAULT_FG);
        pinFill.setColor(0xFF00E5A0);
        pinRing.setColor(0xFFFFFFFF);
        pinRing.setStyle(Paint.Style.STROKE);
        pinRing.setStrokeWidth(dp(2f));
        applyTextMetrics();
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        if (hasSelection) {
                            clearSelection();
                        }
                        scaling = true;
                        float next = Math.max(5f, Math.min(30f, textSizeSp * d.getScaleFactor()));
                        if (Math.abs(next - textSizeSp) < 0.2f) {
                            return true;
                        }
                        textSizeSp = next;
                        applyTextMetrics();
                        relayout();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector d) {
                        scaling = false;
                        applyPendingLayout();
                        settings.edit().putFloat("font_sp", textSizeSp).apply();
                    }
                });
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void applyTextMetrics() {        paint.setTextSize(textSizeSp * getResources().getDisplayMetrics().scaledDensity);
        Paint.FontMetrics fm = paint.getFontMetrics();
        ascent = fm.ascent;
        charW = paint.measureText("M");
        charH = fm.descent - fm.ascent + fm.leading;
        if (charH <= 0) {
            charH = paint.getTextSize() * 1.2f;
        }
        if (charW <= 0) {
            charW = paint.getTextSize() * 0.6f;
        }
    }

    private void relayout() {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        int newCols = Math.max(8, (int) ((w - getPaddingLeft() - getPaddingRight()) / charW));
        int newRows = Math.max(2, (int) ((h - getPaddingTop() - getPaddingBottom()) / charH));
        if (newCols == cols && newRows == rows) {
            pendingRows = -1;
            pendingCols = -1;
            return;
        }
        if (scaling) {
            pendingRows = newRows;
            pendingCols = newCols;
            invalidate();
            return;
        }
        applyLayout(newRows, newCols);
    }

    /** Applies a settled grid size to the buffer and the child process. */
    private void applyLayout(int newRows, int newCols) {
        cols = newCols;
        rows = newRows;
        if (buffer != null) {
            buffer.resize(rows, cols);
        }
        Session s = Hub.session;
        if (s != null) {
            s.resize(rows, cols);
        }
        Logger.i("grid: " + cols + "x" + rows
                + (buffer != null ? " history=" + buffer.historySize() : ""));
        invalidate();
    }

    private void applyPendingLayout() {
        int r = pendingRows;
        int c = pendingCols;
        pendingRows = -1;
        pendingCols = -1;
        if (r > 0 && c > 0) {
            applyLayout(r, c);
        }
    }

    /** Test hook: changes the font through the same path as a pinch. */
    public void setFontSizeSp(float sp) {
        textSizeSp = Math.max(5f, Math.min(30f, sp));
        applyTextMetrics();
        relayout();
        settings.edit().putFloat("font_sp", textSizeSp).apply();
    }

    /** Test hooks: mimic the deferred grid update of a real pinch. */
    public void beginScaleForTest() {
        scaling = true;
    }

    public void endScaleForTest() {
        scaling = false;
        applyPendingLayout();
    }

    public float fontSp() {
        return textSizeSp;
    }

    public void setBuffer(TerminalBuffer b) {
        buffer = b;
        scroll = 0;
        pendingRows = -1;
        pendingCols = -1;
        if (b != null) {
            b.resize(rows, cols);
            Session s = Hub.session;
            if (s != null) {
                s.resize(rows, cols);
            }
        }
        invalidate();
    }

    public TerminalBuffer buffer() {
        return buffer;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Hub.addListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        applyPendingLayout();
        Hub.removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onOutput() {
        if (buffer == null && Hub.buffer != null) {
            setBuffer(Hub.buffer);
        }
        postInvalidateOnAnimation();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayout();
    }

    // ------------------------------------------------------------------- draw

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(TerminalBuffer.DEFAULT_BG);
        TerminalBuffer b = buffer != null ? buffer : Hub.buffer;
        if (b == null) {
            paint.setColor(0xFF5E7183);
            canvas.drawText("Starting terminal...", getPaddingLeft(), -ascent + charH, paint);
            return;
        }
        int viewRows = Math.min(rows, b.rows());
        int maxScroll = b.historySize();
        if (scroll > maxScroll) {
            scroll = maxScroll;
        }
        for (int r = 0; r < viewRows; r++) {
            TerminalBuffer.Line line = b.lineAt(r, scroll);
            if (line == null) {
                continue;
            }
            float top = getPaddingTop() + r * charH;
            drawBackgrounds(canvas, line, top);
            if (hasSelection) {
                drawSelection(canvas, r, top);
            }
            float x = getPaddingLeft();
            float baseline = top - ascent;
            for (int c = 0; c < Math.min(cols, line.ch.length); c++) {
                char ch = line.ch[c];
                if (ch != ' ' && ch != 0) {
                    paint.setColor(line.fg[c]);
                    canvas.drawText(String.valueOf(ch), x, baseline, paint);
                }
                x += charW;
            }
        }
        drawCursor(canvas, b);
        if (hasSelection && scroll == 0) {
            drawSelectionPins(canvas);
        }
    }

    private void drawBackgrounds(Canvas canvas, TerminalBuffer.Line line, float top) {
        int c = 0;
        while (c < Math.min(cols, line.bg.length)) {
            int color = line.bg[c];
            if (color == TerminalBuffer.DEFAULT_BG) {
                c++;
                continue;
            }
            int start = c;
            while (c < Math.min(cols, line.bg.length) && line.bg[c] == color) {
                c++;
            }
            bgPaint.setColor(color);
            rect.set(getPaddingLeft() + start * charW, top,
                    getPaddingLeft() + c * charW, top + charH);
            canvas.drawRect(rect, bgPaint);
        }
    }

    private void drawSelection(Canvas canvas, int row, float top) {
        int sr;
        int sc;
        int er;
        int ec;
        if (selRow1 < selRow2 || (selRow1 == selRow2 && selCol1 <= selCol2)) {
            sr = selRow1;
            sc = selCol1;
            er = selRow2;
            ec = selCol2;
        } else {
            sr = selRow2;
            sc = selCol2;
            er = selRow1;
            ec = selCol1;
        }
        if (row < sr || row > er) {
            return;
        }
        int from = row == sr ? sc : 0;
        int to = row == er ? ec : cols - 1;
        from = Math.max(0, Math.min(cols - 1, from));
        to = Math.max(0, Math.min(cols - 1, to));
        bgPaint.setColor(0x6600E5A0);
        rect.set(getPaddingLeft() + from * charW, top,
                getPaddingLeft() + (to + 1) * charW, top + charH);
        canvas.drawRect(rect, bgPaint);
    }

    private void drawCursor(Canvas canvas, TerminalBuffer b) {
        if (scroll != 0 || !b.cursorVisible()) {
            return;
        }
        int r = b.cursorDrawRow();
        int c = b.cursorDrawCol();
        if (r < 0 || r >= rows || c < 0 || c >= cols) {
            return;
        }
        // A solid white block: the one thing that always shows where typing
        // will land, even when the TUI repaints around it.
        bgPaint.setColor(0xFFFFFFFF);
        float top = getPaddingTop() + r * charH;
        rect.set(getPaddingLeft() + c * charW, top,
                getPaddingLeft() + (c + 1) * charW, top + charH);
        canvas.drawRect(rect, bgPaint);
        TerminalBuffer.Line line = b.lineAt(r, 0);
        if (line != null && c < line.ch.length && line.ch[c] != ' ') {
            paint.setColor(TerminalBuffer.DEFAULT_BG);
            canvas.drawText(String.valueOf(line.ch[c]),
                    getPaddingLeft() + c * charW, top - ascent, paint);
        }
    }

    // ------------------------------------------------------- selection handles

    private float pinX(int col) {
        return getPaddingLeft() + col * charW + charW / 2f;
    }

    private float pinTopY(int row) {
        // Handle sits on the top edge of the first cell and the bottom edge of
        // the last cell, so both are reachable without covering the text.
        return getPaddingTop() + row * charH;
    }

    private float pinBottomY(int row) {
        return getPaddingTop() + (row + 1) * charH;
    }

    /** -1 top edge, +1 bottom edge depending on which end of the range it is. */
    private int pinEdge(int row) {
        int topRow = Math.min(selRow1, selRow2);
        int bottomRow = Math.max(selRow1, selRow2);
        return row == bottomRow && selRow1 != selRow2 ? 1 : -1;
    }

    private float pinCenterY(int row) {
        return pinEdge(row) > 0 ? pinBottomY(row) : pinTopY(row);
    }

    private void drawSelectionPins(Canvas canvas) {
        float radius = dp(9f);
        drawPin(canvas, pinX(selCol1), pinCenterY(selRow1), radius);
        drawPin(canvas, pinX(selCol2), pinCenterY(selRow2), radius);
    }

    private void drawPin(Canvas canvas, float x, float y, float radius) {
        canvas.drawCircle(x, y, radius, pinFill);
        canvas.drawCircle(x, y, radius, pinRing);
    }

    /** 0 when the touch is not near either handle. */
    private int hitPin(float x, float y) {
        if (!hasSelection || scroll != 0) {
            return 0;
        }
        float r = dp(22f);
        float dx1 = x - pinX(selCol1);
        float dy1 = y - pinCenterY(selRow1);
        if (dx1 * dx1 + dy1 * dy1 <= r * r) {
            return 1;
        }
        float dx2 = x - pinX(selCol2);
        float dy2 = y - pinCenterY(selRow2);
        if (dx2 * dx2 + dy2 * dy2 <= r * r) {
            return 2;
        }
        return 0;
    }

    // ------------------------------------------------------------------ touch

    private float downY;
    private float lastMoveY;
    private float wheelAccum;
    private int downScroll;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        int action = e.getActionMasked();
        if (e.getPointerCount() > 1 || scaleDetector.isInProgress()) {
            pinching = true;
            handler.removeCallbacks(longPressRunnable);
            return true;
        }
        if (pinching) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                pinching = false;
            }
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downScroll = scroll;
                lastMoveY = downY;
                wheelAccum = 0f;
                moved = false;
                draggingPin = hitPin(downX, downY);
                if (draggingPin != 0) {
                    handler.removeCallbacks(longPressRunnable);
                    return true;
                }
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (buffer == null) {
                    return true;
                }
                if (draggingPin != 0) {
                    int row = cellRow(e.getY());
                    int col = cellCol(e.getX());
                    if (draggingPin == 1) {
                        selRow1 = row;
                        selCol1 = col;
                    } else {
                        selRow2 = row;
                        selCol2 = col;
                    }
                    selectionDragged = true;
                    invalidate();
                    notifySelection();
                    return true;
                }
                if (selecting) {
                    selRow2 = cellRow(e.getY());
                    selCol2 = cellCol(e.getX());
                    selectionDragged = true;
                    invalidate();
                    return true;
                }
                if (Math.abs(e.getX() - downX) > charW * 1.5f
                        || Math.abs(e.getY() - downY) > charH) {
                    moved = true;
                    handler.removeCallbacks(longPressRunnable);
                }
                if (moved) {
                    if (hasSelection) {
                        clearSelection();
                    }
                    if (buffer.mouseEnabled()) {
                        // The TUI owns an alternate screen with no local
                        // history; scrolling means sending wheel events to it.
                        // Touch panning: the content follows the finger, so
                        // dragging down reveals earlier output (wheel up).
                        wheelAccum += lastMoveY - e.getY();
                        lastMoveY = e.getY();
                        float step = Math.max(dp(24f), charH * 2f);
                        int col = cellCol(e.getX());
                        int row = cellRow(e.getY());
                        while (wheelAccum <= -step) {
                            sendMouseWheel(col, row, true);
                            wheelAccum += step;
                        }
                        while (wheelAccum >= step) {
                            sendMouseWheel(col, row, false);
                            wheelAccum -= step;
                        }
                    } else {
                        int delta = (int) ((e.getY() - downY) / charH);
                        int next = downScroll + delta;
                        scroll = Math.max(0, Math.min(buffer.historySize(), next));
                        invalidate();
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPressRunnable);
                if (draggingPin != 0) {
                    draggingPin = 0;
                    notifySelection();
                    return true;
                }
                if (selecting) {
                    selecting = false;
                    if (selectionDragged) {
                        selRow2 = cellRow(e.getY());
                        selCol2 = cellCol(e.getX());
                    }
                    invalidate();
                    notifySelection();
                    return true;
                }
                if (!moved && Math.abs(e.getY() - downY) < charH / 2) {
                    if (hasSelection) {
                        clearSelection();
                    } else {
                        scroll = 0;
                        invalidate();
                        if (buffer != null && buffer.mouseEnabled()) {
                            sendMouseClick(cellCol(e.getX()), cellRow(e.getY()));
                        }
                        focusAndShowKeyboard();
                    }
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                selecting = false;
                draggingPin = 0;
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }

    private int cellCol(float x) {
        return Math.max(0, Math.min(cols - 1,
                (int) ((x - getPaddingLeft()) / charW)));
    }

    private int cellRow(float y) {
        return Math.max(0, Math.min(rows - 1,
                (int) ((y - getPaddingTop()) / charH)));
    }

    private void beginSelection(float x, float y) {
        if (buffer == null) {
            return;
        }
        int row = cellRow(y);
        int col = cellCol(x);
        selRow1 = row;
        selRow2 = row;
        selCol1 = col;
        selCol2 = col;
        TerminalBuffer.Line line = buffer.lineAt(row, scroll);
        if (line != null) {
            int a = col;
            int b = col;
            while (a > 0 && !isSpace(line, a - 1)) {
                a--;
            }
            while (b < cols - 1 && !isSpace(line, b)) {
                b++;
            }
            selCol1 = a;
            selCol2 = b;
        }
        selScroll = scroll;
        hasSelection = true;
        selecting = true;
        selectionDragged = false;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        invalidate();
        notifySelection();
    }

    private static boolean isSpace(TerminalBuffer.Line line, int c) {
        if (c < 0 || c >= line.ch.length) {
            return true;
        }
        char ch = line.ch[c];
        return ch == 0 || ch == ' ' || ch == '\t';
    }

    public boolean hasSelection() {
        return hasSelection;
    }

    /** Text of the current selection, or an empty string. */
    public String selectedText() {
        if (!hasSelection || buffer == null) {
            return "";
        }
        return buffer.text(selRow1, selCol1, selRow2, selCol2, selScroll);
    }

    public void clearSelection() {
        if (!hasSelection) {
            return;
        }
        hasSelection = false;
        selecting = false;
        invalidate();
        notifySelection();
    }

    public void setSelectionListener(SelectionListener l) {
        selectionListener = l;
    }

    private void notifySelection() {
        if (selectionListener != null) {
            String text = selectedText();
            int topRow = Math.min(selRow1, selRow2);
            int bottomRow = Math.max(selRow1, selRow2);
            int topY = Math.round(getPaddingTop() + topRow * charH);
            int bottomY = Math.round(getPaddingTop() + (bottomRow + 1) * charH);
            selectionListener.onSelection(hasSelection,
                    text != null && !text.isEmpty(), topY, bottomY);
        }
    }

    private void sendMouseWheel(int col, int row, boolean up) {
        int cx = Math.max(1, col + 1);
        int cy = Math.max(1, row + 1);
        int button = up ? 64 : 65;
        if (buffer != null && buffer.mouseSgr()) {
            sendRaw("\u001b[<" + button + ";" + cx + ";" + cy + "M");
        } else {
            sendRaw("\u001b[M" + (char) (32 + button) + (char) (32 + cx) + (char) (32 + cy));
        }
    }

    private void sendMouseClick(int col, int row) {
        int cx = Math.max(1, col + 1);
        int cy = Math.max(1, row + 1);
        if (buffer != null && buffer.mouseSgr()) {
            sendRaw("\u001b[<0;" + cx + ";" + cy + "M"
                    + "\u001b[<0;" + cx + ";" + cy + "m");
        } else {
            sendRaw("\u001b[M" + (char) 32 + (char) (32 + cx) + (char) (32 + cy));
        }
    }

    /** Writes clipboard text into the PTY. */
    public void paste(String text) {
        Session s = Hub.session;
        if (s != null && text != null && !text.isEmpty()) {
            s.write(text);
        }
    }

    /** Sends a raw escape sequence from the extra-keys bar. */
    public void sendRaw(String text) {
        Session s = Hub.session;
        if (s != null) {
            s.write(text);
        }
        ctrlArmed = false;
        altArmed = false;
        shiftArmed = false;
        notifyModifiers();
    }

    public void setCtrl(boolean on) {
        ctrlArmed = on;
    }

    public void setAlt(boolean on) {
        altArmed = on;
    }

    public void setShift(boolean on) {
        shiftArmed = on;
    }

    public boolean ctrlArmed() {
        return ctrlArmed;
    }

    public boolean altArmed() {
        return altArmed;
    }

    public boolean shiftArmed() {
        return shiftArmed;
    }

    /** Lets the activity repaint the extra-keys bar after a sticky modifier ends. */
    public void setModifierListener(Runnable listener) {
        modifierListener = listener;
    }

    private void notifyModifiers() {
        Runnable listener = modifierListener;
        if (listener != null) {
            listener.run();
        }
    }

    /** Applies sticky CTRL/ALT/SHIFT to the next text committed by the IME. */
    String applyModifiers(String text) {
        if (!ctrlArmed && !altArmed && !shiftArmed) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char out = c;
            if (shiftArmed) {
                out = Character.toUpperCase(out);
            }
            if (ctrlArmed) {
                char lower = Character.toLowerCase(out);
                if (lower >= 'a' && lower <= 'z') {
                    out = (char) (lower & 0x1f);
                } else if (out == ' ') {
                    out = 0;
                }
            }
            if (altArmed) {
                sb.append('\u001b');
            }
            sb.append(out);
        }
        ctrlArmed = false;
        altArmed = false;
        shiftArmed = false;
        notifyModifiers();
        return sb.toString();
    }

    /** Tap-to-keyboard: focus AND show, in that order. */
    public void focusAndShowKeyboard() {
        requestFocus();
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE;
        return new TermInputConnection(this);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleKey(event) || super.onKeyDown(keyCode, event);
    }

    /** Guards against the same character arriving twice (IME + key event). */
    private long lastCharAt;
    private String lastChar = "";

    void writeDedup(String text) {
        Session s = Hub.session;
        if (s == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (text.equals(lastChar) && now - lastCharAt < 30) {
            return;
        }
        lastCharAt = now;
        lastChar = text;
        s.write(text);
    }

    boolean handleKey(KeyEvent ev) {
        Session s = Hub.session;
        if (s == null) {
            return false;
        }
        int code = ev.getKeyCode();
        int meta = ev.getMetaState();
        boolean ctrl = (meta & KeyEvent.META_CTRL_ON) != 0;
        boolean alt = (meta & KeyEvent.META_ALT_ON) != 0;

        switch (code) {
            case KeyEvent.KEYCODE_ENTER: {
                // The IME sends ENTER through sendKeyEvent; a hardware keyboard
                // may also send it directly.
                s.write("\r");
                return true;
            }
            case KeyEvent.KEYCODE_DEL:
                s.write("\u007f");
                return true;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                s.write("\u001b[3~");
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                s.write("\u001b[A");
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                s.write("\u001b[B");
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                s.write("\u001b[C");
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                s.write("\u001b[D");
                return true;
            case KeyEvent.KEYCODE_TAB:
                s.write("\t");
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                s.write("\u001b");
                return true;
            case KeyEvent.KEYCODE_MOVE_HOME:
                s.write("\u001b[H");
                return true;
            case KeyEvent.KEYCODE_MOVE_END:
                s.write("\u001b[F");
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
                s.write("\u001b[5~");
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                s.write("\u001b[6~");
                return true;
            default:
                break;
        }

        int unicode = ev.getUnicodeChar();
        if (unicode == 0) {
            return false;
        }
        if (ctrl) {
            int c = Character.toLowerCase((char) unicode);
            if (c >= 'a' && c <= 'z') {
                s.write(String.valueOf((char) (c & 0x1f)));
                return true;
            }
        }
        String text = String.valueOf((char) unicode);
        writeDedup((alt ? "\u001b" : "") + text);
        return true;
    }

    /** IME bridge: commitText goes straight to the PTY. */
    private static final class TermInputConnection extends BaseInputConnection {
        private final TerminalView view;

        TermInputConnection(TerminalView v) {
            super(v, true);
            this.view = v;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            view.writeDedup(view.applyModifiers(text.toString()));
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            Session s = Hub.session;
            if (s != null) {
                for (int i = 0; i < beforeLength; i++) {
                    s.write("\u007f");
                }
            }
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                return view.handleKey(event);
            }
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            return true;
        }
    }
}
