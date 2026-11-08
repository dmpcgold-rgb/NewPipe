/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Message;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.schabi.newpipe.util.ThemeHelper;
import org.schabi.newpipe.util.external_communication.ShareUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens the original episode page so the user can select the hoster manually.
 * User-selected VOE links are forwarded to the separate VOE browser.
 * The page is never scraped and protected video streams are not extracted.
 */
public class EpisodeHostActivity extends AppCompatActivity {
    public static final String EXTRA_EPISODE_URL =
            "org.schabi.newpipe.download.extra.EPISODE_URL";

    @Nullable
    private WebView browser;
    private final List<WebView> popupBrowsers = new ArrayList<>();
    private boolean handedOff;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        ThemeHelper.setTheme(this);
        super.onCreate(savedInstanceState);
        setTitle("Hoster auswählen");

        final String episodeUrl = getIntent().getStringExtra(EXTRA_EPISODE_URL);
        if (!LinkQueueLinks.isEpisodePage(episodeUrl)) {
            Toast.makeText(this, "Kein gültiger Episodenlink",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final TextView help = new TextView(this);
        help.setText("Wähle auf der Episodenseite den Hoster VOE und "
                + "tippe auf „Stream öffnen“. Wird ein VOE-Link erkannt, "
                + "öffnet sich die VOE-Ansicht. Downloads nur, wenn "
                + "der Anbieter sie erlaubt.");
        final int padding = (int) (12 * getResources().getDisplayMetrics().density);
        help.setPadding(padding, padding, padding, padding);
        root.addView(help);

        final Button external = new Button(this);
        external.setAllCaps(false);
        external.setText("Seite im normalen Browser öffnen");
        external.setOnClickListener(v ->
                ShareUtils.openUrlInBrowser(EpisodeHostActivity.this, episodeUrl));
        root.addView(external);

        browser = new WebView(this);
        root.addView(browser, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        configureWebView(browser);
        browser.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(final WebView view,
                                                    final WebResourceRequest request) {
                if (!request.isForMainFrame()) {
                    return false;
                }
                return handleNavigation(request.getUrl().toString(), request.hasGesture());
            }

            @Override
            public void onPageStarted(final WebView view, final String url,
                                      final android.graphics.Bitmap favicon) {
                if (LinkQueueLinks.isVoeUrl(url)) {
                    handOffToVoe(url);
                }
            }
        });
        browser.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(final WebView view, final boolean isDialog,
                                          final boolean isUserGesture,
                                          final Message resultMsg) {
                if (!isUserGesture || resultMsg == null || popupBrowsers.size() >= 3) {
                    return false;
                }
                final WebView popup = new WebView(EpisodeHostActivity.this);
                configureWebView(popup);
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(final WebView v,
                                                            final WebResourceRequest request) {
                        if (!request.isForMainFrame()) {
                            return false;
                        }
                        return handleNavigation(request.getUrl().toString(),
                                request.hasGesture());
                    }

                    @Override
                    public void onPageStarted(final WebView v, final String url,
                                              final android.graphics.Bitmap favicon) {
                        if (LinkQueueLinks.isVoeUrl(url)) {
                            handOffToVoe(url);
                        }
                    }
                });
                popupBrowsers.add(popup);
                final WebView.WebViewTransport transport =
                        (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });

        if (savedInstanceState == null || browser.restoreState(savedInstanceState) == null) {
            browser.loadUrl(episodeUrl);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void configureWebView(@NonNull final WebView view) {
        final WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
    }

    private boolean handleNavigation(@Nullable final String url,
                                     final boolean userGesture) {
        if (LinkQueueLinks.isVoeUrl(url)) {
            handOffToVoe(url);
            return true;
        }
        if (LinkQueueLinks.isEpisodeSiteUrl(url)) {
            return false;
        }
        // Only a deliberate tap may open a different host, and only after confirmation.
        if (userGesture && LinkQueueLinks.isSafeHttps(url)) {
            new AlertDialog.Builder(this)
                    .setTitle("Externe Webseite")
                    .setMessage("Dieser Link führt nicht direkt zu VOE. "
                            + "Möchtest du ihn im normalen Browser öffnen?\n" + url)
                    .setPositiveButton("Öffnen", (dialog, which) ->
                            ShareUtils.openUrlInBrowser(this, url))
                    .setNegativeButton("Abbrechen", null)
                    .show();
        }
        return true;
    }

    private void handOffToVoe(@Nullable final String url) {
        if (handedOff || !LinkQueueLinks.isVoeUrl(url) || isFinishing()) {
            return;
        }
        handedOff = true;
        startActivity(new Intent(this, VoeBrowserActivity.class)
                .putExtra(VoeBrowserActivity.EXTRA_VOE_URL, url));
        finish();
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
            browser.destroy();
            browser = null;
        }
        for (final WebView popup : popupBrowsers) {
            popup.destroy();
        }
        popupBrowsers.clear();
        super.onDestroy();
    }
}
