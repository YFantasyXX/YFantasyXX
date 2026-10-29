package com.yfantasyxx.ui.workout

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfantasyxx.data.Progression
import com.yfantasyxx.data.ProgressionMode
import com.yfantasyxx.data.RepRange
import com.yfantasyxx.data.db.SessionExerciseWithSets
import com.yfantasyxx.data.db.SetLog
import com.yfantasyxx.ui.components.ActionText
import com.yfantasyxx.ui.components.AppDialog
import com.yfantasyxx.ui.components.NumberStepper
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 训练页的单个动作卡片：标题行（完成数 / 收纳 / 移除）+ 组列表。
 * 收纳后只留标题行，方便在一屏里拖动排序。
 */
@Composable
internal fun ExerciseBlock(
    item: SessionExerciseWithSets,
    lastSets: List<SetLog>,
    unitLabel: String,
    collapsed: Boolean = false,
    onToggleCollapse: () -> Unit = {},
    /** 超级组编号，非空时在标题行显示标记 */
    supersetGroup: Int? = null,
    /** 最后一个动作没有「下一个」，不能组超级组 */
    canSupersetWithNext: Boolean = true,
    modifier: Modifier = Modifier,
    onUpdateSet: (SetLog, Float, Int) -> Unit,
    onToggleSet: (SetLog) -> Unit,
    onAddSet: () -> Unit,
    onRemoveExercise: () -> Unit,
    onDeleteSet: (SetLog) -> Unit,
    onTogglePr: (SetLog) -> Unit,
    onCopyPreviousSet: () -> Unit = {},
    /** 打开这个动作的历史记录（训练中参考重量次数） */
    onOpenHistory: () -> Unit = {},
    onReplaceExercise: () -> Unit = {},
    onToggleSuperset: () -> Unit = {},
    onOpenPlateCalculator: () -> Unit = {},
    /** 模板里的进阶策略；关闭时不显示建议 */
    progressionMode: ProgressionMode = ProgressionMode.OFF,
    onApplyProgression: (Float, Int) -> Unit = { _, _ -> },
) {
    val completed = item.sets.count { it.completed }
    val lastSummary = if (lastSets.isNotEmpty()) {
        "上次 " + lastSets.take(3).joinToString(" / ") { "${it.weightKg.toInt()}×${it.reps}" }
    } else ""
    // 动作级操作收进一个菜单：标题行原来挤了收纳 + 删除两个按钮，
    // 再加替换/超级组就放不下了，而且删除放这么显眼也容易误触。
    var showMenu by remember { mutableStateOf(false) }

    // 进阶建议：纯计算，输入是上次训练的工作组 + 目标区间 + 策略。
    // 规则在 Progression 里，这里只负责展示与「应用」。
    val range = remember(item.sessionExercise) {
        item.sessionExercise.let { s ->
            s.plannedRepsMin?.let { min -> s.plannedRepsMax?.let { max -> RepRange(min, max) } }
        } ?: RepRange.parseOrDefault(item.sessionExercise.plannedReps)
    }
    val suggestion = remember(lastSets, progressionMode, range, item.sets) {
        Progression.suggest(
            mode = progressionMode,
            lastWeight = item.sets.firstOrNull()?.weightKg ?: 0f,
            range = range,
            lastSets = lastSets,
            category = item.exercise.category,
            equipment = item.exercise.equipment,
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ---- 动作标题行 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.exercise.name,
                            fontSize = 17.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (supersetGroup != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "超级组",
                                fontSize = 10.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MiuixTheme.colorScheme.primary)
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                        if (item.sets.isNotEmpty() && item.sets.all { it.isWarmup }) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "热身",
                                fontSize = 10.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(androidx.compose.ui.graphics.Color(0xFFFF9F0A))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    // 副标题后面挂一个「历史」小标签：训练中想知道上次练了多少，
                    // 不用退出去翻历史页。收起状态下不显示，保持一行的高度。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (collapsed) {
                                "${item.sets.size} 组 · 已完成 $completed 组"
                            } else buildString {
                                append(item.exercise.category)
                                if (item.exercise.equipment.isNotEmpty()) {
                                    append(" · ")
                                    append(item.exercise.equipment)
                                }
                                // 模板设定的目标次数，如「目标 8-12」
                                val planned = item.sessionExercise.plannedReps
                                if (!planned.isNullOrBlank()) {
                                    append(" · 目标 ")
                                    append(planned)
                                }
                                if (lastSummary.isNotEmpty()) {
                                    append(" · ")
                                    append(lastSummary)
                                } else {
                                    append(" · 首次训练")
                                }
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!collapsed) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "历史",
                                fontSize = 11.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
                                    .clickable(onClick = onOpenHistory)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                val allDone = item.sets.isNotEmpty() && completed == item.sets.size
                Text(
                    text = "$completed/${item.sets.size}",
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = if (allDone) {
                        MiuixTheme.colorScheme.onPrimary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (allDone) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.surfaceContainer
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(6.dp))
                // 收纳/展开组数
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .clickable(onClick = onToggleCollapse),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (collapsed) {
                            Icons.Filled.ExpandMore
                        } else {
                            Icons.Filled.ExpandLess
                        },
                        contentDescription = if (collapsed) "展开组数" else "收纳组数",
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .clickable { showMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多操作",
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (collapsed) return@Column

            // ---- 进阶建议 ----
            // 放在标题下面而不是弹窗：训练时要的是「一眼看到该上多少」，
            // 点一下就能把建议填进所有未完成的组。
            if (suggestion != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable {
                            onApplyProgression(suggestion.weightKg, suggestion.reps)
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = buildString {
                                append("进阶 ")
                                append(fmtWeight(suggestion.weightKg))
                                append(" kg × ")
                                append(suggestion.reps)
                                if (suggestion.addedWeight) append("  ↑")
                            },
                            fontSize = 13.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.primary,
                        )
                        Text(
                            text = suggestion.reason,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "应用",
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            top.yukonga.miuix.kmp.basic.HorizontalDivider(
                color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(4.dp))
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
                    onTogglePr = onTogglePr,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionText(text = "+ 添加一组", onClick = onAddSet)
                Spacer(Modifier.width(4.dp))
                // 复制上一组：最省手指的一个操作。
                // 同重量做组时，重量本来就已经填好了，真正要改的是次数。
                ActionText(text = "复制上一组", onClick = onCopyPreviousSet)
            }
        }
    }

    if (showMenu) {
        AppDialog(
            title = item.exercise.name,
            onDismissRequest = { showMenu = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MenuItem("替换动作", "换成同类动作，已完成的组保留") {
                    showMenu = false
                    onReplaceExercise()
                }
                MenuItem("杠铃片计算器", "按当前重量反推每边挂什么片") {
                    showMenu = false
                    onOpenPlateCalculator()
                }
                if (supersetGroup != null) {
                    MenuItem("退出超级组", "恢复成单独做完再休息") {
                        showMenu = false
                        onToggleSuperset()
                    }
                } else if (canSupersetWithNext) {
                    MenuItem("和下一个组成超级组", "两个动作交替做，组末才休息") {
                        showMenu = false
                        onToggleSuperset()
                    }
                }
                MenuItem(
                    text = "移除动作",
                    hint = "本次训练的组记录会一起删除",
                    danger = true,
                ) {
                    showMenu = false
                    onRemoveExercise()
                }
            }
        }
    }
}

/** 动作菜单里的一行 */
@Composable
private fun MenuItem(
    text: String,
    hint: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.main,
            color = if (danger) {
                MiuixTheme.colorScheme.error
            } else {
                MiuixTheme.colorScheme.onSurface
            },
        )
        Text(
            text = hint,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 重量显示：整数不带小数点 */
private fun fmtWeight(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.1f", v)

@Composable
private fun SetRow(
    set: SetLog,
    onUpdateSet: (SetLog, Float, Int) -> Unit,
    onToggleSet: (SetLog) -> Unit,
    onDeleteSet: (SetLog) -> Unit,
    onTogglePr: (SetLog) -> Unit,
) {
    var weight by remember(set.id) { mutableStateOf(set.weightKg) }
    var reps by remember(set.id) { mutableStateOf(set.reps) }
    LaunchedEffect(weight, reps) {
        if (weight != set.weightKg || reps != set.reps) {
            onUpdateSet(set, weight, reps)
        }
    }
    // 反向同步：程序也可能改这一组（复制上一组 / 应用进阶建议）。
    // 判据是「本地值等于上次看到的库值」——说明本地没有还没写回去的改动，
    // 这时候库值变了就是程序改的，可以安全覆盖；
    // 否则会出现在输入过程中被写库回包打回原值的问题。
    var lastDbWeight by remember(set.id) { mutableStateOf(set.weightKg) }
    var lastDbReps by remember(set.id) { mutableStateOf(set.reps) }
    LaunchedEffect(set.weightKg) {
        if (set.weightKg != lastDbWeight) {
            if (weight == lastDbWeight) weight = set.weightKg
            lastDbWeight = set.weightKg
        }
    }
    LaunchedEffect(set.reps) {
        if (set.reps != lastDbReps) {
            if (reps == lastDbReps) reps = set.reps
            lastDbReps = set.reps
        }
    }

    val completed = set.completed
    val prGold = androidx.compose.ui.graphics.Color(0xFFFF9F0A)
    val background by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            set.isPr -> prGold.copy(alpha = 0.18f)
            completed -> MiuixTheme.colorScheme.primaryContainer
            else -> MiuixTheme.colorScheme.surfaceContainer
        },
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "setRowBackground",
    )
    val badgeColor by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            set.isPr -> prGold
            completed -> MiuixTheme.colorScheme.primary
            else -> MiuixTheme.colorScheme.surface
        },
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "setBadge",
    )

    // 左滑露出删除
    val density = LocalDensity.current
    val maxSwipe = with(density) { 112.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    var rawOffset by remember { mutableStateOf(0f) }
    val animatedOffset by androidx.compose.animation.core.animateFloatAsState(
        targetValue = rawOffset,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "setSwipeOffset",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surface),
    ) {
        // 底层：PR + 删除（被上层组行完全遮住，滑开才露出）
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(prGold)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        rawOffset = 0f
                        onTogglePr(set)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (set.isPr) "取消PR" else "PR",
                    fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.error)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        rawOffset = 0f
                        onDeleteSet(set)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "删除",
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onError,
                )
            }
        }

        // 上层：组行内容
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .clip(RoundedCornerShape(16.dp))
                .background(background)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = rawOffset + dragAmount
                            val old = rawOffset
                            rawOffset = next.coerceIn(-maxSwipe, 0f)
                            // 刚滑过阈值时给一次轻震
                            if (old > -maxSwipe / 3f && rawOffset <= -maxSwipe / 3f) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = {
                            rawOffset = if (rawOffset < -maxSwipe / 3f) -maxSwipe else 0f
                        },
                        onDragCancel = { rawOffset = 0f },
                    )
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                    color = when {
                        set.isPr -> androidx.compose.ui.graphics.Color.White
                        completed -> MiuixTheme.colorScheme.onPrimary
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
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
}
