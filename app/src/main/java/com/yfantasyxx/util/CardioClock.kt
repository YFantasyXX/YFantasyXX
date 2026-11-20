package com.yfantasyxx.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 进行中的有氧计时状态。
 *
 * 落 SharedPreferences 而不是只放内存：切后台、被系统回收、
 * 从通知栏杀回来，秒表都得接着走。
 *
 * 存的是**绝对时刻**而不是「已累计多少毫秒」——界面每次按
 * `现在 - 开始时刻 - 暂停累计` 现算。这样不用起前台服务保活，
 * 也不会有 tick 累加带来的漂移，App 在后台待一小时回来数字也是准的。
 *
 * 时钟一律用 [Clock.realNow]：测试工具拨的是业务时间，
 * 秒表要是跟着拨过去，会瞬间跳出几个小时。
 */
object CardioClock {

    private const val PREFS = "cardio_clock"
    private const val K_ACTIVE = "active"
    private const val K_TYPE = "type"
    private const val K_SETTINGS = "settings"
    private const val K_STARTED = "startedAt"
    private const val K_PAUSED_TOTAL = "pausedTotalMs"
    private const val K_PAUSED_AT = "pausedAt"

    /**
     * @param pausedAt 0 = 正在走；> 0 = 从这一刻起暂停
     */
    data class Active(
        val type: String,
        val startedAt: Long,
        val pausedTotalMs: Long,
        val pausedAt: Long,
        /** 器械参数 JSON，见 [com.yfantasyxx.data.CardioControls] */
        val settings: String = "",
    ) {
        val running: Boolean get() = pausedAt <= 0L

        /** 有效时长（毫秒），暂停的那段不算 */
        fun elapsedMs(now: Long = Clock.realNow()): Long {
            val end = if (pausedAt > 0L) pausedAt else now
            return (end - startedAt - pausedTotalMs).coerceAtLeast(0L)
        }
    }

    fun read(context: Context): Active? {
        val p = prefs(context)
        if (!p.getBoolean(K_ACTIVE, false)) return null
        val startedAt = p.getLong(K_STARTED, 0L)
        // 开始时刻为空说明这份状态是坏的（写到一半被杀），当作没有
        if (startedAt <= 0L) return null
        return Active(
            type = p.getString(K_TYPE, null) ?: "other",
            startedAt = startedAt,
            pausedTotalMs = p.getLong(K_PAUSED_TOTAL, 0L).coerceAtLeast(0L),
            pausedAt = p.getLong(K_PAUSED_AT, 0L),
            settings = p.getString(K_SETTINGS, "").orEmpty(),
        )
    }

    /** 开始一段新的。已有进行中的会被覆盖，调用方负责先确认 */
    fun start(
        context: Context,
        type: String,
        settings: String = "",
        at: Long = Clock.realNow(),
    ) {
        edit(context) {
            putBoolean(K_ACTIVE, true)
            putString(K_TYPE, type)
            putString(K_SETTINGS, settings)
            putLong(K_STARTED, at)
            putLong(K_PAUSED_TOTAL, 0L)
            putLong(K_PAUSED_AT, 0L)
        }
    }

    fun setType(context: Context, type: String) {
        if (read(context) == null) return
        edit(context) { putString(K_TYPE, type) }
    }

    /** 训练中调坡度/速度：跟着秒表一起落盘，切后台再回来不会丢 */
    fun setSettings(context: Context, settings: String) {
        if (read(context) == null) return
        edit(context) { putString(K_SETTINGS, settings) }
    }

    fun pause(context: Context, at: Long = Clock.realNow()) {
        val active = read(context) ?: return
        if (!active.running) return
        edit(context) { putLong(K_PAUSED_AT, at) }
    }

    fun resume(context: Context, at: Long = Clock.realNow()) {
        val active = read(context) ?: return
        if (active.running) return
        // 暂停时长累加而不是清零：一天里可以暂停很多次
        val added = (at - active.pausedAt).coerceAtLeast(0L)
        edit(context) {
            putLong(K_PAUSED_TOTAL, active.pausedTotalMs + added)
            putLong(K_PAUSED_AT, 0L)
        }
    }

    fun clear(context: Context) {
        edit(context) { clear() }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 用 commit 而不是 apply：这几个值就是「进程被杀了也要留着」的东西，
     * 异步写有可能还没落盘进程就没了。
     */
    private fun edit(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        prefs(context).edit().apply(block).commit()
    }
}
