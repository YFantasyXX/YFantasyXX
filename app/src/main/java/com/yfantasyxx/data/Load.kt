package com.yfantasyxx.data

import com.yfantasyxx.data.db.Equipment

/**
 * 负荷口径。
 *
 * 自重动作（引体向上、双杠臂屈伸、俯卧撑）的 `weightKg` 记的是**额外负重**
 * （负重带、挂片），纯粹的徒手组就是 0。如果容量直接算 `weightKg × reps`，
 * 这些组的容量全是 0 —— 容量、周容量、部位占比、PR 榜会一起把它们当成"没练"。
 *
 * 所以容量的口径是：**有效负荷 = 体重 + 额外负重**。
 *
 * 两条约束：
 * 1. 只影响「容量」这类聚合，**不改 `weightKg` 本身**：次数、RPE、1RM 换算
 *    都要用原始值，改写入会污染历史数据。
 * 2. 体重取**最近一次**记录（`body_metrics`）。没记过体重时退化成 0，
 *    与旧行为一致，不会把数据算成负的或凭空变大。
 *
 * SQL 侧（[com.yfantasyxx.data.db.Daos] 里的容量聚合）有一份等价实现，
 * 改这里的规则时那边必须同步改。
 */
object Load {

    /** 容量用的有效负荷（kg） */
    fun effectiveKg(rawWeightKg: Float, equipment: String, bodyweightKg: Float?): Float =
        if (equipment == Equipment.BODYWEIGHT) {
            (bodyweightKg ?: 0f) + rawWeightKg.coerceAtLeast(0f)
        } else {
            rawWeightKg
        }

    /** 自重动作（重量栏的含义是「额外负重」，界面标签也要跟着换） */
    fun isBodyweight(equipment: String?): Boolean = equipment == Equipment.BODYWEIGHT
}
