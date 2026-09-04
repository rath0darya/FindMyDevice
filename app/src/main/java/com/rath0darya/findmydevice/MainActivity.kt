package com.rath0darya.findmydevice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var ownerInput: EditText
    private lateinit var status: TextView
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
        val save = Button(this).apply { text = "Save & Start Recovery" }
        val secretView = TextView(this)
        status = TextView(this).apply { textSize = 14f }
        val last = TextView(this)
        layout.addView(title)
        layout.addView(ownerInput)
        layout.addView(save)
        layout.addView(secretView)
        layout.addView(status)
        layout.addView(last)
        setContentView(layout)

        ownerInput.setText(SecureStore.owner(this) ?: "")
        secretView.text = "Command secret: ${SecureStore.commandSecret(this)}\nKeep this secret."
        last.text = SecureStore.lastReport(this) ?: "No location report cached yet."
        status.text = "Grant permissions, save the control number, then keep recovery enabled."

        save.setOnClickListener {
            if (!allPermissionsGranted()) {
                ActivityCompat.requestPermissions(this, permissions, 42)
            }
            SecureStore.setOwner(this, ownerInput.text.toString())
            try {
                ContextCompat.startForegroundService(this, Intent(this, RecoveryService::class.java))
                status.text = "Recovery engine started. Reboot persistence is enabled where Android permits it."
            } catch (e: Exception) {
                status.text = "Could not start recovery service: ${e.javaClass.simpleName}"
            }
            last.text = SecureStore.lastReport(this) ?: "Waiting for a location fix..."
        }
    }

    private fun allPermissionsGranted() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
