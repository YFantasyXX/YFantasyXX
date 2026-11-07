package com.yfantasyxx.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.yfantasyxx.data.prefs.ThemeMode
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 训练主题色（HyperOS 蓝） */
val BrandBlue = Color(0xFF3482FF)

/** 语义色：涨/涨红跌绿是股市约定，这里用健身语义色 */
val SuccessGreen = Color(0xFF34C759)
val WarningOrange = Color(0xFFFF9500)

/**
 * 正向变化（涨）用的红。
 *
 * 中文语境里「涨」是红的，所以 e1RM 提升显示红色、下降才显示绿色。
 * 数值和 [SuccessGreen] 是一对，别在页面里再各写一份字面量——
 * 同一个红曾经在三个文件里各写一遍，改一处另外两处就跟不上了。
 */
val RiseRed = Color(0xFFFF3B30)

/**
 * 琥珀金：热身标记与 PR（个人纪录）标记共用。
 *
 * 和 [WarningOrange] 不是一回事：橙色是「需要注意」，这个金是「值得高兴」。
 * 数值恰好不同（FF9F0A vs FF9500），肉眼几乎分不出，所以必须靠命名区分。
 */
val AccentAmber = Color(0xFFFF9F0A)

val LocalSuccessGreen = staticCompositionLocalOf { SuccessGreen }

@Composable
fun YFantasyXXTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) darkColorScheme() else lightColorScheme()

    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalSuccessGreen provides SuccessGreen) {
        MiuixTheme(colors = colors, content = content)
    }
}
