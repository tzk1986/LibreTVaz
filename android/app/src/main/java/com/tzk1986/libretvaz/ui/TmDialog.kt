package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/**
 * C27（审计 2026-11-11）：全 app 弹窗统一壳——内部强制套 tmDialogW（画布 0.7 宽，P1-4 投影画布基准）
 * + tmDialogProps（关平台默认宽）。此前三种写法并存：裸 AlertDialog（平台默认宽，投影档窄窗）、
 * 只传 properties 不套宽（超平台宽溢屏）、套宽未关平台宽（被平台上限压缩）。一律走本壳收敛。
 */
@Composable
fun TmDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = tmDialogProps,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier.width(tmDialogW()),
        properties = properties,
        icon = icon,
        title = title,
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    )
}
