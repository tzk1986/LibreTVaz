package com.tzk1986.libretvaz.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 全 app 统一筛选 chip（用户 2026-11-05「按钮底色统一改为黑色，不要用灰色」）：
 * 黑半透底+白字 / 选中亮底深字，与 TmBtn 同语义配色；文字 labelMedium 兜底同 TmBtn 缩小档。
 * 半透黑既满足黑底统一，又保留底部面板压在海报墙上的透出感（PanelChip 同源需求）。
 */
@Composable
fun TmChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = !dim,
        modifier = modifier,
        leadingIcon = {},
        label = { ProvideTextStyle(MaterialTheme.typography.labelMedium) { Text(label, maxLines = 1) } },
        // 用户 2026-11-05：全 app 统一走播放详情页按钮样式（PillButton 同款）——
        // 普通白20%、选中白40%、恒白字、无描边（原半透黑底+选中亮底深字已废）
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color(0x33FFFFFF),
            selectedContainerColor = Color(0x66FFFFFF),
            labelColor = Color.White,
            selectedLabelColor = Color.White,
            disabledContainerColor = Color(0x1AFFFFFF),
            disabledLabelColor = Color(0x8AFFFFFF),
        ),
        border = BorderStroke(0.dp, Color.Transparent),
    )
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
