package com.yfantasyxx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
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
