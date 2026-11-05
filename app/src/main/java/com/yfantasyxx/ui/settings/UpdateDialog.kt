package com.yfantasyxx.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yfantasyxx.ui.components.ActionText
import com.yfantasyxx.ui.components.AppDialog
import com.yfantasyxx.ui.components.InfoRow
import com.yfantasyxx.update.UpdateInfo
import com.yfantasyxx.update.UpdateRepository
import com.yfantasyxx.update.UpdateState
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val RELEASE_PAGE = "https://github.com/YFantasyXX/YFantasyXX/releases/latest"

/**
 * 更新对话框。
 *
 * 六种状态共用同一个壳：检查中 / 已最新 / 有新版本 / 下载中 / 可安装 / 失败。
 * 下载中不允许点外部关闭（这时候关掉只会让人以为下载停了），
 * 要退出得明确点「取消下载」。
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    currentLabel: String,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dismissable = state !is UpdateState.Downloading

    AppDialog(
        title = titleOf(state),
        onDismissRequest = { if (dismissable) onDismiss() },
    ) {
        Column {
            when (state) {
                UpdateState.Idle, UpdateState.Checking -> {
                    Text(
                        text = "正在向 GitHub 查询最新版本…",
                        style = MiuixTheme.textStyles.paragraph,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                is UpdateState.UpToDate -> {
                    Text(
                        text = "当前已经是最新版本，不需要更新。",
                        style = MiuixTheme.textStyles.paragraph,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (state.fromCache) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "这是 ${agoText(state.checkedAt)} 的检查结果：刚才没能连上 " +
                                "GitHub，先按上次的结论显示。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    InfoRow("当前版本", currentLabel)
                    if (state.fromCache) {
                        InfoRow("上次检查", agoText(state.checkedAt))
                    }
                }

                is UpdateState.Available -> {
                    AvailableBody(state.info, currentLabel, state.fromCache, state.checkedAt)
                }

                is UpdateState.Downloading -> {
                    DownloadingBody(state)
                }

                is UpdateState.Ready -> {
                    ReadyBody(state, currentLabel)
                }

                is UpdateState.Failed -> {
                    Text(
                        text = state.message,
                        style = MiuixTheme.textStyles.paragraph,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(10.dp))
                    InfoRow("当前版本", currentLabel)
                    // 网络被墙、接口限流时重试多少次都没用，留一条浏览器里的出路
                    ActionText(
                        text = "在浏览器里打开 Release 页面",
                        onClick = { openReleasePage(context) },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state) {
                    is UpdateState.Available -> {
                        ActionText(text = "稍后", onClick = onDismiss)
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = onDownload) {
                            Text("下载更新", style = MiuixTheme.textStyles.button)
                        }
                    }

                    is UpdateState.Downloading -> {
                        ActionText(text = "取消下载", onClick = onCancelDownload)
                    }

                    is UpdateState.Ready -> {
                        if (!state.installable) {
                            ActionText(
                                text = "去网页下载",
                                onClick = { openReleasePage(context) },
                            )
                            Spacer(Modifier.width(4.dp))
                            Button(onClick = onDismiss) {
                                Text("关闭", style = MiuixTheme.textStyles.button)
                            }
                        } else {
                            ActionText(text = "关闭", onClick = onDismiss)
                            Spacer(Modifier.width(4.dp))
                            Button(onClick = onInstall) {
                                Text("立即安装", style = MiuixTheme.textStyles.button)
                            }
                        }
                    }

                    is UpdateState.Failed -> {
                        FailedActions(
                            retryAfterAt = state.retryAfterAt,
                            onRetry = onRetry,
                            onDismiss = onDismiss,
                        )
                    }

                    else -> {
                        ActionText(text = "好", onClick = onDismiss)
                    }
                }
            }
        }
    }
}

/**
 * 失败时的按钮区。
 *
 * 被限流时「重试」按了也是白按（仓库那边在这段时间里会直接挡掉请求，一个字节都不发），
 * 所以冷却期间把它换成一个到点自己变回按钮的倒计时。
 * 计时器只在这个小组件里，不会带着整个对话框每 15 秒重组一次。
 */
@Composable
private fun FailedActions(
    retryAfterAt: Long,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var now by remember(retryAfterAt) { mutableLongStateOf(System.currentTimeMillis()) }
    val cooling = retryAfterAt > now

    LaunchedEffect(retryAfterAt) {
        if (retryAfterAt <= 0L) return@LaunchedEffect
        while (System.currentTimeMillis() < retryAfterAt) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
        now = retryAfterAt
    }

    if (cooling) {
        val minutes = ((retryAfterAt - now) / 60_000L).coerceAtLeast(1L)
        Text(
            text = "约 $minutes 分钟后可重试",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(12.dp))
        ActionText(text = "关闭", onClick = onDismiss)
    } else {
        ActionText(text = "关闭", onClick = onDismiss)
        Spacer(Modifier.width(4.dp))
        Button(onClick = onRetry) {
            Text("重试", style = MiuixTheme.textStyles.button)
        }
    }
}

@Composable
private fun AvailableBody(
    info: UpdateInfo,
    currentLabel: String,
    fromCache: Boolean,
    checkedAt: Long,
) {
    InfoRow("当前版本", currentLabel)
    InfoRow(
        "最新版本",
        buildString {
            append("v")
            append(info.versionName)
            if (info.versionCode != null) append(" (${info.versionCode})")
        },
    )
    if (info.sizeBytes > 0L) {
        InfoRow("安装包", UpdateRepository.formatSize(info.sizeBytes))
    }
    if (fromCache) {
        // 缓存里带的是当时的下载地址，release 附件不会过期，仍然能下
        InfoRow("检查时间", agoText(checkedAt))
    }
    Spacer(Modifier.height(10.dp))
    Text(
        text = "更新内容",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    Spacer(Modifier.height(4.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 说明可能很长，限高 + 内部滚动，别把对话框顶出屏幕
            .heightIn(max = 200.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = info.notes,
            style = MiuixTheme.textStyles.paragraph,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DownloadingBody(state: UpdateState.Downloading) {
    val fraction = if (state.total > 0L) {
        (state.bytes.toFloat() / state.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    ProgressBar(fraction)
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "v${state.info.versionName}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = if (state.total > 0L) {
                "${(fraction * 100).toInt()}% · " +
                    "${UpdateRepository.formatSize(state.bytes)} / " +
                    UpdateRepository.formatSize(state.total)
            } else {
                UpdateRepository.formatSize(state.bytes)
            },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "下载期间可以退出这个页面，进度会继续。",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun ReadyBody(state: UpdateState.Ready, currentLabel: String) {
    val note = state.note
    Text(
        text = when {
            !state.installable && note != null -> note
            else -> "安装包已经下载完成并校验通过。" +
                "点「立即安装」会打开系统安装器，覆盖安装不会丢数据。"
        },
        style = MiuixTheme.textStyles.paragraph,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    Spacer(Modifier.height(10.dp))
    InfoRow("当前版本", currentLabel)
    InfoRow("新版本", "v${state.info.versionName}")
    InfoRow("安装包", UpdateRepository.formatSize(state.file.length()))
}

/** 自绘进度条：项目里没有 material3，MIUIX 也没有现成的线性进度条 */
@Composable
private fun ProgressBar(fraction: Float) {
    val color = MiuixTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.16f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}

private fun openReleasePage(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** 「12 分钟前 / 3 小时前 / 11月3日 15:46」，用于标明缓存结果有多旧 */
private fun agoText(at: Long): String {
    if (at <= 0L) return "之前"
    val diff = System.currentTimeMillis() - at
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 24 * 3_600_000L -> "${diff / 3_600_000L} 小时前"
        else -> SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(at))
    }
}

private fun titleOf(state: UpdateState): String = when (state) {
    is UpdateState.Available -> "发现新版本 v${state.info.versionName}"
    is UpdateState.Downloading -> "正在下载更新"
    is UpdateState.Ready -> if (state.installable) "可以安装了" else "无法直接安装"
    // 限流不算「失败」：过一会儿就能查，别用红色语气吓人
    is UpdateState.Failed ->
        if (state.retryAfterAt > System.currentTimeMillis()) "接口暂时限流" else "更新失败"
    is UpdateState.UpToDate -> "已是最新版本"
    UpdateState.Checking -> "检查更新"
    UpdateState.Idle -> "应用更新"
}
