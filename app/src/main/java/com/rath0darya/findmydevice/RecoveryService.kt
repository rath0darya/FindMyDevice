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
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlin.math.max

class RecoveryService : Service() {
    companion object {
        const val ACTION_REFRESH = "com.rath0darya.findmydevice.REFRESH"
        const val ACTION_REFRESH_SIGNAL = "com.rath0darya.findmydevice.REFRESH_SIGNAL"
        private const val CHANNEL = "recovery"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_TIME_MS = 5_000L
        private const val MIN_DISTANCE_M = 5f
        private const val FRESH_LOCATION_TIMEOUT_MS = 30_000L
    }

    private lateinit var locationManager: LocationManager
    private lateinit var fusedClient: FusedLocationProviderClient
    private val latest = AtomicReference<Location?>(null)
    private val bleCount = AtomicInteger(0)
    private val handler = Handler(Looper.getMainLooper())
    private var relayEngine: OfflineRelayEngine? = null
    private var relayBeacon: OfflineBleRelay? = null
    private var pendingReplyTo: String? = null
    private var pendingReplyStartedAt = 0L
    private var locationAttemptId = 0L
    private val replyTimeout = Runnable { finishPendingReplyWithCachedLocation() }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_REFRESH_SIGNAL) return
            handleRefresh(intent)
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            acceptLocation(location, "LocationManager/${location.provider}")
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
        SecureStore.setServiceActive(this, true)
        locationManager = getSystemService(LocationManager::class.java)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        ContextCompat.registerReceiver(
            this,
            refreshReceiver,
            IntentFilter(ACTION_REFRESH_SIGNAL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (!hasLocationPermission()) {
            SecureStore.setSmsStatus(this, "LOCATION_ERROR: runtime location permission is not granted")
        } else if (!isLocationEnabled()) {
            SecureStore.setSmsStatus(this, "LOCATION_ERROR: device Location Services are OFF")
        } else {
            SecureStore.setSmsStatus(this, "LOCATION_READY: Fused + GNSS + network providers initialized")
            requestLocation()
            requestBestLocation(false)
        }

        scanNearby()
        updateRelayEngine()
        persistReport()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) handleRefresh(intent)
        return START_STICKY
    }

    private fun handleRefresh(intent: Intent) {
        pendingReplyTo = intent.getStringExtra(SmsCommandReceiver.EXTRA_REPLY_TO)
        pendingReplyStartedAt = System.currentTimeMillis()
        locationAttemptId++
        val attempt = locationAttemptId
        handler.removeCallbacks(replyTimeout)

        if (!hasLocationPermission()) {
            SecureStore.setSmsStatus(this, "SMS_LOCATION: runtime location permission is NOT granted")
            finishPendingReplyWithCachedLocation()
            return
        }
        if (!isLocationEnabled()) {
            SecureStore.setSmsStatus(this, "SMS_LOCATION: device Location Services are OFF")
            finishPendingReplyWithCachedLocation()
            return
        }

        SecureStore.setSmsStatus(this, "SMS_LOCATION: acquiring fresh location via Fused + GNSS + network")
        requestLocation()
        requestBestLocation(true)
        scanNearby()
        updateRelayEngine()
        handler.postDelayed({
            if (locationAttemptId == attempt && pendingReplyTo != null) finishPendingReplyWithCachedLocation()
        }, FRESH_LOCATION_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        if (!hasLocationPermission() || !isLocationEnabled()) return
        var registered = 0
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    listener,
                    mainLooper
                )
                registered++
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { acceptCachedLocation(it, "GPS last-known") }
            }
        } catch (e: Exception) {
            SecureStore.setSmsStatus(this, "LOCATION_DIAGNOSTIC: GNSS unavailable (${e.javaClass.simpleName})")
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    listener,
                    mainLooper
                )
                registered++
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { acceptCachedLocation(it, "Network last-known") }
            }
        } catch (e: Exception) {
            SecureStore.setSmsStatus(this, "LOCATION_DIAGNOSTIC: network provider unavailable (${e.javaClass.simpleName})")
        }

        if (registered == 0) {
            SecureStore.setSmsStatus(this, "LOCATION_ERROR: no enabled LocationManager provider")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestBestLocation(forReply: Boolean) {
        if (!hasLocationPermission()) return
        if (!isLocationEnabled()) return

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(FRESH_LOCATION_TIMEOUT_MS - 2_000L)
            .setMaxUpdateAgeMillis(5_000L)
            .build()
        val cancellation = CancellationTokenSource()

        SecureStore.setSmsStatus(
            this,
            if (forReply) "SMS_LOCATION: FusedLocationProvider current-location request started" else "LOCATION_READY: initial FusedLocationProvider request started"
        )

        try {
            fusedClient.getCurrentLocation(request, cancellation.token)
                .addOnSuccessListener(mainExecutor) { location ->
                    if (location != null && usable(location)) {
                        acceptLocation(location, "FusedLocationProvider")
                        if (forReply && pendingReplyTo != null) {
                            finishPendingReply(location)
                        }
                    } else {
                        fusedClient.lastLocation
                            .addOnSuccessListener(mainExecutor) { cached ->
                                if (cached != null && usable(cached)) acceptCachedLocation(cached, "Fused last-known")
                            }
                            .addOnFailureListener(mainExecutor) {
                                SecureStore.setSmsStatus(this, "LOCATION_DIAGNOSTIC: Fused last-known failed (${it.javaClass.simpleName})")
                            }
                    }
                }
                .addOnFailureListener(mainExecutor) {
                    SecureStore.setSmsStatus(this, "LOCATION_ERROR: Fused current-location failed (${it.javaClass.simpleName})")
                    fusedClient.lastLocation
                        .addOnSuccessListener(mainExecutor) { cached ->
                            if (cached != null && usable(cached)) acceptCachedLocation(cached, "Fused last-known after error")
                        }
                }
        } catch (e: Exception) {
            SecureStore.setSmsStatus(this, "LOCATION_ERROR: Fused request threw ${e.javaClass.simpleName}")
        }
    }

    private fun acceptCachedLocation(location: Location, source: String) {
        if (!usable(location)) return
        val old = latest.get()
        if (old == null || location.time > old.time || location.accuracy < old.accuracy) {
            latest.set(location)
            persistReport()
        }
        SecureStore.setSmsStatus(this, "LOCATION_CACHE: $source fix age=${max(0L, System.currentTimeMillis() - location.time)}ms")
    }

    private fun acceptLocation(location: Location, source: String) {
        if (!usable(location)) return
        val old = latest.get()
        if (old == null || location.accuracy <= old.accuracy || location.time >= old.time) {
            latest.set(location)
            persistReport()
        }
        if (pendingReplyTo != null && location.time >= pendingReplyStartedAt - 2_000L) {
            SecureStore.setSmsStatus(this, "SMS_LOCATION: fresh fix received from $source accuracy=±${location.accuracy.toInt()}m")
            finishPendingReply(location)
        }
    }

    private fun usable(location: Location): Boolean =
        location.latitude in -90.0..90.0 &&
            location.longitude in -180.0..180.0 &&
            location.hasAccuracy() && location.accuracy > 0f

    private fun isLocationEnabled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 28) locationManager.isLocationEnabled
        else locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) {
        false
    }

    private fun finishPendingReply(location: Location) {
        val destination = pendingReplyTo ?: return
        pendingReplyTo = null
        handler.removeCallbacks(replyTimeout)
        val report = buildReport(location)
        SecureStore.saveReport(this, report)
        SecureStore.setSmsStatus(this, "SMS_REPLY: fresh location acquired; queuing complete report")
        SmsReplySender.send(this, destination, report)
    }

    private fun finishPendingReplyWithCachedLocation() {
        val destination = pendingReplyTo ?: return
        pendingReplyTo = null
        handler.removeCallbacks(replyTimeout)
        val location = latest.get()
        if (location != null && usable(location)) {
            val report = buildReport(location)
            SecureStore.saveReport(this, report)
            SecureStore.setSmsStatus(this, "SMS_REPLY: fresh acquisition timed out; sending latest cached fix")
            SmsReplySender.send(this, destination, report)
        } else {
            val report = "DEVICE LOCATION\nNo usable location fix available.\nReason: ${locationFailureReason()}"
            SecureStore.saveReport(this, report)
            SecureStore.setSmsStatus(this, "SMS_LOCATION: no usable location fix after 30s")
            SmsReplySender.send(this, destination, report)
        }
    }

    private fun locationFailureReason(): String = when {
        !hasLocationPermission() -> "location permission not granted"
        !isLocationEnabled() -> "device Location Services are OFF"
        else -> "no provider returned a usable fix"
    }

    @SuppressLint("MissingPermission")
    private fun scanNearby() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        try {
            val adapter = getSystemService(BluetoothAdapter::class.java) ?: return
            if (!adapter.isEnabled) return
            bleCount.set(0)
            adapter.bluetoothLeScanner?.startScan(bleCallback)
            handler.postDelayed({
                try { adapter.bluetoothLeScanner?.stopScan(bleCallback) } catch (_: Exception) {}
                persistReport()
            }, 8_000L)
        } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun updateRelayEngine() {
        if (!RelaySettings.isEnabled(this)) {
            relayEngine?.stop()
            relayEngine = null
            relayBeacon?.stopAdvertising()
            relayBeacon = null
            return
        }
        if (relayEngine == null) relayEngine = OfflineRelayEngine(this)
        relayEngine?.start()
        if (relayBeacon == null) relayBeacon = OfflineBleRelay(this)
        relayBeacon?.advertise()
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
        sources += "FUSED/LOCATION"
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
            appendLine("Sources: ${sources.joinToString(", ")}")
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

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Device recovery", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setContentTitle("Find My Device")
        .setContentText("Recovery engine active")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        SecureStore.setServiceActive(this, false)
        handler.removeCallbacks(replyTimeout)
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
        try { fusedClient.flushLocations() } catch (_: Exception) {}
        try { relayEngine?.stop() } catch (_: Exception) {}
        try { relayBeacon?.stopAdvertising() } catch (_: Exception) {}
        try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
