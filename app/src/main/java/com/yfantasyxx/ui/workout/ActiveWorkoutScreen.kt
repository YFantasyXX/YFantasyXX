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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import com.yfantasyxx.ui.components.AppDialog
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.yfantasyxx.data.prefs.WeightUnit
import com.yfantasyxx.ui.rememberAppContainer
import com.yfantasyxx.ui.components.ActionText
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import com.yfantasyxx.ui.components.RestSecondsPickerDialog
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.mutableStateMapOf

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

    // 训练内动作拖动排序：相位状态（未拖 / 拖动中 / 落位中）收敛在
    // WorkoutDragState 里，屏幕这边只负责渲染列表与消费落位计划
    val haptics = LocalHapticFeedback.current
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
    // 只按 listState 记忆：把 haptics 也写进 key 的话，
    // 一旦组合局部换了实例就会重建状态，拖到一半的 pendingDrop 会被清掉
    val drag = remember(listState) { WorkoutDragState(listState, haptics) }
    // 动作列表之前还有几个 item（错误提示卡 / 休息倒计时卡）。
    // visibleItemsInfo.index 是「整个 LazyColumn 的下标」，减掉它才是动作下标。
    // 若以后在动作列表前面再加 item，这里必须同步 +1。
    val headerItemCount = (if (error != null) 1 else 0) +
        (if (restTimer.running) 1 else 0)
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
    LaunchedEffect(drag.pendingDrop) {
        val plan = drag.pendingDrop ?: return@LaunchedEffect
        // 1) 先平滑滑进已经让出来的空隙
        slideTo(drag.offsetY, plan.gap, 160L) { drag.offsetY = it }

        // 2) 换序 + 清位移 + 收回让位，必须在同一帧、同一个同步块里写完。
        //    这三件事分开到两帧，中间那帧就是「新布局 + 旧位移」或者
        //    「旧布局 + 新位移」，卡片会猛地跳一格。
        val moved = plan.to != plan.from
        if (moved) {
            val ids = latestExercises.map { it.sessionExercise.id }.toMutableList()
            ids.add(plan.to, ids.removeAt(plan.from))
            localOrderIds = ids
        }
        drag.endPhase()
        drag.offsetY = 0f

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
        drag.draggingId = null

        drag.pendingDrop = null
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
                    WorkoutExerciseRow(
                        item = item,
                        index = index,
                        lastSets = lastPerformance[item.sessionExercise.exerciseId].orEmpty(),
                        unitLabel = WeightUnit.KG.label,
                        collapsed = collapsedIds[id] == true,
                        onToggleCollapse = {
                            collapsedIds[id] = collapsedIds[id] != true
                        },
                        drag = drag,
                        headerCount = headerItemCount,
                        exerciseCount = displayExercises.size,
                        onUpdateSet = { set, weight, reps -> vm.updateSet(set, weight, reps) },
                        onToggleSet = vm::toggleSetComplete,
                        onAddSet = { vm.addSet(id) },
                        onRemoveExercise = { vm.removeExercise(id) },
                        onDeleteSet = vm::deleteSet,
                        onTogglePr = vm::toggleSetPr,
                    )
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
