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

/** 投影画布映射（用户 2026-11-11 定稿）：大屏整幅 UI 按「手机横屏画布宽 914dp」等比
 *  放大——占屏比例=手机（6 钮全显），物理尺寸随屏增大远看可读；
 *  coerceAtLeast=手机/竖屏绝不缩小 */
fun tvCanvasScale(widthPx: Int, density: Float): Float =
    (widthPx / 914f).coerceAtLeast(density)

/** 画布逻辑 dp（审计 2026-11-11 P1-4）：px ÷ 生效密度。投影档 LocalConfiguration 的 dp 仍按
 *  原密度算（3840dp），与画布（914dp）错 4 倍——弹窗宽/高与断点一律以此为基准 */
fun canvasDp(px: Int, scale: Float): Float = px / scale

/** MainActivity 在 LocalDensity 改写处同步下发画布宽高 dp；0=未下发，回退原配置 dp（手机档两者相等） */
val LocalCanvasWdp = androidx.compose.runtime.compositionLocalOf { 0f }
val LocalCanvasHdp = androidx.compose.runtime.compositionLocalOf { 0f }

@Composable
fun canvasWdp(): Float =
    LocalCanvasWdp.current.takeIf { it > 0 } ?: LocalConfiguration.current.screenWidthDp.toFloat()

@Composable
fun canvasHdp(): Float =
    LocalCanvasHdp.current.takeIf { it > 0 } ?: LocalConfiguration.current.screenHeightDp.toFloat()

@Composable
fun tvu(mm: Int): Dp {
    val c = LocalConfiguration.current
    return tvDp(mm.toFloat(), tvMinSide(minOf(c.screenWidthDp.toFloat(), c.screenHeightDp.toFloat()))).dp
}

/** 字号版：蓝本 ts_N 同为 mm 物理字号→走 tvDp 直通取数值。
 *  C31（审计 2026-11-11）更正：sp 换算 px 仍乘系统 fontScale——旧注释「不吃」失实；
 *  兜底在 MainActivity 根（Density(scale, fontScale≤1.15)），大字号档字体最多 +15%，
 *  EpCell(tvu50)/FsAct(tvu40) 固定高对行高余量 ≥1.5x，裁不掉；勿在 tvSp 内再乘系数（双重放大） */
@Composable
fun tvSp(mm: Int): TextUnit = tvu(mm).value.sp
