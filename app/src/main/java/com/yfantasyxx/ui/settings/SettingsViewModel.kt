package com.yfantasyxx.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.prefs.AppSettings
import com.yfantasyxx.data.prefs.BottomBarStyle
import com.yfantasyxx.data.prefs.SettingsRepository
import com.yfantasyxx.data.prefs.ThemeMode
import com.yfantasyxx.data.prefs.WeightUnit
import com.yfantasyxx.sync.BackupManager
import com.yfantasyxx.sync.WebDavClient
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class SettingsViewModel(
    private val container: AppContainer,
    private val settingsRepo: SettingsRepository,
    private val backupManager: BackupManager,
    private val repo: WorkoutRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val backupState = backupManager.state

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    }

    fun setWeightUnit(unit: WeightUnit) {
        viewModelScope.launch { settingsRepo.setWeightUnit(unit) }
    }

    fun setRestSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepo.setRestSeconds(seconds) }
    }

    fun setTapHaptics(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setTapHaptics(enabled) }
    }

    fun setBlurBottomBar(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setBlurBottomBar(enabled) }
    }

    fun setBottomBarStyle(style: BottomBarStyle) {
        viewModelScope.launch { settingsRepo.setBottomBarStyle(style) }
    }

    fun setAutoBackup(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoBackup(enabled) }
    }

    fun setWeeklyGoal(count: Int) {
        viewModelScope.launch { settingsRepo.setWeeklyGoal(count.coerceIn(0, 14)) }
    }

    fun setBarWeight(kg: Float) {
        viewModelScope.launch { settingsRepo.setBarWeight(kg.coerceIn(0f, 50f)) }
    }

    fun setPlates(csv: String) {
        viewModelScope.launch { settingsRepo.setPlates(csv) }
    }

    fun saveWebdav(url: String, user: String, pass: String) {
        viewModelScope.launch { settingsRepo.setWebdavConfig(url, user, pass) }
    }

    suspend fun buildCsv(): String = repo.buildCsvExport()

    private fun client(s: AppSettings): WebDavClient? {
        if (s.webdavUrl.isBlank() || s.webdavUsername.isBlank()) return null
        return WebDavClient(s.webdavUrl, s.webdavUsername, s.webdavPassword)
    }

    fun testWebdav(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val s = settings.value
            val c = client(s)
            if (c == null) {
                onResult(false, "请先填写服务器地址和用户名")
                return@launch
            }
            val result = c.testConnection()
            onResult(result.isSuccess, result.exceptionOrNull()?.message ?: "连接成功")
        }
    }

    fun backupNow() {
        viewModelScope.launch {
            val s = settings.value
            val c = client(s)
            if (c == null) {
                backupManager.setError("请先完成 WebDAV 配置")
                return@launch
            }
            val result = backupManager.backup(c)
            if (result.isSuccess) {
                settingsRepo.setLastBackupTime(System.currentTimeMillis())
                // 立刻刷新列表，刚传上去的那份要马上能看见
                backupManager.refreshRemoteBackups(c)
            }
        }
    }

    fun refreshBackups() {
        viewModelScope.launch {
            val c = client(settings.value) ?: return@launch
            backupManager.refreshRemoteBackups(c)
        }
    }

    fun restoreBackup(fileName: String, onRestored: () -> Unit = {}) {
        viewModelScope.launch {
            val c = client(settings.value) ?: return@launch
            backupManager.restore(c, fileName)
            // 恢复成功后重建数据库实例
            container.rebuildDatabase()
            onRestored()
            // 各 Repository 仍持有旧数据库实例，无法通过 Flow 刷新，
            // 直接重启进程让所有数据源重新加载（消息已提示"恢复成功"）
            delay(700)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    fun deleteBackup(fileName: String) {
        viewModelScope.launch {
            val c = client(settings.value) ?: return@launch
            backupManager.deleteRemote(c, fileName)
        }
    }

    fun clearMessage() = backupManager.clearMessage()

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                SettingsViewModel(
                    container,
                    container.settingsRepository,
                    container.backupManager,
                    container.workoutRepository,
                )
            }
        }
    }
}
