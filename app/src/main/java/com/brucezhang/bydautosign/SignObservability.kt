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

/** Never persist raw service messages, codes, session IDs, or exception text. */
internal enum class SignFailureKind(val label: String) {
    NONE("无"), NO_SESSION("未授权"), SESSION_UNREADABLE("本地会话异常"),
    AUTH_EXPIRED("会话过期"), NETWORK("网络异常"), TIMEOUT("请求超时"),
    TLS("TLS 验证失败"), SERVER("服务端异常"), HTTP_CLIENT("请求被拒绝"),
    PROTOCOL("协议或响应异常"), BUSINESS("业务失败"), UNKNOWN("未知错误")
}
internal enum class SignOrigin(val label: String) { MANUAL("手动"), SCHEDULED("自动") }
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
    val lastState: String = "尚未运行"
)

/** Separate scheduled, fired, enqueued and actually executed timestamps. */
internal class ScheduleTelemetry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("byd_sign_telemetry", Context.MODE_PRIVATE)
    fun planned(next: Long, exact: Boolean) {
        prefs.edit().putLong("next", next).putBoolean("exact", exact).apply()
    }
    fun alarmFired() {
        val now = System.currentTimeMillis()
        val planned = prefs.getLong("next", 0)
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
    fun snapshot() = ScheduleSnapshot(
        nextAt = prefs.getLong("next", 0),
        exact = prefs.getBoolean("exact", false),
        lastAlarmAt = prefs.getLong("alarm", 0),
        lastAlarmDelayMs = prefs.getLong("delay", 0),
        lastEnqueueAt = prefs.getLong("enqueued", 0),
        lastStartAt = prefs.getLong("started", 0),
        lastFinishAt = prefs.getLong("finished", 0),
        lastWatchdogAt = prefs.getLong("watchdog", 0),
        lastState = prefs.getString("state", "尚未运行") ?: "尚未运行"
    )
}

/** One notification per day and outcome category; skipped/already-signed never alerts. */
internal class SignNotifier(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("byd_sign_notifications", Context.MODE_PRIVATE)

    fun notifyResult(result: ActionResult) {
        if (result.skipped || !allowed(context)) return
        val kind = if (result.ok) "SUCCESS" else result.failure.name
        val key = "${LocalDate.now()}:$kind"
        synchronized(monitor) {
            if (prefs.getString("last_key", "") == key) return
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
            try {
                manager.notify(NOTIFICATION_ID, notification)
                prefs.edit().putString("last_key", key).apply()
            } catch (_: SecurityException) {
                // The user can revoke notification permission between check and notify.
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
