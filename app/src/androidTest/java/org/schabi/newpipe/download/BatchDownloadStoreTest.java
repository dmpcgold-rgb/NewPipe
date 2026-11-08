/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
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
        entry.status = BatchDownloadStore.PENDING;
        entry.error = "";

        final JSONObject encoded = entry.toJson();
        final BatchDownloadStore.Entry restored = BatchDownloadStore.Entry.fromJson(encoded);

        assertEquals(entry.key(), restored.key());
        assertEquals("Deutsch (Originalton)", restored.title);
        assertEquals("720p", restored.quality);
        assertEquals(BatchDownloadStore.AUDIO_ORIGINAL, restored.audioTrack);
        assertEquals(BatchDownloadStore.PENDING, restored.status);
        assertTrue(restored.wifiOnly);
        assertFalse(restored.audioOnly);
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
}
