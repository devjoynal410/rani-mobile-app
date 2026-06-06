package com.joynal.raniai;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final String GITHUB_API =
        "https://api.github.com/repos/devjoynal410/rani-mobile-app/releases/latest";
    private static final int CURRENT_BUILD = 31;
    private static final int FILE_REQ = 102;

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private long downloadId = -1;
    private DownloadManager dm;
    private boolean receiverRegistered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            setupWebView();
            dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            new Handler(Looper.getMainLooper()).postDelayed(this::requestPerms, 1500);
            new Handler(Looper.getMainLooper()).postDelayed(this::checkUpdate, 8000);
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setupWebView() {
        webView = findViewById(R.id.webview);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setGeolocationEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setDatabaseEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                // Suppress file:// asset errors
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    try { request.grant(request.getResources()); }
                    catch (Exception ignored) {}
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                try { callback.invoke(origin, true, false); }
                catch (Exception ignored) {}
            }

            @Override
            public boolean onShowFileChooser(WebView wv,
                    ValueCallback<Uri[]> cb,
                    FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                }
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQ);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void requestPerms() {
        try {
            String[] perms = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_EXTERNAL_STORAGE
            };
            boolean need = false;
            for (String p : perms)
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { need = true; break; }
            if (need) requestPermissions(perms, 101);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == FILE_REQ && fileCallback != null) {
            try {
                fileCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(res, data));
            } catch (Exception e) {
                fileCallback.onReceiveValue(null);
            }
            fileCallback = null;
        }
    }

    // ── Auto-update (fully background, no dialogs) ────────────────────────────
    private void checkUpdate() {
        new Thread(() -> {
            try {
                URL url = new URL(GITHUB_API);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestProperty("User-Agent", "RANI-Mobile");
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) return;

                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject json = new JSONObject(sb.toString());
                String tag = json.optString("tag_name", "");
                if (!tag.startsWith("build-")) return;

                int latest = Integer.parseInt(tag.replace("build-", "").trim());
                if (latest <= CURRENT_BUILD) return;

                JSONArray assets = json.getJSONArray("assets");
                String apkUrl = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.getString("name").endsWith(".apk")) {
                        apkUrl = a.getString("browser_download_url");
                        break;
                    }
                }
                if (apkUrl == null) return;

                final String finalUrl = apkUrl;
                final int build = latest;
                // Auto-download silently — no dialog, no user prompt
                runOnUiThread(() -> {
                    try {
                        Toast.makeText(MainActivity.this,
                            "RANI AI update downloading...", Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                    downloadApk(finalUrl);
                });
            } catch (Exception ignored) {}
        }).start();
    }

    private void downloadApk(String apkUrl) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl));
            req.setTitle("RANI AI Update");
            req.setDescription("Installing update in background...");
            // Silent download — notify only when complete (tap to install)
            req.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(this, null, "RANI-update.apk");
            req.setMimeType("application/vnd.android.package-archive");
            downloadId = dm.enqueue(req);
            registerReceiver(dlReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
            receiverRegistered = true;
        } catch (Exception ignored) {}
    }

    private final BroadcastReceiver dlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == downloadId) installApk();
        }
    };

    private void installApk() {
        try {
            Uri uri = dm.getUriForDownloadedFile(downloadId);
            if (uri == null) return;
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    @Override
    public void onBackPressed() {
        try {
            if (webView != null && webView.canGoBack()) webView.goBack();
            else super.onBackPressed();
        } catch (Exception ignored) { super.onBackPressed(); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiverRegistered) try { unregisterReceiver(dlReceiver); } catch (Exception ignored) {}
        if (webView != null) try { webView.destroy(); } catch (Exception ignored) {}
    }
}
