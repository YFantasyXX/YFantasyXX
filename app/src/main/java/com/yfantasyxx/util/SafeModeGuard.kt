package com.yfantasyxx.util

import android.content.Context
import android.os.SystemClock

/**
 * 安全模式守卫。
 *
 * 解决的问题：应用在启动阶段崩掉时，用户什么也做不了——设置页进不去，
 * 导出诊断包点不到，连「把玻璃模糊关掉」这个动作都发不出来。而启动崩溃
 * 恰恰最容易由渲染层引起（离屏图层、RuntimeShader），一旦命中就是死循环：
 * 每次打开都崩，永远没有机会自救。
 *
 * 这里的判定不做任何「我以为的异常」推断，只看一个很硬的事实：
 * **这次启动有没有活过 [HEALTHY_AFTER_MS]**。
 *
 * - 启动时把 `pending` 置 true；
 * - 主界面正常跑到 [HEALTHY_AFTER_MS] 之后才把它清掉；
 * - 下次启动如果发现 `pending` 还是 true，说明上一次进程没活到那一刻，
 *   计一次异常启动；
 * - 连续 [THRESHOLD] 次异常启动，判定为崩溃循环。
 *
 * 为什么不加「1 分钟内」这种时间窗口：连续计数本身已经表达了「连续」——
 * 中间只要有一次正常启动，计数就清零。加了时间窗口反而有个坏处：用户
 * 在安全模式里慢慢看说明、慢慢点按钮，隔了一分钟才触发下一次崩溃，
 * 计数被重置，于是永远进不去第二次安全模式。真正会误判的场景只有一个：
 * 被系统低内存反复杀掉，那种情况下进安全模式看一眼也不会损失什么。
 *
 * 为什么用 `SystemClock.elapsedRealtime()` 而不是 `System.currentTimeMillis()`：
 * 用户改系统时间、时区跳变都不该影响「是不是连续崩溃」的判断。
 *
 * 阈值取 3 而不是 10：真的崩起来，用户每开一次都是闪退，让他连点 10 次
 * 太折磨人；3 次已经排除了偶发（低内存被系统杀、手滑点开又退出）的可能。
 * 想改的话只改 [THRESHOLD] 一处。
 */
object SafeModeGuard {

    private const val PREFS = "safe_mode"
    private const val KEY_PENDING = "pending"
    private const val KEY_STREAK = "streak"
    private const val KEY_LAST_LAUNCH = "last_launch"
    private const val KEY_LAST_FAULT = "last_fault"
    private const val KEY_ACTIVE = "active"

    /** 连续几次异常启动判定为崩溃循环 */
    const val THRESHOLD = 3

    /**
     * 活过这么久才算这次启动是健康的。
     *
     * 启动崩溃通常发生在首帧绘制，也就是 1–2 秒内。取 4 秒既能把它们全部
     * 抓住，又不至于把「打开看一眼就退」的正常操作误判成崩溃——即便真的
     * 误判，看到安全模式点一下「直接进入」就出来了。
     */
    const val HEALTHY_AFTER_MS = 4_000L

    /**
     * 本次进程是否运行在降级渲染下。
     *
     * 只用于「强制安全渲染」这一条路径：设置一个都不改，但本次进程里
     * 底栏强制走悬浮胶囊、页面不加转场。进程结束即失效。
     */
    @Volatile
    var degraded: Boolean = false
        private set

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 在 `Application.onCreate` 的最前面调用，早于任何界面、数据库、网络初始化。
     *
     * @return 本次是否应进入安全模式
     */
    fun recordLaunch(context: Context): Boolean {
        val p = prefs(context)
        val now = SystemClock.elapsedRealtime()

        val pending = p.getBoolean(KEY_PENDING, false)
        val lastLaunch = p.getLong(KEY_LAST_LAUNCH, 0L)
        var streak = p.getInt(KEY_STREAK, 0)

        if (pending) {
            // 上一次没走到健康点：确认一次异常启动
            streak += 1
        }

        val active = streak >= THRESHOLD
        val editor = p.edit()
            .putBoolean(KEY_PENDING, true)
            .putInt(KEY_STREAK, streak)
            .putLong(KEY_LAST_LAUNCH, now)
            .putBoolean(KEY_ACTIVE, active)
        // 记「上一次启动的时刻」而不是现在：崩溃发生在那个时间点附近，
        // 安全模式界面上写「刚刚」才对得上
        if (pending) editor.putLong(KEY_LAST_FAULT, if (lastLaunch > 0L) lastLaunch else now)

        // 必须是 commit 而不是 apply：apply 是异步落盘，而我们要防的正是
        // 「进程在几百毫秒内被 SIGSEGV 干掉」——apply 的写大概率还在队列里
        // 没刷出去，pending 白置，安全模式永远触发不了。三个标量同步写一次
        // 在主线程只需微秒级，启动路径上这点开销完全值得。
        editor.commit()

        degraded = active
        return active
    }

    /** 启动成功，清掉「未完成」标记；用户主动退出应用时也会走这里 */
    fun markHealthy(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_PENDING, false)
            .putInt(KEY_STREAK, 0)
            .commit()
    }

    fun isActive(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVE, false)

    /** 当前累计的异常启动次数，安全模式界面上要显示 */
    fun streak(context: Context): Int = prefs(context).getInt(KEY_STREAK, 0)

    /** 距离上次异常启动过了多久，没记录过返回 -1 */
    fun sinceLastFaultMs(context: Context): Long {
        val t = prefs(context).getLong(KEY_LAST_FAULT, 0L)
        return if (t <= 0L) -1L else SystemClock.elapsedRealtime() - t
    }

    /**
     * 退出安全模式界面。
     *
     * 注意这里**只清 active**，`pending` 和 `streak` 原样留着：安全模式界面
     * 本身能显示，不代表主界面也能活下来。接下来进主界面后还得重新证明一次
     * ——活过 [HEALTHY_AFTER_MS] 才会 [markHealthy] 清零，否则继续累加。
     * 否则「进安全模式 → 点直接进入 → 又崩」这个循环会被自己重置，永远
     * 进不去第二次。
     *
     * @param keepDegraded true 表示仍然按降级渲染跑本次进程（不改任何设置，
     *   只是把玻璃模糊和转场绕过去），下次冷启动会重新判定。
     */
    fun exit(context: Context, keepDegraded: Boolean = false) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .commit()
        degraded = keepDegraded
    }
}
