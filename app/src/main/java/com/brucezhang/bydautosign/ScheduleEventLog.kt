package com.brucezhang.bydautosign

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A schedule wake-up is not a sign request; track it separately from SignHistory. */
internal enum class ScheduleEventType(val label: String) {
    ALARM_REGISTERED("已注册系统闹钟"),
    SERVICE_REQUESTED("已请求启动签到前台服务"),
    SERVICE_STARTED("签到前台服务已启动"),
    SERVICE_FALLBACK("前台服务启动受限，降级后台任务"),
    EXECUTION_STARTED("签到引擎开始执行"),
    EXECUTION_FINISHED("签到引擎执行结束"),
    STALE_TICKET("已跳过过期调度任务"),
    TEST_REGISTERED("锁屏测试闹钟已注册"),
    TEST_ALARM_FIRED("锁屏测试闹钟已触发"),
    TEST_SERVICE_STARTED("锁屏测试前台服务已启动"),
    TEST_NETWORK_READY("锁屏测试：系统网络已验证可用"),
    TEST_NETWORK_UNAVAILABLE("锁屏测试：网络未验证可用"),
    TEST_FAILED("锁屏测试启动失败"),
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
    SERVICE("签到前台服务"), TEST("锁屏测试"),
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
