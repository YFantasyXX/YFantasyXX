package com.yfantasyxx.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfantasyxx.sync.BackupFileMeta
import com.yfantasyxx.sync.BackupManager
import com.yfantasyxx.sync.BackupState
import com.yfantasyxx.sync.RemoteBackupFile
import com.yfantasyxx.sync.WebDavClient
import com.yfantasyxx.ui.components.ActionText
import com.yfantasyxx.ui.components.AppDialog
import com.yfantasyxx.ui.components.EmptyState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 云端备份列表。
 *
 * 之前每行只显示去掉前缀的文件名（`20261025-140322`），一眼看不出哪个新哪个旧。
 * 现在：
 * - 按「今天 / 昨天 / 更早」分组，一眼分辨不同日期；
 * - 时间显示成人话（今天 14:03 / 10月22日 19:03）；
 * - 最新的那份排在最上面并标「最新」；
 * - 附带相对时间（3 小时前）和文件大小。
 */
@Composable
fun BackupListDialog(
    state: BackupState,
    onRefresh: () -> Unit,
    onBackupNow: () -> Unit,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 待删除确认的文件名
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    // 待恢复确认的文件名（恢复会覆盖当前数据，必须确认）
    var pendingRestore by remember { mutableStateOf<String?>(null) }

    // 打开时的基准时间：相对时间（3 小时前）按它算，避免每次重组都变
    val now = remember { System.currentTimeMillis() }
    val items = remember(state.remoteBackups) {
        state.remoteBackups
            .map { BackupItem(it) }
            .sortedByDescending { it.timestamp ?: Long.MIN_VALUE }
    }
    val groups = remember(items, now) { groupItems(items, now) }
    val latestName = items.firstOrNull()?.name
    val appSchema = state.appSchemaVersion

    AppDialog(
        title = "云端备份",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 顶部：概览 + 刷新
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (items.isEmpty()) {
                        "还没有备份"
                    } else {
                        "共 ${items.size} 份 · 最新 ${items.first().mainLabel(now)}"
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新备份列表",
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            state.message?.let { msg ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = msg,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (state.isError) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "网盘目录 ${WebDavClient.BACKUP_DIRECTORY}/ · 每天留最后一份，最多 ${BackupManager.KEEP_DAYS} 天",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
            )

            if (state.running) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "处理中…",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            Spacer(Modifier.height(10.dp))

            if (items.isEmpty()) {
                EmptyState(
                    text = "云端还没有备份文件\n点下面的「立即备份」上传一份",
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    groups.forEach { group ->
                        if (group.items.isNotEmpty()) {
                            GroupHeader(group.title, group.items.size)
                            group.items.forEachIndexed { index, item ->
                                BackupRow(
                                    item = item,
                                    isLatest = item.name == latestName,
                                    now = now,
                                    appSchema = appSchema,
                                    onRestore = { pendingRestore = item.name },
                                    onDelete = { pendingDelete = item.name },
                                )
                                if (index != group.items.lastIndex) {
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionText(text = "立即备份", onClick = onBackupNow)
                ActionText(text = "关闭", onClick = onDismiss)
            }
        }
    }

    // 恢复会覆盖当前全部数据，确认一次
    pendingRestore?.let { name ->
        AppDialog(
            title = "用这份备份覆盖当前数据？",
            summary = "「${BackupItem(RemoteBackupFile(name)).mainLabel(now)}」会覆盖你现在所有的训练记录，" +
                "当前数据不可恢复。\n备份来自更新的 App 版本、或者是空库时，会直接拦下不会写入。",
            onDismissRequest = { pendingRestore = null },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(text = "取消", onClick = { pendingRestore = null })
                Spacer(Modifier.width(12.dp))
                ActionText(
                    text = "恢复",
                    color = MiuixTheme.colorScheme.primary,
                    onClick = {
                        pendingRestore = null
                        onRestore(name)
                    },
                )
            }
        }
    }

    // 删除是不可逆的，确认一次
    pendingDelete?.let { name ->
        AppDialog(
            title = "删除这份备份？",
            summary = "「${BackupItem(RemoteBackupFile(name)).mainLabel(now)}」将被永久删除，无法恢复。",
            onDismissRequest = { pendingDelete = null },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(text = "取消", onClick = { pendingDelete = null })
                Spacer(Modifier.width(12.dp))
                ActionText(
                    text = "删除",
                    color = MiuixTheme.colorScheme.error,
                    onClick = {
                        pendingDelete = null
                        onDelete(name)
                    },
                )
            }
        }
    }
}

/** 分组小标题 */
@Composable
private fun GroupHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$count 份",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
        )
    }
}

/** 单条备份 */
@Composable
private fun BackupRow(
    item: BackupItem,
    isLatest: Boolean,
    now: Long,
    appSchema: Int,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val tooNew = item.tooNew(appSchema)
    val suspicious = item.size == 0L || tooNew
    val secondary = listOfNotNull(
        item.relativeLabel(now),
        item.sizeLabel(),
        // 文件名里带着写这份备份时的 schema 版本，方便判断能不能恢复
        item.schemaVersion?.let { "v$it" },
        // 0 字节说明上次上传被打断了，这份不能用来恢复
        if (item.size == 0L) "文件为空，可能不可用" else null,
        if (tooNew) "比当前 App 新，恢复不了" else null,
    ).joinToString(" · ")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isLatest) {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.09f)
                } else {
                    MiuixTheme.colorScheme.surfaceContainer
                }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.mainLabel(now),
                        style = MiuixTheme.textStyles.main,
                    )
                    if (isLatest) {
                        Spacer(Modifier.width(8.dp))
                        LatestBadge()
                    }
                }
                if (secondary.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = secondary,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (suspicious) {
                            MiuixTheme.colorScheme.error
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            BackupAction(text = "恢复", color = MiuixTheme.colorScheme.primary, onClick = onRestore)
            BackupAction(text = "删除", color = MiuixTheme.colorScheme.error, onClick = onDelete)
        }
    }
}

/** 「最新」小徽章 */
@Composable
private fun LatestBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "最新",
            style = MiuixTheme.textStyles.footnote1,
            fontSize = 10.sp,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

/** 备份行里的文字按钮（比通用 ActionText 更紧凑） */
@Composable
private fun BackupAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.button,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/* ───────────────────── 数据模型与格式化 ───────────────────── */

/** 列表里的一项 */
private data class BackupItem(
    val name: String,
    val timestamp: Long?,
    val size: Long,
    val schemaVersion: Int?,
) {
    constructor(file: RemoteBackupFile) : this(
        name = file.name,
        timestamp = BackupFileMeta.effectiveTimestamp(file),
        size = file.size,
        schemaVersion = BackupFileMeta.schemaOf(file.name),
    )

    /** 备份的 schema 比当前 App 高：旧版 Room 会走销毁式迁移把数据清空，必须拦住 */
    fun tooNew(appSchema: Int): Boolean =
        schemaVersion != null && appSchema > 0 && schemaVersion > appSchema

    /** 主标题：今天 14:03 / 昨天 21:40 / 10月22日 19:03 */
    fun mainLabel(now: Long): String {
        val ts = timestamp ?: return "未知时间"
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts))
        return when (groupOf(ts, now)) {
            BackupGroup.TODAY -> "今天 $time"
            BackupGroup.YESTERDAY -> "昨天 $time"
            BackupGroup.EARLIER -> {
                val sameYear = yearOf(ts) == yearOf(now)
                val date = if (sameYear) {
                    SimpleDateFormat("M月d日", Locale.CHINA).format(Date(ts))
                } else {
                    SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(ts))
                }
                "$date $time"
            }
        }
    }

    /** 副标题：3 小时前 */
    fun relativeLabel(now: Long): String? {
        val ts = timestamp ?: return null
        val minutes = (now - ts) / 60_000L
        return when {
            minutes < 0 -> null
            minutes < 1 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 60 * 24 -> "${minutes / 60} 小时前"
            minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天前"
            else -> "${minutes / (60 * 24 * 30)} 个月前"
        }
    }

    /** 副标题：1.2 MB */
    fun sizeLabel(): String? {
        if (size < 0) return null
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024L * 1024 * 1024 ->
                String.format(Locale.CHINA, "%.1f MB", size / 1024.0 / 1024)
            else -> String.format(Locale.CHINA, "%.1f GB", size / 1024.0 / 1024 / 1024)
        }
    }
}

private enum class BackupGroup(val title: String) {
    TODAY("今天"),
    YESTERDAY("昨天"),
    EARLIER("更早"),
}

private data class BackupGroupBlock(
    val group: BackupGroup,
    val items: List<BackupItem>,
) {
    val title: String get() = group.title
}

private fun groupItems(items: List<BackupItem>, now: Long): List<BackupGroupBlock> =
    BackupGroup.entries.map { group ->
        BackupGroupBlock(group, items.filter { groupOf(it.timestamp, now) == group })
    }

/** 判断某个时间点属于今天 / 昨天 / 更早 */
private fun groupOf(timestamp: Long?, now: Long): BackupGroup {
    if (timestamp == null) return BackupGroup.EARLIER
    val todayStart = startOfDay(now)
    val yesterdayStart = startOfDay(addDays(now, -1))
    return when {
        timestamp >= todayStart -> BackupGroup.TODAY
        timestamp >= yesterdayStart -> BackupGroup.YESTERDAY
        else -> BackupGroup.EARLIER
    }
}

private fun yearOf(ts: Long): Int =
    Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.YEAR)

private fun startOfDay(ts: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/** 按日历加减天数（避开夏令时导致的 23/25 小时误差） */
private fun addDays(ts: Long, days: Int): Long =
    Calendar.getInstance().apply {
        timeInMillis = ts
        add(Calendar.DAY_OF_YEAR, days)
    }.timeInMillis
