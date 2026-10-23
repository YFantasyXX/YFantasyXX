package com.yfantasyxx

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.yfantasyxx.data.prefs.AppSettings
import com.yfantasyxx.ui.YFantasyXXApp
import com.yfantasyxx.ui.theme.YFantasyXXTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 拒绝也不影响训练记录，只是看不到通知栏倒计时
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestHighestRefreshRate()
        requestNotificationPermissionIfNeeded()

        val app = application as YFantasyXXApplication
        setContent {
            val settings by app.container.settingsRepository.settings
                .collectAsState(initial = AppSettings())
            YFantasyXXTheme(themeMode = settings.themeMode) {
                YFantasyXXApp()
            }
        }
    }

    /**
     * 请求使用屏幕支持的最高刷新率。
     * 不请求的话系统通常只分配 60Hz / 120Hz，即使屏幕是 144Hz 或 185Hz。
     * 注意：厂商 ROM 可能仍把超高刷限制在游戏或白名单场景。
     */
    private fun requestHighestRefreshRate() {
        runCatching {
            val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return
            val modes = currentDisplay.supportedModes
            val maxMode = modes.maxByOrNull { it.refreshRate } ?: return
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = maxMode.modeId
                preferredRefreshRate = maxMode.refreshRate
            }
        }
    }

    /** 通知栏倒计时需要通知权限（Android 13+） */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
