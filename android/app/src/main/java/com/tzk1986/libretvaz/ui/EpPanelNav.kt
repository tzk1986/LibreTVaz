package com.tzk1986.libretvaz.ui

/**
 * C23（审计 2026-11-12）：全屏选集面板遥控导航状态机（纯函数，单测覆盖）。
 * 双模式：grid=网格内走焦（2 列 左右±1/上下±2，原 11-04 遥控语义不变）；
 * rail=焦点上移到面板顶部线路行（网格顶行再按 ↑ 进入）。
 * rail 模式：←→（↑亦左）=移线路选、OK=换线（面板保持）、↓=回网格、BACK=收面板。
 * 修复病根：旧版四键全吞仅做网格移位，板内线路行永不可达（审计 23.）。
 */
enum class EpNav { Pass, MoveEp, EnterRail, ExitRail, MoveRail, RailOk, PlayEp, Close }

/** 导航后新状态：nav=动作，ep/rail/railOn=状态机更新值（调用方回写） */
data class EpStep(val nav: EpNav, val ep: Int, val rail: Int, val railOn: Boolean)

/** @param key 0← 1→ 2↑ 3↓ 4OK 5BACK/ESC（其余由调用方传 -1=Pass）
 *  @param ep 网格当前高亮下标；nEp 该线路总集数（0=空网格仍可换线）
 *  @param rail 线路行选中下标；nRail 线路总数；railOn 焦点是否在线路行 */
fun epPanelKey(
    key: Int,
    ep: Int,
    nEp: Int,
    rail: Int,
    nRail: Int,
    railOn: Boolean,
): EpStep {
    fun s(nav: EpNav, e: Int = ep, r: Int = rail, ro: Boolean = railOn) = EpStep(nav, e, r, ro)
    if (key !in 0..5) return s(EpNav.Pass)
    val maxEp = (nEp - 1).coerceAtLeast(0)
    return if (railOn) when (key) {
        0, 2 -> s(EpNav.MoveRail, r = (rail - 1).coerceAtLeast(0))
        1 -> s(EpNav.MoveRail, r = (rail + 1).coerceAtMost((nRail - 1).coerceAtLeast(0)))
        3 -> s(EpNav.ExitRail, ro = false)
        4 -> s(EpNav.RailOk)
        else -> s(EpNav.Close, ro = false)
    } else when (key) {
        0 -> s(EpNav.MoveEp, e = (ep - 1).coerceAtLeast(0))
        1 -> s(EpNav.MoveEp, e = (ep + 1).coerceAtMost(maxEp))
        2 -> if (ep <= 1 && nRail > 0) s(EpNav.EnterRail, ro = true)
             else s(EpNav.MoveEp, e = (ep - 2).coerceAtLeast(0))
        3 -> s(EpNav.MoveEp, e = (ep + 2).coerceAtMost(maxEp))
        4 -> s(EpNav.PlayEp)
        else -> s(EpNav.Close, ro = false)
    }
}
