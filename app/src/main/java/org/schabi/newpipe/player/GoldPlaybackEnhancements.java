/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player;

import androidx.annotation.Nullable;

/** Pure and bounded playback option calculations, safe to unit-test without media services. */
public final class GoldPlaybackEnhancements {
    private GoldPlaybackEnhancements() { }

    /**
     * Parse a user-defined skip interval without risking overflow or unbounded seeking.
     *
     * @param value seconds from preferences
     * @return duration in milliseconds, capped at 30 seconds
     */
    public static int secondsToMillis(@Nullable final String value) {
        try {
            return Math.max(0, Math.min(30, Integer.parseInt(value))) * 1000;
        } catch (final NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * Normalize user speed values to ExoPlayer's practical, supported range.
     *
     * @param value decimal playback speed
     * @return safe playback speed
     */
    public static float safeSpeed(@Nullable final String value) {
        try {
            final float speed = Float.parseFloat(value);
            if (!Float.isFinite(speed)) {
                return 1.0f;
            }
            return Math.max(0.5f, Math.min(2.0f, speed));
        } catch (final NumberFormatException exception) {
            return 1.0f;
        }
    }

    /**
     * Skip a configured intro only at the beginning of sufficiently long on-demand videos.
     *
     * @param progress current playback time in milliseconds
     * @param duration total playback time in milliseconds
     * @param intro intro length in milliseconds
     * @param outro outro length in milliseconds
     * @return whether intro should be skipped
     */
    public static boolean shouldSkipIntro(final int progress, final int duration,
                                          final int intro, final int outro) {
        return intro > 0 && progress >= 0 && progress < 3500
                && duration > intro + outro + 5000;
    }

    /**
     * Skip the very end of a long on-demand video after normal playback reached its outro.
     *
     * @param progress current playback time in milliseconds
     * @param duration total playback time in milliseconds
     * @param intro intro length in milliseconds
     * @param outro outro length in milliseconds
     * @return whether outro should be skipped
     */
    public static boolean shouldSkipOutro(final int progress, final int duration,
                                          final int intro, final int outro) {
        return outro > 0 && progress > 5000 && duration > intro + outro + 5000
                && duration - progress > 200 && duration - progress <= outro;
    }
}
