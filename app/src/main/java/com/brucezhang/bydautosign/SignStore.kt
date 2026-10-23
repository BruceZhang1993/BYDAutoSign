package com.brucezhang.bydautosign

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.time.LocalDate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Session data belongs to the ordinary app sandbox, NOT /data/adb or external storage.
 * The AES key stays in hardware-backed AndroidKeyStore when supported by the device.
 * Auto Backup is disabled to avoid restoring ciphertext without its device-local key.
 */
internal class SignStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("byd_native_sign", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun saveSession(value: String) {
        require(value.isNotBlank() && value.length <= 8192)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        if (!prefs.edit().putString("session_v1", Base64.getEncoder().encodeToString(bytes)).commit()) {
            throw IllegalStateException("会话保存失败")
        }
    }

    @Synchronized
    fun session(): String? {
        val value = prefs.getString("session_v1", null) ?: return null
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size > 12 + 16) { "会话文件无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    @Synchronized fun clearSession() {
        prefs.edit().remove("session_v1").commit()
    }

    var signedDate: String
        get() = prefs.getString("signed_date", "") ?: ""
        set(value) { prefs.edit().putString("signed_date", value).apply() }

    var streak: Int
        get() = prefs.getInt("streak", 0)
        set(value) { prefs.edit().putInt("streak", value).apply() }

    var lastResult: String
        get() = prefs.getString("last_result", "尚未执行") ?: "尚未执行"
        set(value) { prefs.edit().putString("last_result", value.take(150)).apply() }

    var lastRun: Long
        get() = prefs.getLong("last_run", 0L)
        set(value) { prefs.edit().putLong("last_run", value).apply() }

    var times: String
        get() = prefs.getString("times", "00:01,07:01,13:01,22:01")
            ?: "00:01,07:01,13:01,22:01"
        set(value) { prefs.edit().putString("times", value).commit() }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).commit() }

    fun signedToday() = signedDate == LocalDate.now().toString()

    companion object {
        private const val KEY_ALIAS = "bydautosign_session_aes_v1"
    }
}

internal data class ActionResult(val ok: Boolean, val message: String, val retry: Boolean = false)

internal object SignCoordinator {
    private val lock = Any()
    private val engine by lazy { NativeSignEngine() }

    fun sign(context: Context): ActionResult = synchronized(lock) {
        val store = SignStore(context)
        if (store.signedToday()) return@synchronized ActionResult(true, "今日已签到，自动跳过")
        val session = try { store.session() }
            catch (_: Exception) { return@synchronized ActionResult(false, "本地会话不可读取，请重新授权") }
            ?: return@synchronized ActionResult(false, "尚未授权，请先通过 Binder 获取会话")
        val outcome = engine.sign(session)
        if (outcome.success) {
            store.signedDate = LocalDate.now().toString()
            store.streak = outcome.days
        } else if (outcome.authorizationExpired) {
            store.clearSession()
        }
        val message = if (outcome.success) "签到成功 · 连续${outcome.days}天" else outcome.message
        store.lastRun = System.currentTimeMillis()
        store.lastResult = message
        ActionResult(outcome.success, message, outcome.shouldRetry)
    }
}
