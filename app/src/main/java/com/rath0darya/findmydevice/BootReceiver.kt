package com.rath0darya.findmydevice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecoveryService::class.java)
            )
        } catch (_: Exception) {
            // Android may defer background service startup under current OS restrictions.
        }
    }
}
