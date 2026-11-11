package com.tzk1986.libretvaz.data

import com.tzk1986.libretvaz.data.perf.PerfCore

/**
 * E46（审计 2026-11-11）：订阅仓库预设清单用户可编。
 * 旧 SourceGuard.PRESETS 硬编码 14 仓库被守护静默合并（用户无从删改）；
 * 现 DEFAULT 仅作首装种子，实际清单存 SettingsStore.subReposRaw（prefs「sub_repos」，
 * 行式 `名称\tURL`），源管理页可增删/恢复默认。raw 空白=未初始化回退 DEFAULT。
 */
object SubRepos {

    /** 订阅仓库预设（web fongmi.js PRESETS 对齐，源管理页共用；tvyuan=master 分支，main 已 404）。
     *  后 9 条来自 2026-11-10「极客小盒」接口合集 xlsx：全部经三级实测（本 app 同款协议：
     *  ac=videolist 搜索有结果 → vod_id 详情取 vod_play_url → 播放地址真拉流验视频流），
     *  剔除 18+/爬虫主导(type3/csp)/格式不兼容(jsm模板)/vicp穿透域/重复镜像后择优收录。 */
    val DEFAULT = listOf(
        "noimank 多仓" to "https://gitlab.com/noimank/tvbox/-/raw/main/tvboxmuti.json",
        "Liucn 单仓" to "https://raw.liucn.cc/box/m.json",
        "Qist 综合" to "https://gh-proxy.com/https://raw.githubusercontent.com/qist/tvbox/master/0821.json",
        "tvyuan 体检聚合" to "https://gh-proxy.com/https://raw.githubusercontent.com/25175/tvyuan/master/tvbox_collect.json",
        "51源合集" to "https://raw.githubusercontent.com/fadai9991/tvbox-sub/main/tvbox.json",
        "Easy涛聚合" to "https://raw.githubusercontent.com/EasyXC/EasyTVbox/refs/heads/main/api/tao.json",
        "Clun 盒子" to "https://clun.top/box.json",
        "非凡 1024" to "https://raw.githubusercontent.com/jigedos/1024/master/jsm.json",
        "zheng 合集" to "https://raw.githubusercontent.com/zheng197600/tvbox/main/0820.json",
        "平安 2万" to "https://cnb.cool/cnlv/tv/-/git/raw/main/20000.json",
        "牛角 TV" to "https://raw.githubusercontent.com/niubihu1/tvbox-/main/1.json",
        "陆城影视" to "https://jianhancloud.cn/lcys/lcjk.json",
        "letian 盒子" to "https://raw.githubusercontent.com/letian1650/mybox/main/m.json",
        "猫 map" to "https://raw.githubusercontent.com/supermeguo/BoxRes/main/Myuse/catcr.json",
    )

    fun serialize(list: List<Pair<String, String>>): String =
        list.joinToString("\n") { "${clean(it.first)}\t${clean(it.second)}" }

    /** 行式 `名称\tURL` 解析；无 tab/空 URL 的脏行跳过（手改 prefs 容错） */
    fun parse(raw: String): List<Pair<String, String>> =
        raw.lineSequence().mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i <= 0) return@mapNotNull null
            val name = clean(line.substring(0, i))
            val url = clean(line.substring(i + 1))
            if (name.isEmpty() || url.isEmpty()) null else name to url
        }.toList()

    /** 生效清单：raw 空白=未初始化回退 DEFAULT（不写盘，写发生在用户首次编辑） */
    fun editable(raw: String): List<Pair<String, String>> =
        if (raw.isBlank()) DEFAULT else parse(raw)

    fun remove(list: List<Pair<String, String>>, url: String): List<Pair<String, String>> {
        val u = PerfCore.norm(url)
        return list.filter { PerfCore.norm(it.second) != u }
    }

    /** 同 URL（norm）已存在=改名替换原位，否则追加 */
    fun add(list: List<Pair<String, String>>, name: String, url: String): List<Pair<String, String>> {
        val u = PerfCore.norm(url)
        if (u.isBlank()) return list
        val n = clean(name).ifBlank { "仓库" }
        val hit = list.indexOfFirst { PerfCore.norm(it.second) == u }
        return if (hit >= 0) list.toMutableList().also { it[hit] = n to it[hit].second }
        else list + (n to url)
    }

    /** 制表/换行是行式存储分隔符，名称与 URL 一律剥除 */
    private fun clean(s: String): String = s.replace(Regex("[\\t\\r\\n]"), "").trim()
}
