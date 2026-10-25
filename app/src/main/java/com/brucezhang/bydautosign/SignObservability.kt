package com.brucezhang.bydautosign

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** Never persist raw service messages, codes, session IDs, or exception text. */
internal enum class SignFailureKind(val label: String) {
    NONE("无"), NO_SESSION("未授权"), SESSION_UNREADABLE("本地会话异常"),
    AUTH_EXPIRED("会话过期"), NETWORK("网络异常"), TIMEOUT("请求超时"),
    TLS("TLS 验证失败"), SERVER("服务端异常"), HTTP_CLIENT("请求被拒绝"),
    PROTOCOL("协议或响应异常"), BUSINESS("业务失败"), UNKNOWN("未知错误")
}
internal enum class SignOrigin(val label: String) {
    MANUAL("手动"), SCHEDULED("自动"), RESTORED("历史补录")
}
internal enum class SignRecordStatus(val label: String) {
    SUCCESS("成功"), FAILED("失败"), ALREADY_SIGNED("已签到")
}
internal data class SignHistoryEntry(
    val timestamp: Long,
    val origin: SignOrigin,
    val status: SignRecordStatus,
    val failure: SignFailureKind,
    val attempt: Int = 1
)

/** Only reconstruct a known completed request from a persisted timestamp; never
 * invent a sign request for a date that has no saved execution time. */
internal object SignHistoryRecovery {
    fun candidate(signedDate: String, lastRun: Long, lastResult: String,
                  existing: List<SignHistoryEntry>): SignHistoryEntry? {
        if (lastRun <= 0 || signedDate != SignDatePolicy.completedLocalDate(lastRun)) return null
        val status = when {
            lastResult.startsWith("签到成功") -> SignRecordStatus.SUCCESS
            lastResult.startsWith("今日已签到") -> SignRecordStatus.ALREADY_SIGNED
            else -> return null
        }
        if (existing.any {
                it.status != SignRecordStatus.FAILED &&
                    SignDatePolicy.completedLocalDate(it.timestamp) == signedDate
            }) return null
        return SignHistoryEntry(lastRun, SignOrigin.RESTORED, status, SignFailureKind.NONE)
    }
}

/** Bounded private history; only enums, timestamps and attempt counts are recorded. */
internal class SignHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("byd_sign_history", Context.MODE_PRIVATE)

    fun list(): List<SignHistoryEntry> = synchronized(monitor) {
        val array = try { JSONArray(prefs.getString("entries", "[]")) }
            catch (_: Exception) { JSONArray() }
        (0 until array.length()).mapNotNull { i ->
            try {
                val item = array.getJSONObject(i)
                SignHistoryEntry(
                    item.getLong("at"),
                    enumValueOf<SignOrigin>(item.getString("origin")),
                    enumValueOf<SignRecordStatus>(item.getString("status")),
                    enumValueOf<SignFailureKind>(item.getString("error")),
                    item.optInt("attempt", 1)
                )
            } catch (_: Exception) { null }
        }
    }

    fun recoverLastSuccess(signedDate: String, lastRun: Long, lastResult: String) =
        synchronized(monitor) {
            val recovered = SignHistoryRecovery.candidate(signedDate, lastRun, lastResult, list())
            if (recovered != null) append(recovered)
        }

    fun append(entry: SignHistoryEntry) = synchronized(monitor) {
        val array = JSONArray()
        array.put(JSONObject()
            .put("at", entry.timestamp)
            .put("origin", entry.origin.name)
            .put("status", entry.status.name)
            .put("error", entry.failure.name)
            .put("attempt", entry.attempt.coerceIn(1, 20)))
        list().take(MAX_ENTRIES - 1).forEach { previous ->
            array.put(JSONObject()
                .put("at", previous.timestamp)
                .put("origin", previous.origin.name)
                .put("status", previous.status.name)
                .put("error", previous.failure.name)
                .put("attempt", previous.attempt))
        }
        check(prefs.edit().putString("entries", array.toString()).commit()) { "无法保存签到历史" }
    }

    private companion object {
        const val MAX_ENTRIES = 100
        val monitor = Any()
    }
}

internal data class ScheduleSnapshot(
    val nextAt: Long = 0,
    val exact: Boolean = false,
    val lastAlarmAt: Long = 0,
    val lastAlarmDelayMs: Long = 0,
    val lastEnqueueAt: Long = 0,
    val lastStartAt: Long = 0,
    val lastFinishAt: Long = 0,
    val lastWatchdogAt: Long = 0,
    val lastCompensateAt: Long = 0,
    val lastState: String = "尚未运行",
    val lastRecoveryAt: Long = 0,
    val lastRecoverySource: String = "",
    val lastRecoveryDetail: String = "",
    val lastSuppressedAt: Long = 0,
    val lastSuppressedKind: String = "",
    val crossDayAttempts: Int = 0
)

/** Separate scheduled, fired, enqueued and actually executed timestamps. */
internal class ScheduleTelemetry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("byd_sign_telemetry", Context.MODE_PRIVATE)
    fun planned(next: Long, exact: Boolean) {
        prefs.edit().putLong("next", next).putBoolean("exact", exact).apply()
    }
    fun alarmFired(expectedAt: Long = 0L) {
        val now = System.currentTimeMillis()
        val planned = if (expectedAt > 0L) expectedAt else prefs.getLong("next", 0)
        prefs.edit().putLong("alarm", now)
            .putLong("delay", if (planned > 0) (now - planned).coerceAtLeast(0) else 0).apply()
    }
    fun enqueued() { prefs.edit().putLong("enqueued", System.currentTimeMillis()).apply() }
    fun started() { prefs.edit().putLong("started", System.currentTimeMillis()).putString("state", "执行中").apply() }
    fun finished(state: String) {
        prefs.edit().putLong("finished", System.currentTimeMillis())
            .putString("state", state.take(80)).apply()
    }
    fun watchdog() { prefs.edit().putLong("watchdog", System.currentTimeMillis()).apply() }
    fun compensation() { prefs.edit().putLong("compensate", System.currentTimeMillis()).apply() }
    fun recovered(source: String, detail: String) {
        prefs.edit().putLong("recovery_at", System.currentTimeMillis())
            .putString("recovery_source", source)
            .putString("recovery_detail", detail).apply()
    }
    fun recoveryFailed(source: String) { recovered(source, "failed") }
    fun suppressed(kind: SignFailureKind) {
        prefs.edit().putLong("suppressed_at", System.currentTimeMillis())
            .putString("suppressed_kind", kind.name).apply()
    }
    fun crossDayAttempt() {
        prefs.edit().putInt("cross_day_attempts", prefs.getInt("cross_day_attempts", 0) + 1).apply()
    }
    fun snapshot() = ScheduleSnapshot(
        nextAt = prefs.getLong("next", 0),
        exact = prefs.getBoolean("exact", false),
        lastAlarmAt = prefs.getLong("alarm", 0),
        lastAlarmDelayMs = prefs.getLong("delay", 0),
        lastEnqueueAt = prefs.getLong("enqueued", 0),
        lastStartAt = prefs.getLong("started", 0),
        lastFinishAt = prefs.getLong("finished", 0),
        lastWatchdogAt = prefs.getLong("watchdog", 0),
        lastCompensateAt = prefs.getLong("compensate", 0),
        lastState = prefs.getString("state", "尚未运行") ?: "尚未运行",
        lastRecoveryAt = prefs.getLong("recovery_at", 0),
        lastRecoverySource = prefs.getString("recovery_source", "") ?: "",
        lastRecoveryDetail = prefs.getString("recovery_detail", "") ?: "",
        lastSuppressedAt = prefs.getLong("suppressed_at", 0),
        lastSuppressedKind = prefs.getString("suppressed_kind", "") ?: "",
        crossDayAttempts = prefs.getInt("cross_day_attempts", 0)
    )
}

/** Pure policy to keep notification deduplication independently testable. */
internal object SignNotificationPolicy {
    fun dailyKey(result: ActionResult, date: LocalDate): String? {
        if (result.skipped) return null
        return "$date:${if (result.ok) "SUCCESS" else result.failure.name}"
    }

    /** Copy the persisted Set; Android SharedPreferences returns a live Set. */
    fun keysForToday(
        storedDate: String, today: LocalDate, storedKeys: Set<String>
    ): Set<String> = if (storedDate == today.toString()) storedKeys.toSet() else emptySet()

    fun shouldNotify(key: String?, todayKeys: Set<String>): Boolean =
        key != null && key !in todayKeys
}

/** One notification per day and outcome category; skipped/already-signed never alerts. */
internal class SignNotifier(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("byd_sign_notifications", Context.MODE_PRIVATE)

    fun notifyResult(result: ActionResult) {
        val today = LocalDate.now()
        val key = SignNotificationPolicy.dailyKey(result, today) ?: return
        if (!allowed(context)) return
        synchronized(monitor) {
            val sentToday = SignNotificationPolicy.keysForToday(
                prefs.getString("notified_date", "") ?: "",
                today,
                prefs.getStringSet("notified_keys", emptySet()) ?: emptySet()
            )
            if (!SignNotificationPolicy.shouldNotify(key, sentToday)) return
            try {
                val manager = context.getSystemService(NotificationManager::class.java)
                val channelId = "byd_sign_result"
                manager.createNotificationChannel(NotificationChannel(
                    channelId, "签到结果", NotificationManager.IMPORTANCE_DEFAULT
                ))
                val launch = PendingIntent.getActivity(context, 0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = Notification.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(if (result.ok) "比亚迪签到成功" else "比亚迪签到失败")
                    .setContentText(if (result.ok) "今日签到已完成" else result.message.take(80))
                    .setAutoCancel(true)
                    .setContentIntent(launch)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .build()
                manager.notify(NOTIFICATION_ID, notification)
                // A daily Set prevents A→B→A categories from notifying A twice.
                // Persist the day and keys together to roll over at midnight.
                prefs.edit()
                    .putString("notified_date", today.toString())
                    .putStringSet("notified_keys", sentToday + key)
                    .commit()
            } catch (_: RuntimeException) {
                // Notifications are best-effort: permission/system errors must
                // never convert a successful sign-in into a failed Worker/UI job.
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 12001
        private val monitor = Any()
        fun allowed(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED)
        }
    }
}
