package com.yfantasyxx.ui.workout

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.yfantasyxx.data.db.SessionExerciseWithSets
import com.yfantasyxx.data.db.SetLog
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 训练页里的一个动作行：拖动把手 + 动作卡片。
 *
 * 手势与视觉都在这一个文件里，是因为它们必须严格配合：
 * - 装手势的节点【绝对不能】被平移（见下方长注释）；
 * - 会移动的卡片把动画值放在 graphicsLayer 里读取（绘制阶段，不触发组合）。
 */
@Composable
internal fun WorkoutExerciseRow(
    item: SessionExerciseWithSets,
    index: Int,
    lastSets: List<SetLog>,
    unitLabel: String,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    drag: WorkoutDragState,
    headerCount: Int,
    exerciseCount: Int,
    onUpdateSet: (SetLog, Float, Int) -> Unit,
    onToggleSet: (SetLog) -> Unit,
    onAddSet: () -> Unit,
    onRemoveExercise: () -> Unit,
    onDeleteSet: (SetLog) -> Unit,
    onTogglePr: (SetLog) -> Unit,
    onCopyPreviousSet: () -> Unit,
    onReplaceExercise: () -> Unit,
    onToggleSuperset: () -> Unit,
    onOpenPlateCalculator: () -> Unit,
    canSupersetWithNext: Boolean,
    progressionMode: com.yfantasyxx.data.ProgressionMode,
    onApplyProgression: (Float, Int) -> Unit,
) {
    val id = item.sessionExercise.id
    val isDragging = drag.draggingId == id
    val primaryColor = MiuixTheme.colorScheme.primary

    // pointerInput(id) 只在 id 变化时重建手势块。
    // 换序后 id 不变、下标变了，手势块里捕获的 index 会一直是旧值 ——
    // 让位范围算错、落点落错位置，表现就是「拖动动作和动画不一致」。
    // 用 rememberUpdatedState 保证手势里读到的永远是当前值。
    val liveIndex by rememberUpdatedState(index)
    val liveHeader by rememberUpdatedState(headerCount)
    val liveCount by rememberUpdatedState(exerciseCount)

    // 实时让位：拖动到哪一格，那一段就先滑开一个卡片高的空隙。
    // 只做视觉平移，不动列表顺序，所以不会引发布局反馈环。
    val shiftTarget = drag.shiftTargetFor(index)
    val shiftAnim = animateFloatAsState(
        targetValue = shiftTarget,
        animationSpec = tween(160),
        label = "dragShift",
    )
    // 关键：这里【不要】读 shiftAnim.value。
    // 在组合阶段读 = 让位动画每帧都让整张卡片重新组合一次。
    // 松手那一帧本来就要换序 + 全列表重新测量，再叠加每个 item
    // 的重组，这一帧必然掉帧，肉眼就是「整页抽一下」。
    // 把 State 本身带进 graphicsLayer，在绘制阶段才读值：
    // 让位动画只失效绘制层，不触发组合与测量。
    // 拖动结束时（fromIndex = -1）直接置空，让位瞬间归零——
    // 此刻布局已经换成新顺序，由布局接管位置，不能回弹一次。
    val shiftState: State<Float>? = if (drag.fromIndex >= 0) shiftAnim else null

    // 「被拎起来」的放大和阴影也要做成动画。
    // 原来松手瞬间 scale 从 1.02 硬跳回 1、阴影 12px 直接消失，
    // 卡片边缘一下子收缩回去，看起来就像整页抽了一下。
    val liftAnim = animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = tween(120),
        label = "dragLift",
    )

    // 外层 Box 只用来承接手势，它自己【永远不平移】。
    //
    // 关键原因（拖动抽搐的真正根因）：
    // Compose 的 graphicsLayer 平移会参与指针坐标换算——
    // NodeCoordinator.fromParentPosition 里对 layer 做了逆变换
    // （layer.mapOffset(position, inverse = true)）。
    // 所以只要 pointerInput 挂在被平移的节点内部，手指的局部坐标
    // 就会跟着卡片一起移动，算出来的位移等于「自己减自己」，
    // 每帧来回折返 → 卡片原地高频抖动。
    // 把手指监听放到不被平移的外层，坐标恒定，位移 1:1 跟手。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f),
    ) {
        // 真正会移动的卡片
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    // 下面这些值全部在【绘制阶段】读取：
                    // graphicsLayer 的 lambda 由 draw 阶段重新执行，
                    // 不触发组合、不触发测量。所以拖动时每帧只更新图层。
                    val lifted = liftAnim.value
                    // 被拖的卡片跟手，其它卡片让位
                    translationY = if (isDragging) {
                        drag.offsetY
                    } else {
                        shiftState?.value ?: 0f
                    }
                    // 阴影和放大跟着 liftAnim 一起淡入淡出，松手时不会「啪」一下收回去
                    shadowElevation = 12f * lifted
                    val s = 1f + 0.02f * lifted
                    scaleX = s
                    scaleY = s
                }
                // 拖动高亮：drawBehind 画在内容下层，不参与测量，不会引发布局反馈环。
                // 透明度跟 liftAnim 一起淡出，松手时不会硬切掉。
                .drawBehind {
                    val a = liftAnim.value
                    if (a > 0.01f) {
                        val stroke = 3.dp.toPx()
                        val inset = stroke / 2f
                        drawRoundRect(
                            color = primaryColor.copy(alpha = a),
                            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                            size = androidx.compose.ui.geometry.Size(
                                size.width - stroke,
                                size.height - stroke
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                16.dp.toPx()
                            ),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                        )
                    }
                },
            verticalAlignment = Alignment.Top,
        ) {
            // 视觉把手：跟着卡片一起走
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .heightIn(min = 72.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "拖动排序",
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                )
            }

            ExerciseBlock(
                item = item,
                lastSets = lastSets,
                unitLabel = unitLabel,
                collapsed = collapsed,
                onToggleCollapse = onToggleCollapse,
                supersetGroup = item.sessionExercise.supersetGroup,
                canSupersetWithNext = canSupersetWithNext,
                modifier = Modifier.weight(1f),
                onUpdateSet = onUpdateSet,
                onToggleSet = onToggleSet,
                onAddSet = onAddSet,
                onRemoveExercise = onRemoveExercise,
                onDeleteSet = onDeleteSet,
                onTogglePr = onTogglePr,
                onCopyPreviousSet = onCopyPreviousSet,
                onReplaceExercise = onReplaceExercise,
                onToggleSuperset = onToggleSuperset,
                onOpenPlateCalculator = onOpenPlateCalculator,
                progressionMode = progressionMode,
                onApplyProgression = onApplyProgression,
            )
        }

        // 手势层：透明、位置恒定，只覆盖左上角把手区域。
        // 它不参与平移，所以 change.position 就是稳定的屏幕坐标。
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(40.dp)
                .height(72.dp)
                .pointerInput(id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 立即占住手势，避免 LazyColumn 从把手上开始滚
                        down.consume()
                        val slop = viewConfiguration.touchSlop
                        var started = false
                        // 落点：每帧重算，默认就是「回原位」
                        var target = liveIndex
                        // 起手那一刻的行几何快照，整场手势只读它（自动滚动时重拍）
                        var snap: DragSnapshot? = null

                        // 自动滚动用：手指在视口里的初始位置与可视范围
                        var pointerStart = 0f
                        var viewportTop = 0f
                        var viewportBottom = 0f
                        // 触发区约一张卡片高，最高速 9dp/帧（≈540dp/s）
                        val zone = 88.dp.toPx()
                        val maxSpeed = 9.dp.toPx()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (!change.pressed) break
                            change.consume()
                            // 相对按下点的绝对位移：不做增量累加，天然没有累积漂移
                            val total = change.position.y - down.position.y
                            if (!started) {
                                if (abs(total) < slop) continue
                                // 拍快照：整场手势不再读实时布局。
                                // 拿不到几何（比如卡片刚好被回收）就放弃这次拖动。
                                val s = buildDragSnapshot(
                                    layout = drag.listState.layoutInfo,
                                    headerCount = liveHeader,
                                    selfIndex = liveIndex,
                                    exerciseCount = liveCount,
                                ) ?: break
                                snap = s
                                started = true
                                // 进入拖动态：记下起点和卡片高度，相邻卡片开始让位
                                drag.begin(id, liveIndex, s)
                                drag.haptics.performHapticFeedback(
                                    HapticFeedbackType.TextHandleMove
                                )
                                val layout = drag.listState.layoutInfo
                                pointerStart = s.selfTopInViewport + down.position.y
                                viewportTop = layout.viewportStartOffset.toFloat()
                                viewportBottom = layout.viewportEndOffset.toFloat()
                            }
                            drag.move(total)

                            // ---- 只计算目标位，不改列表顺序 ----
                            // 拖动中改顺序 → 重新布局 → 坐标变化 →
                            // 又触发换位判断，会形成闪烁反馈环。
                            //
                            // 判据：有多少行的中心在我中心之上，我就排第几位。
                            // 这一条同时覆盖向上和向下，不需要分支，也不会差一格。
                            val s = snap ?: break
                            target = s.targetFor(liveIndex, drag.cardCenterY)
                            // 落点变了就同步给相邻卡片，让它们滑开空隙
                            if (target != drag.targetIndex) {
                                drag.targetIndex = target
                            }

                            // ---- 贴边自动滚动 ----
                            // 手势只负责「想滚多快」，真正的滚动在屏幕的
                            // LaunchedEffect 里按帧执行（受限协程作用域里不能挂起）。
                            drag.autoScrollSpeed = autoScrollSpeed(
                                pointerY = pointerStart + total,
                                top = viewportTop,
                                bottom = viewportBottom,
                                zone = zone,
                                maxSpeed = maxSpeed,
                            )
                        }

                        // ---- 松手：只下单，动画与落库由屏幕的 LaunchedEffect 执行 ----
                        // 先把自动滚动停掉：落位动画要滑进「此刻」的空隙，
                        // 如果这 160ms 里列表还在滚，空隙位置会一路跑掉。
                        drag.autoScrollSpeed = 0f
                        val count = liveCount
                        val moved = started && liveIndex in 0 until count &&
                            target in 0 until count && target != liveIndex
                        when {
                            !started -> drag.reset()
                            moved -> {
                                // 滑进空隙的距离 = 中间被越过卡片的高度之和
                                val gap = snap?.gapFor(liveIndex, target) ?: 0f
                                drag.pendingDrop = DropPlan(liveIndex, target, gap)
                            }
                            else -> {
                                // 没有换位：滑回原位
                                drag.pendingDrop = DropPlan(liveIndex, liveIndex, 0f)
                            }
                        }
                    }
                },
        )
    }
}
