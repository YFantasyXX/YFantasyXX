package com.yfantasyxx.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode(val value: Int) {
    SYSTEM(0), LIGHT(1), DARK(2);

    companion object {
        fun from(v: Int) = entries.firstOrNull { it.value == v } ?: SYSTEM
    }
}

/**
 * 底栏样式。
 *
 * - [FLOATING]：HyperOS 风格的实心悬浮胶囊，选中项用主色块标记
 * - [LIQUID_GLASS]：仿 iOS 的液态玻璃，背景实时模糊 + 边缘高光 + 滑动玻璃药丸
 */
enum class BottomBarStyle(val value: String, val label: String) {
    FLOATING("floating", "悬浮胶囊"),
    LIQUID_GLASS("liquid", "液态玻璃");

    companion object {
        fun from(v: String?) = entries.firstOrNull { it.value == v } ?: LIQUID_GLASS
    }
}

/**
 * 休息结束的提示音。
 *
 * 健身房场景里手机经常是静音/震动，走通知音量基本听不见，
 * 所以默认用闹钟音量（系统闹钟流不受静音影响，只有勿扰能压住）。
 */
enum class AlertSound(val value: String, val label: String, val hint: String) {
    ALARM("alarm", "闹钟音量", "最响，手机静音也能听见"),
    NOTIFICATION("notification", "通知音量", "跟普通通知一样，安静环境够用"),
    SILENT("silent", "仅震动", "不发声，只震动 + 悬浮窗");

    companion object {
        fun from(v: String?) = entries.firstOrNull { it.value == v } ?: ALARM
    }
}

enum class WeightUnit(val label: String, val factor: Float) {
    KG("kg", 1f),
    LB("lb", 2.20462f);

    fun fromKg(kg: Float): Float = kg * factor
    fun toKg(value: Float): Float = value / factor

    companion object {
        fun from(s: String?) = entries.firstOrNull { it.label == s } ?: KG
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val restSeconds: Int = 90,
    val webdavUrl: String = "",
    val webdavUsername: String = "",
    val webdavPassword: String = "",
    val autoBackup: Boolean = false,
    val lastBackupTime: Long? = null,
    /** 底栏样式：默认仿 iOS 液态玻璃 */
    val bottomBarStyle: BottomBarStyle = BottomBarStyle.LIQUID_GLASS,
    /** 悬浮胶囊底栏的毛玻璃效果（液态玻璃样式下恒为开启） */
    val blurBottomBar: Boolean = false,
    /** 点击时的触觉反馈 */
    val tapHaptics: Boolean = true,
    /** 杠铃杆重量（kg），杠铃片计算器的基准 */
    val barWeightKg: Float = 20f,
    /** 手上有哪些片，逗号分隔（每侧每档的数量视为不限） */
    val plates: String = "25,20,15,10,5,2.5,1.25",
    /** 每周训练次数目标；0 表示不设目标 */
    val weeklyGoal: Int = 0,
    /** 休息期间在桌面/其他应用上方显示悬浮窗（需要悬浮窗权限） */
    val restOverlay: Boolean = true,
    /** 休息结束时的提示音来源 */
    val restAlertSound: AlertSound = AlertSound.ALARM,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val WEIGHT_UNIT = stringPreferencesKey("weight_unit")
        val REST_SECONDS = longPreferencesKey("rest_seconds")
        val WEBDAV_URL = stringPreferencesKey("webdav_url")
        val WEBDAV_USER = stringPreferencesKey("webdav_user")
        val WEBDAV_PASS_ENC = stringPreferencesKey("webdav_pass_enc")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        val BOTTOM_BAR_STYLE = stringPreferencesKey("bottom_bar_style")
        val BLUR_BOTTOM_BAR = booleanPreferencesKey("blur_bottom_bar")
        val TAP_HAPTICS = booleanPreferencesKey("tap_haptics")
        val LAST_BACKUP = longPreferencesKey("last_backup")
        val BAR_WEIGHT = floatPreferencesKey("bar_weight")
        val PLATES = stringPreferencesKey("plates")
        val WEEKLY_GOAL = longPreferencesKey("weekly_goal")
        val REST_OVERLAY = booleanPreferencesKey("rest_overlay")
        val REST_ALERT_SOUND = stringPreferencesKey("rest_alert_sound")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        // 配置文件损坏 / 磁盘满时 DataStore 会抛 IOException，
        // 不接住就会一路传到 stateIn 的协程 → 每次启动都崩，用户连设置页都进不去
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            AppSettings(
                themeMode = ThemeMode.from(prefs[Keys.THEME_MODE]?.toIntOrNull() ?: 0),
                weightUnit = WeightUnit.from(prefs[Keys.WEIGHT_UNIT]),
                restSeconds = (prefs[Keys.REST_SECONDS] ?: 90L).toInt(),
                webdavUrl = prefs[Keys.WEBDAV_URL] ?: "",
                webdavUsername = prefs[Keys.WEBDAV_USER] ?: "",
                webdavPassword = SecureStore.decrypt(prefs[Keys.WEBDAV_PASS_ENC]),
                autoBackup = prefs[Keys.AUTO_BACKUP] ?: false,
                lastBackupTime = prefs[Keys.LAST_BACKUP],
                bottomBarStyle = BottomBarStyle.from(prefs[Keys.BOTTOM_BAR_STYLE]),
                blurBottomBar = prefs[Keys.BLUR_BOTTOM_BAR] ?: false,
                tapHaptics = prefs[Keys.TAP_HAPTICS] ?: true,
                barWeightKg = prefs[Keys.BAR_WEIGHT] ?: 20f,
                plates = prefs[Keys.PLATES] ?: "25,20,15,10,5,2.5,1.25",
                weeklyGoal = (prefs[Keys.WEEKLY_GOAL] ?: 0L).toInt(),
                restOverlay = prefs[Keys.REST_OVERLAY] ?: true,
                restAlertSound = AlertSound.from(prefs[Keys.REST_ALERT_SOUND]),
            )
        }
        // 解密 WebDAV 密码要走 Keystore（跨进程），不能在主线程做
        .flowOn(Dispatchers.IO)

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.value.toString() }
    }

    suspend fun setWeightUnit(unit: WeightUnit) {
        context.dataStore.edit { it[Keys.WEIGHT_UNIT] = unit.label }
    }

    suspend fun setRestSeconds(seconds: Int) {
        context.dataStore.edit { it[Keys.REST_SECONDS] = seconds.toLong() }
    }

    suspend fun setWebdavConfig(url: String, username: String, password: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WEBDAV_URL] = url.trim()
            prefs[Keys.WEBDAV_USER] = username.trim()
            prefs[Keys.WEBDAV_PASS_ENC] = SecureStore.encrypt(password)
        }
    }

    suspend fun setAutoBackup(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_BACKUP] = enabled }
    }

    suspend fun setTapHaptics(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TAP_HAPTICS] = enabled }
    }

    suspend fun setBlurBottomBar(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BLUR_BOTTOM_BAR] = enabled }
    }

    suspend fun setBottomBarStyle(style: BottomBarStyle) {
        context.dataStore.edit { it[Keys.BOTTOM_BAR_STYLE] = style.value }
    }

    suspend fun setLastBackupTime(time: Long) {
        context.dataStore.edit { it[Keys.LAST_BACKUP] = time }
    }

    suspend fun setBarWeight(kg: Float) {
        context.dataStore.edit { it[Keys.BAR_WEIGHT] = kg }
    }

    suspend fun setPlates(csv: String) {
        context.dataStore.edit { it[Keys.PLATES] = csv }
    }

    suspend fun setWeeklyGoal(count: Int) {
        context.dataStore.edit { it[Keys.WEEKLY_GOAL] = count.toLong() }
    }

    suspend fun setRestOverlay(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REST_OVERLAY] = enabled }
    }

    suspend fun setRestAlertSound(sound: AlertSound) {
        context.dataStore.edit { it[Keys.REST_ALERT_SOUND] = sound.value }
    }
}
