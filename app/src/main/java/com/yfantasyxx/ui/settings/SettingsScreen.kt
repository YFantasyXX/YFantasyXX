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
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yfantasyxx.data.prefs.BottomBarStyle
import com.yfantasyxx.data.prefs.ThemeMode
import com.yfantasyxx.data.prefs.WeightUnit
import com.yfantasyxx.ui.LocalBottomBarSpace
import com.yfantasyxx.ui.components.ActionText
import com.yfantasyxx.ui.components.AppDialog
import com.yfantasyxx.ui.components.InfoRow
import com.yfantasyxx.ui.components.NumberStepper
import com.yfantasyxx.ui.components.OptionPickerSheet
import com.yfantasyxx.ui.components.PickerOption
import com.yfantasyxx.ui.components.SectionHeader
import com.yfantasyxx.ui.components.SettingsCard
import com.yfantasyxx.ui.components.SettingsDivider
import com.yfantasyxx.ui.components.SettingsHint
import com.yfantasyxx.ui.components.SettingsRow
import com.yfantasyxx.ui.plans.TrainingPlanDialog
import com.yfantasyxx.ui.rememberAppContainer
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
private enum class SettingPicker { THEME, UNIT, BOTTOM_BAR }

@Composable
fun SettingsScreen(onOpenTemplates: () -> Unit) {
    val container = rememberAppContainer()
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val settings by vm.settings.collectAsState()
    val refreshRate = rememberDisplayRefreshRate()
    val refreshRateLabel = if (refreshRate > 0f) "${refreshRate.toInt()} Hz" else "读取中…"
    val backupState by vm.backupState.collectAsState()
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
                        IconButton(onClick = { vm.refreshBackups() }) {
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
                SettingsDivider()
                SettingsRow(
                    title = "每周训练目标",
                    subtitle = "0 = 不设目标，首页显示本周进度",
                    icon = Icons.Filled.Flag,
                    trailing = {
                        NumberStepper(
                            value = settings.weeklyGoal.toFloat(),
                            onValueChange = { vm.setWeeklyGoal(it.toInt()) },
                            step = 1f,
                            unit = " 次",
                        )
                    },
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
                                vm.testWebdav { _, msg -> toastMessage = msg }
                            },
                        )
                        ActionText(
                            text = "立即备份",
                            onClick = { vm.backupNow() },
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
                    InfoRow("开源地址", "github.com/YFantasyXX/YFantasyXX")
                }
            }
        }
    }

    // ---- 选择面板 ----
    when (picker) {
        SettingPicker.THEME -> OptionPickerSheet(
            title = "深色模式",
            options = listOf(
                PickerOption(ThemeMode.SYSTEM, "跟随系统", "跟随手机的深色模式设置", Icons.Filled.DarkMode),
                PickerOption(ThemeMode.LIGHT, "浅色", "始终使用浅色外观", Icons.Filled.DarkMode),
                PickerOption(ThemeMode.DARK, "深色", "始终使用深色外观", Icons.Filled.DarkMode),
            ),
            selected = settings.themeMode,
            onSelect = vm::setThemeMode,
            onDismiss = { picker = null },
        )

        SettingPicker.UNIT -> OptionPickerSheet(
            title = "重量单位",
            options = listOf(
                PickerOption(WeightUnit.KG, "千克 (kg)", "配片按 25/20/15 这类公斤片计算", Icons.Filled.Scale),
                PickerOption(WeightUnit.LB, "磅 (lb)", "显示时换算成磅，底层仍按公斤存储", Icons.Filled.Scale),
            ),
            selected = settings.weightUnit,
            onSelect = vm::setWeightUnit,
            onDismiss = { picker = null },
        )

        SettingPicker.BOTTOM_BAR -> OptionPickerSheet(
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

        null -> Unit
    }

    if (showBackups) {
        vm.refreshBackups()
        AppDialog(
            title = "云端备份",
            onDismissRequest = { showBackups = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (backupState.running) {
                    Text("处理中…", style = MiuixTheme.textStyles.paragraph)
                }
                backupState.message?.let { msg ->
                    Text(
                        text = msg,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (backupState.isError) {
                            MiuixTheme.colorScheme.error
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (backupState.remoteBackups.isEmpty()) {
                    Text(
                        text = "暂无云端备份",
                        style = MiuixTheme.textStyles.paragraph,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    backupState.remoteBackups.sortedDescending().forEach { name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = name.removePrefix("yfantasyxx-backup-").removeSuffix(".db"),
                                style = MiuixTheme.textStyles.paragraph,
                                modifier = Modifier.weight(1f),
                            )
                            ActionText(text = "恢复", onClick = {
                                vm.restoreBackup(name) {
                                    toastMessage = "恢复成功，正在刷新数据"
                                }
                                showBackups = false
                            })
                            ActionText(text = "删除", onClick = { vm.deleteBackup(name) })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ActionText(text = "关闭", onClick = { showBackups = false })
                }
            }
        }
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

/** 实时读取屏幕当前刷新率（滚动/静止时可能不同） */
@Composable
private fun rememberDisplayRefreshRate(): Float {
    val context = LocalContext.current
    var rate by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
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
            delay(800)
        }
    }
    return rate
}
