package com.yfantasyxx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 周训练量柱状图
 */
@Composable
fun VolumeBarChart(
    data: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    barColor: Color = MiuixTheme.colorScheme.primary,
    labelColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    gridColor: Color = MiuixTheme.colorScheme.dividerLine,
) {
    val maxVolume = (data.maxOfOrNull { it.second } ?: 0f).coerceAtLeast(1f)
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            val chartWidth = size.width
            val chartHeight = size.height - 20.dp.toPx()
            val barCount = data.size.coerceAtLeast(1)
            val gap = 8.dp.toPx()
            val barWidth = ((chartWidth - gap * (barCount - 1)) / barCount).coerceAtLeast(2f)

            // 底部基线
            drawLine(
                color = gridColor,
                start = Offset(0f, chartHeight),
                end = Offset(chartWidth, chartHeight),
                strokeWidth = 1.dp.toPx()
            )

            data.forEachIndexed { index, (_, volume) ->
                val ratio = (volume / maxVolume).coerceIn(0f, 1f)
                val barHeight = (chartHeight * ratio).coerceAtLeast(2.dp.toPx())
                val x = index * (barWidth + gap)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, chartHeight - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            data.forEach { (label, _) ->
                Text(
                    text = label,
                    fontSize = 10.sp,
                    color = labelColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 体重折线图
 */
@Composable
fun WeightLineChart(
    data: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    lineColor: Color = MiuixTheme.colorScheme.primary,
    labelColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    gridColor: Color = MiuixTheme.colorScheme.dividerLine,
) {
    if (data.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
            Text("暂无数据", color = labelColor, fontSize = 13.sp)
        }
        return
    }
    val minValue = data.minOf { it.second }
    val maxValue = data.maxOf { it.second }
    val range = (maxValue - minValue).coerceAtLeast(0.5f)
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            val chartWidth = size.width
            val chartHeight = size.height - 16.dp.toPx()
            val stepX = if (data.size > 1) chartWidth / (data.size - 1) else 0f

            val points = data.mapIndexed { index, (_, value) ->
                val ratio = (value - minValue) / range
                Offset(index * stepX, chartHeight - chartHeight * ratio)
            }

            // 网格线（3 条）
            listOf(0f, 0.5f, 1f).forEach { r ->
                drawLine(
                    color = gridColor,
                    start = Offset(0f, chartHeight * r),
                    end = Offset(chartWidth, chartHeight * r),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // 折线
            val path = Path().apply {
                points.forEachIndexed { index, p ->
                    if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
            }
            drawPath(path, color = lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))

            // 数据点
            points.forEach { p ->
                drawCircle(color = lineColor, radius = 3.dp.toPx(), center = p)
                drawCircle(color = Color.White, radius = 1.2.dp.toPx(), center = p)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = data.first().first,
                fontSize = 10.sp,
                color = labelColor,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = data.last().first,
                fontSize = 10.sp,
                color = labelColor,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 横向占比条（部位分布）
 */
@Composable
fun DistributionRow(
    label: String,
    valueText: String,
    ratio: Float,
    color: Color = MiuixTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = valueText,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio.coerceIn(0.02f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}
