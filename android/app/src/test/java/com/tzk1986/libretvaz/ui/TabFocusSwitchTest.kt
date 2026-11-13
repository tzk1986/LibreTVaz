package com.tzk1986.libretvaz.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TVBox 对齐（用户 2026-11-12）：tab 焦点即切+250ms 防抖纯逻辑。
 * 调度器注入假时钟（生产=协程 delay；此处手工触发/断言取消），零 Android 依赖。
 * 装机走查（2026-11-12）补裁决：焦点自行外进入 chip（触屏清焦后 DOWN 恢复、BACK 回首页默认落焦）
 * 不得触发换档，仅行内焦点横移（上一事件=同行另一档）才排程——否则触屏/返回被「即切」误伤。
 */
class TabFocusSwitchTest {

    // ---- tabSwitchPlan：焦点落在异 tab=应排程；同 key（含触屏点选/进场直达选中）=不排程 ----

    @Test
    fun plan_differentKey_returnsFocus() {
        assertEquals(2, tabSwitchPlan(2, 1))
    }

    @Test
    fun plan_sameKey_returnsNull() {
        assertNull(tabSwitchPlan(1, 1))
    }

    @Test
    fun plan_negOneVsAgg0_isDifferent() {
        // -1=「最新/更新推荐」哨兵档，与真实索引 0 不同档
        assertEquals(-1, tabSwitchPlan(-1, 0))
        assertEquals(0, tabSwitchPlan(0, -1))
    }

    // ---- FocusTabSwitcher：防抖排程/换向取消/回选中取消/处置取消 ----

    /** 假调度器：记录待触发任务与取消标记，测试手工 fire */
    private class FakeSched {
        var lastDelay: Long = -1
        var task: (() -> Unit)? = null
        var cancelled = false
        fun schedule(ms: Long, t: () -> Unit): () -> Unit {
            lastDelay = ms
            task = t
            return { cancelled = true; task = null }
        }
        fun fire() { task?.invoke() }
    }

    /** 行内已站岗事件：模拟焦点已在选中档（行内前事件存在），使后续横移可按即切语义排程 */
    private fun FocusTabSwitcher.seedAtSelected(sel: Int) { onFocus(sel) { sel } }

    @Test
    fun focusOffSelected_schedules250msThenApplies() {
        val f = FakeSched()
        val applied = mutableListOf<Int>()
        val sw = FocusTabSwitcher(250, f::schedule) { applied += it }
        sw.seedAtSelected(1)
        sw.onFocus(2) { 1 }
        assertEquals(250, f.lastDelay)
        f.fire()
        assertEquals(listOf(2), applied)
    }

    @Test
    fun focusBeforeDelay_notAppliedYet() {
        val f = FakeSched()
        val applied = mutableListOf<Int>()
        val sw = FocusTabSwitcher(250, f::schedule) { applied += it }
        sw.seedAtSelected(1)
        sw.onFocus(3) { 1 }
        assertEquals(emptyList<Int>(), applied)
    }

    @Test
    fun focusMovesAgain_cancelsPreviousPending() {
        val f = FakeSched()
        val applied = mutableListOf<Int>()
        val sw = FocusTabSwitcher(250, f::schedule) { applied += it }
        sw.seedAtSelected(1)
        sw.onFocus(2) { 1 }
        sw.onFocus(3) { 1 }
        assertEquals(true, f.cancelled)
        // 旧目标 2 永不落地；仅最新目标 3 触发
        f.fire()
        assertEquals(listOf(3), applied)
    }

    @Test
    fun focusBackToSelected_cancelsPending() {
        val f = FakeSched()
        val applied = mutableListOf<Int>()
        val sw = FocusTabSwitcher(250, f::schedule) { applied += it }
        sw.seedAtSelected(1)
        sw.onFocus(2) { 1 } // 排程切 2
        sw.onFocus(1) { 1 } // 回到当前选中=取消，不切
        assertNull(f.task) // 第二次 onFocus 取消后不留新任务（task 引用虽残留，但 cancel 已调用）
        assertEquals(true, f.cancelled)
    }

    @Test
    fun focusOnSelected_schedulesNothing() {
        val f = FakeSched()
        var applied = -99
        val sw = FocusTabSwitcher(250, f::schedule) { applied = it }
        sw.onFocus(1) { 1 }
        assertNull(f.task)
        assertEquals(-1, f.lastDelay)
        assertEquals(-99, applied)
    }

    @Test
    fun dispose_cancelsPending() {
        val f = FakeSched()
        val sw = FocusTabSwitcher(250, f::schedule) { }
        sw.seedAtSelected(1)
        sw.onFocus(2) { 1 }
        sw.dispose()
        assertEquals(true, f.cancelled)
    }

    // ---- 行外进入不即切（装机走查 w15/16 实锤回归） ----

    @Test
    fun firstEntryFromOutside_noSchedule() {
        // 触屏 tap 清焦后 DOWN 恢复落「最新」(0)、选中仍「电影」(1)：前事件=null=行外进入 → 不切
        val f = FakeSched()
        val applied = mutableListOf<Int>()
        val sw = FocusTabSwitcher(250, f::schedule) { applied += it }
        sw.onFocus(0) { 1 }
        assertNull(f.task)
        assertEquals(-1, f.lastDelay)
        assertEquals(emptyList<Int>(), applied)
    }

    @Test
    fun outsideEntry_thenHorizontal_schedules() {
        // 进入行不切，但之后行内横移正常即切
        val f = FakeSched()
        val applied = mutableListOf<Int>()
        val sw = FocusTabSwitcher(250, f::schedule) { applied += it }
        sw.onFocus(0) { 1 } // 行外进入 0：不切
        sw.onFocus(2) { 1 } // 行内 0→2：排程切 2
        assertEquals(250, f.lastDelay)
        f.fire()
        assertEquals(listOf(2), applied)
    }

    @Test
    fun duplicateFocusEvent_sameKey_noSchedule() {
        val f = FakeSched()
        val applied = mutableListOf<Int>()
        val sw = FocusTabSwitcher(250, f::schedule) { applied += it }
        sw.seedAtSelected(1)
        sw.onFocus(2) { 1 }
        sw.onFocus(2) { 1 } // 同档重复事件（滚动重组重放）：撤旧排程不新排
        assertNull(f.task)
        assertEquals(emptyList<Int>(), applied)
    }

    // ---- tabFrIndexFor：筛选面板 BACK 收起后焦点须显式落回当前选中 tab ----
    // （修复 2026-11-13 bug：不收焦=Compose 把焦点还给行首「最新」chip，且 lastKey 仍为行内值、
    //   「行外进入不即切」护栏失效 → 250ms 后 aggCat 被切回 -1，页面跳回首页最新墙）

    @Test
    fun tabFrIndexFor_latestSentinel_isFirstChip() {
        assertEquals(0, tabFrIndexFor(-1))
    }

    @Test
    fun tabFrIndexFor_categoryUsesPlusOneLayout() {
        assertEquals(1, tabFrIndexFor(0))
        assertEquals(4, tabFrIndexFor(3))
    }

    @Test
    fun reenterRow_focusRestoredToLastChip_noSchedule() {
        // 从综艺(2) DOWN 进墙、UP 回综艺：回档=上次行内焦点 → 不切（保守，无惊喜）
        val f = FakeSched()
        val applied = mutableListOf<Int>()
        val sw = FocusTabSwitcher(250, f::schedule) { applied += it }
        sw.seedAtSelected(1)
        sw.onFocus(2) { 1 } // 行内 1→2 排程（此处不 fire，模拟切档后墙重载…简化：直接续走）
        sw.onFocus(2) { 2 } // 2 成选中后重获焦（回行）：key==selected 取消且不再排
        assertNull(f.task)
        assertEquals(emptyList<Int>(), applied)
    }

    // ---- browseBackStage：分层返回（用户 2026-11-13 裁决流程）----
    // 海报墙焦点 BACK=回焦 tab 行；tab 行焦点 BACK=切回首页 tab；其余沿用旧层级

    @Test
    fun back_dialogFirst() {
        assertEquals(BrowseBack.CloseDialog, browseBackStage(dialogOpen = true, panelOpen = true, wallFocused = true, tabFocused = true, atTop = false))
    }

    @Test
    fun back_panelFirst() {
        assertEquals(BrowseBack.ClosePanel, browseBackStage(dialogOpen = false, panelOpen = true, wallFocused = true, tabFocused = true, atTop = false))
    }

    @Test
    fun back_wallFocus_refocusTab() {
        assertEquals(BrowseBack.RefocusTab, browseBackStage(false, false, wallFocused = true, tabFocused = false, atTop = true))
    }

    @Test
    fun back_tabFocus_goHome() {
        assertEquals(BrowseBack.GoHome, browseBackStage(false, false, wallFocused = false, tabFocused = true, atTop = false, homeSelected = false))
    }

    @Test
    fun back_tabFocusAtTop_stillGoHome() {
        // tab 行常驻吸顶，回不回顶不优先于「tab 焦点回首页档」
        assertEquals(BrowseBack.GoHome, browseBackStage(false, false, wallFocused = false, tabFocused = true, atTop = true, homeSelected = false))
    }

    @Test
    fun back_tabFocusAlreadyHome_fallsThrough() {
        // 已在「最新/更新推荐」档：再按 BACK 下潜（回顶/退出），不空转 GoHome
        assertEquals(BrowseBack.ScrollTop, browseBackStage(false, false, wallFocused = false, tabFocused = true, atTop = false, homeSelected = true))
        assertEquals(BrowseBack.Exit, browseBackStage(false, false, wallFocused = false, tabFocused = true, atTop = true, homeSelected = true))
    }

    @Test
    fun back_noFocus_scrollTopThenExit() {
        assertEquals(BrowseBack.ScrollTop, browseBackStage(false, false, wallFocused = false, tabFocused = false, atTop = false, homeSelected = true))
        assertEquals(BrowseBack.Exit, browseBackStage(false, false, wallFocused = false, tabFocused = false, atTop = true, homeSelected = true))
    }
}
