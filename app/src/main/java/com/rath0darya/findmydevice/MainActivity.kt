package com.rath0darya.findmydevice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var ownerInput: EditText
    private lateinit var status: TextView
    private lateinit var last: TextView
    private lateinit var smsStatus: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshUi = object : Runnable {
        override fun run() {
            if (::smsStatus.isInitialized) {
                smsStatus.text = "SMS diagnostics: ${SecureStore.smsStatus(this@MainActivity) ?: "No SMS command processed yet."}"
                last.text = safeLastReport()
            }
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startRecovery()
    }

    private fun requestedPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 31 && RelaySettings.isEnabled(this@MainActivity)) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply {
            text = "Find My Device\nOffline Recovery"
            textSize = 24f
        }
        ownerInput = EditText(this).apply {
            hint = "Your control phone number"
            inputType = 3
        }
        val relayOptIn = CheckBox(this).apply {
            text = "Participate in Nearby Relay"
            isChecked = RelaySettings.isEnabled(this@MainActivity)
        }
        val relayInfo = TextView(this).apply {
            text = "When enabled, this phone may record sightings of FindMyDevice recovery beacons and cache the relay phone's own location."
            textSize = 12f
        }
        val save = Button(this).apply { text = "Save & Start Recovery" }
        val backgroundLocation = Button(this).apply { text = "Enable Background Location" }
        val secretView = TextView(this)
        status = TextView(this).apply { textSize = 14f }
        last = TextView(this)
        smsStatus = TextView(this).apply { textSize = 12f }

        layout.addView(title)
        layout.addView(ownerInput)
        layout.addView(relayOptIn)
        layout.addView(relayInfo)
        layout.addView(save)
        if (Build.VERSION.SDK_INT >= 29) layout.addView(backgroundLocation)
        layout.addView(secretView)
        layout.addView(status)
        layout.addView(last)
        layout.addView(smsStatus)
        setContentView(layout)

        ownerInput.setText(SecureStore.owner(this) ?: "")
        secretView.text = "Command secret: ${SecureStore.commandSecret(this)}\nKeep this secret."
        last.text = safeLastReport()
        smsStatus.text = "SMS diagnostics: ${SecureStore.smsStatus(this) ?: "No SMS command processed yet."}"
        updateStatus()

        save.setOnClickListener {
            SecureStore.setOwner(this, ownerInput.text.toString())
            RelaySettings.setEnabled(this, relayOptIn.isChecked)
            if (!foregroundLocationGranted()) {
                status.text = "Foreground location permission is required for recovery."
                permissionLauncher.launch(requestedPermissions())
                return@setOnClickListener
            }
            startRecovery()
        }

        backgroundLocation.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                    status.text = "In App permissions, set Location to Allow all the time."
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(refreshUi)
        uiHandler.post(refreshUi)
        if (::status.isInitialized) updateStatus()
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refreshUi)
        super.onPause()
    }

    private fun startRecovery() {
        if (!foregroundLocationGranted()) {
            status.text = "Recovery not started because foreground location permission is missing."
            return
        }
        SecureStore.setServiceActive(this, false)
        try {
            ContextCompat.startForegroundService(this, Intent(this, RecoveryService::class.java))
            updateStatus("Recovery engine started.")
        } catch (e: Exception) {
            status.text = "Could not start recovery service: ${e.javaClass.simpleName}"
        }
        last.text = safeLastReport()
        smsStatus.text = "SMS diagnostics: ${SecureStore.smsStatus(this) ?: "No SMS command processed yet."}"
    }

    private fun foregroundLocationGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun backgroundLocationGranted(): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun updateStatus(prefix: String? = null) {
        val base = prefix ?: "Recovery configuration"
        val background = if (backgroundLocationGranted()) "background location: READY" else "background location: NOT ENABLED"
        val sms = if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
            "SMS: READY"
        } else "SMS: NOT READY"
        status.text = "$base\n$background\n$sms"
    }

    private fun safeLastReport(): String = try {
        SecureStore.lastReport(this) ?: "No location report cached yet."
    } catch (_: Exception) {
        "No readable location report cached yet."
    }
}
