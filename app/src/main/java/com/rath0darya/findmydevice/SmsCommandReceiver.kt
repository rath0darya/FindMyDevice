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

        // Do not trust a persisted "service active" flag. Android may kill the hosting
        // process without giving the app a reliable opportunity to clear that flag.
        // Starting the already-started service is safe, while a dead START_STICKY service
        // can be recreated and receive the refresh intent through onStartCommand().
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecoveryService::class.java)
                    .setAction(RecoveryService.ACTION_REFRESH)
                    .putExtra(EXTRA_REPLY_TO, sender)
            )
            SecureStore.setSmsStatus(context, "SMS_COMMAND: recovery service refresh requested")
        } catch (e: Exception) {
            Log.w(TAG, "Recovery service could not be started from SMS", e)
            // If the existing service process is alive, the dynamic receiver remains the
            // fastest path. Otherwise retain the report rather than claiming the command ran.
            try {
                context.sendBroadcast(refresh)
                SecureStore.setSmsStatus(context, "SMS_COMMAND: service start unavailable; refresh signal sent")
            } catch (_: Exception) {
                SecureStore.setSmsStatus(
                    context,
                    "SMS_COMMAND: recovery service unavailable (${e.javaClass.simpleName}); cached report retained"
                )
            }
        }
    }

    private fun sameNumber(a: String, b: String): Boolean {
        val na = a.filter(Char::isDigit).takeLast(10)
        val nb = b.filter(Char::isDigit).takeLast(10)
        return na.length >= 10 && na == nb
    }
}
