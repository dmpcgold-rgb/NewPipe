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
            dynamics.setEnabled(true);
            return dynamics;
        } catch (final RuntimeException exception) {
            dynamics.release();
            throw exception;
        }
    }
}
