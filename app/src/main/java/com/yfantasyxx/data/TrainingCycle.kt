package com.yfantasyxx.data

import java.util.Calendar

/**
 * 训练计划（循环制）的当前状态。
 *
 * 三分化「练三休一」这类计划**不是按周**走的：练完 A→B→C 之后休一天，再回到 A，
 * 和「今天星期几」没有关系。按周排会产生两种明显错误：
 * - 周三练完 C，周四本来就该休，但周五在日历上还没「换周」，计划却已经回到 A；
 * - 出差断了五天回来，按周会强行接上周的星期几，把休息日插在刚好能练的那天。
 *
 * 所以这里只认两个事实：**上次练到第几个模板**、**离那天过了几个自然日**。
 * 全部状态都能从这两件事推出来，不需要存进度、也不会因为跨周而漂移。
 *
 * @param dayNames 计划内的模板名，顺序即 A→B→C 的循环顺序
 * @param templateIds 与 [dayNames] 一一对应的模板 id
 * @param restDays 每轮结束后的休息天数（练三休一 = 1）
 * @param lastDayIndex 上次练的是第几个（0 基）；null 表示这个计划还没练过
 * @param nextDayIndex 下一个该练第几个；休息期间指向下一轮的第一个
 * @param restRemaining 还要休几天（含今天）；0 表示今天就可以练
 * @param trainedToday 今天已经练过这个计划里的模板
 */
data class TrainingPlan(
    val folderId: Long,
    val name: String,
    val dayNames: List<String>,
    val templateIds: List<Long>,
    val restDays: Int,
    val lastDayIndex: Int?,
    val nextDayIndex: Int,
    val restRemaining: Int,
    val trainedToday: Boolean,
) {
    val dayCount: Int get() = dayNames.size

    val nextTemplateId: Long? get() = templateIds.getOrNull(nextDayIndex)

    val nextName: String get() = dayNames.getOrNull(nextDayIndex).orEmpty()

    /** 「练 3 休 1」；不休息时只显示循环长度 */
    val rhythmLabel: String
        get() = if (restDays > 0) "练 $dayCount 休 $restDays" else "$dayCount 天循环"

    val resting: Boolean get() = restRemaining > 0
}

object TrainingCycle {

    /** 休息天数上限：设 0 就是老行为（不停歇地循环），最多给到一周 */
    const val MAX_REST_DAYS = 7

    /**
     * 按历史推出当前该练哪个。
     *
     * @param lastTemplateId 最近一次练的模板；不在本计划里时按「没练过」处理
     * @param lastStartTime 那次的开始时间；用来算「过了几天」
     * @param now 当前时间，测试时可注入
     */
    fun resolve(
        folderId: Long,
        name: String,
        templateIds: List<Long>,
        dayNames: List<String>,
        restDays: Int,
        lastTemplateId: Long?,
        lastStartTime: Long?,
        now: Long = System.currentTimeMillis(),
    ): TrainingPlan {
        val count = templateIds.size
        val lastIndex = lastTemplateId?.let { id ->
            templateIds.indexOf(id).takeIf { it >= 0 }
        }

        if (count == 0 || lastIndex == null || lastStartTime == null) {
            // 还没练过：从第一个开始，也没有「上次」可参照
            return TrainingPlan(
                folderId = folderId,
                name = name,
                dayNames = dayNames,
                templateIds = templateIds,
                restDays = restDays,
                lastDayIndex = null,
                nextDayIndex = 0,
                restRemaining = 0,
                trainedToday = false,
            )
        }

        val daysSince = daysBetween(lastStartTime, now)
        val atCycleEnd = lastIndex == count - 1

        // 只有在「这一轮刚练完」且确实设了休息日时，才可能出现休息期。
        // 练 A、练 B 之间不插休息——想插的人把「休」当成一个模板排在中间即可。
        val resting = atCycleEnd && restDays > 0 && daysSince in 1..restDays
        // 含今天：练完 C 当天不算（当天已经练了），第二天剩 restDays 天。
        val restRemaining = if (resting) restDays + 1 - daysSince else 0

        return TrainingPlan(
            folderId = folderId,
            name = name,
            dayNames = dayNames,
            templateIds = templateIds,
            restDays = restDays,
            lastDayIndex = lastIndex,
            nextDayIndex = if (atCycleEnd) 0 else lastIndex + 1,
            restRemaining = restRemaining,
            trainedToday = daysSince == 0,
        )
    }

    /**
     * 两个时间戳之间差几个自然日（按本地零点算，不看时分秒）。
     *
     * 用四舍五入而不是整除：跨夏令时的地区一天可能是 23 或 25 小时，
     * 整除会把「昨天」算成 0 天。
     */
    fun daysBetween(from: Long, to: Long): Int =
        Math.round((startOfDay(to) - startOfDay(from)) / 86_400_000.0).toInt()

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
