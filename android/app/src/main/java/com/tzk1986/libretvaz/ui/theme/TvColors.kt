package com.tzk1986.libretvaz.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * TVBox 复刻调色板（用户 2026-11-09「完全复刻 TVBox 样式，全 app 换红主题」）。
 * 值全部来自 craft-anything 逆向取证（temp/tvbox_replicate_spec.md）：
 * color_theme=#F80000、color_theme_40=#66F80000、详情钮常态 #803D3D3D、chip #99000000、
 * 进度 track=#66FFFFFF。组件一律引本对象常量、不依赖 MaterialTheme.primary，便于单行回退。
 */
object TvColors {
    /** 主题强调红：选中 tab / 进度填充 / 描边强调 / 播放态图标 */
    val Red = Color(0xFFF80000)

    /** 焦点钮填充 = 40% 红（TVBox state_focused solid）；亦作 ripple */
    val Red40 = Color(0x66F80000)

    /** 详情页动作钮常态底 = 50% 深灰（button_detail_all state_focused=false） */
    val DetailBtn = Color(0x803D3D3D)

    /** 源/线路 chip 常态底 = 60% 黑（button_dialog_main） */
    val ChipBg = Color(0x99000000)

    /** 进度条轨道底 = 40% 白 */
    val Track = Color(0x66FFFFFF)

    /** 进度条次级（缓冲）底 = 同深灰 */
    val Buffer = Color(0x803D3D3D)

    /** 焦点白描边（TVBox 2mm 实锤≈1.4dp；原 4dp 为 Phase-2 近似，按解码值收窄） */
    val FocusStroke = Color.White

    /** 集数 cell 常态底 = 45% 深灰（shape_source_series_focus state_focused=false #733d3d3d） */
    val SeriesBg = Color(0x733D3D3D)

    /** 半透黑浮层底（顶/底栏、胶囊共用近似） */
    val Overlay = Color(0xCC000000)

    /** C34（审计 2026-11-11 #34）收敛 token ↓ */

    /** 白 20% 幽灵底（FuncCard/PillButton 2026-11-05 终定；纯黑底上黑半透不可见所以白） */
    val GhostBg = Color(0x33FFFFFF)

    /** 白 40% 幽灵亮档（YearSort 激活序 / FsAct 关闭态 / 快搜未测点） */
    val GhostBgStrong = Color(0x66FFFFFF)

    /** 测速状态色（web sp-ok/sp-mid/sp-bad/sp-dim 语义，三套散写色板合一；Player/Probe/FindSource 共用） */
    val SpeedFast = Color(0xFF66BB6A)
    val SpeedMid = Color(0xFFFFC107)
    val SpeedSlow = Color(0xFFEF5350)
    val SpeedDim = Color(0xFF9E9E9E)

    /** 选中线路旗标黄字（rail 在播线，TVBox flag 高亮语义） */
    val Selected = Color(0xFFFFEB3B)

    /** 2mm 白描边宽度（1mm=短边/576，420dpi 下 2mm≈1.4dp） */
    val StrokeW = 1.5.dp

    /** 圆角：详情动作钮 vs_20 / 全屏钮 vs_30 / chip vs_5 */
    val RadiusDetail = 8.dp
    val RadiusFs = 12.dp
    val RadiusChip = 4.dp
}

/** 按钮类型（决定常态底，取证自 selector state_focused=false 分支） */
enum class TvBtnKind { Detail, Fs, Chip, Flag, Series }

/**
 * TVBox 底色映射（用户 2026-11-09 精准复刻，selector XML 实锤）：
 * 底色只由 焦点/按下 决定——focused 或 pressed→40%红 Red40（focused 另配白描边由调用方加）；
 * 「选中」不改底色（源/分组 chip 选中=红下划线、当前集=红字，见 tvEpTextColor）。
 * disabled 态不走此函数，调用方自留 disabled 配色。
 */
fun tvBtnContainer(focused: Boolean, pressed: Boolean, kind: TvBtnKind): Color =
    if (focused || pressed) TvColors.Red40 else when (kind) {
        TvBtnKind.Detail, TvBtnKind.Flag -> TvColors.DetailBtn
        TvBtnKind.Series -> TvColors.SeriesBg
        TvBtnKind.Fs -> Color.Transparent
        TvBtnKind.Chip -> TvColors.ChipBg
    }

/** 当前播放集文字=主题红（EpisodeAdapter selected 分支），其余白 */
fun tvEpTextColor(current: Boolean): Color = if (current) TvColors.Red else Color.White
