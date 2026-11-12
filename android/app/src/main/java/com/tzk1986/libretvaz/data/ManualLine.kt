package com.tzk1986.libretvaz.data

import android.content.Context

/**
 * 方案1（用户 2026-11-12 裁决）：手动换线跨会话持久化——「vodId|sourceKey」→线路名 flag。
 * switchLine 即写（不等 5s 进度落盘）；重进详情 paint 优先于测速最优线与进度线；「测速」钮清除。
 * 存名不存序数：源线路表顺序变化按名还原（同 Prog.flag 语义）；名不在表=弃权回落原逻辑。
 */
object ManualLine {
    fun key(vodId: String, sourceKey: String) = "$vodId|$sourceKey"

    private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("libretvaz_manualline", Context.MODE_PRIVATE)
    }

    fun get(vodId: String, sourceKey: String): String? = prefs?.getString(key(vodId, sourceKey), null)

    fun put(vodId: String, sourceKey: String, flag: String) { prefs?.edit()?.putString(key(vodId, sourceKey), flag)?.apply() }

    fun delete(vodId: String, sourceKey: String) { prefs?.edit()?.remove(key(vodId, sourceKey))?.apply() }
}

/**
 * 重进详情初始线决策（paint 纯逻辑抽取）：
 * manual 命中 > 进度 ph（线名命中→否则 ph.line 钳位）> best（测速最优）。
 * progFlag=null=无进度；progFlag=""=无名按序数；progLine<0 且无名=不采纳进度。
 * 全弃权/空表返 best 钳位结果（空表=-1，调用方 coerce 兜底同旧链）。
 */
fun resolveEntryLine(flags: List<String>, best: Int, manualFlag: String?, progFlag: String?, progLine: Int): Int {
    if (flags.isEmpty()) return -1
    val mi = if (!manualFlag.isNullOrEmpty()) flags.indexOfFirst { it == manualFlag } else -1
    if (mi >= 0) return mi
    if (progFlag != null) {
        val fi = if (progFlag.isNotEmpty()) flags.indexOfFirst { it == progFlag } else -1
        return if (fi >= 0) fi else progLine.coerceIn(0, flags.size - 1)
    }
    return best.coerceIn(0, flags.size - 1)
}
