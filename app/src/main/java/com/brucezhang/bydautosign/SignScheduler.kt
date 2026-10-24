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

    fun firstToday(times: String, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime =
        ZonedDateTime.of(now.toLocalDate(), LocalTime.parse(requireNotNull(normalize(times)).split(',').first()), now.zone)
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

    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_ID, Intent(context, SignAlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun exactAllowed(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    @Synchronized
    fun reschedule(context: Context): String {
        val store = SignStore(context)
        val manager = context.getSystemService(AlarmManager::class.java)
        val alarm = intent(context)
        manager.cancel(alarm)
        if (!store.enabled) {
            WorkManager.getInstance(context).cancelUniqueWork(WATCHDOG_NAME)
            ScheduleTelemetry(context).planned(0, false)
            return "自动签到已关闭"
        }
        // If an Android 12+ exact-alarm grant is revoked, existing alarms may
        // be canceled silently. Periodic WorkManager is a delayed safety net.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCHDOG_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SignWatchdogWorker>(12, TimeUnit.HOURS).build()
        )
        val next = ScheduleTimes.next(store.times)
        val millis = next.toInstant().toEpochMilli()
        var scheduledExact = exactAllowed(context)
        val scheduled = runCatching {
            if (scheduledExact) {
                try {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarm)
                } catch (_: SecurityException) {
                    scheduledExact = false
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarm)
                }
            } else {
                // Revoked exact access means a non-exact wake, possibly substantially delayed.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarm)
            }
        }
        if (scheduled.isFailure) {
            ScheduleTelemetry(context).planned(0, false)
            return "设置定时失败，请检查系统闹钟权限"
        }
        ScheduleTelemetry(context).planned(millis, scheduledExact)
        return "下次 ${next.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}" +
            if (scheduledExact) " · 精确唤醒" else " · 非精确（需授权闹钟）"
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
        store.times = normalized
        store.enabled = enabled
        if (!enabled) WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        return reschedule(context)
    }
}

/** Manifest receiver is allowed to enqueue durable work; network work is never run inside onReceive. */
class SignAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScheduleTelemetry(context).alarmFired()
        runCatching { ScheduleEventLog(context).append(
            ScheduleEventType.ALARM_FIRED, ScheduleEventSource.ALARM) }
        SignScheduler.reschedule(context)
        SignScheduler.enqueue(context, ScheduleEventSource.ALARM)
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
