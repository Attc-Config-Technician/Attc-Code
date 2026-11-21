package com.attc.opencode;

/** One PTY-backed shell process. */
public final class Session {

    private static final java.util.concurrent.atomic.AtomicInteger NEXT_ID =
            new java.util.concurrent.atomic.AtomicInteger(1);

    public interface ExitListener {
        void onExit(int code);
    }

    private final TerminalBuffer buffer;
    private final int id = NEXT_ID.getAndIncrement();
    private final String cli;
    private final String label;
    private volatile boolean cliRunning;
    private volatile long cliStartedAt;
    private volatile int fd = -1;
    private volatile int pid = -1;
    private volatile boolean closed;
    private volatile boolean quiet;
    private Thread reader;
    private ExitListener exitListener;

    public Session(TerminalBuffer buffer) {
        this(buffer, "", "Terminal");
    }

    public Session(TerminalBuffer buffer, String cli, String label) {
        this.buffer = buffer;
        this.cli = cli == null ? "" : cli;
        this.label = label == null || label.isEmpty() ? this.cli : label;
    }

    public TerminalBuffer buffer() {
        return buffer;
    }

    public int id() {
        return id;
    }

    public String cli() {
        return cli;
    }

    public String label() {
        return label;
    }

    public boolean cliRunning() {
        return cliRunning;
    }

    public long cliStartedAt() {
        return cliStartedAt;
    }

    public void markCliStarted() {
        cliRunning = true;
        cliStartedAt = System.currentTimeMillis();
    }

    public void markCliStopped() {
        cliRunning = false;
    }

    public boolean isRunning() {
        return !closed && fd >= 0;
    }

    public void setExitListener(ExitListener l) {
        exitListener = l;
    }

    public boolean start(String[] argv, String[] envp, String cwd, int rows, int cols) {
        int[] pair = Pty.nativeOpen(argv, envp, cwd, rows, cols);
        if (pair == null || pair.length != 2 || pair[0] < 0) {
            return false;
        }
        fd = pair[0];
        pid = pair[1];
        Logger.i("pty opened fd=" + fd + " pid=" + pid);
        reader = new Thread(this::readLoop, "pty-reader");
        reader.setDaemon(true);
        reader.start();
        return true;
    }

    private void readLoop() {
        int localFd = fd;
        byte[] buf = new byte[8192];
        while (!closed) {
            int n = Pty.nativeRead(localFd, buf);
            if (n <= 0) {
                // EIO means the child finished; EBADF means we closed it.
                break;
            }
            boolean attention = false;
            for (int i = 0; i < n; i++) {
                if (buf[i] == 7) {
                    attention = true;
                    break;
                }
            }
            if (!attention) {
                attention = hasNotification(buf, n);
            }
            Hub.push(this, buf, n);
            OverlayService.noteOutput();
            if (attention) {
                AudioBridge.chime(AudioBridge.CHIME_ATTENTION);
            }
        }

        int code = 0;
        int localPid = pid;
        if (localPid > 0) {
            for (int i = 0; i < 100; i++) {
                code = Pty.nativeWaitPid(localPid);
                if (code != -1) {
                    break;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (code < 0) {
                code = 0;
            }
            pid = -1;
        }
        int f = fd;
        fd = -1;
        if (f >= 0) {
            Pty.nativeClose(f);
        }
        closed = true;
        if (!quiet) {
            OverlayService.noteDone();
            Hub.onSessionExit(this, code);
            Logger.i("pty exited code=" + code + "\n--- output tail ---\n" + Hub.tail()
                    + "\n--- end tail ---");
        }
        ExitListener l = exitListener;
        if (l != null) {
            l.onExit(code);
        }
    }

    public void write(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void write(byte[] data) {
        int local = fd;
        if (local < 0 || closed) {
            return;
        }
        int off = 0;
        while (off < data.length) {
            int n = Pty.nativeWrite(local, data, off, data.length - off);
            if (n <= 0) {
                return;
            }
            off += n;
        }
    }

    public void resize(int rows, int cols) {
        int local = fd;
        if (local >= 0 && !closed) {
            Pty.nativeResize(local, rows, cols);
        }
    }

    private static boolean hasNotification(byte[] buf, int n) {
        for (int i = 0; i + 3 < n; i++) {
            if (buf[i] != 27 || buf[i + 1] != 93) {
                continue;
            }
            if (buf[i + 2] == 57 && buf[i + 3] == 59) {
                return true;
            }
            if (i + 6 < n && buf[i + 2] == 55 && buf[i + 3] == 55
                    && buf[i + 4] == 55 && buf[i + 5] == 59) {
                return true;
            }
        }
        return false;
    }

    /** Asks the child to die; the reader thread reaps it and closes the fd. */
    public void close() {
        quiet = true;
        closed = true;
        int local = pid;
        if (local > 0) {
            try {
                android.system.Os.kill(local, 9);
            } catch (Exception ignored) {
            }
        }
    }
}
