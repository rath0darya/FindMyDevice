package com.rath0darya.findmydevice

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.ServiceState
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

object SmsReplySender {
    private const val ACTION_SMS_SENT = "com.rath0darya.findmydevice.SMS_SENT"
    private const val REQUEST_BASE = 2000

    fun send(context: Context, destination: String, message: String) {
        SecureStore.savePendingSms(context, destination, message)
        attemptPending(context)
    }

    fun retryPending(context: Context) {
        if (SecureStore.pendingSms(context) == null) return
        attemptPending(context)
    }

    private fun attemptPending(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            SecureStore.setSmsStatus(context, "SMS_QUEUE: SEND_SMS permission is NOT granted; report retained")
            return
        }

        val pending = SecureStore.pendingSms(context) ?: return
        val destination = pending.first
        val message = pending.second
        val subscriptionId = selectReadySubscriptionId(context)

        if (subscriptionId == null) return

        try {
            val smsManager = SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            val parts = smsManager.divideMessage(message)
            if (parts.isEmpty()) {
                SecureStore.setSmsStatus(context, "SMS_QUEUE: report produced no SMS parts")
                return
            }

            SecureStore.setPendingSmsPartCount(context, parts.size)
            SecureStore.setSmsStatus(
                context,
                "SMS_REPLY: sending ${parts.size} part(s) via subscription $subscriptionId"
            )

            val sentIntents = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { index ->
                sentIntents += PendingIntent.getBroadcast(
                    context,
                    REQUEST_BASE + index,
                    Intent(ACTION_SMS_SENT)
                        .setPackage(context.packageName)
                        .putExtra("part_index", index)
                        .putExtra("part_count", parts.size)
                        .putExtra("subscription_id", subscriptionId),
                    PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
                )
            }

            smsManager.sendMultipartTextMessage(destination, null, parts, sentIntents, null)
        } catch (e: Exception) {
            SecureStore.setSmsStatus(
                context,
                "SMS_QUEUE: submit failed on subscription $subscriptionId: ${e.javaClass.simpleName}; report retained"
            )
        }
    }

    private fun selectReadySubscriptionId(context: Context): Int? {
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: run {
            SecureStore.setSmsStatus(context, "SMS_QUEUE: subscription manager unavailable; report retained")
            return null
        }

        val active = try {
            manager.activeSubscriptionInfoList
        } catch (_: SecurityException) {
            SecureStore.setSmsStatus(context, "SMS_QUEUE: READ_PHONE_STATE unavailable; report retained")
            return null
        } catch (_: Exception) {
            SecureStore.setSmsStatus(context, "SMS_QUEUE: could not read SIM subscriptions; report retained")
            return null
        }

        if (active.isEmpty()) {
            SecureStore.setSmsStatus(context, "SMS_QUEUE: no active SIM/eSIM subscription; report retained")
            return null
        }

        val defaultId = SubscriptionManager.getDefaultSmsSubscriptionId()
        val ordered = active.sortedBy { if (it.subscriptionId == defaultId) 0 else 1 }
        var sawService = false

        for (info in ordered) {
            val state = try {
                val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
                    ?.createForSubscriptionId(info.subscriptionId)
                tm?.serviceState?.state
            } catch (_: Exception) {
                null
            }

            if (state == null || state == ServiceState.STATE_IN_SERVICE) {
                sawService = true
                return info.subscriptionId
            }
        }

        if (!sawService) {
            SecureStore.setSmsStatus(
                context,
                "SMS_QUEUE: SIM present but no cellular service; report retained for retry"
            )
        }
        return null
    }

    private fun pendingIntentImmutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
}
