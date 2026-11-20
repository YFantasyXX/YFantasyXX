package com.yfantasyxx.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import com.yfantasyxx.ui.theme.Motion

/** 按压缩放的方向 */
enum class ScaleAxis {
    /** 等比缩放（默认） */
    BOTH,

    /** 只缩横向，保持高度 */
    X,

    /** 只缩纵向 */
    Y,
}

/**
 * 给 [Modifier] 挂上按压缩放反馈。
 *
 * ## 为什么必须加
 *
 * 上一版整个应用里，能按的地方只有水波纹。但这个应用大部分可点区域是
 * **卡片**——卡片本身有圆角和阴影，水波纹画在卡片上是「圆角矩形里套一个
 * 半透明圆」，看着像贴纸而不是「按下去」。
 *
 * 真正让界面「有实体感」的是**尺寸变化**：按下去卡片缩一点点，松手弹回。
 * 用户不需要看水波纹就知道自己按到了。
 *
 * ## 为什么用指针观察而不是 interactionSource
 *
 * MIUIX 的 `Surface` / `IconButton` / `Button` 都不暴露 `interactionSource`
 * 参数，没法和 `clickable` 共用一个 source——如果自己 `remember` 一个，
 * 它永远不会收到按下事件，按下状态恒为 false，动画等于没接上。
 *
 * 所以这里改成**旁路观察**：`awaitEachGesture` + `awaitFirstDown` 拿到按下，
 * 之后自己跟踪指针抬起 / 取消，全程**不消费**事件
 * （`awaitFirstDown(requireUnconsumed = false)`，循环里也不调用 `consume()`）。
 *
 * 代价是要自己判断「这次按下算不算数」：
 *
 * - 指针移动超过触摸阈值 → 判定为滚动，按下取消（否则列表滚动时卡片会一直缩着）；
 * - 事件被别处消费（父级的横向拖动、bottom bar 的手势层）→ 判定为取消。
 */
@Composable
fun Modifier.pressScale(
    pressedScale: Float = 0.97f,
    axis: ScaleAxis = ScaleAxis.BOTH,
    enabled: Boolean = true,
): Modifier = composed {
    if (!enabled) return@composed this

    var pressed by remember { mutableStateOf(false) }
    val touchSlop = LocalViewConfiguration.current.touchSlop

    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        // 按下用快弹簧（跟手），松手用带一点弹性的慢弹簧
        animationSpec = if (pressed) Motion.press() else Motion.release(),
        label = "pressScale",
    )

    Modifier
        .graphicsLayer {
            scaleX = if (axis == ScaleAxis.Y) 1f else scale
            scaleY = if (axis == ScaleAxis.X) 1f else scale
        }
        // 旁路观察：只看，不消费。放在 graphicsLayer 之后，
        // 这样缩放和观察都在同一个修饰符链里，不需要调用方关心顺序。
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val startPos = down.position
                pressed = true
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                            ?: continue

                        if (change.isConsumed) {
                            // 别处（父级手势、bottom bar 的拖拽层）消费了这次事件
                            break
                        }
                        if ((change.position - startPos).getDistance() > touchSlop) {
                            // 移出触摸阈值：这是滚动，不是点击
                            break
                        }
                        if (!change.pressed) {
                            // 抬起：这次按下有效
                            break
                        }
                    }
                } finally {
                    pressed = false
                }
            }
        }
}
