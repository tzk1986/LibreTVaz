package com.tzk1986.libretvaz.ui

/**
 * TVBox 对齐（用户 2026-11-12 裁决）：分类 tab 行遥控走焦=焦点移到即切换（免 OK）。
 * 250ms 防抖：焦点在异档停稳才切；途中回当前档/继续移动=取消旧排程，防横滚连发拉墙请求。
 * -1=「最新/更新推荐」哨兵档；key=该行 chip 索引。
 */
fun tabSwitchPlan(key: Int, selected: Int?): Int? = if (key != selected) key else null

/** 筛选面板 BACK 收起后焦点还原目标（2026-11-13 bug 修）：不收焦=Compose 把焦点还给行首
 *  「最新」chip 且行内 lastKey 护栏失效→走焦即切把内容打回最新墙。-1=最新→0，分类 i→i+1（同 tabFrs 排位） */
fun tabFrIndexFor(aggCat: Int): Int = (aggCat + 1).coerceAtLeast(0)

/** 防抖调度壳：scheduler 注入延迟执行并返回取消句柄（生产=协程 delay；单测=假时钟） */
class FocusTabSwitcher(
    private val delayMs: Long,
    private val scheduler: (Long, () -> Unit) -> (() -> Unit),
    private val apply: (Int) -> Unit,
) {
    private var cancelTask: (() -> Unit)? = null
    // 行内最近一次获焦档；null=焦点不在本行（触屏清焦/BACK 默认落焦等行外进入不即切，装机走查 w15/16 实锤）
    private var lastKey: Int? = null

    fun onFocus(key: Int, selected: () -> Int) {
        cancelTask?.invoke()
        cancelTask = null
        val prev = lastKey
        lastKey = key
        val target = tabSwitchPlan(key, selected()) ?: return
        // 仅行内焦点横移（前事件=另一档）才换档；首次入行/同档重放=不切，避免触屏/返回惊喜
        if (prev == null || prev == key) return
        cancelTask = scheduler(delayMs) { apply(target) }
    }

    fun dispose() {
        cancelTask?.invoke()
        cancelTask = null
        lastKey = null
    }
}
