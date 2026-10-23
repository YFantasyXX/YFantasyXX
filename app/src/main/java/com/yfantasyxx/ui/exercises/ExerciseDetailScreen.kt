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

@Composable
fun ExerciseDetailScreen(exerciseId: Long, onBack: () -> Unit) {
    val container = rememberAppContainer()
    val vm: ExercisesViewModel = viewModel(factory = ExercisesViewModel.factory(container))
    var exercise by remember { mutableStateOf<Exercise?>(null) }
    var lastSets by remember { mutableStateOf<List<SetLog>>(emptyList()) }

    LaunchedEffect(exerciseId) {
        exercise = vm.getExercise(exerciseId)
        lastSets = vm.getLastPerformance(exerciseId)
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
                            if (ex.note.isNotEmpty()) InfoRow("备注", ex.note)
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
}
