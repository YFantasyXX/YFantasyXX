package com.yfantasyxx.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfantasyxx.data.ExerciseHistoryEntry
import com.yfantasyxx.ui.components.ActionText
import com.yfantasyxx.ui.components.AppDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 训练中的「动作历史」：最近若干次这个动作练了什么。
 *
 * 目的很具体——加重量之前先看一眼上次怎么练的，不用退出去翻历史页。
 * 每行右边可以直接把这一天的重量次数套到本次还没完成的组上。
 */
@Composable
internal fun ExerciseHistoryDialog(
    state: HistoryState,
    unitLabel: String,
    onApply: (ExerciseHistoryEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    // 今天/昨天的判断在一帧内算一次就够，别每行都去格式化当前时间
    val today = remember { dayKey(System.currentTimeMillis()) }
    val yesterday = remember { dayKey(System.currentTimeMillis() - DAY_MILLIS) }

    AppDialog(
        title = "${state.exerciseName} · 历史",
        onDismissRequest = onDismiss,
        fullWidth = true,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "点「套用」把那一天的重量次数填进还没完成的组。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))

            when {
                state.loading -> Text(
                    text = "读取中…",
                    style = MiuixTheme.textStyles.paragraph,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                state.entries.isEmpty() -> Text(
                    text = "这个动作还没有练完的记录",
                    style = MiuixTheme.textStyles.paragraph,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(state.entries, key = { it.sessionId }) { entry ->
                        HistoryRow(
                            entry = entry,
                            label = when (dayKey(entry.startedAt)) {
                                today -> "今天 " + timeLabel(entry.startedAt)
                                yesterday -> "昨天 " + timeLabel(entry.startedAt)
                                else -> fullLabel(entry.startedAt)
                            },
                            unitLabel = unitLabel,
                            onApply = { onApply(entry) },
                        )
                        HorizontalDivider(
                            color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.5f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionText(text = "关闭", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: ExerciseHistoryEntry,
    label: String,
    unitLabel: String,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.main,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${fmtKg(entry.volumeKg)} $unitLabel 容量",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.sets.joinToString(" · ") { "${fmtKg(it.weightKg)}×${it.reps}" },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        ActionText(text = "套用", onClick = onApply)
    }
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

private fun dayKey(millis: Long): String =
    SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(millis))

private fun timeLabel(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(millis))

private fun fullLabel(millis: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(millis))

/** 重量显示：整数不带小数点 */
private fun fmtKg(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(Locale.CHINA, "%.1f", v)
