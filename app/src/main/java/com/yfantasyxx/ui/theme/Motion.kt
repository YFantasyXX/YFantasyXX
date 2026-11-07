package com.yfantasyxx.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 全局动效令牌。
 *
 * ## 为什么以弹簧为主
 *
 * 上一版全用 `tween` + cubic-bezier，结果是「刚但硬」：
 *
 * - `CubicBezier(0.05, 0.7, 0.1, 1)`（原 emphasized 曲线）前 5% 的时间就冲掉
 *   70% 的距离，300ms 里实际 60ms 就到位然后干等 240ms —— 观感是「跳切」；
 * - `tween` 不带速度信息。快速连点两次时，第二次会把进度从当前值重新按
 *   曲线走一遍，曲线在中间「拐一下」，看起来像卡了一帧；
 * - `tween` 的时长是死的。同一个动作在 60Hz 和 120Hz 屏上完成时间一样，
 *   但高刷屏的采样间隔更细，反而更容易看到缓动的中段。
 *
 * 弹簧（spring）按「当前速度 + 当前位移」积分，天然满足：
 *
 * - 中途打断不需要重来，速度被继承，观感连续；
 * - 时长由参数自然涌现，不需要手写「这个动作大概要多久」；
 * - 阻尼比决定「有没有回弹」，刚度决定「多快到位」，两件事解耦。
 *
 * 所以这里的分工是：
 *
 * | 动作类型 | 用什么 | 理由 |
 * |---|---|---|
 * | 位置、尺寸、缩放 | 弹簧 | 要能被手指打断、要能续上速度 |
 * | 按下反馈 | 弹簧（快、几乎不回弹） | 必须跟手，延迟超过 100ms 就「不灵敏」 |
 * | 透明度、颜色 | tween | 没有「位置」可积分，且淡入淡出的观感就是线性的好 |
 * | 页面转场 | 弹簧（慢、略带回弹） | 大位移需要一个能带出惯性的收尾 |
 *
 * ## 命名
 *
 * 令牌按**角色**命名而不是按时长命名。角色名逼着你想清楚「这个东西属于
 * 哪一类」，而不是随手抄一个 `tween(220)`。
 */
object Motion {

    /* ───────────────────────── 透明度 / 颜色（用时长） ───────────────────────── */

    /** 淡入淡出：给颜色和透明度用。这两个量没有「速度」可言，线性插值反而最干净。 */
    const val DURATION_INSTANT = 100
    const val DURATION_FAST = 160
    const val DURATION_STANDARD = 240
    const val DURATION_SLOW = 320

    /**
     * 标准曲线（Material 3 standard，0.2 0 0 1）。
     *
     * 起手快、收尾缓。人对运动的感知主要来自减速段，
     * 用这条曲线的东西看起来是有质量的，而不是被线性拖动。
     */
    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 减速曲线：元素**进入**画面时用，一出现就在高速，落地轻 */
    val DecelerateEasing: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

    /** 加速曲线：元素**离开**画面时用，先缓后快，走得干净不拖尾 */
    val AccelerateEasing: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /**
     * 强调曲线（Material 3 emphasized 的第一段）。
     *
     * 只用于「淡入淡出叠加在弹簧位移之上」的场景：位移本身已经由弹簧负责，
     * 这里只补一层透明度变化，用强调曲线让画面在到位前就基本清晰。
     */
    val EmphasizedEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /* ───────────────────────── 弹簧（用于位置 / 尺寸 / 缩放） ───────────────────────── */

    /**
     * 按下反馈。
     *
     * 刚度高（2000）+ 阻尼 0.9：几乎立刻到位、只留一点点回弹。
     * 按下的手感要求是「手指按下去东西就缩了」，所以这里必须比别的动作快。
     * 回弹过大（阻尼 < 0.8）会让人觉得按钮在晃，很廉价。
     */
    fun <T> press(): FiniteAnimationSpec<T> =
        spring(
            dampingRatio = 0.9f,
            stiffness = Spring.StiffnessHigh,
        )

    /**
     * 松手回弹。
     *
     * 比按下慢、弹性略大：按下是「你按我」，松手是「我弹回去」，
     * 慢一点才看得出弹性。这一档是整个应用里最能体现「活物感」的地方。
     */
    fun <T> release(): FiniteAnimationSpec<T> =
        spring(
            dampingRatio = 0.7f,
            stiffness = Spring.StiffnessMedium,
        )

    /**
     * 通用状态切换：颜色、图标形状、轻微缩放。
     *
     * 阻尼 0.85：几乎不弹，但收尾有一点「软」。刚度 Medium（700）大约 200ms 到位。
     */
    fun <T> snappy(): FiniteAnimationSpec<T> =
        spring(
            dampingRatio = 0.85f,
            stiffness = Spring.StiffnessMedium,
        )

    /**
     * 位置 / 尺寸变化：底栏胶囊滑动、卡片展开、列表项进出。
     *
     * 刚度 MediumLow（400）—— 位置变化需要比状态变化慢一点，
     * 太快的移动会让用户「找不到东西去哪了」。
     * 阻尼 0.8：到位后有一次极轻的过冲，像有惯性。
     */
    fun <T> smooth(): FiniteAnimationSpec<T> =
        spring(
            dampingRatio = 0.8f,
            stiffness = Spring.StiffnessMediumLow,
        )

    /**
     * 大范围位移：页面转场。
     *
     * 刚度 Low（200）+ 阻尼 0.85。这一档走完大约 350–400ms，
     * 比普通状态变化慢，因为整屏内容在移动，
     * 太快会晕、慢到 500ms 以上会觉得「卡」。
     */
    fun <T> emphasized(): FiniteAnimationSpec<T> =
        spring(
            dampingRatio = 0.85f,
            stiffness = Spring.StiffnessLow,
        )

    /**
     * 数字滚动：重量、次数、时长这类会变值的读数。
     *
     * 刚度偏高（MediumHigh）+ 阻尼 1.0（完全不过冲）：
     * 数字如果弹过头会读错，读数必须「稳稳停住」。
     */
    fun <T> counting(): FiniteAnimationSpec<T> =
        spring(
            dampingRatio = 1f,
            stiffness = Spring.StiffnessMedium,
        )

    /* ───────────────────────── 便捷封装 ───────────────────────── */

    fun <T> standard(durationMillis: Int = DURATION_STANDARD): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = StandardEasing)

    fun <T> decelerate(durationMillis: Int = DURATION_STANDARD): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = DecelerateEasing)

    fun <T> accelerate(durationMillis: Int = DURATION_FAST): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = AccelerateEasing)

    fun <T> emphasizedFade(durationMillis: Int = DURATION_STANDARD): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = EmphasizedEasing)
}
