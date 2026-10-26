package com.brucezhang.bydautosign

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** One 24-hour clock time per day; old comma-separated configurations are migrated. */
internal object ScheduleTimes {
    private val pattern = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    fun normalize(input: String): String? = input.trim().takeIf { pattern.matches(it) }

    fun migrate(input: String): String =
        input.split(',').mapNotNull(::normalize).minOrNull() ?: "07:01"
}

internal data class NextSignAlarm(val at: Long, val retry: Boolean)

/** Pure scheduling rules; always interpret day boundaries in the device's local timezone. */
internal object DailySchedulePolicy {
    private fun anchor(time: String, day: LocalDate, now: ZonedDateTime): ZonedDateTime =
        ZonedDateTime.of(day, LocalTime.parse(requireNotNull(ScheduleTimes.normalize(time))), now.zone)

    fun next(time: String, now: ZonedDateTime, signedToday: Boolean, retryAt: Long): NextSignAlarm {
        val tomorrow = anchor(time, now.toLocalDate().plusDays(1), now).toInstant().toEpochMilli()
        if (signedToday) return NextSignAlarm(tomorrow, false)
        if (retryAt > 0 && Instant.ofEpochMilli(retryAt).atZone(now.zone).toLocalDate() ==
            now.toLocalDate()) return NextSignAlarm(retryAt, true)
        val today = anchor(time, now.toLocalDate(), now)
        return NextSignAlarm(
            (if (today.isAfter(now)) today.toInstant().toEpochMilli() else tomorrow), false)
    }

    /** No retries tomorrow from today's failure, even if the one-hour window crosses midnight. */
    fun retryAfter(completedAt: ZonedDateTime): Long {
        val next = completedAt.toInstant().plus(1, ChronoUnit.HOURS)
        return if (next.atZone(completedAt.zone).toLocalDate() == completedAt.toLocalDate())
            next.toEpochMilli() else 0L
    }
}

internal enum class ScheduleRecoverySource {
    APP_START, FOREGROUND, BOOT, PACKAGE_UPDATED, TIME_CHANGED, TIMEZONE_CHANGED,
    ALARM_PERMISSION_CHANGED, WATCHDOG, UNKNOWN
}

internal object SignScheduler {
    private const val ACTION = "com.brucezhang.bydautosign.ALARM_SIGN"
    private const val REQUEST_ID = 1077
    private const val WORK_NAME = "native-daily-sign"
    private const val WATCHDOG_NAME = "native-alarm-watchdog"
    private const val EXTRA_EXPECTED = "schedule_due_at"
    private const val WORK_DAY = "scheduled_local_day"

    private fun intent(context: Context, at: Long = 0L): PendingIntent =
        PendingIntent.getBroadcast(context, REQUEST_ID,
            Intent(context, SignAlarmReceiver::class.java)
                .setAction(ACTION).putExtra(EXTRA_EXPECTED, at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Cancel alarms from the unmerged multi-slot implementation if its APK was installed. */
    private fun cancelOldSlotAlarms(context: Context, oldTimes: String) {
        oldTimes.split(',').mapNotNull(ScheduleTimes::normalize).forEach { slot ->
            val time = LocalTime.parse(slot)
            val code = 20000 + time.hour * 60 + time.minute
            val old = PendingIntent.getBroadcast(context, code,
                Intent(context, SignAlarmReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (old != null) {
                context.getSystemService(AlarmManager::class.java).cancel(old)
                old.cancel()
            }
        }
    }

    fun exactAllowed(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    }

    private fun scheduleAlarm(manager: AlarmManager, context: Context, at: Long): Boolean {
        val alarm = intent(context, at)
        if (exactAllowed(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
                return true
            } catch (_: SecurityException) {
                // The user may revoke the special alarm grant between check and set.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
        return false
    }

    /**
     * Never auto-repeat at a fixed clock slot after success. On failure, the
     * persisted same-day retry deadline takes precedence over the daily anchor.
     */
    @Synchronized
    fun reschedule(context: Context): String {
        val store = SignStore(context)
        val manager = context.getSystemService(AlarmManager::class.java)
        cancelOldSlotAlarms(context, store.rawTimes)
        manager.cancel(intent(context))
        if (!store.enabled) {
            WorkManager.getInstance(context).cancelUniqueWork(WATCHDOG_NAME)
            ScheduleTelemetry(context).planned(0L, false)
            return "自动签到已关闭"
        }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCHDOG_NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SignWatchdogWorker>(12, TimeUnit.HOURS).build()
        )
        val now = ZonedDateTime.now()
        val plan = DailySchedulePolicy.next(store.times, now, store.signedToday(), store.retryAt)
        val exact = runCatching { scheduleAlarm(manager, context, plan.at) }
            .getOrElse {
                ScheduleTelemetry(context).planned(0L, false)
                runCatching { ScheduleEventLog(context).append(
                    ScheduleEventType.SCHEDULE_FAILED, ScheduleEventSource.COMPENSATION) }
                return "设置定时失败，请检查系统闹钟权限"
            }
        ScheduleTelemetry(context).planned(plan.at, exact)
        return "下次 ${DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(now.zone)
            .format(Instant.ofEpochMilli(plan.at))} · " +
            (if (plan.retry) "失败重试" else "每日签到") +
            (if (exact) " · 精确唤醒" else " · 非精确唤醒")
    }

    /**
     * Reserve the next retry before starting a worker. If the process dies or
     * WorkManager is delayed, an alarm remains registered rather than stopping.
     */
    private fun reserveRetry(context: Context, now: ZonedDateTime) {
        val store = SignStore(context)
        store.retryAt = DailySchedulePolicy.retryAfter(now)
        reschedule(context)
    }

    fun enqueue(context: Context, source: ScheduleEventSource = ScheduleEventSource.COMPENSATION) {
        val store = SignStore(context)
        if (!store.enabled) {
            runCatching { ScheduleEventLog(context).append(ScheduleEventType.SKIPPED_DISABLED, source) }
            return
        }
        if (store.signedToday()) {
            runCatching { ScheduleEventLog(context).append(ScheduleEventType.SKIPPED_SIGNED, source) }
            reschedule(context)
            return
        }
        // Run the worker without a network constraint: offline is also a failure,
        // and must schedule the one-hour retry instead of blocking indefinitely.
        val request = OneTimeWorkRequestBuilder<SignWorker>()
            .setInputData(workDataOf(WORK_DAY to LocalDate.now().toString()))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        ScheduleTelemetry(context).enqueued()
        runCatching { ScheduleEventLog(context).append(ScheduleEventType.WORK_QUEUED, source) }
    }

    /**
     * All non-timer opportunities use the same gate. A WeChat foreground event
     * after today's configured time is sufficient even when no earlier alarm
     * reached the worker. Connection registration alone never starts early.
     */
    @Synchronized
    fun onOpportunity(context: Context, trigger: SignTrigger) {
        if (trigger == SignTrigger.MANUAL || trigger == SignTrigger.ALARM) return
        val store = SignStore(context)
        val now = System.currentTimeMillis()
        if (!SignOpportunityPolicy.shouldDispatch(
                enabled = store.enabled, signedToday = store.signedToday(),
                time = store.times, now = now, phase = store.dayPhase,
                lastRunAt = store.lastRun,
                lastStartedAt = store.lastDayStartedAt,
                lastOpportunityAt = store.lastSignOpportunityAt
            )) return

        store.lastSignOpportunityAt = now
        val source = when (trigger) {
            SignTrigger.HOOK_CONNECTED -> ScheduleEventSource.HOOK
            SignTrigger.WECHAT_FOREGROUND -> ScheduleEventSource.WECHAT_FOREGROUND
            else -> ScheduleEventSource.APP_FOREGROUND
        }
        runCatching {
            ScheduleEventLog(context).append(ScheduleEventType.OPPORTUNITY, source)
        }

        // Maintain the hourly fallback even if the alarm never fired.
        if (store.retryAt <= now ||
            SignDatePolicy.completedLocalDate(store.retryAt) !=
                SignDatePolicy.completedLocalDate(now)) {
            store.retryAt = DailySchedulePolicy.retryAfter(ZonedDateTime.now())
            reschedule(context)
        }
        enqueue(context, source)
    }

    fun onAlarm(context: Context, received: Intent) {
        val telemetry = ScheduleTelemetry(context)
        val planned = telemetry.snapshot().nextAt
        val due = received.getLongExtra(EXTRA_EXPECTED, 0L)
        // Discard obsolete alarms left by a settings edit or a race with manual sign.
        if (due <= 0L || due != planned) {
            recover(context, ScheduleRecoverySource.UNKNOWN, force = true)
            return
        }
        telemetry.alarmFired()
        runCatching { ScheduleEventLog(context).append(
            ScheduleEventType.ALARM_FIRED, ScheduleEventSource.ALARM) }
        val store = SignStore(context)
        if (!store.enabled || store.signedToday()) {
            enqueue(context, ScheduleEventSource.ALARM)
            reschedule(context)
            return
        }
        val now = ZonedDateTime.now()
        if (Instant.ofEpochMilli(due).atZone(now.zone).toLocalDate() != now.toLocalDate()) {
            // Yesterday's delayed retry must never submit an early request today.
            reschedule(context)
            return
        }
        reserveRetry(context, now)
        enqueue(context, ScheduleEventSource.ALARM)
    }

    /** A missed wake is compensated once; then failures follow the hourly policy. */
    fun recover(context: Context, source: ScheduleRecoverySource, force: Boolean = false) {
        val store = SignStore(context)
        val telemetry = ScheduleTelemetry(context)
        val prior = telemetry.snapshot()
        val now = ZonedDateTime.now()
        val at = now.toInstant().toEpochMilli()
        val stale = store.enabled && prior.nextAt > 0L && at >= prior.nextAt &&
            !store.signedToday() &&
            Instant.ofEpochMilli(prior.nextAt).atZone(now.zone).toLocalDate() == now.toLocalDate() &&
            prior.lastAlarmAt < prior.nextAt
        val permissionChanged = store.enabled && prior.nextAt > 0L &&
            SignRecoveryPolicy.permissionChanged(prior.exact, exactAllowed(context))
        if (stale) {
            runCatching { ScheduleEventLog(context).append(
                ScheduleEventType.MISSED_WAKE, ScheduleEventSource.COMPENSATION) }
            // Reserve a retry before queuing the catch-up to prevent repeated
            // foreground refreshes from enqueuing multiple check-in attempts.
            reserveRetry(context, now)
            telemetry.compensation()
            enqueue(context)
        } else if (force || prior.nextAt <= 0L || permissionChanged ||
            (store.enabled && SignRecoveryPolicy.planOverdue(prior.nextAt, at))) {
            runCatching {
                val result = reschedule(context)
                if (result.startsWith("设置定时失败")) telemetry.recoveryFailed(source.name)
                else telemetry.recovered(source.name,
                    if (permissionChanged) "permission_changed" else "rearmed")
            }.onFailure { telemetry.recoveryFailed(source.name) }
        }
    }

    /** Called on scheduled completion or successful manual sign, never on each UI refresh. */
    fun afterResult(context: Context, response: ActionResult,
                    scheduledDay: String? = null): Boolean {
        val store = SignStore(context)
        val now = ZonedDateTime.now()
        // An attempt started yesterday but completed after midnight must not
        // create a retry before today's configured daily starting time.
        val belongsToToday = scheduledDay == null || scheduledDay == now.toLocalDate().toString()
        store.retryAt = if (response.ok || !belongsToToday) 0L
            else DailySchedulePolicy.retryAfter(now)
        val result = reschedule(context)
        if (!response.ok && store.retryAt > 0L && !result.startsWith("设置定时失败")) {
            runCatching { ScheduleEventLog(context).append(
                ScheduleEventType.RETRY_PLANNED, ScheduleEventSource.WORKER) }
        }
        // True when today's retry sequence has ended.
        return response.ok || store.retryAt == 0L
    }

    fun configure(context: Context, enabled: Boolean, time: String): String {
        val normalized = ScheduleTimes.normalize(time) ?: return "请选择一个有效签到时间（HH:mm）"
        val store = SignStore(context)
        store.times = normalized
        store.retryAt = 0L
        store.enabled = enabled
        if (!enabled) WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        return reschedule(context)
    }
}

/** Explicit, app-private signal sent after the authenticated Binder registers. */
class HookRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val source = when (intent.getStringExtra("source")) {
            "foreground" -> SignTrigger.WECHAT_FOREGROUND
            else -> SignTrigger.HOOK_CONNECTED
        }
        SignScheduler.onOpportunity(context, source)
    }
}

class SignAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SignScheduler.onAlarm(context, intent)
    }
}

class SignRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val source = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> ScheduleRecoverySource.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> ScheduleRecoverySource.PACKAGE_UPDATED
            Intent.ACTION_TIME_CHANGED -> ScheduleRecoverySource.TIME_CHANGED
            Intent.ACTION_TIMEZONE_CHANGED -> ScheduleRecoverySource.TIMEZONE_CHANGED
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" ->
                ScheduleRecoverySource.ALARM_PERMISSION_CHANGED
            else -> ScheduleRecoverySource.UNKNOWN
        }
        SignScheduler.recover(context, source, force = true)
    }
}

class SignWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val telemetry = ScheduleTelemetry(applicationContext)
        val store = SignStore(applicationContext)
        if (!store.enabled) {
            telemetry.finished("自动签到已关闭")
            runCatching { ScheduleEventLog(applicationContext).append(
                ScheduleEventType.SKIPPED_DISABLED, ScheduleEventSource.WORKER) }
            return@withContext Result.success()
        }
        if (inputData.getString("scheduled_local_day") != LocalDate.now().toString()) {
            // An old queued job is not allowed to sign on the next day before its anchor.
            SignScheduler.reschedule(applicationContext)
            return@withContext Result.success()
        }
        if (store.signedToday()) {
            telemetry.finished("今日已签到，跳过")
            runCatching { ScheduleEventLog(applicationContext).append(
                ScheduleEventType.SKIPPED_SIGNED, ScheduleEventSource.WORKER) }
            SignScheduler.reschedule(applicationContext)
            return@withContext Result.success()
        }
        telemetry.started()
        val response = runCatching {
            SignCoordinator.sign(applicationContext, SignOrigin.SCHEDULED, 1)
        }.getOrElse {
            runCatching { SignHistory(applicationContext).append(SignHistoryEntry(
                System.currentTimeMillis(), SignOrigin.SCHEDULED, SignRecordStatus.FAILED,
                SignFailureKind.UNKNOWN)) }
            ActionResult(false, "签到执行异常", failure = SignFailureKind.UNKNOWN)
        }
        telemetry.finished(when {
            response.skipped -> "今日已签到，跳过"
            response.ok -> "签到成功"
            else -> "签到失败 · ${response.failure.label}"
        })
        val done = SignScheduler.afterResult(applicationContext, response,
            inputData.getString("scheduled_local_day"))
        if (response.awaitingAuthorization) {
            runCatching {
                ScheduleEventLog(applicationContext).append(
                    ScheduleEventType.AUTH_WAITING, ScheduleEventSource.WORKER,
                    response.failure
                )
            }
        }
        // Retry failures are intermediate events; avoid hourly notification spam.
        if (done) SignNotifier(applicationContext).notifyResult(response)
        Result.success()
    }
}

class SignWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        ScheduleTelemetry(applicationContext).watchdog()
        SignScheduler.recover(applicationContext, ScheduleRecoverySource.WATCHDOG, force = true)
        Result.success()
    }
}
