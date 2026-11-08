/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.schabi.newpipe.extractor.MediaFormat;
import org.junit.Test;

/** Instrumented tests for durable multi-download queue records. */
public class BatchDownloadStoreTest {
    @Test
    public void queueRecordRoundTripsItsOptionsAndState() throws Exception {
        final BatchDownloadStore.Entry entry = new BatchDownloadStore.Entry();
        entry.serviceId = 0;
        entry.url = "https://www.youtube.com/watch?v=example";
        entry.title = "Deutsch (Originalton)";
        entry.audioOnly = false;
        entry.wifiOnly = true;
        entry.quality = "720p";
        entry.audioTrack = BatchDownloadStore.AUDIO_ORIGINAL;
        entry.audioFormat = BatchDownloadStore.FORMAT_M4A;
        entry.status = BatchDownloadStore.PENDING;
        entry.error = "";

        final JSONObject encoded = entry.toJson();
        final BatchDownloadStore.Entry restored = BatchDownloadStore.Entry.fromJson(encoded);

        assertEquals(entry.key(), restored.key());
        assertEquals("Deutsch (Originalton)", restored.title);
        assertEquals("720p", restored.quality);
        assertEquals(BatchDownloadStore.AUDIO_ORIGINAL, restored.audioTrack);
        assertEquals(BatchDownloadStore.FORMAT_M4A, restored.audioFormat);
        assertEquals(BatchDownloadStore.PENDING, restored.status);
        assertTrue(restored.wifiOnly);
        assertFalse(restored.audioOnly);
    }

    @Test
    public void differentYoutubeUrlsWithSameIdAreDuplicates() {
        final BatchDownloadStore.Entry longUrl = new BatchDownloadStore.Entry();
        longUrl.serviceId = 0;
        longUrl.url = "https://www.youtube.com/watch?v=abc123&t=20";
        longUrl.audioOnly = false;

        final BatchDownloadStore.Entry shortUrl = new BatchDownloadStore.Entry();
        shortUrl.serviceId = 0;
        shortUrl.url = "https://youtu.be/abc123";
        shortUrl.audioOnly = false;

        assertEquals(longUrl.key(), shortUrl.key());
    }

    @Test
    public void differentAudioFormatsAreDistinctQueueEntries() {
        final BatchDownloadStore.Entry mp3 = new BatchDownloadStore.Entry();
        mp3.serviceId = 0;
        mp3.url = "https://youtu.be/abc123";
        mp3.audioOnly = true;
        mp3.audioFormat = BatchDownloadStore.FORMAT_MP3;

        final BatchDownloadStore.Entry m4a = new BatchDownloadStore.Entry();
        m4a.serviceId = 0;
        m4a.url = "https://www.youtube.com/watch?v=abc123";
        m4a.audioOnly = true;
        m4a.audioFormat = BatchDownloadStore.FORMAT_M4A;

        assertFalse(mp3.key().equals(m4a.key()));
    }

    @Test
    public void legacyQueueRecordsUseAutomaticAudioFormat() {
        final JSONObject original = new JSONObject();
        try {
            original.put("service", 0);
            original.put("url", "https://youtu.be/abc123");
            original.put("audio", true);
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
        final BatchDownloadStore.Entry restored = BatchDownloadStore.Entry.fromJson(original);
        assertEquals(BatchDownloadStore.FORMAT_AUTO, restored.audioFormat);
    }

    @Test
    public void videoAndAudioAreDistinctQueueEntries() {
        final BatchDownloadStore.Entry video = new BatchDownloadStore.Entry();
        video.serviceId = 0;
        video.url = "https://www.youtube.com/watch?v=abc";
        video.audioOnly = false;
        final BatchDownloadStore.Entry audio = new BatchDownloadStore.Entry();
        audio.serviceId = video.serviceId;
        audio.url = video.url;
        audio.audioOnly = true;

        assertFalse(video.key().equals(audio.key()));
    }
    @Test
    public void mp3FormatNeverAcceptsAacOrOpusData() {
        assertTrue(BatchDownloadStarter.matchesAudioFormat(
                MediaFormat.MP3, BatchDownloadStore.FORMAT_MP3));
        assertFalse(BatchDownloadStarter.matchesAudioFormat(
                MediaFormat.M4A, BatchDownloadStore.FORMAT_MP3));
        assertFalse(BatchDownloadStarter.matchesAudioFormat(
                MediaFormat.WEBMA_OPUS, BatchDownloadStore.FORMAT_MP3));
    }

    @Test
    public void opusCanBeExtractedFromWebmWithoutMp3Transcoding() {
        assertTrue(BatchDownloadStarter.matchesAudioFormat(
                MediaFormat.OPUS, BatchDownloadStore.FORMAT_OPUS));
        assertTrue(BatchDownloadStarter.matchesAudioFormat(
                MediaFormat.WEBMA_OPUS, BatchDownloadStore.FORMAT_OPUS));
        assertFalse(BatchDownloadStarter.matchesAudioFormat(
                MediaFormat.M4A, BatchDownloadStore.FORMAT_OPUS));
    }

    @Test
    public void webmPreservesWebmAudioStreams() {
        assertTrue(BatchDownloadStarter.matchesAudioFormat(
                MediaFormat.WEBMA, BatchDownloadStore.FORMAT_WEBM));
        assertTrue(BatchDownloadStarter.matchesAudioFormat(
                MediaFormat.WEBMA_OPUS, BatchDownloadStore.FORMAT_WEBM));
        assertFalse(BatchDownloadStarter.matchesAudioFormat(
                MediaFormat.MP3, BatchDownloadStore.FORMAT_WEBM));
    }

}
