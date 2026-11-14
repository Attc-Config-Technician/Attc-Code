package com.attc.opencode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

/**
 * Split-tunnel VPN service for the simulated public IP.
 *
 * Only 10.7.0.0/24 is routed into the tun device, so everything else keeps
 * using the real network. There is deliberately no default route and the app
 * itself is excluded from the tunnel.
 */
public class FakeVpnService extends VpnService {

    public static final String ACTION_START = "com.attc.opencode.VPN_START";
    public static final String ACTION_STOP = "com.attc.opencode.VPN_STOP";
    public static final String ACTION_ROTATE = "com.attc.opencode.VPN_ROTATE";

    private static final String CHANNEL = "attc_fakevpn";
    private static final int NOTIFICATION_ID = 12;
    private static final int STOP_REQUEST = 1;
    private static final int ROTATE_REQUEST = 2;

    private ParcelFileDescriptor tun;
    private FakeVpnStack stack;
    private boolean active;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Rotates the simulated address while the tunnel stays up. */
    private final Runnable rotation = new Runnable() {
        @Override
        public void run() {
            if (!active) {
                return;
            }
            String ip = VpnState.rotate(FakeVpnService.this);
            Logger.i("vpn: rotated sim=" + ip);
            refreshNotification();
            handler.postDelayed(this, VpnState.ROTATION_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelfClean();
            return START_NOT_STICKY;
        }
        if (ACTION_ROTATE.equals(action)) {
            if (!active) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            String ip = VpnState.rotate(this);
            Logger.i("vpn: rotated sim=" + ip);
            refreshNotification();
            scheduleRotation();
            return START_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        if (!VpnState.isRunning() && !establish()) {
            Logger.i("vpn: start failed; guest interception stays on");
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private boolean establish() {
        try {
            Builder builder = new Builder();
            builder.setSession("ATTC Fake VPN");
            builder.addAddress(VpnState.TUN_IP, 32);
            builder.addDnsServer(VpnState.DNS_IP);
            builder.addRoute("10.7.0.0", 24);
            builder.setMtu(1500);
            builder.setBlocking(true);
            builder.addDisallowedApplication(getPackageName());
            tun = builder.establish();
        } catch (Exception e) {
            tun = null;
        }

        if (tun == null) {
            Logger.i("vpn: establish failed");
            stopSelfClean();
            return false;
        }

        try {
            stack = new FakeVpnStack(this, tun);
        } catch (Exception e) {
            Logger.i("vpn: establish failed");
            stopSelfClean();
            return false;
        }

        Thread thread = new Thread(stack, "attc-fakevpn");
        thread.setDaemon(true);
        thread.start();
        active = true;
        VpnState.setRunning(true);
        scheduleRotation();
        Logger.i("vpn: started sim=" + VpnState.getSimulatedIp(this)
                + " pool=" + VpnState.poolSize());
        return true;
    }

    private void scheduleRotation() {
        handler.removeCallbacks(rotation);
        handler.postDelayed(rotation, VpnState.ROTATION_INTERVAL_MS);
    }

    private void stopStack() {
        handler.removeCallbacks(rotation);
        FakeVpnStack current = stack;
        stack = null;
        if (current != null) {
            current.stop();
        }
        tun = null;
        boolean wasActive = active;
        active = false;
        VpnState.setRunning(false);
        if (wasActive) {
            Logger.i("vpn: stopped");
        }
    }

    private void stopSelfClean() {
        // Stopping the tunnel must not rewrite the user's choice: the local
        // interception keeps working without it, and a system hiccup used to
        // leave the feature permanently "off" after a single failure.
        stopStack();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onRevoke() {
        // Consent was revoked (system or another VPN app). Keep the intent and
        // let the next app start ask for consent again instead of silently
        // disabling the interception.
        Logger.i("vpn: revoked; interception stays on, consent needed again");
        stopStack();
        stopForeground(true);
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopStack();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // -------------------------------------------------------- notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Fake VPN", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the simulated public IP responder running.");
            channel.setSound(null, null);
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, FakeVpnService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, STOP_REQUEST, stop, flags());

        Intent rotate = new Intent(this, FakeVpnService.class).setAction(ACTION_ROTATE);
        PendingIntent rotatePending =
                PendingIntent.getService(this, ROTATE_REQUEST, rotate, flags());

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("Fake VPN active")
                .setContentText("Simulated IP " + VpnState.getSimulatedIp(this)
                        + " (rotates every 30 min)")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_rotate, "New IP", rotatePending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
                .build();
    }

    private void refreshNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private static int flags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
    }
}
