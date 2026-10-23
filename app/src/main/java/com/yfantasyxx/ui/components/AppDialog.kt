package com.yfantasyxx.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 通用对话框：Compose 原生 Dialog + MIUIX 卡片外观。
 *
 * 不使用 MIUIX 的 OverlayDialog / WindowDialog，避免依赖 Scaffold 的 popup 宿主
 * 或平台窗口实现带来的兼容性问题，任何位置都可稳定显示。
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    summary: String? = null,
    /** 全屏宽度的对话框（适合列表选择类内容） */
    fullWidth: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = !fullWidth,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = if (fullWidth) {
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 48.dp)
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            },
            contentAlignment = Alignment.Center,
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    title?.let {
                        Text(text = it, style = MiuixTheme.textStyles.subtitle)
                        Spacer(Modifier.height(6.dp))
                    }
                    summary?.let {
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.paragraph,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    content()
                }
            }
        }
    }
}
