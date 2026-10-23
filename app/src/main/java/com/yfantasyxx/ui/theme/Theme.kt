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
