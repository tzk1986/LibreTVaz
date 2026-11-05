package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 全 app 统一动作按钮（用户 2026-11-05：按详情页样式走查统一）——
 * 黑底+白字+圆角长方形 8dp+紧凑内边距不浪费空间；selected=亮底深字（在播/当前页等）。
 * 用户 2026-11-05 追加：按钮大小与文字全 app 统一调小——内容默认 labelMedium 字号
 * （ProvideTextStyle 兜底，调用方显式 style 如翻页 tab 的 labelSmall 仍可覆盖）；
 * 底色由半透明灰改黑（用户 2026-11-05：「按钮底色统一改为黑色，不要用灰色」）；
 * 再调为半透黑 0x99（用户 2026-11-05 参考图核对：TVBox 按钮=半透明底+白字，显黑因背后画面是深色）。
 * 固定深色主题（theme/Theme.kt），白字对比前提成立。
 */
/** 全 app 输入框统一高度＝TmBtn 按钮高度（用户 2026-11-05：所有输入框与按钮同高，
 *  M3 OutlinedTextField 默认 56dp 比按钮(48dp 触控高)一截，各处加 height(TmFieldHeight) 对齐） */
val TmFieldHeight = 48.dp

/** 全 app 弹窗统一宽＝屏宽×0.7（用户 2026-11-05：所有弹出框照快搜同款，横屏按屏宽 70% 居中；
 *  必须配 DialogProperties(usePlatformDefaultWidth=false)，否则平台把宽钳到 ~400dp；
 *  dialog 窗口 gr=CENTER＋系统 insets 左右对称＝物理屏居中） */
@Composable
fun tmDialogW(): androidx.compose.ui.unit.Dp =
    androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp * 0.7f

val tmDialogProps = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)

@Composable
fun TmBtn(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding,
        shape = RoundedCornerShape(8.dp),
        // 用户 2026-11-05：全 app 按钮统一走播放详情页按钮样式（PillButton 同款）——
        // 普通白20%、选中白40%、恒白字、无描边（原半透黑底+选中亮底深字已废）
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0x66FFFFFF) else Color(0x33FFFFFF),
            contentColor = Color.White,
            disabledContainerColor = Color(0x1AFFFFFF),
            disabledContentColor = Color(0x8AFFFFFF),
        ),
    ) { ProvideTextStyle(MaterialTheme.typography.labelMedium) { content() } }
}
