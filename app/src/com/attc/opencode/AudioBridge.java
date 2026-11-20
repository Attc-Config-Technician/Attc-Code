package com.attc.opencode;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

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

    private static void handlePlay(Socket client) {
        String header = null;
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
            int min = AudioTrack.getMinBufferSize(rate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT);
            int buffer = Math.max(min > 0 ? min : 4096, 4096);
            AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                    channelMask, AudioFormat.ENCODING_PCM_16BIT, buffer,
                    AudioTrack.MODE_STREAM);
            Logger.i("audio: play rate=" + rate + " ch=" + channels
                    + " buffer=" + buffer);
            byte[] data = new byte[4096];
            int read;
            track.play();
            while ((read = in.read(data)) > 0) {
                track.write(data, 0, read);
            }
            // Let the queued frames reach the speaker before tearing down.
            sleep(120L);
            try {
                track.stop();
            } catch (Throwable ignored) {
            }
            track.release();
            Logger.i("audio: play done");
        } catch (Throwable t) {
            Logger.i("audio: play failed " + header + ": " + t);
        } finally {
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
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                    channelMask, AudioFormat.ENCODING_PCM_16BIT, buffer);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Logger.i("audio: recorder not initialized");
                return;
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
                out.write(data, 0, read);
                out.flush();
            }
        } catch (Throwable t) {
            Logger.i("audio: rec closed " + header + ": " + t);
        } finally {
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
