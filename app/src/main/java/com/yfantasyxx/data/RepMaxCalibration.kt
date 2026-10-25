package com.yfantasyxx.data

import com.yfantasyxx.data.db.RepMaxRow
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * 「某次数下这组重量占 1RM 的比例」的来源。
 *
 * 两个实现：群体默认的 [LiftPattern] 表，和用个人历史校准过的 [PatternCalibration]。
 * [OneRepMax] 只认这个接口，不关心曲线从哪来。
 */
interface RepMaxCurve {
    /** 做 [reps] 次时的 %1RM；返回 null 表示这条曲线不覆盖该次数，调用方自行兜底 */
    fun fractionAt(reps: Int): Float?
}

/** 某动作在某次数下举起过的最重重量（同次数练过多次时只留最重的那次） */
data class RepMaxSample(
    val reps: Int,
    val weightKg: Float,
)

/**
 * 一个分档的校准结果。
 *
 * 用**幂变换**而不是重画一条直线：群体表本身是凸的（前几次掉得快、后面变平），
 * 换成线性会两头都偏。`table(r)^γ` 保留原形状，只调「掉得多快」：
 * γ = 1 就是群体均值，γ > 1 掉得更快（硬拉型选手），γ < 1 更抗次数（耐力型）。
 * 且 `table(1) = 1`，指数变换不改变 1RM 的定义锚点。
 */
data class PatternCalibration(
    val pattern: LiftPattern,
    val gamma: Float,
    /** 参与校准的动作数（同一档下有多少个动作攒够了样本） */
    val actionCount: Int,
    /** 参与校准的总组数，用于说明样本量 */
    val setCount: Int,
) : RepMaxCurve {

    override fun fractionAt(reps: Int): Float? {
        val base = pattern.fractionAt(reps) ?: return null
        if (gamma == 1f) return base
        return base.pow(gamma)
    }

    /** 是否真的用上了个人数据 */
    val isPersonal: Boolean get() = actionCount > 0
}

/** 给界面用的一句人话：这条曲线是怎么来的 */
data class CurveHint(
    val curve: RepMaxCurve,
    val text: String,
)

/**
 * 用个人训练历史校准 rep-max 曲线。
 *
 * **为什么不能直接拟合重量**：一个人卧推 100 kg × 10、推肩 60 kg × 10，
 * 绝对重量差一倍，放一起回归只会拟合出「哪个动作练得多」。
 * 能跨动作合并的是**无量纲的掉速**，所以每个动作各自拟合 γ，再按档取中位数。
 *
 * **为什么单个动作的样本往往不够**：大多数人一次训练里只做 1~2 个次数档
 * （比如 4 组 8 次），凑不出斜率。这就是必须按档聚合的原因——
 * 同一档下「卧推 8 次」「上斜卧推 6 次」「推肩 10 次」拼起来才有跨度。
 *
 * **为什么向群体默认收缩**：攒到一两个动作时，个人 γ 很容易被某次状态好的
 * 训练带偏。α 随动作数增长，攒够 [FULL_TRUST_ACTIONS] 个才完全采信。
 */
object RepMaxCalibration {

    /** 至少要两个不同次数档，单点连不成斜率 */
    const val MIN_POINTS = 2

    /** 两个点挨太近（8 次和 9 次）时斜率几乎全是噪声，要求次数跨度 */
    const val MIN_SPAN = 3

    /**
     * 指数变换的可行域。
     * γ = 1.55 意味着 10 次只剩 `table(10)^1.55`——对硬拉类是 0.51，
     * 再往外基本是「磅和公斤混着记」这类数据问题，夹住而不是采信。
     */
    const val GAMMA_MIN = 0.65f
    const val GAMMA_MAX = 1.55f

    /** 攒够这么多个动作才完全信任个人数据 */
    const val FULL_TRUST_ACTIONS = 3

    /**
     * 单个动作的 γ。
     *
     * 模型：`w_i = 1RM × table(r_i)^γ`，两边取对数后是直线
     * `ln w_i = ln 1RM + γ · ln table(r_i)`，对 `(ln table, ln w)` 做最小二乘，
     * 斜率就是 γ。
     *
     * @return γ；样本不足、跨度不够、或数据退化（同一次数只有一组）时返回 null
     */
    fun fitAction(pattern: LiftPattern, points: List<RepMaxSample>): Float? {
        if (pattern == LiftPattern.UNKNOWN) return null
        val usable = points.filter { it.reps in 1..OneRepMax.MAX_RELIABLE_REPS && it.weightKg > 0f }
        if (usable.size < MIN_POINTS) return null
        if (usable.maxOf { it.reps } - usable.minOf { it.reps } < MIN_SPAN) return null

        var n = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0
        usable.forEach { point ->
            val fraction = pattern.fractionAt(point.reps) ?: return@forEach
            if (fraction <= 0f || fraction > 1f) return@forEach
            val x = ln(fraction.toDouble())
            val y = ln(point.weightKg.toDouble())
            n += 1
            sumX += x
            sumY += y
            sumXY += x * y
            sumXX += x * x
        }
        if (n < MIN_POINTS) return null

        // 所有点落在同一个次数档时 x 没有方差，斜率为 0/0
        val denominator = n * sumXX - sumX * sumX
        if (abs(denominator) < 1e-9) return null

        val gamma = (n * sumXY - sumX * sumY) / denominator
        if (!gamma.isFinite()) return null
        return gamma.toFloat().coerceIn(GAMMA_MIN, GAMMA_MAX)
    }

    /**
     * 全量校准：库里所有已完成工作组 → 每档一条曲线。
     *
     * 只返回确实攒出样本的档位；查不到的档由调用方回落到 [LiftPattern] 原始表。
     */
    fun calibrate(rows: List<RepMaxRow>): Map<Int, PatternCalibration> {
        val perPattern = HashMap<Int, MutableList<Pair<Float, Int>>>()

        rows.groupBy { it.exerciseId }.forEach { (_, list) ->
            val pattern = LiftPattern.fromValue(list.first().pattern)
            if (pattern == LiftPattern.UNKNOWN) return@forEach

            // 同一动作同一次数练过很多次，只留最重的那次；
            // 拿平均值会把「某次状态差」也算进曲线
            val samples = list.groupBy { it.reps }.mapNotNull { (reps, group) ->
                val heaviest = group.maxByOrNull { it.weightKg } ?: return@mapNotNull null
                RepMaxSample(reps, heaviest.weightKg)
            }
            val gamma = fitAction(pattern, samples) ?: return@forEach
            val sets = list.sumOf { it.setCount }
            perPattern.getOrPut(pattern.value) { mutableListOf() } += gamma to sets
        }

        return perPattern.mapValues { (value, fits) ->
            val pattern = LiftPattern.fromValue(value)
            val personal = weightedMedian(fits)
            // 向 1 收缩：1 就是群体默认表本身
            val alpha = min(1f, fits.size / FULL_TRUST_ACTIONS.toFloat())
            PatternCalibration(
                pattern = pattern,
                gamma = 1f + alpha * (personal - 1f),
                actionCount = fits.size,
                setCount = fits.sumOf { it.second },
            )
        }
    }

    /**
     * 组数加权中位数。
     *
     * 用中位数不用均值：某个动作如果换过器械、重量单位混记，γ 会跑飞，
     * 中位数不会被单个离群动作拖走。权重用组数——练了 60 组的动作
     * 比只做过 3 组的更有发言权。
     */
    private fun weightedMedian(fits: List<Pair<Float, Int>>): Float {
        if (fits.isEmpty()) return 1f
        val sorted = fits.sortedBy { it.first }
        val total = sorted.sumOf { it.second }
        if (total <= 0) return sorted[sorted.size / 2].first
        var acc = 0
        sorted.forEach { (gamma, weight) ->
            acc += weight
            if (acc * 2 >= total) return gamma
        }
        return sorted.last().first
    }
}
