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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@Composable
fun ActiveWorkoutScreen(onBack: () -> Unit) {
    val container = rememberAppContainer()
    val vm: WorkoutViewModel = viewModel(factory = WorkoutViewModel.factory(container))
    val exercises by vm.exercises.collectAsState()
    val elapsed by vm.elapsedSeconds.collectAsState()
    val restTimer by vm.restTimer.collectAsState()
    val lastPerformance by vm.lastPerformance.collectAsState()
    val error by vm.error.collectAsState()
    var showExercisePicker by remember { mutableStateOf(false) }
    var showFinishDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.ensureSession()
        // 退出过 App 的话，恢复仍在跑的休息倒计时
        vm.restoreRestTimer()
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = formatDuration(elapsed),
                subtitle = if (exercises.isEmpty()) "添加动作开始训练" else "${exercises.size} 个动作",
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
        Box(modifier = Modifier.padding(padding)) {
            LazyColumn(
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
                                Text("休息中", style = MiuixTheme.textStyles.subtitle)
                                Text(
                                    text = "${restTimer.remainingSeconds}s",
                                    style = MiuixTheme.textStyles.headline1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
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

                items(exercises, key = { it.sessionExercise.id }) { item ->
                    ExerciseBlock(
                        item = item,
                        lastSets = lastPerformance[item.sessionExercise.exerciseId].orEmpty(),
                        unitLabel = WeightUnit.KG.label,
                        onUpdateSet = { set, weight, reps -> vm.updateSet(set, weight, reps) },
                        onToggleSet = vm::toggleSetComplete,
                        onAddSet = { vm.addSet(item.sessionExercise.id) },
                        onRemoveExercise = { vm.removeExercise(item.sessionExercise.id) },
                        onDeleteSet = vm::deleteSet,
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

        if (showExercisePicker) {
            ExercisePickerDialog(
                container = container,
                onDismiss = { showExercisePicker = false },
                onPick = { exerciseId ->
                    vm.addExercise(exerciseId)
                    showExercisePicker = false
                },
            )
        }

        if (showFinishDialog) {
            FinishDialog(
                defaultName = "",
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
    onUpdateSet: (SetLog, Float, Int) -> Unit,
    onToggleSet: (SetLog) -> Unit,
    onAddSet: () -> Unit,
    onRemoveExercise: () -> Unit,
    onDeleteSet: (SetLog) -> Unit,
) {
    val completed = item.sets.count { it.completed }
    val lastSummary = if (lastSets.isNotEmpty()) {
        "上次: " + lastSets.joinToString(" / ") { "${it.weightKg.toInt()}kg×${it.reps}" }
    } else ""

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.exercise.name,
                        style = MiuixTheme.textStyles.main,
                    )
                    Text(
                        text = if (lastSummary.isNotEmpty()) lastSummary else "首次训练",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Text(
                    text = "$completed/${item.sets.size}",
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                IconButton(onClick = onRemoveExercise) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "移除动作",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
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
) {
    var weight by remember(set.id) { mutableStateOf(set.weightKg) }
    var reps by remember(set.id) { mutableStateOf(set.reps) }
    // 本地编辑同步到 DB
    LaunchedEffect(weight, reps) {
        if (weight != set.weightKg || reps != set.reps) {
            onUpdateSet(set, weight, reps)
        }
    }

    val completed = set.completed
    val background by androidx.compose.animation.animateColorAsState(
        targetValue = if (completed) {
            MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        } else {
            MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
        },
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "setRowBackground",
    )
    val badgeColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (completed) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.surface
        },
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "setBadge",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 组号圆形徽章
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
                color = if (completed) {
                    MiuixTheme.colorScheme.onPrimary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
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
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.height(360.dp)) {
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
