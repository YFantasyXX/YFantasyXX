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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import com.yfantasyxx.ui.components.FilterChip
import com.yfantasyxx.ui.components.AddExerciseDialog

@Composable
fun ExercisesScreen(onOpenExercise: (Long) -> Unit) {
    val container = rememberAppContainer()
    val vm: ExercisesViewModel = viewModel(factory = ExercisesViewModel.factory(container))
    val exercises by vm.exercises.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    val category by vm.selectedCategory.collectAsState()
    val equipment by vm.selectedEquipment.collectAsState()
    val categories by vm.categories.collectAsState()
    val equipments by vm.equipments.collectAsState()
    val totalCount by vm.totalCount.collectAsState()

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
                        onClick = { vm.setCategory(null) },
                    )
                }
                items(categories) { cat ->
                    FilterChip(
                        label = cat,
                        selected = category == cat,
                        onClick = { vm.setCategory(cat) },
                    )
                }
            }

            // 器械筛选（按数据库中实际存在的器械动态生成）
            if (equipments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            label = "全部器械",
                            selected = equipment == null,
                            onClick = { vm.setEquipment(null) },
                        )
                    }
                    items(equipments) { eq ->
                        FilterChip(
                            label = eq,
                            selected = equipment == eq,
                            onClick = { vm.setEquipment(eq) },
                        )
                    }
                }
            }

            // 库里有几条必须能直接看到：动作库是按名字差集增量补齐的，
            // 某次补齐没跑成时列表会静静停在旧条数，靠手滑是感觉不出来的
            val filtering = category != null || equipment != null || keyword.isNotBlank()
            Text(
                text = if (filtering) {
                    "筛选出 ${exercises.size} 个 · 全部 $totalCount"
                } else {
                    "共 $totalCount 个动作"
                },
                modifier = Modifier.padding(start = 20.dp, top = 10.dp, end = 20.dp),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            if (exercises.isEmpty()) {
                EmptyState(text = "没有找到相关动作\n试试其他关键词或分类")
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = 6.dp,
                        bottom = com.yfantasyxx.ui.LocalBottomBarSpace.current
                    )
                ) {
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
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
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


