package com.tzk1986.libretvaz.ui

/**
 * P1-6（审计 2026-11-11）：字幕轨轮切纯逻辑——与 media3 类型零耦合，供 DetailScreen.toggleSubtitle 消费。
 * 档位序：-1=关闭（文本轨禁用），0..count-1=第 n 条轨。
 */
fun subNext(cur: Int, count: Int): Int = if (count <= 0) -1 else if (cur >= count - 1) -1 else cur + 1

/** toast 标签：轨自带 label 优先，其次 ISO 语言码，皆空退「轨 n」（1 起） */
fun subLabel(label: String?, language: String?, idx: Int): String =
    label?.takeIf { it.isNotBlank() } ?: language?.takeIf { it.isNotBlank() } ?: "轨 ${idx + 1}"
