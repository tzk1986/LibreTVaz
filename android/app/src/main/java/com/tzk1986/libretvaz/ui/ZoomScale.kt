package com.tzk1986.libretvaz.ui

import androidx.media3.ui.AspectRatioFrameLayout

/**
 * TVBox 对标 C4（2026-11-10）：画面缩放全局档（设置 zoom_pref）→media3 resizeMode 映射。
 * 0=比例适配 / 1=满屏裁切 / 2=拉伸充满；越界防御性回落适配。
 * A10（审计 2026-11-11）：播放中比例钮改三档环（FIT→ZOOM→FILL，原二档 FILL 不可达），
 * 档值与 zoom_pref 同域——初始档=zoomResizeMode(zoom_pref)，翻档=zoomNext。
 */
fun zoomResizeMode(pref: Int): Int = when (pref) {
    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    // 方案原写 STRETCH，media3 无此常量（Exo2 旧名），拉伸充满即 FILL（编译实锤）
    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

/** A10：比例钮三档循环 0→1→2→0（入参限 0..2，DetailScreen 以 coerceIn 护初始越界档） */
fun zoomNext(stage: Int): Int = (stage + 1) % 3

/** 设置行三档文案（下标即 zoom_pref 值） */
val ZOOM_LABELS = listOf("比例适配", "满屏裁切", "拉伸充满")
