package com.attc.opencode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground owner of the terminal session: bootstrap, PTY, wake locks and the
 * persistent notification with Keep Awake / Disable Keep Awake / Fully Close.
 */
public class TerminalService extends Service {

    public static final String ACTION_START = "com.attc.opencode.START";
    public static final String ACTION_SWITCH = "com.attc.opencode.SWITCH";
    public static final String ACTION_RETRY = "com.attc.opencode.RETRY";
    public static final String ACTION_KEEP_AWAKE = "com.attc.opencode.KEEP_AWAKE";
    public static final String ACTION_DISABLE_AWAKE = "com.attc.opencode.DISABLE_AWAKE";
    public static final String ACTION_RESTART = "com.attc.opencode.RESTART";
    public static final String ACTION_CLOSE = "com.attc.opencode.CLOSE";
    public static final String ACTION_UPDATE = "com.attc.opencode.UPDATE";
    public static final String ACTION_PRIVACY = "com.attc.opencode.PRIVACY";
    public static final String ACTION_QUOTA_RESET = "com.attc.opencode.QUOTA_RESET";
    public static final String ACTION_QUOTA_PROBE = "com.attc.opencode.QUOTA_PROBE";
    public static final String ACTION_SCREEN = "com.attc.opencode.SCREEN";
    public static final String ACTION_TUI_UPDATE = "com.attc.opencode.TUI_UPDATE";
    public static final String ACTION_TUI_PING = "com.attc.opencode.TUI_PING";
    public static final String ACTION_GUEST_AUTH = "com.attc.opencode.GUEST_AUTH";
    public static final String ACTION_RUN_PROBE = "com.attc.opencode.RUN_PROBE";
    public static final String ACTION_FS_TEST = "com.attc.opencode.FS_TEST";
    public static final String ACTION_GUEST_CMD = "com.attc.opencode.GUEST_CMD";
    public static final String ACTION_VOICE_ENABLE = "com.attc.opencode.VOICE_ENABLE";
    public static final String ACTION_VOICE_DISABLE = "com.attc.opencode.VOICE_DISABLE";
    public static final String ACTION_VOICE_RELOAD = "com.attc.opencode.VOICE_RELOAD";
    public static final String ACTION_VOICE_COMMAND = "com.attc.opencode.VOICE_COMMAND";
    public static final String ACTION_VOICE_WAKE_TEST = "com.attc.opencode.VOICE_WAKE_TEST";
    /** CLI picked in the start screen: opencode, claude, agy, codex or kimi. */
    public static final String EXTRA_CLI = "cli";
    /** Send /voice live shortly after Antigravity starts. */
    static final String PREF_AGY_VOICE = "agy_auto_voice";
    public static final String EXTRA_VOICE_TEXT = "voice_text";

    /** Test instrumentation for the voice pipeline. */
    public static final String ACTION_VOICE_CTL = "com.attc.opencode.VOICE_CTL";
    public static final String EXTRA_VOICE_CTL = "voice_ctl";

    private static final String CHANNEL = "attc_opencode";
    private static final int NOTIFICATION_ID = 7;

    /** Official installers for the CLIs the app can launch. */
    private static final String CLAUDE_INSTALL = "https://claude.ai/install.sh";
    private static final String KIMI_INSTALL = "https://code.kimi.com/kimi-code/install.sh";
    private static final String AGY_INSTALL = "https://antigravity.google/cli/install.sh";

    /** Minimal PATH an installer needs; the login shell may not have one. */
    private static final String GUEST_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/root/.local/bin";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private static volatile TerminalService current;

    private Setup setup;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private volatile boolean booting;
    private volatile boolean quotaTimerArmed;
    private volatile String pendingCli;
    private volatile String status = "Starting...";
    private volatile boolean cliRunning;
    private volatile String activeCli;
    private volatile long cliStartedAt;
    private SharedPreferences prefs;

    private VoiceEngine voiceEngine;
    private SpeechRecognizer speech;
    private boolean capturing;
    private volatile String pendingVoiceText;

    private final Runnable voiceCaptureTimeout = new Runnable() {
        @Override
        public void run() {
            if (!capturing) {
                return;
            }
            Logger.i("voice: command capture timed out");
            if (speech != null) {
                try {
                    speech.cancel();
                } catch (Throwable ignored) {
                }
            }
            finishCapture();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(this);
        Logger.i("service created");
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        current = this;
        ZenProxy.get(this).start();
        createChannel();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "attc:terminal");
            wakeLock.setReferenceCounted(false);
        }
        WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "attc:terminal");
            wifiLock.setReferenceCounted(false);
        }
        startUpdateWatcher();
        handler.postDelayed(cliExitTick, 1000);
        VoiceBridge.start(this);
        VoiceBridge.install(this);
        AudioBridge.setListener(new AudioBridge.Listener() {
            @Override
            public void onMicStart() {
                // A CLI is recording now; the wake-word engine must release
                // the microphone or Android hands it an empty stream.
                handler.post(TerminalService.this::stopVoice);
            }

            @Override
            public void onMicStop() {
                handler.post(() -> {
                    if (voiceEnabled()) {
                        startVoice();
                    }
                });
            }
        });
        AudioBridge.start(this);
        if (voiceEnabled() && VoiceEngine.hasPermission(this)
                && VoiceEngine.hasTemplates(this)) {
            startVoice();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (action == null) {
            action = ACTION_START;
        }
        Logger.i("onStartCommand " + action);
        switch (action) {
            case ACTION_CLOSE:
                hardClose();
                return START_NOT_STICKY;
            case ACTION_KEEP_AWAKE:
                setKeepAwake(true);
                return START_STICKY;
            case ACTION_DISABLE_AWAKE:
                setKeepAwake(false);
                return START_STICKY;
            case ACTION_SWITCH: {
                String cli = intent == null ? null : intent.getStringExtra(EXTRA_CLI);
                Session target = Hub.findByCli(cli);
                if (target != null) {
                    activateSession(target);
                }
                return START_STICKY;
            }
            case ACTION_RETRY:
                startForeground(NOTIFICATION_ID, buildNotification());
                retry();
                return START_STICKY;
            case ACTION_RESTART:
                startForeground(NOTIFICATION_ID, buildNotification());
                worker.execute(this::restart);
                return START_STICKY;
            case ACTION_UPDATE:
                startForeground(NOTIFICATION_ID, buildNotification());
                updateOpencode();
                return START_STICKY;
            case ACTION_PRIVACY:
                Privacy.apply(this, setup != null ? setup.rootDir() : new Setup(this).rootDir());
                schedulePrivacyRotation();
                emit("Device identity updated.");
                return START_STICKY;
            case ACTION_QUOTA_RESET:
                startForeground(NOTIFICATION_ID, buildNotification());
                worker.execute(this::quotaReset);
                return START_STICKY;
            case ACTION_QUOTA_PROBE:
                startForeground(NOTIFICATION_ID, buildNotification());
                worker.execute(this::quotaProbe);
                return START_STICKY;
            case ACTION_SCREEN:
                dumpScreen("manual");
                return START_STICKY;
            case ACTION_TUI_UPDATE:
                startForeground(NOTIFICATION_ID, buildNotification());
                runTuiUpdate();
                return START_STICKY;
            case ACTION_TUI_PING:
                startForeground(NOTIFICATION_ID, buildNotification());
                runTuiPing();
                return START_STICKY;
            case ACTION_GUEST_AUTH:
                startForeground(NOTIFICATION_ID, buildNotification());
                worker.execute(this::dumpGuestAuth);
                return START_STICKY;
            case ACTION_RUN_PROBE:
                startForeground(NOTIFICATION_ID, buildNotification());
                worker.execute(this::runProbe);
                return START_STICKY;
            case ACTION_FS_TEST:
                startForeground(NOTIFICATION_ID, buildNotification());
                worker.execute(this::runFsTest);
                return START_STICKY;
            case ACTION_GUEST_CMD: {
                startForeground(NOTIFICATION_ID, buildNotification());
                String command = intent.getStringExtra("cmd");
                if (command != null && !command.isEmpty()) {
                    worker.execute(() -> runGuestCmd(command));
                }
                return START_STICKY;
            }
            case ACTION_VOICE_ENABLE:
                startForeground(NOTIFICATION_ID, buildNotification());
                prefs.edit().putBoolean(VoiceEngine.KEY_ENABLED, true).apply();
                startVoice();
                if (!booting && setup == null) {
                    booting = true;
                    worker.execute(this::boot);
                }
                return START_STICKY;
            case ACTION_VOICE_DISABLE:
                startForeground(NOTIFICATION_ID, buildNotification());
                prefs.edit().putBoolean(VoiceEngine.KEY_ENABLED, false).apply();
                stopVoice();
                status = "Running";
                updateNotification();
                return START_STICKY;
            case ACTION_VOICE_RELOAD:
                startForeground(NOTIFICATION_ID, buildNotification());
                prefs.edit().putBoolean(VoiceEngine.KEY_ENABLED, true).apply();
                stopVoice();
                startVoice();
                if (!booting && setup == null) {
                    booting = true;
                    worker.execute(this::boot);
                }
                return START_STICKY;
            case ACTION_VOICE_COMMAND: {
                startForeground(NOTIFICATION_ID, buildNotification());
                String text = intent.getStringExtra(EXTRA_VOICE_TEXT);
                if (text != null && !text.trim().isEmpty()) {
                    Logger.i("voice: injected \"" + text + "\"");
                    handleVoiceText(text);
                }
                return START_STICKY;
            }
            case ACTION_VOICE_WAKE_TEST:
                startForeground(NOTIFICATION_ID, buildNotification());
                Logger.i("voice: wake test");
                onWakeDetected();
                return START_STICKY;
            case ACTION_VOICE_CTL: {
                startForeground(NOTIFICATION_ID, buildNotification());
                final String ctl = intent.getStringExtra(EXTRA_VOICE_CTL);
                if (ctl != null && !ctl.isEmpty()) {
                    worker.execute(() -> Logger.i("voice: ctl test -> "
                            + ShizukuHelper.run(ctl)));
                }
                return START_STICKY;
            }
            case ACTION_START:
            default:
                startForeground(NOTIFICATION_ID, buildNotification());
                String cli = intent == null ? null : intent.getStringExtra(EXTRA_CLI);
                if (cli != null && !cli.isEmpty()) {
                    pendingCli = cli;
                    if (setup != null) {
                        // Each pick gets its own PTY; switching back to a
                        // running CLI only changes which session is visible.
                        openCli(cli);
                    }
                }
                if (!booting && setup == null) {
                    booting = true;
                    worker.execute(this::boot);
                }
                return START_STICKY;
        }
    }

    // ---------------------------------------------------------------- boot

    private void boot() {
        Hub.failed = false;
        for (Session old : Hub.sessions) {
            old.setExitListener(null);
            old.close();
        }
        Hub.sessions.clear();
        Hub.setActive(null);
        Hub.buffer = new TerminalBuffer(24, 80);
        Hub.clearTail();
        Hub.setStatus("Starting...");
        emit("Attc Code");
        emit("Ubuntu + opencode inside the app.");
        emit("");
        runSetup();
    }

    private void retry() {
        if (booting && Hub.session != null) {
            return;
        }
        Hub.failed = false;
        if (Hub.buffer == null) {
            Hub.buffer = new TerminalBuffer(24, 80);
        }
        emit("");
        emit("Retrying...");
        booting = true;
        worker.execute(this::runSetup);
    }

    private void restart() {
        for (Session old : Hub.sessions) {
            old.setExitListener(null);
            old.close();
        }
        Hub.sessions.clear();
        Hub.session = null;
        Hub.failed = false;
        booting = true;
        Hub.buffer = new TerminalBuffer(24, 80);
        Hub.setStatus("Restarting...");
        emit("Restarting terminal...");
        runSetup();
    }

    private void runSetup() {
        Logger.i("setup start");
        setup = new Setup(this);
        setup.run(new Setup.Progress() {
            @Override
            public void line(String text) {
                emit(text);
            }

            @Override
            public void failed(String message) {
                Logger.e("setup failed: " + message, null);
                Hub.failed = true;
                status = "Setup failed";
                emit("");
                emit("ERROR: " + message);
                emit("Tap Retry to try again.");
                Hub.setStatus("Setup failed: " + message);
                updateNotification();
            }

            @Override
            public void ready() {
                Hub.failed = false;
                booting = false;
                VoiceBridge.install(TerminalService.this);
                if (voiceEnabled() && VoiceEngine.hasPermission(TerminalService.this)
                        && VoiceEngine.hasTemplates(TerminalService.this)) {
                    startVoice();
                }
                updateNotification();
                if (pendingCli == null) {
                    // No CLI selected yet: the activity shows the chooser and
                    // sends the pick in a follow-up start command.
                    Hub.setStatus("Choose a CLI");
                    emit("");
                    emit("Choose a CLI to start: Attc Code, Claude Code, Antigravity, Codex, Kimi or Terminal.");
                } else {
                    startSession();
                }
            }
        });
    }

    /** The guest user's home: the CLI must see the whole device from here. */
    private File guestHome() {
        File root = setup != null ? setup.rootDir() : new Setup(this).rootDir();
        return new File(root, "root");
    }

    /**
     * Antigravity wraps model-generated shell commands in its own Exebox
     * sandbox when enableTerminalSandbox is true. Inside this Ubuntu guest the
     * sandbox hides /usr/local/bin, so the agent reports that opencode is
     * missing or "operation not permitted". The guest is already isolated by
     * the APK, so the extra sandbox only breaks tools. Merge the keys we need
     * and keep everything else the user changed.
     */
    private void prepareAntigravity() {
        String script = ""
                + "mkdir -p /root/.gemini/antigravity-cli\n"
                + "if command -v python3 >/dev/null 2>&1; then\n"
                + "python3 - <<'PY'\n"
                + "import json\n"
                + "p = '/root/.gemini/antigravity-cli/settings.json'\n"
                + "try:\n"
                + "    d = json.load(open(p))\n"
                + "except Exception:\n"
                + "    d = {}\n"
                + "d['enableTerminalSandbox'] = False\n"
                + "d['toolPermission'] = 'always-proceed'\n"
                + "d['allowNonWorkspaceAccess'] = True\n"
                + "ws = [w for w in (d.get('trustedWorkspaces') or [])\n"
                + "      if isinstance(w, str)]\n"
                + "if '/root' not in ws:\n"
                + "    ws.append('/root')\n"
                + "d['trustedWorkspaces'] = ws\n"
                + "voice = d.get('voice')\n"
                + "if not isinstance(voice, dict):\n"
                + "    voice = {}\n"
                + "voice.setdefault('voiceMode', 'live')\n"
                + "voice.setdefault('fullDuplex', True)\n"
                + "voice.setdefault('volume', 100)\n"
                + "d['voice'] = voice\n"
                + "json.dump(d, open(p, 'w'), indent=2)\n"
                + "PY\n"
                + "fi\n";
        try {
            guestHome().mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(
                    new File(Setup.STORAGE_DIR, "agy-env.sh"));
            out.write(script.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception e) {
            Logger.i("agy-setup: failed " + e);
        }
    }

    private void prepareClaude() {
        // Claude Code reads ~/.claude/settings.json; pin the gateway there so
        // discovery and routing survive even when the shell env is lost. The
        // "model" key some earlier setup wrote is dropped so the picker can
        // list the gateway models instead of forcing Haiku.
        String script = ""
                + "mkdir -p /root\n"
                // python3 is missing in some Ubuntu rootfs images, so the
                // settings files are written by hand when it is unavailable.
                + "if command -v python3 >/dev/null 2>&1; then\n"
                + "python3 - <<'PY'\n"
                + "import json, os\n"
                + "p = '/root/.claude/settings.json'\n"
                + "try:\n"
                + "    d = json.load(open(p))\n"
                + "except Exception:\n"
                + "    d = {}\n"
                + "d.pop('model', None)\n"
                + "env = dict(d.get('env') or {})\n"
                + "env['ANTHROPIC_BASE_URL'] = 'http://127.0.0.1:8797'\n"
                + "env['ANTHROPIC_AUTH_TOKEN'] = 'attc'\n"
                + "env['CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY'] = '1'\n"
                + "env['CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC'] = '1'\n"
                + "env['CLAUDE_CODE_DISABLE_UNKNOWN_MODEL_WINDOW_ENFORCEMENT'] = '1'\n"
                + "d['env'] = env\n"
                + "if not d.get('theme'):\n"
                + "    d['theme'] = 'dark'\n"
                + "json.dump(d, open(p, 'w'), indent=2)\n"
                + "q = '/root/.claude.json'\n"
                + "try:\n"
                + "    c = json.load(open(q))\n"
                + "except Exception:\n"
                + "    c = {}\n"
                + "c['hasCompletedOnboarding'] = True\n"
                + "projects = c.setdefault('projects', {})\n"
                + "for folder in ('/root', '/root/attc/workspace'):\n"
                + "    projects[folder] = {\n"
                + "        'hasTrustDialogAccepted': True,\n"
                + "        'hasCompletedProjectOnboarding': True,\n"
                + "    }\n"
                + "json.dump(c, open(q, 'w'))\n"
                + "PY\n"
                + "else\n"
                + "mkdir -p /root/.claude\n"
                + "cat > /root/.claude/settings.json <<'JSON'\n"
                + "{\"env\":{\"ANTHROPIC_BASE_URL\":\"http://127.0.0.1:8797\","
                + "\"ANTHROPIC_AUTH_TOKEN\":\"attc\","
                + "\"CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY\":\"1\","
                + "\"CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC\":\"1\","
                + "\"CLAUDE_CODE_DISABLE_UNKNOWN_MODEL_WINDOW_ENFORCEMENT\":\"1\"},"
                + "\"theme\":\"dark\"}\n"
                + "JSON\n"
                + "cat > /root/.claude.json <<'JSON'\n"
                + "{\"hasCompletedOnboarding\":true,\"projects\":{"
                + "\"/root\":{\"hasTrustDialogAccepted\":true,"
                + "\"hasCompletedProjectOnboarding\":true},"
                + "\"/root/attc/workspace\":{\"hasTrustDialogAccepted\":true,"
                + "\"hasCompletedProjectOnboarding\":true}}}\n"
                + "JSON\n"
                + "fi\n";
        try {
            guestHome().mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(
                    new File(Setup.STORAGE_DIR, "claude-env.sh"));
            out.write(script.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception e) {
            Logger.i("claude-setup: failed " + e);
        }
    }

    /** Starts the one-shot guest audio bootstrap without blocking the CLI. */
    private void bootstrapAudio(Session session) {
        File script = new File(Setup.STORAGE_DIR, "audio-bootstrap.sh");
        if (script.exists()) {
            session.write("( sh /root/attc/audio-bootstrap.sh >/dev/null 2>&1 & )\r");
        }
    }

    /**
     * Antigravity can speak and listen through Gemini Live, but only when the
     * account allows it. Ask for live voice once the TUI has settled; the CLI
     * answers with its own notice when the plan blocks it, and nothing else on
     * the audio path depends on this succeeding.
     */
    private void scheduleAgyVoice(Session session) {
        if (prefs == null || !prefs.getBoolean(PREF_AGY_VOICE, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(12000L);
            } catch (InterruptedException e) {
                return;
            }
            if (Hub.session != session || !session.isRunning()
                    || !"agy".equals(activeCli)) {
                return;
            }
            Logger.i("agy: requesting live voice");
            session.write("/voice live\r");
        }, "agy-voice");
        t.setDaemon(true);
        t.start();
    }

    /** Opens (or switches to) a CLI, one PTY per CLI so they can run together. */
    private void launchSelectedCli(String cli) {
        openCli(cli);
    }

    private void openCli(String cli) {
        if (cli == null || cli.isEmpty()) {
            cli = "opencode";
        }
        Session running = Hub.findByCli(cli);
        if (running != null) {
            activateSession(running);
            if (!running.cliRunning()) {
                // The PTY is sitting at the shell prompt after an earlier exit.
                launchIntoSession(running, cli);
            }
            return;
        }
        if (setup == null) {
            pendingCli = cli;
            if (!booting) {
                booting = true;
                worker.execute(this::boot);
            }
            return;
        }
        startNewSession(cli);
    }

    /** Creates a fresh PTY for one CLI; other CLI sessions keep running. */
    private void startNewSession(String cli) {
        int rows = Hub.buffer != null ? Hub.buffer.rows() : 24;
        int cols = Hub.buffer != null ? Hub.buffer.cols() : 80;
        TerminalBuffer buffer = new TerminalBuffer(rows, cols);
        Session session = new Session(buffer, cli, SessionStore.label(cli));
        session.setExitListener(code -> onSessionEnded(session, code));
        String[] argv = buildArgv("/bin/bash", "-l");
        String[] envp = buildEnv();
        Logger.i("session: opening " + cli + " in a new pty");
        boolean ok = session.start(argv, envp, "/", rows, cols);
        if (!ok) {
            Logger.e("forkpty failed for " + cli, null);
            emit("");
            emit("ERROR: could not start the Ubuntu shell for " + cli + ".");
            Hub.setStatus("Could not start " + cli);
            return;
        }
        Hub.addSession(session);
        activateSession(session);
        VoiceBridge.install(this);
        Privacy.apply(this, setup != null ? setup.rootDir() : new Setup(this).rootDir());
        launchIntoSession(session, cli);
    }

    private void activateSession(Session session) {
        if (session == null) {
            return;
        }
        Hub.setActive(session);
        activeCli = session.cli();
        cliRunning = session.cliRunning();
        Hub.cliActive = true;
        Hub.cliExited = false;
        status = "Running";
        Hub.setStatus(session.cliRunning() ? "Running" : "Terminal");
        TerminalBuffer buffer = session.buffer();
        if (buffer != null && Hub.buffer != null) {
            int rows = Hub.buffer.rows();
            int cols = Hub.buffer.cols();
            buffer.resize(rows, cols);
            session.resize(rows, cols);
        }
        Logger.i("session: active " + session.cli() + " id=" + session.id());
        updateNotification();
    }

    private void onSessionEnded(Session session, int code) {
        Hub.removeSession(session);
        Logger.i("session: " + session.cli() + " id=" + session.id()
                + " ended code=" + code);
        if (Hub.session == session) {
            Session next = Hub.firstRunning();
            if (next != null) {
                activateSession(next);
            } else {
                Hub.setActive(null);
                cliRunning = false;
                Hub.cliActive = false;
                Hub.cliExited = true;
                Hub.setStatus("Session ended (exit " + code + ")");
            }
        }
        updateNotification();
    }

    /** Writes the chosen CLI (or the plain shell) into one session's PTY. */
    private void launchIntoSession(Session session, String cli) {
        if (session == null || !session.isRunning()) {
            return;
        }
        activateSession(session);
        TerminalBuffer b = session.buffer();
        if (b != null) {
            // The shell may have started at the boot size before the view was
            // laid out. Start the CLI in a PTY that already matches the grid
            // the user sees, otherwise its first frames scroll out of view.
            session.resize(b.rows(), b.cols());
        }
        new File(Setup.STORAGE_DIR, "cli-exit-" + session.id()).delete();
        session.markCliStarted();
        cliStartedAt = System.currentTimeMillis();
        cliRunning = !"terminal".equals(cli);
        if ("terminal".equals(cli)) {
            session.markCliStopped();
        }
        Hub.cliActive = true;
        Hub.cliExited = false;
        status = "Running";
        Hub.setStatus("Running");
        SessionStore.record(cli);
        Logger.i("session: launching " + cli + " id=" + session.id());
        // A fresh install or an app update can leave the CLI missing again;
        // install it once here instead of failing with "command not found".
        if (("claude".equals(cli) || "kimi".equals(cli) || "agy".equals(cli))
                && !cliInstalled(cli)) {
            installCli(cli);
            return;
        }
        activeCli = cli;
        bootstrapAudio(session);
        // After this CLI returns, write a per-session marker the service
        // watches. A shared marker would make one CLI's exit hide another.
        String exitMark = "; printf '%s' \"$?\" > /root/attc/cli-exit-" + session.id();
        if ("claude".equals(cli)) {
            // Claude Code talks to the in-app gateway, which speaks the
            // Anthropic Messages API and forwards to the free Zen models.
            prepareClaude();
            session.write("export PATH=/root/.local/bin:/root/.kimi-code/bin:$PATH\r");
            session.write("export ANTHROPIC_BASE_URL=http://127.0.0.1:8797"
                    + " ANTHROPIC_AUTH_TOKEN=attc"
                    + " CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY=1"
                    + " CLAUDE_CODE_DISABLE_UNKNOWN_MODEL_WINDOW_ENFORCEMENT=1"
                    + " CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1\r");
            session.write("sh /root/attc/claude-env.sh\r");
            session.write("cd /root; claude --dangerously-skip-permissions"
                    + exitMark + "\r");
        } else if ("agy".equals(cli)) {
            prepareAntigravity();
            session.write("export PATH=/root/.local/bin:/root/.kimi-code/bin:$PATH\r");
            session.write("sh /root/attc/agy-env.sh\r");
            // The starter account warns that --mode auto is unavailable, but
            // --dangerously-skip-permissions is what lets the agent run Bash,
            // edit files and open links without a prompt. The model alias is
            // pinned so voice mode and the status line stay on Flash Low.
            session.write("cd /root; agy --dangerously-skip-permissions"
                    + " --mode auto --model gemini-3.8-flash-low"
                    + exitMark + "\r");
            scheduleAgyVoice(session);
        } else if ("codex".equals(cli)) {
            Setup s = setup;
            if (s == null || !s.codexReady()) {
                installCodex();
                return;
            }
            session.write("export PATH=/root/.local/bin:$PATH\r");
            // The standalone release ships without the background server
            // package; --no-daemon is the supported mode and avoids the
            // immediate "no complete local package" exit.
            session.write("cd /root; codex --no-daemon"
                    + " --dangerously-bypass-approvals-and-sandbox"
                    + exitMark + "\r");
        } else if ("kimi".equals(cli)) {
            session.write("export PATH=/root/.local/bin:/root/.kimi-code/bin:$PATH\r");
            // The OAuth device request is the one call that must stay quick.
            // Sending it through the loopback egress proxy adds several
            // seconds; auth.kimi.com is reachable directly (about 1s), so
            // bypass the proxy for this process only. API traffic keeps using
            // the normal route.
            session.write("cd /root; NO_PROXY=\"$NO_PROXY,auth.kimi.com\""
                    + " no_proxy=\"$no_proxy,auth.kimi.com\" kimi --auto"
                    + exitMark + "\r");
        } else if ("terminal".equals(cli)) {
            // Plain shell: nothing to launch, but make the installed CLIs
            // available if the user starts one by hand.
            session.write("export PATH=/root/.local/bin:/root/.kimi-code/bin:$PATH\r");
            session.write("cd /root\r");
        } else {
            // The installed wrapper already appends --standalone, so passing it
            // here would duplicate the flag and opencode rejects the launch.
            session.write("cd /root; opencode" + exitMark + "\r");
        }
        updateNotification();
        flushPendingVoice();
    }

    /**
     * First Codex launch: fetch the official standalone build into the guest
     * and launch it when the download finishes. Replaces the old dead-end hint.
     */
    private void installCodex() {
        final Setup s = setup;
        if (s == null) {
            emit("ERROR: setup is not ready.");
            return;
        }
        Hub.setStatus("Installing Codex...");
        emit("");
        emit("Codex CLI is not installed yet.");
        emit("Downloading the official build once (about 120 MB)...");
        Logger.i("codex: missing, installing");
        new Thread(() -> {
            try {
                s.installCodex(new Setup.Progress() {
                    @Override
                    public void line(String text) {
                        emit(text);
                    }

                    @Override
                    public void failed(String message) {
                        emit("ERROR: " + message);
                    }

                    @Override
                    public void ready() {
                    }
                });
                emit("Codex installed.");
                openCli("codex");
            } catch (Exception e) {
                Logger.e("codex install failed", e);
                cliRunning = false;
                Hub.cliActive = false;
                Hub.cliExited = true;
                emit("");
                emit("ERROR: could not install Codex: " + e.getMessage());
                emit("Install it by hand with: npm install -g @openai/codex");
                Hub.setStatus("Codex install failed");
                updateNotification();
            }
        }, "codex-install").start();
    }

    /**
     * True when the CLI's launcher exists in the guest user's bin folder.
     * The no-follow check keeps the launch path fast and works even while the
     * guest is busy; the installer writes here on a successful run. Following
     * the link would fail because its target is an absolute guest path that
     * the Android process cannot resolve.
     */
    private boolean cliInstalled(String cli) {
        Setup s = setup != null ? setup : new Setup(this);
        java.nio.file.Path launcher = new File(s.rootDir(), "root/.local/bin/" + cli).toPath();
        return java.nio.file.Files.exists(launcher, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Installs a missing CLI with its official installer and then relaunches
     * it. Runs on the worker thread because the installer downloads packages
     * and may take a few minutes on a phone.
     */
    private void installCli(final String cli) {
        final Setup s = setup != null ? setup : new Setup(this);
        final String name = cliDisplay(cli);
        final String url = cliInstallUrl(cli);
        emit("");
        emit(name + " is not installed in this Ubuntu environment.");
        emit("Installing it with the official installer (one time, this can take a few minutes)...");
        Hub.setStatus("Installing " + name + "...");
        Logger.i("cli: installing " + cli);
        worker.execute(() -> {
            String script;
            if ("kimi".equals(cli)
                    && new File(s.rootDir(), "root/.kimi-code/bin/kimi").exists()) {
                // The official installer already unpacked its runtime; only
                // the launcher in ~/.local/bin is missing.
                script = "export PATH=" + GUEST_PATH + "\n"
                        + "ln -sf /root/.kimi-code/bin/kimi /root/.local/bin/kimi\n"
                        + "test -x /root/.local/bin/kimi || exit 1\n"
                        + "command -v kimi\n";
            } else {
                script = "export PATH=" + GUEST_PATH + "\n"
                        + "export DEBIAN_FRONTEND=noninteractive\n"
                        + "command -v curl >/dev/null 2>&1 || {"
                        + " apt-get update -qq >/dev/null 2>&1;"
                        + " apt-get install -y -qq curl ca-certificates >/dev/null 2>&1; }\n"
                        + "curl -fsSL " + url + " | bash\n"
                        + "command -v " + cli + "\n";
            }
            String out = runGuestOnceTimeout(600000L, "/bin/bash", "-lc", script);
            Logger.i("cli: install " + cli + " ->\n" + out);
            java.util.List<String> tail = new java.util.ArrayList<>();
            for (String line : out.split("\n")) {
                if (line.trim().length() > 0) {
                    tail.add(line);
                }
            }
            for (int i = Math.max(0, tail.size() - 20); i < tail.size(); i++) {
                emit(tail.get(i));
            }
            if (cliInstalled(cli)) {
                emit("Installed. Starting " + name + ".");
                handler.postDelayed(() -> openCli(cli), 1500L);
            } else {
                emit("Install failed. Manual command: curl -fsSL " + url + " | bash");
                Hub.setStatus("Running");
            }
        });
    }

    /** Human name used in the installer messages. */
    private static String cliDisplay(String cli) {
        if ("claude".equals(cli)) {
            return "Claude Code";
        }
        if ("kimi".equals(cli)) {
            return "Kimi Code";
        }
        if ("agy".equals(cli)) {
            return "Antigravity";
        }
        return cli;
    }

    /** Official installer URL for a CLI. */
    private static String cliInstallUrl(String cli) {
        if ("claude".equals(cli)) {
            return CLAUDE_INSTALL;
        }
        if ("kimi".equals(cli)) {
            return KIMI_INSTALL;
        }
        if ("agy".equals(cli)) {
            return AGY_INSTALL;
        }
        return "";
    }

    private void startSession() {
        runFsProbeOnce();
        schedulePrivacyRotation();
        guestHome().mkdirs();
        String cli = pendingCli == null ? "opencode" : pendingCli;
        openCli(cli);
        if (!quotaTimerArmed) {
            quotaTimerArmed = true;
            Logger.i("quota-reset: timer armed for " + (QuotaReset.INTERVAL_MS / 60000L) + " min");
            handler.postDelayed(this::autoQuotaReset, QuotaReset.INTERVAL_MS);
        }
        worker.execute(this::runGuestIpProbe);
    }

    /**
     * Proves the guest actually gets the simulated address: runs a plain-HTTP
     * request from inside the rootfs through the loopback proxy using bash's
     * /dev/tcp (no curl/wget needed) and logs the answer.
     */
    private void runGuestIpProbe() {
        String script = "exec 3<>/dev/tcp/127.0.0.1/" + IpEcho.PROXY_PORT + " || { echo IP=PROXY_DOWN; exit 3; }; "
                + "printf 'GET http://api.ipify.org/ HTTP/1.0\\r\\nHost: api.ipify.org\\r\\n\\r\\n' >&3; "
                + "IP=$(awk 'NF{last=$0} END{print last}' <&3); echo \"IP=$IP\"; "
                + "T=\"\"; for c in curl wget openssl node tor apt-get python3 busybox; do "
                + "p=$(command -v $c 2>/dev/null); [ -n \"$p\" ] && T=\"$T $c=$p\"; done; echo \"TOOLS=$T\"";
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(buildArgv("/bin/bash", "-c", script));
            builder.redirectErrorStream(true);
            for (String entry : buildEnv()) {
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    builder.environment().put(entry.substring(0, eq), entry.substring(eq + 1));
                }
            }
            process = builder.start();
            final StringBuilder out = new StringBuilder();
            final Process probe = process;
            Thread reader = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(probe.getInputStream(),
                                StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (out) {
                            out.append(line.trim()).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "attc-guest-ip");
            reader.setDaemon(true);
            reader.start();
            reader.join(25000);
            String seen = "";
            String tools = "";
            synchronized (out) {
                for (String line : out.toString().split("\n")) {
                    if (line.startsWith("IP=")) {
                        seen = line.substring(3).trim();
                    } else if (line.startsWith("TOOLS=")) {
                        tools = line.substring(6).trim();
                    }
                }
            }
            String expected = VpnState.getSimulatedIp(this);
            if (seen.equals(expected)) {
                Logger.i("guest-ip: ok " + seen);
                IpEcho.setGuestResult("ok " + seen);
            } else {
                Logger.i("guest-ip: MISMATCH seen=" + seen + " expected=" + expected);
                IpEcho.setGuestResult("mismatch " + seen);
            }
            Logger.i("guest-tools:" + tools);
        } catch (Exception e) {
            Logger.i("guest-ip: failed " + e);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private String[] buildArgv(String... command) {
        File proot = setup.proot();
        File rootDir = setup.rootDir();
        String root = rootDir.getAbsolutePath();
        java.util.ArrayList<String> a = new java.util.ArrayList<>();
        a.add(proot.getAbsolutePath());
        a.add("--link2symlink");
        a.add("-0");
        a.add("-r");
        a.add(root);
        a.add("-b");
        a.add("/dev");
        a.add("-b");
        a.add("/proc");
        a.add("-b");
        a.add("/sys");

        // Termux-style access to the real phone filesystem. Each bind needs a
        // mount point inside the rootfs, otherwise proot refuses to start.
        addBind(a, rootDir, "/storage", "/storage");
        addBind(a, rootDir, "/sdcard", "/sdcard");
        addBind(a, rootDir, "/mnt", "/mnt");
        addBind(a, rootDir, "/system", "/system");

        String bind = Setup.STORAGE_DIR;
        if (new File(bind).exists()) {
            //noinspection ResultOfMethodCallIgnored
            new File(rootDir, "root/attc").mkdirs();
            a.add("-b");
            a.add(bind + ":/root/attc");
        } else {
            Logger.i("bind skipped " + bind + " (missing)");
        }
        if (Privacy.enabled(this)) {
            File mac = Privacy.macFile(this);
            if (mac.exists()) {
                a.add("-b");
                a.add(mac.getAbsolutePath() + ":/sys/class/net/wlan0/address");
            }
        }
        a.add("-w");
        a.add("/root");
        a.add("/usr/bin/env");
        a.add("-i");
        a.add("HOME=/root");
        a.add("TERM=xterm-256color");
        a.add("LANG=C.UTF-8");
        a.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        a.add("SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt");
        a.add("CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt");
        a.add("GIT_SSL_CAINFO=/etc/ssl/certs/ca-certificates.crt");
        // IP-echo interception: curl and friends use this loopback proxy, which
        // answers the allowlisted IP-check hosts locally and tunnels the rest.
        String proxy = "http://127.0.0.1:" + IpEcho.PROXY_PORT;
        a.add("HTTP_PROXY=" + proxy);
        a.add("HTTPS_PROXY=" + proxy);
        a.add("http_proxy=" + proxy);
        a.add("https_proxy=" + proxy);
        a.add("ALL_PROXY=" + proxy);
        a.add("NO_PROXY=localhost,127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16");
        a.add("no_proxy=localhost,127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16");
        a.add("NODE_EXTRA_CA_CERTS=/etc/ssl/certs/attc-ipecho.pem");
        a.add("EXTERNAL_STORAGE=/storage/emulated/0");
        for (String part : command) {
            a.add(part);
        }
        return a.toArray(new String[0]);
    }

    /** Binds a host path at the same path in the guest, if the host path exists. */
    private void addBind(java.util.List<String> argv, File rootDir, String host, String guest) {
        if (!new File(host).exists()) {
            Logger.i("bind skipped " + host + " (missing)");
            return;
        }
        String relative = guest.startsWith("/") ? guest.substring(1) : guest;
        File mountPoint = new File(rootDir, relative);
        if (!mountPoint.exists() && !mountPoint.mkdirs() && !mountPoint.exists()) {
            Logger.i("bind skipped " + host + " (no mount point)");
            return;
        }
        argv.add("-b");
        argv.add(host + ":" + guest);
    }

    private String[] buildEnv() {
        Map<String, String> env = new HashMap<>();
        Map<String, String> inherited = System.getenv();
        if (inherited != null) {
            env.putAll(inherited);
        }
        File tmp = new File(getFilesDir(), "tmp");
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        env.put("LD_LIBRARY_PATH", setup.libDir().getAbsolutePath());
        env.put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        env.put("PROOT_LOADER", new File(setup.libDir(), "libproot-loader.so").getAbsolutePath());
        env.put("HOME", getFilesDir().getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        String[] out = new String[env.size()];
        int i = 0;
        for (Map.Entry<String, String> e : env.entrySet()) {
            out[i++] = e.getKey() + "=" + e.getValue();
        }
        return out;
    }

    // -------------------------------------------------------------- fs probe

    /**
     * Manual path probe behind {@code --ez fstest true}. Prints what the guest
     * really sees under /storage and proves it can write there.
     */
    /**
     * Test hook behind {@code --es guestcmd "..."}: runs one shell command in
     * the guest and writes its output to attc.log. Used by build tooling and
     * manual diagnostics; never reachable from the UI.
     */
    private void runGuestCmd(String command) {
        Logger.i("guestcmd$ " + command);
        String out = runGuestOnceTimeout(600000L, "/bin/sh", "-c", command);
        Logger.i("guestcmd out:\n" + out);
    }

    private void runFsTest() {        String script =
                "for p in /storage/emulated/0 '/storage/emulated/0/ATTC OPENCODE' /sdcard; do "
                        + "if [ -d \"$p\" ]; then echo \"DIR_OK $p\"; "
                        + "else echo \"DIR_MISSING $p\"; fi; done; "
                        + "d='/storage/emulated/0/ATTC OPENCODE/logs'; "
                        + "if echo probe > \"$d/.fstest\" 2>/dev/null; then "
                        + "echo \"WRITE_OK $d\"; rm -f \"$d/.fstest\"; "
                        + "else echo \"WRITE_FAIL $d\"; fi; "
                        + "ls -ld /storage /sdcard 2>&1; "
                        + "echo ALSA_CONF; head -6 /etc/asound.conf 2>&1; "
                        + "echo ALSA_PCMS; (aplay -L 2>/dev/null || echo no-aplay) | head -8; "
                        + "echo CFG; for f in /root/.config/opencode/opencode.json "
                        + "/root/.config/opencode/opencode.jsonc; do "
                        + "echo \"--$f\"; grep -i -n 'attention\\|sound' \"$f\" 2>/dev/null | head -10; done; "
                        + "echo CLI; sed -n '1,60p' /root/.config/opencode/cli.json 2>&1";
        String out = runGuestOnceTimeout(30000L, "/bin/sh", "-c", script);
        Logger.i("fstest output:\n" + out);
    }

    private static final String FS_PROBE_MARKER = ".fs-probe-1";
    private static final String FS_PROBE_SCRIPT =
            "for p in /storage/emulated/0 /sdcard /mnt /system; "
                    + "do if [ -d \"$p\" ]; then echo \"OK $p\"; else echo \"MISSING $p\"; fi; done";

    /**
     * One-shot check that the proot binds expose the phone filesystem. Runs on
     * the setup worker thread before the PTY starts and never blocks the shell
     * for more than 20 seconds. The marker makes it a single boot cost.
     */
    private void runFsProbeOnce() {
        File marker = new File(getFilesDir(), FS_PROBE_MARKER);
        if (marker.exists()) {
            return;
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(buildArgv("/bin/sh", "-c", FS_PROBE_SCRIPT));
            builder.redirectErrorStream(true);
            for (String entry : buildEnv()) {
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    builder.environment().put(entry.substring(0, eq), entry.substring(eq + 1));
                }
            }
            process = builder.start();

            final StringBuilder output = new StringBuilder();
            final Process probe = process;
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(probe.getInputStream(),
                                    StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            synchronized (output) {
                                output.append(line).append('\n');
                            }
                            Logger.i("fs-probe: " + line);
                        }
                    } catch (Exception e) {
                        Logger.i("fs-probe: read " + e);
                    }
                }
            }, "attc-fs-probe");
            reader.setDaemon(true);
            reader.start();
            reader.join(20000);

            if (reader.isAlive()) {
                throw new java.io.IOException("timed out");
            }
            int code = process.waitFor();
            synchronized (output) {
                if (code != 0) {
                    throw new java.io.IOException("exit " + code);
                }
                if (output.indexOf("MISSING ") >= 0) {
                    throw new java.io.IOException("some paths are missing");
                }
            }
            //noinspection ResultOfMethodCallIgnored
            marker.createNewFile();
            Logger.i("fs-probe: ok");
        } catch (Exception e) {
            String msg = e.getMessage();
            Logger.i("fs-probe: failed "
                    + (msg == null || msg.isEmpty() ? e.getClass().getSimpleName() : msg));
            //noinspection ResultOfMethodCallIgnored
            marker.delete();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    // --------------------------------------------------------------- updates

    private static boolean updateWatcherStarted;

    /**
     * Watches for the request file written by the wrapper's update path. The
     * guest cannot reach the update host reliably through the egress proxy, so
     * the app performs the download on the default route instead.
     */
    private void startUpdateWatcher() {
        synchronized (TerminalService.class) {
            if (updateWatcherStarted) {
                return;
            }
            updateWatcherStarted = true;
        }
        Thread thread = new Thread(() -> {
            File request = new File(Setup.STORAGE_DIR, "update.request");
            while (true) {
                try {
                    if (request.isFile()) {
                        //noinspection ResultOfMethodCallIgnored
                        request.delete();
                        emit("Update requested from the terminal.");
                        updateOpencode();
                    }
                } catch (Exception e) {
                    Logger.i("update-watch: " + e);
                }
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "attc-update-watch");
        thread.setDaemon(true);
        thread.start();
    }

    private void updateOpencode() {
        emit("");
        emit("Checking for opencode updates...");
        worker.execute(() -> {
            try {
                Setup s = new Setup(this);
                s.updateNow(new Setup.Progress() {
                    @Override
                    public void line(String text) {
                        emit(text);
                    }

                    @Override
                    public void failed(String message) {
                        emit("Update failed: " + message);
                    }

                    @Override
                    public void ready() {
                    }
                });
                emit("opencode update finished. Restart the terminal to use it.");
            } catch (Exception e) {
                emit("Update failed: " + e.getMessage());
            }
        });
    }

    // --------------------------------------------------------------- quota

    /**
     * Makes the next request look like it comes from a fresh client: rotate the
     * simulated address and the device identity, restart opencode so it binds a
     * new private server port, then restore the persisted conversation.
     */
    private void quotaReset() {
        emit("");
        emit("Resetting quota: new opencode server address...");

        if (VpnState.isRunning()) {
            try {
                startService(new Intent(this, FakeVpnService.class)
                        .setAction(FakeVpnService.ACTION_ROTATE));
            } catch (Exception ignored) {
            }
        } else {
            VpnState.rotate(this);
        }
        Privacy.apply(this, setup != null ? setup.rootDir() : new Setup(this).rootDir());

        Session session = Hub.session;
        if (session != null) {
            session.write("\u0003");
        }
        try {
            Thread.sleep(1500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String out = runGuestOnce("/bin/sh", "-c",
                "for p in $(pgrep -x opencode); do kill -9 \"$p\" 2>/dev/null; done; sleep 1; pgrep -x opencode | tr '\\n' ' ' || true");
        Logger.i("quota-reset: remaining opencode pids: [" + out + "]");
        try {
            Thread.sleep(800L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        session = Hub.session;
        if (session != null) {
            // Sessions are persisted, so this restores the conversation.
            session.write("opencode --continue\r");
        }
        Logger.i("quota-reset: opencode relaunched with a fresh server address");
    }

    private void autoQuotaReset() {
        if (Hub.session != null && !Hub.failed) {
            try {
                worker.execute(this::quotaReset);
            } catch (Exception e) {
                quotaTimerArmed = false;
                return;
            }
            handler.postDelayed(this::autoQuotaReset, QuotaReset.INTERVAL_MS);
        } else {
            quotaTimerArmed = false;
        }
    }

    /** Runs one proot command to completion and returns its trimmed output. */
    private String runGuestOnce(String... command) {
        return runGuestOnceTimeout(20000L, command);
    }

    /** Runs one proot command and waits up to {@code timeoutMs} for it. */
    private String runGuestOnceTimeout(long timeoutMs, String... command) {
        Process process = null;
        final StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder builder = new ProcessBuilder(buildArgv(command));
            builder.redirectErrorStream(true);
            for (String entry : buildEnv()) {
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    builder.environment().put(entry.substring(0, eq), entry.substring(eq + 1));
                }
            }
            process = builder.start();
            final Process guest = process;
            Thread reader = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(guest.getInputStream(),
                                StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "attc-guest-once");
            reader.setDaemon(true);
            reader.start();
            reader.join(timeoutMs);
            synchronized (output) {
                return output.toString().trim();
            }
        } catch (Exception e) {
            Logger.i("guest-once: failed " + e);
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    // ------------------------------------------------------------- self test

    /**
     * Asks the free model for a fixed marker on the guest side and writes the
     * result to the shared folder. Used to prove that a quota reset really
     * brings the free allowance back; the payload never comes from outside the
     * app, so adb cannot inject commands here.
     */
    private void quotaProbe() {
        emit("");
        emit("Quota probe: asking the free model...");
        long started = System.currentTimeMillis();
        String out = runGuestOnceTimeout(420000L, "/bin/sh", "-c",
                "echo '==version=='; opencode --version 2>&1 | head -3; "
                        + "echo '==run=='; "
                        + "opencode run 'Reply with exactly QUOTA_OK and nothing else' 2>&1 | tail -60; "
                        + "echo '==end=='");
        long seconds = (System.currentTimeMillis() - started) / 1000L;
        String lower = out.toLowerCase(java.util.Locale.US);
        String verdict;
        if (lower.contains("free usage limit") || lower.contains("free usage exceeded")
                || lower.contains("insufficient_quota") || lower.contains("quota exceeded")
                || lower.contains("credit balance is too low")) {
            verdict = "FAIL free quota still exhausted";
        } else if (lower.contains("quota_ok")) {
            verdict = "PASS free model answered on a fresh server address";
        } else if (out.isEmpty()) {
            verdict = "UNKNOWN no output";
        } else {
            verdict = "UNKNOWN no quota error and no marker";
        }
        StringBuilder report = new StringBuilder();
        report.append("verdict=").append(verdict).append('\n');
        report.append("seconds=").append(seconds).append('\n');
        report.append("at=").append(new java.util.Date()).append('\n');
        report.append("---- output ----\n").append(out).append('\n');
        writeLog("quota-probe.log", report.toString());
        Logger.i("quota-probe: " + verdict + " in " + seconds + "s");
        for (String line : out.split("\n")) {
            if (line.length() > 0) {
                Logger.i("quota-probe| " + line.substring(0, Math.min(300, line.length())));
            }
        }
        emit("Quota probe finished: " + verdict);
    }

    /** Writes the visible terminal contents to the shared log folder. */
    private void dumpScreen(String name) {
        TerminalBuffer buffer = Hub.buffer;
        String text = buffer == null ? "<no terminal buffer>"
                : buffer.text(0, 0, buffer.rows() - 1, buffer.cols() - 1, 0);
        writeLog("screen-" + name + ".txt", text);
        String raw = Hub.tail();
        writeLog("screen-" + name + "-raw.txt", raw == null ? "" : raw);
        Logger.i("screen: dumped " + text.length() + " chars (" + name + ")"
                + (buffer == null ? " no buffer"
                : " rows=" + buffer.rows() + " cols=" + buffer.cols()
                + " history=" + buffer.historySize())
                + " raw=" + (raw == null ? 0 : raw.length()));
    }

    /** Types /update into the live TUI and snapshots the screen afterwards. */
    private void runTuiUpdate() {
        Session session = Hub.session;
        if (session == null) {
            Logger.i("tui-update: no terminal session");
            return;
        }
        dumpScreen("update-before");
        session.write("/update");
        Logger.i("tui-update: typed /update into the terminal");
        // The command palette needs a separate Enter once the list is drawn.
        handler.postDelayed(() -> {
            Session s = Hub.session;
            if (s != null) {
                s.write("\r");
            }
            Logger.i("tui-update: sent Enter to run the command");
        }, 1500L);
        handler.postDelayed(() -> dumpScreen("update-8s"), 8000L);
        handler.postDelayed(() -> dumpScreen("update-25s"), 25000L);
        handler.postDelayed(() -> dumpScreen("update-60s"), 60000L);
    }

    /** Types a fixed sentence into the live TUI and snapshots the answer. */
    private void runTuiPing() {
        Session session = Hub.session;
        if (session == null) {
            Logger.i("tui-ping: no terminal session");
            return;
        }
        dumpScreen("ping-before");
        session.write("Reply with exactly QUOTA_OK and nothing else");
        handler.postDelayed(() -> {
            Session s = Hub.session;
            if (s != null) {
                s.write("\r");
            }
            Logger.i("tui-ping: prompt sent to the terminal");
        }, 800L);
        handler.postDelayed(() -> dumpScreen("ping-20s"), 20000L);
        handler.postDelayed(() -> dumpScreen("ping-50s"), 50000L);
        handler.postDelayed(() -> dumpScreen("ping-100s"), 100000L);
    }

    /** Lists the guest assistant account state without printing any token. */
    private void dumpGuestAuth() {
        String out = runGuestOnceTimeout(90000L, "/bin/sh", "-c",
                "ls -la /root/.local/share/opencode 2>&1 | head -30; "
                        + "echo '==config=='; ls -la /root/.config/opencode 2>&1 | head -30; "
                        + "echo '==storage=='; ls -la /root/.local/share/opencode/storage 2>&1 | head -20; "
                        + "echo '==auth-list=='; opencode auth list 2>&1 | head -20; "
                        + "echo '==help=='; opencode --help 2>&1 | head -45; "
                        + "echo '==auth-keys=='; "
                        + "for f in /root/.local/share/opencode/auth.json "
                        + "/root/.config/opencode/auth.json; do "
                        + "if [ -f \"$f\" ]; then echo \"file=$f\"; "
                        + "grep -o '\"[a-zA-Z0-9_-]*\"[[:space:]]*:' \"$f\" | head -20; fi; done");
        writeLog("guest-auth.log", out);
        Logger.i("guest-auth: dumped " + out.length() + " chars");
        for (String line : out.split("\n")) {
            if (line.length() > 0) {
                Logger.i("guest-auth| " + line.substring(0, Math.min(200, line.length())));
            }
        }
    }

    /**
     * Runs one non-interactive free-model request in the guest and writes the
     * answer to the shared folder. Fixed payload; nothing comes from outside.
     */
    private void runProbe() {
        emit("");
        emit("Model probe: asking the free model...");
        long started = System.currentTimeMillis();
        String out = runGuestOnceTimeout(240000L, "/bin/sh", "-c",
                "opencode run -m opencode/deepseek-v4.1-flash-free "
                        + "'Reply with exactly QUOTA_OK and nothing else' 2>&1 | tail -40");
        long seconds = (System.currentTimeMillis() - started) / 1000L;
        String lower = out.toLowerCase(java.util.Locale.US);
        String verdict;
        if (lower.contains("free usage limit") || lower.contains("insufficient_quota")
                || lower.contains("quota exceeded") || lower.contains("credit balance is too low")) {
            verdict = "FAIL free quota still exhausted";
        } else if (lower.contains("quota_ok")) {
            verdict = "PASS free model answered";
        } else if (out.isEmpty()) {
            verdict = "UNKNOWN no output";
        } else {
            verdict = "UNKNOWN no quota error and no marker";
        }
        StringBuilder report = new StringBuilder();
        report.append("verdict=").append(verdict).append('\n');
        report.append("seconds=").append(seconds).append('\n');
        report.append("at=").append(new java.util.Date()).append('\n');
        report.append("---- output ----\n").append(out).append('\n');
        writeLog("run-probe.log", report.toString());
        Logger.i("run-probe: " + verdict + " in " + seconds + "s");
        for (String line : out.split("\n")) {
            if (line.length() > 0) {
                Logger.i("run-probe| " + line.substring(0, Math.min(300, line.length())));
            }
        }
        emit("Model probe finished: " + verdict);
    }

    private void writeLog(String name, String text) {
        try {
            File out = new File("/storage/emulated/0/ATTC OPENCODE/logs", name);
            File parent = out.getParentFile();
            if (parent != null) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Logger.i("write log failed " + name + ": " + e);
        }
    }

    // -------------------------------------------------------------- privacy

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Polls the marker the shell writes after a CLI exits. A file is cheaper
     * and more reliable than parsing the terminal stream for a sentinel.
     */
    private final Runnable cliExitTick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            for (Session s : Hub.sessions) {
                File marker = new File(Setup.STORAGE_DIR, "cli-exit-" + s.id());
                if (!s.cliRunning() || !marker.exists() || !marker.delete()) {
                    continue;
                }
                if (now - s.cliStartedAt() < 1500) {
                    continue;
                }
                Logger.i("session: " + s.cli() + " id=" + s.id()
                        + " exited, shell remains");
                s.markCliStopped();
                if (s == Hub.session) {
                    cliRunning = false;
                    Hub.cliActive = false;
                    Hub.cliExited = true;
                    Hub.setStatus("Choose a CLI");
                    updateNotification();
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable privacyTick = new Runnable() {
        @Override
        public void run() {
            File root = setup != null ? setup.rootDir() : new Setup(TerminalService.this).rootDir();
            Privacy.apply(TerminalService.this, root);
            Logger.i("privacy rotation tick");
            handler.postDelayed(this, Privacy.ROTATE_MS);
        }
    };

    private void schedulePrivacyRotation() {
        handler.removeCallbacks(privacyTick);
        if (Privacy.enabled(this)) {
            handler.postDelayed(privacyTick, Privacy.ROTATE_MS);
        }
    }

    // --------------------------------------------------------- voice assistant

    private boolean voiceEnabled() {
        return prefs != null && prefs.getBoolean(VoiceEngine.KEY_ENABLED, false);
    }

    private void startVoice() {
        if (!voiceEnabled()) {
            return;
        }
        if (!VoiceEngine.hasPermission(this)) {
            Logger.i("voice: enabled but RECORD_AUDIO is not granted");
            status = "Mikrofon izni bekleniyor";
            updateNotification();
            return;
        }
        if (!VoiceEngine.hasTemplates(this)) {
            Logger.i("voice: enabled but no wake templates");
            status = VoiceEngine.phrase(this) + " kaydı yok";
            updateNotification();
            return;
        }
        if (capturing) {
            return;
        }
        if (voiceEngine != null && voiceEngine.isRunning()) {
            applyLocks();
            return;
        }
        VoiceEngine engine = new VoiceEngine(this, new VoiceEngine.WakeListener() {
            @Override
            public void onWake() {
                onWakeDetected();
            }

            @Override
            public void onError(String message) {
                Logger.i("voice: engine error " + message);
                status = "Ses motoru: " + message;
                updateNotification();
            }
        });
        voiceEngine = engine;
        engine.start();
        applyLocks();
        updateNotification();
    }

    /** Short haptic feedback; the app deliberately plays no tones of its own. */
    private void vibrate(long millis) {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(
                    Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(millis);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopVoice() {
        VoiceEngine engine = voiceEngine;
        voiceEngine = null;
        if (engine != null) {
            engine.stop();
        }
        finishCapture();
        applyLocks();
        updateNotification();
    }

    private void onWakeDetected() {
        if (capturing || !voiceEnabled()) {
            return;
        }
        Logger.i("voice: wake -> command capture");
        vibrate(60);
        startCommandCapture();
    }

    private void startCommandCapture() {
        if (capturing) {
            return;
        }
        capturing = true;
        status = "Dinliyorum...";
        updateNotification();
        VoiceEngine engine = voiceEngine;
        voiceEngine = null;
        if (engine != null) {
            engine.stop();
        }
        handler.post(() -> {
            if (!capturing) {
                return;
            }
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                    Logger.i("voice: no speech recognition service");
                    status = "Konuşma tanıma yok";
                    finishCapture();
                    return;
                }
                speech = SpeechRecognizer.createSpeechRecognizer(this);
                speech.setRecognitionListener(new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        Logger.i("voice: stt ready");
                    }

                    @Override
                    public void onBeginningOfSpeech() {
                        Logger.i("voice: stt speech start");
                    }

                    @Override
                    public void onRmsChanged(float rmsdB) {
                    }

                    @Override
                    public void onBufferReceived(byte[] buffer) {
                    }

                    @Override
                    public void onEndOfSpeech() {
                        status = "Anlaşılıyor...";
                        updateNotification();
                    }

                    @Override
                    public void onError(int error) {
                        Logger.i("voice: stt error " + error
                                + " (" + speechError(error) + ")");
                        status = "Ses anlaşılamadı";
                        handler.removeCallbacks(voiceCaptureTimeout);
                        finishCapture();
                    }

                    @Override
                    public void onResults(Bundle results) {
                        handler.removeCallbacks(voiceCaptureTimeout);
                        java.util.ArrayList<String> list = results == null ? null
                                : results.getStringArrayList(
                                        SpeechRecognizer.RESULTS_RECOGNITION);
                        String text = list == null || list.isEmpty()
                                ? null : list.get(0);
                        finishCapture();
                        if (text != null && !text.trim().isEmpty()) {
                            Logger.i("voice: heard \"" + text + "\"");
                            handleVoiceText(text.trim());
                        } else {
                            Logger.i("voice: empty transcript");
                        }
                    }

                    @Override
                    public void onPartialResults(Bundle partialResults) {
                    }

                    @Override
                    public void onEvent(int eventType, Bundle params) {
                    }
                });
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR");
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
                intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,
                        getPackageName());
                // Give the speaker a moment after the wake beep, then stop on a
                // natural pause instead of waiting for the system default.
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1200L);
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1200L);
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        1200L);
                speech.startListening(intent);
                handler.removeCallbacks(voiceCaptureTimeout);
                handler.postDelayed(voiceCaptureTimeout, 16000L);
            } catch (Throwable t) {
                Logger.i("voice: stt start failed " + t);
                status = "Konuşma tanıma başlatılamadı";
                finishCapture();
            }
        });
    }

    private static String speechError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "audio";
            case SpeechRecognizer.ERROR_CLIENT:
                return "client";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "permission";
            case SpeechRecognizer.ERROR_NETWORK:
                return "network";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "no match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "busy";
            case SpeechRecognizer.ERROR_SERVER:
                return "server";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "speech timeout";
            default:
                return "code " + error;
        }
    }

    /** Releases the recognizer and hands the microphone back to the wake engine. */
    private void finishCapture() {
        handler.removeCallbacks(voiceCaptureTimeout);
        SpeechRecognizer s = speech;
        speech = null;
        if (s != null) {
            try {
                s.destroy();
            } catch (Throwable ignored) {
            }
        }
        if (!capturing && s == null) {
            return;
        }
        capturing = false;
        if (voiceEnabled()) {
            status = "Running";
            handler.postDelayed(this::startVoice, 800L);
        }
        updateNotification();
        applyLocks();
    }

    /**
     * Sends a recognised sentence to the chosen CLI. The CLI keeps whatever
     * model the user selected in its own UI; the app never overrides it.
     */
    private void handleVoiceText(String text) {
        String clean = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (clean.isEmpty()) {
            return;
        }
        String cli = voiceCli();
        Session target = Hub.findByCli(cli);
        if (target != null && target.isRunning() && ("terminal".equals(cli) || target.cliRunning())) {
            activateSession(target);
            writeVoicePrompt(clean);
            return;
        }
        pendingVoiceText = clean;
        pendingCli = cli;
        if (setup == null) {
            if (!booting) {
                booting = true;
                worker.execute(this::boot);
            }
        } else {
            // Antigravity is the voice default; open or resume its own PTY
            // without disturbing the CLI the user is reading.
            openCli(cli);
        }
    }

    private String voiceCli() {
            String configured = prefs == null ? "" : prefs.getString(VoiceEngine.KEY_CLI, "");
            if (configured != null && !configured.isEmpty()) {
                return configured;
            }
            Session active = Hub.session;
            if (active != null && active.isRunning()) {
                String cli = active.cli();
                if (cli != null && !cli.isEmpty()) {
                    return cli;
                }
            }
            if (activeCli != null && !activeCli.isEmpty()) {
                return activeCli;
            }
            return "opencode";
        }

    private void writeVoicePrompt(String text) {
            Session session = Hub.session;
            if (session == null) {
                pendingVoiceText = text;
                return;
            }
            // A plain shell is not an AI CLI: send the sentence as a command,
            // never as prose. This is what makes voice work in every terminal.
            if ("terminal".equals(session.cli())) {
                session.write(text.replace('\r', ' ').replace('\n', ' ') + "\r");
                Logger.i("voice: command written to plain terminal");
                return;
            }
            String prompt = "[SESLİ KOMUT] " + text
                    + " — Telefonu kontrol etmek için bash'te attc-ctl kullanabilirsin"
                    + " (Shizuku shell yetkisi). Örnek: attc-ctl \"am start -a"
                    + " android.intent.action.VIEW -d 'https://music.youtube.com/search?q=...'\"."
                    + " Ekran kapalıysa önce attc-ctl \"input keyevent 224\"."
                    + " Kurulu uygulamayı attc-ctl \"pm list packages | grep -i ...\" ile bul.";
            session.write(prompt + "\r");
            Logger.i("voice: command written to pty");
        }

    private void flushPendingVoice() {
        final String text = pendingVoiceText;
        pendingVoiceText = null;
        if (text == null) {
            return;
        }
        handler.postDelayed(() -> {
            if (Hub.session != null && cliRunning) {
                writeVoicePrompt(text);
            } else {
                Logger.i("voice: pending command dropped, no CLI");
            }
        }, 5000L);
    }

    // ------------------------------------------------------------- wake lock

    private volatile boolean keepAwakeWanted;

    private void setKeepAwake(boolean on) {
        keepAwakeWanted = on;
        applyLocks();
        status = on ? "Running - keep awake" : "Running";
        Hub.keepAwake = on;
        updateNotification();
    }

    /**
     * The CPU must stay awake for an always-listening microphone, but the wifi
     * lock stays tied to the explicit keep-awake switch: STT wakes wifi on its
     * own when a command needs the network.
     */
    private void applyLocks() {
        try {
            boolean cpu = keepAwakeWanted || voiceEnabled();
            if (wakeLock != null) {
                if (cpu && !wakeLock.isHeld()) {
                    wakeLock.acquire();
                } else if (!cpu && wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
            if (wifiLock != null) {
                if (keepAwakeWanted && !wifiLock.isHeld()) {
                    wifiLock.acquire();
                } else if (!keepAwakeWanted && wifiLock.isHeld()) {
                    wifiLock.release();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private boolean keepAwake() {
        return keepAwakeWanted;
    }

    private void releaseLocks() {
        keepAwakeWanted = false;
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Attc Code session", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the Ubuntu terminal and opencode running.");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, pendingFlags());

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        boolean awake = keepAwake();
        String text = status;
        if (capturing) {
            text = "Dinliyorum...";
        } else if (voiceEnabled()) {
            text = text + " • " + VoiceEngine.phrase(this) + " dinliyor";
        }
        b.setContentTitle("Attc Code")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setContentIntent(content);
        if (voiceEnabled()) {
            b.addAction(action("Sesi kapat", ACTION_VOICE_DISABLE, 3));
        } else if (VoiceEngine.hasPermission(this) && VoiceEngine.hasTemplates(this)) {
            b.addAction(action(VoiceEngine.phrase(this) + " aç", ACTION_VOICE_ENABLE, 4));
        }
        if (awake) {
            b.addAction(action("Disable keep awake", ACTION_DISABLE_AWAKE, 1));
        } else {
            b.addAction(action("Keep awake", ACTION_KEEP_AWAKE, 1));
        }
        b.addAction(action("Fully close app", ACTION_CLOSE, 2));
        return b.build();
    }

    private Notification.Action action(String title, String action, int code) {
        Intent i = new Intent(this, TerminalService.class).setAction(action);
        PendingIntent pi = PendingIntent.getService(this, code, i, pendingFlags());
        return new Notification.Action.Builder(
                android.R.drawable.ic_menu_manage, title, pi).build();
    }

    private static int pendingFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private void updateNotification() {
        if (!booting && Hub.session == null && !voiceEnabled()) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    // ------------------------------------------------------------------ close

    private void hardClose() {
        stopVoice();
        AudioBridge.setListener(null);
        AudioBridge.stop();
        VoiceBridge.stop();
        ZenProxy.get(this).stop();
        pendingCli = null;
        if (setup != null) {
            setup.cancel();
        }
        for (Session session : Hub.sessions) {
            session.setExitListener(null);
            session.close();
        }
        Hub.sessions.clear();
        Hub.setActive(null);
        releaseLocks();
        stopForeground(true);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancelAll();
        }
        stopSelf();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    static TerminalService current() {
        return current;
    }

    /** Runs one command inside the Ubuntu guest and returns its process. */
    Process startGuest(String... command) throws java.io.IOException {
        if (setup == null) {
            throw new java.io.IOException("guest not ready");
        }
        ProcessBuilder builder = new ProcessBuilder(buildArgv(command));
        for (String entry : buildEnv()) {
            int eq = entry.indexOf('=');
            if (eq > 0) {
                builder.environment().put(entry.substring(0, eq), entry.substring(eq + 1));
            }
        }
        return builder.start();
    }

    @Override
    public void onDestroy() {
        if (current == this) {
            current = null;
        }
        ZenProxy.get(this).stop();
        stopVoice();
        VoiceBridge.stop();
        handler.removeCallbacks(privacyTick);
        handler.removeCallbacks(cliExitTick);
        releaseLocks();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void emit(String text) {
        TerminalBuffer b = Hub.buffer;
        if (b != null) {
            byte[] bytes = (text + "\r\n").getBytes(StandardCharsets.UTF_8);
            b.feed(bytes, bytes.length);
        }
        Hub.setStatus(text);
        updateNotification();
    }
}
