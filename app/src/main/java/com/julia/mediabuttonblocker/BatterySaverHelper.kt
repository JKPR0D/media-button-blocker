package com.julia.mediabuttonblocker

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Thin wrapper around [PowerManager] that the UI uses to decide whether to show
 * the "battery optimisation" banner (one-time hint to whitelist the app) and
 * the "power save mode is on" warning banner.
 *
 * The two are independent:
 * - Battery-optimisation exemption is a per-app setting that survives reboots
 *   and only flips when the user explicitly grants it through the system dialog.
 * - Power-save mode is a global, transient state that flips on automatically at
 *   low battery (or manually) and tightens FGS scheduling for non-exempt apps.
 *
 * Both situations can demote our [BlockerService] enough that media-button
 * routing falls through to TeamTalk, which is why we surface them.
 */
internal object BatterySaverHelper {

    /**
     * @return `true` if the app is exempt from battery optimisations (i.e. on
     * the system's "ignore" list). Pre-Marshmallow devices have no such concept,
     * so we report `true` so the UI doesn't pester the user with an irrelevant
     * banner.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * @return `true` if the system is currently in power-save mode (a.k.a.
     * "Battery Saver" in Settings).
     */
    fun isPowerSaveMode(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isPowerSaveMode
    }

    /**
     * Opens the standard system dialog asking the user to put this app on the
     * battery-optimisation whitelist. Uses the
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent which shows a single
     * "Allow" / "Deny" prompt without taking the user out of the launching
     * activity.
     *
     * If the device manufacturer somehow doesn't handle that intent (rare), we
     * fall back to the broader battery-optimisation settings list so the user
     * can flip the switch manually.
     */
    @SuppressLint("BatteryLife") // off-Play distribution, not subject to Play policy
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val pkg = context.packageName
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (direct.resolveActivity(context.packageManager) != null) {
            context.startActivity(direct)
            return
        }
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (fallback.resolveActivity(context.packageManager) != null) {
            context.startActivity(fallback)
        }
    }
}
