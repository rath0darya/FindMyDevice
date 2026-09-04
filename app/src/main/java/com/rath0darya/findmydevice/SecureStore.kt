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
 * Device-protected storage for recovery settings, reports, and pending SMS replies.
 * Long-term encryption keys are kept in Android Keystore.
 */
object SecureStore {
    private const val PREFS = "recovery_secure"
    private const val LEGACY_KEY_B64 = "aes_key"
    private const val LAST_REPORT = "last_report"
    private const val OWNER = "owner_number"
    private const val SECRET = "command_secret"
    private const val SMS_STATUS = "sms_status"
    private const val SERVICE_ACTIVE = "service_active"
    private const val PENDING_SMS_REPORT = "pending_sms_report"
    private const val PENDING_SMS_DESTINATION = "pending_sms_destination"
    private const val PENDING_SMS_PART_COUNT = "pending_sms_part_count"
    private const val PENDING_SMS_SUCCESS_COUNT = "pending_sms_success_count"
    private const val PENDING_SMS_FAILURE_COUNT = "pending_sms_failure_count"
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
        if (existing != null && isValidCommandSecret(existing)) return existing

        val random = SecureRandom()
        val chars = CharArray(COMMAND_SECRET_LENGTH) {
            COMMAND_SECRET_ALPHABET[random.nextInt(COMMAND_SECRET_ALPHABET.length)]
        }
        chars[0] = ('A'.code + random.nextInt(26)).toChar()
        chars[1] = ('0'.code + random.nextInt(10)).toChar()
        for (index in chars.lastIndex downTo 1) {
            val swapIndex = random.nextInt(index + 1)
            val swap = chars[index]
            chars[index] = chars[swapIndex]
            chars[swapIndex] = swap
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

    fun setServiceActive(context: Context, active: Boolean) =
        prefs(context).edit().putBoolean(SERVICE_ACTIVE, active).apply()

    fun isServiceActive(context: Context): Boolean =
        prefs(context).getBoolean(SERVICE_ACTIVE, false)

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

    fun savePendingSms(context: Context, destination: String, report: String) {
        val p = prefs(context)
        p.edit()
            .putString(PENDING_SMS_DESTINATION, encrypt(key(), destination))
            .putString(PENDING_SMS_REPORT, encrypt(key(), report))
            .putInt(PENDING_SMS_PART_COUNT, 0)
            .putInt(PENDING_SMS_SUCCESS_COUNT, 0)
            .putInt(PENDING_SMS_FAILURE_COUNT, 0)
            .apply()
    }

    fun pendingSms(context: Context): Pair<String, String>? {
        val p = prefs(context)
        val destination = p.getString(PENDING_SMS_DESTINATION, null) ?: return null
        val report = p.getString(PENDING_SMS_REPORT, null) ?: return null
        return try {
            decrypt(key(), destination) to decrypt(key(), report)
        } catch (_: Exception) {
            null
        }
    }

    fun pendingSmsPartCount(context: Context): Int = prefs(context).getInt(PENDING_SMS_PART_COUNT, 0)
    fun pendingSmsSuccessCount(context: Context): Int = prefs(context).getInt(PENDING_SMS_SUCCESS_COUNT, 0)
    fun pendingSmsFailureCount(context: Context): Int = prefs(context).getInt(PENDING_SMS_FAILURE_COUNT, 0)

    fun setPendingSmsPartCount(context: Context, count: Int) =
        prefs(context).edit().putInt(PENDING_SMS_PART_COUNT, count).apply()

    fun resetPendingSmsAttempt(context: Context) =
        prefs(context).edit()
            .putInt(PENDING_SMS_SUCCESS_COUNT, 0)
            .putInt(PENDING_SMS_FAILURE_COUNT, 0)
            .apply()

    fun incrementPendingSmsSuccess(context: Context): Int {
        val p = prefs(context)
        val value = p.getInt(PENDING_SMS_SUCCESS_COUNT, 0) + 1
        p.edit().putInt(PENDING_SMS_SUCCESS_COUNT, value).apply()
        return value
    }

    fun incrementPendingSmsFailure(context: Context): Int {
        val p = prefs(context)
        val value = p.getInt(PENDING_SMS_FAILURE_COUNT, 0) + 1
        p.edit().putInt(PENDING_SMS_FAILURE_COUNT, value).apply()
        return value
    }

    fun clearPendingSms(context: Context) =
        prefs(context).edit()
            .remove(PENDING_SMS_DESTINATION)
            .remove(PENDING_SMS_REPORT)
            .remove(PENDING_SMS_PART_COUNT)
            .remove(PENDING_SMS_SUCCESS_COUNT)
            .remove(PENDING_SMS_FAILURE_COUNT)
            .apply()

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
