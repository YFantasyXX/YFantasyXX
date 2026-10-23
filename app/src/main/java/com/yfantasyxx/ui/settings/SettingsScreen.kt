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

@Composable
fun SettingsScreen(onOpenTemplates: () -> Unit) {
    val container = rememberAppContainer()
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val settings by vm.settings.collectAsState()
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
                .padding(bottom = 24.dp),
        ) {
            // ---- 外观 ----
            SectionHeader("外观")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("深色模式", style = MiuixTheme.textStyles.main)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            Surface(
                                onClick = { vm.setThemeMode(mode) },
                                color = if (settings.themeMode == mode) {
                                    MiuixTheme.colorScheme.primaryContainer
                                } else {
                                    MiuixTheme.colorScheme.surfaceContainer
                                },
                            ) {
                                Text(
                                    text = when (mode) {
                                        ThemeMode.SYSTEM -> "跟随系统"
                                        ThemeMode.LIGHT -> "浅色"
                                        ThemeMode.DARK -> "深色"
                                    },
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = if (settings.themeMode == mode) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("重量单位", style = MiuixTheme.textStyles.main)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WeightUnit.entries.forEach { unit ->
                            Surface(
                                onClick = { vm.setWeightUnit(unit) },
                                color = if (settings.weightUnit == unit) {
                                    MiuixTheme.colorScheme.primaryContainer
                                } else {
                                    MiuixTheme.colorScheme.surfaceContainer
                                },
                            ) {
                                Text(
                                    text = unit.label,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = if (settings.weightUnit == unit) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
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
                    TextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = "密码 / 应用专用密码",
                        singleLine = true,
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
                    InfoRow("版本", "1.0.0")
                    InfoRow("UI 框架", "MIUIX (Compose Multiplatform)")
                    InfoRow("数据存储", "本地 Room + WebDAV 备份")
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
