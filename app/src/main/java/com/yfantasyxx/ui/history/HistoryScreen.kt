package com.yfantasyxx.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.db.SessionSummary
import com.yfantasyxx.ui.LocalBottomBarSpace
import com.yfantasyxx.ui.components.EmptyState
import com.yfantasyxx.ui.components.formatVolume
import com.yfantasyxx.ui.rememberAppContainer
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

class HistoryViewModel(repo: WorkoutRepository) : ViewModel() {
    val sessions = repo.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<SessionSummary>())

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
    val sessions by vm.sessions.collectAsState()

    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    val sessionsByDate = remember(sessions) {
        sessions.groupBy {
            Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).toLocalDate()
        }
    }

    Scaffold(
        topBar = { SmallTopAppBar(title = "训练历史") },
    ) { padding ->
        if (sessions.isEmpty()) {
            EmptyState(text = "暂无训练记录")
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp + LocalBottomBarSpace.current),
            ) {
                MonthCalendar(
                    month = month,
                    sessionsByDate = sessionsByDate,
                    selectedDate = selectedDate,
                    onSelectDate = { selectedDate = it },
                    onMonthChange = { month = it },
                )

                Spacer(Modifier.height(16.dp))

                val daySessions = sessionsByDate[selectedDate].orEmpty()
                Text(
                    text = "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日 · " +
                        "${daySessions.size} 次训练",
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
                        SessionCard(session = session, onClick = { onOpenSession(session.id) })
                    }
                }
            }
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
) {
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
                        imageVector = Icons.Filled.KeyboardArrowLeft,
                        contentDescription = "上个月",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Text(
                    text = "${month.year}年${month.monthValue}月",
                    style = MiuixTheme.textStyles.subtitle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onMonthChange(month.plusMonths(1)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowRight,
                        contentDescription = "下个月",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
private fun SessionCard(session: SessionSummary, onClick: () -> Unit) {
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
                Text(
                    text = session.name,
                    style = MiuixTheme.textStyles.main,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${session.exerciseCount} 动作 · ${session.setCount} 组 · " +
                        "容量 ${formatVolume(session.totalVolume)} kg",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(session.startTime)),
                style = MiuixTheme.textStyles.paragraph,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
