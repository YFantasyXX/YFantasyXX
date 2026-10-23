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
        val total = prefs.getInt(KEY_TOTAL, 0)
        val remaining = ((end - System.currentTimeMillis()) / 1000).toInt()
        return Snapshot(
            running = remaining > 0,
            remainingSeconds = remaining.coerceAtLeast(0),
            totalSeconds = total,
            endTime = end,
            label = prefs.getString(KEY_LABEL, null),
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
