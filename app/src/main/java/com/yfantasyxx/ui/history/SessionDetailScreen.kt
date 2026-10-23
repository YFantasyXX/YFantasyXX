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
import top.yukonga.miuix.kmp.overlay.OverlayDialog
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
        OverlayDialog(
            show = true,
            title = "删除这条训练记录？",
            summary = "删除后无法恢复",
            onDismissRequest = { showDelete = false },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
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
