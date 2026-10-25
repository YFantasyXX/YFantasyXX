package com.yfantasyxx.data

import com.yfantasyxx.data.db.PlanDay
import com.yfantasyxx.data.db.PlanEntity
import java.util.Calendar

/** 计划里的一天：模板或休息 */
data class PlanDaySlot(val templateId: Long?, val name: String) {
    val isRest: Boolean get() = templateId == null
}

/**
 * 训练计划当前的运行状态。
 *
 * 计划是一串「天」（见 [com.yfantasyxx.data.db.PlanDay]），进度只存一个下标 + 这一天从哪天开始：
 * - **训练日**：一直停在这里，练完才往后走。断练一周回来还是该练这一天，不会跳过；
 * - **休息日**：占满一整个自然日，到点自动流过。所以「练三休一」就是 4 天一圈，
 *   练完第 3 天之后的第二天自动变成休息日，再下一天回到第 1 天。
 *
 * 不按周排是有意的：按周排会出现「周三练完 C，周五还没换周却已经回到 A」这种错位。
 *
 * @param nextIndex 今天该看第几个位置（已经跳过了过完的休息日）
 * @param restRemaining 连续休息还剩几天（含今天）；0 表示今天就是训练日
 */
data class TrainingPlan(
    val planId: Long,
    val name: String,
    val slots: List<PlanDaySlot>,
    val nextIndex: Int,
    val restRemaining: Int,
    val trainedToday: Boolean,
) {
    val dayCount: Int get() = slots.size

    val dayNames: List<String> get() = slots.map { it.name }

    fun isRestAt(index: Int): Boolean = slots.getOrNull(index)?.isRest ?: true

    /** 今天该练的模板；休息日为 null */
    val nextTemplateId: Long? get() = slots.getOrNull(nextIndex)?.templateId

    val nextName: String get() = slots.getOrNull(nextIndex)?.name.orEmpty()

    val resting: Boolean get() = restRemaining > 0

    /** 休息结束后第一个训练日的位置；今天就是训练日时等于 [nextIndex] */
    val upNextIndex: Int
        get() {
            if (slots.isEmpty()) return 0
            var i = nextIndex
            var guard = 0
            while (guard++ < slots.size && slots[i].isRest) i = (i + 1) % slots.size
            return i
        }

    /** 「开始」按钮该练的模板：休息日时是休息后的第一个训练日 */
    val upNextTemplateId: Long? get() = slots.getOrNull(upNextIndex)?.templateId

    val upNextName: String get() = slots.getOrNull(upNextIndex)?.name.orEmpty()

    val trainingDayCount: Int get() = slots.count { !it.isRest }

    val restDayCount: Int get() = slots.count { it.isRest }

    /** 「练 3 休 1」；没有休息日时只说循环长度 */
    val rhythmLabel: String
        get() = if (restDayCount > 0) {
            "练 $trainingDayCount 休 $restDayCount"
        } else {
            "$dayCount 天循环"
        }
}

object TrainingCycle {

    /** 编辑计划时的天数上限：一个月内，够排 5 分化 + 休息 */
    const val MAX_DAYS = 21

    private const val DAY_MS = 86_400_000L

    /**
     * 由计划本身的游标算出今天该看哪一天。
     *
     * @param nextIndex 存下来的位置
     * @param dayStartedAt 这一天从哪个本地零点开始（0 表示没记过，按今天算）
     */
    fun currentIndex(
        nextIndex: Int,
        dayStartedAt: Long,
        slots: List<PlanDaySlot>,
        now: Long = System.currentTimeMillis(),
    ): Int {
        if (slots.isEmpty()) return 0
        var index = nextIndex.coerceIn(0, slots.lastIndex)
        var startedAt = if (dayStartedAt > 0L) dayStartedAt else startOfDay(now)
        // 休息日一过就往后走一天，连着几个休息日就连着走；训练日停住
        var guard = 0
        while (guard++ <= slots.size * 2) {
            if (!slots[index].isRest) break
            val restEndsAt = startOfNextDay(startedAt)
            if (now < restEndsAt) break
            index = (index + 1) % slots.size
            startedAt = restEndsAt
        }
        return index
    }

    /** 把存下来的计划 + 天 + 模板名解析成界面要的整套状态 */
    fun resolve(
        plan: PlanEntity,
        days: List<PlanDay>,
        templateNames: Map<Long, String>,
        lastTrainedAt: Long?,
        now: Long = System.currentTimeMillis(),
    ): TrainingPlan? {
        val sorted = days.sortedBy { it.position }
        if (sorted.isEmpty()) return null
        val slots = sorted.map { day ->
            PlanDaySlot(
                templateId = day.templateId,
                name = day.templateId?.let { templateNames[it] } ?: "休息",
            )
        }
        val index = currentIndex(plan.nextIndex, plan.dayStartedAt, slots, now)
        var restLeft = 0
        if (slots[index].isRest) {
            var i = index
            var guard = 0
            while (guard++ < slots.size && slots[i].isRest) {
                restLeft++
                i = (i + 1) % slots.size
            }
        }
        return TrainingPlan(
            planId = plan.id,
            name = plan.name,
            slots = slots,
            nextIndex = index,
            restRemaining = restLeft,
            trainedToday = lastTrainedAt != null && daysBetween(lastTrainedAt, now) == 0,
        )
    }

    /** 两个时间戳之间差几个自然日（按本地零点算，不看时分秒） */
    fun daysBetween(from: Long, to: Long): Int =
        Math.round((startOfDay(to) - startOfDay(from)) / DAY_MS.toDouble()).toInt()

    fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 下一个自然日的零点；跨夏令时用 Calendar 加一天，别用 +24h */
    fun startOfNextDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = startOfDay(millis)
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
}
