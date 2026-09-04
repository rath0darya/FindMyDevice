package com.rath0darya.findmydevice

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Stores opaque BLE sightings locally. The relay never learns the target's location
 * or command secret. A future transport can upload these encrypted packets.
 */
object OfflineRelayStore {
    private const val PREFS = "offline_relay"
    private const val PACKETS = "packets"
    private const val MAX_PACKETS = 200
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    fun targetTag(context: Context): ByteArray {
        val secret = SecureStore.commandSecret(context)
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(("FMD-TAG:" + secret).toByteArray(StandardCharsets.UTF_8)).copyOf(8)
    }

    @Synchronized
    fun add(context: Context, packet: ByteArray) {
        val p = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val old = p.getStringSet(PACKETS, emptySet())?.toMutableList() ?: mutableListOf()
        old.removeAll { decodeTime(it) < now - MAX_AGE_MS }
        old.add(Base64.encodeToString(packet, Base64.NO_WRAP))
        val kept = old.takeLast(MAX_PACKETS).toSet()
        p.edit().putStringSet(PACKETS, kept).apply()
    }

    fun count(context: Context): Int = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(PACKETS, emptySet())?.size ?: 0

    private fun decodeTime(encoded: String): Long = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        if (bytes.size < 8) 0L else java.nio.ByteBuffer.wrap(bytes, 0, 8).long
    } catch (_: Exception) { 0L }

    fun packet(timestamp: Long, payload: ByteArray): ByteArray =
        java.nio.ByteBuffer.allocate(8 + payload.size).putLong(timestamp).put(payload).array()
}
