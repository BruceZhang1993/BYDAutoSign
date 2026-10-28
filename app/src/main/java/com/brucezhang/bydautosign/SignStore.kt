package com.brucezhang.bydautosign

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
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
        if (!prefs.edit().putString("session_v1", Base64.getEncoder().encodeToString(bytes))
                .putLong("session_authorized_at", System.currentTimeMillis())
                .remove("session_suspected_invalid_at")
                .remove("automatic_block_kind").remove("automatic_block_at").commit()) {
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
        prefs.edit().remove("session_v1").remove("session_authorized_at")
            .remove("session_suspected_invalid_at").commit()
    }

    val lastAuthorizedAt: Long
        get() = prefs.getLong("session_authorized_at", 0L)

    /** Heuristic only: do not erase the saved session on ret=50010. */
    val sessionSuspectedInvalid: Boolean
        get() = prefs.getLong("session_suspected_invalid_at", 0L) >= lastAuthorizedAt &&
            prefs.getLong("session_suspected_invalid_at", 0L) > 0L

    fun markSessionSuspectedInvalid(at: Long) {
        prefs.edit().putLong("session_suspected_invalid_at", at).commit()
    }

    /** Old saved sessions and history remain compatible across upgrades. */
    val dayPhase: SignDayPhase
        get() {
            if (signedToday()) return SignDayPhase.COMPLETE
            if (prefs.getString("sign_phase_day", "") != SignDatePolicy.completedLocalDate(
                    System.currentTimeMillis())) return SignDayPhase.WAITING_TIME
            return runCatching {
                enumValueOf<SignDayPhase>(prefs.getString("sign_phase", "") ?: "")
            }.getOrDefault(SignDayPhase.WAITING_TIME)
        }

    val lastDayStartedAt: Long
        get() = prefs.getLong("sign_phase_started", 0L)

    fun markDayPhase(phase: SignDayPhase, at: Long = System.currentTimeMillis()) {
        val edit = prefs.edit()
            .putString("sign_phase_day", SignDatePolicy.completedLocalDate(at))
            .putString("sign_phase", phase.name)
        if (phase == SignDayPhase.RUNNING) edit.putLong("sign_phase_started", at)
        check(edit.commit()) { "无法保存当天签到状态" }
    }

    var lastSignOpportunityAt: Long
        get() = prefs.getLong("sign_opportunity_at", 0L)
        set(value) {
            check(prefs.edit().putLong("sign_opportunity_at", value).commit()) {
                "无法保存恢复调度时间"
            }
        }

    val lastAutoRefreshAt: Long
        get() = prefs.getLong("auto_refresh_at", 0L)

    /** Debounces reconnect-triggered compensation; never stores any credentials. */
    var lastHookRecoveryAt: Long
        get() = prefs.getLong("hook_recovery_at", 0L)
        set(value) {
            check(prefs.edit().putLong("hook_recovery_at", value).commit()) {
                "无法保存重连恢复时间"
            }
        }

    val lastAutoRefreshStatus: AutoRefreshStatus
        get() = runCatching {
            enumValueOf<AutoRefreshStatus>(prefs.getString("auto_refresh_status", "") ?: "")
        }.getOrDefault(AutoRefreshStatus.NEVER)

    val lastAutoRefreshCode: Int?
        get() = if (prefs.contains("auto_refresh_code")) {
            AutoRefreshDiagnostics.safeCode(prefs.getInt("auto_refresh_code", 0))
        } else null

    fun recordAutoRefresh(status: AutoRefreshStatus, code: Int? = null) {
        val edit = prefs.edit().putLong("auto_refresh_at", System.currentTimeMillis())
            .putString("auto_refresh_status", status.name)
        val safe = AutoRefreshDiagnostics.safeCode(code)
        if (safe != null) edit.putInt("auto_refresh_code", safe)
        else edit.remove("auto_refresh_code")
        check(edit.commit()) { "无法保存授权刷新诊断" }
    }

    /** Automatic retries should stop after nonrecoverable errors, but manual sign remains available. */
    fun automaticBlockKind(now: Long = System.currentTimeMillis()): SignFailureKind? {
        val kind = runCatching {
            enumValueOf<SignFailureKind>(prefs.getString("automatic_block_kind", "") ?: "")
        }.getOrNull() ?: return null
        val blockedAt = prefs.getLong("automatic_block_at", 0L)
        return kind.takeIf { SignRecoveryPolicy.isBlocked(it, blockedAt, now) }
    }

    fun markAutomaticBlock(kind: SignFailureKind, now: Long) {
        if (SignRecoveryPolicy.blockMode(kind) == AutomaticBlockMode.NONE) return
        prefs.edit().putString("automatic_block_kind", kind.name)
            .putLong("automatic_block_at", now).commit()
    }

    fun clearAutomaticBlock() {
        prefs.edit().remove("automatic_block_kind").remove("automatic_block_at").commit()
    }

    var signedDate: String
        get() = prefs.getString("signed_date", "") ?: ""
        // Critical for idempotence: publish the completed day to disk before releasing the lock.
        set(value) { check(prefs.edit().putString("signed_date", value).commit()) }

    var streak: Int
        get() = prefs.getInt("streak", 0)
        set(value) { prefs.edit().putInt("streak", value).apply() }

    var lastResult: String
        get() = prefs.getString("last_result", "尚未执行") ?: "尚未执行"
        set(value) { prefs.edit().putString("last_result", value.take(150)).apply() }

    var lastRun: Long
        get() = prefs.getLong("last_run", 0L)
        set(value) { prefs.edit().putLong("last_run", value).apply() }

    /** Read the original value when cleaning up alarms created by older versions. */
    val rawTimes: String
        get() = prefs.getString("times", "07:01") ?: "07:01"

    var times: String
        get() {
            val original = rawTimes
            val migrated = ScheduleTimes.migrate(original)
            if (migrated != original) check(prefs.edit().putString("times", migrated).commit())
            return migrated
        }
        set(value) {
            check(prefs.edit().putString("times", requireNotNull(ScheduleTimes.normalize(value))).commit())
        }

    /** An hourly retry is meaningful only for the local day on which it was created. */
    var retryAt: Long
        get() = prefs.getLong("retry_at", 0L)
        set(value) { check(prefs.edit().putLong("retry_at", value).commit()) }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).commit() }

    fun signedToday() = SignDatePolicy.wasSignedOn(signedDate, System.currentTimeMillis())

    companion object {
        private const val KEY_ALIAS = "bydautosign_session_aes_v1"
    }
}

internal data class ActionResult(
    val ok: Boolean,
    val message: String,
    val retry: Boolean = false,
    val failure: SignFailureKind = SignFailureKind.NONE,
    val skipped: Boolean = false,
    val awaitingAuthorization: Boolean = false,
    val requestSent: Boolean = true
)

/** Use the response completion instant, not the request start day, for the signed marker. */
internal object SignDatePolicy {
    fun completedLocalDate(completedAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate().toString()

    fun wasSignedOn(signedDate: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        signedDate == completedLocalDate(now, zone)

    /** Completion is the authoritative local day even if a request spans midnight. */
    fun crossesLocalDay(startedAt: Long, completedAt: Long, zone: ZoneId = ZoneId.systemDefault()) =
        completedLocalDate(startedAt, zone) != completedLocalDate(completedAt, zone)
}

internal object SignCoordinator {
    private val lock = Any()
    private val engine by lazy { NativeSignEngine() }

    fun sign(context: Context, origin: SignOrigin = SignOrigin.MANUAL, attempt: Int = 1): ActionResult =
        synchronized(lock) {
            val store = SignStore(context)
            // No actual sign attempt: neither history entry nor notification.
            if (store.signedToday()) return@synchronized ActionResult(
                true, "今日已签到，自动跳过", skipped = true
            )
            val startedAt = System.currentTimeMillis()
            store.markDayPhase(SignDayPhase.RUNNING, startedAt)
            val signedAttempt = AuthorizationManager.sign(context, store, engine, startedAt)
            val outcome = signedAttempt.outcome
            // Capture time *after* the response. A request crossing midnight must
            // not leave the new day unsigned and trigger a second scheduled request.
            val now = System.currentTimeMillis()
            val awaitingAuthorization = AuthorizationManager.awaitingAuthorization(outcome)
            if (outcome.success) {
                store.signedDate = SignDatePolicy.completedLocalDate(now)
                store.clearAutomaticBlock()
                if (!outcome.alreadySigned) store.streak = outcome.days
            } else {
                // ret=50010 is not a proven expiry signal, so retain encrypted
                // credentials while preventing futile hourly submissions.
                if (outcome.businessCode == 50010 || outcome.authorizationExpired) {
                    store.markSessionSuspectedInvalid(now)
                }
                if (!outcome.shouldRetry && !awaitingAuthorization) {
                    store.markAutomaticBlock(outcome.failure, now)
                }
            }
            store.markDayPhase(SignOpportunityPolicy.phaseAfter(
                outcome.success, awaitingAuthorization, startedAt, now
            ), now)
            if (SignDatePolicy.crossesLocalDay(startedAt, now)) {
                ScheduleTelemetry(context).crossDayAttempt()
            }
            val status = when {
                outcome.alreadySigned -> SignRecordStatus.ALREADY_SIGNED
                outcome.success -> SignRecordStatus.SUCCESS
                else -> SignRecordStatus.FAILED
            }
            val message = when {
                outcome.alreadySigned -> "今日已签到，无需重复执行"
                outcome.success -> "签到成功 · 连续${outcome.days}天"
                else -> outcome.message
            }
            store.lastRun = now
            store.lastResult = message
            // Only record an actual BYD sign request in SignHistory. Waiting
            // for a WeChat code is a scheduler/auth event, not a sign failure.
            if (signedAttempt.requestSent) {
                runCatching {
                    SignHistory(context).append(SignHistoryEntry(
                        now, origin, status, outcome.failure, attempt,
                        businessCode = outcome.businessCode,
                        serverMessage = outcome.serverMessage,
                        httpStatus = outcome.httpStatus
                    ))
                }
            }
            ActionResult(outcome.success, message,
                retry = outcome.shouldRetry || awaitingAuthorization,
                failure = outcome.failure, skipped = outcome.alreadySigned,
                awaitingAuthorization = awaitingAuthorization,
                requestSent = signedAttempt.requestSent)
        }
}
