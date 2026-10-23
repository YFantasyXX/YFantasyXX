package com.yfantasyxx.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.yfantasyxx.util.RestTimerStore

/**
 * 组间休息倒计时前台服务。
 *
 * - 通知栏用系统 Chronometer 倒计时，同时每秒刷新标题里的剩余秒数
 * - 同时设置 AlarmManager 精确闹钟：即使 App 在后台被冻结、本服务不再执行，
 *   到点也能弹出提醒并震动（见 [RestAlarmReceiver]）
 */
class RestTimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var endTime = 0L
    private var totalSeconds = 0
    private var label: String? = null

    private val finishRunnable = Runnable { onFinished() }

    private val tickRunnable = object : Runnable {
        override fun run() {
            val remaining = ((endTime - System.currentTimeMillis()) / 1000).toInt()
            if (remaining > 0) {
                runCatching {
                    notificationManager()?.notify(
                        RestNotifier.NOTIF_COUNTDOWN,
                        RestNotifier.buildCountdown(
                            this@RestTimerService,
                            endTime,
                            remaining,
                            totalSeconds,
                            label,
                        )
                    )
                }
                handler.postDelayed(this, 1000)
            }
        }
    }

    private fun notificationManager(): NotificationManager? =
        ContextCompat.getSystemService(this, NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                totalSeconds = intent.getIntExtra(EXTRA_SECONDS, 90)
                label = intent.getStringExtra(EXTRA_LABEL)
                endTime = System.currentTimeMillis() + totalSeconds * 1000L
                RestTimerStore.save(this, endTime, totalSeconds, label)
                RestNotifier.ensureChannels(this)
                // 清掉上一条「休息结束」通知：同一时刻只保留一条
                RestNotifier.cancelDone(this)
                startForeground(
                    RestNotifier.NOTIF_COUNTDOWN,
                    RestNotifier.buildCountdown(
                        this, endTime, totalSeconds, totalSeconds, label
                    )
                )
                // 兜底：系统闹钟到点必定唤醒，不依赖本服务存活
                if (intent.getBooleanExtra(EXTRA_USE_ALARM, true)) {
                    RestAlarmScheduler.schedule(this, endTime)
                } else {
                    RestAlarmScheduler.cancel(this)
                }
                scheduleFinish()
                startTicking()
            }

            ACTION_EXTEND -> {
                val extra = intent.getIntExtra(EXTRA_SECONDS, 30)
                val useAlarm = intent.getBooleanExtra(EXTRA_USE_ALARM, true)
                if (endTime > System.currentTimeMillis()) {
                    endTime += extra * 1000L
                    totalSeconds += extra
                    RestTimerStore.save(this, endTime, totalSeconds, label)
                    RestNotifier.ensureChannels(this)
                    RestNotifier.cancelDone(this)
                    val remaining = ((endTime - System.currentTimeMillis()) / 1000).toInt()
                    runCatching {
                        notificationManager()?.notify(
                            RestNotifier.NOTIF_COUNTDOWN,
                            RestNotifier.buildCountdown(
                                this, endTime, remaining, totalSeconds, label
                            )
                        )
                    }
                    if (useAlarm) {
                        RestAlarmScheduler.schedule(this, endTime)
                    } else {
                        RestAlarmScheduler.cancel(this)
                    }
                    scheduleFinish()
                    startTicking()
                }
            }

            ACTION_SKIP, ACTION_STOP -> {
                cancelTimer()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun scheduleFinish() {
        handler.removeCallbacks(finishRunnable)
        val delay = (endTime - System.currentTimeMillis()).coerceAtLeast(0L)
        handler.postDelayed(finishRunnable, delay)
    }

    private fun startTicking() {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, 1000)
    }

    private fun cancelTimer() {
        handler.removeCallbacks(finishRunnable)
        handler.removeCallbacks(tickRunnable)
        RestAlarmScheduler.cancel(this)
        RestTimerStore.clear(this)
        RestNotifier.cancelCountdown(this)
        RestNotifier.cancelDone(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun onFinished() {
        handler.removeCallbacks(finishRunnable)
        handler.removeCallbacks(tickRunnable)
        RestAlarmScheduler.cancel(this)
        RestTimerStore.clear(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        RestNotifier.cancelCountdown(this)
        RestNotifier.notifyRestFinished(this, label)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishRunnable)
        handler.removeCallbacks(tickRunnable)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.yfantasyxx.action.REST_START"
        const val ACTION_EXTEND = "com.yfantasyxx.action.REST_EXTEND"
        const val ACTION_SKIP = "com.yfantasyxx.action.REST_SKIP"
        const val ACTION_STOP = "com.yfantasyxx.action.REST_STOP"
        const val EXTRA_SECONDS = "extra_seconds"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_USE_ALARM = "extra_use_alarm"

        fun start(
            context: Context,
            seconds: Int,
            label: String? = null,
            useAlarm: Boolean = true,
        ) {
            val intent = Intent(context, RestTimerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SECONDS, seconds)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_USE_ALARM, useAlarm)
            ContextCompat.startForegroundService(context, intent)
        }

        fun extend(context: Context, extraSeconds: Int = 30, useAlarm: Boolean = true) {
            val intent = Intent(context, RestTimerService::class.java)
                .setAction(ACTION_EXTEND)
                .putExtra(EXTRA_SECONDS, extraSeconds)
                .putExtra(EXTRA_USE_ALARM, useAlarm)
            runCatching { context.startService(intent) }
        }

        fun skip(context: Context) {
            val intent = Intent(context, RestTimerService::class.java).setAction(ACTION_SKIP)
            runCatching { context.startService(intent) }
            RestAlarmScheduler.cancel(context)
            RestTimerStore.clear(context)
        }
    }
}
