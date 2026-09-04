package com.rath0darya.findmydevice

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecureStore {
    private const val PREFS = "recovery_secure"
    private const val KEY_B64 = "aes_key"
    private const val LAST_REPORT = "last_report"
    private const val OWNER = "owner_number"
    private const val SECRET = "command_secret"

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(context: Context): SecretKey {
        val p = prefs(context)
        val existing = p.getString(KEY_B64, null)
        if (existing != null) return SecretKeySpec(Base64.decode(existing, Base64.NO_WRAP), "AES")
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        val generated = generator.generateKey()
        p.edit().putString(KEY_B64, Base64.encodeToString(generated.encoded, Base64.NO_WRAP)).apply()
        return generated
    }

    fun setOwner(context: Context, number: String) = prefs(context).edit().putString(OWNER, number.trim()).apply()
    fun owner(context: Context): String? = prefs(context).getString(OWNER, null)

    fun commandSecret(context: Context): String {
        val p = prefs(context)
        p.getString(SECRET, null)?.let { return it }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val value = Base64.encodeToString(bytes, Base64.NO_WRAP)
        p.edit().putString(SECRET, value).apply()
        return value
    }

    fun saveReport(context: Context, report: String) {
        val encrypted = encrypt(key(context), report)
        prefs(context).edit().putString(LAST_REPORT, encrypted).apply()
    }

    fun lastReport(context: Context): String? = prefs(context).getString(LAST_REPORT, null)?.let { decrypt(key(context), it) }

    private fun encrypt(key: SecretKey, text: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(key: SecretKey, encoded: String): String {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val data = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), StandardCharsets.UTF_8)
    }
}
