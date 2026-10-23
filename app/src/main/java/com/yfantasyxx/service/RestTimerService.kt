package com.yfantasyxx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yfantasyxx.MainActivity
import com.yfantasyxx.R
import com.yfantasyxx.util.RestTimerStore

/**
 * 组间休息倒计时前台服务。
 *
 * - 通知使用系统 Chronometer 倒计时，无需每秒刷新通知
 * - 退出 App 后服务继续运行，计时不会重置
 * - 通知上提供「跳过」「+30s」操作
 * - 结束时发提醒通知（声音 + 震动）
 */
class RestTimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var endTime = 0L
    private var totalSeconds = 0

    private val finishRunnable = Runnable { onFinished() }

    /** 每秒刷新通知，让用户直接看到「剩余 88s」这样的实时秒数 */
    private val tickRunnable = object : Runnable {
        override fun run() {
            val remaining = ((endTime - System.currentTimeMillis()) / 1000).toInt()
            if (remaining > 0) {
                runCatching {
                    notificationManager().notify(
                        NOTIF_COUNTDOWN,
                        buildCountdownNotification(endTime, remaining)
                    )
                }
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                totalSeconds = intent.getIntExtra(EXTRA_SECONDS, 90)
                endTime = System.currentTimeMillis() + totalSeconds * 1000L
                RestTimerStore.save(this, endTime, totalSeconds)
                ensureChannels()
                startForeground(NOTIF_COUNTDOWN, buildCountdownNotification(endTime, totalSeconds))
                scheduleFinish()
                startTicking()
            }

            ACTION_EXTEND -> {
                val extra = intent.getIntExtra(EXTRA_SECONDS, 30)
                if (endTime > System.currentTimeMillis()) {
                    endTime += extra * 1000L
                    totalSeconds += extra
                    RestTimerStore.save(this, endTime, totalSeconds)
                    ensureChannels()
                    notificationManager().notify(
                        NOTIF_COUNTDOWN,
                        buildCountdownNotification(endTime, ((endTime - System.currentTimeMillis()) / 1000).toInt())
                    )
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
        RestTimerStore.clear(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun onFinished() {
        handler.removeCallbacks(finishRunnable)
        handler.removeCallbacks(tickRunnable)
        RestTimerStore.clear(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        // 结束提醒（声音 + 震动）
        notificationManager().notify(NOTIF_DONE, buildDoneNotification())
        vibrate()
        stopSelf()
    }

    private fun vibrate() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 200, 120, 200), -1)
            )
        }
    }

    // ---------- 通知 ----------

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_COUNTDOWN) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_COUNTDOWN,
                    "组间休息计时",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "训练时在通知栏显示休息倒计时"
                    setShowBadge(false)
                }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_DONE) == null) {
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DONE,
                    "休息结束提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "休息倒计时结束时提醒"
                    enableVibration(true)
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                    setSound(sound, attrs)
                }
            )
        }
    }

    private fun buildCountdownNotification(endTime: Long, remainingSeconds: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val skip = PendingIntent.getService(
            this, 1,
            Intent(this, RestTimerService::class.java).setAction(ACTION_SKIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val extend = PendingIntent.getService(
            this, 2,
            Intent(this, RestTimerService::class.java)
                .setAction(ACTION_EXTEND)
                .putExtra(EXTRA_SECONDS, 30),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_timer_notification)
            .setContentTitle("组间休息 · 剩余 ${remainingSeconds}s")
            .setContentText("结束后会提醒你")
            .setContentIntent(openApp)
            // 系统级倒计时：两次刷新之间也保持准确
            .setWhen(endTime)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            // 进度条随剩余时间递减，展开通知可见
            .setProgress(
                totalSeconds.coerceAtLeast(1),
                (totalSeconds - remainingSeconds).coerceIn(0, totalSeconds),
                false
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "跳过", skip)
            .addAction(0, "+30s", extend)
            .build()
    }

    private fun buildDoneNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_timer_notification)
            .setContentTitle("休息结束")
            .setContentText("可以开始下一组了")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun notificationManager(): NotificationManager =
        ContextCompat.getSystemService(this, NotificationManager::class.java)!!

    companion object {
        const val ACTION_START = "com.yfantasyxx.action.REST_START"
        const val ACTION_EXTEND = "com.yfantasyxx.action.REST_EXTEND"
        const val ACTION_SKIP = "com.yfantasyxx.action.REST_SKIP"
        const val ACTION_STOP = "com.yfantasyxx.action.REST_STOP"
        const val EXTRA_SECONDS = "extra_seconds"

        private const val CHANNEL_COUNTDOWN = "rest_countdown"
        private const val CHANNEL_DONE = "rest_done"
        private const val NOTIF_COUNTDOWN = 1001
        private const val NOTIF_DONE = 1002

        fun start(context: Context, seconds: Int) {
            val intent = Intent(context, RestTimerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SECONDS, seconds)
            ContextCompat.startForegroundService(context, intent)
        }

        fun extend(context: Context, extraSeconds: Int = 30) {
            val intent = Intent(context, RestTimerService::class.java)
                .setAction(ACTION_EXTEND)
                .putExtra(EXTRA_SECONDS, extraSeconds)
            runCatching { context.startService(intent) }
        }

        fun skip(context: Context) {
            val intent = Intent(context, RestTimerService::class.java).setAction(ACTION_SKIP)
            runCatching { context.startService(intent) }
            RestTimerStore.clear(context)
        }
    }
}
