/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download;

import static org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP;
import static org.schabi.newpipe.util.ListHelper.getStreamsOfSpecifiedDelivery;

import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Build;
import android.net.NetworkCapabilities;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import androidx.core.content.ContextCompat;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.settings.NewPipeSettings;
import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.EnergySaver;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.FilenameUtils;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.SecondaryStreamHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import us.shandian.giga.get.MissionRecoveryInfo;
import us.shandian.giga.postprocessing.Postprocessing;
import us.shandian.giga.service.DownloadManager;
import us.shandian.giga.service.DownloadManagerService;
import us.shandian.giga.service.DownloadManagerService.DownloadManagerBinder;
import us.shandian.giga.service.MissionState;

/**
 * Queues selected video or audio files using NewPipe's existing download manager.
 * Stream information is resolved sequentially to avoid unnecessary parallel network traffic.
 * Unlike the regular download dialog this deliberately never overwrites existing files.
 */
public final class BatchDownloadStarter implements ServiceConnection {
    private static final Object ACTIVE_LOCK = new Object();
    @Nullable
    private static BatchDownloadStarter active;
    @Nullable
    private static ConnectivityManager.NetworkCallback wifiWatcher;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable
    private DownloadManager manager;
    @Nullable
    private StoredDirectoryHelper videoStorage;
    @Nullable
    private StoredDirectoryHelper audioStorage;
    @Nullable
    private Disposable fetchSubscription;
    private boolean bound;
    private boolean finished;
    private boolean storageError;
    private int queuedInSession;

    private BatchDownloadStarter(@NonNull final Context context) {
        appContext = context.getApplicationContext();
    }

    /**
     * Persist the selected items before creating the download service, including their options.
     *
     * @param context Android context
     * @param selected user-selected videos
     * @param options audio/video, quality, language and network settings
     */
    public static void start(@NonNull final Context context,
                             @NonNull final List<StreamInfoItem> selected,
                             @NonNull final BatchDownloadStore.Options options) {
        if (selected.isEmpty()) {
            return;
        }
        BatchDownloadStore.enqueue(context, selected, options);
        resumePending(context);
    }

    /**
     * Restart any unqueued items after NewPipe Gold is reopened.
     *
     * @param context active Android context
     */
    public static void resumePending(@NonNull final Context context) {
        final Context app = context.getApplicationContext();
        synchronized (ACTIVE_LOCK) {
            if (active != null || BatchDownloadStore.nextPending(app) == null) {
                return;
            }
            if (!hasDownloadableNow(app)) {
                watchWifi(app);
                return;
            }
            stopWatchingWifi(app);
            active = new BatchDownloadStarter(app);
            active.bind();
        }
    }

    private static boolean hasDownloadableNow(@NonNull final Context context) {
        for (final BatchDownloadStore.Entry item : BatchDownloadStore.snapshot(context)) {
            if (BatchDownloadStore.PENDING.equals(item.status)
                    || BatchDownloadStore.QUEUING.equals(item.status)) {
                if (!item.wifiOnly || isWifiAvailable(context)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isWifiAvailable(@NonNull final Context context) {
        final ConnectivityManager manager =
                ContextCompat.getSystemService(context, ConnectivityManager.class);
        if (manager == null || manager.getActiveNetwork() == null) {
            return false;
        }
        final NetworkCapabilities capabilities =
                manager.getNetworkCapabilities(manager.getActiveNetwork());
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static void watchWifi(@NonNull final Context context) {
        if (wifiWatcher != null) {
            return;
        }
        final ConnectivityManager manager =
                ContextCompat.getSystemService(context, ConnectivityManager.class);
        if (manager == null) {
            return;
        }
        wifiWatcher = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull final Network network) {
                new Handler(Looper.getMainLooper()).post(() -> resumePending(context));
            }

            @Override
            public void onCapabilitiesChanged(@NonNull final Network network,
                                              @NonNull final NetworkCapabilities capabilities) {
                new Handler(Looper.getMainLooper()).post(() -> resumePending(context));
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(wifiWatcher);
            } else {
                manager.registerNetworkCallback(new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build(), wifiWatcher);
            }
        } catch (final RuntimeException ignored) {
            wifiWatcher = null;
        }
    }

    private static void stopWatchingWifi(@NonNull final Context context) {
        if (wifiWatcher == null) {
            return;
        }
        final ConnectivityManager manager =
                ContextCompat.getSystemService(context, ConnectivityManager.class);
        if (manager != null) {
            try {
                manager.unregisterNetworkCallback(wifiWatcher);
            } catch (final RuntimeException ignored) {
                // The operating system may have already released the callback.
            }
        }
        wifiWatcher = null;
    }

    private void bind() {
        final Intent intent = new Intent(appContext, DownloadManagerService.class);
        try {
            appContext.startService(intent);
            bound = appContext.bindService(intent, this, Context.BIND_AUTO_CREATE);
        } catch (final RuntimeException exception) {
            finishWithStorageError();
            return;
        }
        if (!bound) {
            finishWithStorageError();
        }
    }

    @Override
    public void onServiceConnected(final ComponentName name, final IBinder service) {
        if (finished) {
            return;
        }
        final DownloadManagerBinder binder = (DownloadManagerBinder) service;
        manager = binder.getDownloadManager();
        videoStorage = binder.getMainStorageVideo();
        audioStorage = binder.getMainStorageAudio();
        if (binder.askForSavePath()) {
            finishWithStorageError();
            return;
        }
        Toast.makeText(appContext, R.string.gold_batch_started, Toast.LENGTH_SHORT).show();
        queueAll();
    }

    @Override
    public void onServiceDisconnected(final ComponentName name) {
        manager = null;
        if (fetchSubscription != null) {
            fetchSubscription.dispose();
        }
        finish();
    }

    private void queueAll() {
        fetchSubscription = Completable.fromAction(() -> {
            for (final BatchDownloadStore.Entry item :
                    BatchDownloadStore.snapshot(appContext)) {
                if (finished) {
                    break;
                }
                if (!BatchDownloadStore.PENDING.equals(item.status)
                        && !BatchDownloadStore.QUEUING.equals(item.status)) {
                    continue;
                }
                if (item.wifiOnly && !isWifiAvailable(appContext)) {
                    continue;
                }
                if (BatchDownloadStore.isCancelled(appContext, item)) {
                    continue;
                }
                BatchDownloadStore.update(appContext, item, BatchDownloadStore.QUEUING, null);
                try {
                    final StreamInfo stream = ExtractorHelper.getStreamInfo(
                            item.serviceId, item.url, false).blockingGet();
                    if (BatchDownloadStore.isCancelled(appContext, item)) {
                        continue;
                    }
                    final String error = queueOne(stream, item);
                    if (error == null) {
                        queuedInSession++;
                        BatchDownloadStore.update(appContext, item,
                                BatchDownloadStore.QUEUED, null);
                    } else if ("Wi-Fi not connected".equals(error)) {
                        // Keep this item pending instead of marking a network change as failure.
                        BatchDownloadStore.update(appContext, item,
                                BatchDownloadStore.PENDING, null);
                    } else if ("File already exists".equals(error)
                            || "Already queued in download manager".equals(error)) {
                        BatchDownloadStore.update(appContext, item,
                                BatchDownloadStore.SKIPPED, error);
                    } else {
                        BatchDownloadStore.update(appContext, item,
                                BatchDownloadStore.FAILED, error);
                    }
                } catch (final Exception exception) {
                    BatchDownloadStore.update(appContext, item,
                            BatchDownloadStore.FAILED, exception.getClass().getSimpleName());
                }
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::finish, error -> finish());
    }

    @Nullable
    private String queueOne(@NonNull final StreamInfo info,
                            @NonNull final BatchDownloadStore.Entry item) {
        try {
            final DownloadSpec spec = item.audioOnly
                    ? audioSpec(info, item) : videoSpec(info, item);
            final StoredDirectoryHelper directory =
                    item.audioOnly ? audioStorage : videoStorage;
            final DownloadManager downloads = manager;
            if (spec == null) {
                return "No compatible audio/video stream";
            }
            if (directory == null
                    || directory.isInvalidSafStorage()
                    || directory.isDirect() == NewPipeSettings.useStorageAccessFramework(appContext)
                    || downloads == null || !directory.mkdirs()) {
                return "Download folder unavailable";
            }
            if (item.wifiOnly && !isWifiAvailable(appContext)) {
                return "Wi-Fi not connected";
            }
            if (item.wifiOnly) {
                // The underlying download manager applies metered network rules globally.
                // The option explicitly warns that this affects other downloads, too.
                PreferenceManager.getDefaultSharedPreferences(appContext).edit()
                        .putBoolean(appContext.getString(R.string.downloads_cross_network), false)
                        .apply();
            }
            final String cleanTitle = FilenameUtils.createFilename(appContext, info.getName());
            final String shortened = cleanTitle.length() > 110
                    ? cleanTitle.substring(0, 110) : cleanTitle;
            final String base = shortened.isBlank() ? "video" : shortened;
            final String name = base + "_" + item.serviceId + "_"
                    + Integer.toHexString(info.getUrl().hashCode()) + "." + spec.extension;
            if (directory.findFile(name) != null) {
                return "File already exists";
            }
            final StoredFileHelper target = new StoredFileHelper(
                    directory.getUri(), name, spec.mime, directory.getTag());
            if (downloads.checkForExistingMission(target) != MissionState.None) {
                return "Already queued in download manager";
            }
            final StoredFileHelper file = directory.createFile(name, spec.mime);
            if (file == null || !file.canWrite()) {
                return "Cannot create download file";
            }
            final int threads = PreferenceManager.getDefaultSharedPreferences(appContext)
                    .getInt(appContext.getString(R.string.default_download_threads), 3);
            DownloadManagerService.startMission(appContext, spec.urls, file, spec.kind,
                    Math.max(1, threads), info, spec.postprocessing, null, 0,
                    spec.recovery);
            return null;
        } catch (final Exception exception) {
            return exception.getClass().getSimpleName();
        }
    }

    @Nullable
    private DownloadSpec audioSpec(@NonNull final StreamInfo info,
                                   @NonNull final BatchDownloadStore.Entry item) {
        final List<AudioStream> streams = ListHelper.getFilteredAudioStreams(appContext,
                getStreamsOfSpecifiedDelivery(info.getAudioStreams(), PROGRESSIVE_HTTP));
        if (streams.isEmpty()) {
            return null;
        }
        final AudioStream chosen = selectAudioStream(streams, item.audioTrack);
        if (chosen == null) {
            return null;
        }
        final AudioStream selected = chosen;
        if (selected.getContent() == null || selected.getFormat() == null) {
            return null;
        }
        final MediaFormat format = selected.getFormat();
        final String postprocessing;
        final String extension;
        final String mime;
        if (format == MediaFormat.WEBMA_OPUS) {
            extension = "opus";
            mime = "audio/ogg";
            postprocessing = Postprocessing.ALGORITHM_OGG_FROM_WEBM_DEMUXER;
        } else {
            extension = format.getSuffix();
            mime = format.mimeType;
            postprocessing = format == MediaFormat.M4A
                    ? Postprocessing.ALGORITHM_M4A_NO_DASH : null;
        }
        return new DownloadSpec(new String[]{selected.getContent()}, 'a',
                extension, mime, postprocessing,
                new ArrayList<>(List.of(new MissionRecoveryInfo(selected))));
    }

    @Nullable
    private DownloadSpec videoSpec(@NonNull final StreamInfo info,
                                   @NonNull final BatchDownloadStore.Entry item) {
        final List<VideoStream> streams = ListHelper.getSortedStreamVideosList(appContext,
                getStreamsOfSpecifiedDelivery(info.getVideoStreams(), PROGRESSIVE_HTTP),
                getStreamsOfSpecifiedDelivery(info.getVideoOnlyStreams(), PROGRESSIVE_HTTP),
                false, !BatchDownloadStore.AUDIO_EMBEDDED.equals(item.audioTrack));
        if (streams.isEmpty()) {
            return null;
        }
        final String resolution = item.quality.isBlank()
                ? (EnergySaver.isEnabled(appContext) ? "480p" : "") : item.quality;
        final int index = resolution.isBlank()
                ? ListHelper.getDefaultResolutionIndex(appContext, streams)
                : ListHelper.getResolutionIndex(appContext, streams, resolution);
        if (index < 0 || index >= streams.size()) {
            return null;
        }
        VideoStream video = streams.get(index);
        final List<AudioStream> audioStreams = ListHelper.getFilteredAudioStreams(appContext,
                getStreamsOfSpecifiedDelivery(info.getAudioStreams(), PROGRESSIVE_HTTP));
        AudioStream audio = null;
        if (BatchDownloadStore.AUDIO_EMBEDDED.equals(item.audioTrack)) {
            video = pickVideoOfType(streams, false, resolution, video);
        } else if (!BatchDownloadStore.AUDIO_AUTO.equals(item.audioTrack)) {
            video = pickVideoOfType(streams, true, resolution, video);
        }
        if (BatchDownloadStore.AUDIO_DUBBED.equals(item.audioTrack)
                && !video.isVideoOnly()) {
            // Dubbed audio cannot be selected from a combined video-only original soundtrack.
            return null;
        }
        if (video.isVideoOnly()) {
            audio = selectAudioStream(audioStreams, item.audioTrack);
            if (audio != null && !compatibleAudio(video, audio)) {
                // Never mix incompatible audio/video containers.
                final AudioStream compatible = SecondaryStreamHelper.getAudioStreamFor(
                        appContext, audioStreams, video);
                if (compatible != null
                        && (BatchDownloadStore.AUDIO_AUTO.equals(item.audioTrack)
                        || compatible.getAudioTrackId() != null
                        && compatible.getAudioTrackId().equals(audio.getAudioTrackId()))) {
                    audio = compatible;
                } else {
                    audio = null;
                }
            }
            if (audio == null) {
                // Prefer a lower quality combined video over downloading a silent file.
                for (final VideoStream candidate : streams) {
                    if (!candidate.isVideoOnly()) {
                        video = candidate;
                        break;
                    }
                }
                if (video.isVideoOnly()) {
                    return null;
                }
                if (BatchDownloadStore.AUDIO_DUBBED.equals(item.audioTrack)) {
                    // Never silently replace an explicitly selected dub with original audio.
                    return null;
                }
            }
        }
        if (video.getContent() == null || video.getFormat() == null) {
            return null;
        }
        final ArrayList<MissionRecoveryInfo> recovery = new ArrayList<>();
        recovery.add(new MissionRecoveryInfo(video));
        String processing = null;
        final String[] urls;
        if (audio != null) {
            recovery.add(new MissionRecoveryInfo(audio));
            urls = new String[]{video.getContent(), audio.getContent()};
            processing = video.getFormat() == MediaFormat.MPEG_4
                    ? Postprocessing.ALGORITHM_MP4_FROM_DASH_MUXER
                    : Postprocessing.ALGORITHM_WEBM_MUXER;
        } else {
            urls = new String[]{video.getContent()};
        }
        return new DownloadSpec(urls, 'v', video.getFormat().getSuffix(),
                video.getFormat().mimeType, processing, recovery);
    }

    private VideoStream pickVideoOfType(@NonNull final List<VideoStream> streams,
                                        final boolean videoOnly,
                                        @NonNull final String resolution,
                                        @NonNull final VideoStream fallback) {
        final List<VideoStream> candidates = new ArrayList<>();
        for (final VideoStream stream : streams) {
            if (stream.isVideoOnly() == videoOnly) {
                candidates.add(stream);
            }
        }
        if (candidates.isEmpty()) {
            return fallback;
        }
        final int idx = resolution.isBlank()
                ? ListHelper.getDefaultResolutionIndex(appContext, candidates)
                : ListHelper.getResolutionIndex(appContext, candidates, resolution);
        return idx >= 0 && idx < candidates.size() ? candidates.get(idx) : fallback;
    }

    private boolean compatibleAudio(@NonNull final VideoStream video,
                                    @NonNull final AudioStream audio) {
        if (video.getFormat() == MediaFormat.WEBM) {
            return audio.getFormat() == MediaFormat.WEBMA
                    || audio.getFormat() == MediaFormat.WEBMA_OPUS;
        }
        return video.getFormat() == MediaFormat.MPEG_4
                && audio.getFormat() == MediaFormat.M4A;
    }

    @Nullable
    private AudioStream selectAudioStream(@NonNull final List<AudioStream> streams,
                                          @NonNull final String preference) {
        if (streams.isEmpty()) {
            return null;
        }
        final List<AudioStream> matches = new ArrayList<>();
        for (final AudioStream stream : streams) {
            if (stream.getAudioLocale() == null
                    || !Locale.GERMAN.getLanguage().equals(
                            stream.getAudioLocale().getLanguage())) {
                continue;
            }
            if (BatchDownloadStore.AUDIO_ORIGINAL.equals(preference)
                    && stream.getAudioTrackType() == AudioTrackType.ORIGINAL
                    || BatchDownloadStore.AUDIO_DUBBED.equals(preference)
                    && stream.getAudioTrackType() == AudioTrackType.DUBBED) {
                matches.add(stream);
            }
        }
        if (matches.isEmpty() && (BatchDownloadStore.AUDIO_ORIGINAL.equals(preference)
                || BatchDownloadStore.AUDIO_DUBBED.equals(preference))) {
            return null;
        }
        final List<AudioStream> candidates = matches.isEmpty() ? streams : matches;
        final int index = ListHelper.getDefaultAudioFormat(appContext, candidates);
        return index >= 0 && index < candidates.size() ? candidates.get(index) : null;
    }

    private void finishWithStorageError() {
        if (finished) {
            return;
        }
        finished = true;
        storageError = true;
        for (final BatchDownloadStore.Entry item : BatchDownloadStore.snapshot(appContext)) {
            if (BatchDownloadStore.PENDING.equals(item.status)
                    || BatchDownloadStore.QUEUING.equals(item.status)) {
                BatchDownloadStore.update(appContext, item, BatchDownloadStore.FAILED,
                        "Download folder unavailable");
            }
        }
        mainHandler.post(() -> {
            Toast.makeText(appContext, R.string.gold_batch_storage_error,
                    Toast.LENGTH_LONG).show();
            finishBinding();
        });
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        mainHandler.post(() -> {
            Toast.makeText(appContext, appContext.getString(R.string.gold_batch_result,
                    queuedInSession, BatchDownloadStore.snapshot(appContext).size(),
                    BatchDownloadStore.count(appContext, BatchDownloadStore.FAILED)),
                    Toast.LENGTH_LONG).show();
            finishBinding();
        });
    }

    private void finishBinding() {
        if (bound) {
            bound = false;
            appContext.unbindService(this);
        }
        synchronized (ACTIVE_LOCK) {
            if (active == this) {
                active = null;
            }
        }
        if (!storageError && BatchDownloadStore.nextPending(appContext) != null) {
            if (hasDownloadableNow(appContext)) {
                resumePending(appContext);
            } else {
                watchWifi(appContext);
            }
        } else {
            stopWatchingWifi(appContext);
        }
    }

    private static final class DownloadSpec {
        private final String[] urls;
        private final char kind;
        private final String extension;
        private final String mime;
        @Nullable
        private final String postprocessing;
        private final ArrayList<MissionRecoveryInfo> recovery;

        private DownloadSpec(final String[] urls, final char kind, final String extension,
                             final String mime, @Nullable final String postprocessing,
                             final ArrayList<MissionRecoveryInfo> recovery) {
            this.urls = urls;
            this.kind = kind;
            this.extension = extension;
            this.mime = mime;
            this.postprocessing = postprocessing;
            this.recovery = recovery;
        }
    }
}
