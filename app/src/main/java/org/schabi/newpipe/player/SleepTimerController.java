/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.R;

import java.util.concurrent.TimeUnit;

/**
 * One-shot wall-clock sleep timer. Uses one scheduled message instead of polling; all pending
 * messages and the preference listener are removed when the playback service is destroyed.
 */
final class SleepTimerController implements SharedPreferences.OnSharedPreferenceChangeListener {
    @NonNull
    private final SharedPreferences preferences;
    @NonNull
    private final String preferenceKey;
    @NonNull
    private final Handler handler = new Handler(Looper.getMainLooper());
    @NonNull
    private final Runnable stopPlayback;
    @NonNull
    private final Runnable expiryAction = this::expire;
    private boolean playbackStarted;
    private boolean timerScheduled;
    private boolean disposed;

    SleepTimerController(@NonNull final Context context,
                         @NonNull final SharedPreferences preferences,
                         @NonNull final Runnable stopPlayback) {
        this.preferences = preferences;
        this.preferenceKey = context.getString(R.string.sleep_timer_key);
        this.stopPlayback = stopPlayback;
        preferences.registerOnSharedPreferenceChangeListener(this);
    }

    void onPlaybackStarted() {
        playbackStarted = true;
        if (!timerScheduled) {
            startTimerIfConfigured();
        }
    }

    private void startTimerIfConfigured() {
        if (disposed || !playbackStarted || timerScheduled) {
            return;
        }
        final String storedMinutes = preferences.getString(preferenceKey, "0");
        final int minutes;
        try {
            minutes = Integer.parseInt(storedMinutes == null ? "0" : storedMinutes);
        } catch (final NumberFormatException ignored) {
            return;
        }
        if (minutes <= 0 || minutes > 120) {
            return;
        }
        timerScheduled = true;
        handler.postDelayed(expiryAction, TimeUnit.MINUTES.toMillis(minutes));
    }

    private void expire() {
        timerScheduled = false;
        if (disposed) {
            return;
        }
        // The next playback session must never inherit a previously consumed sleep timer.
        preferences.edit().putString(preferenceKey, "0").apply();
        stopPlayback.run();
    }

    @Override
    public void onSharedPreferenceChanged(@NonNull final SharedPreferences sharedPreferences,
                                          @Nullable final String key) {
        if (!preferenceKey.equals(key)) {
            return;
        }
        // A settings change resets the countdown, including when playback is already running.
        handler.post(() -> {
            if (disposed) {
                return;
            }
            handler.removeCallbacks(expiryAction);
            timerScheduled = false;
            startTimerIfConfigured();
        });
    }

    void dispose() {
        disposed = true;
        handler.removeCallbacks(expiryAction);
        preferences.unregisterOnSharedPreferenceChangeListener(this);
    }
}
