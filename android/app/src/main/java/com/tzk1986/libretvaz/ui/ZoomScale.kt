package com.tzk1986.libretvaz.ui

import androidx.media3.ui.AspectRatioFrameLayout

/**
 * TVBox 对标 C4（2026-11-10）：画面缩放全局档（设置 zoom_pref）→media3 resizeMode 映射。
 * 0=比例适配 / 1=满屏裁切 / 2=拉伸充满；越界防御性回落适配。
 * 比例钮仍走 FIT↔ZOOM 二档循环（TVBox 同：钮只翻两态），档 2 仅作进入时的初始呈现。
 */
fun zoomResizeMode(pref: Int): Int = when (pref) {
    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    // 方案原写 STRETCH，media3 无此常量（Exo2 旧名），拉伸充满即 FILL（编译实锤）
    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

fun zoomModeInitial(pref: Int): Boolean = pref == 1

/** 设置行三档文案（下标即 zoom_pref 值） */
val ZOOM_LABELS = listOf("比例适配", "满屏裁切", "拉伸充满")
