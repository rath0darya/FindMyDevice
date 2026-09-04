package com.rath0darya.findmydevice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

class SmsCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val sender = messages.first().originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody ?: "" }.trim()
        val owner = SecureStore.owner(context) ?: return
        if (!sameNumber(sender, owner)) return

        val secret = SecureStore.commandSecret(context)
        if (!body.equals("FMD LOCATE $secret", ignoreCase = true)) return

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecoveryService::class.java)
                    .setAction(RecoveryService.ACTION_REFRESH)
            )
        } catch (_: Exception) {
            // Background foreground-service restrictions may reject this request.
        }

        val report = try {
            SecureStore.lastReport(context)
        } catch (_: Exception) {
            null
        } ?: "DEVICE LOCATION\nNo location fix available yet."

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager?.sendTextMessage(sender, null, report.take(1400), null, null)
        } catch (_: Exception) { }
    }

    private fun sameNumber(a: String, b: String): Boolean {
        val na = a.filter(Char::isDigit).takeLast(10)
        val nb = b.filter(Char::isDigit).takeLast(10)
        return na.length >= 10 && na == nb
    }
}
