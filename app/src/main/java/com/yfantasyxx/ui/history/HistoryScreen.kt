package com.yfantasyxx.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yfantasyxx.AppContainer
import com.yfantasyxx.data.WorkoutRepository
import com.yfantasyxx.data.db.SessionSummary
import com.yfantasyxx.ui.components.EmptyState
import com.yfantasyxx.ui.components.formatVolume
import com.yfantasyxx.ui.rememberAppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryViewModel(repo: WorkoutRepository) : ViewModel() {
    val sessions = repo.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<SessionSummary>())

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HistoryViewModel(container.workoutRepository) }
        }
    }
}

@Composable
fun HistoryScreen(onOpenSession: (Long) -> Unit) {
    val container = rememberAppContainer()
    val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container))
    val sessions by vm.sessions.collectAsState()

    Scaffold(
        topBar = { SmallTopAppBar(title = "训练历史") },
    ) { padding ->
        if (sessions.isEmpty()) {
            EmptyState(text = "暂无训练记录")
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp + com.yfantasyxx.ui.LocalBottomBarSpace.current
                ),
            ) {
                items(sessions, key = { it.id }) { session ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        onClick = { onOpenSession(session.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.name,
                                    style = MiuixTheme.textStyles.main,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${session.exerciseCount} 动作 · ${session.setCount} 组 · " +
                                        "容量 ${formatVolume(session.totalVolume)} kg",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Text(
                                text = SimpleDateFormat("MM/dd", Locale.CHINA)
                                    .format(Date(session.startTime)),
                                style = MiuixTheme.textStyles.paragraph,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
    }
}
