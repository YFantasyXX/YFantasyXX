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
                                    text = "最大重量 ${pr.bestWeightKg.toInt()} kg",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(
                                    text = "${pr.bestWeightKg.toInt()} × ${pr.bestReps}",
                                    style = MiuixTheme.textStyles.subtitle,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "最大容量 ${formatVolume(pr.bestVolume)} kg",
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

private val categoryColors = listOf(
    androidx.compose.ui.graphics.Color(0xFF3482FF),
    androidx.compose.ui.graphics.Color(0xFF34C759),
    androidx.compose.ui.graphics.Color(0xFFFF9500),
    androidx.compose.ui.graphics.Color(0xAF52DE),
    androidx.compose.ui.graphics.Color(0xFFFF3B30),
    androidx.compose.ui.graphics.Color(0x00C7BE),
    androidx.compose.ui.graphics.Color(0x8E8E93),
)

private fun categoryColor(index: Int) = categoryColors[index % categoryColors.size]
