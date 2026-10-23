package com.yfantasyxx.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 休息时长按 15 秒一档 */
const val REST_STEP_SECONDS = 15

/**
 * 休息时长波轮选择器。
 *
 * @param showSaveAsDefault 是否显示"设为该动作默认"选项
 */
@Composable
fun RestSecondsPickerDialog(
    initialSeconds: Int,
    showSaveAsDefault: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (seconds: Int, saveAsDefault: Boolean) -> Unit,
    /** 传入则显示「跟随全局」，用于清除动作的自定义时长 */
    onClear: (() -> Unit)? = null,
) {
    val initialStep = (initialSeconds / REST_STEP_SECONDS).coerceIn(1, 20)
    var step by remember { mutableStateOf(initialStep) }
    var saveAsDefault by remember { mutableStateOf(false) }

    AppDialog(
        title = "休息时长",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            NumberPicker(
                value = step,
                onValueChange = { step = it },
                range = 1..20,
                label = { "${it * REST_STEP_SECONDS} 秒" },
                visibleItemCount = 5,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            )

            if (showSaveAsDefault) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        state = if (saveAsDefault) {
                            androidx.compose.ui.state.ToggleableState.On
                        } else {
                            androidx.compose.ui.state.ToggleableState.Off
                        },
                        onClick = { saveAsDefault = !saveAsDefault },
                    )
                    Text(
                        text = "设为该动作的默认休息时长",
                        style = MiuixTheme.textStyles.paragraph,
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onClear != null) {
                    ActionText(text = "跟随全局", onClick = {
                        onClear()
                        onDismiss()
                    })
                } else {
                    Spacer(Modifier.width(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionText(text = "取消", onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        onConfirm(step * REST_STEP_SECONDS, saveAsDefault)
                    }) {
                        Text("确定", style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }
}
