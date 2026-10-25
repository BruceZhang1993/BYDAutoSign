package com.brucezhang.bydautosign

 import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Parse/normalize daily local clock times. No device-specific hard-coded cron entries. */
internal object ScheduleTimes {
    private val pattern = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    fun normalize(input: String): String? {
        val values = input.split(',').map { it.trim() }
        if (values.isEmpty() || values.size > 8 || values.any { !pattern.matches(it) }) return null
        return values.distinct().sorted().joinToString(",")
    }

    fun next(times: String, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        val parsed = requireNotNull(normalize(times)).split(',').map(LocalTime::parse)
        val zone = now.zone
        return (0..2).asSequence().flatMap { day ->
            val date = now.toLocalDate().plusDays(day.toLong())
            parsed.asSequence().map { time ->
                // Handles DST gaps/overlaps via ZoneRules rather than assuming 24h intervals.
                ZonedDateTime.of(LocalDateTime.of(date, time), zone)
            }
        }.first { it.isAfter(now) }
    }

    /** Each configured time owns an independent daily alarm, even if another slot is missed. */
    fun nextForSlot(slot: String, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        require(normalize(slot) == slot && !slot.contains(','))
        val today = ZonedDateTime.of(now.toLocalDate(), LocalTime.parse(slot), now.zone)
        return if (today.isAfter(now)) today
            else ZonedDateTime.of(now.toLocalDate().plusDays(1), LocalTime.parse(slot), now.zone)
    }

    fun firstToday(times: String, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime =
        ZonedDateTime.of(now.toLocalDate(), LocalTime.parse(requireNotNull(normalize(times)).split(',').first()), now.zone)
}

internal enum class ScheduleRecoverySource {
    APP_START, FOREGROUND, BOOT, PACKAGE_UPDATED, TIME_CHANGED, TIMEZONE_CHANGED,
    ALARM_PERMISSION_CHANGED, WATCHDOG, UNKNOWN
}

internal object SignScheduler {
    private const val ACTION = "com.brucezhang.bydautosign.ALARM_SIGN"
    // Old releases used a single pending broadcast, so cancel that identity on migration.
    private const val LEGACY_REQUEST_ID = 1077
    private const val SLOT_REQUEST_BASE = 20000
    private const val EXTRA_SLOT = "scheduled_slot"
    private const val EXTRA_EXPECTED_AT = "expected_at"
    private const val WORK_NAME = "native-daily-sign"
    private const val WATCHDOG_NAME = "native-alarm-watchdog"

    private fun slots(times: String): List<String> =
        ScheduleTimes.normalize(times)?.split(',') ?: emptyList()

    /** Stable, collision-free PendingIntent identity for all 1440 possible HH:mm slots. */
    internal fun slotRequestCode(slot: String): Int {
        require(ScheduleTimes.normalize(slot) == slot && !slot.contains(','))
        val time = LocalTime.parse(slot)
        return SLOT_REQUEST_BASE + time.hour * 60 + time.minute
    }

    private fun intent(context: Context, slot: String, expectedAt: Long = 0): PendingIntent =
        PendingIntent.getBroadcast(
            context, slotRequestCode(slot),
            Intent(context, SignAlarmReceiver::class.java)
                .setAction(ACTION).putExtra(EXTRA_SLOT, slot)
                .putExtra(EXTRA_EXPECTED_AT, expectedAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun legacyIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, LEGACY_REQUEST_ID, Intent(context, SignAlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun exactAllowed(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    /** Set one OS alarm, using the inexact fallback if exact-alarm access was revoked. */
    private fun arm(manager: AlarmManager, context: Context, slot: String,
                    next: ZonedDateTime): Boolean {
        val at = next.toInstant().toEpochMilli()
        val alarm = intent(context, slot, at)
        if (exactAllowed(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
                return true
            } catch (_: SecurityException) {
                // Permission can be revoked between the query and the scheduling call.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
        return false
    }

    /**
     * Register every time independently. A missing 00:01 broadcast must never
     * prevent 06:15, 13:01 or 18:01 from already being registered with Android.
     */
    @Synchronized
    fun reschedule(context: Context, previousTimes: String? = null): String {
        val store = SignStore(context)
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(legacyIntent(context))
        (slots(previousTimes ?: store.times) + slots(store.times)).distinct().forEach { slot ->
            manager.cancel(intent(context, slot))
        }
        if (!store.enabled) {
            WorkManager.getInstance(context).cancelUniqueWork(WATCHDOG_NAME)
            ScheduleTelemetry(context).planned(0, false)
            return "自动签到已关闭"
        }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCHDOG_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SignWatchdogWorker>(12, TimeUnit.HOURS).build()
        )
        val now = ZonedDateTime.now()
        val next = ScheduleTimes.next(store.times, now)
        var allExact = true
        val scheduled = runCatching {
            slots(store.times).forEach { slot ->
                val exact = arm(manager, context, slot, ScheduleTimes.nextForSlot(slot, now))
                allExact = allExact && exact
            }
        }
        if (scheduled.isFailure) {
            ScheduleTelemetry(context).planned(0, false)
            runCatching { ScheduleEventLog(context).append(
                ScheduleEventType.SCHEDULE_FAILED, ScheduleEventSource.COMPENSATION) }
            return "设置定时失败，请检查系统闹钟权限"
        }
        ScheduleTelemetry(context).planned(next.toInstant().toEpochMilli(), allExact)
        return "下次 ${next.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}" +
            if (allExact) " · 精确唤醒" else " · 非精确（需授权闹钟）"
    }

    /** Re-arm only the fired slot; never cancel another slot awaiting delivery. */
    @Synchronized
    fun onAlarm(context: Context, received: Intent) {
        val store = SignStore(context)
        val slot = received.getStringExtra(EXTRA_SLOT)
        val valid = slot != null && slot in slots(store.times)
        val expectedAt = received.getLongExtra(EXTRA_EXPECTED_AT, 0L)
        val telemetry = ScheduleTelemetry(context)
        telemetry.alarmFired(expectedAt)
        runCatching { ScheduleEventLog(context).append(
            ScheduleEventType.ALARM_FIRED, ScheduleEventSource.ALARM,
            plannedAt = expectedAt, slot = if (valid) slot!! else "") }
        if (valid && store.enabled) {
            val next = ZonedDateTime.now()
            val result = runCatching {
                arm(context.getSystemService(AlarmManager::class.java),
                    context, slot!!, ScheduleTimes.nextForSlot(slot, next))
            }
            if (result.isFailure) {
                telemetry.planned(0, false)
                runCatching { ScheduleEventLog(context).append(
                    ScheduleEventType.SCHEDULE_FAILED, ScheduleEventSource.ALARM, slot = slot!!) }
            } else {
                // Snapshot is a display/repair hint; the independent alarms are the actual schedule.
                telemetry.planned(ScheduleTimes.next(store.times, next).toInstant().toEpochMilli(),
                    exactAllowed(context) && result.getOrDefault(false))
            }
        } else if (slot == null) {
            // Legacy PendingIntent from the single-alarm implementation.
            reschedule(context)
        } else if (!store.enabled) {
            runCatching { ScheduleEventLog(context).append(
                ScheduleEventType.SKIPPED_DISABLED, ScheduleEventSource.ALARM, slot = slot ?: "") }
            return
        } else {
            // An outdated slot from a concurrent settings change must not sign.
            reschedule(context)
            return
        }
        enqueue(context, ScheduleEventSource.ALARM)
    }

    /** Emit an explicit diagnostic when a planned wake is overdue without a receiver. */
    private fun recordMissedWake(context: Context, snapshot: ScheduleSnapshot, now: Long) {
        if (!SignRecoveryPolicy.planOverdue(snapshot.nextAt, now) ||
            snapshot.lastAlarmAt >= snapshot.nextAt) return
        runCatching {
            val log = ScheduleEventLog(context)
            if (log.list().none {
                    it.type == ScheduleEventType.MISSED_WAKE && it.plannedAt == snapshot.nextAt
                }) {
                log.append(ScheduleEventType.MISSED_WAKE, ScheduleEventSource.COMPENSATION,
                    plannedAt = snapshot.nextAt)
            }
        }
    }

    /** WorkManager KEEP is the final safety net against overlapping alarms and repair passes. */
    fun enqueue(context: Context, source: ScheduleEventSource = ScheduleEventSource.COMPENSATION) {
        val store = SignStore(context)
        if (!store.enabled) {
            runCatching { ScheduleEventLog(context).append(ScheduleEventType.SKIPPED_DISABLED, source) }
            return
        }
        if (store.signedToday()) {
            // Keep a durable audit trail even when no network sign request is necessary.
            runCatching { ScheduleEventLog(context).append(ScheduleEventType.SKIPPED_SIGNED, source) }
            return
        }
        store.automaticBlockKind()?.let {
            ScheduleTelemetry(context).suppressed(it)
            runCatching { ScheduleEventLog(context).append(ScheduleEventType.SKIPPED_BLOCKED, source, it) }
            return
        }
        val request = OneTimeWorkRequestBuilder<SignWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        ScheduleTelemetry(context).enqueued()
        runCatching { ScheduleEventLog(context).append(ScheduleEventType.WORK_QUEUED, source) }
    }

    fun compensate(context: Context) {
        val store = SignStore(context)
        val now = ZonedDateTime.now()
        val blocked = store.automaticBlockKind(now.toInstant().toEpochMilli())
        if (blocked != null) {
            ScheduleTelemetry(context).suppressed(blocked)
            return
        }
        if (SignRecoveryPolicy.shouldCompensate(
                store.enabled, store.signedToday(),
                now.toInstant().toEpochMilli(),
                ScheduleTimes.firstToday(store.times, now).toInstant().toEpochMilli(),
                store.lastRun
            )) {
            ScheduleTelemetry(context).compensation()
            enqueue(context)
        }
    }

    /**
     * Repair overdue/unknown plans on foreground resume, and re-arm on boot,
     * time changes and watchdog ticks (exact-alarm revocation can be silent).
     * Rescheduling never starts WeChat and never accesses a session.
     */
    fun recover(context: Context, source: ScheduleRecoverySource, force: Boolean = false) {
        val store = SignStore(context)
        val telemetry = ScheduleTelemetry(context)
        val prior = telemetry.snapshot()
        val now = System.currentTimeMillis()
        val stale = store.enabled && SignRecoveryPolicy.planOverdue(prior.nextAt, now)
        val absent = store.enabled && prior.nextAt <= 0L
        val permissionChanged = store.enabled && prior.nextAt > 0L &&
            SignRecoveryPolicy.permissionChanged(prior.exact, exactAllowed(context))
        if (store.enabled && stale) recordMissedWake(context, prior, now)
        if (force || stale || absent || permissionChanged) {
            val detail = when {
                stale -> "overdue"
                absent -> "missing"
                permissionChanged -> "permission_changed"
                else -> "rearmed"
            }
            runCatching {
                val result = reschedule(context)
                if (result.startsWith("设置定时失败")) telemetry.recoveryFailed(source.name)
                else telemetry.recovered(source.name, detail)
            }.onFailure {
                telemetry.recoveryFailed(source.name)
            }
        }
        compensate(context)
    }

    fun configure(context: Context, enabled: Boolean, times: String): String {
        val normalized = ScheduleTimes.normalize(times) ?: return "时间格式无效，请使用 HH:mm，逗号分隔（最多8组）"
        val store = SignStore(context)
        val previousTimes = store.times
        store.times = normalized
        store.enabled = enabled
        if (!enabled) WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        return reschedule(context, previousTimes)
    }
}

/** Manifest receiver is allowed to enqueue durable work; network work is never run inside onReceive. */
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
        if (store.signedToday()) {
            telemetry.finished("今日已签到，跳过")
            runCatching { ScheduleEventLog(applicationContext).append(
                ScheduleEventType.SKIPPED_SIGNED, ScheduleEventSource.WORKER) }
            return@withContext Result.success()
        }
        store.automaticBlockKind()?.let {
            telemetry.suppressed(it)
            runCatching { ScheduleEventLog(applicationContext).append(
                ScheduleEventType.SKIPPED_BLOCKED, ScheduleEventSource.WORKER, it) }
            return@withContext Result.success()
        }
        telemetry.started()
        val response = SignCoordinator.sign(applicationContext,
            SignOrigin.SCHEDULED, runAttemptCount + 1)
        if (response.retry && runAttemptCount < 4) {
            telemetry.finished("可重试失败 · ${response.failure.label} · 等待 WorkManager 重试")
            return@withContext Result.retry()
        }
        telemetry.finished(when {
            response.skipped -> "今日已签到，跳过"
            response.ok -> "签到成功"
            else -> "签到失败 · ${response.failure.label}"
        })
        // Notify only the terminal result; never alert for retries or already-signed.
        SignNotifier(applicationContext).notifyResult(response)
        Result.success()
    }
}

/** Low-frequency safety net if the OS silently discards an exact alarm. */
class SignWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        ScheduleTelemetry(applicationContext).watchdog()
        SignScheduler.recover(applicationContext, ScheduleRecoverySource.WATCHDOG, force = true)
        Result.success()
    }
}
