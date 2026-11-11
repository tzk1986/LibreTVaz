package com.tzk1986.libretvaz.ui

/**
 * A3（审计 2026-11-11）：遥控媒体键动作映射纯逻辑——与 android.view.KeyEvent 常量零耦合（JVM 可测）。
 * live=true 直播态：流无时长概念，±15s 快进快退不消费（放行系统）。STOP 不映射（留给系统行为）。
 */
enum class Mk { Toggle, SeekFwd, SeekBack, NextItem, PrevItem }

fun mediaKeyPlan(key: Int, live: Boolean): Mk? = when (key) {
    85, 126, 127 -> Mk.Toggle                   // PLAY_PAUSE / PLAY / PAUSE
    90, 272 -> if (live) null else Mk.SeekFwd   // FFWD / SKIP_FORWARD(13+)
    89, 273 -> if (live) null else Mk.SeekBack  // REWIND / SKIP_BACKWARD(13+)
    87, 166, 274 -> Mk.NextItem                 // NEXT / CH_UP / STEP_FORWARD
    88, 167, 275 -> Mk.PrevItem                 // PREVIOUS / CH_DOWN / STEP_BACKWARD
    else -> null                                // 86 STOP / 187 APP_SWITCH 等一律放行
}

/** 页级媒体键派发口：MainActivity.onKeyDown 转发；页面 DisposableEffect 注册/注销（同时仅一屏渲染=互斥成立） */
object MediaKeys {
    var handler: ((Int) -> Boolean)? = null
}
