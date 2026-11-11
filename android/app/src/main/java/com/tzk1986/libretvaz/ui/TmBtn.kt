package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.Modifier
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.ui.theme.TvBtnKind
import com.tzk1986.libretvaz.ui.theme.TvColors
import com.tzk1986.libretvaz.ui.theme.tvBtnContainer

/**
 * 全 app 统一动作按钮（用户 2026-11-05：按详情页样式走查统一）——
 * 焦点=40%红底+白描边，常态=深灰底白字（TVBox button_detail_all selector 实锤）。
 * 用户 2026-11-09 装机像素实锤：M3 Button 的 48dp 最小触控盒把外层描边撑到底色外一圈
 * （环 124px vs 底 73px）——改 Box 自绘：底/描边/点击同节点，描边严丝合缝贴按钮边界；
 * clickable 节点天然可焦（BrowseScreen tabFrs/详情窗先例），遥控走焦不受影响。
 */
/** 全 app 输入框统一高度＝TmBtn 按钮高度（用户 2026-11-05：所有输入框与按钮同高） */
val TmFieldHeight = 48.dp

/** 全 app 弹窗统一宽＝画布逻辑宽×0.7（用户 2026-11-05；须配 usePlatformDefaultWidth=false。
 *  P1-4：基准从原密度 cfg dp 改画布 dp——4K 投影档 cfg=3840dp 而画布 914dp，旧式溢屏 3 倍） */
@Composable
fun tmDialogW(): androidx.compose.ui.unit.Dp = (canvasWdp() * 0.7f).dp

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
    var focused by remember { mutableStateOf(false) }
    val btnSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by btnSrc.collectIsPressedAsState()
    val shape = RoundedCornerShape(TvColors.RadiusDetail)
    val container = if (!enabled) Color(0x1AFFFFFF)
    else if (selected) TvColors.Red40 else tvBtnContainer(focused, pressed, TvBtnKind.Detail)
    Box(
        modifier = modifier
            .onFocusEvent { focused = it.isFocused }
            .background(container, shape)
            .then(if (focused) Modifier.border(TvColors.StrokeW, TvColors.FocusStroke, shape) else Modifier)
            .clickable(interactionSource = btnSrc, enabled = enabled, onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelMedium) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides
                    if (enabled) Color.White else Color(0x8AFFFFFF),
            ) { content() }
        }
    }
}
