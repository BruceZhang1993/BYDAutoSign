package com.brucezhang.bydautosign

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only alive during an attempt; no permanent foreground notification. */
class SignExecutionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var active = 0
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val day = intent?.getStringExtra(SignScheduler.WORK_DAY).orEmpty()
        val at = intent?.getLongExtra(SignScheduler.WORK_AT, 0L) ?: 0L
        val test = intent?.getBooleanExtra("test", false) == true
        if (!test && (day.isEmpty() || at <= 0L)) {
            if (active == 0) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "签到执行",
                NotificationManager.IMPORTANCE_LOW))
            startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("比亚迪自动签到")
                .setContentText(if (test) "正在测试锁屏唤醒" else "正在执行签到")
                .setOngoing(true).build())
        } catch (_: RuntimeException) {
            if (test) SignScheduler.record(this, ScheduleEventType.TEST_FAILED, ScheduleEventSource.TEST)
            else {
                SignScheduler.record(this, ScheduleEventType.SERVICE_FALLBACK, ScheduleEventSource.SERVICE)
                runCatching { SignScheduler.fallback(this, day, at) }
            }
            if (active == 0) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        active++
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val source = if (test) ScheduleEventSource.TEST else ScheduleEventSource.SERVICE
                    SignScheduler.record(applicationContext, if (test) ScheduleEventType.TEST_SERVICE_STARTED
                        else ScheduleEventType.SERVICE_STARTED, source)
                    val wake = getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:sign-execution")
                    wake.setReferenceCounted(false)
                    try {
                        wake.acquire(5 * 60_000L)
                        if (test) {
                            val cm = getSystemService(ConnectivityManager::class.java)
                            val ready = cm.getNetworkCapabilities(cm.activeNetwork)
                                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                            SignScheduler.record(applicationContext, if (ready) ScheduleEventType.TEST_NETWORK_READY
                                else ScheduleEventType.TEST_NETWORK_UNAVAILABLE, source)
                        } else ScheduledSignExecution.run(applicationContext, day, at, source)
                    } finally {
                        if (wake.isHeld) wake.release()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                SignScheduler.record(applicationContext, if (test) ScheduleEventType.TEST_FAILED
                    else ScheduleEventType.WORK_FAILED,
                    if (test) ScheduleEventSource.TEST else ScheduleEventSource.SERVICE)
                if (!test) runCatching { SignScheduler.fallback(applicationContext, day, at) }
            } finally {
                active--
                if (active == 0 && stopSelfResult(latestStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "byd_sign_execution"
        private const val NOTIFICATION_ID = 12002

        fun launch(context: Context, day: String, at: Long, test: Boolean = false): Boolean =
            try {
                context.startForegroundService(Intent(context, SignExecutionService::class.java)
                    .putExtra(SignScheduler.WORK_DAY, day)
                    .putExtra(SignScheduler.WORK_AT, at)
                    .putExtra("test", test))
                true
            } catch (_: RuntimeException) { false }
    }
}
