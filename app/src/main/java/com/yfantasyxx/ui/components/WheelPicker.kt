package com.yfantasyxx.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 波轮选择器的状态。
 *
 * 之所以不用 miuix 的 `NumberPicker`：它只在**惯性动画跑完之后**才把新值回调出来
 * （`onDragStopped` 里 `animateDecay` + `animateTo` 之后才 `onValueChange`），
 * 手一松就点「确定」时动画还没结束、回调还没来，读到的是旧值。
 * 这里把「当前滚到了第几格」直接暴露成同步读取的 [currentValue]，
 * 点确定时读到的就是眼睛看到的那一格。
 */
@Stable
class WheelPickerState internal constructor(
    private val values: IntRange,
    initialValue: Int,
) {
    private val lastIndex = values.last - values.first

    /** 基准格（与 [dragOffset]、[anim] 相加才是视觉位置） */
    internal var index by mutableIntStateOf((initialValue - values.first).coerceIn(0, lastIndex))

    /** 手指拖动产生的位移，单位：格 */
    internal var dragOffset by mutableFloatStateOf(0f)

    /** 松手后惯性与吸附产生的位移，单位：格 */
    internal val anim = Animatable(0f)

    /** 视觉上位于正中间那一格的值。**同步读取**，不依赖动画是否结束 */
    fun currentValue(): Int =
        values.first + (index + (dragOffset + anim.value).roundToInt()).coerceIn(0, lastIndex)

    internal suspend fun onDragStart() {
        if (anim.isRunning) anim.stop()
        // 把正在跑的惯性位移接过来，接着手指继续拖，不跳
        val carried = anim.value
        if (carried != 0f) {
            dragOffset = (dragOffset + carried).coerceIn(
                -index.toFloat(),
                (lastIndex - index).toFloat(),
            )
            anim.snapTo(0f)
        }
    }

    internal suspend fun onDragStop(velocity: Float, itemHeightPx: Float) {
        if (itemHeightPx <= 0f) return
        val current = dragOffset
        dragOffset = 0f
        anim.snapTo(current)

        val min = -index.toFloat()
        val max = (lastIndex - index).toFloat()
        anim.updateBounds(min, max)

        val velocityInItems = -velocity / itemHeightPx
        if (abs(velocityInItems) > 0.1f) {
            anim.animateDecay(velocityInItems, exponentialDecay(frictionMultiplier = 2f))
        }
        val target = anim.value.roundToInt().toFloat().coerceIn(min, max)
        anim.animateTo(target, spring(dampingRatio = 1f, stiffness = 400f))
        anim.updateBounds(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)

        // 把基准格挪到视觉中心那一格，位移归零 —— 画面完全不动，但值已经是新的
        index = (index + target.roundToInt()).coerceIn(0, lastIndex)
        anim.snapTo(0f)
    }
}

@Composable
fun rememberWheelPickerState(values: IntRange, initialValue: Int): WheelPickerState =
    remember(values, initialValue) { WheelPickerState(values, initialValue) }

/**
 * 和 miuix `NumberPicker` 观感一致的波轮：居中一列、越远越小越淡、松手吸附。
 * 区别是控制器随时能读到中间那一格，不用等吸附动画结束。
 */
@Composable
fun WheelPicker(
    state: WheelPickerState,
    values: IntRange,
    modifier: Modifier = Modifier,
    visibleItemCount: Int = 5,
    itemHeight: Dp = 36.dp,
    label: (Int) -> String = { it.toString() },
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val itemHeightPx = with(density) { itemHeight.toPx() }
    val half = visibleItemCount / 2
    val lastIndex = values.last - values.first

    val dragState = rememberDraggableState { delta ->
        val min = -state.index.toFloat()
        val max = (lastIndex - state.index).toFloat()
        state.dragOffset = (state.dragOffset - delta / itemHeightPx).coerceIn(min, max)
    }

    // 每跨过一格给一次触感反馈
    val centeredTick = state.index + (state.dragOffset + state.anim.value).roundToInt()
    LaunchedEffect(centeredTick) {
        if (centeredTick != state.index) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(itemHeight * visibleItemCount)
            .clipToBounds()
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStarted = { scope.launch { state.onDragStart() } },
                onDragStopped = { velocity -> state.onDragStop(velocity, itemHeightPx) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val offset = state.dragOffset + state.anim.value
        val rounded = offset.roundToInt()
        val fraction = offset - rounded
        val selectedColor = MiuixTheme.colorScheme.onSurface
        val unselectedColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
        val textStyle = MiuixTheme.textStyles.title1.copy(fontWeight = FontWeight.SemiBold)

        for (i in -half - 1..half + 1) {
            val rawIndex = state.index + rounded + i
            if (rawIndex !in 0..lastIndex) continue
            // 距离中心的小数格数，用来算大小、透明度与纵向位置
            val distance = i - fraction
            val normalized = (abs(distance) / (half + 0.5f)).coerceIn(0f, 1f)
            Text(
                text = label(values.first + rawIndex),
                style = textStyle,
                color = androidx.compose.ui.graphics.lerp(
                    selectedColor,
                    unselectedColor,
                    normalized,
                ),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    alpha = (1f - normalized) * (1f - normalized * 0.5f)
                    val scale = 1f - 0.2f * normalized
                    scaleX = scale
                    scaleY = scale
                    translationY = distance * itemHeightPx
                },
            )
        }
    }
}
