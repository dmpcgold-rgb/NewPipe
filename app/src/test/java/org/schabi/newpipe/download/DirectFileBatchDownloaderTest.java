/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Input validation tests: direct file downloads never interpret ordinary web pages as media. */
public class DirectFileBatchDownloaderTest {
    @Test
    public void acceptsHttpsVideoAndAudioFiles() {
        assertTrue(DirectFileBatchDownloader.isSupportedDirectMedia(
                "https://example.org/videos/clip.mp4"));
        assertTrue(DirectFileBatchDownloader.isSupportedDirectMedia(
                "https://media.example.org/music/audio.m4a?token=abc123"));
        assertTrue(DirectFileBatchDownloader.isSupportedDirectMedia(
                "https://example.org/video.webm"));
    }

    @Test
    public void refusesEpisodePagesAndStreamingManifests() {
        assertFalse(DirectFileBatchDownloader.isSupportedDirectMedia(
                "https://example.org/serie/show/staffel-1/Episode-2"));
        assertFalse(DirectFileBatchDownloader.isSupportedDirectMedia(
                "https://example.org/watch?id=123"));
        assertFalse(DirectFileBatchDownloader.isSupportedDirectMedia(
                "https://example.org/master.m3u8"));
        assertFalse(DirectFileBatchDownloader.isSupportedDirectMedia(
                "https://example.org/manifest.mpd"));
    }

    @Test
    public void refusesDangerousSchemesCredentialsAndFragments() {
        assertFalse(DirectFileBatchDownloader.isSupportedDirectMedia(
                "file:///storage/emulated/0/private.mp4"));
        assertFalse(DirectFileBatchDownloader.isSupportedDirectMedia(
                "http://example.org/file.mp4"));
        assertFalse(DirectFileBatchDownloader.isSupportedDirectMedia(
                "https://user:secret@example.org/file.mp4"));
        assertFalse(DirectFileBatchDownloader.isSupportedDirectMedia(
                "https://example.org/file.mp4#fragment"));
        assertFalse(DirectFileBatchDownloader.isSupportedDirectMedia("not a url"));
    }
}
