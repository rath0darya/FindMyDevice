package com.rath0darya.findmydevice

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log

class SmsSendResultReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "FindMyDeviceSMS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val partIndex = intent.getIntExtra("part_index", -1)
        val partCount = intent.getIntExtra("part_count", -1)
        val subscriptionId = intent.getIntExtra("subscription_id", -1)
        val errorCode = intent.getIntExtra("errorCode", -1)
        val noDefault = intent.getBooleanExtra("noDefault", false)

        if (resultCode == Activity.RESULT_OK) {
            val success = SecureStore.incrementPendingSmsSuccess(context)
            if (partCount > 0 && success >= partCount) {
                SecureStore.clearPendingSms(context)
                SecureStore.setSmsStatus(
                    context,
                    "SMS_SENT: all $partCount report part(s) accepted via subscription $subscriptionId"
                )
            } else {
                SecureStore.setSmsStatus(
                    context,
                    "SMS_SENT: part ${partIndex + 1}/$partCount accepted via subscription $subscriptionId"
                )
            }
            Log.i(TAG, SecureStore.smsStatus(context) ?: "SMS_SENT")
            return
        }

        val failure = SecureStore.incrementPendingSmsFailure(context)
        val status = when (resultCode) {
            SmsManager.RESULT_ERROR_RADIO_OFF ->
                "SMS_SEND_FAILED: radio off, part ${partIndex + 1}/$partCount, subscription $subscriptionId"
            SmsManager.RESULT_ERROR_NO_SERVICE ->
                "SMS_SEND_FAILED: no SMS service, part ${partIndex + 1}/$partCount, subscription $subscriptionId"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED ->
                "SMS_SEND_FAILED: SMS queue limit, part ${partIndex + 1}/$partCount, subscription $subscriptionId"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
                "SMS_SEND_FAILED: generic failure, part ${partIndex + 1}/$partCount, subscription $subscriptionId, errorCode=$errorCode, noDefault=$noDefault"
            else ->
                "SMS_SEND_FAILED: resultCode=$resultCode, part ${partIndex + 1}/$partCount, subscription $subscriptionId, errorCode=$errorCode, noDefault=$noDefault"
        }

        SecureStore.setSmsStatus(
            context,
            "$status; failedParts=$failure, successfulParts=${SecureStore.pendingSmsSuccessCount(context)}; report retained"
        )
        Log.w(TAG, SecureStore.smsStatus(context) ?: status)
    }
}
