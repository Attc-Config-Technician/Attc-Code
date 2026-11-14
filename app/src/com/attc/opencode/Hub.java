package com.attc.opencode;

import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide bridge between the service (owner of the PTY) and the view. */
public final class Hub {

    public interface Listener {
        void onOutput();
    }

    public static volatile TerminalBuffer buffer;
    public static volatile Session session;
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
        TerminalBuffer b = buffer;
        if (b != null) {
            b.feed(data, len);
        }
        synchronized (TAIL) {
            TAIL.append(new String(data, 0, len, java.nio.charset.StandardCharsets.UTF_8));
            if (TAIL.length() > 30000) {
                TAIL.delete(0, TAIL.length() - 30000);
            }
        }
        notifyListeners();
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

    public static void setStatus(String text) {
        status = text;
        notifyListeners();
    }
}
