package com.attc.opencode;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Bridge between an agent running inside the Ubuntu guest and Android itself.
 *
 * The guest cannot inject input or start activities with shell privileges. This
 * watcher picks up small request files written by {@code attc-ctl} inside
 * /root/attc/ctl, runs the command through Shizuku (uid 2000, the same powers
 * as adb shell) and writes the output back. It is deliberately file based: no
 * sockets, no ports, and it works while the screen is off.
 */
public final class VoiceBridge {

    private static final String HELPER = "#!/bin/sh\n"
            + "# attc-ctl: run a shell command on Android through Attc Code (Shizuku).\n"
            + "# Example: attc-ctl \"am start -a android.intent.action.VIEW -d 'https://music.youtube.com'\n"
            + "D=/root/attc/ctl\n"
            + "mkdir -p \"$D\" 2>/dev/null\n"
            + "if [ $# -eq 0 ]; then\n"
            + "  echo \"usage: attc-ctl <android shell command>\"\n"
            + "  echo \"examples:\"\n"
            + "  echo \"  attc-ctl \\\"open https://www.youtube.com\\\"\"\n"
            + "  echo \"  attc-ctl \\\"am start -a android.intent.action.VIEW -d 'https://music.youtube.com/search?q=sezen aksu'\\\"\"\n"
            + "  echo \"  attc-ctl \\\"input keyevent 224\\\"\"\n"
            + "  echo \"  attc-ctl \\\"cmd media_session dispatch play\\\"\"\n"
            + "  exit 2\n"
            + "fi\n"
            + "ID=\"$$.$(date +%s)\"\n"
            + "printf '%s' \"$*\" > \"$D/req.$ID.tmp\" && mv \"$D/req.$ID.tmp\" \"$D/req.$ID\"\n"
            + ": > \"$D/go.$ID\"\n"
            + "i=0\n"
            + "while [ ! -f \"$D/out.$ID\" ]; do\n"
            + "  i=$((i+1))\n"
            + "  if [ $i -ge 600 ]; then\n"
            + "    echo \"attc-ctl: timeout waiting for the app\"\n"
            + "    rm -f \"$D/req.$ID\" \"$D/go.$ID\"\n"
            + "    exit 3\n"
            + "  fi\n"
            + "  sleep 0.1\n"
            + "done\n"
            + "cat \"$D/out.$ID\"\n"
            + "rm -f \"$D/req.$ID\" \"$D/go.$ID\" \"$D/out.$ID\"\n";

    /**
     * CLIs look for xdg-open or termux-open-url to open a link. Both point at
     * the same bridge, so the URL opens in the phone's browser without
     * Shizuku and without a desktop browser.
     */
    private static final String OPEN_HELPER = "#!/bin/sh\n"
            + "# Open a URL in the phone's browser through Attc Code.\n"
            + "URL=\"$1\"\n"
            + "if [ -z \"$URL\" ]; then\n"
            + "  echo \"usage: $0 <url>\" >&2\n"
            + "  exit 2\n"
            + "fi\n"
            + "exec /root/.local/bin/attc-ctl \"open $URL\"\n";

    /**
     * Antigravity reads AGENTS.md from the working directory. Tell it that
     * this terminal can open the phone's browser; without this note the model
     * assumes it has no browser and only prints a link.
     */
    private static final String AGENTS_HELPER = "# Attc Code device integration\n\n"
            + "This terminal runs inside Attc Code on an Android phone.\n\n"
            + "- To open a URL in the phone's browser, run `xdg-open <url>` "
            + "(or `termux-open-url <url>`). Do not claim that you cannot open "
            + "a browser; the command is available.\n"
            + "- `attc-ctl \"open <url>\"` is the same bridge if xdg-open is "
            + "not on PATH.\n\n"
            + "## Android control tools\n\n"
            + "This session exposes MCP tools named `android_*`. When the user asks "
            + "you to open an app, tap, swipe, type or inspect the phone, call those "
            + "tools instead of describing steps. A live cursor overlay shows every "
            + "action on the phone screen.\n";

    /**
     * Python shim installed as arecord, pw-record, parec, paplay, pw-cat and
     * aplay inside the guest. It streams raw s16le PCM to AudioBridge, so a
     * CLI that wants a microphone or a speaker reaches the phone hardware
     * without a PulseAudio server or an ALSA device.
     */
    private static final String AUDIO_HELPER = ""
            + "#!/usr/bin/env python3\n"
            + "# Attc Code audio shim: carries a CLI's microphone and speaker to the phone.\n"
            + "# Installed as arecord, pw-record, parec, paplay, pw-cat and aplay.\n"
            + "import os\n"
            + "import socket\n"
            + "import struct\n"
            + "import sys\n"
            + "\n"
            + "HOST = \"127.0.0.1\"\n"
            + "PLAY_PORT = 4714\n"
            + "REC_PORT = 4715\n"
            + "LOG = \"/root/attc/audio.log\"\n"
            + "NAME = os.path.basename(sys.argv[0])\n"
            + "RECORDERS = (\"arecord\", \"pw-record\", \"parec\", \"rec\", \"record\")\n"
            + "\n"
            + "\n"
            + "def log(message):\n"
            + "    try:\n"
            + "        if os.path.exists(LOG) and os.path.getsize(LOG) > 262144:\n"
            + "            os.replace(LOG, LOG + \".old\")\n"
            + "        with open(LOG, \"a\") as handle:\n"
            + "            handle.write(message + \"\\n\")\n"
            + "    except Exception:\n"
            + "        pass\n"
            + "\n"
            + "\n"
            + "def parse_args(argv):\n"
            + "    rate, channels, kind = 16000, 1, \"raw\"\n"
            + "    path = None\n"
            + "    skip_value = {\n"
            + "        \"-D\", \"--device\", \"--target\", \"--latency-msec\", \"--buffer-size\",\n"
            + "        \"--period-size\", \"--volume\", \"--media-role\", \"--name\", \"--client-name\",\n"
            + "        \"--server\", \"--source\", \"--monitor\", \"--property\", \"-p\", \"-d\",\n"
            + "    }\n"
            + "    i = 0\n"
            + "    while i < len(argv):\n"
            + "        arg = argv[i]\n"
            + "        if arg in (\"-r\", \"--rate\") and i + 1 < len(argv):\n"
            + "            rate = argv[i + 1]\n"
            + "            i += 2\n"
            + "        elif arg.startswith(\"--rate=\"):\n"
            + "            rate = arg.split(\"=\", 1)[1]\n"
            + "            i += 1\n"
            + "        elif arg in (\"-c\", \"--channels\") and i + 1 < len(argv):\n"
            + "            channels = argv[i + 1]\n"
            + "            i += 2\n"
            + "        elif arg.startswith(\"--channels=\"):\n"
            + "            channels = arg.split(\"=\", 1)[1]\n"
            + "            i += 1\n"
            + "        elif arg in (\"-f\", \"--format\") and i + 1 < len(argv):\n"
            + "            kind = argv[i + 1]\n"
            + "            i += 2\n"
            + "        elif arg.startswith(\"--format=\"):\n"
            + "            kind = arg.split(\"=\", 1)[1]\n"
            + "            i += 1\n"
            + "        elif arg in (\"-t\", \"--file-type\") and i + 1 < len(argv):\n"
            + "            kind = argv[i + 1]\n"
            + "            i += 2\n"
            + "        elif arg.startswith(\"--file-type=\"):\n"
            + "            kind = arg.split(\"=\", 1)[1]\n"
            + "            i += 1\n"
            + "        elif arg in skip_value:\n"
            + "            i += 2\n"
            + "        elif arg.startswith(\"-\") and arg != \"-\":\n"
            + "            i += 1\n"
            + "        else:\n"
            + "            path = arg\n"
            + "            i += 1\n"
            + "    return number(rate, 16000), clamp_channels(channels), kind.lower(), path\n"
            + "\n"
            + "\n"
            + "def number(value, fallback):\n"
            + "    try:\n"
            + "        text = str(value).split(\",\")[0].split(\":\")[0]\n"
            + "        parsed = int(text)\n"
            + "        return parsed if 4000 <= parsed <= 96000 else fallback\n"
            + "    except Exception:\n"
            + "        return fallback\n"
            + "\n"
            + "\n"
            + "def clamp_channels(value):\n"
            + "    try:\n"
            + "        parsed = int(str(value).split(\",\")[0].split(\":\")[0])\n"
            + "        return 2 if parsed >= 2 else 1\n"
            + "    except Exception:\n"
            + "        return 1\n"
            + "\n"
            + "\n"
            + "def read_exact(stream, size):\n"
            + "    data = b\"\"\n"
            + "    while len(data) < size:\n"
            + "        chunk = stream.read(size - len(data))\n"
            + "        if not chunk:\n"
            + "            break\n"
            + "        data += chunk\n"
            + "    return data\n"
            + "\n"
            + "\n"
            + "def wav_header(stream):\n"
            + "    head = read_exact(stream, 12)\n"
            + "    if len(head) < 12 or head[0:4] != b\"RIFF\" or head[8:12] != b\"WAVE\":\n"
            + "        return 16000, 1, False\n"
            + "    rate, channels = 16000, 1\n"
            + "    while True:\n"
            + "        chunk = read_exact(stream, 8)\n"
            + "        if len(chunk) < 8:\n"
            + "            return rate, channels, False\n"
            + "        cid = chunk[0:4]\n"
            + "        size = struct.unpack(\"<I\", chunk[4:8])[0]\n"
            + "        if cid == b\"fmt \":\n"
            + "            body = read_exact(stream, size)\n"
            + "            if len(body) >= 8:\n"
            + "                channels = struct.unpack(\"<H\", body[2:4])[0]\n"
            + "                rate = struct.unpack(\"<I\", body[4:8])[0]\n"
            + "        elif cid == b\"data\":\n"
            + "            return number(rate, 16000), clamp_channels(channels), True\n"
            + "        else:\n"
            + "            remaining = size\n"
            + "            while remaining > 0:\n"
            + "                block = stream.read(min(remaining, 65536))\n"
            + "                if not block:\n"
            + "                    return rate, channels, False\n"
            + "                remaining -= len(block)\n"
            + "        if size % 2:\n"
            + "            stream.read(1)\n"
            + "\n"
            + "\n"
            + "def connect(port):\n"
            + "    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)\n"
            + "    sock.settimeout(3.0)\n"
            + "    sock.connect((HOST, port))\n"
            + "    sock.settimeout(None)\n"
            + "    return sock\n"
            + "\n"
            + "\n"
            + "def write_all(sock, data):\n"
            + "    view = memoryview(data)\n"
            + "    while view:\n"
            + "        sent = sock.send(view)\n"
            + "        if sent <= 0:\n"
            + "            raise IOError(\"socket closed\")\n"
            + "        view = view[sent:]\n"
            + "\n"
            + "\n"
            + "def record(rate, channels, kind, path):\n"
            + "    sock = connect(REC_PORT)\n"
            + "    write_all(sock, (\"rec %d %d s16le\\n\" % (rate, channels)).encode())\n"
            + "    wav = kind == \"wav\" or (path or \"\").lower().endswith(\".wav\")\n"
            + "    if path and path != \"-\":\n"
            + "        target = open(path, \"wb\")\n"
            + "    else:\n"
            + "        target = sys.stdout.buffer\n"
            + "    header_at = 0\n"
            + "    if wav:\n"
            + "        if path and path != \"-\":\n"
            + "            header_at = target.tell()\n"
            + "        target.write(b\"\\0\" * 44)\n"
            + "    total = 0\n"
            + "    try:\n"
            + "        while True:\n"
            + "            block = sock.recv(8192)\n"
            + "            if not block:\n"
            + "                break\n"
            + "            target.write(block)\n"
            + "            total += len(block)\n"
            + "    finally:\n"
            + "        if wav:\n"
            + "            try:\n"
            + "                target.flush()\n"
            + "            except Exception:\n"
            + "                pass\n"
            + "            try:\n"
            + "                if hasattr(target, \"seek\"):\n"
            + "                    target.seek(header_at)\n"
            + "                    target.write(make_wav_header(rate, channels, total))\n"
            + "                    target.seek(0, 2)\n"
            + "            except Exception:\n"
            + "                pass\n"
            + "        try:\n"
            + "            target.flush()\n"
            + "        except Exception:\n"
            + "            pass\n"
            + "        if path and path != \"-\":\n"
            + "            target.close()\n"
            + "        sock.close()\n"
            + "    log(\"rec %s rate=%d ch=%d bytes=%d path=%s\" % (NAME, rate, channels, total, path or \"-\"))\n"
            + "\n"
            + "\n"
            + "def make_wav_header(rate, channels, total):\n"
            + "    byte_rate = rate * channels * 2\n"
            + "    return (\n"
            + "        b\"RIFF\" + struct.pack(\"<I\", 36 + total) + b\"WAVEfmt \" +\n"
            + "        struct.pack(\"<IHHIIHH\", 16, 1, channels, rate, byte_rate,\n"
            + "                    channels * 2, 16) +\n"
            + "        b\"data\" + struct.pack(\"<I\", total)\n"
            + "    )\n"
            + "\n"
            + "\n"
            + "def play(rate, channels, kind, path):\n"
            + "    if path and path != \"-\":\n"
            + "        source = open(path, \"rb\")\n"
            + "    else:\n"
            + "        source = sys.stdin.buffer\n"
            + "    if kind == \"wav\" or (path or \"\").lower().endswith(\".wav\"):\n"
            + "        parsed_rate, parsed_channels, ok = wav_header(source)\n"
            + "        if ok:\n"
            + "            rate, channels = parsed_rate, parsed_channels\n"
            + "    sock = connect(PLAY_PORT)\n"
            + "    write_all(sock, (\"play %d %d s16le\\n\" % (rate, channels)).encode())\n"
            + "    total = 0\n"
            + "    while True:\n"
            + "        block = source.read(8192)\n"
            + "        if not block:\n"
            + "            break\n"
            + "        write_all(sock, block)\n"
            + "        total += len(block)\n"
            + "    try:\n"
            + "        sock.shutdown(socket.SHUT_WR)\n"
            + "    except Exception:\n"
            + "        pass\n"
            + "    sock.close()\n"
            + "    if path and path != \"-\":\n"
            + "        source.close()\n"
            + "    log(\"play %s rate=%d ch=%d bytes=%d path=%s\" % (NAME, rate, channels, total, path or \"-\"))\n"
            + "\n"
            + "\n"
            + "def main():\n"
            + "    rate, channels, kind, path = parse_args(sys.argv[1:])\n"
            + "    # pw-cat is both a player and a recorder; --record selects capture.\n"
            + "    record_mode = NAME in RECORDERS or (\n"
            + "        NAME == \"pw-cat\" and \"--record\" in sys.argv[1:])\n"
            + "    try:\n"
            + "        if record_mode:\n"
            + "            record(rate, channels, kind, path)\n"
            + "        else:\n"
            + "            play(rate, channels, kind, path)\n"
            + "    except Exception as error:\n"
            + "        log(\"error %s %s: %r\" % (NAME, \" \".join(sys.argv[1:]), error))\n"
            + "        return 0\n"
            + "    return 0\n"
            + "\n"
            + "\n"
            + "if __name__ == \"__main__\":\n"
            + "    sys.exit(main())\n"
            ;
    /**
     * One-shot guest bootstrap: installs the interpreter and the audio tools
     * the shims stand in for. It is safe to run on every launch because the
     * marker short-circuits it, and it never blocks the CLI from starting.
     */
    private static final String AUDIO_BOOTSTRAP = ""
            + "#!/bin/sh\n"
            + "# Attc Code: install the tools the audio shims depend on, once.\n"
            + "MARK=/root/attc/.audio-bootstrap-done\n"
            + "LOG=/root/attc/audio-bootstrap.log\n"
            + "[ -f \"$MARK\" ] && exit 0\n"
            + "missing=\"\"\n"
            + "command -v python3 >/dev/null 2>&1 || missing=\"$missing python3-minimal\"\n"
            + "[ -x /usr/bin/aplay ] || missing=\"$missing alsa-utils\"\n"
            + "[ -x /usr/bin/paplay ] || missing=\"$missing pulseaudio-utils\"\n"
            + "if [ -z \"$missing\" ]; then touch \"$MARK\"; exit 0; fi\n"
            + "echo \"installing:$missing\" >>\"$LOG\"\n"
            + "export DEBIAN_FRONTEND=noninteractive\n"
            + "apt-get update -qq >>\"$LOG\" 2>&1\n"
            + "apt-get install -y -qq $missing >>\"$LOG\" 2>&1\n"
            + "if command -v python3 >/dev/null 2>&1; then\n"
            + "    touch \"$MARK\"\n"
            + "    echo done >>\"$LOG\"\n"
            + "else\n"
            + "    echo failed >>\"$LOG\"\n"
            + "fi\n";

    private static volatile boolean started;
    private static Thread thread;
    private static Context appContext;

    private VoiceBridge() {
    }

    /** Writes the guest helper script. Safe to call on every boot. */
    static void install(Context context) {
        try {
            Setup setup = new Setup(context);
            File root = setup.rootDir();
            if (!root.exists()) {
                return;
            }
            File home = new File(root, "root");
            File mcp = new File(Setup.STORAGE_DIR, "android-mcp.py");
            copyAsset(context, "android-mcp.py", mcp);
            mergeMcpConfig(new File(home, ".config/opencode/opencode.json"));
            File bin = new File(home, ".local/bin");
            if (!bin.exists() && !bin.mkdirs()) {
                return;
            }
            File script = new File(bin, "attc-ctl");
            write(script, HELPER);
            script.setExecutable(true, false);
            File xdgOpen = new File(bin, "xdg-open");
            write(xdgOpen, OPEN_HELPER);
            xdgOpen.setExecutable(true, false);
            File termuxOpenUrl = new File(bin, "termux-open-url");
            write(termuxOpenUrl, OPEN_HELPER);
            termuxOpenUrl.setExecutable(true, false);
            File termuxOpen = new File(bin, "termux-open");
            write(termuxOpen, OPEN_HELPER);
            termuxOpen.setExecutable(true, false);
            File agents = new File(home, "AGENTS.md");
            if (!agents.exists()) {
                write(agents, AGENTS_HELPER);
            } else {
                ensureAgentsMcp(agents);
            }
            // Audio shims. .local/bin comes first on the PATH the app exports,
            // and /usr/local/bin shadows the real pulseaudio-utils binaries
            // when another CLI builds its own PATH.
            String[] audio = {"arecord", "pw-record", "parec",
                    "paplay", "pw-cat", "aplay"};
            for (String name : audio) {
                File helper = new File(bin, name);
                write(helper, AUDIO_HELPER);
                helper.setExecutable(true, false);
                File global = new File(new File(root, "usr/local/bin"), name);
                if (global.isDirectory()) {
                    continue;
                }
                write(global, AUDIO_HELPER);
                global.setExecutable(true, false);
            }
            File bootstrap = new File(Setup.STORAGE_DIR, "audio-bootstrap.sh");
            write(bootstrap, AUDIO_BOOTSTRAP);
            Logger.i("voice: attc-ctl/xdg-open/audio installed at " + bin);
        } catch (Throwable t) {
            Logger.i("voice: attc-ctl install failed " + t);
        }
    }

    private static void ensureAgentsMcp(File agents) {
        try {
            String text = readText(agents);
            if (text.contains("Android control tools")) {
                return;
            }
            String hint = "\n## Android control tools\n\n"
                    + "This session exposes MCP tools named `android_*`. When the user asks "
                    + "you to open an app, tap, swipe, type or inspect the phone, call those "
                    + "tools instead of describing steps. A live cursor overlay shows every "
                    + "action on the phone screen.\n";
            writeText(agents, text + hint);
            Logger.i("voice: AGENTS.md updated with MCP hint");
        } catch (Throwable t) {
            Logger.i("voice: AGENTS.md update failed " + t);
        }
    }

    private static void copyAsset(Context context, String name, File target) throws Exception {
        java.io.InputStream in = context.getAssets().open(name);
        try {
            FileOutputStream out = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static void mergeMcpConfig(File config) {
        try {
            if (!config.exists()) {
                File parent = config.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                org.json.JSONObject root = new org.json.JSONObject();
                root.put("$schema", "https://opencode.ai/config.json");
                root.put("mcp", mcpSection());
                writeText(config, root.toString(2));
                Logger.i("mcp: config created " + config);
                return;
            }
            String text = readText(config);
            org.json.JSONObject root = new org.json.JSONObject(text);
            org.json.JSONObject mcp = root.optJSONObject("mcp");
            if (mcp == null) {
                mcp = new org.json.JSONObject();
                root.put("mcp", mcp);
            }
            mcp.remove("android");
            org.json.JSONObject servers = mcp.optJSONObject("servers");
            if (servers == null) {
                servers = new org.json.JSONObject();
                mcp.put("servers", servers);
            }
            servers.put("android", mcpAndroid());
            writeText(config, root.toString(2));
            Logger.i("mcp: config merged " + config);
        } catch (Throwable t) {
            Logger.i("mcp: config merge failed " + t);
        }
    }

    private static org.json.JSONObject mcpSection() throws Exception {
        org.json.JSONObject servers = new org.json.JSONObject();
        servers.put("android", mcpAndroid());
        org.json.JSONObject mcp = new org.json.JSONObject();
        mcp.put("servers", servers);
        return mcp;
    }

    private static org.json.JSONObject mcpAndroid() throws Exception {
        org.json.JSONObject android = new org.json.JSONObject();
        android.put("type", "local");
        android.put("disabled", false);
        org.json.JSONArray command = new org.json.JSONArray();
        command.put("python3");
        command.put("/root/attc/android-mcp.py");
        android.put("command", command);
        return android;
    }

    private static String readText(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read <= 0) {
                    break;
                }
                offset += read;
            }
            return new String(data, 0, offset, StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static void writeText(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private static void write(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    static synchronized void start(Context context) {
        if (started) {
            return;
        }
        appContext = context.getApplicationContext();
        started = true;
        final File dir = new File(Setup.STORAGE_DIR, "ctl");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        thread = new Thread(() -> {
            Logger.i("voice: bridge watching " + dir);
            while (started) {
                try {
                    File[] files = dir.listFiles();
                    if (files != null && files.length > 0) {
                        Arrays.sort(files, new Comparator<File>() {
                            @Override
                            public int compare(File a, File b) {
                                return a.getName().compareTo(b.getName());
                            }
                        });
                        for (File go : files) {
                            if (!started) {
                                break;
                            }
                            String name = go.getName();
                            if (!name.startsWith("go.") || !go.isFile()) {
                                continue;
                            }
                            String id = name.substring(3);
                            handle(dir, id);
                        }
                    }
                    Thread.sleep(180L);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable t) {
                    Logger.i("voice: bridge loop " + t);
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
            Logger.i("voice: bridge stopped");
        }, "attc-voice-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    static synchronized void stop() {
        started = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
        thread = null;
    }

    private static void handle(File dir, String id) {
        File req = new File(dir, "req." + id);
        File go = new File(dir, "go." + id);
        String command = null;
        try {
            command = read(req);
        } catch (Throwable t) {
            Logger.i("voice: ctl read failed " + t);
        }
        if (command == null || command.trim().isEmpty()) {
            writeResult(dir, id, "attc-ctl: empty or unreadable request\n");
            req.delete();
            go.delete();
            return;
        }
        command = command.trim();
        Logger.i("voice: ctl$ " + truncate(command, 400));
        String result;
        if (command.startsWith("open ")) {
            // xdg-open and termux-open-url use this path. It needs no
            // Shizuku: the app itself can start the phone's browser.
            result = openUrl(command.substring(5).trim());
        } else if (!ShizukuHelper.granted()) {
            result = "attc-ctl: Shizuku permission is not granted\n";
            Logger.i("voice: ctl blocked, no Shizuku");
        } else {
            try {
                result = ShizukuHelper.run(timeoutWrap(command));
                if (result.length() > 200000) {
                    result = result.substring(0, 200000) + "\n[output truncated]\n";
                }
            } catch (Throwable t) {
                result = "attc-ctl: " + t + "\n";
            }
        }
        Logger.i("voice: ctl result " + truncate(result, 400));
        writeResult(dir, id, result);
        req.delete();
        go.delete();
    }

    /** Opens a web/mail/phone link in the phone's default app. */
    private static String openUrl(String url) {
        if (url.isEmpty()) {
            return "attc-ctl: empty URL\n";
        }
        String lower = url.toLowerCase(Locale.US);
        if (!(lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("mailto:") || lower.startsWith("tel:"))) {
            return "attc-ctl: unsupported URL scheme\n";
        }
        Context context = appContext;
        if (context == null) {
            return "attc-ctl: app is not ready\n";
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(url.replace(" ", "%20")));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Logger.i("voice: opened " + truncate(url, 300));
            return "opened " + url + "\n";
        } catch (Throwable t) {
            Logger.i("voice: open failed " + t);
            return "attc-ctl: could not open " + url + ": " + t + "\n";
        }
    }

    private static String read(File file) throws Exception {
        if (!file.exists()) {
            return null;
        }
        byte[] data = new byte[(int) Math.min(file.length(), 64000)];
        FileInputStream in = new FileInputStream(file);
        int read = 0;
        try {
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
        } finally {
            in.close();
        }
        return new String(data, 0, read, StandardCharsets.UTF_8);
    }

    private static void writeResult(File dir, String id, String result) {
        File tmp = new File(dir, "out." + id + ".tmp");
        File out = new File(dir, "out." + id);
        try {
            write(tmp, result == null ? "" : result);
            if (!tmp.renameTo(out)) {
                write(out, result == null ? "" : result);
                tmp.delete();
            }
        } catch (Throwable t) {
            Logger.i("voice: ctl write failed " + t);
            try {
                write(out, "attc-ctl: result write failed\n");
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Toybox ships timeout on modern Android. Wrapping the command keeps one
     * stuck UI dump from blocking the whole bridge.
     */
    private static String timeoutWrap(String command) {
        if (!new File("/system/bin/timeout").exists()) {
            return command;
        }
        return "timeout 40 sh -c '" + command.replace("'", "'\\''") + "'";
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
