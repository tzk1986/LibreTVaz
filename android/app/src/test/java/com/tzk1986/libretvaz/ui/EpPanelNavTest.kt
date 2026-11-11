package com.tzk1986.libretvaz.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** C23 选集面板状态机单测：key 0← 1→ 2↑ 3↓ 4OK 5BACK */
class EpPanelNavTest {

    // ---- 网格模式（railOn=false，原语义保留）----
    @Test fun gridLeftMovesOne() {
        val s = epPanelKey(0, ep = 5, nEp = 10, rail = 0, nRail = 3, railOn = false)
        assertEquals(EpNav.MoveEp, s.nav); assertEquals(4, s.ep); assertEquals(false, s.railOn)
    }

    @Test fun gridLeftClampsAtZero() {
        val s = epPanelKey(0, ep = 0, nEp = 10, rail = 0, nRail = 3, railOn = false)
        assertEquals(EpNav.MoveEp, s.nav); assertEquals(0, s.ep)
    }

    @Test fun gridRightMovesOne() {
        val s = epPanelKey(1, ep = 1, nEp = 10, rail = 0, nRail = 3, railOn = false)
        assertEquals(EpNav.MoveEp, s.nav); assertEquals(2, s.ep)
    }

    @Test fun gridRightClampsAtEnd() {
        val s = epPanelKey(1, ep = 9, nEp = 10, rail = 0, nRail = 3, railOn = false)
        assertEquals(EpNav.MoveEp, s.nav); assertEquals(9, s.ep)
    }

    @Test fun gridDownTwoRows() {
        val s = epPanelKey(3, ep = 7, nEp = 10, rail = 0, nRail = 3, railOn = false)
        assertEquals(EpNav.MoveEp, s.nav); assertEquals(9, s.ep)
    }

    @Test fun gridUpFromSecondRowMovesTwo() {
        val s = epPanelKey(2, ep = 2, nEp = 10, rail = 1, nRail = 3, railOn = false)
        assertEquals(EpNav.MoveEp, s.nav); assertEquals(0, s.ep); assertEquals(false, s.railOn)
    }

    // ---- C23 核心：网格顶行（下标 0/1）再按 ↑ = 焦点上移线路行 ----
    @Test fun gridUpFromTopRowEntersRail() {
        val s = epPanelKey(2, ep = 1, nEp = 10, rail = 1, nRail = 3, railOn = false)
        assertEquals(EpNav.EnterRail, s.nav)
        assertEquals(true, s.railOn); assertEquals(1, s.rail)
    }

    @Test fun gridUpEmptyGridStillEntersRail() {
        val s = epPanelKey(2, ep = 0, nEp = 0, rail = 0, nRail = 2, railOn = false)
        assertEquals(EpNav.EnterRail, s.nav); assertEquals(true, s.railOn)
    }

    @Test fun gridUpNoRailStaysInGrid() {
        val s = epPanelKey(2, ep = 0, nEp = 10, rail = 0, nRail = 0, railOn = false)
        assertEquals(EpNav.MoveEp, s.nav); assertEquals(false, s.railOn)
    }

    @Test fun gridOkPlays() {
        val s = epPanelKey(4, ep = 3, nEp = 10, rail = 0, nRail = 3, railOn = false)
        assertEquals(EpNav.PlayEp, s.nav)
    }

    @Test fun gridBackClosesAndResetsRail() {
        val s = epPanelKey(5, ep = 3, nEp = 10, rail = 2, nRail = 3, railOn = false)
        assertEquals(EpNav.Close, s.nav); assertEquals(false, s.railOn)
    }

    @Test fun unknownKeyPasses() {
        val s = epPanelKey(-1, ep = 0, nEp = 10, rail = 0, nRail = 3, railOn = false)
        assertEquals(EpNav.Pass, s.nav)
        val r = epPanelKey(9, ep = 0, nEp = 10, rail = 0, nRail = 3, railOn = true)
        assertEquals(EpNav.Pass, r.nav); assertEquals(true, r.railOn)
    }

    // ---- 线路行模式（railOn=true）----
    @Test fun railLeftMovesClampZero() {
        val s = epPanelKey(0, ep = 2, nEp = 10, rail = 0, nRail = 3, railOn = true)
        assertEquals(EpNav.MoveRail, s.nav); assertEquals(0, s.rail); assertEquals(true, s.railOn)
    }

    @Test fun railRightMovesAndClampsLast() {
        val a = epPanelKey(1, ep = 2, nEp = 10, rail = 0, nRail = 3, railOn = true)
        assertEquals(EpNav.MoveRail, a.nav); assertEquals(1, a.rail)
        val b = epPanelKey(1, ep = 2, nEp = 10, rail = 2, nRail = 3, railOn = true)
        assertEquals(EpNav.MoveRail, b.nav); assertEquals(2, b.rail)
    }

    @Test fun railUpAlsoMovesLeft() {
        val s = epPanelKey(2, ep = 2, nEp = 10, rail = 1, nRail = 3, railOn = true)
        assertEquals(EpNav.MoveRail, s.nav); assertEquals(0, s.rail); assertEquals(true, s.railOn)
    }

    @Test fun railDownExitsToGrid() {
        val s = epPanelKey(3, ep = 2, nEp = 10, rail = 1, nRail = 3, railOn = true)
        assertEquals(EpNav.ExitRail, s.nav); assertEquals(false, s.railOn); assertEquals(1, s.rail)
    }

    @Test fun railOkSwitchesStayingOnRail() {
        val s = epPanelKey(4, ep = 2, nEp = 10, rail = 1, nRail = 3, railOn = true)
        assertEquals(EpNav.RailOk, s.nav); assertEquals(true, s.railOn)
    }

    @Test fun railBackCloses() {
        val s = epPanelKey(5, ep = 2, nEp = 10, rail = 1, nRail = 3, railOn = true)
        assertEquals(EpNav.Close, s.nav); assertEquals(false, s.railOn)
    }
}
