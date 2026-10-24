package com.yfantasyxx.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yfantasyxx.data.db.CategoryVolume
import com.yfantasyxx.ui.components.DistributionRow
import com.yfantasyxx.ui.components.EmptyState
import com.yfantasyxx.ui.components.SectionHeader
import com.yfantasyxx.ui.components.VolumeBarChart
import com.yfantasyxx.ui.components.WeightLineChart
import com.yfantasyxx.ui.components.formatVolume
import com.yfantasyxx.ui.rememberAppContainer
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable

@Composable
fun StatsScreen() {
    val container = rememberAppContainer()
    val vm: StatsViewModel = viewModel(factory = StatsViewModel.factory(container))
    val state by vm.uiState.collectAsState()

    Scaffold(
        topBar = { SmallTopAppBar(title = "训练统计") },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = 24.dp + com.yfantasyxx.ui.LocalBottomBarSpace.current
            ),
        ) {
            // 周训练量
            item {
                SectionHeader("近 8 周训练容量 (kg)")
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (state.weekVolumes.all { it.second == 0f }) {
                            EmptyState(text = "还没有足够的训练数据")
                        } else {
                            VolumeBarChart(data = state.weekVolumes)
                        }
                    }
                }
            }

            // 部位分布
            item {
                SectionHeader("近 30 天部位分布")
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (state.categoryVolumes.isEmpty()) {
                            EmptyState(text = "暂无数据")
                        } else {
                            val max = state.categoryVolumes.maxOf { it.volume }
                            state.categoryVolumes.forEachIndexed { index, cv ->
                                if (index > 0) Spacer(Modifier.height(2.dp))
                                DistributionRow(
                                    label = cv.category,
                                    valueText = "${formatVolume(cv.volume)} kg",
                                    ratio = if (max > 0) cv.volume / max else 0f,
                                    color = categoryColor(index),
                                )
                            }
                        }
                    }
                }
            }

            // 肌群热力图 + 疲劳度
            item {
                SectionHeader("近 7 天肌群负荷")
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (state.muscleLoads.isEmpty()) {
                            EmptyState(text = "暂无数据")
                        } else {
                            MuscleHeatmap(loads = state.muscleLoads)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "主肌群记 1 组、次要肌群记 0.5 组；" +
                                    "48 小时内练过的部位标为恢复中",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            // 体重趋势
            item {
                SectionHeader("体重趋势 (kg)")
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        WeightLineChart(data = state.weightTrend)
                    }
                }
            }

            // PR
            item { SectionHeader("个人纪录 PR") }
            if (state.personalRecords.isEmpty()) {
                item { EmptyState(text = "完成训练后会自动生成 PR") }
            } else {
                items(state.personalRecords.take(20)) { pr ->
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pr.exerciseName, style = MiuixTheme.textStyles.main)
                                Text(
                                    // 这组是真实发生过的，不是「最大重量」和「最大次数」拼出来的
                                    text = if (pr.bestWeightKg > 0f) {
                                        "最佳组 ${pr.bestWeightKg.toInt()} kg × ${pr.bestReps}"
                                    } else {
                                        "最佳组 ${pr.bestReps} 次（自重）"
                                    },
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(
                                    // 力量指标看估算 1RM：100 kg × 10 强于 110 kg × 3
                                    text = pr.estOneRm?.let { "e1RM ${it.toInt()} kg" }
                                        ?: "e1RM —",
                                    style = MiuixTheme.textStyles.subtitle,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "容量 ${formatVolume(pr.bestVolume)} kg",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 肌群热力图。
 *
 * 两列小格子，颜色深浅 = 近 7 天有效组数，副标题 = 恢复状态。
 * 不用真的画人体：肌群是一组离散的名字，用格子比拼一张图更好读，
 * 而且不用维护身体轮廓的坐标。两列而不是三列，是为了让「胸大肌」「三角肌前束」
 * 这类四五个字的名字不会被截断。
 */
@Composable
private fun MuscleHeatmap(loads: List<com.yfantasyxx.data.db.MuscleLoad>) {
    val maxSets = (loads.maxOfOrNull { it.sets } ?: 1f).coerceAtLeast(1f)
    val now = remember { System.currentTimeMillis() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        loads.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pair.forEach { load ->
                    MuscleCell(
                        load = load,
                        maxSets = maxSets,
                        now = now,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 单数个时补一个空位，避免最后一个格子被拉成整行宽
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MuscleCell(
    load: com.yfantasyxx.data.db.MuscleLoad,
    maxSets: Float,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val intensity = (load.sets / maxSets).coerceIn(0f, 1f)
    val base = MiuixTheme.colorScheme.surfaceContainer
    val accent = MiuixTheme.colorScheme.primary
    val background = androidx.compose.ui.graphics.lerp(base, accent, intensity * 0.7f)
    val onBackground = if (intensity > 0.55f) {
        MiuixTheme.colorScheme.onPrimary
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    val hours = if (load.lastTrainedAt > 0L) {
        (now - load.lastTrainedAt) / 3_600_000f
    } else {
        null
    }
    val status = when {
        hours == null -> "本周未练"
        hours < 24f -> "恢复中"
        hours < 48f -> "恢复期"
        else -> "可练"
    }
    Column(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = load.muscle,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = onBackground,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            text = "${load.sets.toInt()} 组 · $status",
            fontSize = 11.sp,
            color = onBackground.copy(alpha = 0.75f),
            maxLines = 1,
        )
    }
}

private val categoryColors = listOf(    androidx.compose.ui.graphics.Color(0xFF3482FF),
    androidx.compose.ui.graphics.Color(0xFF34C759),
    androidx.compose.ui.graphics.Color(0xFFFF9500),
    androidx.compose.ui.graphics.Color(0xAF52DE),
    androidx.compose.ui.graphics.Color(0xFFFF3B30),
    androidx.compose.ui.graphics.Color(0x00C7BE),
    androidx.compose.ui.graphics.Color(0x8E8E93),
)

private fun categoryColor(index: Int) = categoryColors[index % categoryColors.size]
