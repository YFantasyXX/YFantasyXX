package com.yfantasyxx.ui.exercises

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.yfantasyxx.data.db.Exercise
import com.yfantasyxx.data.db.SetLog
import com.yfantasyxx.ui.components.InfoRow
import com.yfantasyxx.ui.components.SectionHeader
import com.yfantasyxx.ui.rememberAppContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import com.yfantasyxx.ui.components.RestSecondsPickerDialog
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import com.yfantasyxx.ui.components.AppDialog
import androidx.compose.foundation.layout.width
import com.yfantasyxx.ui.components.ActionText
import top.yukonga.miuix.kmp.basic.Button
import androidx.compose.foundation.layout.imePadding

@Composable
fun ExerciseDetailScreen(exerciseId: Long, onBack: () -> Unit) {
    val container = rememberAppContainer()
    val vm: ExercisesViewModel = viewModel(factory = ExercisesViewModel.factory(container))
    var exercise by remember { mutableStateOf<Exercise?>(null) }
    var lastSets by remember { mutableStateOf<List<SetLog>>(emptyList()) }
    var bestSet by remember { mutableStateOf<SetLog?>(null) }
    var e1rmPoints by remember {
        mutableStateOf<List<com.yfantasyxx.data.db.E1rmPoint>>(emptyList())
    }
    var showRestPicker by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var editCategory by remember { mutableStateOf("") }
    var editEquipment by remember { mutableStateOf("") }
    var editMuscle by remember { mutableStateOf("") }

    LaunchedEffect(exerciseId) {
        exercise = vm.getExercise(exerciseId)
        lastSets = vm.getLastPerformance(exerciseId)
        bestSet = vm.getBestSet(exerciseId)
        e1rmPoints = vm.getE1rmHistory(exerciseId)
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = exercise?.name ?: "动作详情",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    if (exercise?.isCustom == true) {
                        IconButton(onClick = {
                            val ex = exercise ?: return@IconButton
                            editName = ex.name
                            editCategory = ex.category
                            editEquipment = ex.equipment
                            editMuscle = ex.primaryMuscle
                            showEdit = true
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "编辑",
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = {
                            vm.deleteExercise(exerciseId)
                            onBack()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = MiuixTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        exercise?.let { ex ->
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            InfoRow("分类", ex.category)
                            InfoRow("器械", ex.equipment)
                            InfoRow("主要部位", ex.primaryMuscle.ifEmpty { "--" })
                            InfoRow("来源", if (ex.isCustom) "自定义" else "内置")
                            bestSet?.let { best ->
                                InfoRow(
                                    "最佳记录",
                                    if (best.weightKg > 0f) {
                                        "${best.weightKg.toInt()} kg × ${best.reps}"
                                    } else {
                                        "${best.reps} 次（自重）"
                                    }
                                )
                                // 最佳记录按估算 1RM 挑，这里把估出来的值一并显示，
                                // 否则「95 kg × 8 为什么比 100 kg × 1 更好」看不出来
                                com.yfantasyxx.data.OneRepMax
                                    .estimate(
                                        weightKg = best.weightKg,
                                        reps = best.reps,
                                        pattern = com.yfantasyxx.data.LiftPattern
                                            .fromValue(ex.pattern),
                                    )
                                    ?.let { InfoRow("估算 1RM", "${it.toInt()} kg") }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showRestPicker = true }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "默认休息时长",
                                    style = MiuixTheme.textStyles.paragraph,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = ex.restSeconds?.let { "$it 秒" } ?: "跟随全局",
                                        style = MiuixTheme.textStyles.paragraph,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            if (ex.note.isNotEmpty()) InfoRow("备注", ex.note)
                        }
                    }
                }
                // ---- 估算 1RM 趋势 ----
                // 这是所有训练智能的地基：只有一条能看出「有没有进步」的曲线，
                // 才知道该不该加重、该不该换计划。
                item { SectionHeader("估算 1RM 趋势") }
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (e1rmPoints.size < 2) {
                                Text(
                                    text = "记录两次以上训练后显示趋势",
                                    style = MiuixTheme.textStyles.paragraph,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            } else {
                                val best = e1rmPoints.maxOf { it.e1rm }
                                val latest = e1rmPoints.last()
                                val first = e1rmPoints.first()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${latest.e1rm.toInt()} kg",
                                            style = MiuixTheme.textStyles.title3,
                                            color = MiuixTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = "最近一次 · 最佳组 " +
                                                "${latest.weightKg.toInt()} × ${latest.reps}",
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        // 涨跌用中文习惯：涨红跌绿
                                        val delta = latest.e1rm - first.e1rm
                                        Text(
                                            text = (if (delta >= 0) "+" else "") +
                                                "${delta.toInt()} kg",
                                            style = MiuixTheme.textStyles.subtitle,
                                            color = if (delta >= 0) {
                                                androidx.compose.ui.graphics.Color(0xFFFF3B30)
                                            } else {
                                                androidx.compose.ui.graphics.Color(0xFF34C759)
                                            },
                                        )
                                        Text(
                                            text = "较首次 · 峰值 ${best.toInt()}",
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                com.yfantasyxx.ui.components.WeightLineChart(
                                    data = e1rmPoints.map {
                                        java.text.SimpleDateFormat("M/d", Locale.CHINA)
                                            .format(Date(it.startTime)) to it.e1rm
                                    },
                                )
                            }
                            // 换算依据要说出来：同一组重量按不同动作的曲线能差出十几个百分点，
                            // 不写清楚，用户会以为换了个动作曲线就乱跳
                            val curve = com.yfantasyxx.data.LiftPattern.fromValue(ex.pattern)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "换算依据：" + if (curve == com.yfantasyxx.data.LiftPattern.UNKNOWN) {
                                    "通用公式（七公式融合）"
                                } else {
                                    "${curve.label} rep-max 曲线"
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                item { SectionHeader("最近表现") }
                if (lastSets.isEmpty()) {
                    item {
                        Text(
                            text = "还没有这个动作的训练记录",
                            style = MiuixTheme.textStyles.paragraph,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                } else {
                    item {
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                lastSets.forEachIndexed { index, set ->
                                    if (index > 0) Spacer(Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = "第 ${set.setNumber} 组",
                                            style = MiuixTheme.textStyles.paragraph,
                                        )
                                        Text(
                                            text = "${set.weightKg.toInt()} kg × ${set.reps} 次",
                                            style = MiuixTheme.textStyles.paragraph,
                                            color = MiuixTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "记录于 " + SimpleDateFormat(
                                        "MM月dd日 HH:mm",
                                        Locale.CHINA
                                    ).format(Date(lastSets.first().loggedAt)),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEdit) {
        exercise?.let { ex ->
            AppDialog(
                title = "编辑动作",
                onDismissRequest = { showEdit = false },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                ) {
                    top.yukonga.miuix.kmp.basic.TextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = "动作名称",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    top.yukonga.miuix.kmp.basic.TextField(
                        value = editCategory,
                        onValueChange = { editCategory = it },
                        label = "分类（如 胸 / 背 / 腿）",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    top.yukonga.miuix.kmp.basic.TextField(
                        value = editEquipment,
                        onValueChange = { editEquipment = it },
                        label = "器械（如 杠铃 / 哑铃）",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    top.yukonga.miuix.kmp.basic.TextField(
                        value = editMuscle,
                        onValueChange = { editMuscle = it },
                        label = "主要部位",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActionText(text = "取消", onClick = { showEdit = false })
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val updated = ex.copy(
                                    name = editName.trim(),
                                    category = editCategory.ifBlank { ex.category },
                                    equipment = editEquipment.ifBlank { ex.equipment },
                                    primaryMuscle = editMuscle.trim(),
                                )
                                vm.updateExercise(updated)
                                exercise = updated
                                showEdit = false
                            },
                            enabled = editName.isNotBlank(),
                        ) {
                            Text("保存", style = MiuixTheme.textStyles.button)
                        }
                    }
                }
            }
        }
    }

    if (showRestPicker) {
        exercise?.let { ex ->
            RestSecondsPickerDialog(
                initialSeconds = ex.restSeconds ?: 90,
                showSaveAsDefault = false,
                onDismiss = { showRestPicker = false },
                onConfirm = { seconds, _ ->
                    val updated = ex.copy(restSeconds = seconds)
                    vm.updateExercise(updated)
                    exercise = updated
                    showRestPicker = false
                },
                onClear = if (ex.restSeconds != null) {
                    {
                        val updated = ex.copy(restSeconds = null)
                        vm.updateExercise(updated)
                        exercise = updated
                    }
                } else {
                    null
                },
            )
        }
    }
}
