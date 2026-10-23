package com.brucezhang.bydautosign

import java.time.Instant
import java.time.ZoneId

/** Pure decision logic: no Android service or time lookup required by unit tests. */
internal enum class AutomaticBlockMode { NONE, TODAY, UNTIL_REAUTHORIZED }

internal object SignRecoveryPolicy {
    private const val OVERDUE_GRACE_MS = 5 * 60 * 1000L
    private const val COMPENSATION_COOLDOWN_MS = 30 * 60 * 1000L

    fun blockMode(kind: SignFailureKind): AutomaticBlockMode = when (kind) {
        SignFailureKind.NO_SESSION, SignFailureKind.SESSION_UNREADABLE,
        SignFailureKind.AUTH_EXPIRED -> AutomaticBlockMode.UNTIL_REAUTHORIZED
        SignFailureKind.TLS, SignFailureKind.HTTP_CLIENT,
        SignFailureKind.PROTOCOL, SignFailureKind.BUSINESS -> AutomaticBlockMode.TODAY
        else -> AutomaticBlockMode.NONE
    }

    fun isBlocked(
        kind: SignFailureKind, blockedAt: Long, now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean = when (blockMode(kind)) {
        AutomaticBlockMode.NONE -> false
        AutomaticBlockMode.UNTIL_REAUTHORIZED -> blockedAt > 0
        AutomaticBlockMode.TODAY -> blockedAt > 0 &&
            SignDatePolicy.completedLocalDate(blockedAt, zone) ==
            SignDatePolicy.completedLocalDate(now, zone)
    }

    fun planOverdue(nextAt: Long, now: Long): Boolean =
        nextAt > 0 && now >= nextAt && now - nextAt >= OVERDUE_GRACE_MS

    /**
     * The first daily slot has passed and no sign was completed. Don't enqueue a
     * recovery on every screen refresh; periodic alarms can still run normally.
     */
    fun shouldCompensate(
        enabled: Boolean, signedToday: Boolean, now: Long,
        firstSlotToday: Long, lastAttemptAt: Long
    ): Boolean = enabled && !signedToday && now >= firstSlotToday &&
        (lastAttemptAt <= 0 || lastAttemptAt > now ||
            now - lastAttemptAt >= COMPENSATION_COOLDOWN_MS)
}
