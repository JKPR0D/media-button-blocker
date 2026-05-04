package com.julia.mediabuttonblocker

import android.content.Context

/** Tiny wrapper around SharedPreferences for the single "blocking enabled" flag. */
object BlockerPrefs {
    private const val FILE = "blocker_prefs"
    private const val KEY_ENABLED = "blocking_enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
