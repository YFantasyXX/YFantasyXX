package com.yfantasyxx.ui.cardio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yfantasyxx.data.CardioControl
import com.yfantasyxx.data.CardioControls
import com.yfantasyxx.data.CardioType
import com.yfantasyxx.ui.components.ActionText
import com.yfantasyxx.ui.components.AppDialog
import com.yfantasyxx.ui.components.NumberStepper
import com.yfantasyxx.ui.components.cardInsetColor
import com.yfantasyxx.ui.rememberAppContainer
import com.yfantasyxx.util.CardioClock
import com.yfantasyxx.util.Clock
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.yfantasyxx.ui.components.GlassButton

/**
 * 计时页。
 *
 * 秒表不靠「每秒 +1」累加，而是每次都用 `现在 - 开始时刻 - 暂停累计` 现算：
 * 计时器在后台被降频、甚至进程被杀重进，数字都是对的。
 *
 * 直接按返回不会中断计时 —— 状态在 [CardioClock] 里，列表页会显示「进行中」。
 * 想丢弃这一段，走「完成」弹窗里的「放弃这次」。
 */
@Composable
fun CardioActiveScreen(
    initialType: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val container = rememberAppContainer()
    val vm: CardioViewModel = viewModel(factory = CardioViewModel.factory(container))
    val context = LocalContext.current

    var active by remember { mutableStateOf(CardioClock.read(context)) }
    // 器械参数跟着秒表一起落盘：切后台、锁屏再回来，坡度和速度都还在
    var settings by remember { mutableStateOf("") }
    var showTypePicker by remember { mutableStateOf(false) }
    var showFinish by remember { mutableStateOf(false) }
    // 打开「完成」弹窗时是否正在计时：填距离、写备注的这几十秒不该算进训练时长。
    // 关掉弹窗（继续练）时按这个标志恢复。
    var resumeAfterFinish by remember { mutableStateOf(false) }

    // 首次进入：没有进行中的就按传入的类型开一段，并铺上该类型的默认参数
    LaunchedEffect(Unit) {
        if (CardioClock.read(context) == null) {
            val startType = CardioType.from(initialType ?: CardioType.RUN.key)
            CardioClock.start(
                context,
                startType.key,
                settings = CardioControls.encode(CardioControls.defaults(startType)),
            )
        }
        active = CardioClock.read(context)
        // 老版本开始的一段没有 settings：展示时用默认值，但不在这里回写。
        // 用户一次都没动过的话，这条记录就该是空的。
        settings = active?.settings.orEmpty()
    }

    // 250ms 一次：秒数要跟手，但没必要每帧重组。暂停时也照跑，
    // 反正只影响这一个页面，而且 resumed 后第一帧就是准的。
    var now by remember { mutableStateOf(Clock.realNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Clock.realNow()
            delay(250)
        }
    }

    val current = active
    val type = CardioType.from(current?.type)
    val elapsedMs = current?.elapsedMs(now) ?: 0L
    val running = current?.running ?: true

    // 改一个参数就把整份 JSON 重写回去：参数最多两项，没必要做增量合并。
    fun updateControl(control: CardioControl, value: Float) {
        val next = CardioControls.decode(settings)
            .ifEmpty { CardioControls.defaults(type) }
            .toMutableMap()
        next[control.key] = value.coerceIn(control.min, control.max)
        val json = CardioControls.encode(next)
        CardioClock.setSettings(context, json)
        settings = json
        active = CardioClock.read(context)
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = type.label,
                subtitle = if (running) "正在计时" else "已暂停",
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
                    ActionText(text = "换类型", onClick = { showTypePicker = true })
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 上下各留一份弹性空间，让秒表落在视觉中心偏上，
            // 而不是挤在顶部、下面空掉半屏
            Spacer(Modifier.weight(0.6f))

            // 秒表本体
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = formatClock(elapsedMs),
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Light,
                        // 暂停时压暗：不用文字提示也能一眼看出表停了
                        color = if (running) {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = current?.let {
                            "${formatCardioDay(it.startedAt)} ${formatCardioTime(it.startedAt)} 开始"
                        } ?: "准备开始",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    GlassButton(
                        onClick = {
                            if (running) {
                                CardioClock.pause(context)
                            } else {
                                CardioClock.resume(context)
                            }
                            active = CardioClock.read(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (running) "  暂停" else "  继续",
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    GlassButton(
                        onClick = {
                            // 先暂停再开弹窗：弹窗里显示多少，保存的就是多少。
                            // 否则用户在弹窗里填 30 秒距离，这 30 秒会一起算进时长——
                            // 他以为练了 12:34，存进去的却是 13:04。
                            resumeAfterFinish = CardioClock.read(context)?.running == true
                            if (resumeAfterFinish) CardioClock.pause(context)
                            active = CardioClock.read(context)
                            showFinish = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("完成", style = MiuixTheme.textStyles.button)
                    }
                }
            }

            // 器械参数：跑步机是坡度 + 速度，骑行是阻力 + 速度，其余一项。
            // 没有参数的类型不画这张卡（保持原来的留白节奏）
            if (type.controls.isNotEmpty()) {
                val values = CardioControls.decode(settings)
                    .ifEmpty { CardioControls.defaults(type) }
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "器械参数",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        type.controls.forEach { control ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = control.label,
                                    style = MiuixTheme.textStyles.paragraph,
                                    modifier = Modifier.weight(1f),
                                )
                                NumberStepper(
                                    value = values[control.key] ?: control.initial,
                                    onValueChange = { updateControl(control, it) },
                                    step = control.step,
                                    unit = control.unit,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(cardInsetColor(0.05f))
                    .padding(12.dp),
            ) {
                Text(
                    text = "可以直接返回：切到后台、锁屏、甚至关掉 App 都会继续计时，" +
                        "回来时数字按真实流逝的时间算。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            Spacer(Modifier.weight(1f))
        }
    }

    if (showTypePicker) {
        AppDialog(
            title = "换一个类型",
            summary = "已经计的时间不会重置",
            onDismissRequest = { showTypePicker = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                CardioType.entries.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEach { item ->
                            TypeChip(
                                text = item.label,
                                selected = item == type,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    // 换类型后旧参数（坡度/阻力）没有意义，
                                    // 整份换成新类型的默认值，免得列表里显示出错误的组合
                                    val nextSettings = CardioControls.encode(
                                        CardioControls.defaults(item)
                                    )
                                    CardioClock.setType(context, item.key)
                                    CardioClock.setSettings(context, nextSettings)
                                    settings = nextSettings
                                    active = CardioClock.read(context)
                                    showTypePicker = false
                                },
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    if (showFinish && current != null) {
        FinishDialog(
            type = type,
            elapsedMs = elapsedMs,
            onDismiss = {
                // 「继续练」：把刚才为了填表而按下的暂停恢复回去
                if (resumeAfterFinish) CardioClock.resume(context)
                resumeAfterFinish = false
                active = CardioClock.read(context)
                showFinish = false
            },
            onDiscard = {
                // 放弃要二次确认：这是唯一会丢数据的出口
                showFinish = false
                resumeAfterFinish = false
                CardioClock.clear(context)
                active = null
                onSaved()
            },
            onSave = { distanceKm, note ->
                showFinish = false
                resumeAfterFinish = false
                vm.save(
                    type = type,
                    startedAt = current.startedAt,
                    durationMs = elapsedMs,
                    distanceKm = distanceKm,
                    note = note,
                    manual = false,
                    // 没动过参数时 settings 可能是空串，保存后列表也不显示摘要
                    settings = settings,
                ) {
                    // 保存成功才清状态：写库失败时秒表还得能接着走
                    CardioClock.clear(context)
                    onSaved()
                }
            },
        )
    }
}

@Composable
private fun FinishDialog(
    type: CardioType,
    elapsedMs: Long,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
    onSave: (Float, String) -> Unit,
) {
    var distanceKm by remember { mutableStateOf(0f) }
    var note by remember { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }

    AppDialog(
        title = "完成这次${type.label}",
        summary = "时长 ${formatClock(elapsedMs)} · 距离可以现在填，也可以留空",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "距离",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(6.dp))
            NumberStepper(
                value = distanceKm,
                onValueChange = {
                    distanceKm = (Math.round(it * 10f) / 10f).coerceIn(0f, 999f)
                },
                step = 0.5f,
                unit = " km",
            )

            Spacer(Modifier.height(12.dp))
            TextField(
                value = note,
                onValueChange = { if (it.length <= 60) note = it },
                label = "备注（可选）",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )

            Spacer(Modifier.height(18.dp))
            if (confirmDiscard) {
                Text(
                    text = "这次不计入记录，确认丢弃？",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    GlassButton(onClick = { confirmDiscard = false }) {
                        Text("再想想", style = MiuixTheme.textStyles.button)
                    }
                    Spacer(Modifier.width(12.dp))
                    GlassButton(onClick = onDiscard) {
                        Text("丢弃", style = MiuixTheme.textStyles.button)
                    }
                }
            } else {
                // 「放弃这次」单独一行靠左：它和两个主按钮挤在一行时，
                // 窄屏 / 大字体下最后那个「保存」会被压成竖排的两行字
                // （截图里就是这样，还被面板右缘裁掉一半）。
                ActionText(text = "放弃这次", onClick = { confirmDiscard = true })
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GlassButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("继续练", style = MiuixTheme.textStyles.button)
                    }
                    GlassButton(
                        onClick = { onSave(distanceKm, note) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存", style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }
}
