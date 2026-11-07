/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download;

import static org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP;
import static org.schabi.newpipe.util.ListHelper.getStreamsOfSpecifiedDelivery;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
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

import io.reactivex.rxjava3.core.Observable;
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
    private final Context appContext;
    private final List<StreamInfoItem> items;
    private final boolean audioOnly;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable
    private DownloadManager manager;
    @Nullable
    private StoredDirectoryHelper storage;
    @Nullable
    private Disposable fetchSubscription;
    private boolean bound;
    private boolean finished;
    private int queued;

    private BatchDownloadStarter(@NonNull final Context context,
                                 @NonNull final List<StreamInfoItem> selected,
                                 final boolean audioOnly) {
        appContext = context.getApplicationContext();
        items = new ArrayList<>(selected);
        this.audioOnly = audioOnly;
    }

    /**
     * Start one batch. A service binding keeps the batch alive while the app is in memory.
     *
     * @param context calling Android context
     * @param selected selected streams to enqueue
     * @param audioOnly download audio instead of video
     */
    public static void start(@NonNull final Context context,
                             @NonNull final List<StreamInfoItem> selected,
                             final boolean audioOnly) {
        if (selected.isEmpty()) {
            return;
        }
        new BatchDownloadStarter(context, selected, audioOnly).bind();
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
        storage = audioOnly ? binder.getMainStorageAudio() : binder.getMainStorageVideo();
        if (binder.askForSavePath() || storage == null
                || storage.isInvalidSafStorage()
                || storage.isDirect() == NewPipeSettings.useStorageAccessFramework(appContext)) {
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
        fetchSubscription = Observable.fromIterable(items)
                .concatMapSingle(item -> ExtractorHelper.getStreamInfo(
                        item.getServiceId(), item.getUrl(), false)
                        .subscribeOn(Schedulers.io())
                        .map(info -> new FetchResult(info))
                        .onErrorReturn(error -> new FetchResult(null)))
                .observeOn(Schedulers.io())
                .subscribe(result -> {
                    if (result.info != null && queueOne(result.info)) {
                        queued++;
                    }
                }, error -> finish(), this::finish);
    }

    private boolean queueOne(@NonNull final StreamInfo info) {
        try {
            final DownloadSpec spec = audioOnly ? audioSpec(info) : videoSpec(info);
            final StoredDirectoryHelper directory = storage;
            final DownloadManager downloads = manager;
            if (spec == null || directory == null || downloads == null
                    || !directory.mkdirs()) {
                return false;
            }
            final String cleanTitle = FilenameUtils.createFilename(appContext, info.getName());
            final String shortened = cleanTitle.length() > 110
                    ? cleanTitle.substring(0, 110) : cleanTitle;
            final String base = shortened.isBlank() ? "video" : shortened;
            final String name = base + "_" + Integer.toHexString(info.getUrl().hashCode())
                    + "." + spec.extension;
            if (directory.findFile(name) != null) {
                return false;
            }
            final StoredFileHelper target = new StoredFileHelper(
                    directory.getUri(), name, spec.mime, directory.getTag());
            if (downloads.checkForExistingMission(target) != MissionState.None) {
                return false;
            }
            final StoredFileHelper file = directory.createFile(name, spec.mime);
            if (file == null || !file.canWrite()) {
                return false;
            }
            final int threads = PreferenceManager.getDefaultSharedPreferences(appContext)
                    .getInt(appContext.getString(R.string.default_download_threads), 3);
            DownloadManagerService.startMission(appContext, spec.urls, file, spec.kind,
                    Math.max(1, threads), info, spec.postprocessing, null, 0,
                    spec.recovery);
            return true;
        } catch (final Exception exception) {
            // A single unavailable or invalid stream must not interrupt the remaining batch.
            return false;
        }
    }

    @Nullable
    private DownloadSpec audioSpec(@NonNull final StreamInfo info) {
        final List<AudioStream> streams = ListHelper.getFilteredAudioStreams(appContext,
                getStreamsOfSpecifiedDelivery(info.getAudioStreams(), PROGRESSIVE_HTTP));
        if (streams.isEmpty()) {
            return null;
        }
        final int index = ListHelper.getDefaultAudioFormat(appContext, streams);
        if (index < 0 || index >= streams.size()) {
            return null;
        }
        final AudioStream selected = streams.get(index);
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
    private DownloadSpec videoSpec(@NonNull final StreamInfo info) {
        final List<VideoStream> streams = ListHelper.getSortedStreamVideosList(appContext,
                getStreamsOfSpecifiedDelivery(info.getVideoStreams(), PROGRESSIVE_HTTP),
                getStreamsOfSpecifiedDelivery(info.getVideoOnlyStreams(), PROGRESSIVE_HTTP),
                false, true);
        if (streams.isEmpty()) {
            return null;
        }
        final int index = EnergySaver.isEnabled(appContext)
                ? ListHelper.getResolutionIndex(appContext, streams, "480p")
                : ListHelper.getDefaultResolutionIndex(appContext, streams);
        if (index < 0 || index >= streams.size()) {
            return null;
        }
        VideoStream video = streams.get(index);
        AudioStream audio = null;
        if (video.isVideoOnly()) {
            final List<AudioStream> audioStreams = ListHelper.getFilteredAudioStreams(appContext,
                    getStreamsOfSpecifiedDelivery(info.getAudioStreams(), PROGRESSIVE_HTTP));
            audio = SecondaryStreamHelper.getAudioStreamFor(appContext, audioStreams, video);
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

    private void finishWithStorageError() {
        finished = true;
        mainHandler.post(() -> {
            Toast.makeText(appContext, R.string.gold_batch_storage_error,
                    Toast.LENGTH_LONG).show();
            unbind();
        });
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        mainHandler.post(() -> {
            Toast.makeText(appContext, appContext.getString(R.string.gold_batch_result,
                    queued, items.size(), items.size() - queued), Toast.LENGTH_LONG).show();
            unbind();
        });
    }

    private void unbind() {
        if (bound) {
            bound = false;
            appContext.unbindService(this);
        }
    }

    private static final class FetchResult {
        @Nullable
        private final StreamInfo info;

        private FetchResult(@Nullable final StreamInfo info) {
            this.info = info;
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
