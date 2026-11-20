package com.yfantasyxx.ui.cardio

import com.yfantasyxx.util.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 秒表：不足一小时 `12:34`，满一小时 `1:02:03`。
 *
 * 时位不补零、分秒补零 —— 和运动手表一致：`1:05:03` 比 `01:05:03` 好读，
 * 但 `12:5` 会让人以为那是 12 分 5 秒还是 12.5 分。
 */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

/** 中文时长：`不到 1 分钟` / `42 分钟` / `1 小时 05 分` */
fun formatDurationCn(ms: Long): String {
    if (ms <= 0L) return "0 分钟"
    // 不足半分钟说「不到 1 分钟」，超过就按最近的一分钟算：
    // 直接截断会把 30 分 42 秒说成「30 分钟」，一周累下来像少练了
    if (ms < 30_000L) return "不到 1 分钟"
    val minutes = Math.round(ms / 60_000.0).toInt().coerceAtLeast(1)
    return if (minutes < 60) {
        "$minutes 分钟"
    } else {
        val h = minutes / 60
        val m = minutes % 60
        if (m == 0) "$h 小时" else "$h 小时 $m 分"
    }
}

/**
 * 配速（min/km）。距离太短或时长缺失时返回 null —— 界面直接不画这一格，
 * 而不是画一个 `--'--"` 占位：没记距离是常态，天天看着占位符很吵。
 */
fun formatPace(durationMs: Long, distanceKm: Float): String? {
    if (distanceKm <= 0.05f || durationMs <= 0L) return null
    val secPerKm = (durationMs / 1000.0) / distanceKm
    // 超过 1 小时/公里基本是「忘了按暂停」，画出来没有意义
    if (secPerKm > 3600) return null
    val total = secPerKm.toInt()
    return String.format(Locale.US, "%d'%02d\"", total / 60, total % 60)
}

/** 时速（km/h） */
fun formatSpeed(durationMs: Long, distanceKm: Float): String? {
    if (distanceKm <= 0.05f || durationMs <= 0L) return null
    val kmh = distanceKm / (durationMs / 3_600_000.0)
    if (kmh > 120) return null
    return String.format(Locale.US, "%.1f", kmh)
}

/** 日期：今天 / 昨天 / 前天 / 6月3日 / 2025年6月3日（跨年才带年份） */
fun formatCardioDay(ts: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
    // 用业务时间判断「今天」：测试工具拨过钟之后，口径要和别处一致
    val today = Instant.ofEpochMilli(Clock.now()).atZone(zone).toLocalDate()
    return when {
        date == today -> "今天"
        date == today.minusDays(1) -> "昨天"
        date == today.minusDays(2) -> "前天"
        date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
        else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    }
}

/** 时刻 HH:mm */
fun formatCardioTime(ts: Long): String =
    String.format(
        Locale.US,
        "%02d:%02d",
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).hour,
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).minute,
    )
