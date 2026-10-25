package com.yfantasyxx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yfantasyxx.MainActivity
import com.yfantasyxx.R
import com.yfantasyxx.data.prefs.AlertSound

/**
 * 休息计时的通知与震动，Service 和闹钟广播共用。
 */
object RestNotifier {

    const val CHANNEL_COUNTDOWN = "rest_countdown"

    /**
     * v3：渠道本身不出声也不震动。
     *
     * 声音和震动改由 App 自己发，这样才能按设置选「闹钟音量」，
     * 也不会出现「渠道响一声 + 自己再响一声」的双重提示。
     */
    const val CHANNEL_DONE = "rest_done_v3"
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
        // 旧渠道一并删掉：v1 是静音版，v2 带系统提示音（会和自放的声音叠成两声）
        runCatching { nm.deleteNotificationChannel("rest_done") }
        runCatching { nm.deleteNotificationChannel("rest_done_v2") }
        if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DONE,
                    "休息结束提醒",
                    // IMPORTANCE_HIGH 才会弹横幅；声音交给 App 自己控制
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "休息倒计时结束时提醒"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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

    /** 「休息结束」通知本体。前台服务也会拿它当 FGS 通知，保证结束提示不被系统收走 */
    fun buildFinished(context: Context, label: String? = null): Notification =
        NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_timer_notification)
            .setContentTitle("休息结束")
            .setContentText(
                if (label != null) "$label 完成，可以开始下一组了" else "可以开始下一组了"
            )
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // 锁屏上也把内容显出来，不然只能看到一个「有新通知」
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(60_000L)
            .build()

    /**
     * 休息结束：横幅通知 + 震动 + 提示音。
     *
     * 走前台服务时用 [buildFinished] + startForeground，这条只在
     * 服务起不来（比如被系统拦住）时兜底。
     */
    fun notifyRestFinished(
        context: Context,
        label: String? = null,
        sound: AlertSound = AlertSound.ALARM,
    ) {
        ensureChannels(context)
        runCatching { manager(context)?.notify(NOTIF_DONE, buildFinished(context, label)) }
        vibrate(context)
        playAlertSound(context, sound)
    }

    // ---------- 提示音 ----------

    private var ringtone: Ringtone? = null
    private val soundHandler = Handler(Looper.getMainLooper())

    /**
     * 放提示音。
     *
     * 用 STREAM_ALARM 的话，即使手机调成静音也能听见——健身房里手机基本都是
     * 静音或震动，走通知音量等于没提醒。勿扰模式仍会压住（系统级策略，绕不过）。
     */
    fun playAlertSound(context: Context, sound: AlertSound) {
        stopAlertSound()
        if (sound == AlertSound.SILENT) return
        runCatching {
            val type = if (sound == AlertSound.ALARM) {
                RingtoneManager.TYPE_ALARM
            } else {
                RingtoneManager.TYPE_NOTIFICATION
            }
            val uri = RingtoneManager.getDefaultUri(type) ?: return
            val rt = RingtoneManager.getRingtone(context, uri) ?: return
            rt.audioAttributes = AudioAttributes.Builder()
                .setUsage(
                    if (sound == AlertSound.ALARM) {
                        AudioAttributes.USAGE_ALARM
                    } else {
                        AudioAttributes.USAGE_NOTIFICATION_EVENT
                    }
                )
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            rt.play()
            ringtone = rt
            // 默认闹铃素材动辄二三十秒，响太久很烦；8 秒足够人注意到了
            soundHandler.postDelayed({
                if (ringtone === rt) stopAlertSound()
            }, 8_000L)
        }
    }

    fun stopAlertSound() {
        runCatching {
            ringtone?.let {
                if (it.isPlaying) it.stop()
            }
        }
        ringtone = null
        soundHandler.removeCallbacksAndMessages(null)
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
    fun vibrate(context: Context, force: Boolean = false) {
        runCatching {
            val prefs = context.getSharedPreferences("rest_vibrate", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            // force 给测试工具用：连着点几次也能每次都震，不被去重吃掉
            if (!force && now - prefs.getLong("last_at", 0L) < 2_600L) return
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
