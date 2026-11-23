package com.carrace;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.view.WindowInsets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends Activity {

    // assets/index.html dimuat lewat origin https virtual, bukan file://.
    // Dengan begitu script module (build React/Vite) tetap jalan di semua versi Android.
    private static final String HOST = "appassets.androidplatform.net";
    private static final String ASSET_PREFIX = "/assets/";
    private static final String START_URL = "https://" + HOST + ASSET_PREFIX + "index.html";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        webView.setWebViewClient(new AssetClient());
        applySystemBarPadding(webView);
        setContentView(webView);

        boolean restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null;
        if (!restored) {
            webView.loadUrl(START_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
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
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void applySystemBarPadding(View view) {
        view.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(
                        insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
    }

    private final class AssetClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            WebResourceResponse response = serveAsset(request.getUrl());
            return response;
        }
    }

    // Mengambil file dari folder assets/. Path di luar assets/ atau yang mengandung ".." ditolak.
    private WebResourceResponse serveAsset(Uri uri) {
        if (uri == null || !HOST.equals(uri.getHost())) {
            return null; // bukan request ke aset kita, biarkan WebView menanganinya
        }
        String path = uri.getPath();
        if (path == null || !path.startsWith(ASSET_PREFIX)) {
            return notFound();
        }
        String file = path.substring(ASSET_PREFIX.length());
        if (file.isEmpty() || file.endsWith("/")) {
            file = file + "index.html";
        }
        if (file.contains("..")) {
            return notFound();
        }
        try {
            InputStream in = getAssets().open(file);
            return new WebResourceResponse(mimeOf(file), "UTF-8", in);
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse(
                "text/plain", "UTF-8", 404, "Not Found", null,
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
