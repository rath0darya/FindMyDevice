package com.rath0darya.findmydevice

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Opt-in relay-side engine. It listens only for FindMyDevice's own BLE service,
 * obtains the relay phone's last-known location, and stores an opaque sighting.
 * It does not inspect, decrypt, or identify the lost device's payload.
 */
class OfflineRelayEngine(private val context: Context) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6d8d7e90-5e6a-4e8b-9f5d-6d4c2a1b0f01")
    }

    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val service = result.scanRecord?.getServiceData(android.os.ParcelUuid(SERVICE_UUID)) ?: return
            if (service.size < 8) return
            val location = lastLocation() ?: return
            val payload = encodeSighting(service.copyOfRange(0, 8), location, result.rssi)
            val packet = OfflineRelayStore.packet(System.currentTimeMillis(), payload)
            OfflineRelayStore.add(context, packet)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        val scanner = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
            ?.adapter?.bluetoothLeScanner ?: return
        try {
            val filter = ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(SERVICE_UUID)).build()
            scanner.startScan(
                listOf(filter),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
                callback
            )
        } catch (_: SecurityException) { }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        try {
            context.getSystemService(android.bluetooth.BluetoothManager::class.java)
                ?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: SecurityException) { }
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Location? {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
                .minByOrNull { it.accuracy }
        } catch (_: Exception) { null }
    }

    private fun encodeSighting(tag: ByteArray, location: Location, rssi: Int): ByteArray {
        val safeTag = tag.copyOf(8)
        return ByteBuffer.allocate(8 + 8 + 8 + 4 + 4)
            .put(safeTag)
            .putDouble(location.latitude)
            .putDouble(location.longitude)
            .putFloat(location.accuracy)
            .putInt(rssi)
            .array()
    }
}
