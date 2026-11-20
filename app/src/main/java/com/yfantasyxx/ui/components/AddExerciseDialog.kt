package com.yfantasyxx.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfantasyxx.data.db.ExerciseCategory
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 新建自定义动作对话框：分类与器械用胶囊选择，只需输入名称和部位。
 * 动作库、模板编辑、训练页共用。
 */
@Composable
fun AddExerciseDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, category: String, equipment: String, muscle: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(ExerciseCategory.all.first()) }
    var equipment by remember { mutableStateOf("") }
    var muscle by remember { mutableStateOf("") }

    AppDialog(
        title = "新建自定义动作",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = "动作名称",
                singleLine = true,
            )

            Spacer(Modifier.height(10.dp))
            Text(
                text = "分类",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ExerciseCategory.all) { item ->
                    FilterChip(
                        label = item,
                        selected = category == item,
                        onClick = { category = item },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            TextField(
                value = equipment,
                onValueChange = { equipment = it },
                label = "器械（自己填，如：杠铃 / 哑铃 / 龙门架）",
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))
            TextField(
                value = muscle,
                onValueChange = { muscle = it },
                label = "主要部位（可选）",
                singleLine = true,
            )

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText(text = "取消", onClick = onDismiss)
                Spacer(Modifier.height(0.dp))
                ActionText(
                    text = "添加",
                    onClick = {
                        onConfirm(
                            name.trim(),
                            category,
                            equipment.trim().ifBlank { "其他" },
                            muscle.trim(),
                        )
                    },
                    enabled = name.isNotBlank(),
                )
            }
        }
    }
}
