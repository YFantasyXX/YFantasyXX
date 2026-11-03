package com.yfantasyxx.ui.dev

import com.yfantasyxx.data.LiftPattern
import com.yfantasyxx.data.OneRepMax
import com.yfantasyxx.data.PatternCalibration
import com.yfantasyxx.data.Progression
import com.yfantasyxx.data.ProgressionMode
import com.yfantasyxx.data.RepMaxCalibration
import com.yfantasyxx.data.RepMaxSample
import com.yfantasyxx.data.RepRange
import com.yfantasyxx.data.TrainingCycle
import com.yfantasyxx.data.PlanDaySlot
import com.yfantasyxx.data.db.Equipment
import com.yfantasyxx.data.db.ExerciseCategory
import com.yfantasyxx.data.db.RepMaxRow
import com.yfantasyxx.data.db.SetLog
import java.util.Locale
import kotlin.math.abs

/** 一条断言 */
data class KernelCase(
    val group: String,
    val name: String,
    val expect: String,
    val actual: String,
    val passed: Boolean,
    val note: String? = null,
)

/** 一次 1RM 计算的完整走位：走了哪条路径、比例多少、每条公式各给多少 */
data class KernelTrace(
    val path: String,
    val fraction: Float?,
    val result: Float?,
    val steps: List<String>,
)

/** 某一档的校准结果，摊开给界面看 */
data class CalibrationLine(
    val pattern: String,
    val gamma: Float,
    val actions: Int,
    val sets: Int,
    val trusted: Boolean,
)

/**
 * 计算内核的观测台与断言面板。
 *
 * 为什么需要：`OneRepMax` / `RepMaxCalibration` / `Progression` 全是纯函数，
 * 算错了**不会崩**，只会悄悄在训练页印一个偏了十几公斤的数字。
 * 这类错误靠点界面看不出来——你看不出 117.6 和 122.0 哪个对。
 *
 * 所以这里做两件事：
 * 1. **断言面板**：把「期望值」从注释里搬进代码，一键全跑，红的绿的一目了然。
 *    每条断言都对应一个真会犯的错（分档判错、斜率算反、区间边界、收缩系数漏乘）。
 * 2. **观测台**：给一组重量/次数/分档，把走位摊开——查表还是融合、
 *    比例多少、七条公式各自算出多少。数字不对时能立刻看出是哪一环。
 *
 * 只存在于 `ui/dev`，正式版被 R8 裁掉。
 */
object DevKernel {

    // ---------- 断言 ----------

    private class Builder {
        val cases = mutableListOf<KernelCase>()

        /** 字符串精确相等（适合枚举、区间、null） */
        fun eq(group: String, name: String, expect: String, block: () -> Any?) {
            val r = runCatching { block() }
            val actual = r.getOrNull()?.let { render(it) }
                ?: if (r.isSuccess) "null" else "异常"
            cases += KernelCase(
                group = group,
                name = name,
                expect = expect,
                actual = actual,
                passed = r.isSuccess && actual == expect,
                note = r.exceptionOrNull()?.let { it.message ?: it::class.simpleName },
            )
        }

        /** 浮点容差比较。null 期望用 eq */
        fun near(group: String, name: String, expect: Float, tol: Float, block: () -> Float?) {
            val r = runCatching { block() }
            val v = r.getOrNull()
            val expectText = "${fmt(expect, 2)} ± ${fmt(tol, 2)}"
            cases += KernelCase(
                group = group,
                name = name,
                expect = expectText,
                actual = if (v != null) fmt(v, 2) else if (r.isSuccess) "null" else "异常",
                passed = v != null && abs(v - expect) <= tol,
                note = r.exceptionOrNull()?.let { it.message ?: it::class.simpleName },
            )
        }

        /** 布尔断言：返回值是不是 null、是不是满足某个不变量 */
        fun flag(group: String, name: String, expect: Boolean, block: () -> Boolean) {
            val r = runCatching { block() }
            cases += KernelCase(
                group = group,
                name = name,
                expect = if (expect) "满足" else "不满足",
                actual = when {
                    r.isFailure -> "异常"
                    r.getOrThrow() -> "满足"
                    else -> "不满足"
                },
                passed = r.getOrNull() == expect,
                note = r.exceptionOrNull()?.let { it.message ?: it::class.simpleName },
            )
        }
    }

    /** 跑全部断言。顺序固定，方便和历史结果对比 */
    fun runAll(): List<KernelCase> {
        val b = Builder()
        ranges(b)
        patterns(b)
        oneRepMax(b)
        calibration(b)
        progression(b)
        cycle(b)
        return b.cases
    }

    // ---------- RepRange：历史脏数据的解析 ----------

    private fun ranges(b: Builder) {
        val g = "RepRange.parse"
        b.eq(g, "\"8-12\"", "8-12") { RepRange.parse("8-12")?.label }
        b.eq(g, "\"12-8\" 自动换序", "8-12") { RepRange.parse("12-8")?.label }
        b.eq(g, "\"8~12\" 波浪号", "8-12") { RepRange.parse("8~12")?.label }
        b.eq(g, "\"8 - 12\" 带空格", "8-12") { RepRange.parse("8 - 12")?.label }
        b.eq(g, "\"8\" 单值", "8") { RepRange.parse("8")?.label }
        b.eq(g, "\"8次\" 带单位", "8") { RepRange.parse("8次")?.label }
        // 0 次没意义，落库前必须被抬到 1，否则 allHitTop 判断会让每一组都算「达标」
        b.eq(g, "\"0\" 抬到 1", "1") { RepRange.parse("0")?.label }
        b.eq(g, "\"abc\" 解析不出", "null") { RepRange.parse("abc")?.label }
        b.eq(g, "空串", "null") { RepRange.parse("  ")?.label }
        b.eq(g, "parseOrDefault 兜底", "8-12") { RepRange.parseOrDefault("??").label }
    }

    // ---------- LiftPattern：分档判错 = 1RM 直接偏十几个点 ----------

    private fun patterns(b: Builder) {
        val g = "LiftPattern.infer"
        val legs = ExerciseCategory.LEGS
        val arms = ExerciseCategory.ARMS
        val back = ExerciseCategory.BACK

        // 「罗马尼亚硬拉」在腿分类里但属于髋主导，必须早于「蹲」和「拉」判定
        b.eq(g, "罗马尼亚硬拉 → 硬拉类", "HINGE") {
            LiftPattern.infer("罗马尼亚硬拉", legs, Equipment.BARBELL).name
        }
        b.eq(g, "杠铃深蹲 → 深蹲类", "SQUAT") {
            LiftPattern.infer("杠铃深蹲", legs, Equipment.BARBELL).name
        }
        // 挂在手臂分类下的复合推，必须早于「臂屈伸 → 孤立」
        b.eq(g, "双杠臂屈伸 → 推类", "PRESS") {
            LiftPattern.infer("双杠臂屈伸", arms, Equipment.BODYWEIGHT).name
        }
        b.eq(g, "窄距卧推 → 推类", "PRESS") {
            LiftPattern.infer("窄距卧推", arms, Equipment.BARBELL).name
        }
        b.eq(g, "腿弯举 → 孤立类", "ISOLATION") {
            LiftPattern.infer("腿弯举", legs, Equipment.MACHINE).name
        }
        b.eq(g, "哑铃侧平举 → 孤立类", "ISOLATION") {
            LiftPattern.infer("哑铃侧平举", ExerciseCategory.SHOULDERS, Equipment.DUMBBELL).name
        }
        b.eq(g, "坐姿划船 → 拉类", "PULL") {
            LiftPattern.infer("坐姿划船", back, Equipment.CABLE).name
        }
        b.eq(g, "平板支撑 → 通用", "UNKNOWN") {
            LiftPattern.infer("平板支撑", ExerciseCategory.CORE, Equipment.BODYWEIGHT).name
        }
        b.eq(g, "跑步 → 通用", "UNKNOWN") {
            LiftPattern.infer("跑步", ExerciseCategory.CARDIO, Equipment.OTHER).name
        }
        // 这条曾经踩过：「雪橇」被「推雪橇」误伤，整台腿举退回七公式
        b.eq(g, "雪橇 45° 腿举 → 深蹲类", "SQUAT") {
            LiftPattern.infer("雪橇 45° 腿举", legs, Equipment.MACHINE).name
        }
        // 名字没线索时退回分类兜底
        b.eq(g, "无名 + 背 → 拉类", "PULL") {
            LiftPattern.infer("某个自建动作", back, Equipment.CABLE).name
        }
        b.eq(g, "无名 + 全身 → 通用", "UNKNOWN") {
            LiftPattern.infer("某个自建动作", ExerciseCategory.FULL_BODY, Equipment.OTHER).name
        }
    }

    // ---------- OneRepMax：数字错了不会崩，只会印在训练页上 ----------

    private fun oneRepMax(b: Builder) {
        val g = "OneRepMax.estimate"
        val press = LiftPattern.PRESS
        val hinge = LiftPattern.HINGE

        b.eq(g, "100×1 就是真 1RM", "100.0") { OneRepMax.estimate(100f, 1, press)?.let { fmt(it, 1) } }
        // 100 / 0.85（推类 5 次）
        b.eq(g, "100×5 推类 → 117.6", "117.6") {
            OneRepMax.estimate(100f, 5, press)?.let { fmt(it, 1) }
        }
        // 100 / 0.82（髋主导 5 次）：同一次数，硬拉比卧推更接近 1RM
        b.eq(g, "100×5 硬拉类 → 122.0", "122.0") {
            OneRepMax.estimate(100f, 5, hinge)?.let { fmt(it, 1) }
        }
        // 100 / 0.65（髋主导 10 次）：高次数下两档的差距被拉开
        b.eq(g, "100×10 硬拉类 → 153.8", "153.8") {
            OneRepMax.estimate(100f, 10, hinge)?.let { fmt(it, 1) }
        }
        b.eq(g, "100×3 深蹲类 → 108.7", "108.7") {
            OneRepMax.estimate(100f, 3, LiftPattern.SQUAT)?.let { fmt(it, 1) }
        }
        // 同一组重量次数，髋主导的估值必须高于推类：这是分档存在的全部意义
        b.flag(g, "10 次时 硬拉 > 推类", true) {
            OneRepMax.estimate(100f, 10, hinge)!! > OneRepMax.estimate(100f, 10, press)!!
        }
        // 7 条公式加权融合；容差留 0.5，公式微调不至于天天红
        b.near(g, "100×10 通用 → 融合兜底", 131.27f, 0.6f) {
            OneRepMax.estimate(100f, 10, LiftPattern.UNKNOWN)
        }
        b.eq(g, "重量 0（自重）→ null", "null") { OneRepMax.estimate(0f, 5, press) }
        b.eq(g, "次数 0 → null", "null") { OneRepMax.estimate(100f, 0, press) }
        b.eq(g, "次数 13（超上限）→ null", "null") { OneRepMax.estimate(100f, 13, press) }
        b.eq(g, "次数 12（上限内）不算 null", "true") {
            (OneRepMax.estimate(100f, 12, press) != null).toString()
        }
    }

    // ---------- RepMaxCalibration：斜率算反了只会让曲线悄悄走偏 ----------

    private fun calibration(b: Builder) {
        val g = "RepMaxCalibration"
        val squat = LiftPattern.SQUAT

        // 用群体表 × 同一个 1RM 生成的点，γ 必须回到 1（不校准 = 采信群体表）
        val neutral = listOf(
            RepMaxSample(2, 100f * 0.95f),
            RepMaxSample(5, 100f * 0.86f),
            RepMaxSample(8, 100f * 0.78f),
        )
        b.near(g, "数据贴合群体表 → γ = 1", 1f, 0.02f) {
            RepMaxCalibration.fitAction(squat, neutral)
        }

        // 高次数掉得更少 → 更抗次数 → γ 应小于 1（表被"压平"）
        val endurant = listOf(
            RepMaxSample(2, 95f),
            RepMaxSample(5, 88f),
            RepMaxSample(8, 86f),
        )
        b.flag(g, "高次数掉得少 → γ < 1", true) {
            (RepMaxCalibration.fitAction(squat, endurant) ?: 1f) < 1f
        }

        // 高次数掉得更狠 → γ > 1
        val explosive = listOf(
            RepMaxSample(2, 96f),
            RepMaxSample(5, 74f),
            RepMaxSample(8, 60f),
        )
        b.flag(g, "高次数掉得狠 → γ > 1", true) {
            (RepMaxCalibration.fitAction(squat, explosive) ?: 1f) > 1f
        }

        // 极端数据必须被夹住，否则 10 次会被算成 1RM 的 30%
        b.flag(g, "极端数据夹在 [0.65, 1.55]", true) {
            val g2 = RepMaxCalibration.fitAction(
                squat,
                listOf(RepMaxSample(2, 99f), RepMaxSample(5, 99f), RepMaxSample(8, 99f)),
            )
            g2 != null && g2 >= RepMaxCalibration.GAMMA_MIN && g2 <= RepMaxCalibration.GAMMA_MAX
        }

        // 只有一档：连不成斜率
        b.eq(g, "单点 → null", "null") {
            RepMaxCalibration.fitAction(squat, listOf(RepMaxSample(5, 100f)))
        }
        // 8 次和 9 次挨太近，斜率全是噪声
        b.eq(g, "跨度不足 3 → null", "null") {
            RepMaxCalibration.fitAction(
                squat,
                listOf(RepMaxSample(8, 80f), RepMaxSample(9, 78f)),
            )
        }
        b.eq(g, "通用档不校准", "null") {
            RepMaxCalibration.fitAction(
                LiftPattern.UNKNOWN,
                listOf(RepMaxSample(2, 95f), RepMaxSample(8, 78f)),
            )
        }
        // 自重动作（重量 0）要排除掉，否则 ln(0) = -∞ 把斜率带飞
        b.eq(g, "重量 0 的点被排除", "null") {
            RepMaxCalibration.fitAction(
                squat,
                listOf(RepMaxSample(2, 0f), RepMaxSample(8, 0f)),
            )
        }

        b.eq(g, "空输入 → 空结果", "0") { RepMaxCalibration.calibrate(emptyList()).size }

        // 一个动作时 α = 1/3，γ 必须被拉向 1
        val oneAction = rows(exerciseId = 1, pattern = squat.value, sets = 10, points = explosive)
        val one = RepMaxCalibration.calibrate(oneAction)[squat.value]
        val personalGamma = RepMaxCalibration.fitAction(squat, explosive) ?: 1f
        b.flag(g, "单动作 α=1/3 → 偏离量只剩 1/3", true) {
            one != null && abs((one.gamma - 1f) - (personalGamma - 1f) / 3f) < 0.02f
        }
        b.eq(g, "单动作 → actionCount = 1", "1") { one?.actionCount }
        b.eq(g, "组数汇总（3 点 × 10 组）", "30") { one?.setCount }

        // 攒够 3 个动作才完全采信
        val threeActions = (1L..3L).flatMap {
            rows(exerciseId = it, pattern = squat.value, sets = 10, points = explosive)
        }
        val full = RepMaxCalibration.calibrate(threeActions)[squat.value]
        b.flag(g, "三动作 → 完全采信（α = 1）", true) {
            val personal = RepMaxCalibration.fitAction(squat, explosive) ?: 1f
            full != null && abs(full.gamma - personal) < 0.02f
        }

        // 同一动作同一次数练过多组时只取最重的那次
        val duplicated = listOf(
            RepMaxRow(1, squat.value, 2, 95f, 1),
            RepMaxRow(1, squat.value, 2, 99f, 1), // 同次数更重，应取代上一行
            RepMaxRow(1, squat.value, 5, 86f, 1),
            RepMaxRow(1, squat.value, 8, 78f, 1),
        )
        b.flag(g, "同次数只取最重的那组", true) {
            val heaviest = RepMaxCalibration.calibrate(duplicated)[squat.value]!!.gamma
            val lighter = RepMaxCalibration.calibrate(
                duplicated.map { if (it.reps == 2) it.copy(weightKg = 95f) else it }
            )[squat.value]!!.gamma
            heaviest > lighter
        }
    }

    /** 一个动作的样本摊平成 [RepMaxRow]：同一档里复制若干组，模拟"真练过很多次" */
    private fun rows(exerciseId: Long, pattern: Int, sets: Int, points: List<RepMaxSample>): List<RepMaxRow> =
        points.map { RepMaxRow(exerciseId, pattern, it.reps, it.weightKg, sets) }

    // ---------- Progression：给错重量比不给更糟 ----------

    private fun progression(b: Builder) {
        val g = "Progression.suggest"
        val range = RepRange(8, 12)
        val legs = ExerciseCategory.LEGS
        val chest = ExerciseCategory.CHEST
        val barbell = Equipment.BARBELL

        fun s(no: Int, w: Float, r: Int, done: Boolean = true, warmup: Boolean = false) = SetLog(
            sessionExerciseId = 1L,
            setNumber = no,
            weightKg = w,
            reps = r,
            isWarmup = warmup,
            completed = done,
        )

        b.eq(g, "OFF → 不给建议", "null") {
            Progression.suggest(
                ProgressionMode.OFF, 100f, range,
                listOf(s(1, 100f, 12)), legs, barbell,
            )
        }
        b.eq(g, "没有历史 → null", "null") {
            Progression.suggest(ProgressionMode.LINEAR, 100f, range, emptyList(), legs, barbell)
        }
        b.eq(g, "只剩热身组 → null", "null") {
            Progression.suggest(
                ProgressionMode.LINEAR, 100f, range,
                listOf(s(1, 40f, 20, warmup = true)), legs, barbell,
            )
        }

        // 三组全部 12 次 → 加重，次数回到下限
        val allTop = listOf(s(1, 100f, 12), s(2, 100f, 12), s(3, 100f, 12))
        b.eq(g, "全达上限（腿）→ +5kg / 回到 8 次", "105.0 / 8 / true") {
            Progression.suggest(ProgressionMode.LINEAR, 100f, range, allTop, legs, barbell)
                ?.let { "${fmt(it.weightKg, 1)} / ${it.reps} / ${it.addedWeight}" }
        }
        // 胸/肩只加 2.5：同一个 5kg 对卧推是一个月，对深蹲是一周
        b.eq(g, "全达上限（胸）→ +2.5kg", "102.5 / 8 / true") {
            Progression.suggest(ProgressionMode.LINEAR, 100f, range, allTop, chest, barbell)
                ?.let { "${fmt(it.weightKg, 1)} / ${it.reps} / ${it.addedWeight}" }
        }
        // 有一组没到上限 → 线性策略原地重来
        b.eq(g, "未全达上限（线性）→ 重量不变", "100.0 / 8 / false") {
            Progression.suggest(
                ProgressionMode.LINEAR, 100f, range,
                listOf(s(1, 100f, 12), s(2, 100f, 10), s(3, 100f, 11)), legs, barbell,
            )?.let { "${fmt(it.weightKg, 1)} / ${it.reps} / ${it.addedWeight}" }
        }
        // 双重进阶：重量不动，次数从最少的那组 +1
        b.eq(g, "双重进阶 → 次数 9 → 10", "100.0 / 10 / false") {
            Progression.suggest(
                ProgressionMode.DOUBLE, 100f, range,
                listOf(s(1, 100f, 12), s(2, 100f, 9), s(3, 100f, 11)), legs, barbell,
            )?.let { "${fmt(it.weightKg, 1)} / ${it.reps} / ${it.addedWeight}" }
        }
        // 最少 11 次时推到 12，但不能越过上限
        b.eq(g, "双重进阶 11 → 12（不越上限）", "100.0 / 12 / false") {
            Progression.suggest(
                ProgressionMode.DOUBLE, 100f, range,
                listOf(s(1, 100f, 11), s(2, 100f, 11), s(3, 100f, 12)), legs, barbell,
            )?.let { "${fmt(it.weightKg, 1)} / ${it.reps} / ${it.addedWeight}" }
        }
        // 未完成的组不参与判断：否则"点了一半"会被当成"没达标"
        b.eq(g, "未完成的组被忽略", "105.0 / 8 / true") {
            Progression.suggest(
                ProgressionMode.LINEAR, 100f, range,
                listOf(s(1, 100f, 12), s(2, 100f, 5, done = false)), legs, barbell,
            )?.let { "${fmt(it.weightKg, 1)} / ${it.reps} / ${it.addedWeight}" }
        }
        // 模板没填目标重量时，用上次第一组的实际重量
        b.eq(g, "上次重量为 0 → 用首组重量", "60.0 / 8 / false") {
            Progression.suggest(
                ProgressionMode.LINEAR, 0f, range,
                listOf(s(1, 60f, 10)), legs, barbell,
            )?.let { "${fmt(it.weightKg, 1)} / ${it.reps} / ${it.addedWeight}" }
        }
        b.eq(g, "roundToStep(103.7) → 102.5", "102.5") {
            fmt(Progression.roundToStep(103.7f), 1)
        }
        b.eq(g, "roundToStep(104.0) → 105.0", "105.0") {
            fmt(Progression.roundToStep(104.0f), 1)
        }
    }

    // ---------- TrainingCycle：休息日自动流过，训练日停住 ----------

    private fun cycle(b: Builder) {
        val g = "TrainingCycle.cursor"
        val day = 86_400_000L
        val today = TrainingCycle.startOfDay(System.currentTimeMillis())
        val slots = listOf(
            PlanDaySlot(1L, "推"),
            PlanDaySlot(null, "休"),
            PlanDaySlot(null, "休"),
            PlanDaySlot(2L, "拉"),
        )

        b.eq(g, "休息日没过完 → 停在休息日", "1") {
            TrainingCycle.cursor(1, today, slots, today + 12 * 3_600_000L).index
        }
        b.eq(g, "过了一天 → 流到第二个休息日", "2") {
            TrainingCycle.cursor(1, today, slots, today + day + 12 * 3_600_000L).index
        }
        b.eq(g, "过了两天 → 流到训练日", "3") {
            TrainingCycle.cursor(1, today, slots, today + 2 * day + 12 * 3_600_000L).index
        }
        // 训练日不随时间自己走：断练一周回来还是这一天
        b.eq(g, "训练日停住（过 10 天也不动）", "3") {
            TrainingCycle.cursor(3, today + 2 * day, slots, today + 10 * day).index
        }
        b.eq(g, "下标越界被夹住", "3") {
            TrainingCycle.cursor(99, today, slots, today).index
        }
        b.eq(g, "空计划不崩", "0") {
            TrainingCycle.cursor(0, today, emptyList(), today).index
        }
    }

    // ---------- 观测台 ----------

    /**
     * 把一次 1RM 计算摊开。
     *
     * @param gamma 传非 null 时用幂变换模拟个人校准（γ = 1 等价于群体表）
     */
    fun trace(weightKg: Float, reps: Int, pattern: LiftPattern, gamma: Float? = null): KernelTrace {
        if (weightKg <= 0f || reps <= 0 || reps > OneRepMax.MAX_RELIABLE_REPS) {
            return KernelTrace(
                path = "不算",
                fraction = null,
                result = null,
                steps = listOf(
                    when {
                        weightKg <= 0f -> "重量 ≤ 0：自重动作没有 1RM"
                        reps <= 0 -> "次数 ≤ 0：非法输入"
                        else -> "次数 > ${OneRepMax.MAX_RELIABLE_REPS}：任何公式都不可信，拒绝给数"
                    }
                ),
            )
        }
        if (reps == 1) {
            return KernelTrace("单次即 1RM", 1f, weightKg, listOf("1 次就是真 1RM，所有公式在 r=1 时都会高估"))
        }

        val curve = gamma?.let { PatternCalibration(pattern, it, actionCount = 3, setCount = 0) }
        val fraction = (curve ?: pattern).fractionAt(reps)
        val result = OneRepMax.estimate(weightKg, reps, curve ?: pattern)

        val steps = mutableListOf<String>()
        steps += "分档：${pattern.label}（${pattern.name}）"
        if (fraction != null) {
            steps += "查表：${reps} 次 → 占 1RM 的 ${fmt(fraction * 100f, 1)}%"
            if (gamma != null) {
                steps += "个人校准：γ = ${fmt(gamma, 2)} → 比例 ${fmt(fraction * 100f, 1)}%"
            }
            steps += "换算：${fmt(weightKg, 1)} ÷ ${fmt(fraction, 3)} = ${fmt(result ?: 0f, 1)}"
            return KernelTrace(
                path = if (gamma != null && gamma != 1f) "查表（个人校准）" else "查表（群体均值）",
                fraction = fraction,
                result = result,
                steps = steps,
            )
        }

        steps += "查表：这条曲线不覆盖 ${reps} 次 → 回落到七公式融合"
        val fused = result ?: 0f
        steps += "融合结果：${fmt(fused, 1)}"
        return KernelTrace("七公式融合", null, result, steps)
    }

    /** 把库里的校准结果摊成一行一档 */
    fun calibrationReport(rows: List<RepMaxRow>): List<CalibrationLine> {
        val map = RepMaxCalibration.calibrate(rows)
        if (map.isEmpty()) return emptyList()
        return map.entries
            .sortedBy { it.key }
            .map { (value, cal) ->
                CalibrationLine(
                    pattern = LiftPattern.fromValue(value).label,
                    gamma = cal.gamma,
                    actions = cal.actionCount,
                    sets = cal.setCount,
                    trusted = cal.actionCount >= RepMaxCalibration.FULL_TRUST_ACTIONS,
                )
            }
    }

    /** 校准报告的一句话总结 */
    fun calibrationSummary(rows: List<RepMaxRow>): String {
        val lines = calibrationReport(rows)
        if (lines.isEmpty()) {
            return "还没有任何一档攒够样本（至少要 2 个不同次数、跨度 ≥ " +
                "${RepMaxCalibration.MIN_SPAN} 次），全部走群体默认表"
        }
        return lines.joinToString("\n") { line ->
            val trust = if (line.trusted) "完全采信" else "部分采信（α = ${fmt(line.actions / 3f, 2)}）"
            "· ${line.pattern}：γ = ${fmt(line.gamma, 2)} · " +
                "${line.actions} 个动作 / ${line.sets} 组 · $trust"
        }
    }

    fun fmt(v: Float, digits: Int): String = String.format(Locale.US, "%.${digits}f", v)

    private fun render(v: Any): String = when (v) {
        is Float -> fmt(v, 1)
        is Double -> String.format(Locale.US, "%.1f", v)
        is String -> v
        else -> v.toString()
    }
}
