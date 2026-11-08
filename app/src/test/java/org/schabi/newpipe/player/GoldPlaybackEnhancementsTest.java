/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests that player automation is opt-in, bounded and ignores short clips. */
public class GoldPlaybackEnhancementsTest {
    @Test
    public void introSkipsOnlyWhenStartingLongOnDemandVideos() {
        assertTrue(GoldPlaybackEnhancements.shouldSkipIntro(500, 120000, 10000, 15000));
        assertFalse(GoldPlaybackEnhancements.shouldSkipIntro(20000, 120000, 10000, 15000));
        assertFalse(GoldPlaybackEnhancements.shouldSkipIntro(500, 20000, 10000, 15000));
        assertFalse(GoldPlaybackEnhancements.shouldSkipIntro(500, 120000, 0, 15000));
    }

    @Test
    public void outroSkipsOnlyTheFinalConfiguredSeconds() {
        assertTrue(GoldPlaybackEnhancements.shouldSkipOutro(112000, 120000, 10000, 15000));
        assertFalse(GoldPlaybackEnhancements.shouldSkipOutro(80000, 120000, 10000, 15000));
        assertFalse(GoldPlaybackEnhancements.shouldSkipOutro(10000, 20000, 10000, 15000));
        assertFalse(GoldPlaybackEnhancements.shouldSkipOutro(119900, 120000, 10000, 15000));
    }

    @Test
    public void userValuesAreClampedAndSafelyParsed() {
        assertEquals(30000, GoldPlaybackEnhancements.secondsToMillis("999"));
        assertEquals(0, GoldPlaybackEnhancements.secondsToMillis("-2"));
        assertEquals(0, GoldPlaybackEnhancements.secondsToMillis("invalid"));
        assertEquals(1.0f, GoldPlaybackEnhancements.safeSpeed("NaN"), 0.0001f);
        assertEquals(2.0f, GoldPlaybackEnhancements.safeSpeed("7"), 0.0001f);
        assertEquals(0.5f, GoldPlaybackEnhancements.safeSpeed("0"), 0.0001f);
        assertEquals(1.25f, GoldPlaybackEnhancements.safeSpeed("1.25"), 0.0001f);
    }
}
