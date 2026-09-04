package com.rath0darya.findmydevice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var ownerInput: EditText
    private lateinit var status: TextView
    private lateinit var last: TextView
    private val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply { text = "Find My Device\nOffline Recovery"; textSize = 24f }
        ownerInput = EditText(this).apply { hint = "Your control phone number"; inputType = 3 }
        val relayOptIn = CheckBox(this).apply {
            text = "Participate in Nearby Relay"
            isChecked = RelaySettings.isEnabled(this@MainActivity)
        }
        val relayInfo = TextView(this).apply {
            text = "When enabled, this phone may record sightings of FindMyDevice recovery beacons and cache the relay phone's own location."
            textSize = 12f
        }
        val save = Button(this).apply { text = "Save & Start Recovery" }
        val secretView = TextView(this)
        status = TextView(this).apply { textSize = 14f }
        last = TextView(this)
        layout.addView(title)
        layout.addView(ownerInput)
        layout.addView(relayOptIn)
        layout.addView(relayInfo)
        layout.addView(save)
        layout.addView(secretView)
        layout.addView(status)
        layout.addView(last)
        setContentView(layout)

        ownerInput.setText(SecureStore.owner(this) ?: "")
        secretView.text = "Command secret: ${SecureStore.commandSecret(this)}\nKeep this secret."
        last.text = safeLastReport()
        status.text = "Grant permissions, save the control number, then keep recovery enabled."

        save.setOnClickListener {
            SecureStore.setOwner(this, ownerInput.text.toString())
            RelaySettings.setEnabled(this, relayOptIn.isChecked)
            if (!allPermissionsGranted()) {
                status.text = "Permission approval is required before recovery can start."
                ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS)
                return@setOnClickListener
            }
            startRecovery()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (allPermissionsGranted()) {
            startRecovery()
        } else {
            status.text = "Recovery not started because required permissions were not granted."
        }
    }

    private fun startRecovery() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, RecoveryService::class.java))
            status.text = if (RelaySettings.isEnabled(this)) {
                "Recovery engine started. Nearby relay participation is enabled."
            } else {
                "Recovery engine started. Nearby relay participation is disabled."
            }
        } catch (e: Exception) {
            status.text = "Could not start recovery service: ${e.javaClass.simpleName}"
        }
        last.text = safeLastReport()
    }

    private fun safeLastReport(): String = try {
        SecureStore.lastReport(this) ?: "No location report cached yet."
    } catch (_: Exception) {
        "No readable location report cached yet."
    }

    private fun allPermissionsGranted() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 42
    }
}
