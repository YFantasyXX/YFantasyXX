package com.yfantasyxx.ui.history

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.db.SessionSummary
import com.yfantasyxx.ui.LocalBottomBarSpace
import com.yfantasyxx.ui.components.pressScale
import com.yfantasyxx.ui.components.EmptyState
import com.yfantasyxx.ui.components.StatCard
import com.yfantasyxx.ui.components.formatSessionDuration
import com.yfantasyxx.ui.components.formatVolume
import com.yfantasyxx.ui.rememberAppContainer
import com.yfantasyxx.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date
import java.util.Locale

class HistoryViewModel(private val repo: WorkoutRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * 搜索：同时匹配训练名、备注和动作名。
     *
     * 动作名要查库（摘要在 SQL 里没有动作列），所以这里是 suspend 的 map，
     * 只在关键词变化时跑一次 —— 全部历史只有 200 条上下，内存过滤足够快。
     * 备注也一起搜：很多人把「今天状态差」「试试宽握」写在备注里，
     * 想找回那次训练时只会记得那句话。
     */
    val sessions: StateFlow<List<SessionSummary>> =
        combine(repo.observeHistory(), _query) { list, keyword -> list to keyword }
            .map { (list, keyword) ->
                if (keyword.isBlank()) {
                    list
                } else {
                    val byExercise = repo.sessionIdsMatchingExercise(keyword)
                    list.filter {
                        it.id in byExercise ||
                            it.name.contains(keyword, true) ||
                            it.note.contains(keyword, true)
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<SessionSummary>())

    fun setQuery(value: String) {
        _query.value = value
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HistoryViewModel(container.workoutRepository) }
        }
    }
}

@Composable
fun HistoryScreen(onOpenSession: (Long) -> Unit) {
    val container = rememberAppContainer()
    val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container))
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()

    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    val sessionsByDate = remember(sessions) {
        sessions.groupBy {
            Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).toLocalDate()
        }
    }

    // 月份汇总跟着「当前翻到哪个月」：翻到 9 月就只看 9 月的，
    // 不是把全部历史加在一起（那样这个数字永远不会变，翻月也就没意义）
    val monthSessions = remember(sessions, month) {
        sessions.filter {
            YearMonth.from(
                Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).toLocalDate()
            ) == month
        }
    }

    Scaffold(
        topBar = { SmallTopAppBar(title = "训练历史") },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // 搜索框固定在顶部：翻到哪个月都能直接搜
            top.yukonga.miuix.kmp.basic.TextField(
                value = query,
                onValueChange = vm::setQuery,
                label = "搜索训练名称、备注或动作",
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (query.isNotBlank()) {
                // 搜索结果不受月份限制，是「全历史」的：训练三年下来几百条，
                // 用 Column + verticalScroll + forEach 会把每一张卡一次性全部组合出来，
                // 首页那一帧要排几百个节点。换成 LazyColumn，只组合屏幕内的那几张。
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 16.dp + LocalBottomBarSpace.current,
                    ),
                ) {
                    item {
                        val searchDuration = sessions.sumOf { it.durationMs }
                        Text(
                            text = buildString {
                                append("找到 ${sessions.size} 次训练")
                                if (searchDuration > 0L) {
                                    append(" · 共 ")
                                    append(formatSessionDuration(searchDuration))
                                }
                            },
                            style = MiuixTheme.textStyles.subtitle,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (sessions.isEmpty()) {
                        item {
                            Text(
                                text = "换个关键词试试，可以搜动作名",
                                style = MiuixTheme.textStyles.paragraph,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    } else {
                        items(sessions, key = { it.id }) { session ->
                            SessionCard(
                                session = session,
                                showDate = true,
                                // 搜索时边打字边过滤：留下来的卡片滑到新位置，
                                // 被筛掉的淡出，而不是整列「啪」地重排
                                modifier = Modifier.animateItem(),
                                onClick = { onOpenSession(session.id) },
                            )
                        }
                    }
                }
            } else if (sessions.isEmpty()) {
                EmptyState(text = "暂无训练记录")
            } else {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp + LocalBottomBarSpace.current),
                ) {
                    MonthCalendar(
                        month = month,
                        sessionsByDate = sessionsByDate,
                        selectedDate = selectedDate,
                        onSelectDate = { selectedDate = it },
                        onMonthChange = { next ->
                            month = next
                            // 选中日跟着落到目标月，否则日历翻到 11 月、
                            // 下面还在显示「10月30日」的记录
                            selectedDate = when {
                                YearMonth.from(selectedDate) == next -> selectedDate
                                next == YearMonth.now() -> LocalDate.now()
                                else -> next.atDay(1)
                            }
                        },
                        onToday = {
                            month = YearMonth.now()
                            selectedDate = LocalDate.now()
                        },
                    )

                    Spacer(Modifier.height(12.dp))

                    // 月度汇总：以前翻日历只能看「哪天有训练」，一共练了多少
                    // 得自己数。三个数都是当前查看月份整月的，切换月份跟着变。
                    MonthSummary(
                        count = monthSessions.size,
                        durationMs = monthSessions.sumOf { it.durationMs },
                        volumeKg = monthSessions.sumOf { it.totalVolume.toDouble() }.toFloat(),
                    )

                    Spacer(Modifier.height(16.dp))

                    // 换一天（或翻月）时，下面这块是整个换掉的。直接替换会「啪」
                    // 地同时换掉标题和整列卡片，眼睛得重新找位置；这里让新内容
                    // 淡入、从下方推上来自身高度的 1/24，旧内容 150ms 快速淡出，
                    // 明确表示「这块被换了」，而不是让人以为页面整体在跳。
                    AnimatedContent(
                        targetState = selectedDate,
                        transitionSpec = {
                            (
                                fadeIn(Motion.standard(Motion.DURATION_STANDARD)) +
                                    slideInVertically(
                                        animationSpec = Motion.decelerate(Motion.DURATION_STANDARD),
                                    ) { it / 24 }
                                ) togetherWith fadeOut(Motion.accelerate(Motion.DURATION_FAST))
                        },
                        label = "dayDetail",
                    ) { date ->
                        val daySessions = sessionsByDate[date].orEmpty()
                        val dayDuration = daySessions.sumOf { it.durationMs }
                        Column {
                            Text(
                                text = buildString {
                                    append("${date.monthValue}月${date.dayOfMonth}日 · ")
                                    append("${daySessions.size} 次训练")
                                    if (dayDuration > 0L) {
                                        append(" · ")
                                        append(formatSessionDuration(dayDuration))
                                    }
                                },
                                style = MiuixTheme.textStyles.subtitle,
                            )
                            Spacer(Modifier.height(8.dp))

                            if (daySessions.isEmpty()) {
                                Text(
                                    text = "这天没有训练记录",
                                    style = MiuixTheme.textStyles.paragraph,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            } else {
                                daySessions.forEach { session ->
                                    SessionCard(
                                        session = session,
                                        onClick = { onOpenSession(session.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 月度汇总：次数 / 总时长 / 总容量。
 *
 * 时长超过一小时按小时给（4.5 小时），不到一小时按分钟给（45 分钟）——
 * 三种量级放在同一行里，固定单位会有一个数字小得看不出差别。
 */
@Composable
private fun MonthSummary(count: Int, durationMs: Long, volumeKg: Float) {
    val hours = durationMs / 3_600_000.0
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCard(
                title = "本月训练",
                value = count.toString(),
                subtitle = "次",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                title = "总时长",
                value = if (hours >= 1.0) {
                    String.format(Locale.CHINA, "%.1f", hours)
                } else {
                    (durationMs / 60_000L).toString()
                },
                subtitle = if (hours >= 1.0) "小时" else "分钟",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                title = "总容量",
                value = formatVolume(volumeKg),
                subtitle = "kg",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 月历：有训练的日子高亮，点击查看当天记录 */
@Composable
private fun MonthCalendar(
    month: YearMonth,
    sessionsByDate: Map<LocalDate, List<SessionSummary>>,
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    onToday: () -> Unit,
) {
    // 翻到几个月前想回来，一个 ‹ › 只能退一个月，往回退十几次实在难受，
    // 所以标题旁给一条「回到今天」的直达路径；已在今天时它不出现，不占位置
    val thisMonth = YearMonth.now()
    val today = LocalDate.now()
    val showToday = month != thisMonth || selectedDate != today
    // 未来月份必然是空的，能翻过去只会多一次回头，所以到本月为止
    val canGoNext = month < thisMonth

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onMonthChange(month.minusMonths(1)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上个月",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${month.year}年${month.monthValue}月",
                        style = MiuixTheme.textStyles.subtitle,
                        textAlign = TextAlign.Center,
                    )
                    if (showToday) {
                        Spacer(Modifier.width(8.dp))
                        TodayChip(onClick = onToday)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(enabled = canGoNext) {
                            onMonthChange(month.plusMonths(1))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下个月",
                        tint = if (canGoNext) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        } else {
                            MiuixTheme.colorScheme.disabledOnSurface
                        },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            val firstDay = month.atDay(1)
            val offset = firstDay.dayOfWeek.value - 1
            val daysInMonth = month.lengthOfMonth()
            val rows = ((offset + daysInMonth) + 6) / 7

            repeat(rows) { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    repeat(7) { col ->
                        val cellIndex = row * 7 + col
                        val dayNumber = cellIndex - offset + 1
                        Box(modifier = Modifier.weight(1f)) {
                            if (dayNumber in 1..daysInMonth) {
                                val date = month.atDay(dayNumber)
                                DayCell(
                                    date = date,
                                    hasSession = sessionsByDate[date]?.isNotEmpty() == true,
                                    selected = date == selectedDate,
                                    onClick = { onSelectDate(date) },
                                )
                            } else {
                                Spacer(Modifier.aspectRatio(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 日历标题旁的「回到今天」小胶囊。
 *
 * 只做一件事：把查看位置拉回今天。翻到三个月前再想回来，靠 ‹ › 要点三次以上，
 * 这个按钮一次到位；已经在今天时它不出现，不会让人以为是个常驻入口。
 */
@Composable
private fun TodayChip(onClick: () -> Unit) {
    Text(
        text = "回到今天",
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.primary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = com.yfantasyxx.ui.theme.Radius.Pill))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun DayCell(
    date: LocalDate,
    hasSession: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val textColor = when {
        selected -> MiuixTheme.colorScheme.onPrimary
        hasSession -> MiuixTheme.colorScheme.onSurface
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Box(
        modifier = Modifier
            .padding(2.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .pressScale(pressedScale = 0.85f)
            .clip(CircleShape)
            .background(
                if (selected) MiuixTheme.colorScheme.primary else Color.Transparent
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${date.dayOfMonth}",
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
        )
        // 有训练的日子：日期下方一个小圆点
        if (hasSession) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MiuixTheme.colorScheme.onPrimary
                        } else {
                            MiuixTheme.colorScheme.primary
                        }
                    ),
            )
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionSummary,
    onClick: () -> Unit,
    showDate: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            // 按下时轻微缩小：卡片有圆角和阴影，水波纹画在上面像贴纸，
            // 尺寸变化才有「按下去」的实体感
            .pressScale()
            .padding(vertical = 4.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.name,
                        style = MiuixTheme.textStyles.main,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // fill = false：名字短的时候标记紧跟名字，长的时候
                        // 由名字让位截断，标记不会被挤出屏幕
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (session.prCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        PrBadge(session.prCount)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${session.exerciseCount} 动作 · ${session.setCount} 组 · " +
                        "容量 ${formatVolume(session.totalVolume)} kg",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (session.note.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = session.note,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = SimpleDateFormat(
                        if (showDate) "MM-dd HH:mm" else "HH:mm",
                        Locale.CHINA,
                    ).format(Date(session.startTime)),
                    style = MiuixTheme.textStyles.paragraph,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(2.dp))
                // 时长放在时间下面：以前这一栏只有一个开始时刻，
                // 想知道练了多久得进去看
                Text(
                    text = formatSessionDuration(session.durationMs),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 这次训练出了 PR 的小标记 */
@Composable
private fun PrBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = if (count > 1) "PR ×$count" else "PR",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}
