package com.joynal.raniai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final String GITHUB_RELEASE_API =
        "https://api.github.com/repos/devjoynal410/rani-mobile-app/releases/latest";
    private static final int CURRENT_BUILD = 22;
    private static final int REQ_AUDIO = 101;

    private WebView webView;
    private long downloadId = -1;
    private DownloadManager downloadManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        // Request mic permission at runtime
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }

        webView.loadUrl("file:///android_asset/index.html");

        downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

        // Check for update after 5 seconds
        new Handler(Looper.getMainLooper()).postDelayed(this::checkUpdate, 5000);
    }

    private void checkUpdate() {
        new Thread(() -> {
            try {
                URL url = new URL(GITHUB_RELEASE_API);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "RANI-Mobile");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                if (conn.getResponseCode() != 200) return;

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                String tagName = json.optString("tag_name", "");
                if (!tagName.startsWith("build-")) return;

                int latestBuild = Integer.parseInt(tagName.replace("build-", "").trim());
                if (latestBuild <= CURRENT_BUILD) return;

                JSONArray assets = json.getJSONArray("assets");
                String apkUrl = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url");
                        break;
                    }
                }
                if (apkUrl == null) return;

                final String finalUrl = apkUrl;
                final int build = latestBuild;
                runOnUiThread(() -> showUpdateDialog(build, finalUrl));

            } catch (Exception e) {
                // Silent — no internet or no release yet
            }
        }).start();
    }

    private void showUpdateDialog(int build, String apkUrl) {
        new AlertDialog.Builder(this)
            .setTitle("নতুন আপডেট!")
            .setMessage("RANI AI Build " + build + " পাওয়া গেছে।\nএখনই install করবেন?")
            .setPositiveButton("Install", (d, w) -> downloadApk(apkUrl))
            .setNegativeButton("পরে", null)
            .show();
    }

    private void downloadApk(String apkUrl) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("RANI AI Update");
            request.setDescription("Downloading update...");
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this, null, "RANI-AI-update.apk");
            request.setMimeType("application/vnd.android.package-archive");

            downloadId = downloadManager.enqueue(request);
            registerReceiver(downloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                .setMessage("Download failed: " + e.getMessage())
                .setPositiveButton("OK", null).show();
        }
    }

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == downloadId) {
                installApk();
            }
        }
    };

    private void installApk() {
        try {
            Uri apkUri = downloadManager.getUriForDownloadedFile(downloadId);
            if (apkUri == null) return;
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                .setMessage("Install failed: " + e.getMessage())
                .setPositiveButton("OK", null).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
    }
}
