package com.yfantasyxx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yfantasyxx.MainActivity
import com.yfantasyxx.R

/**
 * 休息计时的通知与震动，Service 和闹钟广播共用。
 */
object RestNotifier {

    const val CHANNEL_COUNTDOWN = "rest_countdown"
    const val CHANNEL_DONE = "rest_done_v2"
    const val NOTIF_COUNTDOWN = 1001
    const val NOTIF_DONE = 1002

    private fun manager(context: Context): NotificationManager? =
        ContextCompat.getSystemService(context, NotificationManager::class.java)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = manager(context) ?: return
        if (nm.getNotificationChannel(CHANNEL_COUNTDOWN) == null) {
            nm.createNotificationChannel(
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
        // 旧渠道可能被降级为静音，删掉换成新渠道
        runCatching { nm.deleteNotificationChannel("rest_done") }
        if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            nm.createNotificationChannel(
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

    fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun buildCountdown(
        context: Context,
        endTime: Long,
        remainingSeconds: Int,
        totalSeconds: Int,
        label: String? = null,
    ): Notification {
        val skip = PendingIntent.getService(
            context, 1,
            Intent(context, RestTimerService::class.java).setAction(RestTimerService.ACTION_SKIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val extend = PendingIntent.getService(
            context, 2,
            Intent(context, RestTimerService::class.java)
                .setAction(RestTimerService.ACTION_EXTEND)
                .putExtra(RestTimerService.EXTRA_SECONDS, 30),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_timer_notification)
            .setContentTitle(label ?: "组间休息")
            .setContentText("休息 $remainingSeconds 秒 · 结束后提醒你")
            .setContentIntent(openAppIntent(context))
            .setWhen(endTime)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
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

    /** 休息结束：弹横幅通知 + 震动 */
    fun notifyRestFinished(context: Context, label: String? = null) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_timer_notification)
            .setContentTitle("休息结束")
            .setContentText(
                if (label != null) "$label 完成，可以开始下一组了" else "可以开始下一组了"
            )
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        runCatching { manager(context)?.notify(NOTIF_DONE, notification) }
        vibrate(context)
    }

    fun cancelCountdown(context: Context) {
        runCatching { manager(context)?.cancel(NOTIF_COUNTDOWN) }
    }

    /** 取消「休息结束」通知，保证同一时刻只有一条休息相关通知 */
    fun cancelDone(context: Context) {
        runCatching { manager(context)?.cancel(NOTIF_DONE) }
    }

    /**
     * 四段强震动，确保能感知。
     * 带 2.6 秒去重：一次完整波形约 2.5 秒，避免前台轮询与服务/闹钟
     * 同时触发时后一次把前一次的波形重置掉（表现为只震一下）。
     */
    fun vibrate(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences("rest_vibrate", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("last_at", 0L) < 2_600L) return
            prefs.edit().putLong("last_at", now).apply()

            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = ContextCompat.getSystemService(context, VibratorManager::class.java)
                manager?.defaultVibrator
                    ?: ContextCompat.getSystemService(context, Vibrator::class.java)
            } else {
                @Suppress("DEPRECATION")
                ContextCompat.getSystemService(context, Vibrator::class.java)
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            val timings = longArrayOf(0, 400, 150, 400, 150, 400, 150, 600)
            val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        }
    }
}
