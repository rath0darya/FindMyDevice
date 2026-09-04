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
        const val ACTION_SMS_SENT = "com.rath0darya.findmydevice.SMS_SENT"
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

        SecureStore.setSmsStatus(context, "SMS_COMMAND: valid LOCATE command received")

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecoveryService::class.java)
                    .setAction(RecoveryService.ACTION_REFRESH)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Recovery refresh could not be started", e)
            SecureStore.setSmsStatus(context, "SMS_COMMAND: valid; refresh service failed: ${e.javaClass.simpleName}")
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
            SecureStore.setSmsStatus(context, "SMS_REPLY: SEND_SMS permission is NOT granted")
            Log.w(TAG, "SEND_SMS permission is not granted")
            return
        }

        try {
            val smsManager = selectSmsManager(context) ?: run {
                SecureStore.setSmsStatus(context, "SMS_REPLY: no active SMS subscription")
                Log.w(TAG, "No active SMS subscription is available")
                return
            }

            val parts = smsManager.divideMessage(message.take(1400))
            if (parts.isEmpty()) {
                SecureStore.setSmsStatus(context, "SMS_REPLY: generated message has no parts")
                return
            }

            val sentIntents = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { index ->
                val sentIntent = PendingIntent.getBroadcast(
                    context,
                    2000 + index,
                    Intent(ACTION_SMS_SENT)
                        .setPackage(context.packageName)
                        .putExtra("part_index", index)
                        .putExtra("part_count", parts.size),
                    PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
                )
                sentIntents.add(sentIntent)
            }

            SecureStore.setSmsStatus(
                context,
                "SMS_REPLY: submitting ${parts.size} part(s) via subscription ${subscriptionIdOf(smsManager)}"
            )

            smsManager.sendMultipartTextMessage(
                destination,
                null,
                parts,
                sentIntents,
                null
            )
            Log.i(TAG, "Recovery SMS reply submitted to $destination in ${parts.size} part(s)")
        } catch (e: Exception) {
            SecureStore.setSmsStatus(context, "SMS_REPLY: submit threw ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
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

    private fun subscriptionIdOf(manager: SmsManager): Int = try {
        if (Build.VERSION.SDK_INT >= 31) manager.subscriptionId else -1
    } catch (_: Exception) {
        -1
    }

    private fun pendingIntentImmutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    private fun sameNumber(a: String, b: String): Boolean {
        val na = a.filter(Char::isDigit).takeLast(10)
        val nb = b.filter(Char::isDigit).takeLast(10)
        return na.length >= 10 && na == nb
    }
}
