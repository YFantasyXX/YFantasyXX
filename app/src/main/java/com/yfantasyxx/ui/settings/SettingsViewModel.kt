package com.yfantasyxx.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.prefs.AlertSound
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

    fun setRestOverlay(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setRestOverlay(enabled) }
    }

    fun setRestAlertSound(sound: AlertSound) {
        viewModelScope.launch { settingsRepo.setRestAlertSound(sound) }
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

    /** 用输入框里的当前值构造客户端（避免刚改完配置、DataStore 还没写完时用旧配置） */
    private fun clientFor(url: String, user: String, pass: String): WebDavClient? {
        if (url.isBlank() || user.isBlank()) return null
        return WebDavClient(url.trim(), user.trim(), pass)
    }

    /**
     * 测试连接。
     *
     * 只做 PROPFIND 只能证明「连得上」，证明不了「写得进去」——
     * 而备份失败往往就死在写权限/配额上。所以这里连完再试写一次。
     */
    fun testWebdav(url: String, user: String, pass: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val c = clientFor(url, user, pass)
            if (c == null) {
                onResult(false, "请先填写服务器地址和用户名")
                return@launch
            }
            val conn = c.testConnection()
            if (conn.isFailure) {
                onResult(false, conn.exceptionOrNull()?.message ?: "连接失败")
                return@launch
            }
            c.writeTest().fold(
                onSuccess = { onResult(true, "连接正常，可以正常读写") },
                onFailure = { onResult(false, "能连上，但写不进去：${it.message ?: "未知错误"}") },
            )
        }
    }

    /** 输入框有内容就按输入框来，否则用已保存的配置 */
    /**
     * 输入框优先，缺失的字段才回退到已保存配置。
     *
     * 以前是「url 或 user 有一个为空就整体回退」：用户把用户名清空、
     * 只改了地址时，备份会静默发到**旧服务器**，还以为传到了新地址。
     * 另外地址换了就绝不沿用旧密码——那是把凭据发给另一台机器。
     */
    private fun resolveClient(url: String, user: String, pass: String): WebDavClient? {
        val saved = settings.value
        val inputUrl = url.trim()
        val sameServer = inputUrl.isBlank() || inputUrl == saved.webdavUrl.trim()
        val finalUrl = inputUrl.ifBlank { saved.webdavUrl.trim() }
        val finalUser = user.trim().ifBlank {
            if (sameServer) saved.webdavUsername.trim() else ""
        }
        val finalPass = pass.ifEmpty { if (sameServer) saved.webdavPassword else "" }
        if (finalUrl.isBlank() || finalUser.isBlank()) return null
        return WebDavClient(finalUrl, finalUser, finalPass)
    }

    fun backupNow(url: String = "", user: String = "", pass: String = "") {
        viewModelScope.launch {
            val c = resolveClient(url, user, pass)
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

    fun refreshBackups(url: String = "", user: String = "", pass: String = "") {
        viewModelScope.launch {
            val c = resolveClient(url, user, pass) ?: return@launch
            backupManager.refreshRemoteBackups(c)
        }
    }

    fun restoreBackup(
        fileName: String,
        url: String = "",
        user: String = "",
        pass: String = "",
        onRestored: () -> Unit = {},
    ) {
        viewModelScope.launch {
            val c = resolveClient(url, user, pass) ?: return@launch
            val result = backupManager.restore(c, fileName)
            // 失败（版本不符 / 空库 / 下载失败）时绝不能重建库再杀进程，
            // 否则用户只看到弹窗被关掉，错误信息一个字都看不到
            if (result.isFailure) return@launch
            // 恢复成功后重建数据库实例。
            // WorkoutRepository 现在按 provider 取库，重建之后所有 DAO 都指向新文件；
            // 这一步失败也不能崩，下面照样重启进程
            runCatching { container.rebuildDatabase() }
            onRestored()
            // 各界面已有的 Flow 仍挂在旧实例上，没法热切换，
            // 留一点时间让提示显示出来，然后直接重启进程
            delay(700)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    fun deleteBackup(
        fileName: String,
        url: String = "",
        user: String = "",
        pass: String = "",
    ) {
        viewModelScope.launch {
            val c = resolveClient(url, user, pass) ?: return@launch
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
