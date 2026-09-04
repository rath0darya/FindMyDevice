package com.rath0darya.findmydevice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SmsRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.intent.action.SERVICE_STATE",
            "android.intent.action.SIM_STATE_CHANGED",
            "android.telephony.action.DEFAULT_SMS_SUBSCRIPTION_CHANGED",
            "android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED" -> {
                SmsReplySender.retryPending(context)
            }
        }
    }
}
