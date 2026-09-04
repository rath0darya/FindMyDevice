package com.rath0darya.findmydevice

data class LocationReport(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val confidence: Int,
    val confidenceLevel: String,
    val sources: List<String>,
    val timestampMs: Long,
    val batteryPct: Int,
    val internet: Boolean,
    val wifiConnected: Boolean,
    val simPresent: Boolean,
    val cellularCells: Int,
    val bluetoothDevices: Int,
    val wifiNetworks: Int
)
