package com.rath0darya.findmydevice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class RecoveryService : Service() {
    companion object {
        const val ACTION_REFRESH = "com.rath0darya.findmydevice.REFRESH"
        private const val CHANNEL = "recovery"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_TIME_MS = 30_000L
        private const val MIN_DISTANCE_M = 10f
    }

    private lateinit var locationManager: LocationManager
    private val latest = AtomicReference<Location?>(null)
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!location.hasAccuracy() || location.accuracy <= 0f) return
            val old = latest.get()
            if (old == null || location.accuracy <= old.accuracy || location.time >= old.time) {
                latest.set(location)
                persistReport()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        locationManager = getSystemService(LocationManager::class.java)
        requestLocation()
        persistReport()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            requestLocation()
            persistReport()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        if (!hasLocationPermission()) return
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M, listener, mainLooper)
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { latest.set(it) }
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M, listener, mainLooper)
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { candidate ->
                    val old = latest.get()
                    if (old == null || candidate.accuracy < old.accuracy) latest.set(candidate)
                }
            }
        } catch (_: SecurityException) { }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun persistReport() {
        val loc = latest.get()
        val report = if (loc != null) buildReport(loc) else "NO_LOCATION_FIX"
        SecureStore.saveReport(this, report)
    }

    private fun buildReport(location: Location): String {
        val sources = mutableListOf<String>()
        if (location.provider.equals(LocationManager.GPS_PROVIDER, true)) sources += "GNSS"
        if (location.provider.equals(LocationManager.NETWORK_PROVIDER, true)) sources += "NETWORK/CELL"
        val cellCount = cellCount()
        val wifiCount = wifiCount()
        val ble = "BLE" // BLE scan results are intentionally not used as a location oracle.
        if (cellCount > 0) sources += "$cellCount CELLULAR_CELLS"
        if (wifiCount > 0) sources += "$wifiCount WIFI_NETWORKS"
        val confidence = confidence(location, cellCount, wifiCount)
        val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val internet = hasInternet()
        val wifiConnected = isWifiConnected()
        val sim = simPresent()
        val time = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss z", Locale.US).format(Date(location.time))
        val map = "https://www.google.com/maps?q=${location.latitude},${location.longitude}"
        return buildString {
            appendLine("DEVICE LOCATION")
            appendLine("Coordinates: %.6f, %.6f".format(Locale.US, location.latitude, location.longitude))
            appendLine("Accuracy: ±%.0f metres".format(Locale.US, location.accuracy))
            appendLine("Confidence: $confidence% — ${confidenceLevel(confidence)}")
            appendLine("Sources: ${if (sources.isEmpty()) "GNSS/NETWORK unavailable" else sources.joinToString(", ")}")
            appendLine("Timestamp: $time")
            appendLine("Battery: ${max(0, battery)}%")
            appendLine("Internet: ${if (internet) "AVAILABLE" else "OFFLINE"}")
            appendLine("Wi-Fi: ${if (wifiConnected) "CONNECTED" else "NOT CONNECTED"}")
            appendLine("SIM: ${if (sim) "DETECTED" else "NOT DETECTED"}")
            appendLine("Map: $map")
            appendLine("Status: ${if (confidence >= 75) "LOCATION VERIFIED" else "LOCATION ESTIMATED"}")
        }
    }

    private fun confidence(location: Location, cells: Int, wifi: Int): Int {
        var score = when {
            location.accuracy <= 10f -> 75
            location.accuracy <= 25f -> 65
            location.accuracy <= 50f -> 55
            location.accuracy <= 100f -> 45
            location.accuracy <= 500f -> 30
            else -> 15
        }
        score += when { cells >= 3 -> 10; cells >= 1 -> 5; else -> 0 }
        score += when { wifi >= 4 -> 10; wifi >= 1 -> 5; else -> 0 }
        return score.coerceIn(0, 99)
    }

    private fun confidenceLevel(score: Int) = when {
        score >= 90 -> "VERY HIGH"
        score >= 75 -> "HIGH"
        score >= 50 -> "MEDIUM"
        score >= 25 -> "LOW"
        else -> "VERY LOW"
    }

    @SuppressLint("MissingPermission")
    private fun cellCount(): Int = try {
        val tm = getSystemService(TelephonyManager::class.java)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            tm.allCellInfo?.size ?: 0
        } else 0
    } catch (_: Exception) { 0 }

    @SuppressLint("MissingPermission")
    private fun wifiCount(): Int = try {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        wm.scanResults?.size ?: 0
    } catch (_: Exception) { 0 }

    private fun hasInternet(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    @SuppressLint("MissingPermission")
    private fun simPresent(): Boolean = try {
        val tm = getSystemService(TelephonyManager::class.java)
        tm.simState == TelephonyManager.SIM_STATE_READY
    } catch (_: Exception) { false }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Device recovery", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setContentTitle("Find My Device")
        .setContentText("Recovery engine active")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        try { locationManager.removeUpdates(listener) } catch (_: Exception) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
