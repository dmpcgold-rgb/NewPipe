/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player;

import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/** Uses API-28+ audio dynamics only from an SDK-guarded call site. */
public final class GoldAudioDynamics {
    private GoldAudioDynamics() { }

    /**
     * Enable Android's optional audio compressor and limiter for one playback session.
     *
     * @param sessionId active audio session ID
     * @return the effect so that the caller can always release it
     */
    @RequiresApi(Build.VERSION_CODES.P)
    @NonNull
    public static AudioEffect create(final int sessionId) {
        final DynamicsProcessing dynamics = new DynamicsProcessing(0, sessionId, null);
        try {
            // The platform defaults can add post-gain. Do not amplify the user's sound:
            // use a moderate compressor and a ceiling limiter with zero makeup gain.
            final int bands = dynamics.getConfig().getMbcBandCount();
            for (int i = 0; i < bands; i++) {
                final DynamicsProcessing.MbcBand band =
                        dynamics.getMbcBandByChannelIndex(0, i);
                band.setPostGain(0.0f);
                band.setPreGain(0.0f);
                band.setThreshold(-18.0f);
                band.setRatio(2.0f);
                band.setAttackTime(30.0f);
                band.setReleaseTime(200.0f);
                dynamics.setMbcBandAllChannelsTo(i, band);
            }
            final DynamicsProcessing.Limiter limiter = dynamics.getLimiterByChannelIndex(0);
            limiter.setPostGain(0.0f);
            limiter.setThreshold(-1.0f);
            limiter.setRatio(4.0f);
            dynamics.setLimiterAllChannelsTo(limiter);
            dynamics.setEnabled(true);
            return dynamics;
        } catch (final RuntimeException exception) {
            dynamics.release();
            throw exception;
        }
    }
}
