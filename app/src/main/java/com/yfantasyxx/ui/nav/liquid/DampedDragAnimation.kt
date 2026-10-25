/*
 * 移植自 HyperNavBar（Apache License 2.0），原始实现见
 * https://github.com/Kyant0/AndroidLiquidGlass
 *
 * 本项目只保留底栏需要的部分；包名、尺寸与配色按本应用改写。
 * 上游版权：Copyright 2026, compose-miuix-ui contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yfantasyxx.ui.nav.liquid

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 药丸的位置 / 按压状态机。
 *
 * 三件事同时在做，缺一件就不像「液态玻璃」：
 *
 * 1. **位置带阻尼**——拖动时不是 `snapTo` 死跟手指，而是每帧把弹簧目标设成手指位置，
 *    药丸会慢手指一点点，抬手时自然收住，像一坨有质量的液体；
 * 2. **按下鼓起**——手指一接触就 `scale` 到 [pressedScale]，
 *    并且 [pressProgress] 从 0 走到 1，用来驱动折射强度、高光和内阴影；
 * 3. **甩动速度**——把速度归一化后交给调用方做横向挤压（甩得越快拉得越长）。
 *
 * 位置值用「第几格」的浮点数表示（1.5f = 一二格之间），不是像素，
 * 所以底栏宽度变化时不用换算。
 */
internal class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    initialValue: Float,
    val valueRange: ClosedRange<Float>,
    private val visibilityThreshold: Float,
    private val initialScale: Float,
    private val pressedScale: Float,
    private val onDragStarted: DampedDragAnimation.(touchX: Float) -> Unit,
    private val onDragStopped: DampedDragAnimation.(dragged: Boolean) -> Unit,
    private val onDrag: DampedDragAnimation.(dragAmount: Float) -> Unit,
) {

    // stiffness 1000 / 阻尼 1.0：跟手很紧但不过冲，最后几像素是自己「吸」过去的
    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private var pressJob: Job? = null
    private var releaseJob: Job? = null
    private val velocityTracker = VelocityTracker()

    /** 当前落在第几格（浮点） */
    val value: Float get() = valueAnimation.value

    /** 弹簧的目标格；手指拖到哪就设到哪 */
    val targetValue: Float get() = valueAnimation.targetValue

    /** 0 = 松开，1 = 完全按下 */
    val pressProgress: Float get() = pressProgressAnimation.value

    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value

    /** 归一化的甩动速度，正负表示方向 */
    val velocity: Float get() = velocityAnimation.value

    /**
     * 手势层。刻意**不消费** down：底栏下面的 tab 点击要照常生效，
     * 只有真正开始拖动后 [positionChange] 才会消费 move，把点击取消掉。
     */
    val modifier: Modifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            // Initial pass 先拿一次，避免被子节点的 clickable 先消费掉
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val down = awaitFirstDown(requireUnconsumed = false)
            onDragStarted(down.position.x)
            press()

            var pointerId = down.id
            val startX = down.position.x
            val touchSlop = viewConfiguration.touchSlop
            // 先不动，超过 touchSlop 才承认是拖动。
            // 否则按下时手指的轻微抖动就会消费 move，把下面的点击判定搞掉。
            var dragging = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (change.changedToUpIgnoreConsumed()) {
                    // 轻点（没超过 touchSlop）：把 up 吃掉，别再让底下的
                    // selectable 触发一次点击——切页改由 onDragStopped(false)
                    // 按按下位置自己算，两条路同时响应会导航两次。
                    if (!dragging) change.consume()
                    break
                }
                if (!dragging && abs(change.position.x - startX) > touchSlop) dragging = true
                if (dragging) {
                    val delta = change.positionChange()
                    if (delta.x != 0f) onDrag(delta.x)
                }
            }
            onDragStopped(dragging)
            release()
        }
    }

    fun press() {
        releaseJob?.cancel()
        pressJob?.cancel()
        velocityTracker.resetTracking()
        pressJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        releaseJob?.cancel()
        releaseJob = animationScope.launch {
            withFrameMillis { }
            // 先等位置弹簧走完再缩小，否则药丸会在半路「瘪」下去
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .first { abs(it - valueAnimation.targetValue) < threshold }
            }
            launch { pressProgressAnimation.animateTo(0f, pressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    /** 拖动中：把目标设成手指算出来的格子，弹簧自己追过去 */
    fun updateValue(value: Float) {
        val target = value.coerceIn(valueRange)
        animationScope.launch {
            valueAnimation.animateTo(target, valueAnimationSpec) { updateVelocity() }
        }
    }

    /** 程序化位移（点 tab / 手指松开落位）；会先鼓起再落下，和手指操作同一套视觉 */
    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val target = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(target, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(SystemClock.elapsedRealtime(), Offset(value, 0f))
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
        val target = velocityTracker.calculateVelocity().x / span
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            velocityAnimation.snapTo(target)
        }
    }
}
