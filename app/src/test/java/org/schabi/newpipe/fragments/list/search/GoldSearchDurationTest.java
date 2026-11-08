/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.fragments.list.search;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regression tests for optional local video length filters. */
public class GoldSearchDurationTest {
    @Test
    public void allFilterAcceptsVideosOfAnyLengthIncludingLiveStreams() {
        assertTrue(SearchFragment.matchesGoldDuration(9100, -1));
        assertTrue(SearchFragment.matchesGoldDuration(9100, 20000));
    }

    @Test
    public void shortVideosExcludeFiveMinutesAndUnknownDurations() {
        assertTrue(SearchFragment.matchesGoldDuration(9101, 299));
        assertFalse(SearchFragment.matchesGoldDuration(9101, 300));
        assertFalse(SearchFragment.matchesGoldDuration(9101, -1));
    }

    @Test
    public void mediumVideosIncludeBothBoundaries() {
        assertTrue(SearchFragment.matchesGoldDuration(9102, 300));
        assertTrue(SearchFragment.matchesGoldDuration(9102, 1200));
        assertFalse(SearchFragment.matchesGoldDuration(9102, 1201));
        assertFalse(SearchFragment.matchesGoldDuration(9102, 299));
    }

    @Test
    public void longVideosStartAfterTwentyMinutes() {
        assertTrue(SearchFragment.matchesGoldDuration(9103, 1201));
        assertFalse(SearchFragment.matchesGoldDuration(9103, 1200));
        assertFalse(SearchFragment.matchesGoldDuration(9103, -1));
    }
}
