/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.util;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.schabi.newpipe.R;

/** Read-only, offline device checks. Never modifies files or private app data. */
public final class GoldDiagnostics {
    private GoldDiagnostics() { }

    /**
     * Return concise diagnostics from Android system services and the in-memory info cache.
     *
     * @param context active Android context
     * @return human-readable device checks
     */
    @NonNull
    public static String report(@NonNull final Context context) {
        final ConnectivityManager connectivity =
                ContextCompat.getSystemService(context, ConnectivityManager.class);
        final android.net.Network activeNetwork = connectivity == null ? null
                : connectivity.getActiveNetwork();
        final NetworkCapabilities capability = activeNetwork == null ? null
                : connectivity.getNetworkCapabilities(activeNetwork);
        final boolean connected = capability != null
                && capability.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        final ActivityManager activity =
                ContextCompat.getSystemService(context, ActivityManager.class);
        final ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (activity != null) {
            activity.getMemoryInfo(memory);
        }
        final long bytes = context.getFilesDir().getUsableSpace();
        final String mb = bytes / (1024L * 1024L) + " MB";
        return context.getString(R.string.gold_diagnostics_network) + ": "
                + (connected ? "✓" : "–") + "\n"
                + context.getString(R.string.gold_diagnostics_memory) + ": " + mb + "\n"
                + context.getString(R.string.gold_diagnostics_ram) + ": "
                + (activity != null && memory.lowMemory ? "✓" : "–") + "\n"
                + context.getString(R.string.gold_diagnostics_cache) + ": "
                + InfoCache.getInstance().getSize() + "\n"
                + context.getString(R.string.gold_diagnostics_saver) + ": "
                + (EnergySaver.isEnabled(context) ? "✓" : "–") + "\n\n"
                + context.getString(R.string.gold_diagnostics_note);
    }
}
