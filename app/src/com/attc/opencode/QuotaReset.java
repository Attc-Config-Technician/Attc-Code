package com.attc.opencode;

import android.content.Context;
import android.content.Intent;

/**
 * Asks the terminal service for a quota reset: a new simulated IP, a new
 * device identity and a fresh opencode server address.
 *
 * The CLI binds a private server on an ephemeral 127.0.0.1 port for every
 * launch, so restarting it is enough to get a new address. A short cooldown
 * keeps a burst of quota errors from restarting the session several times.
 */
public final class QuotaReset {

    /** How often the session is reset automatically while it is running. */
    public static final long INTERVAL_MS = 30L * 60L * 1000L;

    private static final long COOLDOWN_MS = 90_000L;

    private static long lastTrigger;

    private QuotaReset() {
    }

    public static synchronized void trigger(Context c, String reason) {
        long now = System.currentTimeMillis();
        if (now - lastTrigger < COOLDOWN_MS) {
            Logger.i("quota-reset: skipped (cooldown)");
            return;
        }
        lastTrigger = now;
        Logger.i("quota-reset: " + reason);
        try {
            Intent i = new Intent(c, TerminalService.class)
                    .setAction(TerminalService.ACTION_QUOTA_RESET);
            c.startService(i);
        } catch (Exception e) {
            Logger.i("quota-reset: start failed " + e);
        }
    }
}
