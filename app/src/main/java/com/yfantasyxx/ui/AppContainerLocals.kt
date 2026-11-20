package com.yfantasyxx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.yfantasyxx.AppContainer
import com.yfantasyxx.YFantasyXXApplication

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}

@Composable
fun rememberAppContainer(): AppContainer {
    LocalContext.current.applicationContext as? YFantasyXXApplication ?: error("no application")
    return LocalAppContainer.current
}

/** 悬浮底栏占用的底部高度，列表据此留白，让内容可以从底栏下方穿过 */
val LocalBottomBarSpace = compositionLocalOf<Dp> { 0.dp }
