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

    /**
     * 悬浮窗是进程级单例，不是 Service 的私产。
     *
     * 常驻模式下结束卡片要活过前台服务的生命周期（服务 12 秒后就 stopSelf），
     * 窗口挂在 Service 上会跟着一起没了。
     */
    private val overlay: RestOverlayController
        get() = RestOverlayController.shared(this)

    private var overlayEnabled = true

    /** 常驻模式：前台也显示气泡，结束卡片不自动消失 */
    private var overlayPersistent = false

    private var alertSound = AlertSound.ALARM

    /** 提醒设置读回来过没有。常驻开关决定卡片要不要留着，读晚了会画错 */
    private var prefsLoaded = false

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

    override fun onCreate() {
        super.onCreate()
        watchPersistentSetting()
    }

    /**
     * 一直盯着「悬浮窗常驻」这个开关。
     *
     * 常驻气泡没有关闭按钮、也不会自己消失，设置里关掉这个开关是唯一的出口，
     * 所以不能只在服务启动时读一次，得全程听着。
     */
    private fun watchPersistentSetting() {
        scope.launch {
            runCatching {
                val app = applicationContext as YFantasyXXApplication
                app.container.settingsRepository.settings.collect { s ->
                    val wasOn = overlayPersistent
                    overlayPersistent = s.restOverlayPersistent
                    overlayEnabled = s.restOverlay
                    alertSound = s.restAlertSound
                    prefsLoaded = true
                    // 卡片/气泡的形态取决于这个开关，改完立刻同步给控制器
                    overlay.persistent = s.restOverlayPersistent
                    // 常驻被关掉（或者连悬浮窗一块儿关了）：气泡没理由再挂着。
                    // 只在「休息结束」那个状态下收摊——计时还在走的时候关开关，
                    // 只是让这颗气泡按普通规则来，不该把倒计时一起停掉
                    if (wasOn && finished && (!s.restOverlayPersistent || !s.restOverlay)) {
                        dismissPersistent()
                        return@collect
                    }
                    // 反过来：卡片还在这 12 秒里，用户把常驻打开了。原本安排好的
                    // 退场得取消，否则卡片刚收成小球，服务一停小球就跟着没了
                    if (!wasOn && s.restOverlayPersistent && finished) {
                        handler.removeCallbacks(endRunnable)
                        runCatching {
                            startForeground(
                                RestNotifier.NOTIF_DONE,
                                RestNotifier.buildFinishedPersistent(this@RestTimerService, label),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务被 OEM 清掉后又被通知上的「+30s / 跳过」拉起：内存里的字段全没了，
        // 先从 SharedPreferences 回填，否则加时会被当成「没有计时」直接跳过
        if (endTime == 0L) {
            val snap = RestTimerStore.snapshot(this)
            if (snap.endTime > 0L) {
                endTime = snap.endTime
                totalSeconds = snap.totalSeconds.coerceAtLeast(1)
                label = snap.label
            }
        }
        when (intent?.action) {
            ACTION_START -> {
                finished = false
                // 上一轮「休息结束」可能还排着 12 秒后的退场任务。不清掉的话，
                // 新一轮刚开始计时，那个 endRunnable 会照旧到点 stopSelf()——
                // 通知栏倒计时和悬浮窗一起消失；服务一旦不再前台，很快会被系统回收，
                // 这一轮休息就彻底没了（只剩闹钟兜底）
                handler.removeCallbacks(endRunnable)
                // 夹住：Intent 里可能是 0 或负数（外部调用、旧版本残留），
                // 负数会让 endTime 落在过去（倒计时立刻结束），
                // 还会让 buildCountdown 里的 setProgress 拿到反转区间直接抛异常
                totalSeconds = intent.getIntExtra(EXTRA_SECONDS, 90).coerceIn(1, 3600)
                label = intent.getStringExtra(EXTRA_LABEL)
                endTime = System.currentTimeMillis() + totalSeconds * 1000L
                RestTimerStore.save(this, endTime, totalSeconds, label)
                RestNotifier.ensureChannels(this)
                RestNotifier.stopAlertSound()
                // 清掉上一条「休息结束」通知：同一时刻只保留一条
                RestNotifier.cancelDone(this)
                // 前台通知建不出来（渠道被用户删掉/禁用、FGS 类型受限）时，
                // 服务不能跟着崩：退回「没有通知栏倒计时」，但计时和闹钟照常走
                val posted = runCatching {
                    startForeground(
                        RestNotifier.NOTIF_COUNTDOWN,
                        RestNotifier.buildCountdown(
                            this, endTime, totalSeconds, totalSeconds, label
                        )
                    )
                }.isSuccess
                if (!posted) {
                    // 服务里没有能弹 UI 的通道，只能留痕；计时和闹钟都还在走，
                    // 到点仍会有「休息结束」提醒（除非通知整体被关）
                    android.util.Log.w(
                        "RestTimer",
                        "倒计时前台通知发布失败：渠道可能被禁用或 FGS 受限",
                    )
                }
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
                    overlay.updateCountdown(endTime, totalSeconds)
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
                val doneLabel = intent.getStringExtra(EXTRA_LABEL) ?: label
                val forceOverlay = intent.getBooleanExtra(EXTRA_FORCE_OVERLAY, false)
                RestNotifier.ensureChannels(this)
                // 无条件先进前台：调用方走的是 startForegroundService，
                // 5 秒内不调 startForeground 系统就抛 RemoteServiceException（后台闪退）。
                // 以前 finished == true 时直接 return，正好漏掉这一步
                runCatching {
                    startForeground(
                        RestNotifier.NOTIF_DONE,
                        RestNotifier.buildFinished(this, doneLabel),
                    )
                }
                if (finished) {
                    // 已经提醒过一轮，这次只是补一个前台身份，撑一会儿就退
                    if (overlayPersistent) {
                        // 常驻模式：卡片还挂着，服务也别退。
                        // 顺手把通知换成常驻版（带「关闭」按钮），否则上面那条
                        // 自动消失的通知会把「关闭」入口顶掉
                        handler.removeCallbacks(endRunnable)
                        runCatching {
                            startForeground(
                                RestNotifier.NOTIF_DONE,
                                RestNotifier.buildFinishedPersistent(this, doneLabel),
                            )
                        }
                    } else {
                        scheduleEnd(FINISHED_LINGER_MS)
                    }
                    return START_NOT_STICKY
                }
                finished = true
                handler.removeCallbacks(finishRunnable)
                handler.removeCallbacks(tickRunnable)
                label = doneLabel
                RestNotifier.cancelCountdown(this)
                RestNotifier.vibrate(this)
                // 万一设置读不出来也要停掉，不能一直挂在前台
                scheduleEnd(FINISHED_LINGER_MS + 3_000L)
                loadAlertPrefs {
                    RestNotifier.playAlertSound(this, alertSound)
                    showFinishedOverlay(force = forceOverlay)
                    if (overlayPersistent) {
                        // 常驻模式：结束卡片不自动消失，服务也继续留着撑住进程，
                        // 直到用户手动把卡片关掉
                        handler.removeCallbacks(endRunnable)
                        runCatching {
                            startForeground(
                                RestNotifier.NOTIF_DONE,
                                RestNotifier.buildFinishedPersistent(this, doneLabel),
                            )
                        }
                    } else {
                        scheduleEnd(FINISHED_LINGER_MS)
                    }
                }
            }

            ACTION_SKIP, ACTION_STOP -> {
                cancelTimer()
                stopSelf()
            }

            // 只有测试工具会发这个：正常使用时常驻气泡的唯一出口是关掉设置里的开关
            ACTION_DISMISS -> dismissPersistent()
        }
        return START_NOT_STICKY
    }

    // ---------- 悬浮窗 ----------

    /**
     * 悬浮窗只在 App 不在前台时出现。
     *
     * 训练页里本来就有休息倒计时条，再盖一层纯属碍事；一秒检查一次，
     * 切到桌面后最多 1 秒气泡就出来了。
     *
     * 常驻模式（[overlayPersistent]）例外：用户就是要一直看着它，
     * 所以前台也照常显示。
     */
    private fun syncOverlay() {
        if (!overlayEnabled) return
        if (AppVisibility.foreground && !overlayPersistent) {
            overlay.hide()
            return
        }
        // 已经在显示倒计时气泡就别重复设置
        // （注意不能只看 isShowing：上一轮的结束卡片还挂着时，
        //   新一轮计时得把它切回气泡，否则一直显示「休息结束」）
        if (overlay.isShowing && overlay.mode == RestOverlayController.MODE_COUNTDOWN) return
        overlay.persistent = overlayPersistent
        overlay.showCountdown(endTime, totalSeconds, label)
    }

    private fun showFinishedOverlay(force: Boolean = false) {
        if (!force) {
            if (!overlayEnabled) return
            // 人就在 App 里，结束提示交给 App 自己的休息条。
            // 但计时气泡要先撤掉：用户恰好在最后一秒切回 App 时，
            // 「0:00」气泡会一直盖在界面上直到 12 秒后服务收尾
            // 常驻模式例外：卡片就是要一直挂着。
            if (AppVisibility.foreground && !overlayPersistent) {
                overlay.hide()
                return
            }
        }
        overlay.persistent = overlayPersistent
        overlay.showFinished(label)
    }

    /**
     * 等设置读回来。
     *
     * [watchPersistentSetting] 已经常驻收集整个设置流了，绝大多数时候这里
     * 直接就是 ready 的；只有在服务刚起来、第一帧设置还没到的时候才真去读一次。
     */
    private fun loadAlertPrefs(onReady: () -> Unit) {
        if (prefsLoaded) {
            onReady()
            return
        }
        scope.launch {
            runCatching {
                val app = applicationContext as YFantasyXXApplication
                // awaitSettings 而不是 first()：服务可能由闹钟从冷启动拉起，
                // 那一刻 StateFlow 里还是默认设置（90 秒 / 悬浮窗开 / 闹钟音），
                // 用户真正设的那份还没从磁盘读出来
                app.container.settingsRepository.awaitSettings()
            }.getOrNull()?.let {
                overlayEnabled = it.restOverlay
                overlayPersistent = it.restOverlayPersistent
                alertSound = it.restAlertSound
            }
            prefsLoaded = true
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
        overlay.hide()
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

        // 设置还没读回来（刚启动就结束）就等一下再画卡片：
        // 常驻开关的状态决定卡片要不要留着，用默认值画会画错
        if (!prefsLoaded) {
            loadAlertPrefs { showFinishedUi() }
            return
        }
        showFinishedUi()
    }

    /** 结束时的界面部分：前台通知换「休息结束」+ 悬浮窗换成大卡片 */
    private fun showFinishedUi() {
        // 前台通知换成「休息结束」：服务这 12 秒继续算前台，
        // 悬浮窗才不会被系统顺手收走
        runCatching {
            startForeground(
                RestNotifier.NOTIF_DONE,
                if (overlayPersistent) RestNotifier.buildFinishedPersistent(this, label)
                else RestNotifier.buildFinished(this, label),
            )
        }
        showFinishedOverlay()
        if (overlayPersistent) {
            // 常驻模式：结束卡片不自动消失，前台服务也不退，
            // 撑住进程让卡片一直挂在屏幕上，直到用户手动关掉
            handler.removeCallbacks(endRunnable)
        } else {
            scheduleEnd(FINISHED_LINGER_MS)
        }
    }

    /**
     * 结束状态的收尾：通知 detach 出去（还能手动划掉），服务退出。
     *
     * 常驻模式下不走这里：卡片要留住，服务也得留着撑住进程，
     * 直到用户手动关掉卡片（悬浮窗的 × 或通知上的「关闭」）。
     */
    private fun endFinished() {
        handler.removeCallbacks(endRunnable)
        // 这 12 秒里可能已经开始了下一轮休息：结束卡片还没收，用户回 App 勾了下一组。
        // ACTION_START 会把 finished 重置成 false，这时退场任务就该作废。
        if (!finished) return
        stopForeground(STOP_FOREGROUND_DETACH)
        overlay.hide()
        stopSelf()
    }

    /**
     * 收掉常驻气泡并退场。
     *
     * 触发点只有一个：用户在设置里关掉「悬浮窗常驻」。
     * 常驻气泡本身没有关闭按钮，这是它唯一的出口。
     */
    private fun dismissPersistent() {
        handler.removeCallbacks(endRunnable)
        overlay.hide()
        RestNotifier.cancelDone(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishRunnable)
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(endRunnable)
        // 服务停了声音必须停：Ringtone 是静态对象，不主动停会继续响到 8 秒结束
        runCatching { RestNotifier.stopAlertSound() }
        // 常驻模式下卡片要活过服务，不能在这里收掉
        if (!overlayPersistent) overlay.hide()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.yfantasyxx.action.REST_START"
        const val ACTION_EXTEND = "com.yfantasyxx.action.REST_EXTEND"
        const val ACTION_SKIP = "com.yfantasyxx.action.REST_SKIP"
        const val ACTION_STOP = "com.yfantasyxx.action.REST_STOP"
        const val ACTION_FINISHED = "com.yfantasyxx.action.REST_FINISHED"

        /** 仅测试工具使用：强制收掉常驻气泡并退场 */
        const val ACTION_DISMISS = "com.yfantasyxx.action.REST_DISMISS"
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
         * 强制收掉常驻气泡。
         *
         * 只给测试工具用：正常路径下常驻气泡没有关闭入口，唯一的出口是
         * 去设置里关掉「悬浮窗常驻」。服务在跑就顺带让它退场，
         * 否则会留下一个只挂着通知、没有窗口的前台服务。
         */
        fun dismissOverlayNow(context: Context) {
            val intent = Intent(context, RestTimerService::class.java).setAction(ACTION_DISMISS)
            runCatching { context.startService(intent) }
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
