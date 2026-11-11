package com.tzk1986.libretvaz.ui

import com.tzk1986.libretvaz.Screen

/**
 * A13（审计 2026-11-11）：扫码远程关键词派发纯函数。
 * 播放态（详情＝播放页 Detail / 轻量播放页 Player）不跳页打断观演，词缓存待进搜索页自然消费；
 * 池归属沿 backTo 递归——专区链（Zone/zone=true 的搜索/收藏/历史/详情）落专区池，其余点播池。
 */
fun remoteJump(s: Screen): Boolean = s !is Screen.Detail && s !is Screen.Player

fun remoteZone(s: Screen): Boolean = when (s) {
    is Screen.Search -> s.zone
    is Screen.Collect -> s.zone
    is Screen.Keep -> s.zone
    is Screen.Hist -> s.zone
    is Screen.Zone -> true
    is Screen.Detail -> remoteZone(s.backTo)
    is Screen.Player -> remoteZone(s.backTo)
    else -> false
}
