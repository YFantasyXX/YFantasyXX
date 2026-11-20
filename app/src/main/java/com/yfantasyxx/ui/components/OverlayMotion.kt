package com.yfantasyxx.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import com.yfantasyxx.ui.theme.Motion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 浮层（下拉菜单、弹层卡片）的进出场状态。
 *
 * ## 为什么抽出来
 *
 * 菜单、选择面板、对话框三个地方原本各写一份 `Animatable` + `LaunchedEffect`，
 * 三份参数不一样、退场时机也不一样，其中两份还各有一个毛病（见 [enter] / [exit]）。
 * 收到一处之后「浮层怎么动」只有一个答案，调参也只调一次。
 *
 * ## 两个量分开
 *
 * - 缩放走弹簧（[Motion.overlay]）：要能被打断、要能续上速度；
 * - 透明度走时长（[Motion.DURATION_OVERLAY_IN]）：没有「位置」可积分，
 *   而且亮度变化用弹簧会在过冲段闪一下。
 *
 * @param fromScale 展开动画的起始缩放。越小「长出来」的感觉越强，
 *   但太小会像从远处飞过来——0.90 左右是「就在这一行上冒出来」。
 * @param toScale 收起动画的终点缩放。必须**小于 1 且大于 [fromScale]**：
 *   收起只缩一点，缩太多会显得是被吸走的。
 */
@Stable
class OverlayMotionState(
    private val fromScale: Float = 0.90f,
    private val toScale: Float = 0.94f,
) {

    val alpha = Animatable(0f)
    val scale = Animatable(fromScale)

    /**
     * 展开。
     *
     * **完全收起时必须先 `snapTo` 回起点**：这一类浮层收起后并不会被销毁（宿主一直挂在
     * App 根部），`scale` 会停在 [toScale]、`alpha` 停在 0。不复位的话第二次打开时
     * 只有 6% 的缩放，一百多毫秒就到位，看起来是「啪」地出现——这正是第一版下拉菜单
     * 「生硬」的来源。
     *
     * 反过来，**收到一半被打断时不能复位**：那时 alpha 还在半路，硬跳回 0 会闪一下。
     * 所以只在 alpha 已经归零（= 上一次退场播完了）时才重置，其余情况接着当前进度往上走。
     */
    suspend fun enter() {
        if (alpha.value <= 0.01f) {
            scale.snapTo(fromScale)
            alpha.snapTo(0f)
        }
        coroutineScope {
            launch { alpha.animateTo(1f, Motion.decelerate(Motion.DURATION_OVERLAY_IN)) }
            launch { scale.animateTo(1f, Motion.overlay()) }
        }
        // 弹簧按「阈值」收敛，会停在目标的 1% 以内而不是精确目标：缩放落在 0.995 上，
        // 玻璃面板就会**永久**带着一个非 1 的缩放做重采样（文字边缘发虚、
        // 1dp 描边对不上像素）。这里补一次 snap，最终状态必须是精确的 1f。
        scale.snapTo(1f)
        alpha.snapTo(1f)
    }

    /**
     * 收起。
     *
     * 两个动画都**等完**才返回：调用方拿它当「能不能把界面摘掉」的信号，
     * 谁先谁后都不能只等其中一个（之前等的是缩放，透明度还在半路就把子树摘了，
     * 淡出被截断，于是收起看起来很干脆、其实是断的）。
     */
    suspend fun exit() = coroutineScope {
        launch { alpha.animateTo(0f, Motion.accelerate(Motion.DURATION_OVERLAY_OUT)) }
        launch { scale.animateTo(toScale, Motion.overlayExit()) }
    }
}

/** 记住一份进出场状态。参数只影响轨迹，实例本身在重组之间保持同一个。 */
@Composable
fun rememberOverlayMotion(
    fromScale: Float = 0.90f,
    toScale: Float = 0.94f,
): OverlayMotionState = remember(fromScale, toScale) {
    OverlayMotionState(fromScale = fromScale, toScale = toScale)
}

/**
 * 把 [state] 的透明度和缩放接到这一层上。
 *
 * 读状态的动作发生在 `graphicsLayer` 的 block 里（绘制阶段），不会引起重组——
 * 每帧只重算这一层的变换矩阵，开销可以忽略。
 *
 * @param origin 缩放原点。下拉菜单给「触行那一侧」让它从行上长出来；
 *   居中弹层保持默认的中心。
 */
fun Modifier.overlayTransform(
    state: OverlayMotionState,
    origin: TransformOrigin = TransformOrigin.Center,
): Modifier = graphicsLayer {
    alpha = state.alpha.value
    scaleX = state.scale.value
    scaleY = state.scale.value
    transformOrigin = origin
}

/**
 * 遮罩层的淡化。
 *
 * 用 `drawBehind` 画而不是 `graphicsLayer { alpha }` + `background`：后者会给
 * 一整块**全屏**图层开离屏缓冲（alpha < 1 必须离屏合成），每帧合成一张
 * 1080×2400 的位图，只为了把一层 6% 的黑淡进来。这里直接把 alpha 乘进颜色，
 * 不产生任何离屏层。
 */
fun Modifier.scrimFade(state: OverlayMotionState, baseAlpha: Float): Modifier = drawBehind {
    // 颜色里直接带 alpha，不产生离屏层
    drawRect(color = Color.Black, alpha = (baseAlpha * state.alpha.value).coerceIn(0f, 1f))
}
