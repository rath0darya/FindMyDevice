package com.rath0darya.findmydevice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Device-protected storage for recovery settings and the last report.
 * Long-term report encryption keys are kept in Android Keystore.
 */
object SecureStore {
    private const val PREFS = "recovery_secure"
    private const val LEGACY_KEY_B64 = "aes_key"
    private const val LAST_REPORT = "last_report"
    private const val OWNER = "owner_number"
    private const val SECRET = "command_secret"
    private const val SMS_STATUS = "sms_status"
    private const val KEY_ALIAS = "recovery_report_aes"
    private const val IV_SIZE = 12
    private const val COMMAND_SECRET_LENGTH = 32
    private const val COMMAND_SECRET_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

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

    fun setOwner(context: Context, number: String) =
        prefs(context).edit().putString(OWNER, number.trim()).apply()

    fun owner(context: Context): String? = prefs(context).getString(OWNER, null)

    fun commandSecret(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(SECRET, null)
        if (existing != null && isValidCommandSecret(existing)) {
            return existing
        }

        val random = SecureRandom()
        val chars = CharArray(COMMAND_SECRET_LENGTH) {
            COMMAND_SECRET_ALPHABET[random.nextInt(COMMAND_SECRET_ALPHABET.length)]
        }

        // Guarantee that every generated secret contains both letters and numbers.
        chars[0] = ('A'.code + random.nextInt(26)).toChar()
        chars[1] = ('0'.code + random.nextInt(10)).toChar()

        // Shuffle so the guaranteed character positions are not predictable.
        for (index in chars.lastIndex downTo 1) {
            val swapIndex = random.nextInt(index + 1)
            val temp = chars[index]
            chars[index] = chars[swapIndex]
            chars[swapIndex] = temp
        }

        val value = String(chars)
        p.edit().putString(SECRET, value).apply()
        return value
    }

    private fun isValidCommandSecret(value: String): Boolean =
        value.length == COMMAND_SECRET_LENGTH &&
            value.all { it in COMMAND_SECRET_ALPHABET } &&
            value.any { it in 'A'..'Z' } &&
            value.any { it in '0'..'9' }

    fun setSmsStatus(context: Context, status: String) =
        prefs(context).edit().putString(SMS_STATUS, status.take(1000)).apply()

    fun smsStatus(context: Context): String? = prefs(context).getString(SMS_STATUS, null)

    fun saveReport(context: Context, report: String) {
        val p = prefs(context)
        val encrypted = encrypt(key(), report)
        p.edit().putString(LAST_REPORT, encrypted).remove(LEGACY_KEY_B64).apply()
    }

    fun lastReport(context: Context): String? {
        val p = prefs(context)
        val encoded = p.getString(LAST_REPORT, null) ?: return null
        return try {
            decrypt(key(), encoded)
        } catch (_: Exception) {
            migrateLegacyReport(context, encoded)
        }
    }

    private fun migrateLegacyReport(context: Context, encoded: String): String? {
        val p = prefs(context)
        val legacyKeyEncoded = p.getString(LEGACY_KEY_B64, null) ?: return null
        return try {
            val legacyKey = SecretKeySpec(
                Base64.decode(legacyKeyEncoded, Base64.NO_WRAP),
                KeyProperties.KEY_ALGORITHM_AES
            )
            val plaintext = decrypt(legacyKey, encoded)
            val migrated = encrypt(key(), plaintext)
            p.edit().putString(LAST_REPORT, migrated).remove(LEGACY_KEY_B64).apply()
            plaintext
        } catch (_: Exception) {
            null
        }
    }

    private fun encrypt(secretKey: SecretKey, text: String): String {
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(secretKey: SecretKey, encoded: String): String {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        require(all.size > IV_SIZE)
        val iv = all.copyOfRange(0, IV_SIZE)
        val data = all.copyOfRange(IV_SIZE, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), StandardCharsets.UTF_8)
    }
}
