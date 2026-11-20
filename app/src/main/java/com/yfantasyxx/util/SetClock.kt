package com.yfantasyxx.util

import android.content.Context

/**
 * 组计时锚点：当前这一组从哪一刻开始算。
 *
 * 「这一组做了多久」= 打勾时刻 − 锚点，而锚点是在**上一次休息结束**时打的，
 * 所以得到的就是「两次休息中间隔了多久」，不包含休息本身。
 *
 * 打勾的瞬间也会把锚点推到「现在」：超级组交替、或者休息没等完就直接做下一组时，
 * 上一组结束就是下一组的起点，这时没有「休息结束」这个事件可依赖。
 *
 * 存到 SharedPreferences 而不是只放内存：训练中进程被系统杀掉再回来，
 * 前面几组的计时还能接上。键里带 sessionId，换了训练自然失效，
 * 不会把上一场的时间带到这一场。
 */
object SetClock {

    private const val PREFS = "set_clock"
    private const val KEY_SESSION = "session_id"
    private const val KEY_ANCHOR = "anchor_at"

    /**
     * 单组最长按 4 小时算。
     *
     * 手机放一边、跨了午觉再回来时，(now - anchor) 可以是十几个小时，
     * 直接显示出来只会让人以为计时坏了；夹住至少还是个「很长」。
     */
    private const val MAX_MS = 4 * 60 * 60 * 1000L

    /** 取锚点；没有、或者不属于这个训练时返回 null */
    fun anchorAt(context: Context, sessionId: Long?): Long? {
        if (sessionId == null) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_SESSION, -1L) != sessionId) return null
        return prefs.getLong(KEY_ANCHOR, 0L).takeIf { it > 0L }
    }

    fun mark(context: Context, sessionId: Long?, at: Long = System.currentTimeMillis()) {
        if (sessionId == null) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SESSION, sessionId)
            .putLong(KEY_ANCHOR, at)
            .apply()
    }

    /** 这一组已经做了多久；没有锚点返回 0（界面据此不显示） */
    fun elapsed(context: Context, sessionId: Long?, now: Long = System.currentTimeMillis()): Long {
        val anchor = anchorAt(context, sessionId) ?: return 0L
        return (now - anchor).coerceIn(0L, MAX_MS)
    }

    /** 训练结束或放弃时清掉，别留给下一场 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
