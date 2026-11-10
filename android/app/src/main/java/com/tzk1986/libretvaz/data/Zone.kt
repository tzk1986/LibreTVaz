package com.tzk1986.libretvaz.data

import android.content.Context
import com.tzk1986.libretvaz.data.perf.PerfCore
import org.json.JSONArray
import java.net.URL

/**
 * 专区（资源口）地基（web fongmi.js ZONE_SEED_VER/seedZoneSources/alignZonePool 等价）：
 * 预置站单=本地私有 assets/zone_sources.json（[name,url] 数组，.git/info/exclude 排除，仓库仅空表 .example）；
 * 公开克隆读不到文件=空列表，本文件所有逻辑整体静默跳过，功能不报错。
 */
object Zone {
    /** 站单扩充后升此版本号触发补种（对齐 web 'v4'；2026-11-10 xlsx 18+ 专项三关实测升 v5：
     *  CK资源/乐播 主机已在站单免重；91麻豆 PC 同款请求头三关全过但 app 端 OkHttp 通道被 Cloudflare 指纹拦截（墙空），
     *  按「有效≠能在本项目使用」撤下；黑料拉流挂、成人71 不可达未收。净新增 0 站，v5 仅作补种闸门留痕 */
    const val SEED_VER = "v5"

    /** 分类名关键词判专区（对齐 web isAdultType，导入时源名命中即直接归专区池） */
    val ADULT_KEYWORDS = listOf(
        "伦理片", "福利", "倫理片", "福利片", "日本伦理", "日本福利", "日本福利片", "日本伦理片",
        "擦边短剧", "擦边", "韩国伦理", "里番动漫", "门事件", "制服诱惑", "国产传媒", "cosplay",
        "黑丝诱惑", "无码", "日本无码", "有码", "日本有码", "SWAG", "网红主播", "色情片", "同性片",
        "福利视频", "无码高清", "有码高清", "91视频", "自拍偷拍", "mini传媒", "萝莉少女",
    )

    fun isAdultType(t: String?): Boolean = !t.isNullOrEmpty() && ADULT_KEYWORDS.any { t.contains(it) }

    /**
     * 资源口专用过滤表（用户 2026-11-04 需求，web 蓝本之外的增强，与首页点播 AGG_CATS/地区筛选完全隔离各用各的）：
     * 一级分类行=最新(全池)+有码+无码+动漫；二级筛选行=成人细分（单选，与一级取交集）。
     * 全部为「latest 聚合池」纯客户端过滤：typeName 优先、片名兜底，切类/切筛选零重拉零请求。
     */
    data class ZoneTag(val key: String, val kw: List<String>)

    val ZONE_CATS = listOf(
        ZoneTag("有码", listOf("有码")),                     // 日本有码/有码高清 同含「有码」
        ZoneTag("无码", listOf("无码")),                     // 「无码」不含子串「有码」，二者不误伤
        ZoneTag("动漫", listOf("动漫", "动画", "番")),        // 里番动漫/国漫/日番
    )

    // 用户 2026-11-04「其他分类可用就加入」：新增 7 项+扩词 3 项。依据=30 站 ac=list 实测分类分布
    // （tmp_verify/zone_cats_probe.json：6 站可达共 159 类，跨站高频=中文字幕/国产自拍/欧美/巨乳/熟女乱伦/黑料吃瓜/主播/人妖/VR；
    // 24 站 PC 侧超时未计入，命中以 typeName 优先+片名兜底，纯客户端零重拉）
    val ZONE_FILTERS = listOf(
        ZoneTag("人妻", listOf("人妻")),
        ZoneTag("制服", listOf("制服", "学生", "jk")),                     // 制服诱惑/校园
        ZoneTag("萝莉", listOf("萝莉")),                     // 萝莉少女
        ZoneTag("熟女乱伦", listOf("熟女", "少妇", "乱伦", "姐夫", "嫂", "母女")),
        ZoneTag("巨乳", listOf("巨乳", "美乳", "爆乳", "罩杯")),
        ZoneTag("中文字幕", listOf("中文字幕", "中字", "字幕")),
        ZoneTag("欧美", listOf("欧美", "西洋", "美国", "欧洲")),
        ZoneTag("黑料吃瓜", listOf("黑料", "吃瓜", "爆料", "门事件")),
        ZoneTag("韩国伦理", listOf("韩国伦理", "韩日", "韩国特")),
        ZoneTag("伦理三级", listOf("伦理", "福利", "擦边", "色情", "三级")),
        ZoneTag("自拍国产", listOf("自拍", "偷拍", "91", "国产传媒", "国产", "mini", "SWAG", "网红主播", "主播")),
        ZoneTag("cosplay", listOf("cosplay")),
        ZoneTag("同性", listOf("同性")),
        ZoneTag("人妖", listOf("人妖")),
        ZoneTag("VR", listOf("VR")),
    )

    /** 条目命中：分类名或片名包含任一关键词（忽略大小写；latest 池分类名粗，关键词多在片名） */
    fun matchesTag(it: VodSummary, t: ZoneTag): Boolean =
        t.kw.any { k -> it.typeName?.contains(k, true) == true || it.name.contains(k, true) }

    /** 池过滤入口（cat/flt 均 -1=不限；两条件取交集） */
    fun filterPool(pool: List<VodSummary>, cat: Int, flt: Int): List<VodSummary> {
        val c = ZONE_CATS.getOrNull(cat)
        val f = ZONE_FILTERS.getOrNull(flt)
        if (c == null && f == null) return pool
        return pool.filter { (c == null || matchesTag(it, c)) && (f == null || matchesTag(it, f)) }
    }

    fun hostOf(u: String?): String? = try { URL(u).host.lowercase() } catch (e: Exception) { null }

    /** 读本地私有站单；缺失/损坏=空表（公开克隆形态）。用户 2026-11-06 走查 #9：align 热路径每次读 asset+全量解码 → 进程一次缓存 */
    @Volatile private var presetCache: List<VodSource>? = null

    fun loadPreset(context: Context): List<VodSource> {
        presetCache?.let { return it }
        val list = runCatching {
            val text = context.assets.open("zone_sources.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONArray(i) ?: return@mapNotNull null
                val url = a.optString(1)
                if (url.isBlank()) null else VodSource(a.optString(0).ifBlank { hostOf(url) ?: url }, url.trim(), isAdult = true)
            }
        }.getOrDefault(emptyList())
        presetCache = list
        return list
    }

    /** 首次/升版本进资源口补种预置站（版本号闸门；用户删过不再重复加回；「恢复预置」清标记重种） */
    fun seedIfAbsent(context: Context, store: SourceStore) {
        val settings = SettingsStore(context)
        if (settings.zoneSeedVer == SEED_VER) return
        settings.zoneSeedVer = SEED_VER
        val preset = loadPreset(context)
        if (preset.isEmpty()) return
        val have = store.vodSources().map { PerfCore.norm(it.url) }.toHashSet()
        preset.filter { PerfCore.norm(it.url) !in have }.forEach { store.addVod(it) }
    }

    /**
     * 归池收编（只升不降）：主机名命中站单的点播源强制 isAdult=true（同站路径变体也收进）；
     * 专区源之间仅按精确 URL 去重（路径变体保留互为备份）；当前源下标越界钳位。站单空=整体跳过。
     * 返回是否有实际改动。任何路径都不得把专区源降回普通源。
     */
    fun align(context: Context, store: SourceStore): Boolean {
        val preset = loadPreset(context)
        if (preset.isEmpty()) return false
        val zoneHosts = preset.mapNotNull { hostOf(it.url) }.toHashSet()
        if (zoneHosts.isEmpty()) return false
        val before = store.vodSources()
        val forced = before.map { s ->
            val h = hostOf(s.url)
            if (h != null && h in zoneHosts && !s.isAdult) s.copy(isAdult = true) else s
        }
        val seen = HashSet<String>()
        val deduped = forced.filter { s ->
            val h = hostOf(s.url)
            if (h == null || h !in zoneHosts) return@filter true
            seen.add(PerfCore.norm(s.url))
        }
        if (deduped == before) return false
        store.replaceVod(deduped)
        val settings = SettingsStore(context)
        if (settings.siteIdx >= deduped.count { !it.isAdult }) settings.siteIdx = 0
        return true
    }
}
