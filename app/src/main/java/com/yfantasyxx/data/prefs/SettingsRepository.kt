package com.yfantasyxx.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val value: Int) {
    SYSTEM(0), LIGHT(1), DARK(2);

    companion object {
        fun from(v: Int) = entries.firstOrNull { it.value == v } ?: SYSTEM
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
    /** 悬浮底栏毛玻璃效果（默认关闭，开启会增加 GPU 开销） */
    val blurBottomBar: Boolean = false,
    /** 后台休息提醒：开启后会设置系统精确闹钟，退出 App 也能准时提醒 */
    val backgroundRestAlarm: Boolean = true,
    /** 点击时的触觉反馈 */
    val tapHaptics: Boolean = true,
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
        val BLUR_BOTTOM_BAR = booleanPreferencesKey("blur_bottom_bar")
        val TAP_HAPTICS = booleanPreferencesKey("tap_haptics")
        val BACKGROUND_REST_ALARM = booleanPreferencesKey("background_rest_alarm")
        val LAST_BACKUP = longPreferencesKey("last_backup")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = ThemeMode.from(prefs[Keys.THEME_MODE]?.toIntOrNull() ?: 0),
            weightUnit = WeightUnit.from(prefs[Keys.WEIGHT_UNIT]),
            restSeconds = (prefs[Keys.REST_SECONDS] ?: 90L).toInt(),
            webdavUrl = prefs[Keys.WEBDAV_URL] ?: "",
            webdavUsername = prefs[Keys.WEBDAV_USER] ?: "",
            webdavPassword = SecureStore.decrypt(prefs[Keys.WEBDAV_PASS_ENC]),
            autoBackup = prefs[Keys.AUTO_BACKUP] ?: false,
            lastBackupTime = prefs[Keys.LAST_BACKUP],
            blurBottomBar = prefs[Keys.BLUR_BOTTOM_BAR] ?: false,
            backgroundRestAlarm = prefs[Keys.BACKGROUND_REST_ALARM] ?: true,
            tapHaptics = prefs[Keys.TAP_HAPTICS] ?: true,
        )
    }

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

    suspend fun setBackgroundRestAlarm(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BACKGROUND_REST_ALARM] = enabled }
    }

    suspend fun setTapHaptics(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TAP_HAPTICS] = enabled }
    }

    suspend fun setBlurBottomBar(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BLUR_BOTTOM_BAR] = enabled }
    }

    suspend fun setLastBackupTime(time: Long) {
        context.dataStore.edit { it[Keys.LAST_BACKUP] = time }
    }
}
