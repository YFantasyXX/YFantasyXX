package com.yfantasyxx.ui.exercises

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.yfantasyxx.ui.components.AppDialog
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.yfantasyxx.ui.components.EmptyState
import com.yfantasyxx.ui.components.SectionHeader
import com.yfantasyxx.ui.rememberAppContainer
import com.yfantasyxx.ui.components.ActionText

@Composable
fun ExercisesScreen(onOpenExercise: (Long) -> Unit) {
    val container = rememberAppContainer()
    val vm: ExercisesViewModel = viewModel(factory = ExercisesViewModel.factory(container))
    val exercises by vm.exercises.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "动作库",
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "添加自定义动作",
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // 搜索框
            TextField(
                value = keyword,
                onValueChange = {
                    keyword = it
                    vm.setKeyword(it)
                },
                label = "搜索动作名称或部位",
                singleLine = true,
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // 分类筛选
            LazyRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        label = "全部",
                        selected = category == null,
                        onClick = {
                            category = null
                            vm.setCategory(null)
                        },
                    )
                }
                items(ExercisesViewModel.categories) { cat ->
                    FilterChip(
                        label = cat,
                        selected = category == cat,
                        onClick = {
                            category = cat
                            vm.setCategory(cat)
                        },
                    )
                }
            }

            if (exercises.isEmpty()) {
                EmptyState(text = "没有找到相关动作\n试试其他关键词或分类")
            } else {
                LazyColumn {
                    items(exercises, key = { it.id }) { exercise ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            onClick = { onOpenExercise(exercise.id) },
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(exercise.name, style = MiuixTheme.textStyles.main)
                                    Text(
                                        text = "${exercise.category} · ${exercise.equipment}" +
                                            if (exercise.primaryMuscle.isNotEmpty()) " · ${exercise.primaryMuscle}" else "",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                if (exercise.isCustom) {
                                    Text(
                                        text = "自定义",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddExerciseDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, cat, equip, muscle ->
                vm.addExercise(name, cat, equip, muscle)
                showAdd = false
            },
        )
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (selected) {
            MiuixTheme.colorScheme.primaryContainer
        } else {
            MiuixTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = if (selected) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun AddExerciseDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(ExercisesViewModel.categories.first()) }
    var equipment by remember { mutableStateOf(ExercisesViewModel.equipments.first()) }
    var muscle by remember { mutableStateOf("") }

    AppDialog(
        title = "添加自定义动作",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = "动作名称",
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Text("分类", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ExercisesViewModel.categories) { cat ->
                    FilterChip(
                        label = cat,
                        selected = category == cat,
                        onClick = { category = cat },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("器械", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ExercisesViewModel.equipments) { eq ->
                    FilterChip(
                        label = eq,
                        selected = equipment == eq,
                        onClick = { equipment = eq },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextField(
                value = muscle,
                onValueChange = { muscle = it },
                label = "主要部位（可选）",
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(text = "取消", onClick = onDismiss)
                Spacer(Modifier.height(0.dp))
                ActionText(text = "添加", onClick = { onConfirm(name, category, equipment, muscle) },
                    enabled = name.isNotBlank(),)
            }
        }
    }
}
