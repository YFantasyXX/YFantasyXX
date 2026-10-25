package com.yfantasyxx.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.db.SessionExerciseWithSets
import com.yfantasyxx.data.db.WorkoutSession
import com.yfantasyxx.ui.components.EmptyState
import com.yfantasyxx.ui.components.InfoRow
import com.yfantasyxx.ui.components.SectionHeader
import com.yfantasyxx.ui.components.formatVolume
import com.yfantasyxx.ui.rememberAppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import com.yfantasyxx.ui.components.AppDialog
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.yfantasyxx.ui.components.ActionText
import androidx.compose.foundation.lazy.items

class SessionDetailViewModel(private val repo: WorkoutRepository) : ViewModel() {
    private val _details = MutableStateFlow<List<SessionExerciseWithSets>>(emptyList())
    val details: StateFlow<List<SessionExerciseWithSets>> = _details.asStateFlow()

    private val _session = MutableStateFlow<WorkoutSession?>(null)
    val session: StateFlow<WorkoutSession?> = _session.asStateFlow()

    fun load(id: Long) {
        viewModelScope.launch {
            _details.value = repo.getSessionDetails(id) ?: emptyList()
            _session.value = repo.getSession(id)
        }
    }

    fun deleteSession(id: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteSession(id)
            onDone()
        }
    }

    /** 各档的实际换算曲线，分享卡片挑亮点时用 */
    suspend fun curves(): Map<Int, com.yfantasyxx.data.PatternCalibration> = repo.calibration()

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SessionDetailViewModel(container.workoutRepository) }
        }
    }
}

@Composable
fun SessionDetailScreen(sessionId: Long, onBack: () -> Unit) {
    val container = rememberAppContainer()
    val vm: SessionDetailViewModel = viewModel(factory = SessionDetailViewModel.factory(container))
    val details by vm.details.collectAsState()
    val session by vm.session.collectAsState()
    var showDelete by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) { vm.load(sessionId) }

    val totalVolume = details.sumOf { item ->
        item.sets.filter { it.completed && !it.isWarmup }
            .sumOf { (it.weightKg * it.reps).toDouble() }
    }.toFloat()
    val totalSets = details.sumOf { item -> item.sets.count { it.completed } }

    // 分享卡片：画图 + PNG 压缩放到 IO 线程，点一下不会卡住界面
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun shareCard() {
        val s = session ?: return
        scope.launch {
            // 曲线要先拿：亮点按 e1RM 挑，群体表和个人校准可能选出不同的组
            val curves = runCatching { vm.curves() }.getOrDefault(emptyMap())
            val data = com.yfantasyxx.util.ShareCard.Data(
                title = s.name.ifBlank { "训练" },
                date = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.CHINA).format(Date(s.startTime)),
                durationMinutes = (((s.endTime ?: System.currentTimeMillis()) -
                    (s.activeStartedAt ?: s.startTime)) / 60000).toInt(),
                exerciseCount = details.count { item -> item.sets.any { it.completed } },
                setCount = totalSets,
                volumeKg = totalVolume,
                // 亮点按每个动作最强的一组取，最多三条
                highlights = details
                    .sortedByDescending { item ->
                        item.sets.filter { it.completed }
                            .sumOf { (it.weightKg * it.reps).toDouble() }
                    }
                    .take(3)
                    .mapNotNull { item ->
                        val pattern = com.yfantasyxx.data.LiftPattern
                            .fromValue(item.exercise.pattern)
                        val curve = curves[pattern.value] ?: pattern
                        val best = item.sets
                            .filter { it.completed && !it.isWarmup }
                            .maxByOrNull {
                                com.yfantasyxx.data.OneRepMax
                                    .estimate(it.weightKg, it.reps, curve) ?: -1f
                            } ?: return@mapNotNull null
                        "${item.exercise.name} ${best.weightKg.toInt()} kg × ${best.reps}"
                    },
            )
            runCatching {
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.yfantasyxx.util.ShareCard.write(context, data)
                }
                com.yfantasyxx.util.ShareCard.share(context, file)
            }
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = session?.name?.ifEmpty { "训练详情" } ?: "训练详情",
                subtitle = session?.let {
                    SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(Date(it.startTime))
                } ?: "",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { shareCard() }) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "分享",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = MiuixTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (details.isEmpty()) {
            EmptyState(text = "记录不存在或已被删除", action = {
                ActionText(text = "返回", onClick = onBack)
            })
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            InfoRow("动作数", "${details.size} 个")
                            InfoRow("完成组数", "$totalSets 组")
                            InfoRow("总容量", "${formatVolume(totalVolume)} kg")
                            session?.note?.takeIf { it.isNotBlank() }?.let {
                                InfoRow("备注", it)
                            }
                        }
                    }
                }
                item { SectionHeader("动作明细") }
                items(details, key = { it.sessionExercise.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(item.exercise.name, style = MiuixTheme.textStyles.main)
                            // 模板设定的目标次数，如「目标 8-12」
                            item.sessionExercise.plannedReps
                                ?.takeIf { it.isNotBlank() }
                                ?.let { reps ->
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "目标 $reps",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            Spacer(Modifier.height(8.dp))
                            item.sets.sortedBy { it.setNumber }.forEach { set ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "第 ${set.setNumber} 组" + if (set.isWarmup) "（热身）" else "",
                                        style = MiuixTheme.textStyles.paragraph,
                                        color = if (set.completed) {
                                            MiuixTheme.colorScheme.onSurface
                                        } else {
                                            MiuixTheme.colorScheme.disabledOnSurface
                                        },
                                    )
                                    Text(
                                        text = "${set.weightKg.toInt()} kg × ${set.reps} 次" +
                                            (set.rpe?.let { " · RPE ${it.toInt()}" } ?: ""),
                                        style = MiuixTheme.textStyles.paragraph,
                                        color = if (set.completed) {
                                            MiuixTheme.colorScheme.primary
                                        } else {
                                            MiuixTheme.colorScheme.disabledOnSurface
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDelete) {
        AppDialog(
            title = "删除这条训练记录？",
            summary = "删除后无法恢复",
            onDismissRequest = { showDelete = false },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(text = "取消", onClick = { showDelete = false })
                ActionText(text = "删除", onClick = {
                    showDelete = false
                    vm.deleteSession(sessionId) { onBack() }
                }, color = MiuixTheme.colorScheme.error)
            }
        }
    }
}
