package com.yfantasyxx.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.yfantasyxx.YFantasyXXApplication
import com.yfantasyxx.data.prefs.AlertSound
import com.yfantasyxx.util.AppVisibility
import com.yfantasyxx.util.RestTimerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 组间休息倒计时前台服务。
 *
 * - 通知栏用系统 Chronometer 倒计时，同时每秒刷新标题里的剩余秒数
 * - 同时设置 AlarmManager 精确闹钟：即使 App 在后台被冻结、本服务不再执行，
 *   到点也能弹出提醒并震动（见 [RestAlarmReceiver]）
 * - App 不在前台时再挂一层系统悬浮窗（见 [RestOverlayController]），
 *   桌面上也能看见还剩几秒、结束时有张大卡片
 *
 * 结束时服务不立刻退出：前台通知换成「休息结束」，多留 12 秒把悬浮窗撑住，
 * 然后 detach 掉通知再停，人回来晚了也还能看到横幅。
 */
class RestTimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var endTime = 0L
    private var totalSeconds = 0
    private var label: String? = null

    /** 已经处理过「结束」，避免普通结束和闹钟兜底各弹一次 */
    private var finished = false

    private var overlay: RestOverlayController? = null
    private var overlayEnabled = true
    private var alertSound = AlertSound.ALARM

    private val finishRunnable = Runnable { onFinished() }
    private val endRunnable = Runnable { endFinished() }

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
                syncOverlay()
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
                finished = false
                totalSeconds = intent.getIntExtra(EXTRA_SECONDS, 90)
                label = intent.getStringExtra(EXTRA_LABEL)
                endTime = System.currentTimeMillis() + totalSeconds * 1000L
                RestTimerStore.save(this, endTime, totalSeconds, label)
                RestNotifier.ensureChannels(this)
                RestNotifier.stopAlertSound()
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
                loadAlertPrefs { syncOverlay() }
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
                    overlay?.updateCountdown(endTime, totalSeconds)
                    if (useAlarm) {
                        RestAlarmScheduler.schedule(this, endTime)
                    } else {
                        RestAlarmScheduler.cancel(this)
                    }
                    scheduleFinish()
                    startTicking()
                }
            }

            // 闹钟到点把服务拉起来：App 可能早被杀了，这里只负责「提醒」这一件事
            ACTION_FINISHED -> {
                if (finished) return START_NOT_STICKY
                finished = true
                handler.removeCallbacks(finishRunnable)
                handler.removeCallbacks(tickRunnable)
                label = intent.getStringExtra(EXTRA_LABEL)
                val forceOverlay = intent.getBooleanExtra(EXTRA_FORCE_OVERLAY, false)
                RestNotifier.ensureChannels(this)
                RestNotifier.cancelCountdown(this)
                // 必须先进前台，否则后台广播里起的服务会被系统立刻掐掉
                runCatching {
                    startForeground(
                        RestNotifier.NOTIF_DONE,
                        RestNotifier.buildFinished(this, label),
                    )
                }
                RestNotifier.vibrate(this)
                // 万一设置读不出来也要停掉，不能一直挂在前台
                scheduleEnd(FINISHED_LINGER_MS + 3_000L)
                loadAlertPrefs {
                    RestNotifier.playAlertSound(this, alertSound)
                    showFinishedOverlay(force = forceOverlay)
                    scheduleEnd(FINISHED_LINGER_MS)
                }
            }

            ACTION_SKIP, ACTION_STOP -> {
                cancelTimer()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ---------- 悬浮窗 ----------

    /**
     * 悬浮窗只在 App 不在前台时出现。
     *
     * 训练页里本来就有休息倒计时条，再盖一层纯属碍事；一秒检查一次，
     * 切到桌面后最多 1 秒气泡就出来了。
     */
    private fun syncOverlay() {
        if (!overlayEnabled) return
        if (AppVisibility.foreground) {
            overlay?.hide()
            return
        }
        if (overlay?.isShowing == true) return
        val controller = overlay ?: RestOverlayController(this).also { overlay = it }
        controller.showCountdown(endTime, totalSeconds, label)
    }

    private fun showFinishedOverlay(force: Boolean = false) {
        if (!force) {
            if (!overlayEnabled) return
            // 人就在 App 里，结束提示交给 App 自己的休息条
            if (AppVisibility.foreground) return
        }
        val controller = overlay ?: RestOverlayController(this).also { overlay = it }
        controller.showFinished(label)
    }

    /**
     * 读一次提醒设置。
     *
     * 不用 SharedPreferences 另存一份镜像：DataStore 是唯一数据源，
     * 服务每次启动读一次就好，读失败就按默认值（悬浮窗开、闹钟音）。
     */
    private fun loadAlertPrefs(onReady: () -> Unit) {
        scope.launch {
            runCatching {
                val app = applicationContext as YFantasyXXApplication
                app.container.settingsRepository.settings.first()
            }.getOrNull()?.let {
                overlayEnabled = it.restOverlay
                alertSound = it.restAlertSound
            }
            onReady()
        }
    }

    // ---------- 生命周期 ----------

    private fun scheduleFinish() {
        handler.removeCallbacks(finishRunnable)
        val delay = (endTime - System.currentTimeMillis()).coerceAtLeast(0L)
        handler.postDelayed(finishRunnable, delay)
    }

    private fun startTicking() {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, 1000)
    }

    private fun scheduleEnd(delay: Long) {
        handler.removeCallbacks(endRunnable)
        handler.postDelayed(endRunnable, delay)
    }

    private fun cancelTimer() {
        finished = true
        handler.removeCallbacks(finishRunnable)
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(endRunnable)
        RestAlarmScheduler.cancel(this)
        RestTimerStore.clear(this)
        RestNotifier.cancelCountdown(this)
        RestNotifier.cancelDone(this)
        RestNotifier.stopAlertSound()
        overlay?.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun onFinished() {
        if (finished) return
        finished = true
        handler.removeCallbacks(finishRunnable)
        handler.removeCallbacks(tickRunnable)
        RestAlarmScheduler.cancel(this)
        RestTimerStore.clear(this)
        RestNotifier.cancelCountdown(this)
        RestNotifier.vibrate(this)
        RestNotifier.playAlertSound(this, alertSound)

        // 前台通知换成「休息结束」：服务这 12 秒继续算前台，
        // 悬浮窗才不会被系统顺手收走
        runCatching {
            startForeground(
                RestNotifier.NOTIF_DONE,
                RestNotifier.buildFinished(this, label),
            )
        }
        showFinishedOverlay()
        scheduleEnd(FINISHED_LINGER_MS)
    }

    /** 结束状态的收尾：通知 detach 出去（还能手动划掉），服务退出 */
    private fun endFinished() {
        handler.removeCallbacks(endRunnable)
        stopForeground(STOP_FOREGROUND_DETACH)
        overlay?.hide()
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishRunnable)
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(endRunnable)
        overlay?.hide()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.yfantasyxx.action.REST_START"
        const val ACTION_EXTEND = "com.yfantasyxx.action.REST_EXTEND"
        const val ACTION_SKIP = "com.yfantasyxx.action.REST_SKIP"
        const val ACTION_STOP = "com.yfantasyxx.action.REST_STOP"
        const val ACTION_FINISHED = "com.yfantasyxx.action.REST_FINISHED"
        const val EXTRA_SECONDS = "extra_seconds"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_USE_ALARM = "extra_use_alarm"
        const val EXTRA_FORCE_OVERLAY = "extra_force_overlay"

        /** 结束提示在屏幕上停留多久 */
        const val FINISHED_LINGER_MS = 12_000L

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

        /**
         * 直接进入「休息结束」状态。
         *
         * 闹钟到点（服务可能已经被杀）和测试工具都走这里：
         * 只有服务活着才挂得上悬浮窗，也能保证结束提示以 FGS 通知形式发出去。
         */
        fun showFinished(
            context: Context,
            label: String? = null,
            forceOverlay: Boolean = false,
        ) {
            val intent = Intent(context, RestTimerService::class.java)
                .setAction(ACTION_FINISHED)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_FORCE_OVERLAY, forceOverlay)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
