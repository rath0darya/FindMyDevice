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
        if (!body.equals("FMD LOCATE $secret", ignoreCase = true)) {
            SecureStore.setSmsStatus(context, "SMS_REJECTED: command secret mismatch")
            return
        }

        SecureStore.setSmsStatus(context, "SMS_COMMAND: valid LOCATE command received; acquiring fresh location")
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecoveryService::class.java)
                    .setAction(RecoveryService.ACTION_REFRESH)
                    .putExtra(EXTRA_REPLY_TO, sender)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Recovery refresh could not be started", e)
            SecureStore.setSmsStatus(
                context,
                "SMS_COMMAND: valid; refresh service failed: ${e.javaClass.simpleName}"
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
