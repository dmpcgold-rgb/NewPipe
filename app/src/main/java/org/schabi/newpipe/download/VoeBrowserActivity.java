/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebResourceRequest;
import android.view.MotionEvent;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.schabi.newpipe.util.ThemeHelper;

import java.util.Locale;

/**
 * Displays the official VOE website and accepts only downloads explicitly offered
 * by its web player. No page/script extraction or access-control bypass is used.
 */
public class VoeBrowserActivity extends AppCompatActivity {
    public static final String EXTRA_VOE_URL =
            "org.schabi.newpipe.download.extra.VOE_URL";
    private static final String PREFERENCES = "gold_link_queue";
    private static final String SAVED_WIFI_ONLY = "wifi_only";

    @Nullable
    private WebView browser;
    private long lastUserTapMs;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable final Bundle state) {
        ThemeHelper.setTheme(this);
        super.onCreate(state);
        setTitle("VOE • Offizieller Download");

        final String url = getIntent().getStringExtra(EXTRA_VOE_URL);
        if (!LinkQueueLinks.isVoeUrl(url)) {
            Toast.makeText(this, "Nur gültige VOE-Links werden geöffnet",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        browser = new WebView(this);
        setContentView(browser);
        browser.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                lastUserTapMs = SystemClock.elapsedRealtime();
            }
            return false;
        });
        final WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        browser.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(final WebView view,
                                                    final WebResourceRequest request) {
                if (!request.isForMainFrame()) {
                    return false;
                }
                final String next = request.getUrl().toString();
                if (LinkQueueLinks.isVoeUrl(next)) {
                    return false;
                }
                // A tapped direct media link is a permitted file-download action.
                if (request.hasGesture() && LinkQueueLinks.isDirectMedia(next)) {
                    offerDownload(next, view.getSettings().getUserAgentString(),
                            null, null);
                }
                // Suppress unrelated advertising redirects and popup domains.
                return true;
            }
        });
        browser.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(final String downloadUrl,
                                        final String userAgent,
                                        final String disposition,
                                        final String mimeType,
                                        final long contentLength) {
                offerDownload(downloadUrl, userAgent, disposition, mimeType);
            }
        });

        if (state == null || browser.restoreState(state) == null) {
            browser.loadUrl(url);
        }
    }

    private boolean fromRecentUserTap() {
        final long elapsed = SystemClock.elapsedRealtime() - lastUserTapMs;
        return lastUserTapMs > 0 && elapsed >= 0 && elapsed < 15000;
    }

    private static boolean isMediaDownload(final String url, final String mimeType) {
        if (LinkQueueLinks.isDirectMedia(url)) {
            return true;
        }
        if (mimeType == null) {
            return false;
        }
        final String type = mimeType.toLowerCase(Locale.ROOT);
        return type.startsWith("video/") || type.startsWith("audio/");
    }

    private void offerDownload(final String url, final String userAgent,
                               final String disposition, final String mimeType) {
        // A page may trigger downloads without a tap. Only enqueue a real media file
        // offered following the user's interaction; never capture playback streams.
        if (!fromRecentUserTap() || !LinkQueueLinks.isSafeHttps(url)
                || isManifestOrLicense(url) || !isMediaDownload(url, mimeType)) {
            return;
        }
        final String original = URLUtil.guessFileName(url, disposition, mimeType);
        final String safeName = original.replaceAll("[^a-zA-Z0-9._-]", "_");
        final String filename = System.currentTimeMillis() + "-"
                + (safeName.isEmpty() ? "video" : safeName);
        // The user explicitly opted into automatic downloads by tapping Play.
        enqueueDownload(url, userAgent, mimeType, filename);
    }

    private static boolean isManifestOrLicense(final String url) {
        final String path = Uri.parse(url).getPath();
        if (path == null) {
            return true;
        }
        final String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".m3u8") || lower.endsWith(".mpd")
                || lower.endsWith(".ism") || lower.endsWith(".key")
                || lower.endsWith(".license");
    }

    private void enqueueDownload(final String url, final String userAgent,
                                 final String mimeType, final String filename) {
        final DownloadManager manager =
                (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "Android-Downloadmanager nicht verfügbar",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            final DownloadManager.Request request =
                    new DownloadManager.Request(Uri.parse(url));
            if (mimeType != null && !mimeType.isEmpty()) {
                request.setMimeType(mimeType);
            }
            if (userAgent != null && !userAgent.isEmpty()) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            final String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) {
                request.addRequestHeader("Cookie", cookies);
            }
            if (browser != null && LinkQueueLinks.isVoeUrl(browser.getUrl())) {
                request.addRequestHeader("Referer", browser.getUrl());
            }
            request.setTitle(filename);
            request.setDescription("VOE • offizieller Dateidownload");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(!getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .getBoolean(SAVED_WIFI_ONLY, false));
            request.setAllowedOverRoaming(false);
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "NewPipeGold/" + filename);
            manager.enqueue(request);
            Toast.makeText(this, "Download an Android übergeben", Toast.LENGTH_LONG).show();
        } catch (final IllegalArgumentException | SecurityException
                | IllegalStateException ex) {
            Toast.makeText(this, "Download nicht möglich: "
                    + ex.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (browser != null && browser.canGoBack()) {
            browser.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (browser != null) {
            browser.setDownloadListener(null);
            browser.destroy();
            browser = null;
        }
        super.onDestroy();
    }
}
