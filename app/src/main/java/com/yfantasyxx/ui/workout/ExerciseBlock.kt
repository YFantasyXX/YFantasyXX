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
import com.yfantasyxx.ui.components.pressScale
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
    /** 最近一次体重：自重动作的容量按它折算，null = 还没记过 */
    bodyweightKg: Float? = null,
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
    /** 主组 id → 递减段（没有段的组查不到，按空处理） */
    segments: Map<Long, List<com.yfantasyxx.data.db.SetSegment>> = emptyMap(),
    onAddSegment: (SetLog) -> Unit = {},
    onUpdateSegment: (com.yfantasyxx.data.db.SetSegment, Float, Int) -> Unit = { _, _, _ -> },
    onDeleteSegment: (com.yfantasyxx.data.db.SetSegment) -> Unit = {},
) {
    val completed = item.sets.count { it.completed }
    // 自重动作的重量栏是「额外负重」：0 就是纯徒手，显示成「自重×10」而不是「0×10」
    val isBodyweight = com.yfantasyxx.data.Load.isBodyweight(item.exercise.equipment)
    val lastSummary = if (lastSets.isNotEmpty()) {
        "上次 " + lastSets.take(3).joinToString(" / ") { s ->
            if (isBodyweight) {
                if (s.weightKg > 0f) "+${fmtWeight(s.weightKg)}×${s.reps}" else "自重×${s.reps}"
            } else {
                "${s.weightKg.toInt()}×${s.reps}"
            }
        }
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
                                    .background(com.yfantasyxx.ui.theme.AccentAmber)
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
                                    // 按下时轻微缩小：这是训练中最高频的可点区域，
                                    // 没有反馈的话每次都要低头确认有没有点到
                                    .pressScale(pressedScale = 0.9f)
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
                        // 按下时轻微缩小：折叠按钮很小，只靠水波纹几乎察觉不到
                        .pressScale(pressedScale = 0.85f)
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
                                // 自重动作显示「自重 × n」，加了负重才显示 +X kg
                                if (isBodyweight) {
                                    if (suggestion.weightKg > 0f) {
                                        append("+").append(fmtWeight(suggestion.weightKg)).append(" kg")
                                    } else {
                                        append("自重")
                                    }
                                } else {
                                    append(fmtWeight(suggestion.weightKg)).append(" kg")
                                }
                                append(" × ")
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
                    modifier = Modifier.width(46.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    // 自重动作填的是额外负重，标签要说清楚，否则会被当成总重量
                    text = if (isBodyweight) "负重($unitLabel)" else "重量($unitLabel)",
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
            // 自重动作的容量靠体重折算，没记过体重时容量会是 0：
            // 不说清楚的话，用户只会看到"练了但容量不动"。
            // 延迟 1.5 秒再显示：体重是异步读出来的，首帧还是 null，
            // 立刻显示会在每次进训练页闪一下
            var showBodyweightHint by remember { mutableStateOf(false) }
            LaunchedEffect(isBodyweight, bodyweightKg) {
                showBodyweightHint = false
                if (isBodyweight && bodyweightKg == null) {
                    kotlinx.coroutines.delay(1500)
                    showBodyweightHint = true
                }
            }
            if (showBodyweightHint) {
                Text(
                    text = "自重的容量按体重折算，先去首页记一次体重",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            item.sets.sortedBy { it.setNumber }.forEach { set ->
                val drops = segments[set.id].orEmpty().sortedBy { it.seq }
                SetRow(
                    set = set,
                    segmentCount = drops.size,
                    onUpdateSet = onUpdateSet,
                    onToggleSet = onToggleSet,
                    onDeleteSet = onDeleteSet,
                    onTogglePr = onTogglePr,
                    onAddSegment = { onAddSegment(set) },
                )
                // 递减段紧贴主行下面：矮一档、缩进、没有勾选框，
                // 视觉上就是「这一组里的第二、第三段」，而不是新的一组
                drops.forEach { segment ->
                    DropSetRow(
                        setNumber = set.setNumber,
                        segment = segment,
                        onUpdate = onUpdateSegment,
                        onDelete = onDeleteSegment,
                    )
                }
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
    /** 已经有几段递减：>0 时在组号旁点一个小标记，滑开按钮也会变 */
    segmentCount: Int = 0,
    onUpdateSet: (SetLog, Float, Int) -> Unit,
    onToggleSet: (SetLog) -> Unit,
    onDeleteSet: (SetLog) -> Unit,
    onTogglePr: (SetLog) -> Unit,
    onAddSegment: () -> Unit = {},
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
    val prGold = com.yfantasyxx.ui.theme.AccentAmber
    val background by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            set.isPr -> prGold.copy(alpha = 0.18f)
            completed -> MiuixTheme.colorScheme.primaryContainer
            else -> MiuixTheme.colorScheme.surfaceContainer
        },
        animationSpec = com.yfantasyxx.ui.theme.Motion.standard(),
        label = "setRowBackground",
    )
    val badgeColor by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            set.isPr -> prGold
            completed -> MiuixTheme.colorScheme.primary
            else -> MiuixTheme.colorScheme.surface
        },
        animationSpec = com.yfantasyxx.ui.theme.Motion.standard(),
        label = "setBadge",
    )

    // 左滑露出「递减 / PR / 删除」
    val density = LocalDensity.current
    // 48 + 50 + 52 三个按钮 + 4*2 间距 + 6 右边距 = 164，留 4dp 余量
    val maxSwipe = with(density) { 168.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    // 按组 id 记忆：LazyColumn 复用行槽位时，上一行没走完的回弹位移会带到下一行，
    // 表现是删掉一组之后，新顶上来的那组莫名其妙歪着
    var rawOffset by remember(set.id) { mutableStateOf(0f) }
    val animatedOffset by androidx.compose.animation.core.animateFloatAsState(
        targetValue = rawOffset,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "setSwipeOffset",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            // 只裁形状不画底色：滑开后露出的那块要跟卡片同色，
            // 铺一层 surface 会在深色主题下露出一条比卡片更黑的底
            .clip(RoundedCornerShape(16.dp)),
    ) {
        // 底层：递减 + PR + 删除（被上层组行完全遮住，滑开才露出）
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 递减：在最左边，因为它是这一排里最常用的一个（降重量续做几乎每次都点），
            // 滑开幅度最小的时候先露出来的就是它
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.primary)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        rawOffset = 0f
                        onAddSegment()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // 已经有一段了就写「再递减」：再点是继续往下加第三段
                    text = if (segmentCount > 0) "再减" else "递减",
                    fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onPrimary,
                )
            }
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
            // 组号 + 这一组做了多久。
            //
            // 用时叠在组号下面，而不是在右边单开一列：这一行里两个数字步进器
            // 各要 124dp（30 圆钮 + 58 数字 + 30 圆钮），再插一列 38dp 实测会把
            // 两个「＋」各挤掉近 20dp（被行的圆角裁掉）。
            // 竖着叠只多占 20dp 宽，行高仍由步进器决定，完成后也不会跳高度。
            Column(
                modifier = Modifier.width(46.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(badgeColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = set.setNumber.toString(),
                        fontSize = 11.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = when {
                            set.isPr -> androidx.compose.ui.graphics.Color.White
                            completed -> MiuixTheme.colorScheme.onPrimary
                            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
                // 「这一组做了多久」= 打勾时刻 − 上一次休息结束（见 SetClock）。
                // 没做的组、以及 v15 之前的老记录没有这个值，只留一个空行 ——
                // 不能整块不渲染，否则打勾那一刻组号会往上跳半个字高
                Text(
                    text = com.yfantasyxx.ui.components.formatSetDuration(set.durationMs).ifEmpty { " " },
                    fontSize = 10.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(6.dp))
            NumberStepper(
                value = weight,
                onValueChange = { weight = it },
                step = 2.5f,
                // 按组身份隔离输入态：勾选、增删组会让行槽位重排，
                // 不隔离的话「正在编辑」会跟着格子跑到别的组上
                rememberKey = set.id,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            NumberStepper(
                value = reps.toFloat(),
                onValueChange = { reps = it.toInt() },
                step = 1f,
                rememberKey = set.id,
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

/**
 * 递减段：主组下面「降重量接着做」的那一段，比正常组矮一档。
 *
 * 没有勾选框 —— 完成状态挂在上面那个主组上，勾主组 = 整组（含所有段）做完，
 * 组数也只算一组。左滑露出删除，和主行同一套手势；删掉一段后面的段号会自动补上。
 */
@Composable
private fun DropSetRow(
    setNumber: Int,
    segment: com.yfantasyxx.data.db.SetSegment,
    onUpdate: (com.yfantasyxx.data.db.SetSegment, Float, Int) -> Unit,
    onDelete: (com.yfantasyxx.data.db.SetSegment) -> Unit,
) {
    var weight by remember(segment.id) { mutableStateOf(segment.weightKg) }
    var reps by remember(segment.id) { mutableStateOf(segment.reps) }
    LaunchedEffect(weight, reps) {
        if (weight != segment.weightKg || reps != segment.reps) onUpdate(segment, weight, reps)
    }
    // 反向同步：库里的值被程序改了（比如以后做「整组一起改重量」）时要跟得上，
    // 但本地有没写回去的改动时不能覆盖 —— 和 SetRow 同一套判据
    var lastDbWeight by remember(segment.id) { mutableStateOf(segment.weightKg) }
    var lastDbReps by remember(segment.id) { mutableStateOf(segment.reps) }
    LaunchedEffect(segment.weightKg) {
        if (segment.weightKg != lastDbWeight) {
            if (weight == lastDbWeight) weight = segment.weightKg
            lastDbWeight = segment.weightKg
        }
    }
    LaunchedEffect(segment.reps) {
        if (segment.reps != lastDbReps) {
            if (reps == lastDbReps) reps = segment.reps
            lastDbReps = segment.reps
        }
    }

    val density = LocalDensity.current
    // 只需露出一个「删除」，滑一点点就够
    val maxSwipe = with(density) { 56.dp.toPx() }
    var rawOffset by remember(segment.id) { mutableStateOf(0f) }
    val animatedOffset by androidx.compose.animation.core.animateFloatAsState(
        targetValue = rawOffset,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "dropSwipe",
    )
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 缩进 + 上下留白只有主行的一半：一眼看出它挂在上面那一组，
            // 而不是又开了一组
            .padding(start = 12.dp)
            .padding(vertical = 1.dp)
            // 同上：不铺底色，滑开后直接露卡片本体
            .clip(RoundedCornerShape(14.dp)),
    ) {
        // 底层：删除
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp)
                .width(48.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.error)
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    rawOffset = 0f
                    onDelete(segment)
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "删除",
                fontSize = 11.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onError,
            )
        }

        // 上层：小一号的组行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .clip(RoundedCornerShape(14.dp))
                // 必须不透明：半透明会把底下的红色「删除」透出来，
                // 看起来像按钮漏了一角在行尾
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .pointerInput(segment.id) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            rawOffset = (rawOffset + dragAmount).coerceIn(-maxSwipe, 0f)
                        },
                        onDragEnd = {
                            rawOffset = if (rawOffset < -maxSwipe / 3f) -maxSwipe else 0f
                        },
                        onDragCancel = { rawOffset = 0f },
                    )
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 和主行同样的列宽：46dp 标号列 + 6 间隔 + 两个 1f + 26 勾选框位，
            // 少留一格的话两个数字会跟上面那一行错开
            Row(
                modifier = Modifier.width(46.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 一小截竖线：把「这一行挂在上面那一组」画出来
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(15.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.45f)),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "$setNumber${segment.label}",
                    fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(6.dp))
            NumberStepper(
                value = weight,
                onValueChange = { weight = it },
                step = 2.5f,
                rememberKey = segment.id,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            NumberStepper(
                value = reps.toFloat(),
                onValueChange = { reps = it.toInt() },
                step = 1f,
                rememberKey = segment.id,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(26.dp))
        }
    }
}
