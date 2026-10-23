package com.yfantasyxx.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 页面分组标题 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        trailing?.invoke()
    }
}

/** 空状态 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.paragraph,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

/**
 * 数字步进器（重量/次数输入）
 */
@Composable
fun NumberStepper(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 2.5f,
    unit: String = "",
    allowNegative: Boolean = false,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(
            onClick = {
                val next = value - step
                onValueChange(if (next < 0f && !allowNegative) 0f else next)
            },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Remove,
                contentDescription = "减少",
                modifier = Modifier.size(16.dp),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = if (value == value.toInt().toFloat()) {
                value.toInt().toString() + unit
            } else {
                String.format("%.1f%s", value, unit)
            },
            style = MiuixTheme.textStyles.main,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.width(64.dp),
            fontSize = 15.sp,
        )
        IconButton(
            onClick = { onValueChange(value + step) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "增加",
                modifier = Modifier.size(16.dp),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 整数输入框（重量/次数/RPE）
 */
@Composable
fun NumberTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
) {
    TextField(
        value = value,
        onValueChange = { input ->
            // 仅允许数字和小数点
            if (input.all { it.isDigit() || it == '.' }) onValueChange(input)
        },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** 键值信息行 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.paragraph,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.paragraph,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** 统计小卡片 */
@Composable
fun StatCard(
    title: String,
    value: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.primary,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 格式化容量显示，如 12.5k */
fun formatVolume(kg: Float): String = when {
    kg >= 100000 -> String.format("%.1fw", kg / 10000)
    kg >= 10000 -> String.format("%.1fw", kg / 1000)
    kg >= 1000 -> String.format("%.1fk", kg / 1000)
    else -> kg.toInt().toString()
}

/**
 * 文字按钮（MIUIX 的 TextButton 只接受 text 参数，这里提供带点击态的通用版本）
 */
@Composable
fun ActionText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: androidx.compose.ui.graphics.Color = MiuixTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.button,
        color = if (enabled) color else MiuixTheme.colorScheme.disabledOnSurface,
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
