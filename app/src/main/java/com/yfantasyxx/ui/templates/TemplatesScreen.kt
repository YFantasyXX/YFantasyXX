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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.CreateNewFolder
import com.yfantasyxx.ui.components.RestSecondsPickerDialog
import com.yfantasyxx.ui.components.AddExerciseDialog
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.shape.CircleShape

private val WarmupOrange = androidx.compose.ui.graphics.Color(0xFFFF9F0A)

@Composable
fun TemplatesScreen(
    onEditTemplate: (Long) -> Unit,
    onCreateTemplate: (Long?) -> Unit,
    onBack: () -> Unit,
) {
    val container = rememberAppContainer()
    val vm: TemplatesViewModel = viewModel(factory = TemplatesViewModel.factory(container))
    val groups by vm.groups.collectAsState()
    val folders by vm.folders.collectAsState()

    var showNewFolder by remember { mutableStateOf(false) }
    var folderNameInput by remember { mutableStateOf("") }
    var renamingFolder by remember { mutableStateOf<com.yfantasyxx.data.db.TemplateFolder?>(null) }
    var moveTarget by remember { mutableStateOf<WorkoutTemplate?>(null) }

    // 拖动排序状态
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 84.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }

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
                    IconButton(onClick = {
                        folderNameInput = ""
                        showNewFolder = true
                    }) {
                        Icon(
                            imageVector = Icons.Filled.CreateNewFolder,
                            contentDescription = "新建文件夹",
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { onCreateTemplate(null) }) {
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
        if (groups.isEmpty()) {
            EmptyState(
                text = "还没有训练模板\n创建模板可以快速开始常规训练",
                action = {
                    Button(onClick = { onCreateTemplate(null) }) {
                        Text("新建模板", style = MiuixTheme.textStyles.button)
                    }
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    top = 12.dp,
                    end = 16.dp,
                    bottom = 16.dp + com.yfantasyxx.ui.LocalBottomBarSpace.current
                ),
            ) {
                groups.forEach { group ->
                    item(key = "folder_${group.folderId}") {
                        val folderEntity = folders.firstOrNull { it.id == group.folderId }
                        FolderHeader(
                            name = group.folderName ?: "未分组",
                            count = group.templates.size,
                            isRealFolder = group.folderId != null,
                            showOnHome = folderEntity?.showOnHome ?: true,
                            onToggleHome = {
                                group.folderId?.let { id ->
                                    vm.toggleFolderHome(id, folderEntity?.showOnHome ?: true)
                                }
                            },
                            onAddTemplate = { onCreateTemplate(group.folderId) },
                            onRename = {
                                renamingFolder = folders.firstOrNull { it.id == group.folderId }
                                folderNameInput = group.folderName.orEmpty()
                            },
                            onDelete = { group.folderId?.let(vm::deleteFolder) },
                        )
                    }

                    itemsIndexed(group.templates, key = { _, t -> t.id }) { index, tpl ->
                        val isDragging = draggingId == tpl.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (isDragging) dragOffsetY else 0f
                                    shadowElevation = if (isDragging) 10f else 0f
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 拖动把手
                            Box(
                                modifier = Modifier
                                    .width(30.dp)
                                    .height(64.dp)
                                    .pointerInput(group.folderId, group.templates.size) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggingId = tpl.id
                                                dragOffsetY = 0f
                                                haptics.performHapticFeedback(
                                                    HapticFeedbackType.LongPress
                                                )
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffsetY += amount.y
                                                val g = vm.groups.value
                                                    .firstOrNull { it.folderId == group.folderId }
                                                    ?: return@detectDragGesturesAfterLongPress
                                                val current =
                                                    g.templates.indexOfFirst { it.id == tpl.id }
                                                if (current >= 0) {
                                                    val shift =
                                                        (dragOffsetY / rowHeightPx).roundToInt()
                                                    val target = (current + shift)
                                                        .coerceIn(0, g.templates.lastIndex)
                                                    if (target != current) {
                                                        vm.moveInGroup(
                                                            group.folderId,
                                                            current,
                                                            target
                                                        )
                                                        dragOffsetY -=
                                                            (target - current) * rowHeightPx
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                draggingId = null
                                                dragOffsetY = 0f
                                            },
                                            onDragCancel = {
                                                draggingId = null
                                                dragOffsetY = 0f
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = "长按拖动排序",
                                    modifier = Modifier.size(16.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        .copy(alpha = 0.6f),
                                )
                            }

                            Card(
                                modifier = Modifier
                                    .weight(1f),
                                onClick = { onEditTemplate(tpl.id) },
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        start = 14.dp,
                                        end = 6.dp,
                                        top = 12.dp,
                                        bottom = 12.dp
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = tpl.name,
                                            style = MiuixTheme.textStyles.main,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = SimpleDateFormat(
                                                "yyyy-MM-dd 创建",
                                                Locale.CHINA
                                            ).format(Date(tpl.createdAt)),
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                    ActionText(
                                        text = "移动",
                                        onClick = { moveTarget = tpl },
                                    )
                                    IconButton(onClick = { vm.delete(tpl) }) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "删除模板",
                                            modifier = Modifier.size(18.dp),
                                            tint = MiuixTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (group.templates.isEmpty()) {
                        item(key = "empty_${group.folderId}") {
                            Text(
                                text = "这个文件夹还没有模板",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 34.dp, top = 4.dp, bottom = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 新建文件夹 ----
    if (showNewFolder) {
        AppDialog(
            title = "新建文件夹",
            onDismissRequest = { showNewFolder = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                top.yukonga.miuix.kmp.basic.TextField(
                    value = folderNameInput,
                    onValueChange = { folderNameInput = it },
                    label = "文件夹名称（如：推类 / 拉类）",
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionText(text = "取消", onClick = { showNewFolder = false })
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            vm.createFolder(folderNameInput)
                            folderNameInput = ""
                            showNewFolder = false
                        },
                        enabled = folderNameInput.isNotBlank(),
                    ) {
                        Text("创建", style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }

    // ---- 重命名文件夹 ----
    renamingFolder?.let { folder ->
        AppDialog(
            title = "重命名文件夹",
            onDismissRequest = { renamingFolder = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                top.yukonga.miuix.kmp.basic.TextField(
                    value = folderNameInput,
                    onValueChange = { folderNameInput = it },
                    label = "文件夹名称",
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionText(text = "取消", onClick = { renamingFolder = null })
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            vm.renameFolder(folder.id, folderNameInput)
                            renamingFolder = null
                        },
                        enabled = folderNameInput.isNotBlank(),
                    ) {
                        Text("保存", style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }

    // ---- 移动到文件夹 ----
    moveTarget?.let { tpl ->
        AppDialog(
            title = "移动到文件夹",
            summary = tpl.name,
            onDismissRequest = { moveTarget = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val options = buildList {
                    add(null to "未分组")
                    folders.forEach { add(it.id to it.name) }
                }
                options.forEach { (id, name) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        onClick = {
                            vm.moveToFolder(tpl, id)
                            moveTarget = null
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = name,
                                style = MiuixTheme.textStyles.main,
                                modifier = Modifier.weight(1f),
                            )
                            if (tpl.folderId == id) {
                                Text(
                                    text = "当前",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 分组标题 */
@Composable
private fun FolderHeader(
    name: String,
    count: Int,
    isRealFolder: Boolean,
    showOnHome: Boolean,
    onToggleHome: () -> Unit,
    onAddTemplate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isRealFolder) Icons.Filled.Folder else Icons.Filled.Inbox,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = name,
            style = MiuixTheme.textStyles.subtitle,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (isRealFolder) {
            // 首页可见开关
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleHome),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (showOnHome) {
                        Icons.Filled.Visibility
                    } else {
                        Icons.Filled.VisibilityOff
                    },
                    contentDescription = if (showOnHome) "首页显示中，点击隐藏" else "首页已隐藏，点击显示",
                    modifier = Modifier.size(18.dp),
                    tint = if (showOnHome) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
                    },
                )
            }
            ActionText(text = "改名", onClick = onRename)
            ActionText(text = "删除", onClick = onDelete, color = MiuixTheme.colorScheme.error)
        }
        ActionText(text = "+ 模板", onClick = onAddTemplate)
    }
}

@Composable
fun TemplateEditScreen(templateId: Long?, folderId: Long? = null, onBack: () -> Unit) {
    val container = rememberAppContainer()
    val vm: TemplateEditViewModel = viewModel(factory = TemplateEditViewModel.factory(container))
    val state by vm.state.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    var showAddExercise by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf("") }
    var newEquipment by remember { mutableStateOf("") }
    var newMuscle by remember { mutableStateOf("") }
    var restPickerKey by remember { mutableStateOf<Long?>(null) }
    var loaded by remember(templateId) { mutableStateOf(false) }

    // 键盘弹出时隐藏底部「添加动作」栏，把空间让给输入框
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.ime
        .getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

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
                    ActionText(text = "保存", onClick = { vm.save(folderId) { onBack() } },
                        enabled = state.name.isNotBlank() && state.items.isNotEmpty(),)
                },
            )
        },
    ) { padding ->
        // Android 15+ edge-to-edge 下 adjustResize 不生效，需显式避让键盘
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
        ) {
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
            // 卡片 158dp + 上下各 4dp 间距
            val rowHeightPx = with(density) { 166.dp.toPx() }
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
                            .height(158.dp)
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
                                                // 用 VM 里的最新列表定位，避免闭包中的 index 过期导致乱跳
                                                val items = vm.state.value.items
                                                val currentIndex =
                                                    items.indexOfFirst { it.key == item.key }
                                                if (currentIndex >= 0) {
                                                    val shift =
                                                        (dragOffsetY / rowHeightPx).roundToInt()
                                                    if (shift != 0) {
                                                        val target = (currentIndex + shift)
                                                            .coerceIn(0, items.lastIndex)
                                                        if (target != currentIndex) {
                                                            vm.moveItem(currentIndex, target)
                                                            dragOffsetY -=
                                                                (target - currentIndex) * rowHeightPx
                                                        }
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
                                    // 休息时长
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(MiuixTheme.colorScheme.surfaceContainer)
                                            .clickable { restPickerKey = item.key }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = item.restSeconds?.let { "${it}s" } ?: "休息",
                                            fontSize = 10.sp,
                                            color = if (item.restSeconds != null) {
                                                MiuixTheme.colorScheme.primary
                                            } else {
                                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            },
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
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

                                // 目标次数（紧凑输入框，避免浮动标签撑高卡片）
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "目标次数",
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MiuixTheme.colorScheme.surfaceContainer)
                                            .padding(horizontal = 10.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        androidx.compose.foundation.text.BasicTextField(
                                            value = item.targetReps,
                                            onValueChange = {
                                                vm.updateItem(item.key, reps = it)
                                            },
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(
                                                fontSize = 14.sp,
                                                color = MiuixTheme.colorScheme.onSurface,
                                            ),
                                            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                                MiuixTheme.colorScheme.primary
                                            ),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!imeVisible) {
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
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ActionText(
                        text = "+ 新建自定义动作",
                        onClick = { showAddExercise = true },
                    )
                }
                Spacer(Modifier.height(2.dp))
                LazyColumn(modifier = Modifier.height(320.dp)) {
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

    // 模板内设置该动作的休息时长
    restPickerKey?.let { key ->
        val item = state.items.firstOrNull { it.key == key }
        if (item != null) {
            RestSecondsPickerDialog(
                initialSeconds = item.restSeconds ?: 90,
                showSaveAsDefault = false,
                onDismiss = { restPickerKey = null },
                onConfirm = { seconds, _ ->
                    vm.updateItem(key, restSeconds = seconds)
                    restPickerKey = null
                },
                onClear = if (item.restSeconds != null) {
                    {
                        vm.updateItem(key, clearRest = true)
                        restPickerKey = null
                    }
                } else {
                    null
                },
            )
        }
    }

    // 新建自定义动作（分类/器械用选择）
    if (showAddExercise) {
        AddExerciseDialog(
            onDismiss = { showAddExercise = false },
            onConfirm = { name, category, equipment, muscle ->
                vm.addCustomExercise(name, category, equipment, muscle)
                newName = ""
                newCategory = ""
                newEquipment = ""
                newMuscle = ""
                showAddExercise = false
                showPicker = false
            },
        )
    }
}
