package com.brucezhang.bydautosign

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A schedule wake-up is not a sign request; track it separately from SignHistory. */
internal enum class ScheduleEventType(val label: String) {
    ALARM_FIRED("定时闹钟已触发"),
    MISSED_WAKE("计划时间未收到闹钟唤醒"),
    SCHEDULE_FAILED("闹钟注册失败"),
    WORK_QUEUED("签到任务已提交"),
    SKIPPED_SIGNED("今日已签到，跳过"),
    SKIPPED_DISABLED("自动签到已关闭，跳过"),
    SKIPPED_BLOCKED("自动签到受阻，跳过")
}
internal enum class ScheduleEventSource(val label: String) {
    ALARM("定时闹钟"), COMPENSATION("补偿调度"), WORKER("后台任务")
}
internal data class ScheduleEventEntry(
    val timestamp: Long,
    val type: ScheduleEventType,
    val source: ScheduleEventSource,
    val failure: SignFailureKind = SignFailureKind.NONE,
    val plannedAt: Long = 0,
    val slot: String = ""
)

/** Strict enum-only serialization: never persist service responses or session values. */
internal object ScheduleEventCodec {
    private const val LIMIT = 100
    private fun safeSlot(value: String): String =
        value.takeIf { ScheduleTimes.normalize(it) == it && !it.contains(',') } ?: ""
    fun decode(raw: String): List<ScheduleEventEntry> {
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val item = array.getJSONObject(i)
                ScheduleEventEntry(
                    timestamp = item.getLong("at"),
                    type = enumValueOf<ScheduleEventType>(item.getString("type")),
                    source = enumValueOf<ScheduleEventSource>(item.getString("source")),
                    failure = enumValueOf<SignFailureKind>(item.optString("failure", "NONE")),
                    plannedAt = item.optLong("plannedAt", 0L),
                    slot = safeSlot(item.optString("slot", ""))
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
                .put("failure", event.failure.name)
                .put("plannedAt", event.plannedAt.coerceAtLeast(0L))
                .put("slot", safeSlot(event.slot)))
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
               failure: SignFailureKind = SignFailureKind.NONE,
               plannedAt: Long = 0L, slot: String = "") = synchronized(monitor) {
        val next = ScheduleEventCodec.add(list(),
            ScheduleEventEntry(System.currentTimeMillis(), type, source, failure,
                plannedAt, slot))
        check(prefs.edit().putString("entries", ScheduleEventCodec.encode(next)).commit()) {
            "无法保存调度记录"
        }
    }

    private companion object { val monitor = Any() }
}
