package com.yfantasyxx.data

import kotlin.math.exp
import kotlin.math.pow

/**
 * 估算 1RM（单次能举起的最大重量）。
 *
 * 两条路径：
 * 1. **分档优先**——知道动作属于哪一类（[LiftPattern] 不是 UNKNOWN）时查表，
 *    `1RM = 这组重量 / 该次数占 1RM 的比例`。这是主路径：
 *    同一次数下硬拉和卧推离 1RM 的距离差得很远，只有查表才分得开；
 * 2. **融合兜底**——分档不明（有氧、负重行走、用户自建的怪名字）时，
 *    七条经验公式按各自固有可靠度加权平均。
 *
 * 为什么兜底不是一条公式：每条经验公式只在低次数区间可信。Brzycki 的分母是
 * (37 - 次数)，到 30 次以上直接发散；Epley 在 20 次以上明显高估；
 * Lombardi 在 1~3 次又偏保守。它们的分歧随次数放大，融合比单条稳。
 *
 * 超过 [MAX_RELIABLE_REPS] 次直接返回 null。与其印一个编出来的数字，
 * 不如承认这个次数区间算不出可信的 1RM。
 */
object OneRepMax {

    /** 超过这个次数，任何公式的估算都不可信 */
    const val MAX_RELIABLE_REPS = 12

    /**
     * @param pattern 动作的 rep-max 分档；[LiftPattern.UNKNOWN] 时回落到多公式融合
     * @return 估算 1RM（kg）；重量为 0（自重）、次数非法、或次数 > [MAX_RELIABLE_REPS] 时返回 null
     */
    fun estimate(
        weightKg: Float,
        reps: Int,
        pattern: LiftPattern = LiftPattern.UNKNOWN,
    ): Float? {
        if (weightKg <= 0f || reps <= 0 || reps > MAX_RELIABLE_REPS) return null
        // 1 次就是真 1RM，不需要估；所有公式在 r=1 时都会给出略大于实际重量的值
        if (reps == 1) return weightKg
        pattern.fractionAt(reps)?.let { fraction ->
            if (fraction > 0f) return weightKg / fraction
        }
        return fused(weightKg, reps)
    }

    /**
     * 七条公式的等可靠度加权平均。
     *
     * 权重只有「公式固有可靠度」一项，**没有**次数衰减项。
     * 旧版本这里乘过一个 `1 / (1 + (r/8)²)`，本意是「次数越高越保守」，
     * 但那个因子在加权平均里分子分母同时出现、被完整约掉——写上去等于没写。
     * 也不能改成「向这组重量收缩」：100 kg × 10 对应 1RM 约 143 kg，
     * 收缩系数 0.39 会把它算成 117 kg，比不估还错。
     * 高次数的不确定性交给两件事：[MAX_RELIABLE_REPS] 上限，
     * 以及分档表本身（高次数段的比例是查表得到的，不是外推）。
     */
    private fun fused(weightKg: Float, reps: Int): Float? {
        var weighted = 0.0
        var weightSum = 0.0
        formulas.forEach { formula ->
            val value = formula.compute(weightKg.toDouble(), reps)
            if (!value.isFinite() || value <= 0.0) return@forEach
            weighted += value * formula.reliability
            weightSum += formula.reliability
        }
        if (weightSum <= 0.0) return null
        return (weighted / weightSum).toFloat()
    }

    private class Formula(
        /** 该公式的固有可靠度（文献中相对更准的给更高） */
        val reliability: Double,
        val compute: (weight: Double, reps: Int) -> Double,
    )

    private val formulas = listOf(
        // Epley
        Formula(0.92) { w, r -> w * (1.0 + r / 30.0) },
        // Brzycki
        Formula(0.95) { w, r -> w * 36.0 / (37.0 - r) },
        // Lombardi
        Formula(0.80) { w, r -> w * r.toDouble().pow(0.10) },
        // O'Conner
        Formula(0.78) { w, r -> w * (1.0 + r / 40.0) },
        // Wathan
        Formula(0.90) { w, r -> w * 100.0 / (48.8 + 53.8 * exp(-0.075 * r)) },
        // Lander
        Formula(0.88) { w, r -> w * 100.0 / (101.3 - 2.67123 * r) },
        // Mayhew
        Formula(0.86) { w, r -> w * 100.0 / (52.2 + 41.9 * exp(-0.055 * r)) },
    )
}
