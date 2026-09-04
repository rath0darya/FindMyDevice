package com.rath0darya.findmydevice

import android.location.Location
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic evidence scorer for the device's own location fix.
 * It never treats a count of nearby radios as an exact coordinate source.
 */
object LocationFusionEngine {
    data class Result(
        val accuracyMeters: Float,
        val confidence: Int,
        val level: String,
        val verified: Boolean
    )

    fun evaluate(
        location: Location?,
        cellularCells: Int,
        wifiNetworks: Int,
        bleObservations: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Result {
        if (location == null || !location.hasAccuracy() || location.accuracy <= 0f) {
            return Result(Float.POSITIVE_INFINITY, 0, "VERY LOW", false)
        }

        var score = when {
            location.accuracy <= 10f -> 70
            location.accuracy <= 25f -> 62
            location.accuracy <= 50f -> 54
            location.accuracy <= 100f -> 45
            location.accuracy <= 500f -> 30
            else -> 15
        }

        score += when {
            cellularCells >= 3 -> 10
            cellularCells >= 2 -> 6
            cellularCells == 1 -> 3
            else -> 0
        }
        score += when {
            wifiNetworks >= 5 -> 10
            wifiNetworks >= 2 -> 6
            wifiNetworks == 1 -> 3
            else -> 0
        }
        score += when {
            bleObservations >= 5 -> 5
            bleObservations >= 2 -> 3
            bleObservations == 1 -> 1
            else -> 0
        }

        val ageMs = max(0L, nowMs - location.time)
        score -= when {
            ageMs <= 30_000L -> 0
            ageMs <= 5 * 60_000L -> 3
            ageMs <= 30 * 60_000L -> 8
            ageMs <= 2 * 60 * 60_000L -> 15
            else -> 25
        }

        score = min(99, max(0, score))
        val level = when {
            score >= 90 -> "VERY HIGH"
            score >= 75 -> "HIGH"
            score >= 50 -> "MEDIUM"
            score >= 25 -> "LOW"
            else -> "VERY LOW"
        }

        return Result(location.accuracy, score, level, score >= 75 && location.accuracy <= 100f)
    }
}
