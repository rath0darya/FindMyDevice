package com.rath0darya.findmydevice

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

object SmsReplySender {
    private const val ACTION_SMS_SENT = "com.rath0darya.findmydevice.SMS_SENT"

    fun send(context: Context, destination: String, message: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            SecureStore.setSmsStatus(context, "SMS_REPLY: SEND_SMS permission is NOT granted")
            return
        }

        try {
            val smsManager = selectSmsManager(context) ?: run {
                SecureStore.setSmsStatus(context, "SMS_REPLY: no active SMS subscription")
                return
            }
            val parts = smsManager.divideMessage(message.take(1400))
            if (parts.isEmpty()) {
                SecureStore.setSmsStatus(context, "SMS_REPLY: generated message has no parts")
                return
            }

            val sentIntents = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { index ->
                sentIntents += PendingIntent.getBroadcast(
                    context,
                    2000 + index,
                    Intent(ACTION_SMS_SENT)
                        .setPackage(context.packageName)
                        .putExtra("part_index", index)
                        .putExtra("part_count", parts.size),
                    PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
                )
            }

            SecureStore.setSmsStatus(
                context,
                "SMS_REPLY: submitting ${parts.size} part(s) via subscription ${subscriptionIdOf(smsManager)}"
            )
            smsManager.sendMultipartTextMessage(destination, null, parts, sentIntents, null)
        } catch (e: Exception) {
            SecureStore.setSmsStatus(
                context,
                "SMS_REPLY: submit threw ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            )
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
                val manager = context.getSystemService(SubscriptionManager::class.java)
                manager?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId
                    ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
        } catch (_: Exception) {
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
}
