package com.yfantasyxx.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfantasyxx.ui.components.ActionText
import com.yfantasyxx.ui.components.AppDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 训练页的几个对话框与时间格式化 */
@Composable
internal fun FinishDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var note by remember { mutableStateOf("") }
    AppDialog(
        title = "完成训练",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            top.yukonga.miuix.kmp.basic.TextField(
                value = name,
                onValueChange = { name = it },
                label = "训练名称（可选）",
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            top.yukonga.miuix.kmp.basic.TextField(
                value = note,
                onValueChange = { note = it },
                label = "备注（可选）",
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(text = "取消", onClick = onDismiss)
                Spacer(Modifier.width(12.dp))
                Button(onClick = { onConfirm(name, note) }) {
                    Text("完成", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

/** 动作选择对话框（搜索 + 分类） */
@Composable
internal fun ExercisePickerDialog(
    container: com.yfantasyxx.AppContainer,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onRequestCreate: () -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val exercises = remember(keyword, category) {
        container.workoutRepository.observeExercises(keyword, category)
    }.collectAsState(initial = emptyList())

    AppDialog(
        title = "选择动作",
        fullWidth = true,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            top.yukonga.miuix.kmp.basic.TextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = "搜索动作",
                singleLine = true,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(
                    text = "+ 新建自定义动作",
                    onClick = onRequestCreate,
                )
            }
            Spacer(Modifier.height(2.dp))
            LazyColumn(modifier = Modifier.height(320.dp)) {
                items(exercises.value, key = { it.id }) { exercise ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        onClick = { onPick(exercise.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(exercise.name, style = MiuixTheme.textStyles.main)
                                Text(
                                    "${exercise.category} · ${exercise.equipment}",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}
