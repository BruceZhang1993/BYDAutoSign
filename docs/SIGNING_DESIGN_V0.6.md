# BYD Auto Sign v0.6.0 — Unified Daily Signing

## Context
Device reports on October 26–27 showed alarms and WorkManager enqueueing correctly, but scheduled signing could not reach the WeChat hook. The old session received BYD `ret=50010`; a later manual sign succeeded. The meaning of 50010 is **not confirmed**. The new design treats this as a suspicious session, not proof of server expiry.

## Invariants

- The configured daily time is the **earliest automatic check-in opportunity** for that local date; manual check-in remains available earlier.
- A successfully confirmed sign is terminal for the day, even if future alarms, app starts, or WeChat lifecycle events occur.
- The BYD sign engine is called through the single serialized SignCoordinator. A sign **history** entry represents an actual BYD sign request; failures to obtain a WeChat Code are separate scheduler events.
- Automatic retry can occur after an alarm, reconnect, WeChat foreground, or app foreground. Event dispatch uses a 20-second debounce and a 3-minute stale-execution lease; the hourly alarm remains the fallback.
- A newly rejected cached session is retained encrypted but not repeatedly submitted until refreshed. `ret=50010` is only a heuristic. Never cache a consumed WeChat Code or raw server responses.
- Notification and history updates occur only after the sign result. Existing encrypted sessions, signed-day markers, single schedule time and history remain compatible with v0.5.x.
- No Root commands, persistent foreground service, background WeChat Activity starts or unauthenticated HTTP IPC.

## Workflow

```text
Alarm / WeChat foreground / Hook registration / App start / Manual
        |
        v
SignScheduler opportunity gate (automatic: time >= daily start)
        |
        v
WorkManager unique sign work (manual calls coordinator directly)
        |
        v
SignCoordinator (serialized; signedToday, mark RUNNING)
        |
        v
AuthorizationManager
    -> cached fresh BYD Session
    -> if missing/previously rejected/new day: secure WeChat Binder Code
    -> exchange Code for Session and persist via Android Keystore
    -> fallback to cache only if it was not previously rejected
        |
        v
NativeSignEngine.sign()
        |
        +-- success -> signedDate + COMPLETE; cancel today's retries
        +-- unable to get authorization -> WAITING_AUTH; hourly fallback
        +-- server/network failure -> RETRY_PENDING; hourly fallback
```

## WeChat foreground lifecycle

- The injected main WeChat process registers standard `Application.ActivityLifecycleCallbacks` during `Application.attach`, without hooking WeChat's obfuscated Activity classes.
- Transition from zero started activities to a started activity notifies `HookIpcClient`. It debounces for 30 seconds and queues a notice until Binder registration finishes.
- `IHookBroker.notifyForeground()` authenticates caller with UID→`com.tencent.mm` AppOps validation and only accepts an already registered Hook.
- `IpcBrokerService` sends an app-private `HookRecoveryReceiver` event; the receiver invokes the same `SignScheduler.onOpportunity` gate as the other sources. It does **not** directly perform network I/O in the Hook or Binder thread.
- A killed WeChat process cannot be silently restarted in a portable unprivileged Android app. Opening WeChat later creates a new opportunity to recover.

## Stored local state

- Existing `signed_date` is the authoritative success flag.
- New per-day `SignDayPhase`: WAITING_TIME / RUNNING / WAITING_AUTH / RETRY_PENDING / COMPLETE.
- `sign_phase_day`, `sign_phase_started`: per-day status with time-bounded stale RUNNING recovery.
- `sign_opportunity_at`: debounce of automatic foreground opportunities.
- `session_suspected_invalid_at`: rejection heuristic, automatically cleared on a newly saved Session; never used to delete encrypted credentials.

## Device validation before merge

1. At the configured time with WeChat Hook available, a fresh Code and Session is acquired and sign succeeds.
2. At the configured time with WeChat killed, the saved session is attempted only if not already rejected; failure records WAITING_AUTH and schedules hourly retry.
3. After that failure, launch WeChat at least 20 seconds later: foreground/reconnect signal schedules a new sign without opening BYD Auto Sign.
4. Repeated WeChat Activity navigation and rapid Binder reconnection must not duplicate requests.
5. Before the configured time, opening WeChat must not perform an automatic sign.
6. After either manual or automatic success, all remaining same-day events must skip; next alarm is tomorrow.
7. Midnight, timezone changes, Doze, reboot and app upgrades preserve correct day-boundary behavior.

## Remaining future work

- Optional non-sensitive account-scoped durable transaction lease across multiple app processes (current SignCoordinator is process serialized).
- Validate server-side status query or idempotency capability: an HTTP response lost after server acceptance may otherwise produce a retried request.
- Network-available opportunity and proactive short-lived Session preparation (only after real-device proof of server semantics).
- Expanded user-visible guidance for aggressive ROM battery optimizations.
