package com.attc.opencode;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import org.json.JSONObject;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Floating mascot for Attc Code.
 *
 * Collapsed state is an animated sleepy logo: closed eyes, a small mouth and
 * a falling drool drop. Tapping the logo opens a live panel with the active
 * session, the last output lines and a prompt input. The logo never hides on
 * tap; only the explicit Gizle action removes it. Long press opens a mini
 * menu next to the logo and the logo can be dragged anywhere. The user picks
 * the window transparency in Settings (overlay_alpha).
 */
public final class OverlayService extends Service {

    public static final String ACTION_SHOW = "com.attc.opencode.OVERLAY_SHOW";
    public static final String ACTION_HIDE = "com.attc.opencode.OVERLAY_HIDE";
    public static final String ACTION_STOP = "com.attc.opencode.OVERLAY_STOP";
    public static final String ACTION_SEND = "com.attc.opencode.OVERLAY_SEND";
    public static final String ACTION_REFRESH = "com.attc.opencode.OVERLAY_REFRESH";
    public static final String EXTRA_TEXT = "text";
    public static final String PREF_ENABLED = "overlay_enabled";
    public static final String PREF_ALPHA = "overlay_alpha";
    public static final String PREF_X = "overlay_x";
    public static final String PREF_Y = "overlay_y";

    public static volatile boolean running;
    public static volatile boolean visible;
    public static volatile boolean panelOpen;

    private static final String CHANNEL = "attc_activity";
    private static final int NOTIFICATION_ID = 21;

    private WindowManager wm;
    private SharedPreferences prefs;
    private View bubble;
    private LogoView logoView;
    private View panel;
    private View menu;
    private WindowManager.LayoutParams bubbleParams;
    private TextView statusView;
    private TextView outputView;
    private TextView alphaButton;
    private EditText promptView;
    private int alpha = 92;
    private int bubbleSize;
    private PointerView pointerView;
    private Thread pointerThread;
    private volatile boolean pointerRunning;
    private float ptrX = -1f;
    private float ptrY = -1f;
    private float ptrFromX;
    private float ptrFromY;
    private float ptrToX;
    private float ptrToY;
    private float ptrX2;
    private float ptrY2;
    private long ptrMoveStart;
    private long ptrMoveDuration;
    private long ptrRippleAt;
    private long ptrHideAt;
    private String ptrAction = "";
    private String ptrLabel = "";
    private boolean ptrSwipe;

    private final Runnable pointerTick = new Runnable() {
        @Override
        public void run() {
            if (pointerView == null) {
                return;
            }
            long now = System.currentTimeMillis();
            float t = ptrMoveDuration <= 0L ? 1f
                    : (now - ptrMoveStart) / (float) ptrMoveDuration;
            if (t < 0f) {
                t = 0f;
            }
            if (t > 1f) {
                t = 1f;
            }
            float ease = 1f - (1f - t) * (1f - t) * (1f - t);
            ptrX = ptrFromX + (ptrToX - ptrFromX) * ease;
            ptrY = ptrFromY + (ptrToY - ptrFromY) * ease;
            pointerView.invalidate();
            if (now < ptrHideAt + 900L) {
                handler.postDelayed(this, 30L);
            }
        }
    };

    private boolean moved;
    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private long downAt;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refreshPanel();
            handler.postDelayed(this, 600L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        alpha = clampAlpha(prefs.getInt(PREF_ALPHA, 92));
        bubbleSize = dp(64);
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        addBubble();
        startPointerWatcher();
        handler.post(refresh);
        Logger.i("overlay: service created alpha=" + alpha);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (action == null) {
            action = ACTION_SHOW;
        }
        switch (action) {
            case ACTION_STOP:
                stopSelf();
                return START_NOT_STICKY;
            case ACTION_HIDE:
                hideBubble();
                break;
            case ACTION_SEND:
                send(intent == null ? null : intent.getStringExtra(EXTRA_TEXT));
                break;
            case ACTION_REFRESH:
                alpha = clampAlpha(prefs.getInt(PREF_ALPHA, 92));
                if (logoView != null) {
                    logoView.invalidate();
                }
                if (panel != null) {
                    collapse();
                    expand();
                }
                updateNotification();
                break;
            case ACTION_SHOW:
                addBubble();
                break;
            default:
                addBubble();
                break;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        visible = false;
        panelOpen = false;
        handler.removeCallbacksAndMessages(null);
        hideMenu();
        collapse();
        removeBubbleView();
        stopPointerWatcher();
        removePointerView();
        stopForeground(true);
        Logger.i("overlay: service destroyed");
        super.onDestroy();
    }

    public static void maybeStart(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_ENABLED, true)) {
            return;
        }
        if (!Settings.canDrawOverlays(context)) {
            return;
        }
        if (running) {
            return;
        }
        try {
            Intent i = new Intent(context, OverlayService.class).setAction(ACTION_SHOW);
            context.startForegroundService(i);
        } catch (Throwable t) {
            Logger.i("overlay: start failed " + t);
        }
    }

    private void addBubble() {
        if (bubble != null) {
            bubble.setVisibility(View.VISIBLE);
            visible = true;
            updateNotification();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Logger.i("overlay: permission missing");
            stopSelf();
            return;
        }
        logoView = new LogoView(this);
        bubble = logoView;
        bubbleParams = createBubbleParams();
        bubble.setOnTouchListener(this::onBubbleTouch);
        try {
            wm.addView(bubble, bubbleParams);
            visible = true;
            Logger.i("overlay: mascot shown");
        } catch (Throwable t) {
            Logger.i("overlay: add failed " + t);
            bubble = null;
            logoView = null;
            visible = false;
        }
        updateNotification();
    }

    private void removeBubbleView() {
        if (bubble != null) {
            try {
                wm.removeView(bubble);
            } catch (Throwable ignored) {
            }
        }
        bubble = null;
        logoView = null;
        bubbleParams = null;
        visible = false;
    }

    private void hideBubble() {
        hideMenu();
        collapse();
        removeBubbleView();
        updateNotification();
        Logger.i("overlay: mascot hidden, session continues");
    }

    private WindowManager.LayoutParams createBubbleParams() {
        Point size = screen();
        int defaultX = Math.max(dp(8), size.x - bubbleSize - dp(14));
        int defaultY = Math.max(dp(80), size.y / 5);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                bubbleSize, bubbleSize, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = clamp(prefs.getInt(PREF_X, defaultX), 0, Math.max(0, size.x - bubbleSize));
        p.y = clamp(prefs.getInt(PREF_Y, defaultY), 0, Math.max(0, size.y - bubbleSize));
        return p;
    }

    private boolean onBubbleTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                moved = false;
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downX = bubbleParams.x;
                downY = bubbleParams.y;
                downAt = System.currentTimeMillis();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!moved && Math.hypot(dx, dy) > dp(8)) {
                    moved = true;
                    hideMenu();
                    collapse();
                }
                if (moved) {
                    Point size = screen();
                    bubbleParams.x = clamp(downX + Math.round(dx), 0,
                            Math.max(0, size.x - bubbleSize));
                    bubbleParams.y = clamp(downY + Math.round(dy), 0,
                            Math.max(0, size.y - bubbleSize));
                    try {
                        wm.updateViewLayout(bubble, bubbleParams);
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (moved) {
                    prefs.edit().putInt(PREF_X, bubbleParams.x)
                            .putInt(PREF_Y, bubbleParams.y).apply();
                    Logger.i("overlay: moved to " + bubbleParams.x + "," + bubbleParams.y);
                } else if (System.currentTimeMillis() - downAt >= 600L) {
                    showMenu();
                } else if (panel != null) {
                    refreshPanel();
                } else {
                    expand();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return false;
        }
    }

    private void showMenu() {
        if (menu != null || bubble == null || bubbleParams == null) {
            return;
        }
        collapse();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));
        box.setBackground(rounded(0xF20B0F14, dp(12), 0xFF00E5A0));
        box.addView(menuItem("Canlı panel", v -> {
            hideMenu();
            expand();
        }));
        box.addView(menuItem("Gizle", v -> {
            hideMenu();
            hideBubble();
        }));
        box.addView(menuItem("Kapat", v -> {
            hideMenu();
            stopSelf();
        }));
        int width = dp(156);
        Point size = screen();
        int x = bubbleParams.x + bubbleSize + dp(8);
        if (x + width > size.x - dp(4)) {
            x = Math.max(dp(4), bubbleParams.x - width - dp(8));
        }
        int y = clamp(bubbleParams.y, dp(4), Math.max(dp(4), size.y - dp(170)));
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = x;
        p.y = y;
        menu = box;
        try {
            wm.addView(menu, p);
            Logger.i("overlay: menu shown");
        } catch (Throwable t) {
            Logger.i("overlay: menu failed " + t);
            menu = null;
        }
    }

    private TextView menuItem(String text, View.OnClickListener click) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xFFE6EDF3);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setOnClickListener(click);
        return view;
    }

    private void hideMenu() {
        if (menu != null) {
            try {
                wm.removeView(menu);
            } catch (Throwable ignored) {
            }
        }
        menu = null;
    }

    private void expand() {
        if (panel != null || bubbleParams == null) {
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(rounded(0xF20B0F14, dp(16), 0xFF00E5A0));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Attc Code • canlı");
        title.setTextColor(0xFFE6EDF3);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView minimize = new TextView(this);
        minimize.setText("—");
        minimize.setTextColor(0xFF9FB0BF);
        minimize.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        minimize.setPadding(dp(12), 0, dp(4), dp(4));
        minimize.setOnClickListener(v -> collapse());
        header.addView(minimize);
        box.addView(header);

        statusView = new TextView(this);
        statusView.setTextColor(0xFF00E5A0);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusView.setSingleLine(true);
        box.addView(statusView);

        outputView = new TextView(this);
        outputView.setTextColor(0xFF9FB0BF);
        outputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        outputView.setMaxLines(3);
        outputView.setEllipsize(TextUtils.TruncateAt.END);
        outputView.setPadding(0, dp(4), 0, dp(8));
        box.addView(outputView);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        promptView = new EditText(this);
        promptView.setHint("Terminale yaz...");
        promptView.setHintTextColor(0xFF7D8B99);
        promptView.setTextColor(0xFFE6EDF3);
        promptView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        promptView.setSingleLine(true);
        promptView.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        promptView.setImeOptions(EditorInfo.IME_ACTION_SEND);
        promptView.setOnEditorActionListener((v, actionId, event) -> {
            send(promptView.getText().toString());
            return true;
        });
        row.addView(promptView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView send = new TextView(this);
        send.setText("Gönder");
        send.setTextColor(0xFF00E5A0);
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        send.setTypeface(Typeface.DEFAULT_BOLD);
        send.setPadding(dp(12), dp(8), dp(2), dp(8));
        send.setOnClickListener(v -> send(promptView.getText().toString()));
        row.addView(send);
        box.addView(row);

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.HORIZONTAL);
        options.setGravity(Gravity.CENTER_VERTICAL);
        options.setPadding(0, dp(8), 0, 0);
        options.addView(optionButton("Gizle", v -> hideBubble()));
        options.addView(optionButton("Kapat", v -> stopSelf()));
        alphaButton = optionButton("Saydamlık: %" + alpha, v -> cycleAlpha());
        options.addView(alphaButton);
        box.addView(options);

        int width = dp(310);
        Point size = screen();
        int x = bubbleParams.x + bubbleSize + dp(8);
        if (x + width > size.x - dp(8)) {
            x = Math.max(dp(4), bubbleParams.x - width - dp(8));
        }
        int y = clamp(bubbleParams.y, dp(36), Math.max(dp(36), size.y / 2));
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = x;
        p.y = y;
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        panel = box;
        try {
            wm.addView(panel, p);
            panelOpen = true;
            refreshPanel();
            Logger.i("overlay: panel opened");
        } catch (Throwable t) {
            Logger.i("overlay: panel failed " + t);
            panel = null;
            panelOpen = false;
        }
        updateNotification();
    }

    private TextView optionButton(String text, View.OnClickListener click) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xFF9FB0BF);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setPadding(0, dp(4), dp(16), dp(4));
        view.setOnClickListener(click);
        return view;
    }

    private void collapse() {
        if (panel != null) {
            try {
                wm.removeView(panel);
            } catch (Throwable ignored) {
            }
        }
        panel = null;
        panelOpen = false;
        statusView = null;
        outputView = null;
        promptView = null;
        alphaButton = null;
        updateNotification();
        Logger.i("overlay: panel collapsed");
    }

    private void refreshPanel() {
        if (statusView == null) {
            return;
        }
        Session session = Hub.session;
        if (session == null) {
            statusView.setText("Oturum yok");
        } else {
            String state = session.label();
            if (session.cliRunning()) {
                state = state + " • çalışıyor • " + elapsed(session.cliStartedAt());
            } else {
                state = state + " • hazır";
            }
            statusView.setText(state);
        }
        if (outputView != null) {
            outputView.setText(lastLine(Hub.tail()));
        }
    }

    private void send(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return;
        }
        Session session = Hub.session;
        if (session == null || !session.isRunning()) {
            Toast.makeText(this, "Aktif oturum yok", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("terminal".equals(session.cli())) {
            session.write(text.replace("\r", " ").replace("\n", " ") + "\r");
        } else {
            session.write(text + "\r");
        }
        Logger.i("overlay: prompt sent to " + session.label());
        if (promptView != null) {
            promptView.setText("");
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(promptView.getWindowToken(), 0);
            }
        }
    }

    private void cycleAlpha() {
        alpha = alpha <= 40 ? 100 : alpha - 20;
        prefs.edit().putInt(PREF_ALPHA, alpha).apply();
        if (logoView != null) {
            logoView.invalidate();
        }
        collapse();
        expand();
        Logger.i("overlay: alpha=" + alpha);
    }

    private String elapsed(long startedAt) {
        if (startedAt <= 0) {
            return "0s";
        }
        long seconds = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000L);
        if (seconds < 60) {
            return seconds + "s";
        }
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    private String lastLine(String text) {
        if (text == null || text.isEmpty()) {
            return "Henüz çıktı yok";
        }
        String[] lines = text.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = cleanLine(lines[i]);
            if (!line.isEmpty()) {
                return shorten(line);
            }
        }
        return "Henüz çıktı yok";
    }

    private String cleanLine(String line) {
        StringBuilder text = new StringBuilder(line.length());
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == 27) {
                i++;
                if (i < line.length() && line.charAt(i) == 91) {
                    i++;
                    while (i < line.length()) {
                        char end = line.charAt(i);
                        i++;
                        if (end >= 64 && end <= 126) {
                            break;
                        }
                    }
                } else if (i < line.length() && line.charAt(i) == 93) {
                    i++;
                    while (i < line.length()) {
                        char end = line.charAt(i);
                        i++;
                        if (end == 7 || end == 27) {
                            break;
                        }
                    }
                }
                continue;
            }
            if (c >= 32) {
                text.append(c);
            }
            i++;
        }
        return text.toString().trim();
    }

    private String shorten(String line) {
        if (line.length() > 150) {
            return line.substring(line.length() - 150);
        }
        return line;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Attc Code canlı aktivite", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Uçan mascot ve oturum durumu");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, pendingFlags());
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        builder.setContentTitle("Attc Code canlı aktivite")
                .setContentText(visible
                        ? "Mascot açık; dokununca panel açılır."
                        : "Mascot gizli; oturum devam ediyor.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setContentIntent(content);
        builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_view,
                visible ? "Gizle" : "Göster",
                PendingIntent.getService(this, 1,
                        new Intent(this, OverlayService.class).setAction(
                                visible ? ACTION_HIDE : ACTION_SHOW),
                        pendingFlags())).build());
        builder.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Kapat",
                PendingIntent.getService(this, 2,
                        new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                        pendingFlags())).build());
        return builder.build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification());
        }
    }

    private static int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private static int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private Point screen() {
        Point point = new Point();
        if (wm != null) {
            wm.getDefaultDisplay().getSize(point);
        }
        return point;
    }

    private GradientDrawable rounded(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(tint(color));
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), tint(stroke));
        return drawable;
    }

    private int tint(int color) {
        return (color & 0x00FFFFFF) | ((clampAlpha(alpha) * 255 / 100) << 24);
    }

    private static int clampAlpha(int value) {
        return value < 20 ? 20 : (value > 100 ? 100 : value);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics()));
    }

    private void startPointerWatcher() {
        if (pointerRunning) {
            return;
        }
        pointerRunning = true;
        pointerThread = new Thread(() -> {
            long seen = 0L;
            while (pointerRunning) {
                try {
                    File file = new File(Setup.STORAGE_DIR, "pointer");
                    if (!file.exists()) {
                        Thread.sleep(140L);
                        continue;
                    }
                    long length = file.length();
                    if (length < seen) {
                        seen = 0L;
                    }
                    if (length == seen) {
                        Thread.sleep(90L);
                        continue;
                    }
                    byte[] data = new byte[(int) (length - seen)];
                    RandomAccessFile raf = new RandomAccessFile(file, "r");
                    try {
                        raf.seek(seen);
                        raf.readFully(data);
                    } finally {
                        raf.close();
                    }
                    String text = new String(data, StandardCharsets.UTF_8);
                    int end = text.lastIndexOf(10);
                    if (end < 0) {
                        Thread.sleep(90L);
                        continue;
                    }
                    seen += text.substring(0, end + 1).getBytes(StandardCharsets.UTF_8).length;
                    String[] lines = text.substring(0, end)
                            .split(String.valueOf((char) 10));
                    for (String line : lines) {
                        handlePointerEvent(line);
                    }
                } catch (Throwable t) {
                    try {
                        Thread.sleep(400L);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }, "attc-pointer");
        pointerThread.setDaemon(true);
        pointerThread.start();
        Logger.i("overlay: pointer watcher started");
    }

    private void stopPointerWatcher() {
        pointerRunning = false;
        pointerThread = null;
    }

    private void handlePointerEvent(String line) {
        try {
            JSONObject event = new JSONObject(line);
            final String action = event.optString("action", "cursor");
            final float x = (float) event.optDouble("x", -1);
            final float y = (float) event.optDouble("y", -1);
            final float x2 = (float) event.optDouble("x2", -1);
            final float y2 = (float) event.optDouble("y2", -1);
            final String label = event.optString("label", action);
            final long duration = event.optLong("duration", 300);
            handler.post(() -> animatePointer(action, x, y, x2, y2, label, duration));
        } catch (Throwable t) {
            Logger.i("overlay: pointer parse failed " + t);
        }
    }

    private void animatePointer(String action, float x, float y, float x2, float y2,
                                String label, long duration) {
        ensurePointerView();
        if (pointerView == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (ptrX < 0f || ptrY < 0f) {
            Point size = screen();
            ptrX = size.x / 2f;
            ptrY = size.y / 2f;
        }
        ptrAction = action == null ? "cursor" : action;
        ptrLabel = label == null || label.isEmpty() ? ptrAction : label;
        ptrSwipe = "swipe".equals(ptrAction);
        if (ptrSwipe) {
            ptrFromX = x >= 0f ? x : ptrX;
            ptrFromY = y >= 0f ? y : ptrY;
            ptrToX = x2 >= 0f ? x2 : ptrFromX;
            ptrToY = y2 >= 0f ? y2 : ptrFromY;
            ptrX2 = ptrToX;
            ptrY2 = ptrToY;
        } else {
            ptrFromX = ptrX;
            ptrFromY = ptrY;
            ptrToX = x >= 0f ? x : ptrX;
            ptrToY = y >= 0f ? y : ptrY;
        }
        ptrMoveStart = now;
        ptrMoveDuration = ptrSwipe ? Math.max(160L, duration) : 240L;
        ptrRippleAt = "tap".equals(ptrAction) || "open".equals(ptrAction)
                ? now + ptrMoveDuration : 0L;
        ptrHideAt = now + ptrMoveDuration + 1200L;
        pointerView.invalidate();
        handler.removeCallbacks(pointerTick);
        handler.post(pointerTick);
        Logger.i("overlay: pointer " + ptrAction + " " + Math.round(x) + "," + Math.round(y));
    }

    private void ensurePointerView() {
        if (pointerView != null) {
            return;
        }
        pointerView = new PointerView(this);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        p.x = 0;
        p.y = 0;
        try {
            wm.addView(pointerView, p);
            Logger.i("overlay: pointer layer added");
        } catch (Throwable t) {
            Logger.i("overlay: pointer layer failed " + t);
            pointerView = null;
        }
    }

    private void removePointerView() {
        if (pointerView != null && wm != null) {
            try {
                wm.removeView(pointerView);
            } catch (Throwable ignored) {
            }
        }
        pointerView = null;
    }

    private final class PointerView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PointerView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            long now = System.currentTimeMillis();
            if (ptrHideAt <= 0L || now > ptrHideAt + 900L) {
                return;
            }
            float fade = 1f;
            if (now > ptrHideAt) {
                fade = Math.max(0f, 1f - (now - ptrHideAt) / 900f);
            }
            if (ptrSwipe) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(3));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(((int) (130 * fade) << 24) | 0x0000E5A0);
                canvas.drawLine(ptrFromX, ptrFromY, ptrX, ptrY, paint);
            }
            if (ptrRippleAt > 0L && now >= ptrRippleAt && now <= ptrRippleAt + 650L) {
                float age = (now - ptrRippleAt) / 650f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(((int) (180 * (1f - age) * fade) << 24) | 0x0000E5A0);
                canvas.drawCircle(ptrToX, ptrToY, dp(16) + dp(46) * age, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(((int) (60 * fade) << 24) | 0x0000E5A0);
            canvas.drawCircle(ptrX, ptrY, dp(24), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(((int) (235 * fade) << 24) | 0x0000E5A0);
            canvas.drawCircle(ptrX, ptrY, dp(15), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(((int) (255 * fade) << 24) | 0x00FFFFFF);
            canvas.drawCircle(ptrX, ptrY, dp(4), paint);
            drawLabel(canvas, fade);
        }

        private void drawLabel(Canvas canvas, float fade) {
            if (ptrLabel == null || ptrLabel.isEmpty()) {
                return;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dp(11));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            float padding = dp(8);
            float width = paint.measureText(ptrLabel) + padding * 2f;
            float height = dp(25);
            float x = ptrX + dp(24);
            float y = ptrY + dp(24);
            if (x + width > getWidth() - dp(4)) {
                x = ptrX - width - dp(24);
            }
            if (y + height > getHeight() - dp(4)) {
                y = ptrY - height - dp(24);
            }
            if (x < dp(4)) {
                x = dp(4);
            }
            if (y < dp(4)) {
                y = dp(4);
            }
            RectF box = new RectF(x, y, x + width, y + height);
            paint.setColor(((int) (220 * fade) << 24) | 0x000B0F14);
            canvas.drawRoundRect(box, dp(8), dp(8), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(((int) (230 * fade) << 24) | 0x0000E5A0);
            canvas.drawRoundRect(box, dp(8), dp(8), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(((int) (255 * fade) << 24) | 0x00E6EDF3);
            canvas.drawText(ptrLabel, x + padding, y + height - dp(7), paint);
        }
    }

    private final class LogoView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator animator;
        private float phase;

        LogoView(Context context) {
            super(context);
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(2800L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.RESTART);
            animator.addUpdateListener(animation -> {
                phase = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            animator.cancel();
            super.onDetachedFromWindow();
        }

        private float dpf(float value) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    getResources().getDisplayMetrics());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float breath = 1f + 0.045f * (float) Math.sin(phase * Math.PI * 2.0);
            float radius = Math.min(w, h) / 2f - dpf(4f);
            float r = radius * breath;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(tint(0x3300E5A0));
            canvas.drawCircle(cx, cy, r + dpf(4f), paint);
            paint.setColor(tint(0xFF0B0F14));
            canvas.drawCircle(cx, cy, r, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpf(1.8f));
            paint.setColor(tint(0xFF00E5A0));
            canvas.drawCircle(cx, cy, r, paint);

            float eyeY = cy - r * 0.06f;
            float eyeDx = r * 0.32f;
            float eyeW = r * 0.36f;
            float eyeH = r * 0.24f;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dpf(2.2f));
            paint.setColor(tint(0xFFE6EDF3));
            RectF left = new RectF(cx - eyeDx - eyeW / 2f, eyeY - eyeH / 2f,
                    cx - eyeDx + eyeW / 2f, eyeY + eyeH / 2f);
            RectF right = new RectF(cx + eyeDx - eyeW / 2f, eyeY - eyeH / 2f,
                    cx + eyeDx + eyeW / 2f, eyeY + eyeH / 2f);
            canvas.drawArc(left, 20f, 140f, false, paint);
            canvas.drawArc(right, 20f, 140f, false, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpf(1.6f));
            paint.setColor(tint(0xFF9FB0BF));
            canvas.drawCircle(cx, cy + r * 0.30f, r * 0.10f, paint);

            float fall = (phase * 2f) % 1f;
            float dropY = cy + r * 0.40f + r * 0.55f * fall;
            float dropR = r * 0.085f * (1f - fall * 0.35f);
            int dropAlpha = clampAlpha(alpha) * 255 / 100;
            dropAlpha = (int) (dropAlpha * (1f - fall * 0.55f));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((0xFFB3E5FC & 0x00FFFFFF) | (dropAlpha << 24));
            canvas.drawCircle(cx + r * 0.16f, dropY, dropR, paint);

            paint.setTextSize(r * 0.34f);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setColor(tint(0xAA00E5A0));
            canvas.drawText("z", cx + r * 0.48f, cy - r * 0.45f - r * 0.20f * phase, paint);
        }
    }
}
