package com.attc.opencode;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

/**
 * Short attention tones for the opencode TUI.
 *
 * The guest binary plays its own mp3 pack through OpenTUI's native audio
 * engine, which has no path to Android audio inside proot. The terminal model
 * therefore reports real BELs and OSC 7501 state changes here, and this class
 * turns them into phone-side tones. Volume follows the notification stream.
 */
public final class Sound {

    public static final int BELL = 1;
    public static final int DONE = 2;
    public static final int ATTENTION = 3;

    public static final String KEY = "attention_sound";

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static ToneGenerator tone;
    private static long lastBell;
    private static long lastDone;
    private static long lastAttention;

    private Sound() {
    }

    public static void play(Context context, int kind) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY, true)) {
            return;
        }
        long now = System.currentTimeMillis();
        long last;
        long gap;
        if (kind == BELL) {
            last = lastBell;
            gap = 700;
        } else if (kind == DONE) {
            last = lastDone;
            gap = 900;
        } else {
            last = lastAttention;
            gap = 900;
        }
        if (now - last < gap) {
            return;
        }
        if (kind == BELL) {
            lastBell = now;
        } else if (kind == DONE) {
            lastDone = now;
        } else {
            lastAttention = now;
        }
        final int toneType;
        final int duration;
        if (kind == DONE) {
            toneType = ToneGenerator.TONE_PROP_ACK;
            duration = 250;
        } else if (kind == ATTENTION) {
            toneType = ToneGenerator.TONE_PROP_PROMPT;
            duration = 400;
        } else {
            toneType = ToneGenerator.TONE_PROP_BEEP;
            duration = 180;
        }
        Logger.i("sound: kind=" + kind);
        handler.post(() -> {
            ToneGenerator t = generator();
            if (t == null) {
                Logger.i("sound: no tone generator");
                return;
            }
            try {
                t.startTone(toneType, duration);
            } catch (RuntimeException e) {
                Logger.i("sound: failed " + e);
            }
        });
    }

    private static ToneGenerator generator() {
        if (tone == null) {
            try {
                tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90);
            } catch (RuntimeException e) {
                tone = null;
            }
        }
        return tone;
    }
}
