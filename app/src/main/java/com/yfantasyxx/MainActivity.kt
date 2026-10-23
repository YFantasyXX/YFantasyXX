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
