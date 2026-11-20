package com.attc.opencode;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Carries the guest's audio over loopback to the phone.
 *
 * The Ubuntu guest has no sound device. CLIs that want to speak (Antigravity
 * voice mode, notification players, anything that shells out to paplay or
 * aplay) find small shim scripts instead, and those shims stream raw PCM to
 * these two servers:
 *
 *   127.0.0.1:4714  play  <rate> <channels> <encoding>\n  then PCM to Android
 *   127.0.0.1:4715  rec   <rate> <channels> <encoding>\n  then phone mic PCM
 *
 * The same idea as the file based attc-ctl bridge: the guest cannot open an
 * Android audio device, so the app owns the device and the guest only sees a
 * socket. Raw s16le keeps both ends simple and matches what arecord, pw-record,
 * paplay and pw-cat already speak.
 */
public final class AudioBridge {

    public static final int PLAY_PORT = 4714;
    public static final int REC_PORT = 4715;

    /** Lets the service pause the wake-word engine while a CLI holds the mic. */
    public interface Listener {
        void onMicStart();

        void onMicStop();
    }

    private static volatile boolean started;
    private static volatile Listener listener;
    private static ServerSocket playServer;
    private static ServerSocket recServer;
    private static Context appContext;
    /** Number of playback clients currently sounding. */
    private static final java.util.concurrent.atomic.AtomicInteger playClients =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Microphone stays muted until this moment, to swallow speaker reverb. */
    private static volatile long micMuteUntil;

    private AudioBridge() {
    }

    public static void setListener(Listener value) {
        listener = value;
    }

    public static void start(Context context) {
        if (started) {
            return;
        }
        appContext = context.getApplicationContext();
        started = true;
        Thread play = new Thread(() -> serve(PLAY_PORT, true), "audio-play");
        play.setDaemon(true);
        play.start();
        Thread rec = new Thread(() -> serve(REC_PORT, false), "audio-rec");
        rec.setDaemon(true);
        rec.start();
        Logger.i("audio: bridge on 127.0.0.1:" + PLAY_PORT + "/" + REC_PORT);
    }

    public static void stop() {
        started = false;
        close(playServer);
        close(recServer);
        playServer = null;
        recServer = null;
        Logger.i("audio: bridge stopped");
    }

    private static void close(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void serve(int port, boolean play) {
        while (started) {
            ServerSocket server = null;
            try {
                server = new ServerSocket(port, 4,
                        InetAddress.getByName("127.0.0.1"));
                if (play) {
                    playServer = server;
                } else {
                    recServer = server;
                }
                while (started) {
                    Socket client = server.accept();
                    Thread worker = new Thread(() -> {
                        if (play) {
                            handlePlay(client);
                        } else {
                            handleRecord(client);
                        }
                    }, play ? "audio-play-client" : "audio-rec-client");
                    worker.setDaemon(true);
                    worker.start();
                }
            } catch (Throwable t) {
                if (started) {
                    Logger.i("audio: server " + port + " " + t);
                    sleep(400L);
                }
            } finally {
                close(server);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** Reads one CRLF/LF terminated ASCII header from the socket. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.append((char) b);
            }
            if (line.length() > 256) {
                throw new IOException("audio: header too long");
            }
        }
        return line.toString().trim();
    }

    private static int clampInt(String text, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(text.trim());
            return value < min || value > max ? fallback : value;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * True while the speaker is (or was just) sounding. The phone microphone
     * sits next to the speaker; without this gate Antigravity transcribes its
     * own voice, treats it as a user turn and restarts the same sentence.
     */
    private static boolean echoGuardActive() {
        return playClients.get() > 0 || System.currentTimeMillis() < micMuteUntil;
    }

    private static void handlePlay(Socket client) {
        String header = null;
        boolean playbackStarted = false;
        try {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            header = readLine(in);
            String[] parts = header.split("\\s+");
            if (parts.length < 2 || !"play".equals(parts[0])) {
                Logger.i("audio: bad play header " + header);
                return;
            }
            int rate = clampInt(parts[1], 16000, 4000, 96000);
            int channels = clampInt(parts[2], 1, 1, 2);
            int channelMask = channels == 2
                    ? AudioFormat.CHANNEL_OUT_STEREO
                    : AudioFormat.CHANNEL_OUT_MONO;
            playClients.incrementAndGet();
            playbackStarted = true;
            int min = AudioTrack.getMinBufferSize(rate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT);
            // Keep about 200 ms queued. A small buffer underruns and a short
            // stop() cuts the tail of a sentence; 200 ms rides out jitter and
            // leaves the drain wait below short.
            int target = rate * channels * 2 / 5;
            int buffer = Math.max(min > 0 ? min : 4096, Math.max(target, 4096));
            AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                    channelMask, AudioFormat.ENCODING_PCM_16BIT, buffer,
                    AudioTrack.MODE_STREAM);
            Logger.i("audio: play rate=" + rate + " ch=" + channels
                    + " buffer=" + buffer);
            byte[] data = new byte[8192];
            int read;
            long frames = 0;
            int bytesPerFrame = channels * 2;
            track.play();
            while ((read = in.read(data)) > 0) {
                int offset = 0;
                while (offset < read) {
                    // write() may accept fewer bytes than asked; dropping the
                    // remainder is what made a long reply stop mid-sentence.
                    int written = track.write(data, offset, read - offset);
                    if (written < 0) {
                        throw new IOException("audio: write " + written);
                    }
                    if (written == 0) {
                        sleep(5L);
                        continue;
                    }
                    offset += written;
                    frames += written / bytesPerFrame;
                }
            }
            // Wait for the head to catch up before stopping: stop() on a
            // streaming track can discard whatever is still queued, which is
            // what cut long replies in half.
            long deadline = System.currentTimeMillis() + 5000L;
            while (track.getPlaybackHeadPosition() < frames
                    && System.currentTimeMillis() < deadline) {
                sleep(20L);
            }
            try {
                track.stop();
            } catch (Throwable ignored) {
            }
            track.release();
            Logger.i("audio: play done frames=" + frames);
        } catch (Throwable t) {
            Logger.i("audio: play failed " + header + ": " + t);
        } finally {
            if (playbackStarted) {
                // Keep the microphone shut a little longer: the last words
                // still ring in the room after the socket is drained.
                micMuteUntil = System.currentTimeMillis() + 700L;
                playClients.decrementAndGet();
            }
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void handleRecord(Socket client) {
        String header = null;
        boolean toldListener = false;
        AudioRecord record = null;
        AcousticEchoCanceler aec = null;
        try {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            header = readLine(in);
            String[] parts = header.split("\\s+");
            if (parts.length < 2 || !"rec".equals(parts[0])) {
                Logger.i("audio: bad rec header " + header);
                return;
            }
            int rate = clampInt(parts[1], 16000, 4000, 96000);
            int channels = clampInt(parts[2], 1, 1, 2);
            int channelMask = channels == 2
                    ? AudioFormat.CHANNEL_IN_STEREO
                    : AudioFormat.CHANNEL_IN_MONO;
            int min = AudioRecord.getMinBufferSize(rate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT);
            int buffer = Math.max(min > 0 ? min : 4096, 4096);
            // VOICE_COMMUNICATION asks the platform for echo cancellation
            // and noise suppression, which is exactly this call's problem.
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate, channelMask, AudioFormat.ENCODING_PCM_16BIT, buffer);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                record = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                        channelMask, AudioFormat.ENCODING_PCM_16BIT, buffer);
            }
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Logger.i("audio: recorder not initialized");
                return;
            }
            try {
                aec = AcousticEchoCanceler.create(record.getAudioSessionId());
                if (aec != null) {
                    aec.setEnabled(true);
                    Logger.i("audio: aec " + aec.getEnabled());
                }
            } catch (Throwable t) {
                Logger.i("audio: aec unavailable " + t);
            }
            Listener l = listener;
            if (l != null) {
                l.onMicStart();
                toldListener = true;
            }
            Logger.i("audio: rec rate=" + rate + " ch=" + channels);
            byte[] data = new byte[Math.max(1024, buffer / 2)];
            record.startRecording();
            while (started) {
                int read = record.read(data, 0, data.length);
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION
                            || read == AudioRecord.ERROR_BAD_VALUE) {
                        break;
                    }
                    continue;
                }
                if (echoGuardActive()) {
                    // Speaker is talking: send silence, never our own echo.
                    java.util.Arrays.fill(data, 0, read, (byte) 0);
                }
                out.write(data, 0, read);
                out.flush();
            }
        } catch (Throwable t) {
            Logger.i("audio: rec closed " + header + ": " + t);
        } finally {
            if (aec != null) {
                try {
                    aec.release();
                } catch (Throwable ignored) {
                }
            }
            if (record != null) {
                try {
                    record.stop();
                } catch (Throwable ignored) {
                }
                record.release();
            }
            Listener l = listener;
            if (l != null && toldListener) {
                l.onMicStop();
            }
            try {
                client.close();
            } catch (IOException ignored) {
            }
            Logger.i("audio: rec done");
        }
    }
}
