package com.brucezhang.bydautosign

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A schedule wake-up is not a sign request; track it separately from SignHistory. */
internal enum class ScheduleEventType(val label: String) {
    ALARM_FIRED("定时闹钟已触发"),
    MISSED_WAKE("计划时间未收到闹钟唤醒"),
    RETRY_PLANNED("签到失败，已安排每小时重试"),
    HOOK_RECOVERED("微信 Hook 已连接，提前补签"),
    OPPORTUNITY("检测到可补签机会，已尝试调度"),
    AUTH_WAITING("签到等待微信授权恢复"),
    WORK_FAILED("后台任务执行异常"),
    SCHEDULE_FAILED("闹钟注册失败"),
    WORK_QUEUED("签到任务已提交"),
    SKIPPED_SIGNED("今日已签到，跳过"),
    SKIPPED_DISABLED("自动签到已关闭，跳过"),
    SKIPPED_BLOCKED("自动签到受阻，跳过")
}
internal enum class ScheduleEventSource(val label: String) {
    ALARM("定时闹钟"), COMPENSATION("补偿调度"), WORKER("后台任务"),
    HOOK("微信 Hook"), WECHAT_FOREGROUND("打开微信"), APP_FOREGROUND("打开应用")
}
internal data class ScheduleEventEntry(
    val timestamp: Long,
    val type: ScheduleEventType,
    val source: ScheduleEventSource,
    val failure: SignFailureKind = SignFailureKind.NONE
)

/** Strict enum-only serialization: never persist service responses or session values. */
internal object ScheduleEventCodec {
    private const val LIMIT = 100
    fun decode(raw: String): List<ScheduleEventEntry> {
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val item = array.getJSONObject(i)
                ScheduleEventEntry(
                    timestamp = item.getLong("at"),
                    type = enumValueOf<ScheduleEventType>(item.getString("type")),
                    source = enumValueOf<ScheduleEventSource>(item.getString("source")),
                    failure = enumValueOf<SignFailureKind>(item.optString("failure", "NONE"))
                )
            }.getOrNull()
        }.take(LIMIT)
    }

    fun encode(entries: List<ScheduleEventEntry>): String {
        val array = JSONArray()
        entries.take(LIMIT).forEach { event ->
            array.put(JSONObject()
                .put("at", event.timestamp)
                .put("type", event.type.name)
                .put("source", event.source.name)
                .put("failure", event.failure.name))
        }
        return array.toString()
    }

    fun add(entries: List<ScheduleEventEntry>, event: ScheduleEventEntry): List<ScheduleEventEntry> =
        (listOf(event) + entries).take(LIMIT)
}

internal class ScheduleEventLog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "byd_schedule_event_history", Context.MODE_PRIVATE
    )

    fun list(): List<ScheduleEventEntry> = synchronized(monitor) {
        ScheduleEventCodec.decode(prefs.getString("entries", "[]") ?: "[]")
    }

    fun append(type: ScheduleEventType, source: ScheduleEventSource,
               failure: SignFailureKind = SignFailureKind.NONE) = synchronized(monitor) {
        val next = ScheduleEventCodec.add(list(),
            ScheduleEventEntry(System.currentTimeMillis(), type, source, failure))
        check(prefs.edit().putString("entries", ScheduleEventCodec.encode(next)).commit()) {
            "无法保存调度记录"
        }
    }

    private companion object { val monitor = Any() }
}
