/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persistent NewPipe Gold batch queue. Each entry is written to local app storage as it changes,
 * so unqueued streams are recoverable after a process restart without deleting app data.
 */
public final class BatchDownloadStore {
    public static final String PENDING = "pending";
    public static final String QUEUING = "queuing";
    public static final String QUEUED = "queued";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";
    public static final String CANCELLED = "cancelled";
    public static final String AUDIO_AUTO = "auto";
    public static final String AUDIO_ORIGINAL = "de-original";
    public static final String AUDIO_DUBBED = "de-dubbed";
    public static final String AUDIO_EMBEDDED = "video-original";

    private static final String PREFERENCES = "newpipe_gold_batch_downloads";
    private static final String DATA_KEY = "batch_items_v1";
    private static final int MAX_HISTORY = 250;
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> LISTENERS =
            new CopyOnWriteArrayList<>();

    private BatchDownloadStore() { }

    /** One queued item, with its individual download settings and last known status. */
    public static final class Entry {
        public int serviceId;
        public String url;
        public String title;
        public boolean audioOnly;
        public boolean wifiOnly;
        public String quality;
        public String audioTrack;
        public String status;
        public String error;

        Entry() { }

        @NonNull
        String key() {
            return serviceId + ":" + url + ":" + audioOnly;
        }

        JSONObject toJson() throws JSONException {
            final JSONObject j = new JSONObject();
            j.put("service", serviceId);
            j.put("url", url);
            j.put("title", title);
            j.put("audio", audioOnly);
            j.put("wifi", wifiOnly);
            j.put("quality", quality);
            j.put("track", audioTrack);
            j.put("status", status);
            j.put("error", error);
            return j;
        }

        static Entry fromJson(@NonNull final JSONObject j) {
            final Entry e = new Entry();
            e.serviceId = j.optInt("service", -1);
            e.url = j.optString("url", "");
            e.title = j.optString("title", "");
            e.audioOnly = j.optBoolean("audio", false);
            e.wifiOnly = j.optBoolean("wifi", true);
            e.quality = j.optString("quality", "");
            e.audioTrack = j.optString("track", AUDIO_AUTO);
            e.status = j.optString("status", PENDING);
            e.error = j.optString("error", "");
            return e;
        }
    }

    /** Audio/video, resolution, track and network choices for new queue entries. */
    public static final class Options {
        public final boolean audioOnly;
        public final String quality;
        public final String track;
        public final boolean wifiOnly;

        public Options(final boolean audioOnly, @NonNull final String quality,
                       @NonNull final String track, final boolean wifiOnly) {
            this.audioOnly = audioOnly;
            this.quality = quality;
            this.track = track;
            this.wifiOnly = wifiOnly;
        }
    }

    private static SharedPreferences prefs(@NonNull final Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE);
    }

    private static List<Entry> load(@NonNull final Context context) {
        final List<Entry> result = new ArrayList<>();
        final String json = prefs(context).getString(DATA_KEY, "[]");
        try {
            final JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject object = arr.optJSONObject(i);
                if (object != null) {
                    final Entry e = Entry.fromJson(object);
                    if (!e.url.isBlank() && e.serviceId >= 0) {
                        result.add(e);
                    }
                }
            }
        } catch (final JSONException ignored) {
            // Ignore unreadable queue entries; the download manager keeps already queued files.
        }
        return result;
    }

    private static void save(@NonNull final Context context, @NonNull final List<Entry> entries) {
        final JSONArray array = new JSONArray();
        final int start = Math.max(0, entries.size() - MAX_HISTORY);
        for (int i = start; i < entries.size(); i++) {
            try {
                array.put(entries.get(i).toJson());
            } catch (final JSONException ignored) {
                // Only a malformed entry is lost; the remaining queue is still saved.
            }
        }
        prefs(context).edit().putString(DATA_KEY, array.toString()).commit();
        MAIN.post(() -> {
            for (final Runnable listener : LISTENERS) {
                listener.run();
            }
        });
    }

    public static void addListener(@NonNull final Runnable listener) {
        LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(@NonNull final Runnable listener) {
        LISTENERS.remove(listener);
    }

    @NonNull
    public static List<Entry> snapshot(@NonNull final Context context) {
        synchronized (LOCK) {
            return load(context);
        }
    }

    public static int enqueue(@NonNull final Context context,
                              @NonNull final List<StreamInfoItem> items,
                              @NonNull final Options options) {
        synchronized (LOCK) {
            final List<Entry> existing = load(context);
            int added = 0;
            for (final StreamInfoItem item : items) {
                final Entry entry = new Entry();
                entry.serviceId = item.getServiceId();
                entry.url = item.getUrl();
                entry.title = item.getName();
                entry.audioOnly = options.audioOnly;
                entry.quality = options.quality;
                entry.audioTrack = options.track;
                entry.wifiOnly = options.wifiOnly;
                entry.status = PENDING;
                entry.error = "";
                boolean duplicate = false;
                for (final Entry old : existing) {
                    if (old.key().equals(entry.key())
                            && !CANCELLED.equals(old.status)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    existing.add(entry);
                    added++;
                }
            }
            if (added > 0) {
                save(context, existing);
            }
            return added;
        }
    }

    @Nullable
    public static Entry nextPending(@NonNull final Context context) {
        synchronized (LOCK) {
            for (final Entry e : load(context)) {
                if (PENDING.equals(e.status) || QUEUING.equals(e.status)) {
                    return e;
                }
            }
            return null;
        }
    }

    public static void update(@NonNull final Context context, @NonNull final Entry item,
                              @NonNull final String status, @Nullable final String error) {
        synchronized (LOCK) {
            final List<Entry> entries = load(context);
            for (final Entry e : entries) {
                if (e.key().equals(item.key())) {
                    // A cancelled item must not be resurrected by an in-flight network response.
                    if (CANCELLED.equals(e.status)) {
                        return;
                    }
                    e.status = status;
                    e.error = error == null ? "" : error;
                    save(context, entries);
                    return;
                }
            }
        }
    }

    public static boolean isCancelled(@NonNull final Context context,
                                      @NonNull final Entry item) {
        synchronized (LOCK) {
            for (final Entry e : load(context)) {
                if (e.key().equals(item.key())) {
                    return CANCELLED.equals(e.status);
                }
            }
            return true;
        }
    }

    public static void removePending(@NonNull final Context context,
                                     @NonNull final Entry item) {
        synchronized (LOCK) {
            final List<Entry> entries = load(context);
            entries.removeIf(e -> e.key().equals(item.key()) && !QUEUED.equals(e.status));
            save(context, entries);
        }
    }

    public static void cancelPending(@NonNull final Context context) {
        synchronized (LOCK) {
            final List<Entry> entries = load(context);
            for (final Entry e : entries) {
                if (PENDING.equals(e.status) || QUEUING.equals(e.status)) {
                    e.status = CANCELLED;
                }
            }
            save(context, entries);
        }
    }

    public static int retryFailures(@NonNull final Context context) {
        synchronized (LOCK) {
            final List<Entry> entries = load(context);
            int retried = 0;
            for (final Entry e : entries) {
                if (FAILED.equals(e.status) || SKIPPED.equals(e.status)) {
                    e.status = PENDING;
                    e.error = "";
                    retried++;
                }
            }
            if (retried > 0) {
                save(context, entries);
            }
            return retried;
        }
    }

    public static void retry(@NonNull final Context context, @NonNull final Entry item) {
        synchronized (LOCK) {
            final List<Entry> entries = load(context);
            for (final Entry e : entries) {
                if (e.key().equals(item.key())
                        && !QUEUED.equals(e.status)) {
                    e.status = PENDING;
                    e.error = "";
                    save(context, entries);
                    return;
                }
            }
        }
    }

    public static int count(@NonNull final Context context, @NonNull final String status) {
        int count = 0;
        for (final Entry entry : snapshot(context)) {
            if (status.equals(entry.status)) {
                count++;
            }
        }
        return count;
    }
}
