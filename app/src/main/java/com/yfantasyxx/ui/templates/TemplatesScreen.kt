package com.yfantasyxx.ui.templates

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import com.yfantasyxx.data.db.WorkoutTemplate
import com.yfantasyxx.ui.components.EmptyState
import com.yfantasyxx.ui.components.NumberStepper
import com.yfantasyxx.ui.components.SectionHeader
import com.yfantasyxx.ui.rememberAppContainer
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.yfantasyxx.ui.components.ActionText
import com.yfantasyxx.ui.components.AppDialog

@Composable
fun TemplatesScreen(
    onEditTemplate: (Long) -> Unit,
    onCreateTemplate: () -> Unit,
    onBack: () -> Unit,
) {
    val container = rememberAppContainer()
    val vm: TemplatesViewModel = viewModel(factory = TemplatesViewModel.factory(container))
    val templates by vm.templates.collectAsState()

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "训练模板",
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
                    IconButton(onClick = onCreateTemplate) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "新建模板",
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (templates.isEmpty()) {
            EmptyState(
                text = "还没有训练模板\n创建模板可以快速开始常规训练",
                action = {
                    Button(onClick = onCreateTemplate) {
                        Text("新建模板", style = MiuixTheme.textStyles.button)
                    }
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(templates, key = { it.id }) { tpl ->
                    TemplateRow(
                        template = tpl,
                        onClick = { onEditTemplate(tpl.id) },
                        onDelete = { vm.delete(tpl) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateRow(
    template: WorkoutTemplate,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(template.name, style = MiuixTheme.textStyles.main)
                Text(
                    text = SimpleDateFormat("yyyy-MM-dd 创建", Locale.CHINA)
                        .format(Date(template.createdAt)),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除模板",
                    tint = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
fun TemplateEditScreen(templateId: Long?, onBack: () -> Unit) {
    val container = rememberAppContainer()
    val vm: TemplateEditViewModel = viewModel(factory = TemplateEditViewModel.factory(container))
    val state by vm.state.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    var loaded by remember(templateId) { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(templateId) {
        vm.load(templateId)
        loaded = true
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = if (templateId == null) "新建模板" else "编辑模板",
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
                    ActionText(text = "保存", onClick = { vm.save { onBack() } },
                        enabled = state.name.isNotBlank() && state.items.isNotEmpty(),)
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            top.yukonga.miuix.kmp.basic.TextField(
                value = state.name,
                onValueChange = vm::setName,
                label = "模板名称（如：推日）",
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SectionHeader("动作安排（${state.items.size}）")
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.items, key = { it.key }) { item ->
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = item.exerciseName,
                                    style = MiuixTheme.textStyles.main,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { vm.removeItem(item.key) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "移除",
                                        tint = MiuixTheme.colorScheme.error,
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("组数", style = MiuixTheme.textStyles.paragraph)
                                Spacer(Modifier.height(0.dp))
                                NumberStepper(
                                    value = item.targetSets.toFloat(),
                                    onValueChange = { vm.updateItem(item.key, sets = it.toInt()) },
                                    step = 1f,
                                )
                            }
                            top.yukonga.miuix.kmp.basic.TextField(
                                value = item.targetReps,
                                onValueChange = { vm.updateItem(item.key, reps = it) },
                                label = "目标次数（如 8-12）",
                                singleLine = true,
                            )
                        }
                    }
                }
            }
            Button(
                onClick = { showPicker = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                Text("  添加动作", style = MiuixTheme.textStyles.button)
            }
        }
    }

    if (showPicker) {
        var keyword by remember { mutableStateOf("") }
        val list by container.workoutRepository.observeExercises(keyword, null)
            .collectAsState(initial = emptyList())
        AppDialog(
            title = "选择动作",
            fullWidth = true,
            onDismissRequest = { showPicker = false },
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
                    items(list, key = { it.id }) { exercise ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            onClick = {
                                vm.addExercise(exercise)
                                showPicker = false
                            },
                        ) {
                            Row(modifier = Modifier.padding(12.dp)) {
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
}
