package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.ui.theme.TvBtnKind
import com.tzk1986.libretvaz.ui.theme.TvColors
import com.tzk1986.libretvaz.ui.theme.tvBtnContainer

/**
 * 全 app 统一筛选 chip：焦点=40%红底+白描边、选中=40%红、常态 60%黑（TVBox button_dialog_main）。
 * 用户 2026-11-09 装机像素实锤：FilterChip 内建 border 画在 48dp 最小触控盒、底色画在
 * 内容尺寸——环比按钮大一圈。改 Box 同节点自绘底/描边/点击（与 TmBtn 同方案），
 * clickable 节点天然可焦（tabFrs/详情窗先例），PanelChip 的 fr 抢焦照常工作。
 */
@Composable
fun TmChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(50)
    val bg = if (dim) Color(0x1AFFFFFF)
    else if (selected) TvColors.Red40 else tvBtnContainer(focused, pressed, TvBtnKind.Chip)
    Box(
        modifier = modifier
            .onFocusEvent { focused = it.isFocused }
            .background(bg, shape)
            .then(if (focused) Modifier.border(TvColors.StrokeW, TvColors.FocusStroke, shape) else Modifier)
            .clickable(interactionSource = src, enabled = !dim, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelMedium) {
            CompositionLocalProvider(
                LocalContentColor provides if (dim) Color(0x8AFFFFFF) else Color.White,
            ) { Text(label, maxLines = 1) }
        }
    }
}

/** 底部筛选面板 chip＝TmChip 同款（保留原名供 BrowseScreen 调用位复用）；
 *  fr＝用户 2026-11-05：面板打开时首个筛选项挂抢焦请求，遥控器方向键直达筛选项 */
@Composable
internal fun PanelChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    dim: Boolean = false,
    fr: FocusRequester? = null,
) {
    val m = if (fr != null) Modifier.focusRequester(fr) else Modifier
    TmChip(selected = selected, onClick = onClick, label = label, dim = dim, modifier = m.padding(0.dp))
}
