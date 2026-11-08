/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.schabi.newpipe.util.ThemeHelper;
import org.schabi.newpipe.util.external_communication.ShareUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Link queue for pasted and shared URLs. The system download manager is used only
 * for direct, downloadable files. Episode pages are bookmarks, not media streams.
 */
public class LinkQueueActivity extends AppCompatActivity {
    public static final String EXTRA_URL = "org.schabi.newpipe.download.extra.URL";

    private static final String PREFERENCES = "gold_link_queue";
    private static final String SAVED_LINKS = "links";
    private static final String SAVED_WIFI_ONLY = "wifi_only";
    private static final int MAX_LINKS = 500;

    private final LinkedHashSet<String> links = new LinkedHashSet<>();
    private final Map<String, CheckBox> checks = new LinkedHashMap<>();
    private LinearLayout linkList;
    private EditText linkInput;
    private TextView status;
    private CheckBox wifiOnly;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        ThemeHelper.setTheme(this);
        super.onCreate(savedInstanceState);
        setTitle("Link-Sammlung");
        restoreLinks();
        buildView();
        if (savedInstanceState == null) {
            final String sharedUrl = getIntent().getStringExtra(EXTRA_URL);
            if (sharedUrl != null) {
                addLinks(sharedUrl);
            }
        }
        refreshList();
    }

    private int dp(final int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(final String value) {
        final TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(15);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private Button button(final String value, final View.OnClickListener action) {
        final Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setOnClickListener(action);
        button.setMinHeight(dp(48));
        return button;
    }

    private void addRow(final LinearLayout parent, final Button first,
                        final Button second) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(0, dp(54), 1);
        row.addView(first, p);
        row.addView(second, new LinearLayout.LayoutParams(0, dp(54), 1));
        parent.addView(row);
    }

    private void buildView() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        final TextView heading = text("Videolinks und Episoden sammeln");
        heading.setTextSize(21);
        root.addView(heading);
        root.addView(text("Mehrere HTTPS-Links einfügen oder mit NewPipe teilen. "
                + "Episodenseiten werden als Links gespeichert. "
                + "Direkte MP4-, WebM- und Audiodateien können heruntergeladen werden."));

        linkInput = new EditText(this);
        linkInput.setHint("https://... (auch mehrere Links auf einmal)");
        linkInput.setMinLines(3);
        linkInput.setMaxLines(6);
        linkInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(linkInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(button("Links hinzufügen", view -> {
            addLinks(linkInput.getText().toString());
            linkInput.setText("");
        }));

        wifiOnly = new CheckBox(this);
        wifiOnly.setText("Nur über WLAN herunterladen");
        wifiOnly.setChecked(getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getBoolean(SAVED_WIFI_ONLY, false));
        wifiOnly.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                        .putBoolean(SAVED_WIFI_ONLY, checked).apply());
        root.addView(wifiOnly);

        addRow(root, button("Alle markieren", view -> setSelected(true)),
                button("Keine markieren", view -> setSelected(false)));
        addRow(root, button("Auswahl herunterladen", view -> downloadSelected()),
                button("Im Browser öffnen", view -> openSelected()));
        root.addView(button("VOE • offiziellen Download öffnen",
                view -> openVoeSelected()));
        addRow(root, button("Auswahl entfernen", view -> removeSelected()),
                button("Systemdownloads", view -> openSystemDownloads()));

        status = text("");
        root.addView(status);
        linkList = new LinearLayout(this);
        linkList.setOrientation(LinearLayout.VERTICAL);
        root.addView(linkList);
    }

    private void restoreLinks() {
        final String raw = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getString(SAVED_LINKS, "[]");
        try {
            final JSONArray stored = new JSONArray(raw);
            for (int i = 0; i < stored.length() && links.size() < MAX_LINKS; i++) {
                final String url = stored.optString(i);
                if (LinkQueueLinks.isSafeHttps(url)) {
                    links.add(url);
                }
            }
        } catch (final JSONException ignored) {
            // Corrupt local queue must not prevent the rest of NewPipe from starting.
        }
    }

    private void saveLinks() {
        final JSONArray data = new JSONArray();
        for (final String url : links) {
            data.put(url);
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(SAVED_LINKS, data.toString()).apply();
    }

    private void addLinks(final String pasted) {
        int added = 0;
        for (final String url : LinkQueueLinks.extractLinks(pasted)) {
            if (links.size() >= MAX_LINKS) {
                break;
            }
            if (links.add(url)) {
                added++;
            }
        }
        if (added == 0 && !pasted.trim().isEmpty()) {
            Toast.makeText(this, "Kein neuer gültiger HTTPS-Link gefunden",
                    Toast.LENGTH_LONG).show();
        }
        saveLinks();
        refreshList();
    }

    private void refreshList() {
        if (linkList == null) {
            return;
        }
        linkList.removeAllViews();
        checks.clear();
        int direct = 0;
        for (final String url : links) {
            if (LinkQueueLinks.isDirectMedia(url)) {
                direct++;
            }
            final CheckBox check = new CheckBox(this);
            check.setText(LinkQueueLinks.describe(url) + "\n" + url);
            check.setTextSize(14);
            check.setPadding(0, dp(5), 0, dp(5));
            check.setMinHeight(dp(56));
            linkList.addView(check);
            checks.put(url, check);
        }
        status.setText(links.size() + " Links gespeichert • " + direct
                + " direkte Mediendateien\n"
                + "Webseiten und geschützte Streams sind nicht direkt herunterladbar.");
    }

    private List<String> selectedLinks() {
        final List<String> selected = new ArrayList<>();
        for (final Map.Entry<String, CheckBox> entry : checks.entrySet()) {
            if (entry.getValue().isChecked()) {
                selected.add(entry.getKey());
            }
        }
        return selected;
    }

    private void setSelected(final boolean selected) {
        for (final CheckBox box : checks.values()) {
            box.setChecked(selected);
        }
    }

    private void removeSelected() {
        final List<String> selected = selectedLinks();
        if (selected.isEmpty()) {
            Toast.makeText(this, "Zuerst Links markieren", Toast.LENGTH_SHORT).show();
            return;
        }
        links.removeAll(selected);
        saveLinks();
        refreshList();
    }

    private void openSelected() {
        final List<String> selected = selectedLinks();
        if (selected.size() != 1) {
            Toast.makeText(this, "Bitte genau einen Link markieren",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        ShareUtils.openUrlInBrowser(this, selected.get(0));
    }

    private void openVoeSelected() {
        final List<String> selected = selectedLinks();
        if (selected.size() != 1 || !LinkQueueLinks.isVoeUrl(selected.get(0))) {
            Toast.makeText(this, "Bitte genau einen VOE-Link markieren",
                    Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, VoeBrowserActivity.class)
                .putExtra(VoeBrowserActivity.EXTRA_VOE_URL, selected.get(0)));
    }

    private String filename(final String url, final int index) {
        String name = Uri.parse(url).getLastPathSegment();
        if (name == null || name.isEmpty()) {
            name = "video.mp4";
        }
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.length() > 96) {
            name = name.substring(name.length() - 96);
        }
        if (name.startsWith(".")) {
            name = "video" + name;
        }
        return System.currentTimeMillis() + "-" + index + "-" + name;
    }

    private void downloadSelected() {
        final List<String> selected = selectedLinks();
        if (selected.isEmpty()) {
            Toast.makeText(this, "Zuerst Links markieren", Toast.LENGTH_SHORT).show();
            return;
        }
        final DownloadManager service =
                (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (service == null) {
            Toast.makeText(this, "Android-Downloadmanager nicht verfügbar",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int queued = 0;
        int unsupported = 0;
        for (final String url : selected) {
            if (!LinkQueueLinks.isDirectMedia(url)) {
                unsupported++;
                continue;
            }
            try {
                final DownloadManager.Request request = new DownloadManager.Request(
                        Uri.parse(url));
                request.setTitle(LinkQueueLinks.describe(url));
                request.setDescription("NewPipe Gold • direkter Mediendownload");
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setAllowedOverMetered(!wifiOnly.isChecked());
                request.setAllowedOverRoaming(false);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        "NewPipeGold/" + filename(url, queued));
                service.enqueue(request);
                queued++;
            } catch (final SecurityException | IllegalArgumentException
                    | IllegalStateException e) {
                Toast.makeText(this, "Download konnte nicht gestartet werden: "
                        + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            }
        }
        Toast.makeText(this, queued + " Downloads gestartet; " + unsupported
                + " Webseiten/andere Links übersprungen", Toast.LENGTH_LONG).show();
    }

    private void openSystemDownloads() {
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(this, "Keine Downloadübersicht verfügbar",
                    Toast.LENGTH_SHORT).show();
        }
    }
}
