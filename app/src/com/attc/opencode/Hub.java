package com.attc.opencode;

import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide bridge between the service (owner of the PTY) and the view. */
public final class Hub {

    public interface Listener {
        void onOutput();
    }

    public static volatile TerminalBuffer buffer;
    public static volatile Session session;
    /** Every live PTY, in the order it was opened. */
    public static final CopyOnWriteArrayList<Session> sessions =
            new CopyOnWriteArrayList<>();
    /** CLI key of the visible session ("" for a plain shell). */
    public static volatile String activeCli = "";
    public static volatile String status = "Starting...";
    public static volatile boolean failed;
    public static volatile boolean keepAwake;
    /** True while a CLI or the plain terminal is the user's current choice. */
    public static volatile boolean cliActive;
    /** Set when a CLI (or the shell) ended and the chooser should return. */
    public static volatile boolean cliExited;

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final StringBuilder TAIL = new StringBuilder();

    private Hub() {
    }

    public static void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    public static void push(byte[] data, int len) {
        push(null, data, len);
    }

    /** Feeds one session's buffer; only the visible session wakes the view. */
    public static void push(Session source, byte[] data, int len) {
        TerminalBuffer b = source != null ? source.buffer() : buffer;
        if (b != null) {
            b.feed(data, len);
        }
        if (source != null && source != session) {
            return;
        }
        synchronized (TAIL) {
            TAIL.append(new String(data, 0, len, java.nio.charset.StandardCharsets.UTF_8));
            if (TAIL.length() > 30000) {
                TAIL.delete(0, TAIL.length() - 30000);
            }
        }
        notifyListeners();
    }

    public static synchronized void addSession(Session s) {
        if (s != null && !sessions.contains(s)) {
            sessions.add(s);
        }
    }

    public static synchronized void removeSession(Session s) {
        sessions.remove(s);
    }

    /** Makes a session visible and points the view at its own buffer. */
    public static synchronized void setActive(Session s) {
        session = s;
        if (s != null) {
            buffer = s.buffer();
            activeCli = s.cli();
        } else {
            activeCli = "";
        }
        notifyListeners();
    }

    public static Session findByCli(String cli) {
        if (cli == null) {
            return null;
        }
        for (Session s : sessions) {
            if (s.isRunning() && cli.equals(s.cli())) {
                return s;
            }
        }
        return null;
    }

    public static Session firstRunning() {
        for (Session s : sessions) {
            if (s.isRunning()) {
                return s;
            }
        }
        return null;
    }

    /** Last few KB of terminal output, for the diagnostic log. */
    public static String tail() {
        synchronized (TAIL) {
            return TAIL.toString();
        }
    }

    public static void clearTail() {
        synchronized (TAIL) {
            TAIL.setLength(0);
        }
    }

    public static void notifyListeners() {
        for (Listener l : listeners) {
            l.onOutput();
        }
    }

    public static void onSessionExit(int code) {
        status = "Session ended (exit " + code + ")";
        notifyListeners();
    }

    public static void onSessionExit(Session source, int code) {
        if (source == null || source == session) {
            onSessionExit(code);
        }
    }

    public static void setStatus(String text) {
        status = text;
        notifyListeners();
    }
}
