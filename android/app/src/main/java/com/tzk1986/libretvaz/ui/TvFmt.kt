package com.tzk1986.libretvaz.ui

/**
 * TVBox 复刻（用户 2026-11-09）全屏控件纯格式化助手——无状态、可单测（TvFmtTest）。
 * 阈值按视频高度取友好清晰度档名；未知/0/负 → 空串（调用方据此隐藏该行）。
 */
fun qualityLabel(hPx: Int): String = when {
    hPx >= 2160 -> "4K"
    hPx >= 1080 -> "超清"
    hPx >= 720 -> "高清"
    hPx >= 480 -> "标清"
    hPx >= 360 -> "流畅"
    hPx > 0 -> "低清"
    else -> ""
}

/** 倍速胶囊文案：两位小数后去尾零、恒留一位小数（1→"1.0"、1.25→"1.25"、0.5→"0.5"） */
fun speedLabel(x: Float): String = "%.2f".format(x).trimEnd('0').let { if (it.endsWith(".")) it + "0" else it }

/** 跳过时长钮文案 MM:SS（零填充，如 60→"01:00"） */
fun fmtSkip(sec: Long): String = "%02d:%02d".format(sec / 60, sec % 60)

/** 右上角时钟 HH:MM（24h 零填充） */
fun fmtClock(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

/** 右下角大时钟 HH:MM:SS（用户 2026-11-10 实拍图：当前时间带秒，24h 零填充） */
fun fmtClockHms(hour: Int, minute: Int, second: Int): String = "%02d:%02d:%02d".format(hour, minute, second)

/** 详情页倒序展示：返回 (原始下标 to 元素)，reversed 时展示序反转、下标仍指真集 */
fun <T> epDisplayOrder(list: List<T>, reversed: Boolean): List<Pair<Int, T>> =
    list.mapIndexed { i, v -> i to v }.let { if (reversed) it.reversed() else it }
