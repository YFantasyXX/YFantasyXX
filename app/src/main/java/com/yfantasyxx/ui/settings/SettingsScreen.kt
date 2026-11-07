package com.yfantasyxx.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yfantasyxx.data.prefs.AlertSound
import com.yfantasyxx.data.prefs.BottomBarStyle
import com.yfantasyxx.data.prefs.ThemeMode
import com.yfantasyxx.data.prefs.WeightUnit
import com.yfantasyxx.ui.LocalBottomBarSpace
import com.yfantasyxx.ui.components.ActionText
import com.yfantasyxx.ui.components.AppDialog
import com.yfantasyxx.ui.components.InfoRow
import com.yfantasyxx.ui.components.NumberStepper
import com.yfantasyxx.ui.components.OptionPickerDialog
import com.yfantasyxx.ui.components.PickerOption
import com.yfantasyxx.ui.components.SectionHeader
import com.yfantasyxx.ui.components.SettingsCard
import com.yfantasyxx.ui.components.SettingsDivider
import com.yfantasyxx.ui.components.SettingsHint
import com.yfantasyxx.ui.components.SettingsRow
import com.yfantasyxx.ui.dev.DevTools
import com.yfantasyxx.service.RestOverlayController
import com.yfantasyxx.ui.plans.TrainingPlanDialog
import com.yfantasyxx.ui.rememberAppContainer
import com.yfantasyxx.update.InstallResult
import com.yfantasyxx.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设置页里用底部面板选择的三项 */
private enum class SettingPicker { THEME, UNIT, BOTTOM_BAR, ALERT_SOUND }

/**
 * 悬浮窗权限状态。
 *
 * 去系统设置授权再返回时，`ON_RESUME` 会重新读一次——
 * 不然关掉设置页回来，行上还写着「未授权」。
 */
@Composable
private fun rememberOverlayPermission(): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(RestOverlayController.canShow(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = RestOverlayController.canShow(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return granted
}

@Composable
fun SettingsScreen(
    onOpenTemplates: () -> Unit,
    onOpenDevTools: () -> Unit = {},
) {
    val container = rememberAppContainer()
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val settings by vm.settings.collectAsStateWithLifecycle()
    val refreshRate = rememberDisplayRefreshRate()
    val refreshRateLabel = if (refreshRate > 0f) "${refreshRate.toInt()} Hz" else "读取中…"
    val backupState by vm.backupState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember(settings.webdavUrl) { mutableStateOf(settings.webdavUrl) }
    var user by remember(settings.webdavUsername) { mutableStateOf(settings.webdavUsername) }
    var pass by remember(settings.webdavPassword) { mutableStateOf(settings.webdavPassword) }
    var platesInput by remember(settings.plates) { mutableStateOf(settings.plates) }
    var showBackups by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<SettingPicker?>(null) }
    var showPlans by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    val overlayGranted = rememberOverlayPermission()
    val requestOverlay = {
        runCatching { context.startActivity(RestOverlayController.permissionIntent(context)) }
        Unit
    }

    // CSV 导出（系统文件选择器）
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            scope.launch {
                val csv = vm.buildCsv()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(it)?.use { os ->
                        os.write(csv.toByteArray(Charsets.UTF_8))
                    }
                }
                toastMessage = "已导出 CSV"
            }
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "我的",
                actions = {
                    if (backupState.remoteBackups.isNotEmpty()) {
                        IconButton(onClick = { vm.refreshBackups(url, user, pass) }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "刷新备份列表",
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp + LocalBottomBarSpace.current),
        ) {
            /* ───────── 外观 ───────── */
            SectionHeader("外观")
            SettingsCard {
                SettingsRow(
                    title = "深色模式",
                    value = when (settings.themeMode) {
                        ThemeMode.SYSTEM -> "跟随系统"
                        ThemeMode.LIGHT -> "浅色"
                        ThemeMode.DARK -> "深色"
                    },
                    icon = Icons.Filled.DarkMode,
                    showChevron = true,
                    onClick = { picker = SettingPicker.THEME },
                )
                SettingsDivider()
                SettingsRow(
                    title = "重量单位",
                    value = when (settings.weightUnit) {
                        WeightUnit.KG -> "千克 (kg)"
                        WeightUnit.LB -> "磅 (lb)"
                    },
                    icon = Icons.Filled.Scale,
                    showChevron = true,
                    onClick = { picker = SettingPicker.UNIT },
                )
                SettingsDivider()
                SettingsRow(
                    title = "底栏样式",
                    value = settings.bottomBarStyle.label,
                    icon = Icons.Filled.BlurOn,
                    showChevron = true,
                    onClick = { picker = SettingPicker.BOTTOM_BAR },
                )
                if (settings.bottomBarStyle == BottomBarStyle.FLOATING) {
                    SettingsDivider()
                    SettingsRow(
                        title = "底栏毛玻璃",
                        subtitle = "模糊下方内容，会增加 GPU 开销",
                        icon = Icons.Filled.Opacity,
                        trailing = {
                            Switch(
                                checked = settings.blurBottomBar,
                                onCheckedChange = { vm.setBlurBottomBar(it) },
                            )
                        },
                    )
                }
                SettingsDivider()
                SettingsRow(
                    title = "点击震动",
                    subtitle = "按钮、卡片点击时的触觉反馈",
                    icon = Icons.Filled.Vibration,
                    trailing = {
                        Switch(
                            checked = settings.tapHaptics,
                            onCheckedChange = { vm.setTapHaptics(it) },
                        )
                    },
                )
            }
            SettingsHint(
                text = if (settings.bottomBarStyle == BottomBarStyle.LIQUID_GLASS) {
                    "液态玻璃会实时模糊底栏下方的内容，边缘高光跟着手机倾斜移动。"
                } else {
                    "悬浮胶囊是实心底栏，可以另外开启毛玻璃。"
                },
                startIndent = 20.dp,
                top = 8.dp,
                bottom = 6.dp,
            )

            /* ───────── 训练 ───────── */
            SectionHeader("训练")
            SettingsCard {
                SettingsRow(
                    title = "训练计划",
                    subtitle = "几天一轮的循环，可以排休息日；同时只开一个",
                    icon = Icons.Filled.Autorenew,
                    showChevron = true,
                    onClick = { showPlans = true },
                )
                SettingsDivider()
                SettingsRow(
                    title = "训练模板",
                    subtitle = "动作模板、循环计划与休息天数",
                    icon = Icons.Filled.FitnessCenter,
                    showChevron = true,
                    onClick = onOpenTemplates,
                )
                SettingsDivider()
                SettingsRow(
                    title = "默认休息时长",
                    icon = Icons.Filled.Timer,
                    trailing = {
                        NumberStepper(
                            value = settings.restSeconds.toFloat(),
                            onValueChange = { vm.setRestSeconds(it.toInt()) },
                            step = 15f,
                            unit = "s",
                        )
                    },
                )
            }

            /* ───────── 休息提醒 ───────── */
            SectionHeader("休息提醒")
            SettingsCard {
                SettingsRow(
                    title = "悬浮窗倒计时",
                    subtitle = if (!overlayGranted) {
                        "未授予「显示在其他应用上层」权限 · 点这里去开启"
                    } else if (settings.restOverlay) {
                        "切到桌面或其他应用时，右上角显示剩余时间；结束时弹出大卡片"
                    } else {
                        "已关闭，只在通知栏提醒"
                    },
                    icon = Icons.Filled.PictureInPictureAlt,
                    trailing = {
                        Switch(
                            checked = settings.restOverlay,
                            onCheckedChange = { on ->
                                vm.setRestOverlay(on)
                                // 开开关时顺手把权限要了，省得用户自己去系统设置翻
                                if (on && !overlayGranted) requestOverlay()
                            },
                        )
                    },
                    onClick = {
                        if (!overlayGranted) {
                            requestOverlay()
                        } else {
                            vm.setRestOverlay(!settings.restOverlay)
                        }
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "悬浮窗常驻",
                    subtitle = when {
                        !settings.restOverlay ->
                            "需要先打开上面的「悬浮窗倒计时」"
                        !settings.restOverlayPersistent ->
                            "开着后：App 在前台也显示气泡；结束卡片停 12 秒后收成小球一直挂着"
                        else ->
                            "结束卡片停 12 秒后收成小圆球常驻屏幕；小球不能关，只能关掉这个开关"
                    },
                    icon = Icons.Filled.PushPin,
                    trailing = {
                        Switch(
                            checked = settings.restOverlayPersistent,
                            onCheckedChange = { on ->
                                if (!on) {
                                    vm.setRestOverlayPersistent(false)
                                } else {
                                    // 常驻也依赖悬浮窗权限，顺手要一下
                                    if (!overlayGranted) requestOverlay()
                                    vm.setRestOverlayPersistent(true)
                                }
                            },
                        )
                    },
                    onClick = {
                        if (!settings.restOverlay) {
                            // 上面那个开关都没开，先把它打开
                            vm.setRestOverlay(true)
                            if (!overlayGranted) requestOverlay()
                            vm.setRestOverlayPersistent(true)
                        } else {
                            vm.setRestOverlayPersistent(!settings.restOverlayPersistent)
                        }
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "结束提示音",
                    value = settings.restAlertSound.label,
                    subtitle = settings.restAlertSound.hint,
                    icon = Icons.Filled.NotificationsActive,
                    showChevron = true,
                    onClick = { picker = SettingPicker.ALERT_SOUND },
                )
            }
            if (settings.restOverlay && !overlayGranted) {
                SettingsHint(
                    text = "悬浮窗需要系统权限。荣耀/小米等机型如果重启后失效，" +
                        "去「设置 → 应用 → 特殊权限 → 显示在其他应用上层」重新打开即可。",
                    startIndent = 20.dp,
                    top = 8.dp,
                    bottom = 6.dp,
                )
            }
            if (settings.restOverlay && settings.restOverlayPersistent) {
                SettingsHint(
                    text = "常驻小圆球没有关闭按钮，点它只会回到 App；" +
                        "想收掉就把上面的「悬浮窗常驻」关掉。",
                    startIndent = 20.dp,
                    top = 8.dp,
                    bottom = 6.dp,
                )
            }

            /* ───────── 杠铃片 ───────── */
            SectionHeader("杠铃片")
            SettingsCard {
                SettingsHint(
                    text = "训练页动作菜单里的「杠铃片计算器」会用这里的杆重和配片，反推每边挂什么。",
                    top = 12.dp,
                )
                SettingsRow(
                    title = "杠铃杆重量",
                    icon = Icons.Filled.Scale,
                    trailing = {
                        NumberStepper(
                            value = settings.barWeightKg,
                            onValueChange = { vm.setBarWeight(it) },
                            step = 2.5f,
                            unit = " kg",
                        )
                    },
                )
                SettingsDivider(startIndent = 16.dp)
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    TextField(
                        value = platesInput,
                        onValueChange = { platesInput = it },
                        label = "每侧可用的片（逗号分隔）",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = {
                                vm.setPlates(platesInput)
                                toastMessage = "配片已保存"
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("保存配片", style = MiuixTheme.textStyles.button)
                        }
                        ActionText(
                            text = "恢复默认",
                            onClick = {
                                platesInput = "25,20,15,10,5,2.5,1.25"
                                vm.setPlates(platesInput)
                                toastMessage = "已恢复默认配片"
                            },
                        )
                    }
                }
            }

            /* ───────── WebDAV ───────── */
            SectionHeader("WebDAV 同步")
            SettingsCard {
                SettingsHint(
                    text = "把数据库备份到你的 WebDAV 网盘（坚果云 / Nextcloud / InfiniCLOUD 等）。",
                    top = 12.dp,
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    TextField(
                        value = url,
                        onValueChange = { url = it },
                        label = "服务器地址",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = user,
                        onValueChange = { user = it },
                        label = "用户名",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = "密码 / 应用专用密码",
                        singleLine = true,
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Password
                        ),
                        trailingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .clickable { showPassword = !showPassword },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (showPassword) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                                    modifier = Modifier.size(18.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    // 两条都是用户实际会踩到的坑：
                    // 1) 填 http:// 会被系统明文策略拦掉，报错完全看不懂；
                    // 2) 口令用本机 Keystore 加密，密钥不可导出，换机后解不开，
                    //    系统云备份又只带数据库 → 新机上必须重填一次
                    SettingsHint(
                        text = "地址要以 https:// 开头。口令用本机密钥加密，换机后不会跟系统备份一起恢复，" +
                            "需要重新填写一次。",
                        top = 0.dp,
                        bottom = 10.dp,
                        startIndent = 0.dp,
                    )
                    Button(
                        onClick = {
                            vm.saveWebdav(url, user, pass)
                            toastMessage = "配置已保存"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("保存配置", style = MiuixTheme.textStyles.button)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActionText(
                            text = "测试连接",
                            onClick = {
                                vm.saveWebdav(url, user, pass)
                                // 直接用输入框里的值测，别等 DataStore 写完
                                vm.testWebdav(url, user, pass) { _, msg -> toastMessage = msg }
                            },
                        )
                        ActionText(
                            text = "立即备份",
                            onClick = { vm.backupNow(url, user, pass) },
                        )
                        ActionText(
                            text = "管理备份",
                            onClick = { showBackups = true },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                SettingsDivider(startIndent = 16.dp)
                SettingsRow(
                    title = "训练完成后自动备份",
                    subtitle = "每次结束训练自动上传一份",
                    icon = Icons.Filled.CloudUpload,
                    trailing = {
                        Switch(
                            checked = settings.autoBackup,
                            onCheckedChange = { vm.setAutoBackup(it) },
                        )
                    },
                )
                val lastBackup = backupState.lastBackupTime ?: settings.lastBackupTime
                if (lastBackup != null) {
                    SettingsHint(
                        text = "上次备份：" + SimpleDateFormat(
                            "yyyy-MM-dd HH:mm",
                            Locale.CHINA
                        ).format(Date(lastBackup)),
                        startIndent = 58.dp,
                        bottom = 12.dp,
                    )
                }
            }

            /* ───────── 数据 ───────── */
            SectionHeader("数据")
            SettingsCard {
                SettingsRow(
                    title = "导出训练记录",
                    subtitle = "CSV：全部组数、重量与次数",
                    icon = Icons.Filled.FileDownload,
                    trailing = {
                        Button(onClick = {
                            val name = "yfantasyxx-" + SimpleDateFormat(
                                "yyyyMMdd",
                                Locale.US
                            ).format(Date()) + ".csv"
                            exportLauncher.launch(name)
                        }) {
                            Text("导出", style = MiuixTheme.textStyles.button)
                        }
                    },
                )
            }

            /* ───────── 开发者（只在 lite 版出现） ───────── */
            if (DevTools.enabled) {
                SectionHeader("开发者")
                SettingsCard {
                    SettingsRow(
                        title = "测试工具",
                        subtitle = "倒计时、造数据、计划时间旅行、崩溃日志",
                        icon = Icons.Filled.BugReport,
                        showChevron = true,
                        onClick = onOpenDevTools,
                    )
                }
            }

            /* ───────── 应用更新 ───────── */
            UpdateSection(
                autoCheck = settings.autoCheckUpdate,
                onAutoCheckChange = vm::setAutoCheckUpdate,
                vm = vm,
                onToast = { toastMessage = it },
            )

            /* ───────── 关于 ───────── */
            SectionHeader("关于")
            SettingsCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    InfoRow("应用", "YFantasyXX")
                    InfoRow(
                        "版本",
                        "${com.yfantasyxx.BuildConfig.VERSION_NAME} " +
                            "(${com.yfantasyxx.BuildConfig.VERSION_CODE}) · " +
                            com.yfantasyxx.BuildConfig.BUILD_TYPE
                    )
                    InfoRow("机型", "${Build.BRAND} ${Build.MODEL}")
                    InfoRow("屏幕刷新率", refreshRateLabel)
                    InfoRow("数据存储", "本地 Room + WebDAV 备份")
                    InfoRow("UI 框架", "MIUIX (Compose Multiplatform)")
                    // Apache-2.0 要求保留归属：底栏的玻璃效果由这个库绘制
                    InfoRow("液态玻璃", "Kyant0/AndroidLiquidGlass (Apache-2.0)")
                    InfoRow("开源地址", "github.com/YFantasyXX/YFantasyXX")
                }
            }
        }
    }

    // ---- 选择面板 ----
    when (picker) {
        SettingPicker.THEME -> OptionPickerDialog(
            title = "深色模式",
            options = listOf(
                // 三个选项以前共用一个「月亮」图标，选哪一项都得靠文字认。
                // 改成亮度自动 / 太阳 / 月亮，一眼能对上是哪一档
                PickerOption(ThemeMode.SYSTEM, "跟随系统", "跟随手机的深色模式设置", Icons.Filled.BrightnessAuto),
                PickerOption(ThemeMode.LIGHT, "浅色", "始终使用浅色外观", Icons.Filled.LightMode),
                PickerOption(ThemeMode.DARK, "深色", "始终使用深色外观", Icons.Filled.DarkMode),
            ),
            selected = settings.themeMode,
            onSelect = vm::setThemeMode,
            onDismiss = { picker = null },
        )

        SettingPicker.UNIT -> OptionPickerDialog(
            title = "重量单位",
            options = listOf(
                PickerOption(WeightUnit.KG, "千克 (kg)", "配片按 25/20/15 这类公斤片计算", Icons.Filled.Scale),
                PickerOption(WeightUnit.LB, "磅 (lb)", "显示时换算成磅，底层仍按公斤存储", Icons.Filled.Scale),
            ),
            selected = settings.weightUnit,
            onSelect = vm::setWeightUnit,
            onDismiss = { picker = null },
        )

        SettingPicker.BOTTOM_BAR -> OptionPickerDialog(
            title = "底栏样式",
            options = listOf(
                PickerOption(
                    BottomBarStyle.LIQUID_GLASS,
                    "液态玻璃",
                    "仿 iOS：实时模糊 + 边缘高光 + 滑动玻璃药丸",
                    Icons.Filled.BlurOn,
                ),
                PickerOption(
                    BottomBarStyle.FLOATING,
                    "悬浮胶囊",
                    "实心胶囊，可另外开启毛玻璃",
                    Icons.Filled.Opacity,
                ),
            ),
            selected = settings.bottomBarStyle,
            onSelect = vm::setBottomBarStyle,
            onDismiss = { picker = null },
        )

        SettingPicker.ALERT_SOUND -> OptionPickerDialog(
            title = "结束提示音",
            options = listOf(
                PickerOption(
                    AlertSound.ALARM,
                    "闹钟音量",
                    "走闹钟音量，手机静音也能听见（推荐）",
                    Icons.Filled.Alarm,
                ),
                PickerOption(
                    AlertSound.NOTIFICATION,
                    "通知音量",
                    "跟普通通知一样，安静环境够用",
                    Icons.Filled.Notifications,
                ),
                PickerOption(
                    AlertSound.SILENT,
                    "仅震动",
                    "不发声，只震动 + 悬浮窗",
                    Icons.Filled.Vibration,
                ),
            ),
            selected = settings.restAlertSound,
            onSelect = vm::setRestAlertSound,
            onDismiss = { picker = null },
        )

        null -> Unit
    }

    if (showBackups) {
        // 打开时拉一次列表。原来是在组合期直接调 refreshBackups()，
        // 每次重组都会再发一次 PROPFIND，白白打服务器。
        // 统一用输入框里的配置：列表、备份、恢复、删除必须看的是同一个服务器。
        LaunchedEffect(Unit) { vm.refreshBackups(url, user, pass) }
        BackupListDialog(
            state = backupState,
            onRefresh = { vm.refreshBackups(url, user, pass) },
            onBackupNow = { vm.backupNow(url, user, pass) },
            onRestore = { name ->
                // 失败时不要关弹窗，否则错误信息一闪而过看不到
                vm.restoreBackup(name, url, user, pass) {
                    toastMessage = "恢复成功，正在重启应用"
                    showBackups = false
                }
            },
            onDelete = { name -> vm.deleteBackup(name, url, user, pass) },
            onDismiss = { showBackups = false },
        )
    }

    if (showPlans) {
        TrainingPlanDialog(onDismiss = { showPlans = false })
    }

    toastMessage?.let { msg ->
        AppDialog(
            title = null,
            summary = msg,
            onDismissRequest = { toastMessage = null },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(text = "好", onClick = { toastMessage = null })
            }
        }
    }
}

/**
 * 「应用更新」分区。
 *
 * 单独抽一个 Composable 是为了重组边界：下载进度每 150ms 变一次，
 * 若在 [SettingsScreen] 顶层收集，外观/训练/WebDAV/关于整页都会跟着重组。
 */
@Composable
private fun UpdateSection(
    autoCheck: Boolean,
    onAutoCheckChange: (Boolean) -> Unit,
    vm: SettingsViewModel,
    onToast: (String) -> Unit,
) {
    val state by vm.updateState.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    SectionHeader("应用更新")
    SettingsCard {
        SettingsRow(
            title = "检查更新",
            subtitle = updateSubtitle(state, autoCheck),
            value = updateValue(state),
            icon = Icons.Filled.SystemUpdate,
            showChevron = true,
            onClick = {
                showDialog = true
                // 看过详情就把底栏红点收掉，避免「点进来红点还挂着」
                vm.markUpdateSeen()
                // 已经有结果（待下载 / 可安装 / 下载中）就直接展示，
                // 别每次点都白打一次 GitHub 接口——它每小时只给 60 次
                // 已经有结果（待下载 / 可安装 / 下载中）就直接展示，
                // 别每次点都白打一次 GitHub 接口——它每小时只给 60 次。
                // 限流冷却期间同理，仓库那边会直接挡掉，按了只会以为在检查。
                val cooling = (state as? UpdateState.Failed)
                    ?.retryAfterAt?.let { it > System.currentTimeMillis() } == true
                when (state) {
                    is UpdateState.Available,
                    is UpdateState.Ready,
                    is UpdateState.Downloading,
                    -> Unit
                    else -> if (!cooling) vm.checkUpdate()
                }
            },
        )
        SettingsDivider()
        SettingsRow(
            title = "自动检查",
            subtitle = "启动时静默查一次 GitHub 上的最新 Release，6 小时内不重复",
            icon = Icons.Filled.CloudDownload,
            trailing = {
                Switch(checked = autoCheck, onCheckedChange = onAutoCheckChange)
            },
        )
    }

    if (showDialog) {
        UpdateDialog(
            state = state,
            currentLabel = "${com.yfantasyxx.BuildConfig.VERSION_NAME} " +
                "(${com.yfantasyxx.BuildConfig.VERSION_CODE})",
            onDownload = { vm.downloadUpdate() },
            onCancelDownload = { vm.cancelDownload() },
            onInstall = {
                when (val result = vm.installUpdate()) {
                    InstallResult.Started -> showDialog = false
                    InstallResult.NeedPermission -> onToast(
                        "已打开「安装未知应用」设置，允许后回来再点一次「立即安装」"
                    )
                    is InstallResult.Failed -> onToast(result.message)
                }
            },
            onRetry = { vm.checkUpdate() },
            onDismiss = { showDialog = false },
        )
    }
}

private fun updateSubtitle(state: UpdateState, autoCheck: Boolean): String = when (state) {
    is UpdateState.Available ->
        if (state.fromCache) "有新版本（${agoLabel(state.checkedAt)}的检查结果）"
        else "GitHub 上有新版本，点这里查看"
    is UpdateState.Downloading -> {
        val percent = if (state.total > 0L) state.bytes * 100 / state.total else 0L
        "正在下载 v${state.info.versionName} · $percent%"
    }
    is UpdateState.Ready -> if (state.installable) "安装包已就绪，点这里安装" else "这个包不能直接安装，点这里看原因"
    is UpdateState.UpToDate -> if (state.fromCache) {
        "已是最新 · 缓存于 " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(state.checkedAt))
    } else {
        "已是最新 · 上次检查 " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(state.checkedAt))
    }
    is UpdateState.Failed -> {
        val left = state.retryAfterAt - System.currentTimeMillis()
        when {
            left > 0 -> "接口限流中 · 约 ${(left / 60_000L).coerceAtLeast(1L)} 分钟后可再查"
            // 下载失败会带上 checkedAt，检查失败不会，用这个区分两句文案
            state.checkedAt > 0L -> "下载没成功，点这里重试"
            else -> "上次检查没成功，点这里重试"
        }
    }
    UpdateState.Checking -> "正在检查…"
    UpdateState.Idle ->
        if (autoCheck) "启动时自动检查一次，也可以手动点" else "自动检查已关闭，可手动检查"
}

/** 设置项上的相对时间，别写出「2026-11-05 15:46」这种把一行撑爆的格式 */
private fun agoLabel(at: Long): String {
    val diff = System.currentTimeMillis() - at
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 24 * 3_600_000L -> "${diff / 3_600_000L} 小时前"
        else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(at))
    }
}

private fun updateValue(state: UpdateState): String? = when (state) {
    is UpdateState.Available -> "v${state.info.versionName}"
    is UpdateState.Ready -> "可安装"
    is UpdateState.UpToDate -> "已最新"
    is UpdateState.Downloading -> "下载中"
    else -> null
}

/**
 * 屏幕当前刷新率。
 *
 * 以前是 `while(true) { … delay(800) }` 轮询：只要页面还在组合，就永远每 800ms
 * 读一次 display 并写一次状态，白白产生重组；改成读一次 + 监听显示变化。
 */
@Composable
private fun rememberDisplayRefreshRate(): Float {
    val context = LocalContext.current
    var rate by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.DisposableEffect(context) {
        fun read() {
            runCatching {
                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.display
                } else {
                    @Suppress("DEPRECATION")
                    (context.getSystemService(android.content.Context.WINDOW_SERVICE)
                        as android.view.WindowManager).defaultDisplay
                }
                rate = display?.refreshRate ?: 0f
            }
        }
        read()
        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) = read()
        }
        dm?.registerDisplayListener(listener, null)
        onDispose { dm?.unregisterDisplayListener(listener) }
    }
    return rate
}
