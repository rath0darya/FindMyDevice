package com.rath0darya.findmydevice

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat

class SmsCommandReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "FindMyDeviceSMS"
        private const val ACTION_SMS_SENT = "com.rath0darya.findmydevice.SMS_SENT"
    }

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
        } catch (e: Exception) {
            Log.w(TAG, "Recovery refresh could not be started", e)
        }

        val report = try {
            SecureStore.lastReport(context)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read cached report", e)
            null
        } ?: "DEVICE LOCATION\nNo location fix available yet."

        sendReply(context, sender, report)
    }

    private fun sendReply(context: Context, destination: String, message: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SEND_SMS permission is not granted")
            return
        }

        try {
            val smsManager = selectSmsManager(context) ?: run {
                Log.w(TAG, "No active SMS subscription is available")
                return
            }

            val parts = smsManager.divideMessage(message.take(1400))
            if (parts.isEmpty()) return

            val sentIntent = PendingIntent.getBroadcast(
                context,
                1001,
                Intent(ACTION_SMS_SENT).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
            )
            val sentIntents = ArrayList<PendingIntent>(parts.size).apply {
                repeat(parts.size) { add(sentIntent) }
            }

            smsManager.sendMultipartTextMessage(
                destination,
                null,
                parts,
                sentIntents,
                null
            )
            Log.i(TAG, "Recovery SMS reply submitted to $destination in ${parts.size} part(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit recovery SMS reply", e)
        }
    }

    private fun selectSmsManager(context: Context): SmsManager? {
        if (Build.VERSION.SDK_INT < 22) {
            @Suppress("DEPRECATION")
            return SmsManager.getDefault()
        }

        val subscriptionId = try {
            val defaultId = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (SubscriptionManager.isValidSubscriptionId(defaultId)) {
                defaultId
            } else {
                val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
                subscriptionManager?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId
                    ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve SMS subscription", e)
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }

        if (SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            return SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }

        @Suppress("DEPRECATION")
        return SmsManager.getDefault()
    }

    private fun pendingIntentImmutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    private fun sameNumber(a: String, b: String): Boolean {
        val na = a.filter(Char::isDigit).takeLast(10)
        val nb = b.filter(Char::isDigit).takeLast(10)
        return na.length >= 10 && na == nb
    }
}
