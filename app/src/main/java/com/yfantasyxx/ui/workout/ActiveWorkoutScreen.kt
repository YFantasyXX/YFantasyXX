package com.yfantasyxx.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import com.yfantasyxx.ui.components.AppDialog
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.yfantasyxx.data.db.SessionExerciseWithSets
import com.yfantasyxx.data.db.SetLog
import com.yfantasyxx.data.prefs.AppSettings
import com.yfantasyxx.data.prefs.SettingsRepository
import com.yfantasyxx.data.prefs.WeightUnit
import com.yfantasyxx.ui.components.NumberStepper
import com.yfantasyxx.ui.components.SectionHeader
import com.yfantasyxx.ui.rememberAppContainer
import kotlinx.coroutines.flow.first
import com.yfantasyxx.ui.components.ActionText
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Close
import com.yfantasyxx.ui.components.RestSecondsPickerDialog
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material.icons.filled.Menu

@Composable
fun ActiveWorkoutScreen(onBack: () -> Unit) {
    val container = rememberAppContainer()
    val vm: WorkoutViewModel = viewModel(factory = WorkoutViewModel.factory(container))
    val exercises by vm.exercises.collectAsState()
    val elapsed by vm.elapsedSeconds.collectAsState()
    val restTimer by vm.restTimer.collectAsState()
    val lastPerformance by vm.lastPerformance.collectAsState()
    val error by vm.error.collectAsState()
    val currentExerciseId by vm.currentExerciseId.collectAsState()
    val restFinished by vm.restFinished.collectAsState()
    val defaultSessionName by vm.defaultSessionName.collectAsState()

    // 训练内动作拖动排序
    val haptics = LocalHapticFeedback.current
    // 正在拖动的动作 id（null = 没在拖）
    var draggingId by remember { mutableStateOf<Long?>(null) }
    // 被拖动作原来的下标（-1 = 没有拖动在进行）
    var dragFromIndex by remember { mutableIntStateOf(-1) }
    // 当前落点下标：相邻卡片据此实时让出空隙
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    // 被拖卡片高度（px）：相邻卡片让位的高度
    var dragHeight by remember { mutableFloatStateOf(0f) }
    // 拖动位移（px）。手势里直接写，落位动画由下面的 LaunchedEffect 驱动
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    // 一次「落位计划」：手势只负责下单，动画和落库放到普通协程里做
    // （awaitEachGesture 是受限作用域，不能调挂起函数）
    var pendingDrop by remember { mutableStateOf<DropPlan?>(null) }
    // 拖动落位后的临时顺序（id 列表）。
    // 为什么要它：数据库顺序更新是异步的（StateFlow → collectAsState），
    // 如果等它回来再清视觉位移，中间必然有一帧「新布局 + 旧位移」的错位闪烁。
    // 用 Compose 状态立即换序，就能让「换序」和「清位移」落在同一帧。
    var localOrderIds by remember { mutableStateOf<List<Long>?>(null) }
    val displayExercises = remember(exercises, localOrderIds) {
        val ids = localOrderIds
        if (ids == null) {
            exercises
        } else {
            val byId = exercises.associateBy { it.sessionExercise.id }
            // 顺序按 ids 走，ids 里没有的（新增动作等）接在后面
            ids.mapNotNull { byId[it] } +
                exercises.filter { it.sessionExercise.id !in ids }
        }
    }
    // 每个动作的收纳状态（收起组数，方便拖动排序）
    val collapsedIds = remember { mutableStateMapOf<Long, Boolean>() }
    // 拖动期间要拿到最新的动作顺序（而不是手势启动那一刻的快照）
    val latestExercises by rememberUpdatedState(exercises)
    // 列表状态：拖动时用它读各 item 的真实布局位置（官方数据源，比自记坐标可靠）
    val listState = rememberLazyListState()
    // 动作列表之前还有几个 item（错误提示卡 / 休息倒计时卡）。
    // visibleItemsInfo.index 是「整个 LazyColumn 的下标」，减掉它才是动作下标。
    // 若以后在动作列表前面再加 item，这里必须同步 +1。
    val headerItemCount = (if (error != null) 1 else 0) +
        (if (restTimer.running) 1 else 0)
    // 休息倒计时卡 / 错误卡会随状态出现、消失，动作下标整体位移一位。
    // 手势块捕获的是组合时的快照，拖动过程中头部 item 一变，算出的下标就全错位
    // （落点错、让位范围错）——所以用 State 读最新值。
    val liveHeaderCount by rememberUpdatedState(headerItemCount)
    var showExercisePicker by remember { mutableStateOf(false) }
    var showFinishDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showRestPicker by remember { mutableStateOf(false) }
    var showAddExercise by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.ensureSession()
        // 退出过 App 的话，恢复仍在跑的休息倒计时
        vm.restoreRestTimer()
    }

    // 拖动落位：滑进空隙 → 同一帧换序并清位移 → 等布局接管 → 撤掉拎起态 → 落库
    LaunchedEffect(pendingDrop) {
        val plan = pendingDrop ?: return@LaunchedEffect
        // 1) 先平滑滑进已经让出来的空隙
        slideTo(dragOffsetY, plan.gap, 160L) { dragOffsetY = it }

        // 2) 换序 + 清位移 + 收回让位，必须在同一帧、同一个同步块里写完。
        //    这三件事分开到两帧，中间那帧就是「新布局 + 旧位移」或者
        //    「旧布局 + 新位移」，卡片会猛地跳一格。
        val moved = plan.to != plan.from
        if (moved) {
            val ids = latestExercises.map { it.sessionExercise.id }.toMutableList()
            ids.add(plan.to, ids.removeAt(plan.from))
            localOrderIds = ids
        }
        dragFromIndex = -1
        dragTargetIndex = -1
        dragOffsetY = 0f

        // 3) 落库（异步，界面已用临时顺序顶上了）
        if (moved) {
            vm.moveExerciseLocal(plan.from, plan.to)
            vm.persistExerciseOrder()
        }

        // 4) 拎起态（放大 / 阴影 / 描边）晚两帧再撤。
        //    换序后 item 的组合会重建（key 换了槽位），如果同一帧就把
        //    scale 从 1.02 清成 1、阴影直接抹掉，卡片边缘会硬缩一下，
        //    看起来就是「整页抽一下」。等布局换完再撤，
        //    liftAnim 会用 120ms 平滑落回 1.0，视觉上是「放下去」。
        withFrameNanos { }
        withFrameNanos { }
        draggingId = null

        pendingDrop = null
    }

    // 数据库顺序追上来之后，撤掉临时顺序
    LaunchedEffect(exercises, localOrderIds) {
        val ids = localOrderIds ?: return@LaunchedEffect
        if (ids == exercises.map { it.sessionExercise.id }) {
            localOrderIds = null
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = if (elapsed == 0) "待开始" else formatDuration(elapsed),
                subtitle = if (exercises.isEmpty()) {
                    "添加动作开始训练"
                } else if (elapsed == 0) {
                    "完成第一组后开始计时 · ${exercises.size} 个动作"
                } else {
                    "${exercises.size} 个动作"
                },
                navigationIcon = {
                    IconButton(onClick = { showDiscardDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "放弃",
                            tint = MiuixTheme.colorScheme.error,
                        )
                    }
                },
                actions = {
                    Button(onClick = { showFinishDialog = true }) {
                        Text("完成", style = MiuixTheme.textStyles.button)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .imePadding()
        ) {
            if (restFinished) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .zIndex(2f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MiuixTheme.colorScheme.primary)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "休息结束，可以开始下一组",
                            style = MiuixTheme.textStyles.main,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        ActionText(
                            text = "知道了",
                            onClick = { vm.dismissRestFinished() },
                            color = MiuixTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                // 操作异常提示（不闪退，显示原因）
                if (error != null) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "操作失败",
                                    style = MiuixTheme.textStyles.main,
                                    color = MiuixTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = error.orEmpty(),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Spacer(Modifier.height(4.dp))
                                ActionText(text = "知道了", onClick = vm::clearError)
                            }
                        }
                    }
                }

                if (restTimer.running) {                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = restTimer.label ?: "休息中",
                                    style = MiuixTheme.textStyles.subtitle,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { showRestPicker = true }
                                        .padding(horizontal = 10.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = "${restTimer.remainingSeconds}s",
                                        style = MiuixTheme.textStyles.headline1,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "调整",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    text = "已同步到通知栏，退出 App 也会继续计时",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ActionText(text = "+30s", onClick = { vm.extendRestTimer(30) })
                                    ActionText(text = "跳过", onClick = { vm.skipRestTimer() })
                                }
                            }
                        }
                    }
                }

                if (exercises.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 60.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "还没有添加动作\n点击右下角按钮添加",
                                style = MiuixTheme.textStyles.paragraph,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                // 注意：这里【故意不写 key = 动作 id】。
                //
                // LazyList 换序后会按「换序前第一个可见 item 的 key」重新锚定滚动位置
                // （LazyListScrollPosition.updateScrollPositionIfTheFirstItemWasMoved
                //  → findIndexByKey(lastKnownFirstItemKey) → setIndex）。
                // 用动作 id 当 key 时，把第 2 个拖到第 1 个，旧的第 1 个仍被钉在顶部，
                // 整个列表往下平移一张卡高 —— 新的第 1 个被挤到可视区之外，
                // 必须手动上滑才看得到。这就是「看不到新的第一个」的原因。
                //
                // 用默认的下标 key 时，换序只换内容不换 key，锚点下标不变，
                // 列表完全不滚动，位置保持原样，正好与拖动时的让位动画一致。
                // 卡片里依赖身份的本地状态都已经提到 collapsedIds / ViewModel，
                // 每行输入框是 remember(set.id)，换序不会串味。
                itemsIndexed(displayExercises) { index, item ->
                    val id = item.sessionExercise.id
                    val isDragging = draggingId == id
                    val isCollapsed = collapsedIds[id] == true
                    val primaryColor = MiuixTheme.colorScheme.primary

                    // pointerInput(id) 只在 id 变化时重建手势块。
                    // 换序后 id 不变、下标变了，手势块里捕获的 index 会一直是旧值 ——
                    // 让位范围算错、落点落错位置，表现就是「拖动动作和动画不一致」。
                    // 用 rememberUpdatedState 保证手势里读到的永远是当前下标。
                    val liveIndex by rememberUpdatedState(index)

                    // 实时让位：拖动到哪一格，那一段就先滑开一个卡片高的空隙。
                    // 只做视觉平移，不动列表顺序，所以不会引发布局反馈环。
                    val shiftTarget = when {
                        dragFromIndex < 0 || index == dragFromIndex -> 0f
                        dragTargetIndex > dragFromIndex &&
                            index > dragFromIndex && index <= dragTargetIndex -> -dragHeight
                        dragTargetIndex < dragFromIndex &&
                            index >= dragTargetIndex && index < dragFromIndex -> dragHeight
                        else -> 0f
                    }
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
                    // 拖动结束时（dragFromIndex = -1）直接置空，让位瞬间归零——
                    // 此刻布局已经换成新顺序，由布局接管位置，不能回弹一次。
                    val shiftState = if (dragFromIndex >= 0) shiftAnim else null

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
                    // 关键原因（这次抽搐的真正根因）：
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
                                    // 不触发组合、不触发测量。
                                    // 所以拖动时每帧只更新图层，整张卡片不重组。
                                    val lifted = liftAnim.value
                                    // 被拖的卡片跟手，其它卡片让位
                                    translationY = if (isDragging) {
                                        dragOffsetY
                                    } else {
                                        shiftState?.value ?: 0f
                                    }
                                    // 阴影和放大跟着 liftAnim 一起淡入淡出，
                                    // 松手时不会「啪」一下收回去
                                    shadowElevation = 12f * lifted
                                    val s = 1f + 0.02f * lifted
                                    scaleX = s
                                    scaleY = s
                                }
                                // 拖动高亮：drawBehind 画在内容下层，
                                // 不参与测量，不会引发布局反馈环。
                                // 透明度跟 liftAnim 一起淡出，松手时不会硬切掉。
                                .drawBehind {
                                    val a = liftAnim.value
                                    if (a > 0.01f) {
                                        val stroke = 3.dp.toPx()
                                        val inset = stroke / 2f
                                        drawRoundRect(
                                            color = primaryColor.copy(alpha = a),
                                            topLeft = androidx.compose.ui.geometry.Offset(
                                                inset, inset
                                            ),
                                            size = androidx.compose.ui.geometry.Size(
                                                size.width - stroke,
                                                size.height - stroke
                                            ),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                                16.dp.toPx()
                                            ),
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                stroke
                                            ),
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
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        .copy(alpha = 0.6f),
                                )
                            }

                            ExerciseBlock(
                                item = item,
                                lastSets = lastPerformance[item.sessionExercise.exerciseId]
                                    .orEmpty(),
                                unitLabel = WeightUnit.KG.label,
                                collapsed = isCollapsed,
                                onToggleCollapse = {
                                    collapsedIds[id] = !isCollapsed
                                },
                                modifier = Modifier.weight(1f),
                                onUpdateSet = { set, weight, reps ->
                                    vm.updateSet(set, weight, reps)
                                },
                                onToggleSet = vm::toggleSetComplete,
                                onAddSet = { vm.addSet(id) },
                                onRemoveExercise = { vm.removeExercise(id) },
                                onDeleteSet = vm::deleteSet,
                                onTogglePr = vm::toggleSetPr,
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
                                        // 起手那一刻的行几何快照，整场手势只读它
                                        var snap: DragSnapshot? = null

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes
                                                .firstOrNull { it.id == down.id }
                                                ?: break
                                            if (!change.pressed) break
                                            change.consume()
                                            // 相对按下点的绝对位移：不做增量累加，
                                            // 天然没有累积漂移
                                            val total =
                                                change.position.y - down.position.y
                                            if (!started) {
                                                if (abs(total) < slop) continue
                                                // 拍快照：整场手势不再读实时布局。
                                                // 拿不到几何（比如卡片刚好被回收）就放弃这次拖动。
                                                val s = buildDragSnapshot(
                                                    layout = listState.layoutInfo,
                                                    headerCount = liveHeaderCount,
                                                    selfIndex = liveIndex,
                                                    exerciseCount = latestExercises.size,
                                                ) ?: break
                                                snap = s
                                                started = true
                                                // 进入拖动态：记下起点和卡片高度，
                                                // 相邻卡片开始让位
                                                dragFromIndex = liveIndex
                                                dragTargetIndex = liveIndex
                                                dragHeight = s.selfHeight
                                                draggingId = id
                                                haptics.performHapticFeedback(
                                                    HapticFeedbackType.TextHandleMove
                                                )
                                            }
                                            dragOffsetY = total

                                            // ---- 只计算目标位，不改列表顺序 ----
                                            // 拖动中改顺序 → 重新布局 → 坐标变化 →
                                            // 又触发换位判断，会形成闪烁反馈环。
                                            //
                                            // 判据：有多少行的中心在我中心之上，我就排第几位。
                                            // 这一条同时覆盖向上和向下，不需要分支，也不会差一格。
                                            // 全部基于起手时的快照，不再读实时布局。
                                            val s = snap ?: break
                                            target = s.targetFor(liveIndex, total)
                                            // 落点变了就同步给相邻卡片，让它们滑开空隙
                                            if (target != dragTargetIndex) {
                                                dragTargetIndex = target
                                            }
                                        }

                                        // ---- 松手：只下单，动画与落库由 LaunchedEffect 执行 ----
                                        val count = latestExercises.size
                                        val moved = started && liveIndex in 0 until count &&
                                            target in 0 until count && target != liveIndex
                                        when {
                                            !started -> {
                                                draggingId = null
                                                dragFromIndex = -1
                                                dragTargetIndex = -1
                                                dragOffsetY = 0f
                                            }
                                            moved -> {
                                                // 滑进空隙的距离 = 中间被越过卡片的高度之和
                                                val gap = snap?.gapFor(liveIndex, target) ?: 0f
                                                pendingDrop = DropPlan(liveIndex, target, gap)
                                            }
                                            else -> {
                                                // 没有换位：滑回原位
                                                pendingDrop = DropPlan(liveIndex, liveIndex, 0f)
                                            }
                                        }
                                    }
                                },
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { showExercisePicker = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                        Text("  添加动作", style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }

        if (showRestPicker) {
            RestSecondsPickerDialog(
                initialSeconds = if (restTimer.totalSeconds > 0) {
                    restTimer.totalSeconds
                } else {
                    restTimer.remainingSeconds
                },
                showSaveAsDefault = currentExerciseId != null,
                onDismiss = { showRestPicker = false },
                onConfirm = { seconds, saveAsDefault ->
                    vm.restartRestTimer(seconds)
                    if (saveAsDefault) {
                        currentExerciseId?.let { vm.saveExerciseRestSeconds(it, seconds) }
                    }
                    showRestPicker = false
                },
            )
        }

        if (showAddExercise) {
            com.yfantasyxx.ui.components.AddExerciseDialog(
                onDismiss = { showAddExercise = false },
                onConfirm = { name, category, equipment, muscle ->
                    vm.addCustomExerciseToSession(name, category, equipment, muscle)
                    showAddExercise = false
                },
            )
        }

        if (showExercisePicker) {
            ExercisePickerDialog(
                container = container,
                onDismiss = { showExercisePicker = false },
                onPick = { exerciseId ->
                    vm.addExercise(exerciseId)
                    showExercisePicker = false
                },
                onRequestCreate = {
                    showExercisePicker = false
                    showAddExercise = true
                },
            )
        }

        if (showFinishDialog) {
            FinishDialog(
                defaultName = defaultSessionName,
                onDismiss = { showFinishDialog = false },
                onConfirm = { name, note ->
                    showFinishDialog = false
                    vm.finishWorkout(name, note) { onBack() }
                },
            )
        }

        if (showDiscardDialog) {
            AppDialog(
                title = "放弃本次训练？",
                summary = "草稿和已记录的组都会被删除",
                onDismissRequest = { showDiscardDialog = false },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ActionText(text = "取消", onClick = { showDiscardDialog = false })
                    Spacer(Modifier.width(12.dp))
                    ActionText(text = "放弃", onClick = {
                        showDiscardDialog = false
                        vm.discardWorkout { onBack() }
                    }, color = MiuixTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ExerciseBlock(
    item: SessionExerciseWithSets,
    lastSets: List<SetLog>,
    unitLabel: String,
    collapsed: Boolean = false,
    onToggleCollapse: () -> Unit = {},
    modifier: Modifier = Modifier,
    onUpdateSet: (SetLog, Float, Int) -> Unit,
    onToggleSet: (SetLog) -> Unit,
    onAddSet: () -> Unit,
    onRemoveExercise: () -> Unit,
    onDeleteSet: (SetLog) -> Unit,
    onTogglePr: (SetLog) -> Unit,
) {
    val completed = item.sets.count { it.completed }
    val lastSummary = if (lastSets.isNotEmpty()) {
        "上次 " + lastSets.take(3).joinToString(" / ") { "${it.weightKg.toInt()}×${it.reps}" }
    } else ""

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ---- 动作标题行 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.exercise.name,
                            fontSize = 17.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (item.sets.isNotEmpty() && item.sets.all { it.isWarmup }) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "热身",
                                fontSize = 10.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(androidx.compose.ui.graphics.Color(0xFFFF9F0A))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (collapsed) {
                            "${item.sets.size} 组 · 已完成 $completed 组"
                        } else buildString {
                            append(item.exercise.category)
                            if (item.exercise.equipment.isNotEmpty()) {
                                append(" · ")
                                append(item.exercise.equipment)
                            }
                            // 模板设定的目标次数，如「目标 8-12」
                            val planned = item.sessionExercise.plannedReps
                            if (!planned.isNullOrBlank()) {
                                append(" · 目标 ")
                                append(planned)
                            }
                            if (lastSummary.isNotEmpty()) {
                                append(" · ")
                                append(lastSummary)
                            } else {
                                append(" · 首次训练")
                            }
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                val allDone = item.sets.isNotEmpty() && completed == item.sets.size
                Text(
                    text = "$completed/${item.sets.size}",
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = if (allDone) {
                        MiuixTheme.colorScheme.onPrimary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (allDone) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.surfaceContainer
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(6.dp))
                // 收纳/展开组数
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .clickable(onClick = onToggleCollapse),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (collapsed) {
                            Icons.Filled.ExpandMore
                        } else {
                            Icons.Filled.ExpandLess
                        },
                        contentDescription = if (collapsed) "展开组数" else "收纳组数",
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .clickable(onClick = onRemoveExercise),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "移除动作",
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (collapsed) return@Column
            Spacer(Modifier.height(10.dp))
            top.yukonga.miuix.kmp.basic.HorizontalDivider(
                color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(4.dp))
            // 表头（与下方组行的布局对齐）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "组",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(26.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "重量($unitLabel)",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "次数",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(44.dp))
            }
            item.sets.sortedBy { it.setNumber }.forEach { set ->
                SetRow(
                    set = set,
                    onUpdateSet = onUpdateSet,
                    onToggleSet = onToggleSet,
                    onDeleteSet = onDeleteSet,
                    onTogglePr = onTogglePr,
                )
            }
            Spacer(Modifier.height(4.dp))
            ActionText(text = "+ 添加一组", onClick = onAddSet)
        }
    }
}

@Composable
private fun SetRow(
    set: SetLog,
    onUpdateSet: (SetLog, Float, Int) -> Unit,
    onToggleSet: (SetLog) -> Unit,
    onDeleteSet: (SetLog) -> Unit,
    onTogglePr: (SetLog) -> Unit,
) {
    var weight by remember(set.id) { mutableStateOf(set.weightKg) }
    var reps by remember(set.id) { mutableStateOf(set.reps) }
    LaunchedEffect(weight, reps) {
        if (weight != set.weightKg || reps != set.reps) {
            onUpdateSet(set, weight, reps)
        }
    }

    val completed = set.completed
    val prGold = androidx.compose.ui.graphics.Color(0xFFFF9F0A)
    val background by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            set.isPr -> prGold.copy(alpha = 0.18f)
            completed -> MiuixTheme.colorScheme.primaryContainer
            else -> MiuixTheme.colorScheme.surfaceContainer
        },
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "setRowBackground",
    )
    val badgeColor by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            set.isPr -> prGold
            completed -> MiuixTheme.colorScheme.primary
            else -> MiuixTheme.colorScheme.surface
        },
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "setBadge",
    )

    // 左滑露出删除
    val density = LocalDensity.current
    val maxSwipe = with(density) { 112.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    var rawOffset by remember { mutableStateOf(0f) }
    val animatedOffset by androidx.compose.animation.core.animateFloatAsState(
        targetValue = rawOffset,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "setSwipeOffset",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surface),
    ) {
        // 底层：PR + 删除（被上层组行完全遮住，滑开才露出）
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(prGold)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        rawOffset = 0f
                        onTogglePr(set)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (set.isPr) "取消PR" else "PR",
                    fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.error)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        rawOffset = 0f
                        onDeleteSet(set)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "删除",
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onError,
                )
            }
        }

        // 上层：组行内容
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .clip(RoundedCornerShape(16.dp))
                .background(background)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = rawOffset + dragAmount
                            val old = rawOffset
                            rawOffset = next.coerceIn(-maxSwipe, 0f)
                            // 刚滑过阈值时给一次轻震
                            if (old > -maxSwipe / 3f && rawOffset <= -maxSwipe / 3f) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = {
                            rawOffset = if (rawOffset < -maxSwipe / 3f) -maxSwipe else 0f
                        },
                        onDragCancel = { rawOffset = 0f },
                    )
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = set.setNumber.toString(),
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = when {
                        set.isPr -> androidx.compose.ui.graphics.Color.White
                        completed -> MiuixTheme.colorScheme.onPrimary
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            NumberStepper(
                value = weight,
                onValueChange = { weight = it },
                step = 2.5f,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            NumberStepper(
                value = reps.toFloat(),
                onValueChange = { reps = it.toInt() },
                step = 1f,
                modifier = Modifier.weight(1f),
            )
            Checkbox(
                state = if (completed) {
                    androidx.compose.ui.state.ToggleableState.On
                } else {
                    androidx.compose.ui.state.ToggleableState.Off
                },
                onClick = { onToggleSet(set) },
            )
        }
    }
}

@Composable
private fun FinishDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var note by remember { mutableStateOf("") }
    AppDialog(
        title = "完成训练",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            top.yukonga.miuix.kmp.basic.TextField(
                value = name,
                onValueChange = { name = it },
                label = "训练名称（可选）",
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            top.yukonga.miuix.kmp.basic.TextField(
                value = note,
                onValueChange = { note = it },
                label = "备注（可选）",
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(text = "取消", onClick = onDismiss)
                Spacer(Modifier.width(12.dp))
                Button(onClick = { onConfirm(name, note) }) {
                    Text("完成", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

/** 动作选择对话框（搜索 + 分类） */
@Composable
private fun ExercisePickerDialog(
    container: com.yfantasyxx.AppContainer,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onRequestCreate: () -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val exercises = remember(keyword, category) {
        container.workoutRepository.observeExercises(keyword, category)
    }.collectAsState(initial = emptyList())

    AppDialog(
        title = "选择动作",
        fullWidth = true,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            top.yukonga.miuix.kmp.basic.TextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = "搜索动作",
                singleLine = true,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(
                    text = "+ 新建自定义动作",
                    onClick = onRequestCreate,
                )
            }
            Spacer(Modifier.height(2.dp))
            LazyColumn(modifier = Modifier.height(320.dp)) {
                items(exercises.value, key = { it.id }) { exercise ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        onClick = { onPick(exercise.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(exercise.name, style = MiuixTheme.textStyles.main)
                                Text(
                                    "${exercise.category} · ${exercise.equipment}",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

/**
 * 一次拖动落位计划。
 * 手势回调（awaitEachGesture）是受限协程作用域，不能调用挂起函数，
 * 所以手势只把「落点 + 需要滑动的距离」记下来，动画和落库交给 LaunchedEffect。
 */
private class DropPlan(val from: Int, val to: Int, val gap: Float)

/**
 * 快照里的一行动作。
 *
 * [centerRel] 是「相对被拖卡片顶部」的中心偏移，不是绝对屏幕坐标。
 * 用相对值的原因：休息倒计时卡可能在拖动途中出现或消失，整列会一起平移，
 * 绝对坐标会全部失效；相对值在整体平移下不变，判定不受影响。
 */
private class DragRow(
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
private class DragSnapshot(
    val rows: List<DragRow>,
    val baseIndex: Int,
    val selfHeight: Float,
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
private fun buildDragSnapshot(
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
    return DragSnapshot(rows, rows.first().actionIndex, self.size.toFloat())
}

/**
 * 手动补间：把视觉位移从 [from] 平滑推到 [to]。
 * 用 smoothstep 曲线（先慢—中快—再慢），比线性自然。
 */
private suspend fun slideTo(
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
