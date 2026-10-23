package com.brucezhang.bydautosign

import android.Manifest
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
        if (exactAllowed(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarm)
            } catch (_: SecurityException) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarm)
            }
        } else {
            // Android 12+/14+: the user may have denied exact-alarm special access.
            // This still wakes in Doze, but delivery can be substantially delayed.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarm)
        }
        ScheduleTelemetry(context).planned(millis, exactAllowed(context))
        return "下次 ${next.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}" +
            if (exactAllowed(context)) " · 精确唤醒" else " · 非精确（需授权闹钟）"
    }

    fun enqueue(context: Context) {
        val store = SignStore(context)
        if (!store.enabled || store.signedToday()) return
        val request = OneTimeWorkRequestBuilder<SignWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        ScheduleTelemetry(context).enqueued()
    }

    fun compensate(context: Context) {
        val store = SignStore(context)
        if (store.enabled && !store.signedToday() &&
            !ZonedDateTime.now().isBefore(ScheduleTimes.firstToday(store.times))) {
            enqueue(context)
        }
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
        SignScheduler.reschedule(context)
        SignScheduler.enqueue(context)
    }
}

class SignRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SignScheduler.reschedule(context)
        SignScheduler.compensate(context)
    }
}

class SignWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val telemetry = ScheduleTelemetry(applicationContext)
        val store = SignStore(applicationContext)
        if (!store.enabled) {
            telemetry.finished("自动签到已关闭")
            return@withContext Result.success()
        }
        if (store.signedToday()) {
            telemetry.finished("今日已签到，跳过")
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
        SignScheduler.reschedule(applicationContext)
        SignScheduler.compensate(applicationContext)
        Result.success()
    }
}
