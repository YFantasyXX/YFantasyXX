package com.yfantasyxx.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/**
 * 业务时间的唯一入口。
 *
 * 设备时钟改不了（要 root，还会把别的 App 一起带偏），所以这里维护一个
 * **可拨动的偏移量**：`now() = 真实时钟 + offset`。
 *
 * 为什么必须分成两个：
 * - [now] 是「用户眼里的现在」——统计窗口、计划该练哪一天、历史里的今天/昨天，
 *   这些都该用它。测试时往前拨 30 天，就能看到「这批数据放了 30 天之后」的样子，
 *   不然 7 天热力图、30 天容量、9 周周桶永远只能靠真实时间流逝去等。
 * - [realNow] 是「真实流逝的时间」——休息倒计时、悬浮窗进度环、日志时间戳、
 *   文件名里的毫秒后缀必须用它。这些一旦跟着拨，倒计时会瞬间结束或者卡死。
 *
 * **写入用 realNow，读取用 now**：拨钟不会改已经落库的时间戳，只会改「怎么解释它」，
 * 这和真把手机时间改掉的表现一致。所以造数据仍然按真实时间写。
 *
 * 偏移量落 SharedPreferences：进程被杀之后还留着，否则一重启就回到今天，
 * 「造完历史拨到 30 天后看」这种用法根本验不了。
 */
object Clock {

    private const val PREFS = "dev_clock"
    private const val KEY_OFFSET = "offset_millis"

    /** 一次性拨动上限：再大就会撞上 SimpleDateFormat / SQLite 的可用范围 */
    const val MAX_ABS_OFFSET_MS = 3650L * 86_400_000L

    @Volatile
    private var offset: Long = 0L

    @Volatile
    private var installed = false

    private val _revision = MutableStateFlow(0L)

    /**
     * 每次拨钟自增。界面 collect 它来触发重算——
     * 否则 `remember { Clock.now() }` 会一直拿着拨钟前算好的那个值。
     */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** 业务时间（可能被拨动）。统计窗口、日历判断都用这个 */
    fun now(): Long = System.currentTimeMillis() + offset

    /** 真实时间（永不被拨动）。倒计时、日志、文件名用这个 */
    fun realNow(): Long = System.currentTimeMillis()

    /** 当前偏移（毫秒），正数表示往未来拨 */
    fun offsetMillis(): Long = offset

    fun isShifted(): Boolean = offset != 0L

    /** 应用启动时读回上次的偏移。只读一次 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        offset = runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_OFFSET, 0L)
        }.getOrDefault(0L).coerceIn(-MAX_ABS_OFFSET_MS, MAX_ABS_OFFSET_MS)
        _revision.value += 1
    }

    /** 设成绝对偏移；超范围会夹住，返回实际生效的值 */
    fun setOffset(context: Context, millis: Long): Long {
        val clamped = millis.coerceIn(-MAX_ABS_OFFSET_MS, MAX_ABS_OFFSET_MS)
        offset = clamped
        installed = true
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_OFFSET, clamped)
                .apply()
        }
        _revision.value += 1
        return clamped
    }

    /** 在当前偏移上再拨 [millis] */
    fun shiftBy(context: Context, millis: Long): Long = setOffset(context, offset + millis)

    fun reset(context: Context): Long = setOffset(context, 0L)

    /**
     * 拨到「指定天数的零点」而不是简单加减毫秒。
     *
     * 直接加 `n * 86_400_000` 在跨夏令时的地区会差一小时，进而让
     * 「今天 0 点」算到前一天 23:00，整张热力图错一格。
     */
    fun shiftDays(context: Context, days: Int): Long {
        val target = Calendar.getInstance().apply {
            timeInMillis = now()
            add(Calendar.DAY_OF_YEAR, days)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return setOffset(context, target - System.currentTimeMillis())
    }
}
