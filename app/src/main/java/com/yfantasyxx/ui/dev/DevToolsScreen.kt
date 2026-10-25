package com.yfantasyxx.ui.dev

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yfantasyxx.BuildConfig
import com.yfantasyxx.service.RestOverlayController
import com.yfantasyxx.ui.components.ActionText
import com.yfantasyxx.ui.components.AppDialog
import com.yfantasyxx.ui.components.InfoRow
import com.yfantasyxx.ui.components.NumberStepper
import com.yfantasyxx.ui.components.SectionHeader
import com.yfantasyxx.ui.components.SettingsCard
import com.yfantasyxx.ui.components.SettingsDivider
import com.yfantasyxx.ui.components.SettingsHint
import com.yfantasyxx.ui.components.SettingsRow
import com.yfantasyxx.ui.rememberAppContainer
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 需要二次确认的危险操作 */
private enum class Pending {
    CLEAR_WORKOUTS,
    SEED_BODY,
    REBUILD_DB,
}

/**
 * 测试工具。只在 lite 版有入口（见 `BuildConfig.FLAVOR`）。
 *
 * 这里刻意把「倒计时」放在第一组：它是改完休息计时后最需要反复验的东西，
 * 而且不用真的开始一次训练——训练页要走到「完成一组」才会起计时。
 */
@Composable
fun DevToolsScreen(onBack: () -> Unit) {
    val container = rememberAppContainer()
    val vm: DevToolsViewModel = viewModel(factory = DevToolsViewModel.factory(container))
    val info by vm.info.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val context = LocalContext.current

    var timerLabel by remember { mutableStateOf("") }
    var customSeconds by remember { mutableStateOf("45") }
    var useAlarm by remember { mutableStateOf(true) }

    var seedDays by remember { mutableIntStateOf(28) }
    var seedPerDay by remember { mutableIntStateOf(3) }
    var seedSets by remember { mutableIntStateOf(3) }
    var includeToday by remember { mutableStateOf(true) }
    var bodyDays by remember { mutableIntStateOf(60) }

    var pending by remember { mutableStateOf<Pending?>(null) }
    var showCrash by remember { mutableStateOf(false) }
    var showLongText by remember { mutableStateOf(false) }

    var notificationsOn by remember { mutableStateOf(notificationsEnabled(context)) }
    var overlayGranted by remember { mutableStateOf(RestOverlayController.canShow(context)) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notificationsOn = notificationsEnabled(context) }

    LaunchedEffect(Unit) { vm.refreshInfo() }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "测试工具",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshInfo() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "刷新信息",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            message?.let { m ->
                MessageBar(m, onDismiss = { vm.clearMessage() })
            }

            if (busy) {
                SettingsHint(
                    text = "处理中…",
                    startIndent = 20.dp,
                    top = 8.dp,
                    bottom = 2.dp,
                )
            }

            /* ───────── 倒计时 / 通知 ───────── */
            SectionHeader("倒计时与通知")
            SettingsCard {
                SettingsHint(
                    text = "走的是训练页同一条路径：前台服务 + 通知栏倒计时 + 到点横幅提醒 + 震动。" +
                        "不用真的开始训练。",
                    top = 12.dp,
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DevChip("5 秒", Modifier.weight(1f)) {
                            vm.startTimer(5, timerLabel, useAlarm)
                        }
                        DevChip("10 秒", Modifier.weight(1f)) {
                            vm.startTimer(10, timerLabel, useAlarm)
                        }
                        DevChip("30 秒", Modifier.weight(1f)) {
                            vm.startTimer(30, timerLabel, useAlarm)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DevChip("60 秒", Modifier.weight(1f)) {
                            vm.startTimer(60, timerLabel, useAlarm)
                        }
                        DevChip("3 分钟", Modifier.weight(1f)) {
                            vm.startTimer(180, timerLabel, useAlarm)
                        }
                        DevChip("5 分钟", Modifier.weight(1f)) {
                            vm.startTimer(300, timerLabel, useAlarm)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = timerLabel,
                        onValueChange = { timerLabel = it },
                        label = "通知标题（留空 = 组间休息）",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = customSeconds,
                            onValueChange = { customSeconds = it.filter(Char::isDigit).take(4) },
                            label = "自定义秒数",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.weight(0.15f))
                        Button(
                            onClick = {
                                vm.startTimer(
                                    customSeconds.toIntOrNull() ?: 10,
                                    timerLabel,
                                    useAlarm,
                                )
                            },
                        ) {
                            Text("开始", style = MiuixTheme.textStyles.button)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                SettingsDivider(startIndent = 16.dp)
                SettingsRow(
                    title = "同时挂系统闹钟",
                    subtitle = "关掉只测前台服务这条路径（App 被冻结时不会响）",
                    trailing = {
                        Switch(checked = useAlarm, onCheckedChange = { useAlarm = it })
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "立即弹「休息结束」",
                    subtitle = "不用等倒计时：直接验通知渠道、提示音和震动",
                    icon = Icons.Filled.NotificationsActive,
                    showChevron = true,
                    onClick = { vm.fireRestFinished() },
                )
                SettingsDivider()
                SettingsRow(
                    title = "强制显示结束悬浮窗",
                    subtitle = if (overlayGranted) {
                        "忽略「App 在前台就不显示」，直接看卡片长什么样（12 秒后消失）"
                    } else {
                        "未授予悬浮窗权限 · 先去「我的 → 休息提醒」开启"
                    },
                    icon = Icons.Filled.PictureInPictureAlt,
                    showChevron = true,
                    onClick = {
                        overlayGranted = RestOverlayController.canShow(context)
                        vm.forceShowOverlay()
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "只震一下（4 段波形）",
                    subtitle = "绕过 2.6 秒去重，连点也能每次都震",
                    icon = Icons.Filled.Vibration,
                    showChevron = true,
                    onClick = { vm.testVibrate() },
                )
                SettingsDivider()
                SettingsRow(
                    title = "停止计时并清通知",
                    icon = Icons.Filled.Close,
                    showChevron = true,
                    onClick = { vm.stopTimer() },
                )
                SettingsDivider()
                SettingsRow(
                    title = "杀掉进程",
                    subtitle = "先发起倒计时再点这里：到点仍应收到提醒，用来验证后台存活",
                    icon = Icons.Filled.Timer,
                    iconTint = MiuixTheme.colorScheme.error,
                    showChevron = true,
                    onClick = { vm.killProcess() },
                )
            }

            /* ───────── 假数据 ───────── */
            SectionHeader("造数据")
            SettingsCard {
                SettingsHint(
                    text = "从空 App 直接铺到能用的状态：3 个模板（推 / 拉 / 腿）+ 一个 5 天计划，" +
                        "重复点也不会造第二份。",
                    top = 12.dp,
                )
                SettingsRow(
                    title = "一键铺好测试环境",
                    subtitle = "模板 + 计划，之后直接去首页点「开始」",
                    icon = Icons.Filled.Add,
                    showChevron = true,
                    onClick = { vm.plantTestSetup() },
                )
                SettingsDivider(startIndent = 16.dp)
                SettingsHint(
                    text = "按天写历史训练（重量随周数递增、最近一天带 PR）。" +
                        "不会碰你的模板和计划结构。",
                    top = 12.dp,
                )
                SettingsRow(
                    title = "生成天数",
                    trailing = {
                        NumberStepper(
                            value = seedDays.toFloat(),
                            onValueChange = { seedDays = it.toInt().coerceIn(1, 365) },
                            step = 7f,
                            unit = " 天",
                        )
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "每天动作数",
                    trailing = {
                        NumberStepper(
                            value = seedPerDay.toFloat(),
                            onValueChange = { seedPerDay = it.toInt().coerceIn(1, 8) },
                            step = 1f,
                            unit = " 个",
                        )
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "每个动作组数",
                    trailing = {
                        NumberStepper(
                            value = seedSets.toFloat(),
                            onValueChange = { seedSets = it.toInt().coerceIn(1, 10) },
                            step = 1f,
                            unit = " 组",
                        )
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "包含今天",
                    subtitle = "关掉就只造昨天及更早，用来对比「今天没练」的首页状态",
                    trailing = {
                        Switch(checked = includeToday, onCheckedChange = { includeToday = it })
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "生成历史训练",
                    subtitle = "约 ${seedDays * seedPerDay * seedSets} 组记录",
                    icon = Icons.Filled.Add,
                    showChevron = true,
                    onClick = {
                        vm.seedHistory(seedDays, seedPerDay, seedSets, includeToday)
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "补全动作库",
                    subtitle = "只补缺的，不会重复",
                    icon = Icons.Filled.Refresh,
                    showChevron = true,
                    onClick = { vm.seedExercises() },
                )
                SettingsDivider()
                SettingsRow(
                    title = "生成体重曲线",
                    subtitle = "$bodyDays 天，会先清空现有体重记录",
                    icon = Icons.Filled.Add,
                    trailing = {
                        ActionText(text = "生成", onClick = { pending = Pending.SEED_BODY })
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "清空所有训练记录",
                    subtitle = "训练、组数、PR 一起删，动作/模板/计划保留",
                    icon = Icons.Filled.Delete,
                    iconTint = MiuixTheme.colorScheme.error,
                    showChevron = true,
                    onClick = { pending = Pending.CLEAR_WORKOUTS },
                )
            }

            /* ───────── 计划 ───────── */
            SectionHeader("计划与时间")
            SettingsCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    InfoRow("当前计划", info.planName ?: "没有启用的计划")
                    InfoRow("进度", info.planDetail ?: "—")
                }
                SettingsHint(
                    text = "「时间前进」= 假装过了 N 天：休息日会立刻流过去，训练日照旧停着等你。",
                    startIndent = 16.dp,
                    bottom = 4.dp,
                )
                SettingsRow(
                    title = "时间前进 1 天",
                    onClick = { vm.shiftPlanTime(1) },
                    showChevron = true,
                )
                SettingsDivider()
                SettingsRow(
                    title = "时间前进 3 天",
                    onClick = { vm.shiftPlanTime(3) },
                    showChevron = true,
                )
                SettingsDivider()
                SettingsRow(
                    title = "时间后退 1 天",
                    subtitle = "休息会被拉长，等同于「再休一天」的极端版",
                    onClick = { vm.shiftPlanTime(-1) },
                    showChevron = true,
                )
                SettingsDivider()
                SettingsRow(
                    title = "跳到下一个训练日",
                    subtitle = "跳过中间的休息格",
                    onClick = { vm.jumpToNextTrainingDay() },
                    showChevron = true,
                )
                SettingsDivider()
                SettingsRow(
                    title = "重置到第 1 天",
                    onClick = { vm.resetPlanProgress() },
                    showChevron = true,
                )
            }

            /* ───────── 数据库 ───────── */
            SectionHeader("数据库")
            SettingsCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    info.tables.forEach { TableRow(it) }
                    Spacer(Modifier.height(6.dp))
                    InfoRow("文件大小", "%.2f MB".format(info.dbBytes / 1024f / 1024f))
                    InfoRow("schema 版本", "v${info.schemaVersion}")
                }
                SettingsDivider(startIndent = 16.dp)
                SettingsRow(
                    title = "WAL 合并到主库",
                    subtitle = "备份前那一步，顺便看能省多少",
                    icon = Icons.Filled.Storage,
                    showChevron = true,
                    onClick = { vm.checkpointWal() },
                )
                SettingsDivider()
                SettingsRow(
                    title = "导出数据库文件",
                    subtitle = "存到 Android/data/${BuildConfig.APPLICATION_ID}/files/dev/",
                    icon = Icons.Filled.FileDownload,
                    showChevron = true,
                    onClick = { vm.exportDatabase() },
                )
                SettingsDivider()
                SettingsRow(
                    title = "重建数据库实例",
                    subtitle = "模拟「恢复备份」之后那一步",
                    icon = Icons.Filled.Refresh,
                    showChevron = true,
                    onClick = { pending = Pending.REBUILD_DB },
                )
            }

            /* ───────── 崩溃 / 日志 ───────── */
            SectionHeader("崩溃与日志")
            SettingsCard {
                SettingsRow(
                    title = "抛一个测试异常",
                    subtitle = "App 会立刻退出；重进后这里能看到刚记下的日志",
                    icon = Icons.Filled.BugReport,
                    iconTint = MiuixTheme.colorScheme.error,
                    showChevron = true,
                    onClick = { vm.crashNow() },
                )
                SettingsDivider()
                SettingsRow(
                    title = "查看最近一次崩溃",
                    subtitle = if (info.crashLog == null) "没有记录" else "有记录，点开看堆栈",
                    icon = Icons.Filled.BugReport,
                    trailing = {
                        ActionText(
                            text = "查看",
                            onClick = { if (info.crashLog != null) showCrash = true },
                        )
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "清空崩溃日志",
                    subtitle = "last_crash.txt 和 logs/ 下的存档",
                    icon = Icons.Filled.Delete,
                    showChevron = true,
                    onClick = { vm.clearCrashLogs() },
                )
            }

            /* ───────── 界面 / 系统 ───────── */
            SectionHeader("界面与系统")
            SettingsCard {
                SettingsRow(
                    title = "通知权限",
                    subtitle = if (notificationsOn) "已开启" else "已关闭，倒计时不会显示在通知栏",
                    icon = Icons.Filled.NotificationsActive,
                    trailing = {
                        ActionText(
                            text = if (notificationsOn) "去设置" else "去开启",
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    !notificationsOn
                                ) {
                                    notificationPermission.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                } else {
                                    openNotificationSettings(context)
                                }
                            },
                        )
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "重建界面（Activity）",
                    subtitle = "验主题切换 / 状态有没有在重建后丢掉",
                    icon = Icons.Filled.Refresh,
                    showChevron = true,
                    onClick = { context.findActivity()?.recreate() },
                )
                SettingsDivider()
                SettingsRow(
                    title = "超长文本对话框",
                    subtitle = "检查换行、省略和滚动有没有崩",
                    showChevron = true,
                    onClick = { showLongText = true },
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    InfoRow("包名", BuildConfig.APPLICATION_ID)
                    InfoRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    InfoRow("构建类型", BuildConfig.BUILD_TYPE)
                    InfoRow("机型", "${Build.BRAND} ${Build.MODEL}")
                    InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    InfoRow("内存", memoryLabel())
                }
            }

            SettingsHint(
                text = "这个页面只在 lite 版出现；正式版里入口和这段代码都会被优化掉。",
                startIndent = 20.dp,
                top = 8.dp,
            )
        }
    }

    /* ---- 确认弹窗 ---- */
    pending?.let { action ->
        val title: String
        val summary: String
        when (action) {
            Pending.CLEAR_WORKOUTS -> {
                title = "清空所有训练记录？"
                summary = "训练历史、每组重量次数、PR 榜会一起删掉，无法撤销。动作库、模板、训练计划不受影响。"
            }

            Pending.SEED_BODY -> {
                title = "重新生成体重曲线？"
                summary = "会先清空现有的体重记录，再写入 $bodyDays 天的测试数据。"
            }

            Pending.REBUILD_DB -> {
                title = "重建数据库实例？"
                summary = "等价于「恢复备份」之后的那一步。各 Repository 还握着旧实例，建议重建完再重建一次界面。"
            }
        }
        AppDialog(
            title = title,
            summary = summary,
            onDismissRequest = { pending = null },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(text = "取消", onClick = { pending = null })
                Spacer(Modifier.size(16.dp))
                Button(
                    onClick = {
                        pending = null
                        when (action) {
                            Pending.CLEAR_WORKOUTS -> vm.clearWorkouts()
                            Pending.SEED_BODY -> vm.seedBodyMetrics(bodyDays)
                            Pending.REBUILD_DB -> vm.rebuildDatabase()
                        }
                    },
                ) {
                    Text("确定", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }

    if (showCrash) {
        val log = info.crashLog
        AppDialog(
            title = "最近一次崩溃",
            onDismissRequest = { showCrash = false },
            fullWidth = true,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = log.orEmpty(),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(text = "关闭", onClick = { showCrash = false })
            }
        }
    }

    if (showLongText) {
        AppDialog(
            title = "超长文本测试",
            onDismissRequest = { showLongText = false },
            fullWidth = true,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text = LONG_TEXT, style = MiuixTheme.textStyles.paragraph)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(text = "关闭", onClick = { showLongText = false })
            }
        }
    }
}

/** 提示条：不自动消失，点 × 才关，方便看清导出路径这类长文本 */
@Composable
private fun MessageBar(message: DevMessage, onDismiss: () -> Unit) {
    val color = if (message.error) {
        MiuixTheme.colorScheme.error
    } else {
        MiuixTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = message.text,
            fontSize = 12.sp,
            color = color,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭提示",
                tint = color,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun DevChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TableRow(item: TableCount) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.table,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = item.rows.toString(),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

private fun notificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun memoryLabel(): String {
    val rt = Runtime.getRuntime()
    val used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
    val max = rt.maxMemory() / 1024 / 1024
    return "$used / $max MB"
}

private val LONG_TEXT = buildString {
    repeat(6) { i ->
        appendLine(
            "第 ${i + 1} 段：" +
                "这是一段用来测试换行的长文本，包含中英文混排、标点符号（，。！？）、" +
                "超长的英文单词 Supercalifragilisticexpialidocious 以及数字 1234567890。" +
                "如果这段文字没有溢出卡片、没有被截断、并且可以正常滚动，说明布局没问题。"
        )
        appendLine()
    }
    append("结尾：没有换行符的超长句子" + "继续".repeat(120) + "。")
}
