package com.rath0darya.findmydevice

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * BLE discovery for the opt-in FindMyDevice relay network.
 * The beacon contains only an opaque target tag. Relay devices record their own
 * location and the sighting timestamp locally; no target location is broadcast.
 */
class OfflineBleRelay(private val context: Context) {
    companion object {
        private val SERVICE_UUID = UUID.fromString("6d8d7e90-5e6a-4e8b-9f5d-6d4c2a1b0f01")
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val advertiser: BluetoothLeAdvertiser? get() = adapter?.bluetoothLeAdvertiser
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            if (!hasScanPermission()) return
            val data = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            if (data.size < 8) return
            val tag = data.copyOfRange(0, 8)
            if (!tag.contentEquals(OfflineRelayStore.targetTag(context))) return
            OfflineRelayStore.add(
                context,
                OfflineRelayStore.packet(System.currentTimeMillis(), tag)
            )
        }
    }

    fun startScanning() {
        if (!hasScanPermission() || scanner == null) return
        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            scanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (_: SecurityException) { }
    }

    fun stopScanning() {
        if (!hasScanPermission()) return
        try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
    }

    fun advertise(payload: ByteArray = ByteArray(0)) {
        if (!hasAdvertisePermission() || advertiser == null) return

        // Keep the legacy BLE advertisement small enough for devices with the
        // 31-byte legacy advertising limit. The payload is intentionally not broadcast.
        val serviceData = OfflineRelayStore.targetTag(context)
        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .setConnectable(false)
                .build()
            val data = AdvertiseData.Builder()
                .addServiceData(ParcelUuid(SERVICE_UUID), serviceData)
                .setIncludeDeviceName(false)
                .build()
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (_: SecurityException) { }
    }

    fun stopAdvertising() {
        if (!hasAdvertisePermission()) return
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: SecurityException) { }
    }

    private val advertiseCallback = object : AdvertiseCallback() {}

    private fun hasScanPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun hasAdvertisePermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
}
