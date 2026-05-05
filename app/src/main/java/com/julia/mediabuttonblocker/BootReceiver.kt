package com.julia.mediabuttonblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts [BlockerService] after a device reboot if the user had blocking
 * enabled before the reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            BlockerPrefs.isEnabled(context)
        ) {
            BlockerService.start(context)
        }
    }
}
