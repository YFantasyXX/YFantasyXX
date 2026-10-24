package com.yfantasyxx.data

import com.yfantasyxx.data.db.Equipment
import com.yfantasyxx.data.db.ExerciseCategory
import com.yfantasyxx.data.db.SetLog
import kotlin.math.roundToInt

/**
 * 次数区间。
 *
 * 以前目标次数是一个自由文本 `"8-12"`，要在代码里反复 `split("-")`、
 * 还要处理用户输入 `8~12`、`8 次`、`八` 这类脏数据。
 * 现在落库时就拆成 min/max 两个整数，比较和计算都不再解析字符串。
 */
data class RepRange(val min: Int, val max: Int) {
    /** 展示用：「8-12」「8」 */
    val label: String get() = if (min == max) "$min" else "$min-$max"

    /** 是否落在区间内 */
    fun contains(reps: Int): Boolean = reps in min..max

    companion object {
        val DEFAULT = RepRange(8, 12)

        /**
         * 兼容旧数据的解析：`8-12` / `8~12` / `8 - 12` / `8` / `8次` 都能认。
         * 解析不出来返回 null，由调用方决定回落到什么。
         */
        fun parse(text: String?): RepRange? {
            if (text.isNullOrBlank()) return null
            val nums = Regex("\\d+").findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
            return when {
                nums.isEmpty() -> null
                nums.size == 1 -> RepRange(nums[0], nums[0])
                else -> {
                    val a = nums[0].coerceAtLeast(1)
                    val b = nums[1].coerceAtLeast(1)
                    RepRange(minOf(a, b), maxOf(a, b))
                }
            }
        }

        /** 宽松版：解析失败时给默认区间，用于展示 */
        fun parseOrDefault(text: String?): RepRange = parse(text) ?: DEFAULT
    }
}

/**
 * 进阶策略。
 *
 * 只做两种，因为这两种覆盖了绝大多数线性计划：
 * - [LINEAR]：达到次数上限就加重，否则原重量重来。
 * - [DOUBLE]：重量不动，先把次数一格一格推到上限，再加重回到下限。
 */
enum class ProgressionMode(val value: Int, val label: String, val description: String) {
    OFF(0, "关闭", "不给建议，按上次重量练"),
    LINEAR(1, "线性加重", "全部组达到次数上限就加重，否则原重量重来"),
    DOUBLE(2, "双重进阶", "先加次数，全部到上限后再加重并回到下限"),
    ;

    companion object {
        fun from(value: Int): ProgressionMode = entries.firstOrNull { it.value == value } ?: OFF
    }
}

/** 一条进阶建议 */
data class ProgressionSuggestion(
    /** 建议重量（kg） */
    val weightKg: Float,
    /** 建议次数（取区间下限或递增后的次数） */
    val reps: Int,
    /** 相对上次是否加重 */
    val addedWeight: Boolean,
    /** 给人看的一句话 */
    val reason: String,
)

/**
 * 进阶建议计算。
 *
 * 纯函数，不碰数据库：输入「上次那次训练的所有工作组」，
 * 输出「这次该上多少」。这样规则可以单独验证，也方便以后加 5/3/1 之类的计划。
 */
object Progression {

    /**
     * 一次加多少。
     *
     * 下肢动作能承受的绝对增量大于上肢，这是训练界最稳的一条经验：
     * 深蹲 +5 kg 和卧推 +5 kg 完全不是一个难度。
     * 器械/绳索的配重片通常 5 kg 一档，也用 5。
     */
    fun incrementFor(category: String, equipment: String): Float = when {
        category == ExerciseCategory.LEGS -> 5f
        category == ExerciseCategory.CHEST || category == ExerciseCategory.SHOULDERS -> 2.5f
        equipment == Equipment.BARBELL -> 2.5f
        // 哑铃每档 1~2 kg，杠铃片最小 1.25，按 2.5 走最通用
        else -> 2.5f
    }

    /**
     * @param lastWeight 上次用的重量（模板目标重量或上次实际重量）
     * @param range 目标次数区间
     * @param lastSets 上次该动作的工作组（已完成、非热身），按组号升序
     */
    fun suggest(
        mode: ProgressionMode,
        lastWeight: Float,
        range: RepRange,
        lastSets: List<SetLog>,
        category: String,
        equipment: String,
    ): ProgressionSuggestion? {
        if (mode == ProgressionMode.OFF) return null
        val working = lastSets.filter { it.completed && !it.isWarmup && it.reps > 0 }
            .sortedBy { it.setNumber }
        // 没有历史 → 没得算，让用户自己填
        if (working.isEmpty()) return null

        val baseWeight = if (lastWeight > 0f) lastWeight else working.first().weightKg
        val allHitTop = working.all { it.reps >= range.max }
        val inc = incrementFor(category, equipment)

        return when {
            // 全部组都摸到上限 → 加重，次数回到下限
            allHitTop -> {
                val next = roundToStep(baseWeight + inc)
                ProgressionSuggestion(
                    weightKg = next,
                    reps = range.min,
                    addedWeight = true,
                    reason = "上次 ${working.size} 组全部达到 ${range.max} 次，加 ${trim(inc)} kg",
                )
            }
            // 线性：没到上限就不动重量，把次数补满
            mode == ProgressionMode.LINEAR -> ProgressionSuggestion(
                weightKg = baseWeight,
                reps = range.min,
                addedWeight = false,
                reason = "上次未全部达到 ${range.max} 次，先用 ${trim(baseWeight)} kg 重来",
            )
            // 双重进阶：重量不动，次数比上次最少的那组多 1
            else -> {
                val lowest = working.minOf { it.reps }
                val next = (lowest + 1).coerceAtMost(range.max)
                ProgressionSuggestion(
                    weightKg = baseWeight,
                    reps = next,
                    addedWeight = false,
                    reason = "重量不变，次数从 $lowest 推到 $next",
                )
            }
        }
    }

    /** 重量对齐到 2.5 kg 的整数倍：杠铃片最小 1.25，两边各一片正好 2.5 */
    fun roundToStep(kg: Float, step: Float = 2.5f): Float =
        (kg / step).roundToInt() * step

    private fun trim(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.1f", v)
}
