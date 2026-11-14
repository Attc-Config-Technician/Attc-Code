package com.attc.opencode;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline "Hey Attc" wake-word engine.
 *
 * A general speech recogniser is a poor fit for a made-up name: "attc" is not
 * in any dictionary. Instead the user records the phrase three times and the
 * engine compares live audio with those personal templates using MFCC features
 * and dynamic time warping. It is speaker dependent, fully offline, and cheap
 * enough to run while the screen is off.
 */
public final class VoiceEngine {

    public static final String KEY_ENABLED = "voice_enabled";
    public static final String KEY_CLI = "voice_cli";
    public static final String KEY_LAST_WAKE = "voice_last_wake";
    /** The phrase the user says; templates must be recorded for this text. */
    public static final String KEY_PHRASE = "voice_phrase";
    public static final String DEFAULT_PHRASE = "Hey Attc";
    /** Presets offered in settings; the wake engine is text independent. */
    public static final String[] PHRASE_PRESETS = {
            "Hey Attc", "Hey Attc Code", "Merhaba Attc", "Selam Attc"};

    private static final String TEMPLATE_FILE = "voice_templates.json";

    private static final int RATE = 16000;
    private static final int FRAME = 400;        // 25 ms analysis window
    private static final int HOP = 160;          // 10 ms hop
    private static final int FFT_SIZE = 512;
    private static final int MEL_BANDS = 26;
    private static final int MFCC = 13;          // c0..c12, c0 z-scored too

    private static final int READ_SAMPLES = 320; // 20 ms per AudioRecord read
    private static final int MIN_FRAMES = 32;    // 0.32 s
    private static final int MAX_FRAMES = 260;   // 2.6 s
    private static final int END_SILENCE = 65;   // 650 ms
    private static final int COOLDOWN_MS = 2500;

    // Energies below are RMS values of samples normalised to [-1, 1]: a quiet
    // room sits near 0.0005-0.002, normal speech peaks around 0.03-0.25.
    private static final double NOISE_INIT = 0.0015;
    private static final double NOISE_MIN = 0.0002;
    private static final double NOISE_MAX = 0.15;
    private static final double VOICED_MIN = 0.004;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface WakeListener {
        void onWake();

        void onError(String message);
    }

    public interface EnrollListener {
        /** Called with 1..3 as each good sample is stored. */
        void onProgress(int done);

        /** Called once when recording finishes or fails. */
        void onDone(boolean ok, String message);
    }

    /** One scored frame of the wake-word stream. */
    private static final class Frame {
        final float[] mfcc;
        final double rms;

        Frame(float[] mfcc, double rms) {
            this.mfcc = mfcc;
            this.rms = rms;
        }
    }

    private final Context app;
    private final WakeListener listener;
    private final Handler handler = MAIN;
    private volatile boolean running;
    private volatile AudioRecord record;
    private Thread thread;

    public VoiceEngine(Context context, WakeListener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
    }

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Returns the configured wake phrase, falling back to the default. */
    public static String phrase(Context context) {
        String value = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(KEY_PHRASE, DEFAULT_PHRASE);
        return value == null || value.trim().isEmpty() ? DEFAULT_PHRASE : value.trim();
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (running) {
            return;
        }
        if (!hasPermission(app)) {
            Logger.i("voice: no microphone permission");
            listener.onError("microphone permission missing");
            return;
        }
        Loaded loaded = load(app);
        if (loaded == null || loaded.templates.isEmpty()) {
            Logger.i("voice: no wake templates, enrol first");
            listener.onError("wake word not enrolled");
            return;
        }
        running = true;
        thread = new Thread(() -> loop(loaded), "attc-voice");
        thread.setDaemon(true);
        thread.start();
        Logger.i("voice: listening (" + loaded.templates.size()
                + " templates, threshold " + loaded.threshold + ")");
    }

    public void stop() {
        running = false;
        AudioRecord r = record;
        if (r != null) {
            try {
                r.stop();
            } catch (Throwable ignored) {
            }
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
        Logger.i("voice: stopped");
    }

    private void loop(Loaded loaded) {
        AudioRecord r = open();
        if (r == null) {
            running = false;
            postError("could not open microphone");
            return;
        }
        record = r;
        double[] samples = new double[FRAME * 3];
        int count = 0;
        FrameBuilder builder = new FrameBuilder();
        Segmenter segmenter = new Segmenter();
        long cooldownUntil = 0;
        try {
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("record state " + r.getRecordingState());
            }
            short[] pcm = new short[READ_SAMPLES];
            while (running) {
                int n = r.read(pcm, 0, pcm.length);
                if (n <= 0) {
                    if (!running) {
                        break;
                    }
                    if (n == AudioRecord.ERROR_INVALID_OPERATION
                            || n == AudioRecord.ERROR_BAD_VALUE
                            || n == AudioRecord.ERROR_DEAD_OBJECT) {
                        Logger.i("voice: read error " + n);
                        break;
                    }
                    continue;
                }
                for (int i = 0; i < n; i++) {
                    samples[count++] = pcm[i] / 32768.0;
                    if (count >= FRAME) {
                        Frame f = builder.frame(samples);
                        count -= HOP;
                        System.arraycopy(samples, HOP, samples, 0, count);
                        if (System.currentTimeMillis() < cooldownUntil) {
                            segmenter.reset();
                            continue;
                        }
                        List<Frame> segment = segmenter.feed(f);
                        if (segment != null) {
                            float[][] mfcc = normalized(trim(segment));
                            if (mfcc != null && mfcc.length >= MIN_FRAMES) {
                                float best = bestDistance(mfcc, loaded.templates);
                                Logger.i("voice: segment " + mfcc.length
                                        + " frames, distance " + best);
                                if (best < loaded.threshold) {
                                    cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
                                    Logger.i("voice: WAKE");
                                    app.getSharedPreferences("settings", Context.MODE_PRIVATE)
                                            .edit().putLong(KEY_LAST_WAKE,
                                                    System.currentTimeMillis()).apply();
                                    handler.post(listener::onWake);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Logger.i("voice: capture failed " + t);
            if (running) {
                postError("microphone capture failed: " + t.getMessage());
            }
        } finally {
            record = null;
            try {
                if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    r.stop();
                }
            } catch (Throwable ignored) {
            }
            try {
                r.release();
            } catch (Throwable ignored) {
            }
            running = false;
            Logger.i("voice: capture thread ended");
        }
    }

    private void postError(final String message) {
        handler.post(() -> listener.onError(message));
    }

    private static AudioRecord open() {
        int min = AudioRecord.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            min = RATE / 2;
        }
        try {
            AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, READ_SAMPLES * 8));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return null;
            }
            return r;
        } catch (Throwable t) {
            Logger.i("voice: AudioRecord failed " + t);
            return null;
        }
    }

    // ------------------------------------------------------------- enrolment

    /**
     * Records three utterances of the wake word and stores them as templates.
     * The caller must stop the live engine first: Android gives the microphone
     * to one AudioRecord at a time.
     */
    public static void enroll(Context context, EnrollListener listener) {
        final Context app = context.getApplicationContext();
        final Handler main = MAIN;
        if (!hasPermission(app)) {
            main.post(() -> listener.onDone(false, "microphone permission missing"));
            return;
        }
        new Thread(() -> {
            AudioRecord r = open();
            if (r == null) {
                main.post(() -> listener.onDone(false, "could not open microphone"));
                return;
            }
            List<float[][]> templates = new ArrayList<>();
            try {
                r.startRecording();
                Logger.i("voice: enrolment started");
                short[] pcm = new short[READ_SAMPLES];
                double[] samples = new double[FRAME * 3];
                int count = 0;
                FrameBuilder builder = new FrameBuilder();
                Segmenter segmenter = new Segmenter();
                long deadline = System.currentTimeMillis() + 60000L;
                long nextLog = System.currentTimeMillis() + 2000L;
                int readErrors = 0;
                while (templates.size() < 3 && System.currentTimeMillis() < deadline) {
                    int n = r.read(pcm, 0, pcm.length);
                    if (n <= 0) {
                        if (readErrors++ < 5) {
                            Logger.i("voice: enrolment read " + n);
                        }
                        continue;
                    }
                    for (int i = 0; i < n; i++) {
                        samples[count++] = pcm[i] / 32768.0;
                        if (count >= FRAME) {
                            Frame f = builder.frame(samples);
                            count -= HOP;
                            System.arraycopy(samples, HOP, samples, 0, count);
                            List<Frame> segment = segmenter.feed(f);
                            if (segment == null) {
                                continue;
                            }
                            float[][] mfcc = normalized(trim(segment));
                            if (mfcc == null || mfcc.length < MIN_FRAMES
                                    || mfcc.length > MAX_FRAMES) {
                                Logger.i("voice: enrolment sample rejected ("
                                        + (mfcc == null ? 0 : mfcc.length) + " frames)");
                                continue;
                            }
                            templates.add(mfcc);
                            final int done = templates.size();
                            Logger.i("voice: enrolment sample " + done
                                    + " (" + mfcc.length + " frames)");
                            main.post(() -> listener.onProgress(done));
                        }
                    }
                    if (System.currentTimeMillis() >= nextLog) {
                        Logger.i("voice: enrol diag " + segmenter.summary()
                                + " samples=" + templates.size());
                        nextLog += 2000L;
                    }
                }
                if (templates.size() < 3) {
                    main.post(() -> listener.onDone(false,
                            "not enough speech (3 samples needed)"));
                    return;
                }
                float d12 = dtw(templates.get(0), templates.get(1));
                float d13 = dtw(templates.get(0), templates.get(2));
                float d23 = dtw(templates.get(1), templates.get(2));
                float max = Math.max(d12, Math.max(d13, d23));
                float threshold = Math.max(0.7f, Math.min(3.0f, max * 1.6f));
                Logger.i("voice: distances " + d12 + " " + d13 + " " + d23
                        + " -> threshold " + threshold);
                save(app, templates, threshold);
                final float saved = threshold;
                main.post(() -> listener.onDone(true,
                        "wake word saved (threshold " + saved + ")"));
            } catch (Throwable t) {
                Logger.i("voice: enrolment failed " + t);
                main.post(() -> listener.onDone(false, "recording failed: " + t));
            } finally {
                try {
                    if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        r.stop();
                    }
                } catch (Throwable ignored) {
                }
                try {
                    r.release();
                } catch (Throwable ignored) {
                }
                Logger.i("voice: enrolment ended");
            }
        }, "attc-voice-enrol").start();
    }

    /**
     * Records raw microphone energy for a few seconds and logs it, so a silent
     * or muted microphone can be told apart from a threshold problem without
     * needing a wake-word recording. Triggered with
     * {@code adb shell am start ... --ez voicediag true}.
     */
    public static void diagnose(Context context, int seconds, EnrollListener listener) {
        final Context app = context.getApplicationContext();
        final Handler main = MAIN;
        if (!hasPermission(app)) {
            main.post(() -> listener.onDone(false, "microphone permission missing"));
            return;
        }
        new Thread(() -> {
            AudioRecord r = open();
            if (r == null) {
                main.post(() -> listener.onDone(false, "could not open microphone"));
                return;
            }
            try {
                r.startRecording();
                Logger.i("voice: diagnose started (" + seconds + "s)");
                short[] pcm = new short[READ_SAMPLES];
                double[] samples = new double[FRAME * 3];
                int count = 0;
                int reads = 0;
                FrameBuilder builder = new FrameBuilder();
                Segmenter segmenter = new Segmenter();
                long end = System.currentTimeMillis() + seconds * 1000L;
                long nextLog = System.currentTimeMillis() + 1000L;
                double intervalPeak = 0;
                long intervalFrames = 0;
                while (System.currentTimeMillis() < end) {
                    int n = r.read(pcm, 0, pcm.length);
                    if (n <= 0) {
                        if (reads == 0 || n == AudioRecord.ERROR_DEAD_OBJECT) {
                            Logger.i("voice: diagnose read " + n);
                        }
                        if (n == AudioRecord.ERROR_DEAD_OBJECT) {
                            break;
                        }
                        continue;
                    }
                    reads++;
                    for (int i = 0; i < n; i++) {
                        samples[count++] = pcm[i] / 32768.0;
                        if (count >= FRAME) {
                            Frame f = builder.frame(samples);
                            count -= HOP;
                            System.arraycopy(samples, HOP, samples, 0, count);
                            intervalPeak = Math.max(intervalPeak, f.rms);
                            intervalFrames++;
                            segmenter.feed(f);
                        }
                    }
                    if (System.currentTimeMillis() >= nextLog) {
                        Logger.i("voice: diag peak=" + round4(intervalPeak)
                                + " n=" + intervalFrames + " "
                                + segmenter.summary());
                        intervalPeak = 0;
                        intervalFrames = 0;
                        nextLog += 1000L;
                    }
                }
                Logger.i("voice: diagnose done reads=" + reads + " "
                        + segmenter.summary());
                main.post(() -> listener.onDone(true, "diagnose done"));
            } catch (Throwable t) {
                Logger.i("voice: diagnose failed " + t);
                main.post(() -> listener.onDone(false, "diagnose failed: " + t));
            } finally {
                try {
                    if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        r.stop();
                    }
                } catch (Throwable ignored) {
                }
                try {
                    r.release();
                } catch (Throwable ignored) {
                }
                Logger.i("voice: diagnose ended");
            }
        }, "attc-voice-diag").start();
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    // ------------------------------------------------------------ templates

    static final class Loaded {
        final List<float[][]> templates = new ArrayList<>();
        float threshold = 0.9f;
    }

    private static File templateFile(Context context) {
        return new File(context.getFilesDir(), TEMPLATE_FILE);
    }

    static boolean hasTemplates(Context context) {
        Loaded l = load(context);
        return l != null && !l.templates.isEmpty();
    }

    /** Drops the stored recordings; used when the wake phrase changes. */
    static void clearTemplates(Context context) {
        File file = templateFile(context);
        if (file.exists() && !file.delete()) {
            Logger.i("voice: could not delete " + file);
        } else if (!file.exists()) {
            Logger.i("voice: no templates to clear");
        } else {
            Logger.i("voice: templates cleared");
        }
    }

    static float threshold(Context context) {
        Loaded l = load(context);
        return l == null ? 0.9f : l.threshold;
    }

    static Loaded load(Context context) {
        File file = templateFile(context);
        if (!file.exists()) {
            return null;
        }
        try {
            byte[] data = new byte[(int) file.length()];
            FileInputStream in = new FileInputStream(file);
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            in.close();
            JSONObject root = new JSONObject(new String(data, 0, read,
                    StandardCharsets.UTF_8));
            Loaded loaded = new Loaded();
            loaded.threshold = (float) root.optDouble("threshold", 0.9);
            JSONArray all = root.getJSONArray("templates");
            for (int t = 0; t < all.length(); t++) {
                JSONArray frames = all.getJSONArray(t);
                float[][] mfcc = new float[frames.length()][];
                for (int i = 0; i < frames.length(); i++) {
                    JSONArray row = frames.getJSONArray(i);
                    float[] frame = new float[MFCC];
                    for (int k = 0; k < MFCC; k++) {
                        frame[k] = (float) row.optDouble(k, 0);
                    }
                    mfcc[i] = frame;
                }
                if (mfcc.length > 0) {
                    loaded.templates.add(mfcc);
                }
            }
            return loaded;
        } catch (Throwable t) {
            Logger.i("voice: template load failed " + t);
            return null;
        }
    }

    private static void save(Context context, List<float[][]> templates, float threshold) {
        try {
            JSONObject root = new JSONObject();
            root.put("threshold", threshold);
            root.put("version", 1);
            JSONArray all = new JSONArray();
            for (float[][] mfcc : templates) {
                JSONArray frames = new JSONArray();
                for (float[] frame : mfcc) {
                    JSONArray row = new JSONArray();
                    for (float v : frame) {
                        row.put(Math.round(v * 1000f) / 1000.0);
                    }
                    frames.put(row);
                }
                all.put(frames);
            }
            root.put("templates", all);
            File file = templateFile(context);
            File tmp = new File(file.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(file)) {
                // rename can fail on some FUSE paths; fall back to a copy.
                FileOutputStream direct = new FileOutputStream(file);
                try {
                    direct.write(root.toString().getBytes(StandardCharsets.UTF_8));
                } finally {
                    direct.close();
                }
                tmp.delete();
            }
            Logger.i("voice: saved " + templates.size() + " templates to " + file);
        } catch (Throwable t) {
            Logger.i("voice: template save failed " + t);
        }
    }

    // -------------------------------------------------------------- analysis

    private static final class FrameBuilder {
        private final double[] window = new double[FRAME];
        private final double[] re = new double[FFT_SIZE];
        private final double[] im = new double[FFT_SIZE];
        private final double[] power = new double[FFT_SIZE / 2 + 1];
        private final double[][] filters;
        private final double[][] dct;

        FrameBuilder() {
            for (int i = 0; i < FRAME; i++) {
                window[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FRAME - 1));
            }
            filters = melFilters();
            dct = dctMatrix();
        }

        Frame frame(double[] samples) {
            double sum = 0;
            for (int i = 0; i < FRAME; i++) {
                re[i] = samples[i] * window[i];
                im[i] = 0;
                sum += samples[i] * samples[i];
            }
            for (int i = FRAME; i < FFT_SIZE; i++) {
                re[i] = 0;
                im[i] = 0;
            }
            fft(re, im);
            for (int i = 0; i <= FFT_SIZE / 2; i++) {
                power[i] = re[i] * re[i] + im[i] * im[i];
            }
            double[] logE = new double[MEL_BANDS];
            for (int m = 0; m < MEL_BANDS; m++) {
                double e = 0;
                double[] weights = filters[m];
                for (int b = 0; b < weights.length; b++) {
                    if (weights[b] != 0) {
                        e += power[b] * weights[b];
                    }
                }
                logE[m] = Math.log(e + 1e-10);
            }
            float[] mfcc = new float[MFCC];
            for (int k = 0; k < MFCC; k++) {
                double v = 0;
                for (int m = 0; m < MEL_BANDS; m++) {
                    v += logE[m] * dct[k][m];
                }
                mfcc[k] = (float) v;
            }
            double rms = Math.sqrt(sum / FRAME);
            return new Frame(mfcc, rms);
        }
    }

    private static double[][] melFilters() {
        double lowMel = hzToMel(80);
        double highMel = hzToMel(RATE / 2.0 - 200);
        double[] points = new double[MEL_BANDS + 2];
        for (int i = 0; i < points.length; i++) {
            points[i] = melToHz(lowMel + (highMel - lowMel) * i / (points.length - 1));
        }
        int bins = FFT_SIZE / 2 + 1;
        double[][] filters = new double[MEL_BANDS][bins];
        for (int m = 0; m < MEL_BANDS; m++) {
            double left = points[m];
            double center = points[m + 1];
            double right = points[m + 2];
            for (int b = 0; b < bins; b++) {
                double hz = b * (double) RATE / FFT_SIZE;
                double w = 0;
                if (hz >= left && hz <= center && center > left) {
                    w = (hz - left) / (center - left);
                } else if (hz > center && hz <= right && right > center) {
                    w = (right - hz) / (right - center);
                }
                filters[m][b] = w;
            }
        }
        return filters;
    }

    private static double[][] dctMatrix() {
        double[][] dct = new double[MFCC][MEL_BANDS];
        for (int k = 0; k < MFCC; k++) {
            double scale = k == 0 ? Math.sqrt(1.0 / MEL_BANDS)
                    : Math.sqrt(2.0 / MEL_BANDS);
            for (int m = 0; m < MEL_BANDS; m++) {
                dct[k][m] = scale * Math.cos(Math.PI * k * (m + 0.5) / MEL_BANDS);
            }
        }
        return dct;
    }

    private static double hzToMel(double hz) {
        return 2595.0 * Math.log10(1.0 + hz / 700.0);
    }

    private static double melToHz(double mel) {
        return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
    }

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            double wlenR = Math.cos(angle);
            double wlenI = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double wr = 1;
                double wi = 0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j;
                    int v = i + j + len / 2;
                    double vr = re[v] * wr - im[v] * wi;
                    double vi = re[v] * wi + im[v] * wr;
                    re[v] = re[u] - vr;
                    im[v] = im[u] - vi;
                    re[u] += vr;
                    im[u] += vi;
                    double nwr = wr * wlenR - wi * wlenI;
                    wi = wr * wlenI + wi * wlenR;
                    wr = nwr;
                }
            }
        }
    }

    /** Adaptive energy VAD that emits one segment when speech pauses. */
    private static final class Segmenter {
        private double noise = NOISE_INIT;
        private boolean speech;
        private int silence;
        private final List<Frame> current = new ArrayList<>();
        private final ArrayDeque<Frame> recent = new ArrayDeque<>();
        private long frames;
        private int segments;
        private double peak;

        List<Frame> feed(Frame f) {
            double rms = f.rms;
            frames++;
            if (rms > peak) {
                peak = rms;
            }
            boolean voiced = rms > Math.max(VOICED_MIN, noise * 2.8);
            if (!voiced) {
                // Track the floor only while nobody is talking, so a held note
                // cannot lift the threshold above the speaker's own level.
                noise = Math.min(NOISE_MAX,
                        Math.max(NOISE_MIN, noise * 0.98 + rms * 0.02));
            }
            recent.addLast(f);
            while (recent.size() > 8) {
                recent.removeFirst();
            }
            if (voiced) {
                if (!speech) {
                    speech = true;
                    current.clear();
                    // Keep a little pre-roll so the first consonant is not lost.
                    int skip = Math.max(0, recent.size() - 6);
                    int i = 0;
                    for (Frame r : recent) {
                        if (i++ >= skip) {
                            current.add(r);
                        }
                    }
                }
                current.add(f);
                silence = 0;
                if (current.size() >= MAX_FRAMES) {
                    // Never drop a long utterance: emit it and let the length
                    // check / DTW matcher decide what to do with it.
                    return emit();
                }
                return null;
            }
            if (!speech) {
                return null;
            }
            current.add(f);
            silence++;
            if (silence >= END_SILENCE) {
                return emit();
            }
            return null;
        }

        private List<Frame> emit() {
            List<Frame> out = new ArrayList<>(current);
            segments++;
            reset();
            return out;
        }

        void reset() {
            speech = false;
            silence = 0;
            current.clear();
            recent.clear();
        }

        String summary() {
            return "frames=" + frames + " segments=" + segments
                    + " noise=" + round4(noise) + " peak=" + round4(peak);
        }

        private static double round4(double v) {
            return Math.round(v * 10000.0) / 10000.0;
        }
    }

    private static List<Frame> trim(List<Frame> frames) {
        if (frames.isEmpty()) {
            return frames;
        }
        double max = 0;
        for (Frame f : frames) {
            max = Math.max(max, f.rms);
        }
        // Relative floor only: the segmenter already decided this is speech.
        double floor = Math.max(0.002, max * 0.15);
        int start = 0;
        int end = frames.size() - 1;
        while (start < end && frames.get(start).rms < floor) {
            start++;
        }
        while (end > start && frames.get(end).rms < floor) {
            end--;
        }
        List<Frame> out = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            out.add(frames.get(i));
        }
        return out;
    }

    private static float[][] normalized(List<Frame> frames) {
        if (frames.size() < MIN_FRAMES) {
            return null;
        }
        int n = frames.size();
        double[] mean = new double[MFCC];
        for (Frame f : frames) {
            for (int k = 0; k < MFCC; k++) {
                mean[k] += f.mfcc[k];
            }
        }
        for (int k = 0; k < MFCC; k++) {
            mean[k] /= n;
        }
        double[] std = new double[MFCC];
        for (Frame f : frames) {
            for (int k = 0; k < MFCC; k++) {
                double d = f.mfcc[k] - mean[k];
                std[k] += d * d;
            }
        }
        for (int k = 0; k < MFCC; k++) {
            std[k] = Math.sqrt(std[k] / n);
            if (std[k] < 1e-4) {
                std[k] = 1e-4;
            }
        }
        float[][] out = new float[n][MFCC];
        for (int i = 0; i < n; i++) {
            Frame f = frames.get(i);
            for (int k = 0; k < MFCC; k++) {
                out[i][k] = (float) ((f.mfcc[k] - mean[k]) / std[k]);
            }
        }
        return out;
    }

    private static float bestDistance(float[][] sample, List<float[][]> templates) {
        float best = Float.MAX_VALUE;
        for (float[][] t : templates) {
            float d = dtw(sample, t);
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    /** Normalised dynamic time warping with a Sakoe-Chiba band. */
    static float dtw(float[][] a, float[][] b) {
        int n = a.length;
        int m = b.length;
        if (n == 0 || m == 0) {
            return Float.MAX_VALUE;
        }
        float[] prev = new float[m + 1];
        float[] cur = new float[m + 1];
        java.util.Arrays.fill(prev, Float.MAX_VALUE);
        prev[0] = 0;
        int band = Math.max(12, (int) (0.6 * Math.max(n, m)));
        if (Math.abs(n - m) > band) {
            // The warping band cannot bridge such different lengths.
            return 10f;
        }
        for (int i = 1; i <= n; i++) {
            java.util.Arrays.fill(cur, Float.MAX_VALUE);
            int lo = Math.max(1, i - band);
            int hi = Math.min(m, i + band);
            for (int j = lo; j <= hi; j++) {
                float cost = dist(a[i - 1], b[j - 1]);
                float best = Math.min(prev[j], Math.min(cur[j - 1], prev[j - 1]));
                cur[j] = cost + best;
            }
            float[] t = prev;
            prev = cur;
            cur = t;
        }
        float total = prev[m];
        if (!Float.isFinite(total) || total > 100f) {
            // Unreachable cells stay at Float.MAX_VALUE; report a plain miss.
            return 10f;
        }
        return total / (n + m);
    }

    private static float dist(float[] a, float[] b) {
        double sum = 0;
        for (int k = 0; k < MFCC; k++) {
            double d = a[k] - b[k];
            sum += d * d;
        }
        return (float) Math.sqrt(sum / MFCC);
    }
}
