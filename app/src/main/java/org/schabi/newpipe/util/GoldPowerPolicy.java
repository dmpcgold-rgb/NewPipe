/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

/**
 * Lightweight battery and network policy. No polling, wake locks or background jobs.
 * Settings affect new streams; an active high-quality stream is never silently changed.
 */
public final class GoldPowerPolicy {
    private GoldPowerPolicy() { }

    public static boolean isBatteryLow(@NonNull final Context context) {
        final BatteryManager battery =
                ContextCompat.getSystemService(context, BatteryManager.class);
        final PowerManager power =
                ContextCompat.getSystemService(context, PowerManager.class);
        final int percentage = battery == null ? -1
                : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return (percentage >= 0 && percentage <= 20)
                || (power != null && power.isPowerSaveMode());
    }

    public static boolean isMetered(@NonNull final Context context) {
        final ConnectivityManager network =
                ContextCompat.getSystemService(context, ConnectivityManager.class);
        return network != null && network.isActiveNetworkMetered();
    }

    public static boolean shouldReduceVideoQuality(@NonNull final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
                context.getString(R.string.gold_mobile_data_saver_key), false)
                && isMetered(context);
    }

    public static boolean shouldUseAudioOnLowBattery(@NonNull final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
                context.getString(R.string.gold_low_battery_audio_key), false)
                && isBatteryLow(context);
    }

    public static int recommendedDownloadThreads(@NonNull final Context context,
                                                 final int requested) {
        if (EnergySaver.isEnabled(context) || isBatteryLow(context)) {
            return Math.min(Math.max(1, requested), 2);
        }
        return Math.max(1, requested);
    }
}
