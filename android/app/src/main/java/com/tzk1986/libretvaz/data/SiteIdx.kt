package com.tzk1986.libretvaz.data

/**
 * siteIdx 单一坐标系（审计 2026-11-11 P0-1）：settings.siteIdx 全局统一定义为
 * **点播池下标**（`SourceStore.vodPool()`=vodSources 过滤 isAdult 后的序）。
 * 消费全量列表（含专区）的边界——测速墙行、orderSources 全表下标——一律经此换算，
 * 杜绝「Browse 写池下标、Guard/Probe 读全量下标」两套坐标混用导致的静默换源。
 */
object SiteIdx {
    /** 全量下标 → 池下标；该行是专区源或越界返回 null（不可作为当前点播源） */
    fun fullToPool(all: List<VodSource>, fullIdx: Int): Int? {
        val s = all.getOrNull(fullIdx) ?: return null
        if (s.isAdult) return null
        return (0 until fullIdx).count { !all[it].isAdult }
    }

    /** 池下标 → 全量下标；越界/无池返回 -1（调用方据此不排优先） */
    fun poolToFull(all: List<VodSource>, poolIdx: Int): Int {
        if (poolIdx < 0) return -1
        var c = 0
        for (i in all.indices) {
            if (!all[i].isAdult) {
                if (c == poolIdx) return i
                c++
            }
        }
        return -1
    }
}
