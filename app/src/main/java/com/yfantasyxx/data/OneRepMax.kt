package com.yfantasyxx.data

import kotlin.math.exp
import kotlin.math.pow

/**
 * 估算 1RM（单次能举起的最大重量）。
 *
 * 为什么不是一条公式：
 * 每条经验公式只在低次数区间可信。Brzycki 的分母是 (37 - 次数)，到 30 次以上直接发散；
 * Epley 在 20 次以上明显高估；Lombardi 在 1~3 次又偏保守。它们的分歧随次数放大，
 * 所以这里做的是「多条公式按各自可靠度加权融合」，并且整体权重随次数上升而衰减——
 * 次数越高，估出来的数字越不可信，就该越谨慎。
 *
 * 超过 [MAX_RELIABLE_REPS] 次直接返回 null。与其印一个编出来的数字，
 * 不如承认这个次数区间算不出可信的 1RM。
 */
object OneRepMax {

    /** 超过这个次数，任何公式的估算都不可信 */
    const val MAX_RELIABLE_REPS = 12

    /**
     * @return 估算 1RM（kg）；重量为 0（自重）、次数非法、或次数 > [MAX_RELIABLE_REPS] 时返回 null
     */
    fun estimate(weightKg: Float, reps: Int): Float? {
        if (weightKg <= 0f || reps <= 0 || reps > MAX_RELIABLE_REPS) return null
        // 1 次就是真 1RM，不需要估；所有公式在 r=1 时都会给出略大于实际重量的值
        if (reps == 1) return weightKg

        var weighted = 0.0
        var weightSum = 0.0
        formulas.forEach { formula ->
            val value = formula.compute(weightKg.toDouble(), reps)
            if (!value.isFinite() || value <= 0.0) return@forEach
            val w = formula.reliability * confidence(reps)
            weighted += value * w
            weightSum += w
        }
        if (weightSum <= 0.0) return null
        return (weighted / weightSum).toFloat()
    }

    /**
     * 整体可信度随次数衰减：1 次 ≈ 0.98，5 次 ≈ 0.72，10 次 ≈ 0.39，12 次 ≈ 0.31。
     * 衰减不改变公式之间的相对权重，只影响「这一组值不值得当真」。
     */
    private fun confidence(reps: Int): Double =
        1.0 / (1.0 + (reps / 8.0).pow(2))

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
