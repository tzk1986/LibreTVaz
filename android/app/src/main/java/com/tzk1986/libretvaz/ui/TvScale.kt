package com.tzk1986.libretvaz.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TVBox 复刻（用户 2026-11-09）mm 虚拟屏换算。蓝本 dimens 全用 mm 单位，
 * 420dpi 2400×1080 实测 1mm≈短边/576（1.875px=0.714dp），据此任意屏等比还原。
 */
fun tvDp(mm: Float, minSideDp: Float): Float = minSideDp * mm / 576f

/** 投影适配（用户 2026-11-11）：4K 投影/低密度电视短边 dp 可达 1080+，纯等比会失控
 *  （N1S 实装机按钮超大）；封顶 420dp=手机横屏实测档，大屏复刻手机比例、6 钮全显 */
fun tvMinSide(minSideDp: Float): Float = minSideDp.coerceAtMost(420f)

@Composable
fun tvu(mm: Int): Dp {
    val c = LocalConfiguration.current
    return tvDp(mm.toFloat(), tvMinSide(minOf(c.screenWidthDp.toFloat(), c.screenHeightDp.toFloat()))).dp
}

/** 字号版：蓝本 ts_N 同为 mm 物理字号→走 tvDp 直通，不吃系统 fontScale（与蓝本一致） */
@Composable
fun tvSp(mm: Int): TextUnit = tvu(mm).value.sp
