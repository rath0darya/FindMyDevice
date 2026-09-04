package com.rath0darya.findmydevice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores BLE relay sightings encrypted at rest. The relay never learns the target's
 * location or command secret. Packets are authenticated with AES-GCM and a Keystore key.
 */
object OfflineRelayStore {
    private const val PREFS = "offline_relay"
    private const val PACKETS = "packets"
    private const val MAX_PACKETS = 200
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    private const val KEY_ALIAS = "offline_relay_aes"
    private const val IV_SIZE = 12

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun targetTag(context: Context): ByteArray {
        val secret = SecureStore.commandSecret(context)
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(("FMD-TAG:" + secret).toByteArray(StandardCharsets.UTF_8)).copyOf(8)
    }

    @Synchronized
    fun add(context: Context, packet: ByteArray) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val old = p.getStringSet(PACKETS, emptySet())?.toMutableList() ?: mutableListOf()
        old.removeAll { decodeTime(it) < now - MAX_AGE_MS }
        old.add(encrypt(packet))
        p.edit().putStringSet(PACKETS, old.takeLast(MAX_PACKETS).toSet()).apply()
    }

    fun count(context: Context): Int = prefs(context).getStringSet(PACKETS, emptySet())?.size ?: 0

    private fun encrypt(packet: ByteArray): String {
        val iv = ByteArray(IV_SIZE)
        java.security.SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(packet)
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decodeTime(encoded: String): Long {
        return try {
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
            if (encrypted.size <= IV_SIZE) {
                0L
            } else {
                val iv = encrypted.copyOfRange(0, IV_SIZE)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                val packet = cipher.doFinal(encrypted.copyOfRange(IV_SIZE, encrypted.size))
                if (packet.size < 8) 0L else ByteBuffer.wrap(packet, 0, 8).long
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun packet(timestamp: Long, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putLong(timestamp).put(payload).array()
}
