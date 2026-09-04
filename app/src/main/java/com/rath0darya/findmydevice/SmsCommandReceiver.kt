package com.rath0darya.findmydevice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat

class SmsCommandReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "FindMyDeviceSMS"
        const val EXTRA_REPLY_TO = "reply_to"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        SecureStore.setSmsStatus(context, "SMS_RECEIVED: broadcast delivered to app")
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) {
            SecureStore.setSmsStatus(context, "SMS_RECEIVED: no message parts")
            return
        }

        val sender = messages.first().originatingAddress ?: run {
            SecureStore.setSmsStatus(context, "SMS_RECEIVED: sender address unavailable")
            return
        }
        val body = messages.joinToString("") { it.messageBody ?: "" }.trim()
        val owner = SecureStore.owner(context) ?: run {
            SecureStore.setSmsStatus(context, "SMS_REJECTED: no control number configured")
            return
        }
        if (!sameNumber(sender, owner)) {
            SecureStore.setSmsStatus(context, "SMS_REJECTED: sender does not match control number")
            return
        }

        val secret = SecureStore.commandSecret(context)
        if (body != "FMD LOCATE $secret") {
            SecureStore.setSmsStatus(context, "SMS_REJECTED: command secret mismatch")
            return
        }

        SecureStore.setSmsStatus(context, "SMS_COMMAND: authenticated LOCATE command received")
        val refresh = Intent(RecoveryService.ACTION_REFRESH_SIGNAL)
            .setPackage(context.packageName)
            .putExtra(EXTRA_REPLY_TO, sender)

        if (SecureStore.isServiceActive(context)) {
            // The recovery FGS is already alive. Signal it directly instead of trying to
            // launch a location FGS from this background SMS broadcast.
            context.sendBroadcast(refresh)
            SecureStore.setSmsStatus(context, "SMS_COMMAND: sent refresh request to active recovery service")
            return
        }

        // If the service was killed, try to restore it. Android 12+ may reject a background
        // FGS launch here, so the catch path deliberately retains the cached report.
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecoveryService::class.java)
                    .setAction(RecoveryService.ACTION_REFRESH)
                    .putExtra(EXTRA_REPLY_TO, sender)
            )
            SecureStore.setSmsStatus(context, "SMS_COMMAND: recovery service restart requested")
        } catch (e: Exception) {
            Log.w(TAG, "Recovery service could not be restarted from SMS", e)
            SecureStore.setSmsStatus(
                context,
                "SMS_COMMAND: service unavailable in background (${e.javaClass.simpleName}); cached report retained"
            )
            val fallback = try { SecureStore.lastReport(context) } catch (_: Exception) { null }
                ?: "DEVICE LOCATION\nNo location fix available yet."
            SmsReplySender.send(context, sender, fallback)
        }
    }

    private fun sameNumber(a: String, b: String): Boolean {
        val na = a.filter(Char::isDigit).takeLast(10)
        val nb = b.filter(Char::isDigit).takeLast(10)
        return na.length >= 10 && na == nb
    }
}
