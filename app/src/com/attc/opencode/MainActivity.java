package com.attc.opencode;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.Manifest;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Vibrator;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.system.Os;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Single-screen app: a status bar, the terminal and a Retry button that only
 * appears when setup fails. Everything else lives in TerminalService.
 */
public final class MainActivity extends Activity implements Hub.Listener {

    private static final int REQ_NOTIFICATIONS = 10;
    private static final int REQ_VPN = 4711;
    private static final int REQ_FILE = 4712;
    private static final int REQ_MEDIA = 4713;
    private static final int REQ_VOICE_MIC = 4714;
    private static final String PERM_RECORD_AUDIO = "android.permission.RECORD_AUDIO";
    private static final String[] VOICE_CLIS =
            {"opencode", "claude", "agy", "codex", "kimi"};

    // The local android.jar is API 28, so newer constants are declared here.
    private static final int API_Q = 29;
    private static final int API_R = 30;
    private static final int API_TIRAMISU = 33;
    private static final String PERM_POST_NOTIFICATIONS =
            "android.permission.POST_NOTIFICATIONS";
    private static final String ACTION_MANAGE_APP_ALL_FILES =
            "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION";
    private static final String ACTION_MANAGE_ALL_FILES =
            "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION";
    private static final String PREFS = "settings";
    private static final String KEY_BOOT = "boot";
    private static final String KEY_STORAGE_ASKED = "storage_asked";
    private static final String KEY_DOWNLOAD_CONSENT = "download_consent";

    /** Copy-fallback housekeeping: 7 days or 100 MB, whichever hits first. */
    private static final long UPLOAD_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long UPLOAD_MAX_BYTES = 100L * 1024 * 1024;

    private TerminalView terminal;
    private TextView statusText;
    private TextView dot;
    private Button retry;
    private TextView ctrlKey;
    private TextView altKey;
    private TextView shiftKey;
    private FrameLayout stage;
    private LinearLayout selectionBar;
    private LinearLayout textInputRow;
    private EditText textInput;
    private TextView shizukuRow;
    private CheckBox fakeVpnToggle;
    private TextView fakeVpnIpText;
    private boolean suppressVpnToggle;
    private String shizukuStatus = "Shizuku not running";
    private View chooser;
    private View panel;
    private View cliDrawer;
    private FrameLayout cliDrawerScrim;
    private LinearLayout cliDrawerList;
    private LinearLayout panelBody;
    private boolean shizukuGranted;

    private static final int MOD_CTRL = 1;
    private static final int MOD_ALT = 2;

    private SharedPreferences prefs;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private CheckBox voiceToggle;
    private boolean pendingEnableVoice;
    private boolean suppressVoiceToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildUi();
        ShizukuHelper.init(this, (status, available, granted) -> {
            shizukuStatus = status;
            shizukuGranted = granted;
            if (shizukuRow != null) {
                shizukuRow.setText("Shizuku: " + status);
            }
            if (available && !granted && !prefs.getBoolean("shizuku_asked", false)) {
                prefs.edit().putBoolean("shizuku_asked", true).apply();
                ShizukuHelper.request(MainActivity.this);
            }
        });
        requestNotificationPermission();
        maybeExplainStorage();
        startTerminal();
        maybeStartFakeVpn();
        handleIntent(getIntent());
        Hub.addListener(this);
        stage.post(this::maybeShowStartScreen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Hub.buffer != null) {
            terminal.setBuffer(Hub.buffer);
        }
        refresh();
    }

    @Override
    protected void onDestroy() {
        Hub.removeListener(this);
        super.onDestroy();
    }

    // -------------------------------------------------------------------- UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0F14);
        root.setFitsSystemWindows(true);

        root.addView(buildTopBar());

        stage = new FrameLayout(this);
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(stage, stageParams);

        terminal = new TerminalView(this);
        terminal.setModifierListener(this::paintModifiers);
        int pad = dp(10);
        terminal.setPadding(pad, dp(8), pad, dp(8));
        stage.addView(terminal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        retry = new Button(this);
        retry.setText("Retry");
        retry.setAllCaps(false);
        retry.setTextColor(0xFF04120C);
        retry.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF00E5A0);
        bg.setCornerRadius(dp(24));
        retry.setBackground(bg);
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> {
            retry.setVisibility(View.GONE);
            Intent i = new Intent(this, TerminalService.class)
                    .setAction(TerminalService.ACTION_RETRY);
            startService(i);
        });
        FrameLayout.LayoutParams retryParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        retryParams.bottomMargin = dp(22);
        stage.addView(retry, retryParams);

        selectionBar = buildSelectionBar();
        FrameLayout.LayoutParams selParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        selParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        selParams.topMargin = dp(8);
        stage.addView(selectionBar, selParams);
        terminal.setSelectionListener((active, hasText, topY, bottomY) -> runOnUiThread(() -> {
            selectionBar.setVisibility(active ? View.VISIBLE : View.GONE);
            if (active) {
                // Measure once so the bar lands just above the selection even
                // on the first frame it appears.
                selectionBar.post(() -> positionSelectionBar(topY, bottomY));
            }
        }));

        textInputRow = buildTextInputRow();
        root.addView(textInputRow);
        root.addView(buildExtraKeys());
        buildCliDrawer();

        setContentView(root);
    }

    private LinearLayout buildSelectionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1B2430);
        bg.setCornerRadius(dp(18));
        bar.setBackground(bg);
        bar.setPadding(dp(4), dp(2), dp(4), dp(2));
        bar.setVisibility(View.GONE);
        bar.addView(actionButton("Copy", v -> copySelection()));
        bar.addView(actionButton("Paste", v -> pasteClipboard()));
        bar.addView(actionButton("Done", v -> terminal.clearSelection()));
        return bar;
    }

    private void positionSelectionBar(int topY, int bottomY) {
        if (stage == null || selectionBar == null) {
            return;
        }
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) selectionBar.getLayoutParams();
        int height = selectionBar.getHeight();
        if (height <= 0) {
            height = dp(40);
        }
        int margin = dp(10);
        int stageHeight = stage.getHeight();
        if (topY - margin - height >= dp(2)) {
            // Preferred: a little above the selected lines.
            lp.topMargin = topY - margin - height;
        } else if (bottomY + margin + height <= stageHeight) {
            // No room above: drop below the selection instead.
            lp.topMargin = bottomY + margin;
        } else {
            lp.topMargin = dp(2);
        }
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        selectionBar.setLayoutParams(lp);
    }

    private TextView actionButton(String label, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(0xFFE6EDF3);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setOnClickListener(click);
        return b;
    }

    private void copySelection() {
        String text = terminal.selectedText();
        if (text != null && !text.isEmpty()) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
            }
        }
        terminal.clearSelection();
    }

    private void pasteClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            terminal.paste(text == null ? "" : text.toString());
        }
        terminal.clearSelection();
    }

    private LinearLayout buildTextInputRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(0xFF0E141C);
        row.setPadding(dp(6), dp(5), dp(6), dp(5));
        row.setVisibility(View.GONE);

        textInput = new EditText(this);
        textInput.setSingleLine(true);
        textInput.setHint("Type here, Enter sends");
        textInput.setHintTextColor(0xFF6C7A88);
        textInput.setTextColor(0xFFE6EDF3);
        textInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        textInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        textInput.setImeOptions(EditorInfo.IME_ACTION_SEND
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        textInput.setBackgroundColor(0xFF1B2430);
        textInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inputParams.setMargins(0, 0, dp(6), 0);
        row.addView(textInput, inputParams);

        textInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean send = actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN);
            if (send) {
                commitTextInput();
                return true;
            }
            return false;
        });
        textInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                commitTextInput();
                return true;
            }
            return false;
        });

        TextView send = new TextView(this);
        send.setText("SEND");
        send.setTextColor(0xFF9FD8FF);
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        send.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        send.setGravity(Gravity.CENTER);
        send.setPadding(dp(10), dp(8), dp(10), dp(8));
        send.setOnClickListener(v -> commitTextInput());
        row.addView(send);

        TextView close = new TextView(this);
        close.setText("×");
        close.setTextColor(0xFF9FB0BF);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(10), dp(4), dp(10), dp(4));
        close.setOnClickListener(v -> hideTextInput());
        row.addView(close);
        return row;
    }

    /** Termux-style text-input mode, toggled by swiping the extra-keys row. */
    private void toggleTextInput() {
        if (textInputRow == null) {
            return;
        }
        if (textInputRow.getVisibility() == View.VISIBLE) {
            hideTextInput();
        } else {
            textInputRow.setVisibility(View.VISIBLE);
            textInput.requestFocus();
            textInput.postDelayed(this::showTextInputKeyboard, 80);
        }
    }

    private void hideTextInput() {
        if (textInputRow == null) {
            return;
        }
        textInputRow.setVisibility(View.GONE);
        textInput.setText("");
        terminal.requestFocus();
        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void showTextInputKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void commitTextInput() {
        if (textInput == null) {
            return;
        }
        String text = textInput.getText().toString();
        if (!text.isEmpty()) {
            terminal.sendRaw(text + "\r");
            textInput.setText("");
        }
        textInput.requestFocus();
        showTextInputKeyboard();
    }

    private View buildExtraKeys() {
        SwipeKeysLayout wrap = new SwipeKeysLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(0xFF0E141C);
        wrap.setPadding(dp(3), dp(3), dp(3), dp(3));
        wrap.setSwipeListener(this::toggleTextInput);

        // Termux default: two rows, same keys, same order. The direction keys
        // use arrow glyphs so they read as arrows on the phone.
        String[][] rows = {
                {"ESC", "/", "-", "HOME", "\u25B2", "END", "PGUP"},
                {"TAB", "SHIFT", "CTRL", "ALT", "\u25C0", "\u25BC", "\u25B6",
                        "PGDN"}
        };
        for (String[] labels : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (String label : labels) {
                row.addView(buildKey(label));
            }
            wrap.addView(row);
        }
        return wrap;
    }

    private TextView buildKey(String label) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, isArrow(label) ? 15 : 11);
        b.setTextColor(0xFF9FB0BF);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(8), 0, dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1B2430);
        bg.setCornerRadius(dp(5));
        b.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(lp);

        if ("SHIFT".equals(label)) {
            shiftKey = b;
            b.setOnClickListener(v -> {
                terminal.setShift(!terminal.shiftArmed());
                paintModifiers();
            });
        } else if ("CTRL".equals(label)) {
            ctrlKey = b;
            b.setOnClickListener(v -> {
                terminal.setCtrl(!terminal.ctrlArmed());
                paintModifiers();
            });
        } else if ("ALT".equals(label)) {
            altKey = b;
            b.setOnClickListener(v -> {
                terminal.setAlt(!terminal.altArmed());
                paintModifiers();
            });
        } else {
            attachHoldRepeat(b, keySequence(label));
        }
        return b;
    }

    /**
     * Sends the key immediately, then repeats it while the button stays
     * pressed. A terminal keyboard should behave like a held key, not like a
     * one-shot web button.
     */
    private void attachHoldRepeat(TextView button, String sequence) {
        Handler handler = new Handler(Looper.getMainLooper());
        final Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            terminal.sendRaw(sequence);
            paintModifiers();
            handler.postDelayed(repeat[0], 70);
        };
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    terminal.sendRaw(sequence);
                    paintModifiers();
                    handler.removeCallbacks(repeat[0]);
                    handler.postDelayed(repeat[0], 400);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(repeat[0]);
                    v.setPressed(false);
                    return true;
                default:
                    return false;
            }
        });
    }

    private static String keySequence(String label) {
        switch (label) {
            case "ESC":
                return "\u001b";
            case "TAB":
                return "\t";
            case "HOME":
                return "\u001b[H";
            case "UP":
            case "\u25B2":
                return "\u001b[A";
            case "END":
                return "\u001b[F";
            case "PGUP":
                return "\u001b[5~";
            case "LEFT":
            case "\u25C0":
                return "\u001b[D";
            case "DOWN":
            case "\u25BC":
                return "\u001b[B";
            case "RIGHT":
            case "\u25B6":
                return "\u001b[C";
            case "PGDN":
                return "\u001b[6~";
            default:
                return label;
        }
    }

    private static boolean isArrow(String label) {
        return "\u25B2".equals(label) || "\u25BC".equals(label)
                || "\u25C0".equals(label) || "\u25B6".equals(label);
    }

    private void paintModifiers() {
        if (shiftKey != null) {
            shiftKey.setTextColor(terminal.shiftArmed() ? 0xFF00E5A0 : 0xFF9FB0BF);
        }
        if (ctrlKey != null) {
            ctrlKey.setTextColor(terminal.ctrlArmed() ? 0xFF00E5A0 : 0xFF9FB0BF);
        }
        if (altKey != null) {
            altKey.setTextColor(terminal.altArmed() ? 0xFF00E5A0 : 0xFF9FB0BF);
        }
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF111823);
        bar.setPadding(dp(14), dp(10), dp(8), dp(10));

        bar.addView(barButton("\u2630", v -> openCliDrawer()));

        dot = new TextView(this);
        dot.setText("\u25CF");
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dot.setTextColor(0xFFE5C07B);
        bar.addView(dot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(10);
        bar.addView(titles, titleParams);

        TextView title = new TextView(this);
        title.setText("Attc code");
        title.setTextColor(0xFFE6EDF3);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titles.addView(title);

        statusText = new TextView(this);
        statusText.setText("Starting...");
        statusText.setTextColor(0xFF7D8B99);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(statusText);

        bar.addView(barButton("\uD83D\uDCCE", v -> pickFile()));
        bar.addView(barButton("\uD83D\uDDBC", v -> pickMedia()));
        bar.addView(barButton("\uD83D\uDDC2", v -> showPanel()));
        bar.addView(barButton("\u2328", v -> terminal.focusAndShowKeyboard()));
        bar.addView(barButton("\u2699", v -> showSettings()));

        return bar;
    }

    private TextView barButton(String label, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(0xFF9FB0BF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setOnClickListener(click);
        return b;
    }

    /** A left-to-right drawer listing every CLI window, running or not. */
    private void buildCliDrawer() {
        if (stage == null || cliDrawerScrim != null) {
            return;
        }
        cliDrawerScrim = new FrameLayout(this);
        cliDrawerScrim.setBackgroundColor(0x99000000);
        cliDrawerScrim.setVisibility(View.GONE);
        cliDrawerScrim.setClickable(true);
        cliDrawerScrim.setOnClickListener(v -> closeCliDrawer());
        stage.addView(cliDrawerScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(0xFF0F1620);
        drawer.setClickable(true);
        drawer.setPadding(dp(14), dp(14), dp(14), dp(10));
        drawer.setTranslationX(-dp(310));
        cliDrawerScrim.addView(drawer, new FrameLayout.LayoutParams(
                dp(300), ViewGroup.LayoutParams.MATCH_PARENT));
        cliDrawer = drawer;

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        drawer.addView(header);

        TextView title = new TextView(this);
        title.setText("CLI windows");
        title.setTextColor(0xFFE6EDF3);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleParams);

        TextView close = barButton("\u00D7", v -> closeCliDrawer());
        header.addView(close);

        TextView hint = new TextView(this);
        hint.setText("Every CLI keeps running in its own PTY. Tap one to switch.");
        hint.setTextColor(0xFF7D8B99);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(0, dp(2), 0, dp(10));
        drawer.addView(hint);

        ScrollView scroll = new ScrollView(this);
        cliDrawerList = new LinearLayout(this);
        cliDrawerList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(cliDrawerList);
        drawer.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void openCliDrawer() {
        if (cliDrawerScrim == null) {
            buildCliDrawer();
        }
        if (cliDrawerScrim == null) {
            return;
        }
        refreshCliDrawer();
        cliDrawerScrim.setVisibility(View.VISIBLE);
        cliDrawer.setTranslationX(-dp(310));
        cliDrawer.animate().translationX(0f).setDuration(180).start();
    }

    private void closeCliDrawer() {
        if (cliDrawerScrim == null || cliDrawerScrim.getVisibility() != View.VISIBLE) {
            return;
        }
        cliDrawer.animate().translationX(-dp(310)).setDuration(160)
                .withEndAction(() -> cliDrawerScrim.setVisibility(View.GONE))
                .start();
    }

    private void refreshCliDrawer() {
        if (cliDrawerList == null) {
            return;
        }
        cliDrawerList.removeAllViews();
        if (!Hub.sessions.isEmpty()) {
            cliDrawerList.addView(drawerSection("Running"));
            for (Session session : Hub.sessions) {
                boolean active = session == Hub.session;
                String detail = session.isRunning()
                        ? (session.cliRunning() ? "running" : "shell prompt")
                        : "closed";
                cliDrawerList.addView(cliDrawerRow(
                        cliKind(session.cli()), session.label() + "  #" + session.id(),
                        detail, active, () -> switchToSession(session)));
            }
        }
        cliDrawerList.addView(drawerSection("Open another"));
        String[][] options = {
                {"opencode", "Attc Code"}, {"claude", "Claude Code"},
                {"agy", "Antigravity"}, {"codex", "Codex"},
                {"kimi", "Kimi Code"}, {"terminal", "Ubuntu terminal"}};
        for (String[] option : options) {
            Session running = Hub.findByCli(option[0]);
            String detail = running != null ? "running - tap to switch"
                    : "open in a new window";
            cliDrawerList.addView(cliDrawerRow(
                    cliKind(option[0]), option[1], detail, false,
                    () -> startCli(option[0])));
        }
    }

    private TextView drawerSection(String text) {
        TextView view = new TextView(this);
        view.setText(text.toUpperCase());
        view.setTextColor(0xFF5F6C7A);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(2), dp(12), 0, dp(4));
        return view;
    }

    private View cliDrawerRow(int kind, String name, String detail,
                              boolean active, Runnable click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(active ? 0xFF163326 : 0xFF141C26);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), active ? 0xFF00E5A0 : 0xFF1F2A37);
        row.setBackground(bg);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(7);
        row.setLayoutParams(params);

        row.addView(new CliLogo(this, kind), new LinearLayout.LayoutParams(
                dp(30), dp(30)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(10);
        row.addView(texts, textParams);

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(0xFFE6EDF3);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        texts.addView(title);

        TextView sub = new TextView(this);
        sub.setText(detail);
        sub.setTextColor(active ? 0xFF00E5A0 : 0xFF7D8B99);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        texts.addView(sub);

        row.setOnClickListener(v -> {
            closeCliDrawer();
            click.run();
        });
        return row;
    }

    private void switchToSession(Session session) {
        if (session == null || !session.isRunning()) {
            return;
        }
        Hub.setActive(session);
        statusText.setText(session.cliRunning() ? "Running" : "Terminal");
        startService(new Intent(this, TerminalService.class)
                .setAction(TerminalService.ACTION_SWITCH)
                .putExtra(TerminalService.EXTRA_CLI, session.cli()));
    }

    private static int cliKind(String cli) {
        if ("claude".equals(cli)) {
            return CliLogo.CLAUDE;
        }
        if ("agy".equals(cli)) {
            return CliLogo.ANTIGRAVITY;
        }
        if ("codex".equals(cli)) {
            return CliLogo.CODEX;
        }
        if ("kimi".equals(cli)) {
            return CliLogo.KIMI;
        }
        if ("terminal".equals(cli)) {
            return CliLogo.TERMINAL;
        }
        return CliLogo.ATTC;
    }

    // ------------------------------------------------------------- lifecycle

    private void startTerminal() {
        boolean consented = prefs.getBoolean(KEY_DOWNLOAD_CONSENT, false);
        if (!consented && new Setup(this).isRootfsReady()) {
            // Existing installations already downloaded the runtime; do not
            // ask again after an update.
            prefs.edit().putBoolean(KEY_DOWNLOAD_CONSENT, true).apply();
            consented = true;
        }
        if (!consented) {
            // Wait for the user to pick a CLI; startCli() asks for consent
            // before anything is downloaded.
            return;
        }
        Intent i = new Intent(this, TerminalService.class)
                .setAction(TerminalService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    @Override
    public void onOutput() {
        runOnUiThread(this::refresh);
    }

    private void refresh() {
        if (terminal != null && Hub.buffer != null && Hub.buffer != terminal.buffer()) {
            terminal.setBuffer(Hub.buffer);
        }
        String s = Hub.status;
        if (statusText != null) {
            statusText.setText(TextUtils.isEmpty(s) ? "Running" : s);
        }
        if (dot != null) {
            dot.setTextColor(Hub.failed ? 0xFFE06C75 : 0xFF00E5A0);
        }
        if (retry != null) {
            retry.setVisibility(Hub.failed ? View.VISIBLE : View.GONE);
        }
        if (cliDrawerScrim != null && cliDrawerScrim.getVisibility() == View.VISIBLE) {
            refreshCliDrawer();
        }
        if (Hub.cliExited) {
            Hub.cliExited = false;
            showChooser();
        }
    }

    // ------------------------------------------------------------ permissions

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < API_TIRAMISU) {
            return;
        }
        if (checkSelfPermission(PERM_POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{PERM_POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    private void maybeExplainStorage() {
        if (hasStorage()) {
            return;
        }
        if (prefs.getBoolean(KEY_STORAGE_ASKED, false)) {
            return;
        }
        prefs.edit().putBoolean(KEY_STORAGE_ASKED, true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Shared folder access")
                .setMessage("Attc code keeps your files in \"/storage/emulated/0/ATTC OPENCODE\". "
                        + "Android needs \"All files access\" for that folder to be readable and "
                        + "writable from inside the Ubuntu terminal.\n\n"
                        + "You can grant it now, or later from the settings button.")
                .setPositiveButton("Grant", (d, w) -> openStorageSettings())
                .setNegativeButton("Later", null)
                .show();
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= API_R) {
            return allFilesGranted();
        }
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Environment.isExternalStorageManager() via reflection (API 30+). */
    private static boolean allFilesGranted() {
        try {
            Object result = Class.forName("android.os.Environment")
                    .getMethod("isExternalStorageManager")
                    .invoke(null);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void openStorageSettings() {
        try {
            if (Build.VERSION.SDK_INT >= API_R) {
                Intent i = new Intent(ACTION_MANAGE_APP_ALL_FILES);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else {
                requestPermissions(
                        new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 11);
            }
        } catch (Exception e) {
            try {
                startActivity(new Intent(ACTION_MANAGE_ALL_FILES));
            } catch (Exception ignored) {
            }
        }
    }

    // --------------------------------------------------------------- settings

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, dp(4));

        section(box, "Terminal");

        CheckBox boot = new CheckBox(this);
        boot.setText("Start terminal on boot");
        boot.setTextColor(0xFFE6EDF3);
        boot.setChecked(prefs.getBoolean(KEY_BOOT, false));
        boot.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(KEY_BOOT, checked).apply());
        box.addView(boot);

        CheckBox awake = new CheckBox(this);
        awake.setText("Keep awake (screen off)");
        awake.setTextColor(0xFFE6EDF3);
        awake.setChecked(Hub.keepAwake);
        awake.setOnCheckedChangeListener((v, checked) ->
                startService(new Intent(this, TerminalService.class).setAction(
                        checked ? TerminalService.ACTION_KEEP_AWAKE
                                : TerminalService.ACTION_DISABLE_AWAKE)));
        box.addView(awake);

        box.addView(item("Restart terminal", v -> {
            startService(new Intent(this, TerminalService.class)
                    .setAction(TerminalService.ACTION_RESTART));
        }));
        box.addView(item("Fully close app", v ->
                startService(new Intent(this, TerminalService.class)
                        .setAction(TerminalService.ACTION_CLOSE))));

        section(box, "Voice assistant (" + VoiceEngine.phrase(this) + ")");

        voiceToggle = new CheckBox(this);
        voiceToggle.setText("Listen for \"" + VoiceEngine.phrase(this) + "\"");
        voiceToggle.setTextColor(0xFFE6EDF3);
        voiceToggle.setChecked(prefs.getBoolean(VoiceEngine.KEY_ENABLED, false));
        voiceToggle.setOnCheckedChangeListener((v, checked) -> {
            if (suppressVoiceToggle) {
                return;
            }
            if (checked) {
                beginVoiceEnable(voiceToggle);
            } else {
                setVoiceEnabled(false);
            }
        });
        box.addView(voiceToggle);

        note(box, "Private, offline wake word from three samples. The recognised "
                + "sentence is typed into the selected CLI; speech to text may use "
                + "the phone's recognition service.");

        if (!VoiceEngine.hasPermission(this)) {
            box.addView(item("Grant microphone permission",
                    v -> requestVoicePermission()));
        }

        box.addView(item("Record \"" + VoiceEngine.phrase(this) + "\" again",
                v -> recordWakeWord(voiceToggle)));
        box.addView(item("Test wake now", v ->
                sendService(new Intent(this, TerminalService.class)
                        .setAction(TerminalService.ACTION_VOICE_WAKE_TEST))));

        section(box, "Network");

        fakeVpnToggle = new CheckBox(this);
        fakeVpnToggle.setText("Fake IP in Ubuntu (no system VPN)");
        fakeVpnToggle.setTextColor(0xFFE6EDF3);
        fakeVpnToggle.setChecked(VpnState.isEnabled(this));
        fakeVpnToggle.setOnCheckedChangeListener((v, checked) -> {
            if (suppressVpnToggle) {
                return;
            }
            if (checked) {
                enableFakeVpn();
            } else {
                confirmDisableFakeVpn();
            }
        });
        box.addView(fakeVpnToggle);

        fakeVpnIpText = new TextView(this);
        fakeVpnIpText.setText(fakeVpnStatusText());
        fakeVpnIpText.setTextColor(0xFF7D8B99);
        fakeVpnIpText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(fakeVpnIpText);

        box.addView(item("Advanced…", v -> showAdvancedSettings()));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();
        dialog.show();
    }

    /** Rarely used tools live behind one more tap, so Settings stays short. */
    private void showAdvancedSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, dp(4));

        section(box, "Voice & privacy");

        box.addView(item("Wake phrase: " + VoiceEngine.phrase(this),
                v -> chooseWakePhrase(voiceToggle)));
        box.addView(item("Voice CLI: " + voiceCliLabel(), v -> chooseVoiceCli()));
        note(box, "Antigravity live voice needs a plan with Gemini Live. If "
                + "/voice live says live mode is unavailable, the wake word still "
                + "types your request into the CLI, and the audio bridge carries "
                + "any sound the CLI itself plays.");
        CheckBox agyVoice = new CheckBox(this);
        agyVoice.setText("Antigravity voice on launch");
        agyVoice.setTextColor(0xFFE6EDF3);
        agyVoice.setChecked(prefs.getBoolean(TerminalService.PREF_AGY_VOICE, true));
        agyVoice.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(TerminalService.PREF_AGY_VOICE, checked).apply());
        box.addView(agyVoice);
        note(box, "Sends /voice live about 12 seconds after Antigravity starts. "
                + "It is ignored when the account does not include Gemini Live.");

        box.addView(item("Forget wake word", v -> forgetWakeWord()));
        box.addView(item("Battery optimization exemption",
                v -> requestBatteryExemption()));

        CheckBox shield = new CheckBox(this);
        shield.setText("Private device identity");
        shield.setTextColor(0xFFE6EDF3);
        shield.setChecked(Privacy.enabled(this));
        shield.setOnCheckedChangeListener((v, checked) -> {
            Privacy.setEnabled(this, checked);
            Privacy.apply(this, new Setup(this).rootDir());
            startService(new Intent(this, TerminalService.class)
                    .setAction(TerminalService.ACTION_PRIVACY));
        });
        box.addView(shield);
        note(box, "Rotates machine-id, hostname and MAC every 30 minutes. The "
                + "public IP needs a VPN or proxy.");

        section(box, "Network tools");

        box.addView(item("Exit network: " + Egress.mode(this),
                v -> showEgressDialog()));
        box.addView(item("New IP now (rotate exit)", v -> rotateEgress()));
        box.addView(item("New IP + new fingerprint",
                v -> rotateNetworkWithFingerprint()));
        box.addView(item("Upstream proxy: " + upstreamLabel(),
                v -> showUpstreamDialog()));
        box.addView(item("Check exit IP (what servers see)", v -> checkExitIp()));
        box.addView(item("Change simulated IP now", v -> {
            if (VpnState.isRunning()) {
                try {
                    startService(new Intent(this, FakeVpnService.class)
                            .setAction(FakeVpnService.ACTION_ROTATE));
                } catch (Exception ignored) {
                }
                if (fakeVpnIpText != null) {
                    fakeVpnIpText.postDelayed(() -> {
                        if (fakeVpnIpText != null) {
                            fakeVpnIpText.setText(fakeVpnStatusText());
                        }
                    }, 400);
                }
            } else {
                VpnState.rotate(this);
                if (fakeVpnIpText != null) {
                    fakeVpnIpText.setText(fakeVpnStatusText());
                }
            }
        }));
        box.addView(item("Run guest IP check", v -> {
            if (fakeVpnIpText != null) {
                fakeVpnIpText.setText("Guest check: running…");
            }
            new Thread(IpEcho::selfTest).start();
            if (fakeVpnIpText != null) {
                fakeVpnIpText.postDelayed(() -> {
                    if (fakeVpnIpText != null) {
                        fakeVpnIpText.setText(fakeVpnStatusText());
                    }
                }, 9000);
            }
        }));
        box.addView(item("Reset quota", v -> {
            QuotaReset.trigger(this, "manual");
            Toast.makeText(this, "Quota reset started: new opencode server address",
                    Toast.LENGTH_SHORT).show();
        }));
        box.addView(item("Rotate identity now", v ->
                Privacy.apply(this, new Setup(this).rootDir())));

        section(box, "System");

        box.addView(item("Update opencode now", v ->
                startService(new Intent(this, TerminalService.class)
                        .setAction(TerminalService.ACTION_UPDATE))));
        shizukuRow = item("Shizuku: " + shizukuStatus, v -> {
            if (shizukuGranted) {
                ShizukuHelper.grantOwnPermissions(this);
            } else {
                ShizukuHelper.request(this);
            }
        });
        box.addView(shizukuRow);
        box.addView(item("All files access", v -> openStorageSettings()));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Advanced")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .show();
    }

    private void forgetWakeWord() {
        new AlertDialog.Builder(this)
                .setTitle("Forget wake word")
                .setMessage("Removes the three stored samples and turns listening "
                        + "off. Record again when you are ready.")
                .setPositiveButton("Forget", (d, w) -> {
                    VoiceEngine.clearTemplates(this);
                    setVoiceEnabled(false);
                    restoreVoiceToggle(voiceToggle, false);
                    Toast.makeText(this, "Wake word removed",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void section(LinearLayout box, String title) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextColor(0xFFE6EDF3);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setPadding(0, dp(18), 0, dp(2));
        box.addView(view);
    }

    private void note(LinearLayout box, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xFF7D8B99);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        box.addView(view);
    }

    // ------------------------------------------------------------- voice setup

    private void beginVoiceEnable(CheckBox box) {
        if (!VoiceEngine.hasPermission(this)) {
            requestVoicePermission();
            return;
        }
        if (VoiceEngine.hasTemplates(this)) {
            setVoiceEnabled(true);
            Toast.makeText(this, VoiceEngine.phrase(this) + " listening",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        recordWakeWord(box);
    }

    private void setVoiceEnabled(boolean on) {
        prefs.edit().putBoolean(VoiceEngine.KEY_ENABLED, on).apply();
        sendService(new Intent(this, TerminalService.class)
                .setAction(on ? TerminalService.ACTION_VOICE_ENABLE
                        : TerminalService.ACTION_VOICE_DISABLE));
    }

    /**
     * Asks for the microphone, and falls back to the system app page when the
     * permission was denied permanently (Android stops showing the dialog).
     */
    private void requestVoicePermission() {
        if (VoiceEngine.hasPermission(this)) {
            if (voiceToggle != null) {
                beginVoiceEnable(voiceToggle);
            }
            return;
        }
        boolean asked = prefs.getBoolean("voice_perm_asked", false);
        if (asked && !shouldShowRequestPermissionRationale(PERM_RECORD_AUDIO)) {
            new AlertDialog.Builder(this)
                    .setTitle("Microphone permission")
                    .setMessage("Microphone access was denied. Enable it in the app "
                            + "settings to use voice activation.")
                    .setPositiveButton("Open settings", (d, w) -> openAppSettings())
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        prefs.edit().putBoolean("voice_perm_asked", true).apply();
        pendingEnableVoice = true;
        requestPermissions(new String[]{PERM_RECORD_AUDIO}, REQ_VOICE_MIC);
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "App settings not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendService(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void recordWakeWord(final CheckBox box) {
        final String phrase = VoiceEngine.phrase(this);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, dp(10), pad, dp(2));

        final TextView step = new TextView(this);
        step.setText("Sample 1 of 3");
        step.setTextColor(0xFFE6EDF3);
        step.setTypeface(Typeface.DEFAULT_BOLD);
        step.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        content.addView(step);

        final TextView hint = new TextView(this);
        hint.setText("Say \"" + phrase + "\" clearly, then pause. The meter "
                + "shows what the microphone hears.");
        hint.setTextColor(0xFF7D8B99);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(0, dp(4), 0, dp(12));
        content.addView(hint);

        final ProgressBar meter = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        meter.setMax(100);
        meter.setProgress(0);
        if (meter.getProgressDrawable() != null) {
            meter.getProgressDrawable().setColorFilter(0xFF00E5A0,
                    PorterDuff.Mode.SRC_IN);
        }
        content.addView(meter, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));

        final TextView status = new TextView(this);
        status.setText("Starting the microphone…");
        status.setTextColor(0xFF7D8B99);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setPadding(0, dp(8), 0, 0);
        content.addView(status);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Record \"" + phrase + "\"")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setCancelable(false)
                .create();

        final boolean[] running = {false};
        final boolean[] cancelled = {false};
        final boolean[] finished = {false};

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                cancelled[0] = true;
                if (running[0]) {
                    VoiceEngine.cancelEnroll();
                }
                dialog.dismiss();
                if (!finished[0]) {
                    restoreVoiceToggle(box, false);
                }
            });
            uiHandler.postDelayed(() -> {
                if (cancelled[0] || isFinishing()) {
                    return;
                }
                running[0] = true;
                restoreVoiceToggle(box, false);
                setVoiceEnabled(false);
                status.setText("Listening…");
                VoiceEngine.enroll(this, new VoiceEngine.EnrollListener() {
                    @Override
                    public void onProgress(int done) {
                        Logger.i("voice: enrol progress " + done + "/3");
                        if (finished[0]) {
                            return;
                        }
                        if (done < 3) {
                            step.setText("Sample " + (done + 1) + " of 3");
                            status.setText("Captured " + done + " of 3. Say \""
                                    + phrase + "\" again.");
                        } else {
                            step.setText("All 3 samples captured");
                            status.setText("Saving the private model…");
                        }
                        vibrate(70);
                    }

                    @Override
                    public void onLevel(float level) {
                        if (!finished[0]) {
                            meter.setProgress(Math.round(level * 100f));
                        }
                    }

                    @Override
                    public void onDone(boolean ok, String message) {
                        running[0] = false;
                        finished[0] = true;
                        Logger.i("voice: enrol done ok=" + ok + " " + message);
                        if (cancelled[0]) {
                            return;
                        }
                        if (ok) {
                            dialog.dismiss();
                            setVoiceEnabled(true);
                            restoreVoiceToggle(box, true);
                            vibrate(120);
                            Toast.makeText(MainActivity.this,
                                    phrase + " is ready", Toast.LENGTH_LONG).show();
                        } else {
                            step.setText("Recording failed");
                            status.setText(message);
                            meter.setProgress(0);
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                                    .setText("Close");
                        }
                    }
                });
            }, 900L);
        });
        dialog.show();
    }

    private void restoreVoiceToggle(CheckBox box, boolean checked) {
        suppressVoiceToggle = true;
        if (box != null) {
            box.setChecked(checked);
        }
        suppressVoiceToggle = false;
    }

    private void vibrate(long millis) {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(
                    Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(millis);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Lets the user pick the phrase that arms the assistant. The detector is
     * speaker dependent, not vocabulary dependent, so any short phrase works.
     * Changing the phrase invalidates recordings made for the old one.
     */
    private void chooseWakePhrase(final CheckBox box) {
        final String current = VoiceEngine.phrase(this);
        String[] labels = new String[VoiceEngine.PHRASE_PRESETS.length + 1];
        int checked = -1;
        for (int i = 0; i < VoiceEngine.PHRASE_PRESETS.length; i++) {
            labels[i] = VoiceEngine.PHRASE_PRESETS[i];
            if (VoiceEngine.PHRASE_PRESETS[i].equalsIgnoreCase(current)) {
                checked = i;
            }
        }
        labels[labels.length - 1] = "Custom phrase...";
        new AlertDialog.Builder(this)
                .setTitle("Wake phrase")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    if (which == labels.length - 1) {
                        askCustomWakePhrase(box, current);
                    } else {
                        applyWakePhrase(VoiceEngine.PHRASE_PRESETS[which], box);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askCustomWakePhrase(final CheckBox box, String current) {
        final EditText input = new EditText(this);
        input.setHint("e.g. Merhaba Attc");
        input.setText(current);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Custom wake phrase")
                .setMessage("Two or three short words work best.")
                .setView(input)
                .setPositiveButton("Save", (d, w) ->
                        applyWakePhrase(input.getText().toString(), box))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyWakePhrase(String value, CheckBox box) {
        String phrase = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (phrase.isEmpty()) {
            Toast.makeText(this, "The phrase cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }
        if (phrase.equals(VoiceEngine.phrase(this))) {
            return;
        }
        prefs.edit().putString(VoiceEngine.KEY_PHRASE, phrase).apply();
        VoiceEngine.clearTemplates(this);
        setVoiceEnabled(false);
        if (box != null) {
            suppressVoiceToggle = true;
            box.setChecked(false);
            suppressVoiceToggle = false;
        }
        new AlertDialog.Builder(this)
                .setTitle("Record the new phrase")
                .setMessage("Wake phrase set to \"" + phrase + "\". The old recordings "
                        + "were made for the previous phrase and were removed. Record "
                        + "three new samples now?")
                .setPositiveButton("Record", (d, w) -> recordWakeWord(box))
                .setNegativeButton("Later", null)
                .show();
    }

    private void chooseVoiceCli() {
        final String[] values = new String[VOICE_CLIS.length + 1];
        final String[] labels = new String[VOICE_CLIS.length + 1];
        values[0] = "";
        labels[0] = "Antigravity (default)";
        for (int i = 0; i < VOICE_CLIS.length; i++) {
            values[i + 1] = VOICE_CLIS[i];
            labels[i + 1] = SessionStore.label(VOICE_CLIS[i]);
        }
        String current = prefs.getString(VoiceEngine.KEY_CLI, "");
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Voice CLI")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    prefs.edit().putString(VoiceEngine.KEY_CLI, values[which]).apply();
                    Toast.makeText(this, "Voice CLI: " + labels[which],
                            Toast.LENGTH_SHORT).show();
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String voiceCliLabel() {
        String cli = prefs.getString(VoiceEngine.KEY_CLI, "");
        if (cli == null || cli.isEmpty()) {
            return "Antigravity (default)";
        }
        return SessionStore.label(cli);
    }

    private void requestBatteryExemption() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Already exempt from battery optimization",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "Battery settings not found",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Automated enrolment hook: play the wake phrase three times from adb. */
    private void runVoiceEnrollTest() {
        setVoiceEnabled(false);
        uiHandler.postDelayed(() -> VoiceEngine.enroll(this,
                new VoiceEngine.EnrollListener() {
                    @Override
                    public void onProgress(int done) {
                        Logger.i("test: voice enrol " + done + "/3");
                    }

                    @Override
                    public void onDone(boolean ok, String message) {
                        Logger.i("test: voice enrol done ok=" + ok + " " + message);
                        if (ok) {
                            setVoiceEnabled(true);
                        }
                    }
                }), 900L);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_VOICE_MIC) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && pendingEnableVoice) {
            pendingEnableVoice = false;
            if (voiceToggle != null) {
                beginVoiceEnable(voiceToggle);
            } else {
                setVoiceEnabled(true);
            }
        } else if (!granted) {
            pendingEnableVoice = false;
            suppressVoiceToggle = true;
            if (voiceToggle != null) {
                voiceToggle.setChecked(false);
            }
            suppressVoiceToggle = false;
            Toast.makeText(this, "Microphone permission is required for voice activation",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------- fake vpn

    private String fakeVpnStatusText() {
        return "Simulated IP: " + VpnState.getSimulatedIp(this)
                + "  (" + VpnState.poolSize() + " addresses, 30 min rotation)"
                + "\nGuest check: " + IpEcho.guestCheck()
                + "   |   Proxy check: " + IpEcho.lastCheck()
                + "\nExit route: " + Egress.describe(this)
                + "\nServers see: " + Upstream.lastExit()
                + "  (upstream: " + Upstream.describe(this) + ")"
                + (Egress.rotationBlocked(this)
                ? "\nHint: the VPN app forbids bypass, so the exit cannot rotate. "
                + "Turn it off or exclude Attc code in the VPN." : "");
    }

    /** Auto rotates between the radios; a fixed choice pins one exit. */
    private void showEgressDialog() {
        final String[] modes = {Egress.MODE_AUTO, Egress.MODE_WIFI,
                Egress.MODE_MOBILE, Egress.MODE_VPN};
        String[] labels = {"Auto (rotate WiFi / mobile / VPN)", "WiFi only",
                "Mobile data only", "VPN only"};
        int checked = 0;
        String mode = Egress.mode(this);
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(mode)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Exit network")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    Egress.setMode(this, modes[which]);
                    d.dismiss();
                    if (fakeVpnIpText != null) {
                        fakeVpnIpText.setText(fakeVpnStatusText());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Switches opencode to a different public address right away. */
    private void rotateEgress() {
        if (fakeVpnIpText != null) {
            fakeVpnIpText.setText("Rotating exit…");
        }
        new Thread(() -> {
            Egress.rotate(this);
            Upstream.exitIp(this);
            runOnUiThread(() -> {
                if (fakeVpnIpText != null) {
                    fakeVpnIpText.setText(fakeVpnStatusText());
                }
            });
        }, "attc-egress-rotate").start();
    }

    private void rotateNetworkWithFingerprint() {
        if (fakeVpnIpText != null) fakeVpnIpText.setText("Rotating network + fingerprint…");
        new Thread(() -> {
            String exit = Egress.rotateWithFingerprint(this, new Setup(this).rootDir());
            Upstream.exitIp(this);
            runOnUiThread(() -> {
                if (fakeVpnIpText != null) fakeVpnIpText.setText(fakeVpnStatusText());
                toast("New exit: " + exit);
            });
        }, "attc-net-rotate").start();
    }

    private String upstreamLabel() {
        String spec = Upstream.spec(this);
        if (spec.isEmpty()) {
            return "direct (real IP)";
        }
        return spec;
    }

    /** Lets the user type a socks5:// or http:// endpoint used for the exit. */
    private void showUpstreamDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("socks5://host:port  or  http://user:pass@host:port");
        input.setText(Upstream.spec(this));
        input.setTextColor(0xFFE6EDF3);
        input.setHintTextColor(0xFF7D8B99);

        TextView note = new TextView(this);
        note.setText("Leave empty for a direct connection. SOCKS5 works with "
                + "WireGuard/V2Ray/Tor local inbounds; HTTP is the CONNECT form. "
                + "The file /sdcard/ATTC OPENCODE/upstream.txt is read as a fallback.");
        note.setTextColor(0xFF7D8B99);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(18), dp(8), dp(18), 0);
        wrap.addView(input);
        wrap.addView(note);

        new AlertDialog.Builder(this)
                .setTitle("Upstream proxy")
                .setView(wrap)
                .setPositiveButton("Save", (d, w) -> {
                    Upstream.save(this, input.getText().toString());
                    checkExitIp();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Measures the address the outside world sees and shows it in the status. */
    private void checkExitIp() {
        if (fakeVpnIpText != null) {
            fakeVpnIpText.setText("Checking exit IP…");
        }
        new Thread(() -> {
            Upstream.exitIp(this);
            runOnUiThread(() -> {
                if (fakeVpnIpText != null) {
                    fakeVpnIpText.setText(fakeVpnStatusText());
                }
            });
        }, "attc-exit-check").start();
    }

    private void enableFakeVpn() {
        // The interception is a loopback proxy and needs no system VPN. Taking
        // the VPN slot used to revoke the user's real VPN, which turned a
        // masked address into the real one, so this switch only turns the
        // local interception on.
        VpnState.setEnabled(this, true);
        Logger.i("vpn: fake ip ON (local interception, system VPN untouched)");
        stopSystemVpn();
        if (fakeVpnIpText != null) {
            fakeVpnIpText.setText(fakeVpnStatusText());
        }
    }

    /** Turning the interception off must be deliberate: it exposes the real IP. */
    private void confirmDisableFakeVpn() {
        if (fakeVpnToggle != null) {
            suppressVpnToggle = true;
            fakeVpnToggle.setChecked(true);
            suppressVpnToggle = false;
        }
        new AlertDialog.Builder(this)
                .setTitle("Turn off fake IP?")
                .setMessage("Programs inside Ubuntu will see your real address again.")
                .setPositiveButton("Turn off", (d, w) -> {
                    if (fakeVpnToggle != null) {
                        suppressVpnToggle = true;
                        fakeVpnToggle.setChecked(false);
                        suppressVpnToggle = false;
                    }
                    disableFakeVpn();
                })
                .setNegativeButton("Keep on", null)
                .show();
    }

    private void disableFakeVpn() {
        VpnState.setEnabled(this, false);
        Logger.i("vpn: fake ip OFF (real ip visible in Ubuntu)");
        stopSystemVpn();
        if (fakeVpnIpText != null) {
            fakeVpnIpText.setText(fakeVpnStatusText());
        }
    }

    /** Releases the VPN slot if an older build is still holding it. */
    private void stopSystemVpn() {
        try {
            startService(new Intent(this, FakeVpnService.class)
                    .setAction(FakeVpnService.ACTION_STOP));
        } catch (Exception ignored) {
        }
    }

    private void startFakeVpnService() {
        Intent i = new Intent(this, FakeVpnService.class)
                .setAction(FakeVpnService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            revertFakeVpnToggle();
        }
    }

    private void revertFakeVpnToggle() {
        // The tunnel is best effort; the guest interception must stay on even
        // when the system refuses the VPN (for example before consent).
        Logger.i("vpn: tunnel not started; spoof stays on");
        if (fakeVpnToggle != null) {
            suppressVpnToggle = true;
            fakeVpnToggle.setChecked(VpnState.isEnabled(this));
            suppressVpnToggle = false;
        }
    }

    /** Best effort silent restart; the user must re-consent if prepare asks. */
    private void maybeStartFakeVpn() {
        // Nothing to start: the interception lives in the local proxy. Only
        // make sure no previous build keeps the system VPN slot occupied.
        if (VpnState.isRunning()) {
            stopSystemVpn();
        }
    }

    /**
     * Test and automation hooks delivered as activity intent extras. Kept in
     * one place so they work both on a cold start and while the activity is
     * already in the foreground.
     */
    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.getStringExtra("cli") != null) {
            // Test hook: pick a CLI without tapping the chooser or opening the
            // keyboard, so resize/zoom runs can be driven from adb. Wait for
            // the first layout so the PTY starts at the real grid size.
            String cli = intent.getStringExtra("cli");
            Logger.i("test: launching " + cli);
            uiHandler.postDelayed(() -> startCli(cli, false), 1200L);
        }
        if (intent.hasExtra("fontsp")) {
            float sp = intent.getFloatExtra("fontsp", terminal.fontSp());
            terminal.setFontSizeSp(sp);
            Logger.i("test: font " + terminal.fontSp() + "sp");
        }
        if (intent.getBooleanExtra("zoomtest", false)) {
            runZoomTest();
        }
        if (intent.getBooleanExtra("voicelisten", false)) {
            Logger.i("test: voice listen on");
            setVoiceEnabled(true);
        }
        if (intent.getBooleanExtra("voicepause", false)) {
            Logger.i("test: voice listen off");
            setVoiceEnabled(false);
        }
        if (intent.getBooleanExtra("voiceenroll", false)) {
            Logger.i("test: voice enrol start");
            runVoiceEnrollTest();
        }
        if (intent.getBooleanExtra("voicediag", false)) {
            Logger.i("test: voice diagnose start");
            setVoiceEnabled(false);
            uiHandler.postDelayed(() -> VoiceEngine.diagnose(this, 8,
                    new VoiceEngine.EnrollListener() {
                        @Override
                        public void onProgress(int done) {
                        }

                        @Override
                        public void onDone(boolean ok, String message) {
                            Logger.i("test: voice diagnose done ok=" + ok
                                    + " " + message);
                        }
                    }), 700L);
        }
        if (intent.getBooleanExtra("voicewake", false)) {
            Logger.i("test: voice wake");
            sendService(new Intent(this, TerminalService.class)
                    .setAction(TerminalService.ACTION_VOICE_WAKE_TEST));
        }
        String voiceCmd = intent.getStringExtra("voicecmd");
        if (voiceCmd != null) {
            Logger.i("test: voice command \"" + voiceCmd + "\"");
            sendService(new Intent(this, TerminalService.class)
                    .setAction(TerminalService.ACTION_VOICE_COMMAND)
                    .putExtra(TerminalService.EXTRA_VOICE_TEXT, voiceCmd));
        }
        String voicePhrase = intent.getStringExtra("voicephrase");
        if (voicePhrase != null) {
            prefs.edit().putString(VoiceEngine.KEY_PHRASE, voicePhrase).apply();
            setVoiceEnabled(false);
            VoiceEngine.clearTemplates(this);
            Logger.i("test: wake phrase set to \"" + voicePhrase + "\"");
        }
        if (intent.getBooleanExtra("voicectl", false)) {
            String ctl = intent.getStringExtra("ctlcmd");
            if (ctl != null) {
                Logger.i("test: voice ctl \"" + ctl + "\"");
                sendService(new Intent(this, TerminalService.class)
                        .setAction(TerminalService.ACTION_VOICE_CTL)
                        .putExtra(TerminalService.EXTRA_VOICE_CTL, ctl));
            }
        }
        if (intent != null && intent.getBooleanExtra("rotate", false)) {
            // Test hook: adb can ask for a rotation without touching the UI.
            rotateEgress();
        }
        if (intent != null && intent.getBooleanExtra("dump", false)) {
            Logger.i("diag: " + QuotaWatch.exportTail(this));
        }
        if (intent != null && intent.getBooleanExtra("resetquota", false)) {
            QuotaReset.trigger(this, "adb-test");
        }
        if (intent != null && intent.getBooleanExtra("quotaprobe", false)) {
            startService(new Intent(this, TerminalService.class)
                    .setAction(TerminalService.ACTION_QUOTA_PROBE));
        }
        if (intent != null && intent.getBooleanExtra("screen", false)) {
            startService(new Intent(this, TerminalService.class)
                    .setAction(TerminalService.ACTION_SCREEN));
        }
        if (intent != null && intent.getBooleanExtra("panel", false)) {
            // Test hook: open the files overlay on demand.
            showPanel();
        }
        if (intent != null && intent.getBooleanExtra("tuiupdate", false)) {
            startService(new Intent(this, TerminalService.class)
                    .setAction(TerminalService.ACTION_TUI_UPDATE));
        }
        if (intent != null && intent.getBooleanExtra("tuiping", false)) {
            startService(new Intent(this, TerminalService.class)
                    .setAction(TerminalService.ACTION_TUI_PING));
        }
        if (intent != null && intent.getBooleanExtra("guestauth", false)) {
            startService(new Intent(this, TerminalService.class)
                    .setAction(TerminalService.ACTION_GUEST_AUTH));
        }
        if (intent != null && intent.getBooleanExtra("runprobe", false)) {
            startService(new Intent(this, TerminalService.class)
                    .setAction(TerminalService.ACTION_RUN_PROBE));
        }
        if (intent != null && intent.getBooleanExtra("fstest", false)) {
            startService(new Intent(this, TerminalService.class)
                    .setAction(TerminalService.ACTION_FS_TEST));
        }
        if (intent != null && intent.getStringExtra("guestcmd") != null) {
            startService(new Intent(this, TerminalService.class)
                    .setAction(TerminalService.ACTION_GUEST_CMD)
                    .putExtra("cmd", intent.getStringExtra("guestcmd")));
        }
        if (intent != null && intent.getStringExtra("attachuri") != null) {
            // Test hook: exercise the same path resolver as the picker.
            attach(Uri.parse(intent.getStringExtra("attachuri")));
        }
    }

    /**
     * Test hook: sweep the font size down to 5sp at the same cadence as a
     * pinch, then dump the terminal once the debounced PTY resize has settled.
     * Used by the adb zoom regression check.
     */
    private void runZoomTest() {
        final float from = terminal.fontSp();
        final float to = 5f;
        final int steps = 40;
        terminal.beginScaleForTest();
        for (int i = 1; i <= steps; i++) {
            final int step = i;
            uiHandler.postDelayed(() -> terminal.setFontSizeSp(
                    from + (to - from) * step / steps), i * 25L);
        }
        uiHandler.postDelayed(terminal::endScaleForTest, steps * 25L + 60L);
        Logger.i("zoomtest: " + from + "sp -> " + to + "sp in " + (steps * 25) + "ms");
        uiHandler.postDelayed(() -> {
            Logger.i("zoomtest: settled at " + terminal.fontSp() + "sp");
            startService(new Intent(this, TerminalService.class)
                    .setAction(TerminalService.ACTION_SCREEN));
        }, steps * 25L + 3000L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE || requestCode == REQ_MEDIA) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                attach(data.getData());
            }
            return;
        }
        if (requestCode != REQ_VPN) {
            return;
        }
        // Consent belongs to older builds that used a system VPN. Never take
        // the slot now: it would disconnect the user's real VPN.
        stopSystemVpn();
        if (fakeVpnToggle != null) {
            suppressVpnToggle = true;
            fakeVpnToggle.setChecked(VpnState.isEnabled(this));
            suppressVpnToggle = false;
        }
    }

    // --------------------------------------------------------------- attach

    /** Opens the system file browser so any file can be attached. */
    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_FILE);
        } catch (Exception e) {
            toast("No file browser found");
        }
    }

    /** Opens the gallery picker filtered to photos and videos. */
    private void pickMedia() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        i.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_MEDIA);
        } catch (Exception e) {
            toast("No gallery found");
        }
    }

    /**
     * Types the picked file's real Android path into the terminal. Content
     * URIs are resolved to a filesystem path when the provider exposes one
     * (see resolveRealPath); otherwise the file is copied into the shared
     * uploads folder and that real path is used. The guest sees /storage
     * through a proot bind, so these paths work directly - no /root/attc
     * translation.
     */
    private void attach(Uri uri) {
        try {
            String name = displayName(uri);
            String safe = name == null ? "file"
                    : name.replaceAll("[^A-Za-z0-9._-]", "_");
            if (safe.isEmpty()) {
                safe = "file";
            }
            String real = resolveRealPath(uri);
            boolean copied = false;
            if (real == null) {
                File dir = new File(Setup.STORAGE_DIR, "uploads");
                // Prune before writing so the new copy is never evicted.
                pruneUploads(dir);
                File out = new File(dir, System.currentTimeMillis() + "-" + safe);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream os = new FileOutputStream(out)) {
                    if (in == null) {
                        throw new IOException("no stream");
                    }
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
                real = out.getAbsolutePath();
                copied = true;
            }
            String typed = shellQuote(real);
            terminal.paste(typed + " ");
            toast(copied ? "Copied to uploads (provider hides the path)"
                    : "Attached " + safe);
            Logger.i("attach: " + typed);
        } catch (Exception e) {
            toast("Attach failed: " + e.getMessage());
        }
    }

    /**
     * Keeps the copy fallback folder bounded: creates it, hides it from the
     * media scanner once, then deletes app-created copies older than seven
     * days and evicts the oldest app-created copies until they total at most
     * 100 MB. Only names written by attach() (timestamp prefix) are eligible;
     * other files, including anything the user put in the folder, are ignored
     * and never deleted or counted. It runs before a new copy is written, so
     * that copy is never a candidate.
     */
    private void pruneUploads(File dir) {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File noMedia = new File(dir, ".nomedia");
        if (!noMedia.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                noMedia.createNewFile();
            } catch (IOException ignored) {
            }
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long total = 0;
        List<File> kept = new ArrayList<>();
        for (File f : files) {
            // Same timestamp prefix as attach(); skip .nomedia and user files.
            if (!f.isFile() || !f.getName().matches("[0-9]{13}-.+")) {
                continue;
            }
            if (now - f.lastModified() > UPLOAD_MAX_AGE_MS) {
                //noinspection ResultOfMethodCallIgnored
                if (f.delete()) {
                    continue;
                }
            }
            kept.add(f);
            total += f.length();
        }
        if (total <= UPLOAD_MAX_BYTES) {
            return;
        }
        File[] oldest = kept.toArray(new File[0]);
        Arrays.sort(oldest, (a, b) ->
                Long.compare(a.lastModified(), b.lastModified()));
        for (File f : oldest) {
            if (total <= UPLOAD_MAX_BYTES) {
                return;
            }
            long len = f.length();
            //noinspection ResultOfMethodCallIgnored
            if (f.delete()) {
                total -= len;
            }
        }
    }

    /**
     * Best-effort real filesystem path for a picker URI, in priority order:
     * file:// paths; the provider's _data column; document ids of the
     * DocumentsUI providers; the photo picker's numeric id, mapped straight
     * to its MediaStore file uri; the media uri embedded in a Google Photos
     * wrapper; MediaStore document ids and other picker forms via
     * reflection; MediaStore relative_path plus display_name; and finally
     * the kernel link behind an open descriptor. Returns null when the
     * provider only exposes a stream, in which case the caller copies the
     * file.
     */
    private String resolveRealPath(Uri uri) {
        if (uri == null) {
            return null;
        }
        if ("file".equals(uri.getScheme())) {
            return normalizeStoragePath(uri.getPath());
        }
        if (!"content".equals(uri.getScheme())) {
            return null;
        }
        String direct = queryDataColumn(uri);
        if (direct != null) {
            return direct;
        }
        String decoded = decodeDocumentId(uri);
        if (decoded != null) {
            return decoded;
        }
        // The Android 13 photo picker hides the row id in its own uri, but
        // MediaStore still resolves content://media/external/file/<id>.
        String picked = mediaPath(pickerIdMediaUri(uri));
        if (picked != null) {
            return picked;
        }
        // Google Photos wraps the media uri in one of its path segments.
        if ("com.google.android.apps.photos.contentprovider"
                .equals(uri.getAuthority())) {
            String wrapped = mediaPath(embeddedMediaUri(uri));
            if (wrapped != null) {
                return wrapped;
            }
        }
        Uri media = mediaDocumentsUri(uri);
        if (media == null) {
            media = pickerMediaUri(uri);
        }
        String viaMedia = mediaPath(media);
        if (viaMedia != null) {
            return viaMedia;
        }
        return readLinkPath(uri);
    }

    /** _data, or relative_path plus display_name, for a MediaStore uri. */
    private String mediaPath(Uri media) {
        if (media == null) {
            return null;
        }
        String data = queryDataColumn(media);
        if (data != null) {
            return data;
        }
        return relativePathFromMedia(media);
    }

    /** Reads the hidden _data column; only readable /storage paths survive. */
    private String queryDataColumn(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(MediaStore.MediaColumns.DATA);
                if (idx >= 0 && !c.isNull(idx)) {
                    return normalizeStoragePath(c.getString(idx));
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    /**
     * Decodes the DocumentsUI document id into a path: "primary:dir/file"
     * becomes /storage/emulated/0/dir/file, "1A2B-3C4D:dir/file" becomes
     * /storage/1A2B-3C4D/dir/file and a downloads "raw:" id is its own path.
     */
    private String decodeDocumentId(Uri uri) {
        String authority = uri.getAuthority();
        if (!"com.android.externalstorage.documents".equals(authority)
                && !"com.android.providers.downloads.documents"
                .equals(authority)) {
            return null;
        }
        String docId;
        try {
            docId = DocumentsContract.getDocumentId(uri);
        } catch (Exception e) {
            return null;
        }
        if (docId == null) {
            return null;
        }
        if ("com.android.providers.downloads.documents".equals(authority)) {
            return docId.startsWith("raw:")
                    ? normalizeStoragePath(docId.substring("raw:".length()))
                    : null;
        }
        int colon = docId.indexOf(':');
        if (colon <= 0 || colon == docId.length() - 1) {
            return null;
        }
        String volume = docId.substring(0, colon);
        String rest = docId.substring(colon + 1);
        String base = "primary".equals(volume)
                ? "/storage/emulated/0" : "/storage/" + volume;
        return normalizeStoragePath(base + "/" + rest);
    }

    /**
     * Rebuilds the MediaStore uri behind a com.android.providers.media
     * .documents id such as "image:1000013385".
     */
    private Uri mediaDocumentsUri(Uri uri) {
        if (!"com.android.providers.media.documents"
                .equals(uri.getAuthority())) {
            return null;
        }
        String docId;
        try {
            docId = DocumentsContract.getDocumentId(uri);
        } catch (Exception e) {
            return null;
        }
        if (docId == null) {
            return null;
        }
        int colon = docId.indexOf(':');
        if (colon <= 0 || colon == docId.length() - 1) {
            return null;
        }
        String type = docId.substring(0, colon);
        String id = docId.substring(colon + 1);
        String segment;
        if ("image".equals(type)) {
            segment = "images";
        } else if ("video".equals(type)) {
            segment = "video";
        } else if ("audio".equals(type)) {
            segment = "audio";
        } else {
            return null;
        }
        return Uri.parse("content://media/external/" + segment + "/media/" + id);
    }

    /**
     * Maps an Android 13 photo-picker uri back to its MediaStore uri with
     * MediaStore.getMediaUri(). The local android.jar is API 28, so the call
     * is reflective and only attempted on API 29+.
     */
    private Uri pickerMediaUri(Uri uri) {
        if (Build.VERSION.SDK_INT < API_Q) {
            return null;
        }
        try {
            Class<?> cls = Class.forName("android.provider.MediaStore");
            Object out = cls.getMethod("getMediaUri", Context.class, Uri.class)
                    .invoke(null, this, uri);
            return out instanceof Uri ? (Uri) out : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Maps a photo-picker uri to its MediaStore file uri. The picker returns
     * authority "media" with a path of
     * picker[_get_content]/<volume>/<provider>/<collection>/<id>, and that
     * numeric id is the MediaStore row id. Other media uris are rejected, so
     * it is safe to try before the reflection lookup.
     */
    private Uri pickerIdMediaUri(Uri uri) {
        if (uri == null || !"media".equals(uri.getAuthority())) {
            return null;
        }
        List<String> segments = uri.getPathSegments();
        if (segments.isEmpty()) {
            return null;
        }
        String kind = segments.get(0);
        if (!"picker".equals(kind) && !"picker_get_content".equals(kind)) {
            return null;
        }
        String id = uri.getLastPathSegment();
        if (id == null || !id.matches("[0-9]{1,19}")) {
            return null;
        }
        Uri out = Uri.parse("content://media/external/file/" + id);
        return out;
    }

    /**
     * Extracts the MediaStore uri that Google Photos embeds as a path segment
     * of its contentprovider wrapper. getPathSegments() already decodes it.
     * Returns null when no segment is a media uri.
     */
    private Uri embeddedMediaUri(Uri uri) {
        if (uri == null) {
            return null;
        }
        for (String segment : uri.getPathSegments()) {
            if (segment == null || !segment.startsWith("content://")) {
                continue;
            }
            Uri inner;
            try {
                inner = Uri.parse(segment);
            } catch (Exception e) {
                continue;
            }
            if (!"media".equals(inner.getAuthority())) {
                continue;
            }
            Uri picker = pickerIdMediaUri(inner);
            if (picker != null) {
                return picker;
            }
            // Already a MediaStore uri: .../[images|video|audio]/media/<id>.
            List<String> innerSegments = inner.getPathSegments();
            int last = innerSegments.size() - 1;
            if (last >= 1 && "media".equals(innerSegments.get(last - 1))
                    && innerSegments.get(last).matches("[0-9]{1,19}")) {
                return inner;
            }
        }
        return null;
    }

    /**
     * Rebuilds a path from MediaStore's relative_path and display_name when
     * a provider hides _data. Both columns are queried as string literals so
     * the API 28 android.jar stays usable.
     */
    private String relativePathFromMedia(Uri media) {
        String relative = null;
        String name = null;
        try (Cursor c = getContentResolver().query(media,
                new String[]{"relative_path", "display_name"},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int rp = c.getColumnIndex("relative_path");
                if (rp >= 0 && !c.isNull(rp)) {
                    relative = c.getString(rp);
                }
                int dn = c.getColumnIndex("display_name");
                if (dn >= 0 && !c.isNull(dn)) {
                    name = c.getString(dn);
                }
            }
        } catch (Exception ignored) {
        }
        if (relative == null || name == null) {
            return null;
        }
        if (!relative.startsWith("/")) {
            relative = "/" + relative;
        }
        if (!relative.endsWith("/")) {
            relative = relative + "/";
        }
        List<String> segments = media.getPathSegments();
        String volume = segments.isEmpty() ? null : segments.get(0);
        if (volume == null) {
            return null;
        }
        String base = "external".equals(volume)
                ? "/storage/emulated/0" : "/storage/" + volume;
        return normalizeStoragePath(base + relative + name);
    }

    /**
     * Last native fallback: reads the kernel link of an opened descriptor,
     * which recovers the real file behind FUSE providers.
     */
    private String readLinkPath(Uri uri) {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) {
                return null;
            }
            String link = Os.readlink("/proc/self/fd/" + pfd.getFd());
            return normalizeStoragePath(link);
        } catch (Exception e) {
            return null;
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Accepts only stable public-storage paths. /sdcard and /storage/self are
     * normalised to /storage/emulated/0; picker transform caches are rejected
     * so the caller copies the file into the uploads folder instead.
     */
    private String normalizeStoragePath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        if (path.startsWith("/sdcard/")) {
            path = "/storage/emulated/0/" + path.substring("/sdcard/".length());
        } else if (path.startsWith("/storage/self/primary/")) {
            path = "/storage/emulated/0/"
                    + path.substring("/storage/self/primary/".length());
        }
        if (path.contains("/.transforms/")) {
            return null;
        }
        if (!path.matches("^/storage/[A-Za-z0-9._-]+/.*")) {
            return null;
        }
        return new File(path).canRead() ? path : null;
    }

    /** Quotes a path for the shell only when it contains shell metacharacters. */
    private static String shellQuote(String path) {
        if (path.matches("[A-Za-z0-9_./:@%+=,-]+")) {
            return path;
        }
        return "'" + path.replace("'", "'\\''") + "'";
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String v = c.getString(idx);
                    if (v != null && !v.isEmpty()) {
                        return v;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------- start screen

    /** Cold start: ask which CLI to launch unless a live session exists. */
    private void maybeShowStartScreen() {
        if (panel != null && panel.getVisibility() == View.VISIBLE) {
            // Keep whatever is behind the files panel (the chooser or the
            // terminal) instead of stacking a second chooser on top.
            return;
        }
        if (Hub.session != null && Hub.cliActive && !Hub.cliExited) {
            if (chooser != null) {
                chooser.setVisibility(View.GONE);
            }
            refresh();
            return;
        }
        showChooser();
    }

    private void showChooser() {
        Hub.cliExited = false;
        if (chooser != null) {
            chooser.setVisibility(View.VISIBLE);
            return;
        }
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xF50B0F14);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> {
            // Swallow taps so the terminal never receives them while choosing.
        });

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(22), dp(22), dp(22), dp(18));

        CliLogo mark = new CliLogo(this, CliLogo.ATTC);
        LinearLayout.LayoutParams markParams =
                new LinearLayout.LayoutParams(dp(56), dp(56));
        markParams.bottomMargin = dp(12);
        box.addView(mark, markParams);

        TextView title = new TextView(this);
        title.setText("Attc code");
        title.setTextColor(0xFFE6EDF3);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Choose a CLI to start");
        sub.setTextColor(0xFF8B98A6);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(4), 0, dp(18));
        box.addView(sub);

        for (int kind = 0; kind < 6; kind++) {
            box.addView(cliCard(kind));
        }

        TextView hint = new TextView(this);
        hint.setText("The session keeps running until you tap Fully close.");
        hint.setTextColor(0xFF5F6C7A);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(14), 0, 0);
        box.addView(hint);

        overlay.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        stage.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        chooser = overlay;
    }

    private View cliCard(int kind) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF141C26);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0xFF1F2A37);
        card.setBackground(bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        card.setLayoutParams(params);

        card.addView(new CliLogo(this, kind), new LinearLayout.LayoutParams(
                dp(44), dp(44)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(14);
        card.addView(texts, textParams);

        TextView name = new TextView(this);
        name.setText(CliLogo.name(kind));
        name.setTextColor(0xFFE6EDF3);
        name.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        texts.addView(name);

        TextView detail = new TextView(this);
        detail.setText(CliLogo.detail(kind));
        detail.setTextColor(CliLogo.accent(kind));
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        detail.setPadding(0, dp(2), 0, 0);
        texts.addView(detail);

        TextView chevron = new TextView(this);
        chevron.setText("\u203A");
        chevron.setTextColor(0xFF5F6C7A);
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        card.addView(chevron);

        card.setOnClickListener(v -> {
            if (chooser != null) {
                chooser.setVisibility(View.GONE);
            }
            startCli(CliLogo.key(kind));
        });
        return card;
    }

    private void startCli(String cli) {
        startCli(cli, true);
    }

    private void startCli(String cli, boolean showKeyboard) {
        boolean consented = prefs.getBoolean(KEY_DOWNLOAD_CONSENT, false);
        if (!consented && new Setup(this).isRootfsReady()) {
            prefs.edit().putBoolean(KEY_DOWNLOAD_CONSENT, true).apply();
            consented = true;
        }
        if (!consented) {
            askDownloadConsent(cli, showKeyboard);
            return;
        }
        if (chooser != null) {
            chooser.setVisibility(View.GONE);
        }
        Hub.cliExited = false;
        Hub.cliActive = true;
        statusText.setText("Starting " + SessionStore.label(cli) + "...");
        Hub.setStatus("Starting " + SessionStore.label(cli) + "...");
        Intent i = new Intent(this, TerminalService.class)
                .setAction(TerminalService.ACTION_START)
                .putExtra(TerminalService.EXTRA_CLI, cli);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        if (showKeyboard) {
            terminal.focusAndShowKeyboard();
        }
    }

    /**
     * F-Droid policy requires explicit consent before downloading executable
     * files that are not part of the APK. The root filesystem and the AI CLIs
     * are fetched from GitHub on first use, so ask once and explain it.
     */
    private void askDownloadConsent(final String cli, final boolean showKeyboard) {
        new AlertDialog.Builder(this)
                .setTitle("İndirme onayı")
                .setMessage("Attc Code, Ubuntu 24.04 arm64 kök dosya sistemini ve "
                        + "seçtiğiniz CLI'yı GitHub üzerinden indirir (yaklaşık "
                        + "30–120 MB).\n\nBu dosyalar uygulamayla birlikte gelmez ve "
                        + "F-Droid tarafından doğrulanmaz. Devam ederseniz indirme bu "
                        + "cihazda başlar.")
                .setPositiveButton("Onayla ve başlat", (d, w) -> {
                    prefs.edit().putBoolean(KEY_DOWNLOAD_CONSENT, true).apply();
                    startCli(cli, showKeyboard);
                })
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    // -------------------------------------------------- sessions and files

    private void showPanel() {
        if (panel != null) {
            panel.setVisibility(View.VISIBLE);
            renderFiles();
            return;
        }
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xFA0B0F14);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> {
        });

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(16), dp(14), dp(16), dp(12));
        overlay.addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        sheet.addView(header);

        TextView title = new TextView(this);
        title.setText("Files");
        title.setTextColor(0xFFE6EDF3);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(this);
        close.setText("\u00D7");
        close.setTextColor(0xFF9FB0BF);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        close.setPadding(dp(12), 0, dp(4), 0);
        close.setOnClickListener(v -> panel.setVisibility(View.GONE));
        header.addView(close);

        panelBody = new LinearLayout(this);
        panelBody.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panelBody);
        sheet.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        stage.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        panel = overlay;
        renderFiles();
    }

    private void renderFiles() {
        if (panelBody != null) {
            panelBody.removeAllViews();
        }
        File home = new File(new Setup(this).rootDir(), "root");
        java.util.List<File> files = SessionStore.files(home);
        TextView root = new TextView(this);
        root.setText("~");
        root.setTextColor(0xFF7D8B99);
        root.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        root.setPadding(0, 0, 0, dp(8));
        panelBody.addView(root);
        if (files.isEmpty()) {
            panelBody.addView(emptyRow("No files yet. Ask the CLI to create one."));
            return;
        }
        for (File file : files) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF141C26);
            bg.setCornerRadius(dp(12));
            row.setBackground(bg);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(6);
            panelBody.addView(row, params);

            LinearLayout nameRow = new LinearLayout(this);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(nameRow);

            TextView name = new TextView(this);
            name.setText(file.getName());
            name.setTextColor(0xFFE6EDF3);
            name.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            nameRow.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            String cli = SessionStore.cliFor(file.lastModified());
            TextView badge = new TextView(this);
            badge.setText(cli == null ? "unknown" : SessionStore.label(cli));
            badge.setTextColor(cli == null ? 0xFF7D8B99 : cliColor(cli));
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            badge.setSingleLine(true);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(0xFF0F1720);
            badgeBg.setCornerRadius(dp(8));
            badge.setBackground(badgeBg);
            badge.setPadding(dp(7), dp(2), dp(7), dp(2));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeParams.leftMargin = dp(8);
            nameRow.addView(badge, badgeParams);

            TextView meta = new TextView(this);
            meta.setText(relative(file, home) + "   \u00B7   " + size(file.length()));
            meta.setTextColor(0xFF7D8B99);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            meta.setPadding(0, dp(2), 0, 0);
            row.addView(meta);

            row.setOnClickListener(v -> previewFile(file, home));
        }
    }

    /** Accent for the CLI badge: green, Claude orange, Antigravity blue. */
    private static int cliColor(String cli) {
        if ("claude".equals(cli)) {
            return 0xFFE8A87C;
        }
        if ("agy".equals(cli)) {
            return 0xFF8AB4F8;
        }
        if ("terminal".equals(cli)) {
            return 0xFF9FB0BF;
        }
        if ("codex".equals(cli)) {
            return 0xFFD6D6D6;
        }
        if ("kimi".equals(cli)) {
            return 0xFF8FA6FF;
        }
        return 0xFF00E5A0;
    }

    private String relative(File file, File home) {
        String path = file.getAbsolutePath();
        String root = home.getAbsolutePath();
        if (path.startsWith(root)) {
            String rest = path.substring(root.length());
            return rest.startsWith("/") ? "~" + rest : "~/" + rest;
        }
        return path;
    }

    private static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(java.util.Locale.ENGLISH, "%.1f MB", bytes / 1048576.0);
    }

    private TextView emptyRow(String text) {
        TextView empty = new TextView(this);
        empty.setText(text);
        empty.setTextColor(0xFF6C7A88);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        empty.setPadding(dp(4), dp(18), dp(4), dp(18));
        return empty;
    }

    /** Shows the file at its place in the guest home, with a path header. */
    private void previewFile(File file, File home) {
        String content;
        try {
            content = readHead(file, 6000);
        } catch (Exception e) {
            content = "(could not read this file)";
        }
        ScrollView scroll = new ScrollView(this);
        TextView body = new TextView(this);
        body.setText(content);
        body.setTextColor(0xFFE6EDF3);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.setTypeface(Typeface.MONOSPACE);
        body.setPadding(dp(14), dp(10), dp(14), dp(10));
        scroll.addView(body);

        new AlertDialog.Builder(this)
                .setTitle(relative(file, home))
                .setMessage(file.getAbsolutePath())
                .setView(scroll)
                .setPositiveButton("Copy path", (d, w) -> {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("path",
                                file.getAbsolutePath()));
                    }
                    toast("Path copied");
                })
                .setNeutralButton("Open with app", (d, w) -> openExternally(file))
                .setNegativeButton("Close", null)
                .show();
    }

    /** Hands the file to another app through the read-only bridge provider. */
    private void openExternally(File file) {
        try {
            Uri uri = FileBridgeProvider.uriFor(file);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, FileBridgeProvider.mimeOf(file))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("file", uri));
            startActivity(Intent.createChooser(intent, "Open with app"));
        } catch (Exception e) {
            Logger.i("open: " + e);
            toast("No app can open this file");
        }
    }

    private static String readHead(File file, int limit) throws IOException {
        InputStream in = new java.io.FileInputStream(file);
        try {
            byte[] buffer = new byte[limit];
            int read = 0;
            while (read < limit) {
                int n = in.read(buffer, read, limit - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            return new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private TextView item(String label, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFF9FD8FF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(0, dp(14), 0, dp(14));
        t.setOnClickListener(click);
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
