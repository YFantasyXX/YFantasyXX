package com.yfantasyxx.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import com.yfantasyxx.ui.components.AppDialog
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.yfantasyxx.data.db.SessionSummary
import com.yfantasyxx.ui.components.InfoRow
import com.yfantasyxx.ui.components.NumberStepper
import com.yfantasyxx.ui.components.SectionHeader
import com.yfantasyxx.ui.components.StatCard
import com.yfantasyxx.ui.components.formatVolume
import com.yfantasyxx.ui.plans.TrainingPlanDialog
import com.yfantasyxx.ui.rememberAppContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.platform.LocalContext
import com.yfantasyxx.util.CrashLogger
import com.yfantasyxx.ui.components.ActionText
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.style.TextOverflow
import com.yfantasyxx.data.TrainingPlan

@Composable
fun HomeScreen(
    onStartWorkout: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onStartFromTemplate: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val container = rememberAppContainer()
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val state by vm.uiState.collectAsState()
    val latestWeight by vm.latestWeightKg.collectAsState()
    var showWeightDialog by remember { mutableStateOf(false) }
    var showPlans by remember { mutableStateOf(false) }
    var weightInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    var crashText by remember { mutableStateOf(CrashLogger.readLast(context)) }

    Scaffold(
        topBar = { SmallTopAppBar(title = "YFantasyXX") },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = 24.dp + com.yfantasyxx.ui.LocalBottomBarSpace.current
            ),
        ) {
            // ---- 上次崩溃提示（直接展示，无需点击，避免二次崩溃） ----
            if (crashText != null) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "上次运行出现异常",
                                style = MiuixTheme.textStyles.main,
                                color = MiuixTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "请截图这一段发给开发者",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = "日志同时保存在 Android/data/${context.packageName}/files/logs/",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = summarizeCrash(crashText.orEmpty()),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                ActionText(text = "复制", onClick = {
                                    // 用系统剪贴板而不是 Compose 的 LocalClipboardManager：
                                    // 后者已废弃，新 API 是挂起式的，为一个按钮不值得引入协程
                                    context.getSystemService(android.content.ClipboardManager::class.java)
                                        ?.setPrimaryClip(
                                            android.content.ClipData.newPlainText(
                                                "崩溃日志",
                                                crashText.orEmpty(),
                                            )
                                        )
                                })
                                Spacer(Modifier.width(8.dp))
                                ActionText(text = "清除", onClick = {
                                    CrashLogger.clear(context)
                                    crashText = null
                                })
                            }
                        }
                    }
                }
            }

            // ---- 进行中的训练 ----
            state.activeSession?.let { active ->
                item {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        onClick = onStartWorkout,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "训练进行中",
                                style = MiuixTheme.textStyles.subtitle,
                                color = MiuixTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "已完成 ${active.completedSetCount}/${active.plannedSetCount} 组 · " +
                                    formatElapsed(active.startTime),
                                style = MiuixTheme.textStyles.paragraph,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onStartWorkout) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.height(18.dp),
                                )
                                Text("  继续训练", style = MiuixTheme.textStyles.button)
                            }
                        }
                    }
                }
            }

            // ---- 本周概览 ----
            item {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "本周概览",
                            style = MiuixTheme.textStyles.subtitle,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            StatCard("训练次数", state.weekSessionCount.toString(), "次")
                            StatCard("训练容量", formatVolume(state.weekVolume), state.weightUnitLabel)
                            StatCard("连续周数", state.streakWeeks.toString(), "周")
                            StatCard(
                                "当前体重",
                                latestWeight?.let {
                                    String.format(
                                        "%.1f",
                                        com.yfantasyxx.data.prefs.WeightUnit
                                            .from(state.settings.weightUnit.label)
                                            .fromKg(it)
                                    )
                                } ?: "--",
                                state.weightUnitLabel,
                            )
                        }

                        // ---- 周目标 ----
                        // 没设目标时给一个入口，而不是空白：不然用户不知道有这个功能
                        Spacer(Modifier.height(14.dp))
                        if (state.settings.weeklyGoal > 0) {
                            val goal = state.settings.weeklyGoal
                            val done = state.weekSessionCount
                            val ratio = (done.toFloat() / goal).coerceIn(0f, 1f)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "本周目标",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "$done / $goal 次" +
                                        if (done >= goal) " · 已达成" else "",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = if (done >= goal) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                                    .background(MiuixTheme.colorScheme.surfaceContainer),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(ratio)
                                        .height(6.dp)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                                        .background(MiuixTheme.colorScheme.primary),
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onOpenSettings)
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "设定每周训练目标",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.height(16.dp),
                                    tint = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            // ---- 训练计划：按计划走，不看星期几 ----
            state.plan?.let { plan ->
                item {
                    Spacer(Modifier.height(12.dp))
                    PlanCard(
                        plan = plan,
                        onStart = {
                            plan.upNextTemplateId?.let { id ->
                                vm.startWorkout(id) { onStartFromTemplate() }
                            }
                        },
                        onOpenPlan = { showPlans = true },
                    )
                }
            }

            // ---- 还没设计划时的入口 ----
            if (state.plan == null) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        onClick = { showPlans = true },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Autorenew,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .weight(1f),
                            ) {
                                Text("创建训练计划", style = MiuixTheme.textStyles.main)
                                Text(
                                    text = "把模板排成循环：推 / 拉 / 腿 / 休息，首页按计划提示今天练哪天",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            // ---- 开始训练 ----
            item {
                SectionHeader("开始训练", trailing = {
                    Button(onClick = onOpenTemplates) { Text("管理模板", style = MiuixTheme.textStyles.button) }
                })
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    onClick = {
                        vm.startWorkout(null) { onStartFromTemplate() }
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(0.dp))
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text("空训练", style = MiuixTheme.textStyles.main)
                            Text(
                                "从零开始，随时添加动作",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            // ---- 模板快捷开始 ----
            if (state.templates.isNotEmpty()) {
                item { SectionHeader("从模板开始") }
                items(state.templates.take(6), key = { it.id }) { tpl ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        onClick = { vm.startWorkout(tpl.id) { onStartFromTemplate() } },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DateRange,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                            )
                            Text(
                                text = tpl.name,
                                style = MiuixTheme.textStyles.main,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }

            // ---- 记录体重 ----
            item {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    onClick = {
                        weightInput = latestWeight?.toString() ?: ""
                        showWeightDialog = true
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("⚖️", fontSize = 20.sp)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text("记录今日体重", style = MiuixTheme.textStyles.main)
                            Text(
                                "追踪体重变化趋势",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            // ---- 最近训练 ----
            item { SectionHeader("最近训练") }
            if (state.recentSessions.isEmpty()) {
                item {
                    com.yfantasyxx.ui.components.EmptyState(
                        text = "还没有训练记录\n点上方开始第一次训练吧",
                    )
                }
            } else {
                items(state.recentSessions, key = { it.id }) { session ->
                    SessionRow(session = session, unitLabel = state.weightUnitLabel, onClick = { onOpenSession(session.id) })
                }
            }
        }
    }

    if (showWeightDialog) {
        var weightValue by remember { mutableStateOf(weightInput.toFloatOrNull() ?: 60f) }
        AppDialog(
            title = "记录今日体重",
            onDismissRequest = { showWeightDialog = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "单位：${state.weightUnitLabel}",
                    style = MiuixTheme.textStyles.paragraph,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                NumberStepper(
                    value = weightValue,
                    onValueChange = { weightValue = it },
                    step = 0.5f,
                    unit = " ${state.weightUnitLabel}",
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = if (weightValue == weightValue.toInt().toFloat()) {
                        weightValue.toInt().toString()
                    } else {
                        String.format("%.1f", weightValue)
                    },
                    onValueChange = { v ->
                        v.toFloatOrNull()?.let { weightValue = it }
                    },
                    label = "体重",
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    ),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { showWeightDialog = false }) {
                        Text("取消", style = MiuixTheme.textStyles.button)
                    }
                    Spacer(Modifier.height(0.dp))
                    Button(onClick = {
                        val kg = com.yfantasyxx.data.prefs.WeightUnit
                            .from(state.settings.weightUnit.label)
                            .toKg(weightValue)
                        vm.recordWeight(kg)
                        showWeightDialog = false
                    }) {
                        Text("保存", style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }

    if (showPlans) {
        TrainingPlanDialog(onDismiss = { showPlans = false })
    }
}

/**
 * 训练计划卡片。
 *
 * 只回答一个问题：「按计划，今天该练哪天」。计划是一串天（模板或休息），
 * 休息日占满一天自动流过，训练日练完才往后走——所以这里没有任何「今天星期几」的判断。
 */
@Composable
private fun PlanCard(
    plan: TrainingPlan,
    onStart: () -> Unit,
    onOpenPlan: () -> Unit,
) {
    val statusMain = when {
        plan.resting -> "休息日"
        plan.trainedToday -> "今天已经练完"
        else -> "今天：${plan.nextName}"
    }
    val statusSub = when {
        plan.resting && plan.restRemaining <= 1 ->
            "休息一天 · 下次 ${plan.upNextName}"
        plan.resting -> "连休 ${plan.restRemaining} 天 · 下次 ${plan.upNextName}"
        plan.trainedToday -> "下次：${plan.upNextName}"
        else -> "第 ${plan.nextIndex + 1}/${plan.dayCount} 天"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Autorenew,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = plan.name,
                    style = MiuixTheme.textStyles.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f),
                )
                // 节奏标签兼入口：点它去管理 / 切换计划
                Row(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenPlan)
                        .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = plan.rhythmLabel,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "管理训练计划",
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            PlanDayStrip(plan)
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = statusMain, style = MiuixTheme.textStyles.main)
                    Text(
                        text = statusSub,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                // 休息日不放大按钮：主按钮会和「休息日」打架，
                // 但也不该拦着想练的人，所以留一个次要入口
                if (plan.resting) {
                    ActionText(text = "提前练 ${plan.upNextName}", onClick = onStart)
                } else {
                    Button(onClick = onStart) {
                        Text("开始", style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }
}

/**
 * 计划里的天：一格一天，休息日和训练日一样占位置。
 * 高亮今天这一格；天数多到挤不下时改成横向滚动，别把每个格子压成一条线。
 */
@Composable
private fun PlanDayStrip(plan: TrainingPlan) {
    val scrollable = plan.dayCount > 8
    Row(
        modifier = if (scrollable) {
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxWidth()
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        plan.slots.forEachIndexed { index, slot ->
            DayChip(
                slot = slot,
                isToday = index == plan.nextIndex,
                modifier = if (scrollable) Modifier.width(52.dp) else Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DayChip(
    slot: com.yfantasyxx.data.PlanDaySlot,
    isToday: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(
                when {
                    isToday -> MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                    slot.isRest -> MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
                    else -> MiuixTheme.colorScheme.surfaceContainer
                }
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (slot.isRest) "休" else slot.name,
            style = MiuixTheme.textStyles.footnote1,
            color = when {
                isToday -> MiuixTheme.colorScheme.primary
                slot.isRest -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                else -> MiuixTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    unitLabel: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(session.startTime)),
                    style = MiuixTheme.textStyles.main,
                )
                Text(
                    text = session.name,
                    style = MiuixTheme.textStyles.paragraph,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${session.exerciseCount} 个动作 · ${session.setCount} 组 · " +
                    "容量 ${formatVolume(session.totalVolume)} $unitLabel",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

private fun formatElapsed(startTime: Long): String {
    val minutes = ((System.currentTimeMillis() - startTime) / 60000).toInt()
    return if (minutes >= 60) {
        "${minutes / 60} 小时 ${minutes % 60} 分钟"
    } else {
        "$minutes 分钟"
    }
}

/** 提取崩溃堆栈的关键信息：异常行 + 前几帧本应用调用 */
private fun summarizeCrash(raw: String): String {
    val lines = raw.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return raw.take(500)
    // 前 6 行是日志头部（时间/版本/机型/系统/分隔线），保留下来便于定位
    val head = lines.take(6)
    val appFrames = lines.filter { it.trimStart().startsWith("at com.yfantasyxx") }.take(6)
    val frames = appFrames.ifEmpty { lines.filter { it.trimStart().startsWith("at ") }.take(6) }
    return (head + frames).joinToString("\n")
}
