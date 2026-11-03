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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
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
    /** 休息期间在桌面/其他应用上方显示悬浮窗（需要悬浮窗权限） */
    val restOverlay: Boolean = true,
    /**
     * 悬浮窗常驻。
     *
     * 关着的时候（默认）：只在 App 不在前台时显示气泡，休息结束的卡片 12 秒后自动消失。
     * 开着的时候：App 在前台也显示气泡；休息结束的卡片一直挂在屏幕上，直到手动关闭。
     */
    val restOverlayPersistent: Boolean = false,
    /** 休息结束时的提示音来源 */
    val restAlertSound: AlertSound = AlertSound.ALARM,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(
    private val context: Context,
    /**
     * 跑共享流的作用域，必须是应用级的。
     *
     * 用 viewModelScope 的话，页面一销毁整条流就停，下一个页面进来又要
     * 重新读 DataStore + 解密一遍，等于没做成共享。
     */
    scope: CoroutineScope,
) {

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
        val REST_OVERLAY = booleanPreferencesKey("rest_overlay")
        val REST_OVERLAY_PERSISTENT = booleanPreferencesKey("rest_overlay_persistent")
        val REST_ALERT_SOUND = stringPreferencesKey("rest_alert_sound")
    }

    /**
     * 是否已经读到 DataStore 的第一份真实设置。
     *
     * `settings` 是 StateFlow，在读到磁盘之前它的值是 [AppSettings] 的默认构造。
     * 进程被闹钟从冷启动拉起时（RestAlarmReceiver → 前台服务），DataStore 还没读完，
     * 这时 `settings.first()` 会**立刻**返回默认值：休息 90 秒、悬浮窗开着、
     * 提示音是闹钟——全都不是用户设置的那份。所以需要「确定值」的地方要走
     * [awaitSettings]。
     */
    private val settled = MutableStateFlow(false)

    /**
     * 设置流，进程内只有一份。
     *
     * 原来这里是冷 Flow：MainActivity（两处）、SettingsViewModel、
     * WorkoutViewModel、HomeViewModel、RestTimerService 各收一份，
     * 任意一个设置变化，每个收集者都要完整跑一遍 map —— 而 map 里要
     * 解密 WebDAV 口令（跨进程 Keystore 调用）。改成 `stateIn(Eagerly)` 后
     * 只跑一遍，收集者共享同一个最新值。
     *
     * Eagerly 而不是 WhileSubscribed：进程启动就开始读，第一个订阅者
     * 拿到的是已就绪的值，不会先闪一下默认主题再跳成用户主题。
     */
    val settings: StateFlow<AppSettings> = context.dataStore.data
        // 配置文件损坏 / 磁盘满时 DataStore 会抛 IOException，
        // 不接住就会一路传到 stateIn 的协程 → 每次启动都崩，用户连设置页都进不去
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            AppSettings(
                themeMode = ThemeMode.from(prefs[Keys.THEME_MODE]?.toIntOrNull() ?: 0),
                weightUnit = WeightUnit.from(prefs[Keys.WEIGHT_UNIT]),
                // 先按 Long 夹住再转 Int：直接 toInt() 遇到超大值会截断成负数，
                // 负数会让倒计时立刻结束，还会把通知的 setProgress 打成非法区间
                restSeconds = (prefs[Keys.REST_SECONDS] ?: 90L).coerceIn(1L, 3600L).toInt(),
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
                restOverlay = prefs[Keys.REST_OVERLAY] ?: true,
                restOverlayPersistent = prefs[Keys.REST_OVERLAY_PERSISTENT] ?: false,
                restAlertSound = AlertSound.from(prefs[Keys.REST_ALERT_SOUND]),
            )
        }
        // 解密 WebDAV 密码要走 Keystore（跨进程），不能在主线程做
        .flowOn(Dispatchers.IO)
        .onEach { settled.value = true }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /**
     * 等到真实设置到位再返回。
     *
     * `settings.first()` 在 StateFlow 上会立刻返回初始占位值，冷启动时读到的
     * 是默认设置。需要「用户真正设置的那份」时必须用这个。
     */
    suspend fun awaitSettings(): AppSettings {
        settled.first { it }
        return settings.value
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.value.toString() }
    }

    suspend fun setWeightUnit(unit: WeightUnit) {
        context.dataStore.edit { it[Keys.WEIGHT_UNIT] = unit.label }
    }

    suspend fun setRestSeconds(seconds: Int) {
        // 写入侧同样夹住：UI 之外（导入、测试工具）也可能调进来
        context.dataStore.edit { it[Keys.REST_SECONDS] = seconds.coerceIn(1, 3600).toLong() }
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

    suspend fun setRestOverlay(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REST_OVERLAY] = enabled }
    }

    suspend fun setRestOverlayPersistent(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REST_OVERLAY_PERSISTENT] = enabled }
    }

    suspend fun setRestAlertSound(sound: AlertSound) {
        context.dataStore.edit { it[Keys.REST_ALERT_SOUND] = sound.value }
    }
}
