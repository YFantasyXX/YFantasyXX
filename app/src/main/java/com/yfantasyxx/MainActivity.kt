package com.yfantasyxx

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yfantasyxx.data.prefs.AppSettings
import com.yfantasyxx.ui.YFantasyXXApp
import com.yfantasyxx.ui.theme.YFantasyXXTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 拒绝也不影响训练记录，只是看不到通知栏倒计时
        }

    /** 点击震动开关（由设置驱动，触摸回调里高频读取，用 @Volatile 缓存） */
    @Volatile
    private var tapHapticsEnabled = true

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestHighestRefreshRate()
        requestNotificationPermissionIfNeeded()

        val app = application as YFantasyXXApplication
        lifecycleScope.launch {
            app.container.settingsRepository.settings.collect {
                tapHapticsEnabled = it.tapHaptics
            }
        }

        setContent {
            val settings by app.container.settingsRepository.settings
                .collectAsState(initial = AppSettings())
            YFantasyXXTheme(themeMode = settings.themeMode) {
                YFantasyXXApp()
            }
        }
    }

    /**
     * 全局点击触觉反馈：在事件分发阶段识别「轻点」（位移小、时间短），
     * 这样按钮、卡片、列表项等所有可点区域都有震动，滑动/长按不触发。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (tapHapticsEnabled) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    downTime = SystemClock.uptimeMillis()
                }

                MotionEvent.ACTION_UP -> {
                    val moved = abs(ev.rawX - downX) > 24f || abs(ev.rawY - downY) > 24f
                    val elapsed = SystemClock.uptimeMillis() - downTime
                    if (!moved && elapsed < 400) {
                        runCatching {
                            window.decorView.performHapticFeedback(
                                HapticFeedbackConstants.VIRTUAL_KEY
                            )
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
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
