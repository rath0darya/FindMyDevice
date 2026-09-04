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
        val errorCode = intent.getIntExtra("errorCode", -1)
        val noDefault = intent.getBooleanExtra("noDefault", false)

        val status = when (resultCode) {
            Activity.RESULT_OK -> "SMS_SENT: part ${partIndex + 1}/$partCount accepted by SmsManager"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "SMS_SEND_FAILED: radio is off (part ${partIndex + 1}/$partCount)"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "SMS_SEND_FAILED: no SMS service (part ${partIndex + 1}/$partCount)"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "SMS_SEND_FAILED: SMS queue limit exceeded (part ${partIndex + 1}/$partCount)"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                "SMS_SEND_FAILED: generic failure (part ${partIndex + 1}/$partCount, errorCode=$errorCode, noDefault=$noDefault)"
            }
            else -> "SMS_SEND_FAILED: resultCode=$resultCode (part ${partIndex + 1}/$partCount, errorCode=$errorCode, noDefault=$noDefault)"
        }

        SecureStore.setSmsStatus(context, status)
        Log.i(TAG, status)
    }
}
