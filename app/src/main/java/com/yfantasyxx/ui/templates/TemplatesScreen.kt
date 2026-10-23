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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Menu
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable

private val WarmupOrange = androidx.compose.ui.graphics.Color(0xFFFF9F0A)

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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp + com.yfantasyxx.ui.LocalBottomBarSpace.current
                ),
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
            // 拖动排序状态
            val density = LocalDensity.current
            val rowHeightPx = with(density) { 158.dp.toPx() }
            val haptics = LocalHapticFeedback.current
            var draggingKey by remember { mutableStateOf<Long?>(null) }
            var dragOffsetY by remember { mutableStateOf(0f) }

            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(state.items, key = { _, item -> item.key }) { index, item ->
                    val isDragging = draggingKey == item.key
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .height(150.dp)
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffsetY else 0f
                                shadowElevation = if (isDragging) 12f else 0f
                            },
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            // 拖动把手（长按上下拖）
                            Box(
                                modifier = Modifier
                                    .width(34.dp)
                                    .fillMaxHeight()
                                    .pointerInput(state.items.size) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggingKey = item.key
                                                dragOffsetY = 0f
                                                haptics.performHapticFeedback(
                                                    HapticFeedbackType.LongPress
                                                )
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffsetY += amount.y
                                                val shift = (dragOffsetY / rowHeightPx).roundToInt()
                                                if (shift != 0) {
                                                    val target = (index + shift)
                                                        .coerceIn(0, state.items.lastIndex)
                                                    if (target != index) {
                                                        vm.moveItem(index, target)
                                                        dragOffsetY -= (target - index) * rowHeightPx
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                draggingKey = null
                                                dragOffsetY = 0f
                                            },
                                            onDragCancel = {
                                                draggingKey = null
                                                dragOffsetY = 0f
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = "长按拖动排序",
                                    modifier = Modifier.size(18.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(
                                        alpha = 0.7f
                                    ),
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(end = 12.dp, top = 10.dp, bottom = 10.dp),
                            ) {
                                // 标题 + 热身 + 删除
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.exerciseName,
                                        style = MiuixTheme.textStyles.main,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // 热身开关
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(
                                                if (item.isWarmup) WarmupOrange
                                                else MiuixTheme.colorScheme.surfaceContainer
                                            )
                                            .clickable { vm.toggleWarmup(item.key) }
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = if (item.isWarmup) "热身" else "设为热身",
                                            fontSize = 11.sp,
                                            fontWeight = if (item.isWarmup) {
                                                androidx.compose.ui.text.font.FontWeight.SemiBold
                                            } else {
                                                androidx.compose.ui.text.font.FontWeight.Normal
                                            },
                                            color = if (item.isWarmup) {
                                                androidx.compose.ui.graphics.Color.White
                                            } else {
                                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            },
                                        )
                                    }
                                    IconButton(onClick = { vm.removeItem(item.key) }) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "移除",
                                            modifier = Modifier.size(18.dp),
                                            tint = MiuixTheme.colorScheme.error,
                                        )
                                    }
                                }

                                // 组数 + 重量
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "组数",
                                            fontSize = 11.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                        NumberStepper(
                                            value = item.targetSets.toFloat(),
                                            onValueChange = {
                                                vm.updateItem(item.key, sets = it.toInt())
                                            },
                                            step = 1f,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "重量(kg)",
                                            fontSize = 11.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                        NumberStepper(
                                            value = item.targetWeightKg,
                                            onValueChange = {
                                                vm.updateItem(item.key, weight = it)
                                            },
                                            step = 2.5f,
                                        )
                                    }
                                }

                                // 目标次数
                                top.yukonga.miuix.kmp.basic.TextField(
                                    value = item.targetReps,
                                    onValueChange = { vm.updateItem(item.key, reps = it) },
                                    label = "目标次数（如 8-12）",
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
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
