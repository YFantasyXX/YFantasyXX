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
 * 进度存在 [com.yfantasyxx.data.db.TemplateFolder.cycleNextTemplateId] 里（存模板 id），
 * 而不是每次按顺序现推：现推的话，把「腿日」拖到第一位会让「上次练到第 3 个」失效，
 * 休息日就凭空消失了。存 id 则改顺序、加减模板都不动进度，历史训练的时间也一个字不改。
 *
 * @param dayNames 计划内的模板名，顺序即 A→B→C 的循环顺序
 * @param templateIds 与 [dayNames] 一一对应的模板 id
 * @param restDays 每轮结束后的休息天数（练三休一 = 1）
 * @param lastDayIndex 上次练的是第几个（0 基，仅用于界面标记）；null 表示这个计划还没练过
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
     * 由「下一个该练哪个 + 本轮何时练完」算出界面要的整套状态。
     *
     * @param nextDayIndex 游标指向的位置（由仓库层决定：游标优先，没游标才按历史推）
     * @param cycleEndedAt 本轮最后一个模板的完成时刻；不在轮末时为 null
     * @param lastTrainedAt 本计划最近一次训练时刻，用来判断「今天已经练过」
     * @param now 当前时间，测试时可注入
     */
    fun state(
        folderId: Long,
        name: String,
        templateIds: List<Long>,
        dayNames: List<String>,
        restDays: Int,
        nextDayIndex: Int,
        cycleEndedAt: Long?,
        lastDayIndex: Int?,
        lastTrainedAt: Long?,
        now: Long = System.currentTimeMillis(),
    ): TrainingPlan {
        // 含今天：练完最后一天当天不算休息（那天已经练了），第二天才算第 1 天
        val daysSinceEnd = cycleEndedAt?.let { daysBetween(it, now) } ?: -1
        val resting = restDays > 0 && daysSinceEnd in 1..restDays
        val restRemaining = if (resting) restDays + 1 - daysSinceEnd else 0

        return TrainingPlan(
            folderId = folderId,
            name = name,
            dayNames = dayNames,
            templateIds = templateIds,
            restDays = restDays,
            lastDayIndex = lastDayIndex,
            nextDayIndex = nextDayIndex.coerceIn(0, (templateIds.size - 1).coerceAtLeast(0)),
            restRemaining = restRemaining,
            trainedToday = lastTrainedAt != null && daysBetween(lastTrainedAt, now) == 0,
        )
    }

    /**
     * 没有游标时（首次升级 / 上次练的模板被删 / 用户手动重算）按历史推一次。
     *
     * 只在这几种一次性场合用；正常推进由仓库层在结束训练时写游标，
     * 所以用户之后改顺序、加减模板都不会再影响已经记下的进度。
     *
     * @return 下一个该练的位置；0 表示从第一个开始
     */
    fun deriveNextIndex(templateIds: List<Long>, lastTrainedTemplateId: Long?): Int {
        if (templateIds.isEmpty()) return 0
        val lastIndex = lastTrainedTemplateId?.let { templateIds.indexOf(it) } ?: -1
        return if (lastIndex < 0 || lastIndex == templateIds.lastIndex) 0 else lastIndex + 1
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
