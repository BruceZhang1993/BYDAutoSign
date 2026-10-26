package com.brucezhang.bydautosign

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Persisted per local day; only a confirmed server success is terminal. */
internal enum class SignDayPhase(val label: String) {
    WAITING_TIME("等待设定时间"),
    RUNNING("正在签到"),
    WAITING_AUTH("等待微信授权恢复"),
    RETRY_PENDING("等待下次尝试"),
    COMPLETE("今日已签到")
}

/** Automatic opportunities share one dispatch path; manual remains explicit. */
internal enum class SignTrigger {
    ALARM, HOOK_CONNECTED, WECHAT_FOREGROUND, APP_FOREGROUND, MANUAL
}

/** No platform APIs: easy to test clock boundaries and deduplication. */
internal object SignOpportunityPolicy {
    private const val MIN_EVENT_GAP_MS = 20 * 1000L
    private const val RUNNING_LEASE_MS = 3 * 60 * 1000L

    fun due(time: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (now <= 0L) return false
        val normalized = ScheduleTimes.normalize(time) ?: return false
        return !Instant.ofEpochMilli(now).atZone(zone).toLocalTime()
            .isBefore(LocalTime.parse(normalized))
    }

    fun shouldDispatch(
        enabled: Boolean,
        signedToday: Boolean,
        time: String,
        now: Long,
        phase: SignDayPhase,
        lastRunAt: Long,
        lastStartedAt: Long,
        lastOpportunityAt: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (!enabled || signedToday || !due(time, now, zone)) return false
        val day = SignDatePolicy.completedLocalDate(now, zone)
        fun recently(at: Long, delta: Long): Boolean =
            at > 0 && at <= now && SignDatePolicy.completedLocalDate(at, zone) == day &&
                now - at < delta

        if (phase == SignDayPhase.RUNNING && recently(lastStartedAt, RUNNING_LEASE_MS)) return false
        if (recently(lastOpportunityAt, MIN_EVENT_GAP_MS)) return false
        if (recently(lastRunAt, MIN_EVENT_GAP_MS)) return false
        return true
    }

    fun phaseAfter(
        success: Boolean,
        waitingForAuthorization: Boolean,
        startedAt: Long = 0L,
        finishedAt: Long = 0L,
        zone: ZoneId = ZoneId.systemDefault()
    ): SignDayPhase = when {
        success -> SignDayPhase.COMPLETE
        // A failed request from yesterday must not mark the new day failed.
        startedAt > 0L && finishedAt > 0L &&
            SignDatePolicy.crossesLocalDay(startedAt, finishedAt, zone) ->
                SignDayPhase.WAITING_TIME
        waitingForAuthorization -> SignDayPhase.WAITING_AUTH
        else -> SignDayPhase.RETRY_PENDING
    }
}
