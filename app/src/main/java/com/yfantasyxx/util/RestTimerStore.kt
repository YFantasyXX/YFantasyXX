package com.yfantasyxx.util

import android.content.Context

/**
 * 组间休息计时的持久化状态。
 * 用结束时间戳（wall clock）保存，退出 App 后仍可准确恢复剩余时间。
 */
object RestTimerStore {

    private const val PREFS = "rest_timer_state"
    private const val KEY_END = "end_time"
    private const val KEY_TOTAL = "total_seconds"
    private const val KEY_LABEL = "label"

    data class Snapshot(
        val running: Boolean,
        val remainingSeconds: Int,
        val totalSeconds: Int,
        val endTime: Long,
        val label: String? = null,
    )

    fun save(context: Context, endTime: Long, totalSeconds: Int, label: String? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_END, endTime)
            .putInt(KEY_TOTAL, totalSeconds)
            .putString(KEY_LABEL, label)
            .apply()
    }

    fun snapshot(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val end = prefs.getLong(KEY_END, 0L)
        val total = prefs.getInt(KEY_TOTAL, 0).coerceAtLeast(0)
        val raw = ((end - System.currentTimeMillis()) / 1000).toInt()
        // 系统时间被往回调（手动改时间、NTP 校时）时 (end - now) 会超过总时长。
        // 剩余秒数必须夹在 [0, total] 内：调用方按 remaining/total 算进度，
        // 超出去就是「已用时间为负」，进度环和通知都画不对。
        // total 为 0 表示没存过（clear 之后），此时不夹上界，保持原语义。
        val remaining = if (total > 0) raw.coerceIn(0, total) else raw.coerceAtLeast(0)
        return Snapshot(
            running = remaining > 0,
            remainingSeconds = remaining,
            totalSeconds = total,
            endTime = end,
            label = prefs.getString(KEY_LABEL, null),
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
