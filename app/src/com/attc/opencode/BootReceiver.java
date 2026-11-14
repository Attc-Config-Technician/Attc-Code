package com.attc.opencode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Optional autostart: only if the user enabled "Start terminal on boot". */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        boolean boot = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("boot", false);
        boolean voice = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(VoiceEngine.KEY_ENABLED, false);
        if (!boot && !voice) {
            return;
        }
        Intent service = new Intent(context, TerminalService.class)
                .setAction(TerminalService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }
}
