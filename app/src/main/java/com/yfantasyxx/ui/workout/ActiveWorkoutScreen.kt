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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    // 拖动中的目标插入位（-1 表示未拖动）
    var dropTargetIndex by remember { mutableStateOf(-1) }
    // 每个动作的收纳状态（收起组数，方便拖动排序）
    val collapsedIds = remember { mutableStateMapOf<Long, Boolean>() }
    // 拖动期间要拿到最新的动作顺序（而不是手势启动那一刻的快照）
    val latestExercises by rememberUpdatedState(exercises)
    // 列表状态：拖动时用它读各 item 的真实布局位置（官方数据源，比自记坐标可靠）
    val listState = rememberLazyListState()
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

                itemsIndexed(exercises, key = { _, it -> it.sessionExercise.id }) { _, item ->
                    val isDragging = draggingId == item.sessionExercise.id
                    val isCollapsed = collapsedIds[item.sessionExercise.id] == true
                    val primaryColor = MiuixTheme.colorScheme.primary
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffsetY else 0f
                                shadowElevation = if (isDragging) 12f else 0f
                            }
                            // 拖动高亮：用 drawBehind 画在内容下层，
                            // 完全不参与测量和布局，不会引发布局反馈环
                            .drawBehind {
                                if (isDragging) {
                                    val stroke = 3.dp.toPx()
                                    val inset = stroke / 2f
                                    drawRoundRect(
                                        color = primaryColor,
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
                        // 拖动把手：按住上下拖动排序。
                        //
                        // 为什么自己写手势而不用 detectVerticalDragGestures：
                        // 那个检测器内部的 verticalDrag 一旦发现事件已被消费
                        // （change.isConsumed()）就返回 null，整个手势会被判定为
                        // 「取消」而走 onDragCancel，onDragEnd 再也不执行——
                        // 这就是之前「拖到位了却没反应」的真正原因。
                        // 自己写 awaitEachGesture 循环可以完全控制：
                        // 该消费时消费、该结束时结束，结束逻辑必然执行。
                        //
                        // 另外两条铁律（前几版踩过）：
                        // 1. 拖动中【绝不修改列表顺序】，否则重新布局 → 坐标变化 →
                        //    又触发换位判断，形成反馈环（表现为持续闪烁）
                        // 2. 位置数据取自 listState.layoutInfo（官方数据源），
                        //    不用 onGloballyPositioned 自记坐标
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .heightIn(min = 72.dp)
                                .pointerInput(item.sessionExercise.id) {
                                    awaitEachGesture {
                                        // 不要求未被消费，保证一定能拿到这次手势
                                        val down = awaitFirstDown(
                                            requireUnconsumed = false
                                        )
                                        // 立即占用手势：把手是专用拖拽区，
                                        // 阻止外层 LazyColumn 的滚动抢走事件
                                        down.consume()
                                        draggingId = item.sessionExercise.id
                                        dragOffsetY = 0f
                                        // 进入拖动态时给一次触觉反馈，确认「抓住了」
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.TextHandleMove
                                        )
                                        var started = false
                                        var total = 0f
                                        var lastY = down.position.y
                                        val slop = viewConfiguration.touchSlop

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes
                                                .firstOrNull { it.id == down.id }
                                                ?: break
                                            // 手指抬起或指针消失 → 手势结束
                                            if (!change.pressed) break
                                            val dy = change.position.y - lastY
                                            lastY = change.position.y
                                            if (!started) {
                                                // 未超过滑动阈值前不算拖动，
                                                // 但照样消费，避免列表滚动启动
                                                if (abs(dy) <= slop) {
                                                    change.consume()
                                                    continue
                                                }
                                                started = true
                                            }
                                            total += dy
                                            change.consume()
                                            dragOffsetY = total

                                            // ---- 计算目标插入位（此时不改列表）----
                                            val list = latestExercises
                                            val from = list.indexOfFirst {
                                                it.sessionExercise.id == draggingId
                                            }
                                            if (from < 0) continue
                                            val infos =
                                                listState.layoutInfo.visibleItemsInfo
                                            val self =
                                                infos.firstOrNull { it.key == draggingId }
                                                    ?: continue
                                            // 被拖卡片视觉中心 = 布局中心 + 累积位移
                                            val visualCenter =
                                                self.offset + self.size / 2f + total
                                            var target = from
                                            var best = Float.MAX_VALUE
                                            infos.forEach { info ->
                                                if (info.key == draggingId) {
                                                    return@forEach
                                                }
                                                val center =
                                                    info.offset + info.size / 2f
                                                val d = abs(center - visualCenter)
                                                if (d < best) {
                                                    best = d
                                                    target = info.index
                                                }
                                            }
                                            dropTargetIndex = target
                                        }

                                        // ---- 手势结束：应用排序并一次性落库 ----
                                        val id = draggingId
                                        val list = latestExercises
                                        val from = list.indexOfFirst {
                                            it.sessionExercise.id == id
                                        }
                                        val to = dropTargetIndex
                                        if (id != null && from >= 0 &&
                                            to in list.indices && to != from
                                        ) {
                                            vm.moveExerciseLocal(from, to)
                                            vm.persistExerciseOrder()
                                        }
                                        draggingId = null
                                        dragOffsetY = 0f
                                        dropTargetIndex = -1
                                    }
                                },
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
                            lastSets = lastPerformance[item.sessionExercise.exerciseId].orEmpty(),
                            unitLabel = WeightUnit.KG.label,
                            collapsed = isCollapsed,
                            onToggleCollapse = {
                                collapsedIds[item.sessionExercise.id] = !isCollapsed
                            },
                            modifier = Modifier.weight(1f),
                            onUpdateSet = { set, weight, reps ->
                                vm.updateSet(set, weight, reps)
                            },
                            onToggleSet = vm::toggleSetComplete,
                            onAddSet = { vm.addSet(item.sessionExercise.id) },
                            onRemoveExercise = { vm.removeExercise(item.sessionExercise.id) },
                            onDeleteSet = vm::deleteSet,
                            onTogglePr = vm::toggleSetPr,
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
