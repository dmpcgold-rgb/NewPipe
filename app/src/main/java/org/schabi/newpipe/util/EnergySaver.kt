/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.util

import android.content.Context
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R

/**
 * NewPipe Gold's opt-out energy-saving preference. This intentionally does not disable Android's
 * normal media player foreground service, which is needed for uninterrupted background audio.
 */
object EnergySaver {
    @JvmStatic
    fun isEnabled(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
            context.getString(R.string.energy_saver_key),
            true
        )
    }
}
