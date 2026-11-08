/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LinkQueueLinksTest {

    @Test
    public void extractsDistinctHttpsLinksFromSharedText() {
        final List<String> result = LinkQueueLinks.extractLinks(
                "Folge: https://serienstream.to/serie/flashforward/staffel-1/episode-2. "
                + "Video https://example.org/video.mp4?token=abc\n"
                + "https://example.org/video.mp4?token=abc");
        assertEquals(2, result.size());
        assertEquals("https://serienstream.to/serie/flashforward/staffel-1/episode-2",
                result.get(0));
        assertEquals("https://example.org/video.mp4?token=abc", result.get(1));
    }

    @Test
    public void recognisesEpisodeUrlAsPageNotAsMedia() {
        final String episode =
                "https://serienstream.to/serie/flashforward/staffel-1/episode-2";
        assertFalse(LinkQueueLinks.isDirectMedia(episode));
        assertEquals("flashforward • Staffel 1 • Folge 2 (Webseite)",
                LinkQueueLinks.describe(episode));
    }

    @Test
    public void acceptsDirectFileWithSignedQuery() {
        assertTrue(LinkQueueLinks.isDirectMedia(
                "https://cdn.example.org/asset/video.MP4?signature=abc"));
        assertTrue(LinkQueueLinks.isDirectMedia("https://example.org/audio.m4a"));
        assertFalse(LinkQueueLinks.isDirectMedia("https://example.org/master.m3u8"));
        assertFalse(LinkQueueLinks.isDirectMedia("https://example.org/watch?id=123"));
    }

    @Test
    public void rejectsInsecureAndLocalAddresses() {
        assertFalse(LinkQueueLinks.isSafeHttps("http://example.org/video.mp4"));
        assertFalse(LinkQueueLinks.isSafeHttps("https://localhost/video.mp4"));
        assertFalse(LinkQueueLinks.isSafeHttps("https://192.168.1.2/video.mp4"));
        assertFalse(LinkQueueLinks.isSafeHttps("file:///storage/movie.mp4"));
    }
}
