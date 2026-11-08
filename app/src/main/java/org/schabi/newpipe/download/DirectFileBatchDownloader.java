/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.schabi.newpipe.util.PermissionHelper;

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Batch downloads direct, public HTTPS media files using the Android download manager.
 * No webpage extraction, hoster integration, cookie reuse, DRM access or protected streams.
 */
public final class DirectFileBatchDownloader {
    private static final int MAX_FILES = 40;
    private DirectFileBatchDownloader() { }

    /** Result counts from one user-confirmed batch. */
    public static final class Result {
        public final int queued;
        public final int skipped;
        public final boolean permissionRequired;

        private Result(final int queued, final int skipped,
                       final boolean permissionRequired) {
            this.queued = queued;
            this.skipped = skipped;
            this.permissionRequired = permissionRequired;
        }
    }

    /**
     * Check that a link points to a recognized direct file rather than an episode page.
     *
     * @param link absolute, unmodified media URL
     * @return whether this is a direct HTTPS file URL with a supported extension
     */
    public static boolean isSupportedDirectMedia(@NonNull final String link) {
        try {
            final URI uri = URI.create(link.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().isBlank()
                    && uri.getUserInfo() == null && uri.getFragment() == null
                    && mediaExtension(uri.getPath()) != null;
        } catch (final IllegalArgumentException exception) {
            return false;
        }
    }

    private static String mediaExtension(final String path) {
        if (path == null) {
            return null;
        }
        final int lastDot = path.lastIndexOf('.');
        if (lastDot < 0 || lastDot <= path.lastIndexOf('/')) {
            return null;
        }
        final String extension = path.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        switch (extension) {
            case "mp4":
            case "m4v":
            case "webm":
            case "mkv":
            case "mov":
            case "mp3":
            case "m4a":
            case "opus":
                return extension;
            default:
                return null;
        }
    }

    private static String mimeType(final String extension) {
        switch (extension) {
            case "mp4":
            case "m4v":
                return "video/mp4";
            case "webm":
                return "video/webm";
            case "mkv":
                return "video/x-matroska";
            case "mov":
                return "video/quicktime";
            case "mp3":
                return "audio/mpeg";
            case "m4a":
                return "audio/mp4";
            case "opus":
                return "audio/ogg";
            default:
                return "application/octet-stream";
        }
    }

    /**
     * Add up to 40 unique direct files to Android's download manager.
     *
     * @param activity visible activity for the legacy storage permission request
     * @param linksText newline-separated direct HTTPS media links
     * @return counts and whether the user must grant legacy storage access first
     */
    @NonNull
    public static Result enqueue(@NonNull final Activity activity,
                                 @NonNull final String linksText) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE);
            return new Result(0, 0, true);
        }
        final DownloadManager manager = (DownloadManager) activity.getSystemService(
                Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            return new Result(0, 1, false);
        }
        int queued = 0;
        int skipped = 0;
        final Set<String> seen = new HashSet<>();
        for (final String line : linksText.split("\\R")) {
            final String url = line.trim();
            if (url.isEmpty()) {
                continue;
            }
            if (queued + skipped >= MAX_FILES) {
                skipped++;
                continue;
            }
            if (!seen.add(url) || !isSupportedDirectMedia(url)) {
                skipped++;
                continue;
            }
            try {
                final URI parsed = URI.create(url);
                final String ext = mediaExtension(parsed.getPath());
                final String path = parsed.getPath();
                final String last = path.substring(path.lastIndexOf('/') + 1);
                final String title = last.substring(0, last.length() - ext.length() - 1);
                final String hash = Integer.toHexString(url.hashCode());
                final String filename = BatchFilenameHelper.audioFilename(
                        "Gold_" + hash + "_", title, ext);
                final DownloadManager.Request request = new DownloadManager.Request(
                        Uri.parse(url));
                request.setMimeType(mimeType(ext));
                request.setTitle(filename);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setAllowedOverMetered(true);
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, filename);
                manager.enqueue(request);
                queued++;
            } catch (final IllegalArgumentException | SecurityException exception) {
                skipped++;
            }
        }
        return new Result(queued, skipped, false);
    }
}
