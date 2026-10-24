package com.yfantasyxx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 杠铃片计算结果。
 *
 * [perSide] 是**每边**要挂的片，降序排列。
 * [actualKg] 是实际能凑出来的总重量，和目标的差由 [shortKg] 表示（负数表示凑不够）。
 */
data class PlateSolution(
    val perSide: List<Float>,
    val actualKg: Float,
    val shortKg: Float,
)

/**
 * 杠铃片反推。
 *
 * 贪心从大到小填：对标准配片（25/20/15/10/5/2.5/1.25，每档都是 1.25 的整数倍）
 * 贪心是最优解，不需要背包 DP。用户的配片若不是 1.25 的倍数，
 * 结果可能不是最优，但至少是「能凑出来的最接近值」。
 */
object PlateMath {

    /** 解析「25,20,15」这类配置，去重降序 */
    fun parsePlates(csv: String): List<Float> = csv.split(',', '，', ' ')
        .mapNotNull { it.trim().toFloatOrNull() }
        .filter { it > 0f }
        .distinct()
        .sortedDescending()

    fun solve(targetKg: Float, barKg: Float, plates: List<Float>): PlateSolution {
        val perSideTarget = (targetKg - barKg) / 2f
        if (perSideTarget <= 0.01f) {
            return PlateSolution(emptyList(), barKg, targetKg - barKg)
        }
        val picked = mutableListOf<Float>()
        var rest = perSideTarget
        plates.sortedDescending().forEach { plate ->
            // 用 while 是允许同一档挂多片（现实里你有多对 25）
            while (rest + 1e-4f >= plate) {
                picked += plate
                rest -= plate
            }
        }
        val actual = barKg + picked.sum() * 2f
        return PlateSolution(
            perSide = picked,
            actualKg = actual,
            shortKg = targetKg - actual,
        )
    }

    /** 「25×2 + 10×1」这种汇总，展示比一串数字清楚 */
    fun summarize(plates: List<Float>): String = plates.groupingBy { it }.eachCount()
        .entries
        .sortedByDescending { it.key }
        .joinToString(" + ") { (plate, count) ->
            if (count > 1) "${trim(plate)}×$count" else trim(plate)
        }

    fun trim(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.2f", v)
            .trimEnd('0').trimEnd('.')
}

/**
 * 杠铃片计算器。
 *
 * 放在动作菜单里而不是全局入口：这样初始重量就是当前动作的重量，
 * 站在杠铃前不用再输一遍数字。
 */
@Composable
fun PlateCalculatorDialog(
    initialWeightKg: Float,
    barWeightKg: Float,
    platesCsv: String,
    onDismiss: () -> Unit,
    onSaveSettings: (Float, String) -> Unit,
) {
    var weight by remember { mutableStateOf(if (initialWeightKg > 0f) initialWeightKg else 60f) }
    var bar by remember { mutableStateOf(barWeightKg) }
    var platesText by remember { mutableStateOf(platesCsv) }
    var editingSettings by remember { mutableStateOf(false) }

    val plates = remember(platesText) { PlateMath.parsePlates(platesText) }
    val solution = remember(weight, bar, plates) { PlateMath.solve(weight, bar, plates) }

    AppDialog(title = "杠铃片计算器", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            NumberStepper(
                value = weight,
                onValueChange = { weight = it },
                step = 2.5f,
                unit = " kg",
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "杆重 ${PlateMath.trim(bar)} kg · 每边需 ${PlateMath.trim((weight - bar) / 2f)} kg",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))

            if (solution.perSide.isEmpty()) {
                Text(
                    text = if (weight <= bar) "比空杆还轻，不用挂片" else "配片凑不出来",
                    style = MiuixTheme.textStyles.paragraph,
                    color = MiuixTheme.colorScheme.error,
                )
            } else {
                Text("每边挂", style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    solution.perSide.groupingBy { it }.eachCount()
                        .entries.sortedByDescending { it.key }
                        .forEach { (plate, count) ->
                            PlateChip(plate = PlateMath.trim(plate), count = count)
                        }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (kotlin.math.abs(solution.shortKg) < 0.01f) {
                        "实际 ${PlateMath.trim(solution.actualKg)} kg，正好"
                    } else {
                        "实际 ${PlateMath.trim(solution.actualKg)} kg，" +
                            "差 ${PlateMath.trim(solution.shortKg)} kg"
                    },
                    style = MiuixTheme.textStyles.paragraph,
                    color = MiuixTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(14.dp))
            ActionText(
                text = if (editingSettings) "收起设置" else "杆重与配片设置",
                onClick = { editingSettings = !editingSettings },
            )
            if (editingSettings) {
                Spacer(Modifier.height(6.dp))
                Text("杠铃杆重量", style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                NumberStepper(
                    value = bar,
                    onValueChange = { bar = it },
                    step = 2.5f,
                    unit = " kg",
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = platesText,
                    onValueChange = { platesText = it },
                    label = "每侧可用的片（逗号分隔）",
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                ActionText(text = "保存设置", onClick = {
                    onSaveSettings(bar, platesText)
                    editingSettings = false
                })
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(text = "关闭", onClick = onDismiss)
            }
        }
    }
}

/** 一片（或一档）杠铃片的小方块 */
@Composable
private fun PlateChip(plate: String, count: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.primary)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = plate,
            fontSize = 14.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onPrimary,
        )
        if (count > 1) {
            Spacer(Modifier.width(2.dp))
            Text(
                text = "×$count",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onPrimary,
            )
        }
    }
}
