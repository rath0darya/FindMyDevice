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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * Offline BLE discovery for devices that have opted into the FindMyDevice relay network.
 * Advertisements contain only an opaque target tag and encrypted/opaque payload.
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
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasBluetoothPermission()) return
            val data = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            if (data.size < 9) return
            val tag = data.copyOfRange(0, 8)
            if (!tag.contentEquals(OfflineRelayStore.targetTag(context))) return
            OfflineRelayStore.add(
                context,
                OfflineRelayStore.packet(System.currentTimeMillis(), data.copyOfRange(8, data.size))
            )
        }
    }

    fun startScanning() {
        if (!hasBluetoothPermission() || scanner == null) return
        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            scanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (_: SecurityException) { }
    }

    fun stopScanning() {
        if (!hasBluetoothPermission()) return
        try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
    }

    fun advertise(payload: ByteArray) {
        if (!hasBluetoothPermission() || advertiser == null) return
        val packet = payload.take(20).toByteArray()
        val serviceData = OfflineRelayStore.targetTag(context) + packet
        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .setConnectable(false)
                .build()
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .addServiceData(ParcelUuid(SERVICE_UUID), serviceData)
                .setIncludeDeviceName(false)
                .build()
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (_: SecurityException) { }
    }

    fun stopAdvertising() {
        if (!hasBluetoothPermission()) return
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: SecurityException) { }
    }

    private val advertiseCallback = object : AdvertiseCallback() {}

    private fun hasBluetoothPermission(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else true
}
