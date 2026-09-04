package com.rath0darya.findmydevice

import android.content.Context

/** Explicit user opt-in for participating in the local BLE relay network. */
object RelaySettings {
    private const val PREFS = "relay_settings"
    private const val ENABLED = "enabled"

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply()
    }
}
