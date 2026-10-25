package com.brucezhang.bydautosign

import android.content.Context
import com.brucezhang.bydautosign.ipc.IpcBrokerService
import java.time.ZoneId

/**
 * The original byd_sign script obtained a fresh WeChat code and BYD session
 * before each real sign request. Do the same once per local sign day, without
 * invoking Root, a localhost HTTP bridge, or launching WeChat in background.
 */
internal object AutoSessionRefreshPolicy {
    fun needsRefresh(
        lastAuthorizedAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()
    ): Boolean = lastAuthorizedAt <= 0L ||
        SignDatePolicy.completedLocalDate(lastAuthorizedAt, zone) !=
        SignDatePolicy.completedLocalDate(now, zone)

    // A return code alone is not proof of expiry. Treat 50010 as a hint to
    // refresh once, not as grounds to delete the cached session.
    fun mayNeedNewSession(outcome: SignOutcome): Boolean =
        outcome.authorizationExpired || outcome.businessCode == 50010
}

internal enum class AutoRefreshStatus(val label: String) {
    NEVER("尚未尝试"), SUCCESS("成功"), BINDER_UNAVAILABLE("微信 Hook 未连接"),
    CODE_FAILED("微信取码失败"), EXCHANGE_FAILED("会话兑换失败"),
    STORAGE_FAILED("会话保存失败")
}

/**
 * Pure orchestration: one refresh maximum per execution. Failed refreshes do
 * not discard a working cached session. Sign success is authoritative even
 * if the optional refresh failed.
 */
internal class FreshSessionSigner(
    private val refresh: () -> String?,
    private val sign: (String) -> SignOutcome
) {
    fun execute(
        cachedSession: String?, lastAuthorizedAt: Long, now: Long,
        missingFailure: SignFailureKind = SignFailureKind.NO_SESSION
    ): SignOutcome {
        var refreshAttempted = false
        var refreshSucceeded = false
        var session = cachedSession

        fun refreshOnce(): String? {
            if (refreshAttempted) return null
            refreshAttempted = true
            val fresh = refresh()
            if (!fresh.isNullOrBlank()) {
                session = fresh
                refreshSucceeded = true
            }
            return fresh
        }

        if (session == null || AutoSessionRefreshPolicy.needsRefresh(lastAuthorizedAt, now)) {
            refreshOnce()
        }

        var outcome = session?.let(sign) ?: SignOutcome(
            false,
            if (missingFailure == SignFailureKind.SESSION_UNREADABLE)
                "本地会话不可读取，请重新授权"
            else "尚未授权，请先通过微信获取会话",
            failure = missingFailure
        )

        // Same-day sessions can be revoked early. One fresh exchange and one
        // additional sign request are allowed, only after a suspected expiry.
        if (!outcome.success && !refreshAttempted &&
            AutoSessionRefreshPolicy.mayNeedNewSession(outcome)) {
            val newSession = refreshOnce()
            if (newSession != null) outcome = sign(newSession)
        }

        if (!outcome.success && refreshAttempted && !refreshSucceeded &&
            (AutoSessionRefreshPolicy.mayNeedNewSession(outcome) ||
                outcome.failure == SignFailureKind.NO_SESSION ||
                outcome.failure == SignFailureKind.SESSION_UNREADABLE)) {
            return outcome.copy(
                message = outcome.message + " · 自动刷新授权失败，请检查微信 Hook 连接",
                shouldRetry = true
            )
        }
        return outcome
    }
}

/** Auto refresh never launches WeChat or exposes credentials in diagnostic text. */
internal object AutoSessionRefresher {
    fun refresh(context: Context, engine: NativeSignEngine): String? {
        val store = SignStore(context)
        fun fail(status: AutoRefreshStatus): String? {
            store.recordAutoRefresh(status)
            return null
        }
        if (!IpcBrokerService.connected()) return fail(AutoRefreshStatus.BINDER_UNAVAILABLE)
        val code = runCatching { IpcBrokerService.obtainCode(BydProtocol.APP_ID) }.getOrNull()
        if (code?.successful() != true) return fail(AutoRefreshStatus.CODE_FAILED)
        val session = try {
            engine.exchangeSession(code.code)
        } catch (_: Exception) {
            return fail(AutoRefreshStatus.EXCHANGE_FAILED)
        }
        try {
            store.saveSession(session)
            if (store.session() != session) return fail(AutoRefreshStatus.STORAGE_FAILED)
        } catch (_: Exception) {
            return fail(AutoRefreshStatus.STORAGE_FAILED)
        }
        store.recordAutoRefresh(AutoRefreshStatus.SUCCESS)
        return session
    }
}
