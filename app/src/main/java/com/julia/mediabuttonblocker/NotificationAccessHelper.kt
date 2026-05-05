package com.julia.mediabuttonblocker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

/**
 * Helpers for the "Notification access" special-app-access permission used by
 * [MediaSessionsNotificationListener] / [BlockerService] to subscribe to
 * active-session changes via
 * [android.media.session.MediaSessionManager.addOnActiveSessionsChangedListener].
 *
 * The permission is **not** a runtime permission (`requestPermissions` doesn't
 * apply); the user must grant it once from the system settings screen, and it
 * persists across reboots. We surface a banner in the main UI when it is
 * missing and provide a button to drop the user straight into the relevant
 * settings page.
 */
internal object NotificationAccessHelper {

    /**
     * @return `true` if the user has enabled "Notification access" for our app
     * (specifically, for [MediaSessionsNotificationListener]).
     *
     * Reads `Settings.Secure.enabled_notification_listeners` directly, which is
     * a flat colon-separated list of `package/component` strings that have been
     * granted access. There's no public API for this; this is the standard
     * approach across the ecosystem.
     */
    fun isGranted(context: Context): Boolean {
        val pkg = context.packageName
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        if (TextUtils.isEmpty(flat)) return false
        val expectedComponent = ComponentName(context, MediaSessionsNotificationListener::class.java)
            .flattenToString()
        for (entry in flat.split(":")) {
            val token = entry.trim()
            if (token.isEmpty()) continue
            val cn = ComponentName.unflattenFromString(token) ?: continue
            if (cn.packageName == pkg && cn.flattenToString() == expectedComponent) {
                return true
            }
        }
        return false
    }

    /**
     * Opens the system's "Notification access" settings screen. The user can
     * then toggle our app on. We can't pre-select our entry on the list (no
     * public API for that), but the screen is small and finding our app is
     * trivial.
     */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
