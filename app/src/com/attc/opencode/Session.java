package com.attc.opencode;

/** One PTY-backed shell process. */
public final class Session {

    public interface ExitListener {
        void onExit(int code);
    }

    private final TerminalBuffer buffer;
    private volatile int fd = -1;
    private volatile int pid = -1;
    private volatile boolean closed;
    private volatile boolean quiet;
    private Thread reader;
    private ExitListener exitListener;

    public Session(TerminalBuffer buffer) {
        this.buffer = buffer;
    }

    public TerminalBuffer buffer() {
        return buffer;
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
            Hub.push(buf, n);
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
            Hub.onSessionExit(code);
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
