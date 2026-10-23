package com.yfantasyxx.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 悬浮底栏的 tab 定义 */
data class FloatingTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
)

/**
 * 悬浮底栏：浮在内容之上、圆角胶囊 + 细描边 + 阴影。
 *
 * 背景使用半透明色（[glass] 开启时约 93% 不透明度），视觉上是磨砂玻璃质感。
 * 这里刻意没有用真实的背景模糊：GPU 每帧对整条底栏做高斯模糊有 1~3ms 开销，
 * 在高刷屏上会明显吃掉帧预算；半透明方案性能开销接近零。
 */
@Composable
fun FloatingBottomBar(
    tabs: List<FloatingTab>,
    selectedRoute: String?,
    glass: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    val surface = MiuixTheme.colorScheme.surface

    Surface(
        shape = shape,
        color = if (glass) surface.copy(alpha = 0.93f) else surface,
        shadowElevation = 12.dp,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 14.dp)
            .height(64.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .border(
                    width = 0.6.dp,
                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.18f),
                    shape = shape,
                )
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val selected = tab.route == selectedRoute
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .then(
                                if (selected) {
                                    Modifier.background(MiuixTheme.colorScheme.primaryContainer)
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { if (!selected) onSelect(tab.route) }
                            .padding(horizontal = if (selected) 14.dp else 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = tab.label,
                            modifier = Modifier.size(22.dp),
                            tint = if (selected) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.65f)
                            },
                        )
                        if (selected) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = tab.label,
                                fontSize = 12.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
