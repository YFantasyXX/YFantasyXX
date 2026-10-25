package com.yfantasyxx.data

import com.yfantasyxx.data.db.Equipment
import com.yfantasyxx.data.db.ExerciseCategory

/**
 * 动作的 rep-max 曲线分档。
 *
 * 为什么需要分档：把「这组重量 × 系数」换算成 1RM 时，系数**不是只由次数决定**的。
 * 同一档次数下，硬拉离 1RM 比卧推更远——握力、腰背等长负担、离地那一下的技术崩点，
 * 都让它在高次数先掉下来。用一条曲线套所有动作，等于假设「人做 10 次时永远剩 76%」，
 * 硬拉会被低估十几个百分点（100 kg × 10 估成 131 kg，实测区间在 154 kg 附近）。
 *
 * 所以每一档给一张 `次数 → 占 1RM 的比例` 表，1RM = 这组重量 / 比例。
 * 表里的数字是常用 RPE / %1RM 对照表与 Richens & Cleather(2014) 对深蹲、卧推、硬拉
 * rep-max 关系的量级，**是近似的群体均值**。
 *
 * 个体差异（有人天生卧推 rep-max 更平）由 [RepMaxCalibration] 用同一个人的历史数据
 * 做幂变换校准，得到 [PatternCalibration]，它是本表的「个人版」。
 *
 * [UNKNOWN] 不查表，回落到七公式融合（见 [OneRepMax]）。有氧、计时类、负重行走
 * 这类动作本身没有有意义的 1RM，也归到 UNKNOWN。
 */
enum class LiftPattern(
    val value: Int,
    val label: String,
    /** 下标 = 次数 - 1，值 = 该次数能举起的重量占 1RM 的比例 */
    private val table: FloatArray,
) : RepMaxCurve {
    UNKNOWN(0, "通用", floatArrayOf()),

    /** 髋主导：硬拉、罗马尼亚硬拉、臀推、山羊挺身、奥举。掉得最快 */
    HINGE(
        1, "硬拉类",
        floatArrayOf(1f, .94f, .90f, .86f, .82f, .79f, .75f, .72f, .68f, .65f, .61f, .57f),
    ),

    /** 蹲主导：深蹲、腿举、哈克、箭步蹲 */
    SQUAT(
        2, "深蹲类",
        floatArrayOf(1f, .95f, .92f, .89f, .86f, .83f, .80f, .78f, .75f, .72f, .68f, .65f),
    ),

    /** 推主导：卧推、肩推、俯卧撑、双杠臂屈伸 */
    PRESS(
        3, "推类",
        floatArrayOf(1f, .95f, .92f, .89f, .85f, .82f, .79f, .76f, .73f, .70f, .66f, .63f),
    ),

    /** 拉主导：划船、引体、下拉 */
    PULL(
        4, "拉类",
        floatArrayOf(1f, .95f, .91f, .88f, .84f, .81f, .78f, .75f, .72f, .69f, .65f, .62f),
    ),

    /** 单关节：弯举、侧平举、腿屈伸、提踵。稳定肌参与少，曲线最平 */
    ISOLATION(
        5, "孤立类",
        floatArrayOf(1f, .96f, .94f, .91f, .88f, .86f, .83f, .80f, .78f, .75f, .72f, .70f),
    );

    /**
     * 做 [reps] 次时，这组重量占 1RM 的比例。
     *
     * [UNKNOWN] 返回 null，调用方据此回落到七公式融合。
     * 表只到 12 次，超出的次数由 [OneRepMax.MAX_RELIABLE_REPS] 在调用方拦掉。
     */
    override fun fractionAt(reps: Int): Float? {
        if (table.isEmpty() || reps <= 0) return null
        if (reps <= table.size) return table[reps - 1]
        return table.last()
    }

    companion object {
        fun fromValue(value: Int): LiftPattern = entries.firstOrNull { it.value == value } ?: UNKNOWN

        /**
         * 从动作名 + 分类 + 器械推断分档。
         *
         * 为什么不只看分类：`腿 + 杠铃` 里既有深蹲也有硬拉，两者的曲线差得最远；
         * `背 + 杠铃` 里既有划船也有硬拉。分类只能决定兜底档，具体档位必须看名字。
         *
         * 判断顺序是有讲究的，几条容易踩的：
         * - 「罗马尼亚硬拉」在腿分类里，但它是髋主导，必须比「蹲」先判；
         * - 「双杠臂屈伸」是复合推，「哑铃颈后臂屈伸」是孤立，不能一把抓「臂屈伸」；
         * - 「窄距卧推」挂在手臂分类下，但它是推类；
         * - 「腿弯举」和「杠铃弯举」都归孤立，这一条两边通用。
         */
        fun infer(
            name: String,
            category: String,
            equipment: String = Equipment.OTHER,
        ): LiftPattern {
            // 1. 本来就没有 1RM 概念的动作
            if (category == ExerciseCategory.CARDIO) return UNKNOWN
            if (name.containsAny("平板", "波比", "起立", "雪橇", "行走", "支撑", "跳绳", "开合跳", "战绳")) {
                return UNKNOWN
            }

            // 2. 髋主导（必须在「蹲」和「拉」之前：「罗马尼亚硬拉」含『拉』、「硬拉」不含蹲）
            if (name.containsAny(
                    "硬拉", "髋屈伸", "早安", "臀推", "臀桥", "山羊挺身",
                    "高翻", "抓举", "挺举", "摆荡",
                )
            ) {
                return HINGE
            }

            // 3. 蹲主导
            if (name.containsAny("深蹲", "前蹲", "哈克", "腿举", "箭步", "分腿蹲", "登阶")) {
                return SQUAT
            }

            // 4. 推主导（「双杠臂屈伸」要赶在下面『臂屈伸 → 孤立』之前）
            if (name.containsAny(
                    "卧推", "推胸", "推肩", "推举", "俯卧撑", "双杠臂屈伸", "倒立撑",
                )
            ) {
                return PRESS
            }

            // 5. 拉主导
            if (name.containsAny("划船", "引体", "下拉")) return PULL

            // 6. 单关节
            if (name.containsAny(
                    "夹胸", "飞鸟", "蝴蝶", "平举", "弯举", "臂屈伸", "屈伸", "下压",
                    "耸肩", "面拉", "直臂", "提踵", "内收", "外展", "卷腹", "举腿",
                )
            ) {
                return ISOLATION
            }

            // 7. 名字里没线索，退回分类兜底
            return when (category) {
                ExerciseCategory.CHEST, ExerciseCategory.SHOULDERS -> PRESS
                ExerciseCategory.BACK -> PULL
                ExerciseCategory.LEGS -> SQUAT
                ExerciseCategory.ARMS, ExerciseCategory.CORE -> ISOLATION
                else -> UNKNOWN
            }
        }

        private fun String.containsAny(vararg keys: String): Boolean = keys.any { contains(it) }
    }
}
