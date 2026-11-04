package com.tzk1986.libretvaz.data.perf

import com.tzk1986.libretvaz.data.VodSummary

/**
 * 地区过滤中间模块（自 PerfCore 迁出，2026-11-04；web 对应 debug-web/region.js）。
 * 纯函数、与具体源无关：任何源/任何墙（单源墙、聚合墙、搜索、专区）都可复用。
 * 判定优先级（用户 2026-11-04 批准改为 area 优先）：vod_area 是权威地区信号，
 * typeName/type/class 仅作兜底——修复「分类名带国产/港台等字样把外国条目混进国内」的漏洞。
 */
object Region {

    const val UNKNOWN = "UNKNOWN"

    private val REGION_PAT: List<Pair<String, List<String>>> = listOf(
        "DOM" to listOf("大陆", "内地", "国产", "中国", "华语", "粤语", "香港", "港剧", "港产", "港台", "澳门", "台湾", "台剧", "台影"),
        "EUUS" to listOf("欧美", "美国", "美剧", "英剧", "英国", "欧洲", "法国", "德国", "意大利", "西班牙", "俄罗斯", "加拿大", "澳大利亚", "新西兰", "好莱坞"),
        "JP" to listOf("日本", "日剧", "日影", "日韩"),
        "KR" to listOf("韩国", "韩剧", "韩影", "日韩"),
        "OTHER" to listOf("泰国", "泰剧", "马来", "菲律宾", "越南", "印度", "新加坡", "印尼", "东南亚", "拉丁", "非洲", "中东"),
    )

    private val AREA_MAP: Map<String, String> = buildMap {
        for ((lab, ws) in REGION_PAT) for (w in ws) if (!containsKey(w)) put(w, lab)
        put("国产剧", "DOM"); put("港", "DOM"); put("台", "DOM"); put("澳", "DOM")
        put("美", "EUUS"); put("英", "EUUS"); put("日", "JP"); put("韩", "KR")
        put("其它", "OTHER"); put("其他", "OTHER")
    }

    /** 名称中含有的全部地区标签（按表序，如「日韩综艺」→ [JP, KR]） */
    fun labels(name: String?): List<String> {
        val s = name ?: ""
        return REGION_PAT.filter { (_, ws) -> ws.any { s.contains(it) } }.map { it.first }
    }

    /** 单条 vod_area：按分隔符拆 token，精确 AREA_MAP 优先，其次整词 labels；全空返回 null */
    fun ofArea(a: String?): String? {
        for (t in (a ?: "").split(Regex("[,，、/;；|]+")).map { it.trim() }.filter { it.isNotEmpty() }) {
            AREA_MAP[t]?.let { return it }
            labels(t).firstOrNull()?.let { return it }
        }
        return null
    }

    /** 条目地区：area > typeName > type/class；无信号 UNKNOWN */
    fun classify(typeName: String?, area: String?, type: String? = null, clazz: String? = null): String {
        ofArea(area)?.let { return it }
        if (!typeName.isNullOrEmpty()) labels(typeName).firstOrNull()?.let { return it }
        val n3 = type?.ifEmpty { null } ?: clazz?.ifEmpty { null }
        if (n3 != null) labels(n3).firstOrNull()?.let { return it }
        return UNKNOWN
    }

    /** 便捷入口：直接吃条目 */
    fun of(item: VodSummary): String = classify(item.typeName, item.area)

    /** 便捷入口：是否命中给定地区组（组空=全部放行） */
    fun matches(item: VodSummary, labs: List<String>): Boolean = labs.isEmpty() || labs.contains(of(item))

    /** 四类海报墙预设（港台澳归国内、美国归欧美）；空列表 = 全部 */
    val PRESETS: Map<String, List<Pair<String, List<String>>>> = mapOf(
        "电影" to listOf("全部" to emptyList(), "国内" to listOf("DOM"), "国外" to listOf("EUUS", "JP", "KR", "OTHER"), "日影" to listOf("JP"), "韩影" to listOf("KR"), "欧美" to listOf("EUUS")),
        "电视剧" to listOf("全部" to emptyList(), "国内" to listOf("DOM"), "欧美剧" to listOf("EUUS"), "日剧" to listOf("JP"), "韩剧" to listOf("KR"), "小语种" to listOf("OTHER")),
        "综艺" to listOf("全部" to emptyList(), "国内" to listOf("DOM"), "国外" to listOf("EUUS", "JP", "KR", "OTHER"), "日本" to listOf("JP"), "韩国" to listOf("KR")),
        "动漫" to listOf("全部" to emptyList(), "国产" to listOf("DOM"), "日漫" to listOf("JP"), "韩漫" to listOf("KR"), "欧美" to listOf("EUUS")),
    )
}
