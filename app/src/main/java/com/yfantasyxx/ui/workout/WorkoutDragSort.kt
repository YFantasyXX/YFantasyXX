package com.yfantasyxx.ui.workout

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.hapticfeedback.HapticFeedback

/**
 * 一次拖动落位计划。
 *
 * 手势回调（awaitEachGesture）是受限协程作用域，不能调用挂起函数，
 * 所以手势只把「落点 + 需要滑动的距离」写进这里，动画和落库交给 LaunchedEffect。
 */
internal class DropPlan(val from: Int, val to: Int, val gap: Float)

/**
 * 快照里的一行动作。
 *
 * [centerRel] 是「相对被拖卡片顶部」的中心偏移，不是绝对屏幕坐标。
 * 用相对值的原因：休息倒计时卡可能在拖动途中出现或消失，整列会一起平移，
 * 绝对坐标会全部失效；相对值在整体平移下不变，判定不受影响。
 */
internal class DragRow(
    val actionIndex: Int,
    val centerRel: Float,
    val height: Float,
)

/**
 * 起手那一刻的行几何快照。
 *
 * 为什么必须快照、而不是每帧读 `listState.layoutInfo`：
 * 拖动过程中只做 graphicsLayer 平移、不改列表顺序，布局本来就是冻结的，
 * 一份快照足够用整场手势。反过来每帧读实时布局，会把「自己刚做的平移和
 * 让位」间接读回来，容易形成「读布局 → 改视觉 → 再读」的反馈环。
 *
 * [baseIndex] 是可视区第一个动作下标。列表滚到中间时，
 * 「我上面有几行」只能换算成相对下标，必须加回这个基址才对。
 */
internal class DragSnapshot(
    private val rows: List<DragRow>,
    val selfHeight: Float,
    private val baseIndex: Int,
) {
    /**
     * 落点下标：有多少行的中心在我中心之上，我就排第几位。
     * 这一条同时覆盖向上和向下两个方向，不需要分支，也不会差一格。
     */
    fun targetFor(from: Int, dy: Float): Int {
        if (rows.isEmpty()) return from
        val midRel = selfHeight / 2f + dy
        val above = rows.count { it.actionIndex != from && it.centerRel < midRel }
        return (baseIndex + above).coerceIn(baseIndex, rows.last().actionIndex)
    }

    /**
     * 松手后卡片需要滑进空隙的距离：
     * 向下拖 = 被越过卡片高度之和，向上拖 = 其负值。
     */
    fun gapFor(from: Int, to: Int): Float = when {
        to > from -> rows.filter { it.actionIndex in (from + 1)..to }
            .map { it.height }.sum()

        to < from -> -rows.filter { it.actionIndex in to until from }
            .map { it.height }.sum()

        else -> 0f
    }
}

/**
 * 从当前布局拍一份快照。拿不到被拖卡片的几何时返回 null（放弃这次拖动）。
 *
 * [headerCount]：动作列表前面还有「错误提示卡 / 休息倒计时卡」，
 * `visibleItemsInfo.index` 是整条 LazyColumn 的下标，减去它才是动作下标。
 */
internal fun buildDragSnapshot(
    layout: LazyListLayoutInfo,
    headerCount: Int,
    selfIndex: Int,
    exerciseCount: Int,
): DragSnapshot? {
    val self = layout.visibleItemsInfo
        .firstOrNull { it.index - headerCount == selfIndex }
        ?: return null
    val selfTop = self.offset.toFloat()
    val rows = layout.visibleItemsInfo.mapNotNull { info ->
        val idx = info.index - headerCount
        if (idx < 0 || idx >= exerciseCount) return@mapNotNull null
        DragRow(
            actionIndex = idx,
            centerRel = info.offset + info.size / 2f - selfTop,
            height = info.size.toFloat(),
        )
    }.sortedBy { it.actionIndex }
    if (rows.isEmpty()) return null
    return DragSnapshot(
        rows = rows,
        selfHeight = self.size.toFloat(),
        baseIndex = rows.first().actionIndex,
    )
}

/**
 * 拖动排序的全部瞬时状态。
 *
 * 抽成一个类而不是散在屏幕里十几个 remember：
 * 屏幕的组合函数只负责「渲染列表」，拖动的相位（未拖 / 拖动中 / 落位中）
 * 收敛在这里。字段都是 Compose 状态，读它们的 graphicsLayer 会正常失效。
 */
@Stable
internal class WorkoutDragState(
    val listState: LazyListState,
    val haptics: HapticFeedback,
) {
    /** 正在拖动的动作 id（null = 没在拖） */
    var draggingId by mutableStateOf<Long?>(null)

    /** 被拖动作原来的下标（-1 = 没有拖动在进行） */
    var fromIndex by mutableIntStateOf(-1)

    /** 当前落点下标；相邻卡片据此实时让出空隙 */
    var targetIndex by mutableIntStateOf(-1)

    /** 被拖卡片高度（px）：相邻卡片让位的高度 */
    var cardHeight by mutableFloatStateOf(0f)

    /** 拖动位移（px）。手势里直接写，落位动画由屏幕的 LaunchedEffect 驱动 */
    var offsetY by mutableFloatStateOf(0f)

    /** 一次落位计划；非空即触发屏幕的落位流程 */
    var pendingDrop by mutableStateOf<DropPlan?>(null)

    /**
     * 相邻卡片要滑开的距离。
     *
     * 注意：读这个值的 graphicsLayer 只在绘制阶段执行，
     * 所以拖动过程中改变它不会让整张卡片重新组合。
     */
    fun shiftTargetFor(index: Int): Float = when {
        fromIndex < 0 || index == fromIndex -> 0f
        // 向下拖：起点之后到落点之间整体往上让
        targetIndex > fromIndex && index > fromIndex && index <= targetIndex -> -cardHeight
        // 向上拖：落点到起点之间整体往下让
        targetIndex < fromIndex && index >= targetIndex && index < fromIndex -> cardHeight
        else -> 0f
    }

    /** 清掉拖动相位。不动 [offsetY]：落位动画还要靠它把卡片滑到空位 */
    fun endPhase() {
        fromIndex = -1
        targetIndex = -1
    }

    /** 彻底清空（落位动画播完、布局接管之后） */
    fun reset() {
        draggingId = null
        fromIndex = -1
        targetIndex = -1
        offsetY = 0f
    }
}

/**
 * 手动补间：把视觉位移从 [from] 平滑推到 [to]。
 * 用 smoothstep 曲线（先慢—中快—再慢），比线性自然。
 */
internal suspend fun slideTo(
    from: Float,
    to: Float,
    durationMs: Long,
    onUpdate: (Float) -> Unit,
) {
    if (from == to) return
    val startNanos = withFrameNanos { it }
    val totalNanos = durationMs * 1_000_000f
    while (true) {
        val now = withFrameNanos { it }
        val p = ((now - startNanos) / totalNanos).coerceIn(0f, 1f)
        val eased = p * p * (3f - 2f * p)
        onUpdate(from + (to - from) * eased)
        if (p >= 1f) break
    }
}
