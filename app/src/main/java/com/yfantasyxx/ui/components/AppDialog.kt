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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yfantasyxx.ui.glass.DialogWindowGlass
import com.yfantasyxx.ui.glass.glassPanel
import com.yfantasyxx.ui.glass.isGlassDark
import com.yfantasyxx.ui.glass.material.GlassShape
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 通用对话框：Compose 原生 Dialog + OS4 玻璃面板。
 *
 * 不使用 MIUIX 的 OverlayDialog / WindowDialog，避免依赖 Scaffold 的 popup 宿主
 * 或平台窗口实现带来的兼容性问题，任何位置都可稳定显示。
 *
 * 面板不再是 MIUIX 的 `Card`（那是实心卡片色，深色下和背后的页面几乎同色，
 * 弹出来像是页面自己变了个色块）。现在走 [glassPanel]：窗口模糊 + 体色 +
 * 边缘光，背后的页面还能看见一层模糊的轮廓——这正是「浮起来」的来源。
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
        // 窗口级模糊，整个 Dialog 只要调一次
        DialogWindowGlass()

        val isDark = isGlassDark()

        // 出场动画。
        //
        // 之前这个弹窗是**没有动画**的：窗口一建好，整块面板就贴在屏幕上了。
        // 全应用三十多处确认框、输入框都走这里，所以它「硬出现」比任何一处转场
        // 都更显眼。现在和下拉菜单、选择面板共用同一套参数，起点取 0.94：
        // 对话框是模态的，缩太小会像从远处飞过来。
        val motion = rememberOverlayMotion(fromScale = 0.94f, toScale = 0.98f)
        LaunchedEffect(Unit) { motion.enter() }

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
            // MIUIX 的 Card 会顺带提供 LocalContentColor；换成自绘玻璃面板之后
            // 这层没了，而 Compose 原生 Dialog 的默认 content color 是**黑色**——
            // 暗色主题下所有没写 color 的 Text 会整条隐形。这里补回去。
            CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onSurface) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 缩放原点在面板中心：对话框没有「触发点」，从中间长出来最自然
                        .overlayTransform(motion)
                        // 模态弹窗用最厚的一档：背后通常是正在跑的训练/计时页面，
                        // 秒表大字和主色按钮一旦透上来，面板里的文字就没法读了
                        .glassPanel(
                            shape = GlassShape(28.dp),
                            isDark = isDark,
                            bodyAlpha = 0.94f,
                        )
                        .padding(20.dp),
                ) {
                    title?.let {
                        // 显式给颜色：Compose 原生 Dialog 的默认 content color 是黑色，
                        // 暗色主题下会变成「黑字落在深色面板上」，标题整条看不见
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.subtitle,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
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
