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
import android.os.SystemClock
import android.os.VibrationAttributes
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
                // 上界必须和 max 用同一个夹取结果：直接用原始 totalSeconds 的话，
                // 一旦它 <= 0，coerceIn(0, 负数) 会抛「空区间」异常，通知直接建不出来
                (totalSeconds - remainingSeconds)
                    .coerceAtMost(totalSeconds.coerceAtLeast(1))
                    .coerceAtLeast(0),
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
     * 常驻模式下的「休息结束」通知。
     *
     * 悬浮窗要一直留着（12 秒后收成小球继续挂），前台服务就不能退——
     * 进程被系统回收的话，悬浮窗窗口会跟着一起消失。
     *
     * 这条通知是 ongoing 的（划不掉），也**不给「关闭」按钮**：
     * 常驻小球本身就是不可关闭的，想收掉只有一条路——去设置里关掉
     * 「悬浮窗常驻」开关，服务会跟着退场。
     */
    fun buildFinishedPersistent(context: Context, label: String? = null): Notification =
        NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_timer_notification)
            .setContentTitle("休息结束")
            .setContentText(
                if (label != null) "$label 完成，可以开始下一组了" else "可以开始下一组了"
            )
            .setSubText("悬浮窗常驻中")
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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

    /** 四段强震动，确保能感知。
     *
     * Android 的振动器同一时刻只放一条，系统里别的应用（通知、键盘触感、来电）
     * 一响就会把**正在响的那一条**顶掉，而且被打断的波形不会自己续上。
     * 公开 API 里没有「设备正在震动吗」这种查询，也没法排队，所以只能：
     *
     * 1. 拆成四次独立下发：打断只吃掉当前这一下，后面几次照常发出；
     * 2. 每段之间留 200ms：给系统那条短震动留出收尾时间，别贴脸抢；
     * 3. 起手先等 350ms：休息结束的横幅通知如果自己带了震动，让它先响完；
     * 4. 标成 [VibrationAttributes.USAGE_ALARM]：和提示音走 STREAM_ALARM 是同一套
     *    口径。后台应用不带 usage 的震动在 Android 13+ 可能被系统直接丢掉，
     *    带上之后既能保证后台震得出来，系统调度时也按闹钟对待。
     *
     * 带 3.2 秒去重：一整轮约 2.75 秒，避免前台轮询与服务/闹钟同时触发时
     * 两轮叠在一起（表现为震动变密而不是更长）。
     */
    @Synchronized
    fun vibrate(context: Context, force: Boolean = false) {
        runCatching {
            val prefs = context.getSharedPreferences("rest_vibrate", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            // force 给测试工具用：连着点几次也能每次都震，不被去重吃掉
            if (!force && now - prefs.getLong("last_at", 0L) < VIBRATE_DEDUPE_MS) return
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

            // 上一轮还没发完（测试工具连点）：先撤掉它剩下的脉冲，免得两轮叠在一起
            vibrateToken?.let { vibrateHandler.removeCallbacksAndMessages(it) }
            val token = Any().also { vibrateToken = it }

            var offset = VIBRATE_LEAD_IN_MS
            for (duration in VIBRATE_PULSES_MS) {
                vibrateHandler.postAtTime(
                    { runCatching { fire(vibrator, duration) } },
                    token,
                    SystemClock.uptimeMillis() + offset,
                )
                offset += duration + VIBRATE_GAP_MS
            }
        }
    }

    /**
     * 单次脉冲。
     *
     * 33 起用 [VibrationAttributes]；32 上只有废弃的 AudioAttributes 重载，
     * 两者都把 usage 标成 ALARM，语义一致。
     */
    @Suppress("DEPRECATION")
    private fun fire(vibrator: Vibrator, durationMs: Long) {
        val effect = VibrationEffect.createOneShot(durationMs, VIBRATE_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        } else {
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
    }

    /** 震动播放在主线程排期：只有 4 个脉冲，精度比协程 delay 稳，也不用多一个 scope */
    private val vibrateHandler = Handler(Looper.getMainLooper())
    private var vibrateToken: Any? = null

    /** 400 / 400 / 400 / 600，之间各停 200ms —— 与原来那条波形的节奏一致 */
    private val VIBRATE_PULSES_MS = longArrayOf(400, 400, 400, 600)
    private const val VIBRATE_GAP_MS = 200L
    private const val VIBRATE_LEAD_IN_MS = 350L
    private const val VIBRATE_AMPLITUDE = 255
    private const val VIBRATE_DEDUPE_MS = 3_200L
}
