package com.yfantasyxx.ui.plans

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yfantasyxx.data.TrainingCycle
import com.yfantasyxx.data.db.PlanEntity
import com.yfantasyxx.data.db.WorkoutTemplate
import com.yfantasyxx.ui.components.ActionText
import com.yfantasyxx.ui.components.AppDialog
import com.yfantasyxx.ui.rememberAppContainer
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 编辑中的计划：天用模板 id 表示，null 是休息日 */
private data class PlanDraft(
    val id: Long?,
    val name: String,
    val days: List<Long?>,
)

/**
 * 训练计划管理。
 *
 * 一个计划就是一串「天」：每天要么是某个模板，要么是休息日；
 * 没开计划时首页只显示模板，开着的那个计划决定首页提示「今天第几天、练什么」。
 * 同时只能开一个，所以列表里用开关，开一个会自动关掉别的。
 */
@Composable
fun TrainingPlanDialog(onDismiss: () -> Unit) {
    val container = rememberAppContainer()
    val vm: PlansViewModel = viewModel(factory = PlansViewModel.factory(container))
    val plans by vm.plans.collectAsState()
    val allDays by vm.days.collectAsState()
    val templates by vm.templates.collectAsState()

    var draft by remember { mutableStateOf<PlanDraft?>(null) }
    var picking by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }

    val daysByPlan = remember(allDays) { allDays.groupBy { it.planId } }
    val editing = draft
    val pickIndex = picking

    val title = when {
        pickIndex != null -> "第 ${pickIndex + 1} 天练什么"
        editing != null -> if (editing.id == null) "新建训练计划" else "编辑训练计划"
        else -> "训练计划"
    }

    AppDialog(
        title = title,
        summary = when {
            pickIndex != null -> "选一个模板，或者把这一天设为休息日"
            editing != null -> "把这串天排成循环：练到最后一个之后回到第 1 天"
            else -> "打开其中一个，首页就会按它提示今天该练什么"
        },
        onDismissRequest = onDismiss,
        fullWidth = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when {
                /* ---------- 选某一天 ---------- */
                pickIndex != null && editing != null -> {
                    ActionText(text = "← 返回", onClick = { picking = null })
                    PickDayContent(
                        templates = templates,
                        keyword = keyword,
                        onKeywordChange = { keyword = it },
                        selectedId = editing.days.getOrNull(pickIndex),
                        onPick = { templateId ->
                            draft = editing.copy(
                                days = editing.days.toMutableList().also { it[pickIndex] = templateId }
                            )
                            picking = null
                        },
                    )
                }

                /* ---------- 编辑计划 ---------- */
                editing != null -> {
                    PlanEditorContent(
                        draft = editing,
                        templates = templates,
                        onNameChange = { draft = editing.copy(name = it) },
                        onPickDay = { picking = it },
                        onMove = { from, to ->
                            val list = editing.days.toMutableList()
                            if (to in list.indices) {
                                val item = list.removeAt(from)
                                list.add(to, item)
                                draft = editing.copy(days = list)
                            }
                        },
                        onRemove = { index ->
                            draft = editing.copy(
                                days = editing.days.toMutableList().also { it.removeAt(index) }
                            )
                        },
                        onAddDay = { templateId ->
                            val list = editing.days + templateId
                            draft = editing.copy(days = list)
                            if (templateId == null) picking = null else picking = list.lastIndex
                        },
                        confirmDelete = confirmDelete,
                        onAskDelete = { confirmDelete = true },
                        onCancelDelete = { confirmDelete = false },
                        onDelete = {
                            editing.id?.let { vm.delete(it) }
                            confirmDelete = false
                            draft = null
                        },
                        onCancel = {
                            draft = null
                            confirmDelete = false
                        },
                        onSave = {
                            vm.save(editing.id, editing.name, editing.days) { draft = null }
                        },
                    )
                }

                /* ---------- 计划列表 ---------- */
                else -> {
                    if (plans.isEmpty()) {
                        Text(
                            text = "还没有训练计划。建一个「练三休一」的计划：推 / 拉 / 腿 / 休息，" +
                                "之后首页就只按这个计划提示今天该练哪一天。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    plans.forEach { plan ->
                        PlanRow(
                            plan = plan,
                            summary = planSummary(daysByPlan[plan.id].orEmpty().map { it.templateId }),
                            onToggleActive = { vm.setActive(if (plan.isActive) null else plan.id) },
                            onEdit = {
                                draft = PlanDraft(
                                    id = plan.id,
                                    name = plan.name,
                                    days = daysByPlan[plan.id].orEmpty()
                                        .sortedBy { it.position }
                                        .map { it.templateId },
                                )
                                confirmDelete = false
                            },
                        )
                    }
                    if (plans.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = {
                            draft = PlanDraft(
                                id = null,
                                name = "",
                                days = emptyList(),
                            )
                            confirmDelete = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "",
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("新建计划", style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }
}

/** 「4 天 · 练 3 休 1」 */
private fun planSummary(days: List<Long?>): String {
    if (days.isEmpty()) return "还没有排天"
    val training = days.count { it != null }
    val rest = days.size - training
    return if (rest > 0) "${days.size} 天 · 练 $training 休 $rest" else "${days.size} 天循环"
}

@Composable
private fun PlanRow(
    plan: PlanEntity,
    summary: String,
    onToggleActive: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onEdit)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = plan.name,
                style = MiuixTheme.textStyles.main,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (plan.isActive) "使用中 · $summary" else summary,
                style = MiuixTheme.textStyles.footnote1,
                color = if (plan.isActive) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = plan.isActive, onCheckedChange = { onToggleActive() })
    }
}

@Composable
private fun PlanEditorContent(
    draft: PlanDraft,
    templates: List<WorkoutTemplate>,
    onNameChange: (String) -> Unit,
    onPickDay: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAddDay: (Long?) -> Unit,
    confirmDelete: Boolean,
    onAskDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val nameOf = remember(templates) { templates.associate { it.id to it.name } }

    TextField(
        value = draft.name,
        onValueChange = onNameChange,
        label = "计划名称（如：三分化练三休一）",
        singleLine = true,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "第几天练什么",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    Spacer(Modifier.height(4.dp))

    if (draft.days.isEmpty()) {
        Text(
            text = if (templates.isEmpty()) {
                "还没有模板。先去模板页建一个（比如「推」「拉」「腿」），再回来排计划。"
            } else {
                "点下面的「训练日」开始排，休息日也占一格。"
            },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }

    draft.days.forEachIndexed { index, templateId ->
        DayRow(
            index = index,
            templateId = templateId,
            name = templateId?.let { nameOf[it] } ?: "休息日",
            canMoveUp = index > 0,
            canMoveDown = index < draft.days.lastIndex,
            onPick = { onPickDay(index) },
            onMoveUp = { onMove(index, index - 1) },
            onMoveDown = { onMove(index, index + 1) },
            onRemove = { onRemove(index) },
        )
    }

    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        ActionText(
            text = "＋ 训练日",
            enabled = templates.isNotEmpty() &&
                draft.days.size < TrainingCycle.MAX_DAYS,
            onClick = { onAddDay(templates.firstOrNull()?.id) },
        )
        ActionText(
            text = "＋ 休息日",
            enabled = draft.days.size < TrainingCycle.MAX_DAYS,
            onClick = { onAddDay(null) },
        )
    }

    Spacer(Modifier.height(10.dp))
    Text(
        text = "休息日占满一整天：到了那天提示「休息」，第二天自动进入下一格。" +
            "改计划不会动已经练过的记录。",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )

    Spacer(Modifier.height(14.dp))
    if (confirmDelete) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "删除后这个计划就没了，训练记录不受影响。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            ActionText(text = "取消", onClick = onCancelDelete)
            ActionText(text = "删除", onClick = onDelete, color = MiuixTheme.colorScheme.error)
        }
        Spacer(Modifier.height(6.dp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (draft.id != null) {
            ActionText(
                text = "删除计划",
                onClick = onAskDelete,
                color = MiuixTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.weight(1f))
        ActionText(text = "取消", onClick = onCancel)
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onSave,
            enabled = draft.days.isNotEmpty() && draft.days.any { it != null },
        ) {
            Text("保存", style = MiuixTheme.textStyles.button)
        }
    }
}

/** 编辑器里的一天：序号 + 名称（点开换）+ 上移 / 下移 / 删除 */
@Composable
private fun DayRow(
    index: Int,
    templateId: Long?,
    name: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val isRest = templateId == null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (isRest) {
                        MiuixTheme.colorScheme.surfaceContainer
                    } else {
                        MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${index + 1}",
                fontSize = 11.sp,
                color = if (isRest) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.primary
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onPick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MiuixTheme.textStyles.paragraph,
                color = if (isRest) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "换",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(2.dp))
        SmallIconButton(
            icon = Icons.Filled.KeyboardArrowUp,
            description = "上移",
            enabled = canMoveUp,
            onClick = onMoveUp,
        )
        SmallIconButton(
            icon = Icons.Filled.KeyboardArrowDown,
            description = "下移",
            enabled = canMoveDown,
            onClick = onMoveDown,
        )
        SmallIconButton(
            icon = Icons.Filled.Close,
            description = "删除这一天",
            enabled = true,
            onClick = onRemove,
        )
    }
}

@Composable
private fun SmallIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(16.dp),
            tint = if (enabled) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            },
        )
    }
}

/** 选择某一天：休息日 + 全部模板 */
@Composable
private fun PickDayContent(
    templates: List<WorkoutTemplate>,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    selectedId: Long?,
    onPick: (Long?) -> Unit,
) {
    if (templates.size > 6) {
        TextField(
            value = keyword,
            onValueChange = onKeywordChange,
            label = "搜索模板",
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
    }
    PickRow(
        label = "休息日",
        description = "这一天不练，第二天自动进入下一格",
        selected = selectedId == null,
        onClick = { onPick(null) },
    )
    templates
        .filter { keyword.isBlank() || it.name.contains(keyword, true) }
        .forEach { template ->
            PickRow(
                label = template.name,
                description = template.note,
                selected = selectedId == template.id,
                onClick = { onPick(template.id) },
            )
        }
}

@Composable
private fun PickRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.paragraph,
                color = if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "当前选择",
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}
