package com.yfantasyxx.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yfantasyxx.data.prefs.BottomBarStyle
import com.yfantasyxx.data.prefs.ThemeMode
import com.yfantasyxx.data.prefs.WeightUnit
import com.yfantasyxx.ui.components.InfoRow
import com.yfantasyxx.ui.components.NumberStepper
import com.yfantasyxx.ui.components.SectionHeader
import com.yfantasyxx.ui.rememberAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import com.yfantasyxx.ui.components.AppDialog
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.yfantasyxx.ui.components.ActionText
import com.yfantasyxx.ui.components.SegmentedSelector
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import android.os.Build
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size

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
    var showBackups by remember { mutableStateOf(false) }
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
                .padding(bottom = 24.dp + com.yfantasyxx.ui.LocalBottomBarSpace.current),
        ) {
            // ---- 外观 ----
            SectionHeader("外观")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("深色模式", style = MiuixTheme.textStyles.main)
                    Spacer(Modifier.height(10.dp))
                    SegmentedSelector(
                        options = listOf("跟随系统", "浅色", "深色"),
                        selectedIndex = ThemeMode.entries.indexOf(settings.themeMode),
                        onSelect = { index ->
                            ThemeMode.entries.getOrNull(index)?.let(vm::setThemeMode)
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("重量单位", style = MiuixTheme.textStyles.main)
                    Spacer(Modifier.height(10.dp))
                    SegmentedSelector(
                        options = WeightUnit.entries.map { it.label },
                        selectedIndex = WeightUnit.entries.indexOf(settings.weightUnit),
                        onSelect = { index ->
                            WeightUnit.entries.getOrNull(index)?.let(vm::setWeightUnit)
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("点击震动", style = MiuixTheme.textStyles.main)
                            Text(
                                text = "点击按钮、卡片时的触觉反馈",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = settings.tapHaptics,
                            onCheckedChange = { vm.setTapHaptics(it) },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("底栏样式", style = MiuixTheme.textStyles.main)
                    Spacer(Modifier.height(10.dp))
                    SegmentedSelector(
                        options = BottomBarStyle.entries.map { it.label },
                        selectedIndex = BottomBarStyle.entries.indexOf(settings.bottomBarStyle),
                        onSelect = { index ->
                            BottomBarStyle.entries.getOrNull(index)?.let(vm::setBottomBarStyle)
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (settings.bottomBarStyle == BottomBarStyle.LIQUID_GLASS) {
                            "仿 iOS 液态玻璃：实时模糊下方内容，边缘高光会跟着手机倾斜移动"
                        } else {
                            "实心悬浮胶囊，可另外开启毛玻璃"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (settings.bottomBarStyle == BottomBarStyle.FLOATING) {
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("底栏毛玻璃", style = MiuixTheme.textStyles.main)
                                Text(
                                    text = "模糊底栏下方内容，会增加 GPU 开销",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = settings.blurBottomBar,
                                onCheckedChange = { vm.setBlurBottomBar(it) },
                            )
                        }
                    }
                }
            }

            // ---- 训练设置 ----
            SectionHeader("训练")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("默认休息时长", style = MiuixTheme.textStyles.main)
                    Spacer(Modifier.height(8.dp))
                    NumberStepper(
                        value = settings.restSeconds.toFloat(),
                        onValueChange = { vm.setRestSeconds(it.toInt()) },
                        step = 15f,
                        unit = "s",
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "训练模板",
                            style = MiuixTheme.textStyles.main,
                            modifier = Modifier.weight(1f),
                        )
                        ActionText(text = "管理", onClick = onOpenTemplates)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("每周训练目标", style = MiuixTheme.textStyles.main)
                    Text(
                        text = "设为 0 表示不设目标；首页会显示本周进度",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    NumberStepper(
                        value = settings.weeklyGoal.toFloat(),
                        onValueChange = { vm.setWeeklyGoal(it.toInt()) },
                        step = 1f,
                        unit = " 次",
                    )
                }
            }

            // ---- 杠铃片计算器设置 ----
            SectionHeader("杠铃片")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "训练页里可以通过动作菜单里的「杠铃片计算器」反推每边挂什么片，" +
                            "这里设定你的杆重和现有的片。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("杠铃杆重量", style = MiuixTheme.textStyles.main)
                    Spacer(Modifier.height(8.dp))
                    NumberStepper(
                        value = settings.barWeightKg,
                        onValueChange = { vm.setBarWeight(it) },
                        step = 2.5f,
                        unit = " kg",
                    )
                    Spacer(Modifier.height(12.dp))
                    var platesInput by remember(settings.plates) {
                        mutableStateOf(settings.plates)
                    }
                    TextField(
                        value = platesInput,
                        onValueChange = { platesInput = it },
                        label = "每侧可用的片（逗号分隔）",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            vm.setPlates(platesInput)
                            toastMessage = "配片已保存"
                        }) {
                            Text("保存配片", style = MiuixTheme.textStyles.button)
                        }
                        ActionText(
                            text = "恢复默认",
                            onClick = {
                                platesInput = "25,20,15,10,5,2.5,1.25"
                                vm.setPlates(platesInput)
                            },
                        )
                    }
                }
            }

            // ---- WebDAV 同步 ----
            SectionHeader("WebDAV 同步")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "备份数据库到你的 WebDAV 网盘（坚果云 / Nextcloud / InfiniCLOUD 等）",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(12.dp))
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
                    var showPassword by remember { mutableStateOf(false) }
                    TextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = "密码 / 应用专用密码",
                        singleLine = true,
                        visualTransformation = if (showPassword) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            androidx.compose.ui.text.input.PasswordVisualTransformation()
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                        ),
                        trailingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.saveWebdav(url, user, pass); toastMessage = "配置已保存" }) {
                            Text("保存配置", style = MiuixTheme.textStyles.button)
                        }
                        Button(
                            onClick = {
                                vm.saveWebdav(url, user, pass)
                                vm.testWebdav { ok, msg -> toastMessage = msg }
                            },
                        ) {
                            Text("测试连接", style = MiuixTheme.textStyles.button)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "训练完成后自动备份",
                            style = MiuixTheme.textStyles.main,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = settings.autoBackup,
                            onCheckedChange = { vm.setAutoBackup(it) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.backupNow() }) {
                            Text("立即备份", style = MiuixTheme.textStyles.button)
                        }
                        ActionText(text = "管理备份", onClick = { showBackups = true })
                    }
                    if (settings.lastBackupTime != null || backupState.lastBackupTime != null) {
                        Spacer(Modifier.height(8.dp))
                        val t = backupState.lastBackupTime ?: settings.lastBackupTime!!
                        Text(
                            text = "上次备份：" + SimpleDateFormat(
                                "yyyy-MM-dd HH:mm",
                                Locale.CHINA
                            ).format(Date(t)),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            // ---- 数据 ----
            SectionHeader("数据")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "导出训练记录 (CSV)",
                            style = MiuixTheme.textStyles.main,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = {
                            val name = "yfantasyxx-" + SimpleDateFormat(
                                "yyyyMMdd",
                                Locale.US
                            ).format(Date()) + ".csv"
                            exportLauncher.launch(name)
                        }) {
                            Text("导出", style = MiuixTheme.textStyles.button)
                        }
                    }
                }
            }

            // ---- 关于 ----
            SectionHeader("关于")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow("应用", "YFantasyXX")
                    InfoRow(
                        "版本",
                        "${com.yfantasyxx.BuildConfig.VERSION_NAME} " +
                            "(${com.yfantasyxx.BuildConfig.VERSION_CODE}) · " +
                            com.yfantasyxx.BuildConfig.BUILD_TYPE
                    )
                    InfoRow(
                        "机型",
                        "${android.os.Build.BRAND} ${android.os.Build.MODEL}"
                    )
                    InfoRow("屏幕刷新率", refreshRateLabel)
                    InfoRow("数据存储", "本地 Room + WebDAV 备份")
                    InfoRow("UI 框架", "MIUIX (Compose Multiplatform)")
                    InfoRow("开源地址", "github.com/YFantasyXX/YFantasyXX")
                }
            }
        }
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

    toastMessage?.let { msg ->
        AppDialog(
            title = null,
            summary = msg,
            onDismissRequest = { toastMessage = null },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
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
            kotlinx.coroutines.delay(800)
        }
    }
    return rate
}
