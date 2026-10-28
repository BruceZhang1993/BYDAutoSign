package com.brucezhang.bydautosign

import android.content.Context

internal data class AuthorizedSignAttempt(
    val outcome: SignOutcome,
    val requestSent: Boolean
)

/**
 * Single authority for selecting a BYD session and refreshing it via WeChat.
 * All triggers, including manual, scheduled and foreground recovery, use this
 * implementation. A session rejected with ret=50010 is retained for diagnosis,
 * but will not be repeatedly submitted until a fresh session is obtained.
 */
internal object AuthorizationManager {
    fun sign(context: Context, store: SignStore, engine: NativeSignEngine, now: Long):
        AuthorizedSignAttempt {
        val saved = runCatching { store.session() }
        val cached = saved.getOrNull().takeUnless { store.sessionSuspectedInvalid }
        var actualSignRequestSent = false
        val outcome = FreshSessionSigner(
            refresh = { AutoSessionRefresher.refresh(context, engine) },
            sign = { session ->
                actualSignRequestSent = true
                engine.sign(session)
            }
        ).execute(
            cachedSession = cached,
            lastAuthorizedAt = store.lastAuthorizedAt,
            now = now,
            missingFailure = when {
                saved.isFailure -> SignFailureKind.SESSION_UNREADABLE
                else -> SignFailureKind.NO_SESSION
            }
        )
        return AuthorizedSignAttempt(outcome, actualSignRequestSent)
    }

    fun awaitingAuthorization(outcome: SignOutcome): Boolean =
        !outcome.success && (
            outcome.failure == SignFailureKind.NO_SESSION ||
            outcome.failure == SignFailureKind.SESSION_UNREADABLE ||
            outcome.authorizationExpired ||
            // This is a *suspicion*, not a proven meaning of the business code.
            outcome.businessCode == 50010
        )
}
