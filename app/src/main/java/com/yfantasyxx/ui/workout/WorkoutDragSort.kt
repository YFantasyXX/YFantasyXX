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
 * [centerY] 是**视口坐标**（`info.offset + size/2`），不是相对被拖卡片的偏移。
 *
 * 为什么从「相对被拖卡片」改成「视口坐标」：
 * 自动滚动实现后，被拖卡片的布局槽位会随着列表滚出视口，
 * 相对坐标的基准点就消失了；而视口坐标的基准点永远存在，
 * 而且列表滚动时整列一起移动，比较关系依然成立。
 */
internal class DragRow(
    val actionIndex: Int,
    val centerY: Float,
    val height: Float,
)

/**
 * 起手那一刻（以及每次自动滚动后）的行几何快照。
 *
 * 拖动过程中只做 graphicsLayer 平移、不改列表顺序，布局本来是冻结的，
 * 一份快照足够用整场手势。唯一的例外是自动滚动：那时必须重拍，
 * 否则滚出视口的行不在快照里，落点会算不到更远的位置。
 */
internal class DragSnapshot(
    private val rows: List<DragRow>,
    val selfHeight: Float,
    private val baseIndex: Int,
    /** 被拖卡片当时在视口里的顶部位置，用来算初始中心 */
    val selfTopInViewport: Float,
) {
    /** 视口顶 / 底部（LazyColumn 的可视范围） */
    val firstRowTop: Float get() = rows.first().centerY - rows.first().height / 2f
    val lastRowBottom: Float get() = rows.last().centerY + rows.last().height / 2f

    /**
     * 落点下标：有多少行的中心在卡片中心之上，我就排第几位。
     * 这一条同时覆盖向上和向下两个方向，不需要分支，也不会差一格。
     */
    fun targetFor(from: Int, cardCenterY: Float): Int {
        if (rows.isEmpty()) return from
        val above = rows.count { it.actionIndex != from && it.centerY < cardCenterY }
        return (baseIndex + above).coerceIn(baseIndex, rows.last().actionIndex)
    }

    /**
     * 松手后卡片需要滑进空隙的距离：
     * 向下拖 = 被越过卡片高度之和，向上拖 = 其负值。
     *
     * 这个值同时也是「卡片视觉位置」与「新槽位」之间的差：
     * 动画把这个差值挪完，换序那一帧清位移就不会跳。
     */
    fun gapFor(from: Int, to: Int): Float = when {
        to > from -> rows.filter { it.actionIndex in (from + 1)..to }
            .map { it.height }.sum()

        to < from -> -rows.filter { it.actionIndex in to until from }
            .map { it.height }.sum()

        else -> 0f
    }
    /** 整份快照平移（列表滚动后兜底用） */
    fun shiftBy(dy: Float): DragSnapshot = DragSnapshot(
        rows = rows.map { DragRow(it.actionIndex, it.centerY + dy, it.height) },
        selfHeight = selfHeight,
        baseIndex = baseIndex,
        selfTopInViewport = selfTopInViewport + dy,
    )
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
    val rows = layout.visibleItemsInfo.mapNotNull { info ->
        val idx = info.index - headerCount
        if (idx < 0 || idx >= exerciseCount) return@mapNotNull null
        DragRow(
            actionIndex = idx,
            centerY = info.offset + info.size / 2f,
            height = info.size.toFloat(),
        )
    }.sortedBy { it.actionIndex }
    if (rows.isEmpty()) return null
    return DragSnapshot(
        rows = rows,
        selfHeight = self.size.toFloat(),
        baseIndex = rows.first().actionIndex,
        selfTopInViewport = self.offset.toFloat(),
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

    /**
     * 卡片中心在视口里的 Y 坐标（px）。
     *
     * 落点判定只看这一个数：手指不动、列表自动滚动时它保持不变，
     * 而各行的 [DragRow.centerY] 会随滚动整体位移，比较结果自然是「继续往下」。
     */
    var cardCenterY by mutableFloatStateOf(0f)

    /** 自动滚动速度（px/帧）：负值向上、正值向下、0 表示不滚 */
    var autoScrollSpeed by mutableFloatStateOf(0f)

    /** 当前几何快照（自动滚动后会重拍） */
    var snapshot: DragSnapshot? = null

    /** 一次落位计划；非空即触发屏幕的落位流程 */
    var pendingDrop by mutableStateOf<DropPlan?>(null)

    /** 起手时卡片中心的视口坐标；[move] 以它为基准，不受后续滚动影响 */
    private var startCenterY = 0f

    /** 起手：记下起点、卡片高度，相邻卡片开始让位 */
    fun begin(id: Long, index: Int, snap: DragSnapshot) {
        snapshot = snap
        fromIndex = index
        targetIndex = index
        cardHeight = snap.selfHeight
        // 基准一旦确定就不再跟随快照变化：
        // 自动滚动后 selfTopInViewport 会变，用新值 + 手指位移会算错中心
        startCenterY = snap.selfTopInViewport + snap.selfHeight / 2f
        cardCenterY = startCenterY
        offsetY = 0f
        draggingId = id
    }

    /** 手指移动：位移与落点一起更新 */
    fun move(dy: Float) {
        offsetY = dy
        cardCenterY = startCenterY + dy
        val snap = snapshot ?: return
        targetIndex = snap.targetFor(fromIndex, cardCenterY)
    }

    /**
     * 自动滚动一帧之后对齐。
     *
     * [scrolled] 是这一帧真正滚动的像素（向下为正）。
     * offsetY 必须同步加上它：槽位向上移动了多少，卡片相对槽位的位移就要补多少，
     * 否则卡片会跟着列表一起跑掉，手指底下就空了。
     * 卡片中心在视口里不动，所以 cardCenterY 不调整 —— 这正是「继续拖远」的来源。
     */
    fun onAutoScrolled(scrolled: Float) {
        offsetY += scrolled
        val snap = snapshot ?: return
        targetIndex = snap.targetFor(fromIndex, cardCenterY)
    }

    /**
     * 重拍快照（列表滚动后视野变了，必须换一份几何）。
     *
     * 被拖卡片的**槽位**可能已经滚出视口（卡片本身还挂在手指下），
     * 这时拍不出来，退化成「把旧快照整体平移」——
     * 相对关系仍然正确，只是看得没有那么远，比每帧读布局出错好。
     */
    fun refreshSnapshot(layout: LazyListLayoutInfo, headerCount: Int, exerciseCount: Int, scrolled: Float) {
        val fresh = buildDragSnapshot(layout, headerCount, fromIndex, exerciseCount)
            ?: snapshot?.shiftBy(-scrolled)
            ?: return
        snapshot = fresh
        targetIndex = fresh.targetFor(fromIndex, cardCenterY)
    }

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
        autoScrollSpeed = 0f
    }

    /** 彻底清空（落位动画播完、布局接管之后） */
    fun reset() {
        draggingId = null
        fromIndex = -1
        targetIndex = -1
        offsetY = 0f
        autoScrollSpeed = 0f
        snapshot = null
    }
}

/**
 * 贴边自动滚动：越靠边越快，离开边缘立刻停。
 *
 * [pointerY] 手指在视口里的位置；[top] / [bottom] 是 LazyColumn 的可视范围；
 * [zone] 是触发区高度（约一张卡片），[maxSpeed] 是全速时的每帧像素。
 * 用线性比例而不是固定速度：贴边 2dp 就想微调、贴到底就想狂滚，
 * 同一个档位满足不了这两种意图。
 */
internal fun autoScrollSpeed(
    pointerY: Float,
    top: Float,
    bottom: Float,
    zone: Float,
    maxSpeed: Float,
): Float = when {
    zone <= 0f -> 0f
    pointerY < top + zone -> {
        val t = ((top + zone - pointerY) / zone).coerceIn(0f, 1f)
        -maxSpeed * t
    }

    pointerY > bottom - zone -> {
        val t = ((pointerY - (bottom - zone)) / zone).coerceIn(0f, 1f)
        maxSpeed * t
    }

    else -> 0f
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
