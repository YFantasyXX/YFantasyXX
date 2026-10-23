package com.yfantasyxx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.yfantasyxx.data.prefs.AppSettings
import com.yfantasyxx.data.prefs.ThemeMode
import com.yfantasyxx.ui.YFantasyXXApp
import com.yfantasyxx.ui.theme.YFantasyXXTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as YFantasyXXApplication
        setContent {
            val settings by app.container.settingsRepository.settings
                .collectAsState(initial = AppSettings())
            YFantasyXXTheme(themeMode = settings.themeMode) {
                YFantasyXXApp()
            }
        }
    }
}
