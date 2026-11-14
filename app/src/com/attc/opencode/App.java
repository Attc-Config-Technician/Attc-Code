package com.attc.opencode;

import android.app.Application;

/**
 * Last-resort crash logger. Writes the stack trace to the shared log file before
 * the process dies, which is the only reliable way to diagnose a crash on ROMs
 * that hide the logcat crash buffer (MIUI).
 */
public final class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(this);
        IpEcho.start(this);
        QuotaWatch.start(this);
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                Logger.i("UNCAUGHT " + thread.getName() + ": "
                        + android.util.Log.getStackTraceString(error));
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }
}
