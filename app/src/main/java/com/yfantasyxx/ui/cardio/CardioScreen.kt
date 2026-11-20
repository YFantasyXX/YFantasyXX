package com.yfantasyxx.ui.cardio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yfantasyxx.data.CardioControl
import com.yfantasyxx.data.CardioControls
import com.yfantasyxx.data.CardioMetric
import com.yfantasyxx.data.CardioType
import com.yfantasyxx.data.db.CardioSession
import com.yfantasyxx.ui.components.ActionText
import com.yfantasyxx.ui.components.AppDialog
import com.yfantasyxx.ui.components.EmptyState
import com.yfantasyxx.ui.components.NumberStepper
import com.yfantasyxx.ui.components.SectionHeader
import com.yfantasyxx.ui.components.cardInsetColor
import com.yfantasyxx.ui.rememberAppContainer
import com.yfantasyxx.util.CardioClock
import com.yfantasyxx.util.Clock
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Calendar
import com.yfantasyxx.ui.components.GlassButton
import kotlinx.coroutines.delay

/**
 * 有氧模块的入口页：本周概览 + 开始/继续 + 历史记录。
 *
 * 和训练页分开做二级页而不是加进底栏：底栏五个槽位已经等分排满，
 * 再加一格每个图标都会变窄；有氧本身也是「练完顺手来一段」的次级入口。
 */
@Composable
fun CardioScreen(
    onBack: () -> Unit,
    onStart: (CardioType) -> Unit,
) {
    val container = rememberAppContainer()
    val vm: CardioViewModel = viewModel(factory = CardioViewModel.factory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showTypePicker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CardioSession?>(null) }
    var creating by remember { mutableStateOf(false) }

    // 从计时页返回时 VM 还活着，必须主动重读一次，
    // 否则「有氧进行中」这张卡会一直停在进入页面时的状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshWeekWindow()
                vm.refreshActive(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { vm.refreshActive(context) }

    // 进行中那张卡的时长要跟着走。
    // 没有这一句时，卡片上写的是「进入页面那一刻已经练了多久」——停在这里不动，
    // 用户在列表页看着 05:00 发呆三分钟，还是 05:00。
    // 只在这张卡真的存在时才起循环，返回键一离开页面协程就随之取消。
    var now by remember { mutableStateOf(Clock.realNow()) }
    LaunchedEffect(active != null) {
        if (active == null) return@LaunchedEffect
        while (true) {
            now = Clock.realNow()
            delay(500)
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "有氧",
                subtitle = "训练计划之外的耐力记录",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                WeekCard(
                    count = state.weekCount,
                    durationMs = state.weekDurationMs,
                    distanceKm = state.weekDistanceKm,
                )
            }

            // ---- 进行中：优先于「开始」，避免手一滑又开一段 ----
            active?.let { running ->
                item {
                    Spacer(Modifier.height(12.dp))
                    val type = CardioType.from(running.type)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        onClick = { onStart(type) },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(MiuixTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp),
                            ) {
                                Text(
                                    text = "${type.label}进行中",
                                    style = MiuixTheme.textStyles.main,
                                )
                                Text(
                                    text = if (running.running) {
                                        "已经 ${formatClock(running.elapsedMs(now))} · 点一下回去看"
                                    } else {
                                        "已暂停 ${formatClock(running.elapsedMs(now))} · 点一下继续"
                                    },
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }

            // ---- 开始 ----
            item {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    onClick = {
                        if (active != null) {
                            onStart(CardioType.from(active?.type))
                        } else {
                            showTypePicker = true
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MiuixTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text(
                                text = if (active != null) "返回计时" else "开始有氧",
                                style = MiuixTheme.textStyles.main,
                            )
                            Text(
                                text = "跑步、快走、骑行、椭圆机…结束后填距离",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    ActionText(text = "补录一条", onClick = { creating = true })
                }
            }

            item { SectionHeader("最近记录") }

            if (state.recent.isEmpty()) {
                item {
                    EmptyState(
                        text = "还没有有氧记录\n练完力量来一段，或者点上面的「补录一条」",
                    )
                }
            } else {
                items(state.recent, key = { "cardio_${it.id}" }) { session ->
                    CardioRow(
                        session = session,
                        modifier = Modifier.animateItem(),
                        onClick = { editing = session },
                    )
                }
            }
        }
    }

    if (showTypePicker) {
        TypePickerDialog(
            onDismiss = { showTypePicker = false },
            onPick = { type ->
                showTypePicker = false
                onStart(type)
            },
        )
    }

    if (creating) {
        CardioEditDialog(
            initial = null,
            onDismiss = { creating = false },
            onSave = { type, startedAt, durationMs, distanceKm, note, settings ->
                vm.save(
                    type, startedAt, durationMs, distanceKm, note,
                    manual = true,
                    settings = settings,
                )
                creating = false
            },
            onDelete = null,
        )
    }

    CardioEditHost(
        session = editing,
        onDismiss = { editing = null },
        onUpdate = { vm.update(it) },
        onDelete = { vm.delete(it) },
    )
}

@Composable
private fun WeekCard(count: Int, durationMs: Long, distanceKm: Float) {
    // 三列必须同构：都是「数字 + 小字单位」+ 一行标签。
    // 之前时长那列直接塞「不到 1 分钟」，比另外两列高一截，
    // 三列基线全歪，看起来像没对齐的表格。
    val durationValue = (Math.ceil(durationMs / 60_000.0).toInt()).let { minutes ->
        when {
            minutes <= 0 -> "--" to ""
            minutes < 60 -> minutes.toString() to "分钟"
            minutes % 60 == 0 -> (minutes / 60).toString() to "小时"
            else -> String.format(java.util.Locale.US, "%.1f", minutes / 60f) to "小时"
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("本周有氧", style = MiuixTheme.textStyles.subtitle)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                MiniStat(
                    label = "次数",
                    value = if (count > 0) count.toString() else "--",
                    unit = if (count > 0) "次" else "",
                    modifier = Modifier.weight(1f),
                )
                MiniStat(
                    label = "时长",
                    value = durationValue.first,
                    unit = durationValue.second,
                    modifier = Modifier.weight(1f),
                )
                MiniStat(
                    label = "距离",
                    value = if (distanceKm > 0f) {
                        String.format(java.util.Locale.US, "%.1f", distanceKm)
                    } else {
                        "--"
                    },
                    unit = if (distanceKm > 0f) "km" else "",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MiuixTheme.textStyles.title4,
                maxLines = 1,
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 2.dp, start = 1.dp),
                    maxLines = 1,
                )
            }
        }
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
}

/**
 * 一条有氧记录。历史页和这里的列表共用同一张卡。
 *
 * [showDate] 在历史页的「某一天」列表里要关掉 —— 标题已经写了是哪天，
 * 每行再重复一遍只会挤掉距离。[horizontalPadding] 给搜索结果用：
 * 那里列表自己已经有 16dp 边距，卡片再带一层就双倍缩进了。
 */
@Composable
internal fun CardioRow(
    session: CardioSession,
    modifier: Modifier = Modifier,
    showDate: Boolean = true,
    horizontalPadding: Dp = 16.dp,
    onClick: () -> Unit,
) {
    val type = CardioType.from(session.type)
    // 右侧副行：跑步看配速、骑行看时速、器械没距离就空着
    val secondary = when (type.metric) {
        CardioMetric.PACE -> formatPace(session.durationMs, session.distanceKm)?.let { "$it/km" }
        CardioMetric.SPEED -> formatSpeed(session.durationMs, session.distanceKm)?.let { "$it km/h" }
        CardioMetric.NONE -> null
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 4.dp),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(cardInsetColor(0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = type.label.take(1),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(type.label, style = MiuixTheme.textStyles.main)
                    Text(
                        text = buildString {
                            if (showDate) {
                                append(formatCardioDay(session.startedAt))
                                append(" ")
                            }
                            append(formatCardioTime(session.startedAt))
                            if (session.distanceKm > 0f) {
                                append(" · ")
                                append(String.format(java.util.Locale.US, "%.2f", session.distanceKm))
                                append(" km")
                            }
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatClock(session.durationMs),
                        style = MiuixTheme.textStyles.main,
                        fontWeight = FontWeight.Medium,
                    )
                    if (secondary != null) {
                        Text(
                            text = secondary,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            val controlsText = CardioControls.summary(type, session.settings)
            if (controlsText.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = controlsText,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (session.note.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = session.note,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 开始前的类型选择 */
@Composable
private fun TypePickerDialog(
    onDismiss: () -> Unit,
    onPick: (CardioType) -> Unit,
) {
    AppDialog(
        title = "做什么有氧",
        summary = "选一个开始计时，距离可以结束后再填",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 每行三个：屏宽 411dp 时对话框内容宽约 275dp，三格刚好不挤
            CardioType.entries.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { type ->
                        TypeChip(
                            text = type.label,
                            selected = false,
                            modifier = Modifier.weight(1f),
                            onClick = { onPick(type) },
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun TypeChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MiuixTheme.colorScheme.primary else cardInsetColor()
            )
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            maxLines = 1,
            color = if (selected) {
                MiuixTheme.colorScheme.onPrimary
            } else {
                MiuixTheme.colorScheme.onSurface
            },
        )
    }
}

/**
 * 「点一条记录 → 改内容 → 确认删除」这一整套交互。
 *
 * 有氧页和历史页都要它：两处各写一份的话，哪天改了删除确认的文案或者
 * 「删完要不要关弹窗」的顺序，早晚会改漏一处。删除二次确认也收在这里，
 * 调用方只拿最终的 [onDelete]（已经点过确认了）。
 */
@Composable
internal fun CardioEditHost(
    session: CardioSession?,
    onDismiss: () -> Unit,
    onUpdate: (CardioSession) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<CardioSession?>(null) }

    session?.let { current ->
        CardioEditDialog(
            initial = current,
            onDismiss = onDismiss,
            onSave = { type, startedAt, durationMs, distanceKm, note, settings ->
                onUpdate(
                    current.copy(
                        type = type.key,
                        startedAt = startedAt,
                        durationMs = durationMs,
                        distanceKm = distanceKm,
                        note = note,
                        settings = settings,
                    )
                )
                onDismiss()
            },
            onDelete = {
                // 先关表单再弹确认：两层弹窗同时开着的时候，
                // 点「取消」会连表单一起吃掉
                onDismiss()
                pendingDelete = current
            },
        )
    }

    pendingDelete?.let { target ->
        AppDialog(
            title = "删除这条记录？",
            summary = "${CardioType.from(target.type).label} · ${formatClock(target.durationMs)}，" +
                "删掉之后就找不回来了。",
            onDismissRequest = { pendingDelete = null },
        ) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                GlassButton(onClick = { pendingDelete = null }) {
                    Text("取消", style = MiuixTheme.textStyles.button)
                }
                Spacer(Modifier.width(12.dp))
                GlassButton(onClick = {
                    onDelete(target.id)
                    pendingDelete = null
                }) {
                    Text("删除", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

/**
 * 新建 / 编辑同一套表单。
 *
 * [initial] 为空 = 补录（日期可选今天/昨天/前天）；不为空 = 编辑，
 * 保留原来的开始时刻，只改内容和时长 —— 改记录不该把「哪天做的」改掉。
 */
@Composable
internal fun CardioEditDialog(
    initial: CardioSession?,
    onDismiss: () -> Unit,
    onSave: (CardioType, Long, Long, Float, String, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val editing = initial != null
    var type by remember { mutableStateOf(CardioType.from(initial?.type)) }
    var minutes by remember { mutableStateOf(((initial?.durationMs ?: 0L) / 60_000).toInt()) }
    var seconds by remember {
        mutableStateOf((((initial?.durationMs ?: 0L) / 1000) % 60).toInt())
    }
    var distanceKm by remember { mutableStateOf(initial?.distanceKm ?: 0f) }
    var settings by remember { mutableStateOf(initial?.settings.orEmpty()) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var dayOffset by remember { mutableStateOf(0) }

    // 界面上的分钟/秒只能精确到秒。用户只是来改距离时，不能顺手把
    // 42.121 秒截成 42.000 —— 那等于悄悄改了数据，累计起来和手表对不上。
    val untouchedDuration = initial != null &&
        minutes == (initial.durationMs / 60_000).toInt() &&
        seconds == (((initial.durationMs / 1000) % 60).toInt())
    val durationMs = if (untouchedDuration) {
        initial.durationMs
    } else {
        (minutes * 60L + seconds) * 1000L
    }
    val canSave = durationMs > 0L

    AppDialog(
        title = if (editing) "修改记录" else "补录一条有氧",
        summary = if (editing) {
            "${formatCardioDay(initial.startedAt)} ${formatCardioTime(initial.startedAt)}"
        } else {
            null
        },
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 类型胶囊：两行，每行三个（数量不整除时补空位）
            CardioType.entries.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { item ->
                        TypeChip(
                            text = item.label,
                            selected = item == type,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                // 换了运动类型，原来的坡度/阻力就不适用了
                                type = item
                                settings = CardioControls.encode(CardioControls.defaults(item))
                            },
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "时长",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberStepper(
                    value = minutes.toFloat(),
                    onValueChange = { minutes = it.toInt().coerceIn(0, 999) },
                    step = 1f,
                    unit = " 分",
                    modifier = Modifier.weight(1f),
                )
                NumberStepper(
                    value = seconds.toFloat(),
                    onValueChange = { seconds = it.toInt().coerceIn(0, 59) },
                    step = 5f,
                    unit = " 秒",
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "距离（不想记就留 0）",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(6.dp))
            NumberStepper(
                value = distanceKm,
                onValueChange = {
                    // 保留一位小数：0.5 步进点久了会积出 3.0000002 这种值
                    distanceKm = (Math.round(it * 10f) / 10f).coerceIn(0f, 999f)
                },
                step = 0.5f,
                unit = " km",
            )

            // 器械参数：选中类型决定有哪几项（跑步机两项、大部分一项）
            if (type.controls.isNotEmpty()) {
                val values = CardioControls.decode(settings)
                    .ifEmpty { CardioControls.defaults(type) }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "器械参数",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                type.controls.forEach { control ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = control.label,
                            style = MiuixTheme.textStyles.paragraph,
                            modifier = Modifier.weight(1f),
                        )
                        NumberStepper(
                            value = values[control.key] ?: control.initial,
                            onValueChange = { v ->
                                val next = CardioControls.decode(settings)
                                    .ifEmpty { CardioControls.defaults(type) }
                                    .toMutableMap()
                                next[control.key] = v.coerceIn(control.min, control.max)
                                settings = CardioControls.encode(next)
                            },
                            step = control.step,
                            unit = control.unit,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            TextField(
                value = note,
                onValueChange = { if (it.length <= 60) note = it },
                label = "备注（可选）",
                singleLine = true,
            )

            if (!editing) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "算在哪天",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("今天" to 0, "昨天" to 1, "前天" to 2).forEach { (label, offset) ->
                        TypeChip(
                            text = label,
                            selected = dayOffset == offset,
                            modifier = Modifier.weight(1f),
                            onClick = { dayOffset = offset },
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            // 时长为 0 时按钮禁用并说明原因：以前只是 onClick 里静默 return，
            // 用户点「保存」一点反应都没有，看起来像按钮坏了
            if (!canSave) {
                Text(
                    text = "时长为 0，填上分钟或秒才能保存",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDelete != null) {
                    ActionText(
                        text = "删除",
                        onClick = onDelete,
                    )
                }
                Spacer(Modifier.weight(1f))
                GlassButton(onClick = onDismiss) {
                    Text("取消", style = MiuixTheme.textStyles.button)
                }
                Spacer(Modifier.width(12.dp))
                GlassButton(
                    enabled = canSave,
                    onClick = {
                        if (!canSave) return@GlassButton
                        val startedAt = initial?.startedAt ?: run {
                            // 用「现在 - 时长」当开始时刻，再按选的天数往前推：
                            // 直接减 86400000 在跨夏令时的地区会差一小时
                            Calendar.getInstance().apply {
                                timeInMillis = Clock.now() - durationMs
                                add(Calendar.DAY_OF_YEAR, -dayOffset)
                            }.timeInMillis
                        }
                        onSave(type, startedAt, durationMs, distanceKm, note, settings)
                    },
                ) {
                    Text("保存", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}
