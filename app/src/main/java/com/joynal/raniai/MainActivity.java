package com.joynal.raniai;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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
    private static final int CURRENT_BUILD = 11;

    private WebView webView;
    private long downloadId = -1;

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
                request.grant(request.getResources());
            }
        });

        webView.loadUrl("file:///android_asset/index.html");

        // Check for update after 3 seconds
        new Handler(Looper.getMainLooper()).postDelayed(this::checkUpdate, 3000);
    }

    private void checkUpdate() {
        new Thread(() -> {
            try {
                URL url = new URL(GITHUB_RELEASE_API);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "RANI-Mobile");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                String tagName = json.getString("tag_name"); // e.g. "build-12"
                int latestBuild = Integer.parseInt(tagName.replace("build-", "").trim());

                if (latestBuild > CURRENT_BUILD) {
                    JSONArray assets = json.getJSONArray("assets");
                    String apkUrl = null;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (asset.getString("name").endsWith(".apk")) {
                            apkUrl = asset.getString("browser_download_url");
                            break;
                        }
                    }
                    final String finalUrl = apkUrl;
                    final int build = latestBuild;
                    new Handler(Looper.getMainLooper()).post(() ->
                        showUpdateDialog(build, finalUrl));
                }
            } catch (Exception e) {
                // Silent fail — no internet or no update
            }
        }).start();
    }

    private void showUpdateDialog(int build, String apkUrl) {
        new AlertDialog.Builder(this)
            .setTitle("🔄 নতুন আপডেট পাওয়া গেছে!")
            .setMessage("RANI AI Build " + build + " available.\nএখনই install করবেন?")
            .setPositiveButton("Install", (d, w) -> downloadApk(apkUrl))
            .setNegativeButton("পরে", null)
            .show();
    }

    private void downloadApk(String apkUrl) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
        request.setTitle("RANI AI Update");
        request.setDescription("Downloading...");
        request.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS, "RANI-AI-update.apk");

        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        downloadId = dm.enqueue(request);

        registerReceiver(downloadReceiver,
            new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
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
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri uri = Uri.fromFile(new java.io.File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "RANI-AI-update.apk"));
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
    }
}
