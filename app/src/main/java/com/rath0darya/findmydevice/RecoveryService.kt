package com.rath0darya.findmydevice

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.net.*
import android.net.wifi.WifiManager
import android.os.*
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlin.math.max

class RecoveryService : Service() {
    companion object {
        const val ACTION_REFRESH = "com.rath0darya.findmydevice.REFRESH"
        private const val CHANNEL = "recovery"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_TIME_MS = 30_000L
        private const val MIN_DISTANCE_M = 10f
        private const val FRESH_LOCATION_TIMEOUT_MS = 15_000L
    }

    private lateinit var locationManager: LocationManager
    private val latest = AtomicReference<Location?>(null)
    private val bleCount = AtomicInteger(0)
    private val handler = Handler(Looper.getMainLooper())
    private var relayEngine: OfflineRelayEngine? = null
    private var pendingReplyTo: String? = null
    private var pendingReplyStartedAt = 0L
    private val replyTimeout = Runnable { finishPendingReplyWithCachedLocation() }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!location.hasAccuracy() || location.accuracy <= 0f) return
            val old = latest.get()
            if (old == null || location.accuracy <= old.accuracy || location.time >= old.time) {
                latest.set(location)
                persistReport()
                if (pendingReplyTo != null && location.time >= pendingReplyStartedAt - 5_000L) {
                    finishPendingReply(location)
                }
            }
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { bleCount.incrementAndGet() }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { bleCount.addAndGet(results.size) }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        locationManager = getSystemService(LocationManager::class.java)
        requestLocation()
        scanNearby()
        updateRelayEngine()
        persistReport()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            pendingReplyTo = intent.getStringExtra(SmsCommandReceiver.EXTRA_REPLY_TO)
            pendingReplyStartedAt = System.currentTimeMillis()
            handler.removeCallbacks(replyTimeout)
            SecureStore.setSmsStatus(this, "SMS_COMMAND: acquiring fresh location")
            requestFreshLocation()
            scanNearby()
            updateRelayEngine()
            handler.postDelayed(replyTimeout, FRESH_LOCATION_TIMEOUT_MS)
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

    @SuppressLint("MissingPermission")
    private fun requestFreshLocation() {
        if (!hasLocationPermission()) {
            SecureStore.setSmsStatus(this, "SMS_LOCATION: location permission is NOT granted")
            finishPendingReplyWithCachedLocation()
            return
        }

        if (Build.VERSION.SDK_INT >= 30) {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { try { locationManager.isProviderEnabled(it) } catch (_: Exception) { false } }
            if (providers.isEmpty()) {
                SecureStore.setSmsStatus(this, "SMS_LOCATION: no enabled location provider")
                finishPendingReplyWithCachedLocation()
                return
            }
            requestCurrentProvider(providers, 0)
        } else {
            requestLocation()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestCurrentProvider(providers: List<String>, index: Int) {
        if (index >= providers.size || pendingReplyTo == null) return
        try {
            locationManager.getCurrentLocation(
                providers[index],
                null,
                mainExecutor,
                Consumer { location ->
                    if (location != null && location.hasAccuracy() && location.accuracy > 0f) {
                        latest.set(location)
                        persistReport()
                        finishPendingReply(location)
                    } else {
                        requestCurrentProvider(providers, index + 1)
                    }
                }
            )
        } catch (_: Exception) {
            requestCurrentProvider(providers, index + 1)
        }
    }

    private fun finishPendingReply(location: Location) {
        val destination = pendingReplyTo ?: return
        pendingReplyTo = null
        handler.removeCallbacks(replyTimeout)
        SecureStore.saveReport(this, buildReport(location))
        SecureStore.setSmsStatus(this, "SMS_REPLY: fresh location acquired; sending reply")
        SmsReplySender.send(this, destination, buildReport(location))
    }

    private fun finishPendingReplyWithCachedLocation() {
        val destination = pendingReplyTo ?: return
        pendingReplyTo = null
        handler.removeCallbacks(replyTimeout)
        val location = latest.get()
        if (location != null && location.hasAccuracy() && location.accuracy > 0f) {
            SecureStore.saveReport(this, buildReport(location))
            SecureStore.setSmsStatus(this, "SMS_REPLY: fresh fix timed out; sending latest cached location")
            SmsReplySender.send(this, destination, buildReport(location))
        } else {
            SecureStore.setSmsStatus(this, "SMS_LOCATION: no usable location fix after 15 seconds")
            SmsReplySender.send(this, destination, "DEVICE LOCATION\nNo usable location fix available.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun scanNearby() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        try {
            val adapter = getSystemService(BluetoothAdapter::class.java) ?: return
            if (!adapter.isEnabled) return
            bleCount.set(0)
            adapter.bluetoothLeScanner?.startScan(bleCallback)
            handler.postDelayed({ try { adapter.bluetoothLeScanner?.stopScan(bleCallback) } catch (_: Exception) {} ; persistReport() }, 8_000L)
        } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun updateRelayEngine() {
        if (!RelaySettings.isEnabled(this)) {
            relayEngine?.stop()
            relayEngine = null
            return
        }
        if (relayEngine == null) relayEngine = OfflineRelayEngine(this)
        relayEngine?.start()
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun persistReport() {
        val loc = latest.get()
        SecureStore.saveReport(this, if (loc != null) buildReport(loc) else "NO_LOCATION_FIX")
    }

    private fun buildReport(location: Location): String {
        val sources = mutableListOf<String>()
        if (location.provider.equals(LocationManager.GPS_PROVIDER, true)) sources += "GNSS"
        if (location.provider.equals(LocationManager.NETWORK_PROVIDER, true)) sources += "NETWORK/CELL"
        val cells = cellCount()
        val wifi = wifiCount()
        val ble = bleCount.get()
        if (cells > 0) sources += "$cells CELLULAR_CELLS"
        if (wifi > 0) sources += "$wifi WIFI_NETWORKS"
        if (ble > 0) sources += "$ble BLE_OBSERVATIONS"

        val fusion = LocationFusionEngine.evaluate(location, cells, wifi, ble)
        val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val time = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss z", Locale.US).format(Date(location.time))
        val map = "https://www.google.com/maps?q=${location.latitude},${location.longitude}"
        return buildString {
            appendLine("DEVICE LOCATION")
            appendLine("Coordinates: %.6f, %.6f".format(Locale.US, location.latitude, location.longitude))
            appendLine("Accuracy: ±%.0f metres".format(Locale.US, fusion.accuracyMeters))
            appendLine("Confidence: ${fusion.confidence}% — ${fusion.level}")
            appendLine("Sources: ${if (sources.isEmpty()) "NO SUPPORTING SOURCES" else sources.joinToString(", ")}")
            appendLine("Timestamp: $time")
            appendLine("Battery: ${max(0, battery)}%")
            appendLine("Internet: ${if (hasInternet()) "AVAILABLE" else "OFFLINE"}")
            appendLine("Wi-Fi: ${if (isWifiConnected()) "CONNECTED" else "NOT CONNECTED"}")
            appendLine("SIM: ${if (simPresent()) "DETECTED" else "NOT DETECTED"}")
            appendLine("Relay: ${if (RelaySettings.isEnabled(this@RecoveryService)) "OPTED IN" else "OFF"}")
            appendLine("Map: $map")
            appendLine("Status: ${if (fusion.verified) "LOCATION VERIFIED" else "LOCATION ESTIMATED"}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun cellCount() = try {
        val tm = getSystemService(TelephonyManager::class.java)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) tm.allCellInfo?.size ?: 0 else 0
    } catch (_: Exception) { 0 }

    @SuppressLint("MissingPermission")
    private fun wifiCount() = try { getSystemService(WifiManager::class.java).scanResults?.size ?: 0 } catch (_: Exception) { 0 }

    private fun hasInternet(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java); val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java); val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    @SuppressLint("MissingPermission")
    private fun simPresent() = try { getSystemService(TelephonyManager::class.java).simState == TelephonyManager.SIM_STATE_READY } catch (_: Exception) { false }

    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Device recovery", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification() = NotificationCompat.Builder(this, CHANNEL).setContentTitle("Find My Device").setContentText("Recovery engine active").setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build()
    override fun onDestroy() { handler.removeCallbacks(replyTimeout); try { relayEngine?.stop() } catch (_: Exception) {}; try { locationManager.removeUpdates(listener) } catch (_: Exception) {}; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
