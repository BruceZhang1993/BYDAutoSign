package com.brucezhang.bydautosign

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Strict allowlist, never a dump of SharedPreferences, Binder details or server errors.
 * Untrusted UI strings (lastResult/ipc/hookInfo/sessionError) are intentionally ignored.
 */
internal object DiagnosticReport {
    fun collect(context: Context, state: NativeUiState): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return build(state, version, Build.VERSION.SDK_INT,
            ZoneId.systemDefault().id, System.currentTimeMillis())
    }

    fun build(
        state: NativeUiState, appVersion: String, sdk: Int, timezone: String, generatedAt: Long
    ): String {
        val trace = state.scheduleTrace
        val attempts = JSONArray()
        state.history.take(100).forEach { entry ->
            attempts.put(JSONObject()
                .put("timestampMs", entry.timestamp)
                .put("origin", entry.origin.name)
                .put("status", entry.status.name)
                .put("failure", entry.failure.name)
                .put("attempt", entry.attempt.coerceIn(1, 20)))
        }
        val report = JSONObject()
            .put("schema", 1)
            .put("generatedAtUtc", Instant.ofEpochMilli(generatedAt).toString())
            .put("appVersion", appVersion)
            .put("androidApi", sdk)
            .put("timezone", timezone)
            .put("authorization", JSONObject()
                .put("sessionAvailable", state.sessionReady)
                .put("binderConnected", state.connected)
                .put("hookReady", state.hookReady))
            .put("permissions", JSONObject()
                .put("exactAlarmGranted", state.exact))
            .put("sign", JSONObject()
                .put("signedToday", state.signedToday)
                .put("historyCount", state.history.size))
            .put("schedule", JSONObject()
                .put("enabled", state.enabled)
                .put("times", ScheduleTimes.normalize(state.times) ?: "invalid")
                .put("nextAlarmMs", trace.nextAt)
                .put("exact", trace.exact)
                .put("lastAlarmMs", trace.lastAlarmAt)
                .put("lastAlarmDelayMs", trace.lastAlarmDelayMs)
                .put("lastEnqueueMs", trace.lastEnqueueAt)
                .put("lastWorkerStartMs", trace.lastStartAt)
                .put("lastWorkerFinishMs", trace.lastFinishAt)
                .put("lastWatchdogMs", trace.lastWatchdogAt)
                .put("lastCompensationMs", trace.lastCompensateAt)
                .put("workerState", safeWorkerState(trace.lastState))
                .put("lastRecoveryMs", trace.lastRecoveryAt)
                .put("recoverySource", safeRecoverySource(trace.lastRecoverySource))
                .put("recoveryResult", safeRecoveryResult(trace.lastRecoveryDetail))
                .put("lastSuppressedMs", trace.lastSuppressedAt)
                .put("blockedFailure", safeFailureName(trace.lastSuppressedKind))
                .put("crossDayAttempts", trace.crossDayAttempts))
            .put("history", attempts)
        return report.toString(2)
    }

    private fun safeWorkerState(text: String): String = when {
        text.startsWith("执行中") -> "RUNNING"
        text.startsWith("签到成功") -> "SUCCESS"
        text.startsWith("签到失败") -> "FAILED"
        text.startsWith("可重试失败") -> "RETRY_PENDING"
        text.startsWith("今日已签到") -> "ALREADY_SIGNED"
        text.startsWith("自动签到已关闭") -> "DISABLED"
        else -> "UNKNOWN"
    }

    private fun safeRecoverySource(text: String): String =
        ScheduleRecoverySource.entries.firstOrNull { it.name == text }?.name ?: "UNKNOWN"

    private fun safeRecoveryResult(text: String): String =
        if (text in setOf("overdue", "missing", "permission_changed", "rearmed", "failed")) text else "unknown"

    private fun safeFailureName(text: String): String =
        SignFailureKind.entries.firstOrNull { it.name == text }?.name ?: "NONE"
}
