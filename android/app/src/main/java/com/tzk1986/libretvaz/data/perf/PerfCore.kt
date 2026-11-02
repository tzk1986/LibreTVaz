package com.tzk1986.libretvaz.data.perf

import com.tzk1986.libretvaz.data.PlayEpisode
import com.tzk1986.libretvaz.data.PlayLine
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Web 端 debug-web/perf-core.js 的 Kotlin 等价移植（B0）。
 * 规则以 perf-core.js 为准；单测 PerfCoreTest 与 web 断言对齐。
 */
object PerfCore {

    fun norm(u: String?): String = (u ?: "").trim().replace(Regex("/+$"), "")

    /** 搜索源顺序：当前源 → health ms 升序 → 未测(ms=null)按原源序；稳定排序 */
    fun <T> orderSources(items: List<T>, currentSi: Int, siOf: (T) -> Int, msOf: (T) -> Long?): List<T> {
        val indexed = items.mapIndexed { i, x -> Triple(x, i, msOf(x) ?: Long.MAX_VALUE) }
            .sortedWith(Comparator { a, b ->
            val ca = if (siOf(a.first) == currentSi) 0 else 1
            val cb = if (siOf(b.first) == currentSi) 0 else 1
            when {
                ca != cb -> ca - cb
                a.third != b.third -> if (a.third < b.third) -1 else 1
                else -> a.second - b.second
            }
        })
        return indexed.map { it.first }
    }

    fun <T> splitBatches(list: List<T>, top: Int = 8): Pair<List<T>, List<T>> = list.take(top) to list.drop(top)

    fun searchKey(kw: String): String = "search:" + kw.trim().lowercase(Locale.ROOT)
    const val SEARCH_TTL = 10 * 60 * 1000L

    /** 换词/重进取消旧回调：bump 后旧 token 视为过期（对齐 FongMi ViewModelSearchRunner epoch） */
    class Epoch {
        private val e = AtomicInteger(0)
        fun bump(): Int = e.incrementAndGet()
        fun cur(): Int = e.get()
        fun stale(token: Int): Boolean = token != e.get()
    }

    data class Shard<T>(val t: Long, val d: List<T>)
    data class TtlSplit<T>(val fresh: Map<String, List<T>>, val stale: Map<String, List<T>>)

    /** 搜索 SWR 分片：按 TTL 拆 fresh/stale（两桶均保留原条目供立即渲染） */
    fun <T> splitTtl(shards: Map<String, Shard<T>>?, ttl: Long, now: Long = System.currentTimeMillis()): TtlSplit<T> {
        val fresh = LinkedHashMap<String, List<T>>()
        val stale = LinkedHashMap<String, List<T>>()
        shards?.forEach { (u, v) ->
            (if (now - v.t <= ttl) fresh else stale)[u] = v.d
        }
        return TtlSplit(fresh, stale)
    }

    // ===== 片名核心词归一化（对齐 web coreName） =====
    private val CORE_BRACKET = Regex("[《〈「『【\\[(（][^》〉」』\\])）\\]]*[》〉」』】\\])）]")
    private val CORE_TOKEN = Regex(
        "(第[0-9一二三四五六七八九十百零两]+[季部集篇期章弹]|[0-9]+[季部]|更新至[0-9]+[集话]?|剧场版|特别篇|特别版|未删减版?|终极版|加长版|导演剪辑版|完整版|正片|预告片|花絮|片花|完结版?|抢先版|高清版?|超清|标清|蓝光|杜比|粤语|国语|中字|双字)"
    )
    private val CORE_CODE = Regex("(^|[^A-Za-z0-9])(HD|BD|TC|TS|MKV|MP4|4K|8K|VIP|[0-9]{3,4}P)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
    private val CORE_SPLIT = Regex("[，。、！!？?；;：:．·\\s|｜/\\\\\\-]+")

    fun coreName(title: String?): String {
        var s = (title ?: "").trim()
        if (s.isEmpty()) return ""
        s = CORE_BRACKET.replace(s, "")
        s = CORE_TOKEN.replace(s, "")
        s = CORE_CODE.replace(s) { it.groupValues[1] }
        s = s.split(CORE_SPLIT)[0].trim().replace(Regex("[0-9]+$"), "").trim()
        return if (s.length >= 2) s else ""
    }

    /**
     * 截断核心词：把查询 q 在词首字母串中的匹配位置对齐截到 q 等长汉字并聚合。
     * variantsOf 传 PinIndex::variants（含多音字变体）；缺省退化为词首前缀截断。
     */
    fun truncateByQuery(
        q: String?, hits: List<String>?, limit: Int = 3, minCount: Int = 2,
        variantsOf: ((String) -> List<String>)? = null,
    ): List<String> {
        val ql = (q ?: "").lowercase(Locale.ROOT)
        if (!Regex("^[a-z]+$").matches(ql) || ql.length < 2 || hits == null || hits.size < minCount) return emptyList()
        val inHits = hits.toHashSet()
        // 与 initialOf 判定一致：ASCII 字母数字或 CJK 才占一个首字母位（标点/符号不占）
        val hasIni = { ch: Char -> ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' || PinIndex.isCjk(ch) }
        val count = LinkedHashMap<String, Int>()
        for (w in hits) {
            var core = ""
            if (variantsOf != null) {
                for (v in variantsOf(w)) {
                    val idx = v.indexOf(ql)
                    if (idx < 0) continue
                    var vi = -1
                    val out = StringBuilder()
                    for (ch in w) {
                        if (!hasIni(ch)) { if (out.isNotEmpty()) out.append(ch); continue }
                        vi++
                        if (vi < idx) continue
                        if (vi >= idx + ql.length) break
                        out.append(ch)
                    }
                    if (vi >= idx + ql.length - 1) core = out.toString().trim()
                    if (core.isNotEmpty()) break
                }
            } else {
                if (w.length > ql.length) core = w.substring(0, ql.length).trim()
            }
            if (core.length < 2 || inHits.contains(core)) continue
            count[core] = (count[core] ?: 0) + 1
        }
        return count.filterValues { it >= minCount }
            .toList().sortedByDescending { it.second }.take(limit).map { it.first }
    }

    // ===== m3u8 改写（分片与 KEY/MAP URI 包 /proxy） =====
    private val PX_RE = Regex("(^|[=/])proxy\\?url=", RegexOption.IGNORE_CASE)
    private val KEY_MAP_RE = Regex("^#EXT-X-(KEY|MAP)\\b", RegexOption.IGNORE_CASE)
    private val URI_ATTR_RE = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    private fun px(u: String): String = "/proxy?url=" + encodeURIComponent(u)

    /** encodeURIComponent 等价（UTF-8 字节，非保留字符外全 %XX 大写） */
    fun encodeURIComponent(s: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
        return buildString {
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (unreserved.indexOf(ch) >= 0) append(ch) else append("%%%02X".format(c))
            }
        }
    }

    private fun resolveUrl(u: String, base: String): String? = try {
        URL(URL(base), u).toExternalForm()
    } catch (e: Exception) { null }

    fun rewriteM3u8(text: String, base: String): String {
        val rwAttr = fun(line: String): String {
            val m = URI_ATTR_RE.find(line) ?: return line
            val u = m.groupValues[1]
            if (PX_RE.containsMatchIn(u)) return line
            val a = resolveUrl(u, base) ?: return line
            return line.replace("\"$u\"", "\"" + px(a) + "\"")
        }
        return text.split("\n").joinToString("\n") { raw ->
            val cr = raw.endsWith("\r")
            val line = if (cr) raw.dropLast(1) else raw
            val s = line.trim()
            when {
                s.isEmpty() -> raw
                s.startsWith("#") -> if (KEY_MAP_RE.containsMatchIn(s)) rwAttr(line) + (if (cr) "\r" else "") else raw
                PX_RE.containsMatchIn(s) -> raw
                else -> {
                    val a = resolveUrl(s, base)
                    (if (a != null) px(a) else s) + (if (cr) "\r" else "")
                }
            }
        }
    }

    // ===== EPG（XMLTV 正则解析） =====
    fun normName(s: String?): String {
        var t = (s ?: "").uppercase(Locale.ROOT).replace(Regex("\\s+"), "")
        t = t.replace(Regex("(高清|标清|超清|蓝光|FHD|HD|SD)$"), "")
        t = t.replace(Regex("^CCTV[-_—–]?"), "CCTV")
        return t
    }

    fun tsNum(t: String?): Long {
        val d = (t ?: "").replace(Regex("[^0-9]"), "").take(14)
        return if (d.length >= 8) d.toLong() else 0L
    }

    fun nowNum(cal: Calendar = Calendar.getInstance()): Long {
        fun p(n: Int, w: Int = 2) = n.toString().padStart(w, '0')
        return ("" + cal.get(Calendar.YEAR) + p(cal.get(Calendar.MONTH) + 1) + p(cal.get(Calendar.DAY_OF_MONTH)) +
            p(cal.get(Calendar.HOUR_OF_DAY)) + p(cal.get(Calendar.MINUTE)) + p(cal.get(Calendar.SECOND))).toLong()
    }

    data class EpgProgram(val t: Long, val s: Long, val title: String, val desc: String)
    data class EpgIndex(
        val channels: Map<String, String>,
        val progs: Map<String, List<EpgProgram>>,
        val byName: Map<String, String>,
    )

    private val XML_ENT = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")

    fun decodeXmlEnt(s: String?): String =
        Regex("&([a-zA-Z]+);").replace(s ?: "") { m ->
            XML_ENT[m.groupValues[1].lowercase(Locale.ROOT)] ?: m.value
        }

    private val CH_RE = Regex("<channel\\b[^>]*\\bid=\"([^\"]*)\"[^>]*>([\\s\\S]*?)</channel>")
    private val PG_RE = Regex("<programme\\b([^>]*?)(?:/>|>([\\s\\S]*?)</programme>)")

    fun parseXmltv(text: String?): EpgIndex {
        val src = text ?: ""
        val channels = LinkedHashMap<String, String>()
        for (m in CH_RE.findAll(src)) {
            val dn = Regex("<display-name[^>]*>([^<]*)</display-name>").find(m.groupValues[2])
            channels[m.groupValues[1]] = decodeXmlEnt((dn?.groupValues?.get(1) ?: m.groupValues[1]).trim())
        }
        val progs = LinkedHashMap<String, MutableList<EpgProgram>>()
        for (m in PG_RE.findAll(src)) {
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2].ifEmpty { "" }
            val ch = Regex("channel=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)
            val t = tsNum(Regex("start=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1))
            val s = tsNum(Regex("stop=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1))
            if (ch.isNullOrEmpty() || t == 0L) continue
            progs.getOrPut(ch) { mutableListOf() }.add(
                EpgProgram(
                    t, s,
                    decodeXmlEnt(Regex("<title[^>]*>([^<]*)</title>").find(inner)?.groupValues?.get(1) ?: "").trim(),
                    decodeXmlEnt(Regex("<desc[^>]*>([^<]*)</desc>").find(inner)?.groupValues?.get(1) ?: "").trim(),
                )
            )
        }
        progs.values.forEach { l -> l.sortBy { it.t } }
        val byName = LinkedHashMap<String, String>()
        for ((id, name) in channels) {
            val n = normName(name)
            if (n.isNotEmpty() && n !in byName) byName[n] = id
        }
        return EpgIndex(channels, progs.mapValues { it.value.toList() }, byName)
    }

    /** 某频道某天的节目列表：名归一→id 匹配；退化 progs 以频道名直键；再退化 channels 值归一全比 */
    fun epgFor(index: EpgIndex?, chanName: String?, dayNum: Long? = null): List<EpgProgram> {
        if (index == null || chanName.isNullOrEmpty()) return emptyList()
        val nm = normName(chanName)
        val list = index.byName[nm]?.let { index.progs[it] }
            ?: index.progs[chanName]
            ?: index.channels.keys.firstOrNull { normName(it) == nm }?.let { index.progs[it] }
            ?: return emptyList()
        val day = (dayNum ?: nowNum()).toString().take(8)
        return list.filter { it.t.toString().take(8) == day }
    }

    fun epgNow(list: List<EpgProgram>?, n: Long = nowNum()): EpgProgram? =
        list?.firstOrNull { it.t <= n && (it.s == 0L || it.s >= n) }

    // ===== O1b/A1 播放选线 =====
    private val STREAM_RE = Regex("\\.(m3u8|mp4|flv|mkv|ts|mpd)(\\?|$)", RegexOption.IGNORE_CASE)
    fun isStreamLink(u: String?): Boolean = u != null && STREAM_RE.containsMatchIn(u)

    const val SPEED_GOOD = 1500
    const val SPEED_FAIR = 150

    data class LineCandidate(val index: Int, val episode: PlayEpisode)

    /**
     * 自动换线候选：未试过≠当前且有地址；speeds=各线 KB/s（null 未测、0 失败、-1 采样失败按未知），
     * 实测慢/不可用线排最后，流后缀优先、同级保线路序（稳定排序）。空数组=无可换。
     */
    fun pickNextLine(
        lines: List<PlayLine>?, line: Int = -1, curEp: Int = 0,
        tried: Set<Int> = emptySet(), speeds: List<Long?>? = null,
    ): List<LineCandidate> {
        val isBad = fun(i: Int): Boolean {
            val k = speeds?.getOrNull(i)
            return k != null && k >= 0 && k < SPEED_FAIR
        }
        return (lines ?: emptyList()).mapIndexed { i, g -> i to (g.list.getOrNull(curEp) ?: g.list.firstOrNull()) }
            .filter { (i, u) -> i != line && i !in tried && u != null }
            .map { (i, u) -> LineCandidate(i, u!!) }
            .sortedWith(Comparator { a, b ->
                val ba = if (isBad(a.index)) 1 else 0
                val bb = if (isBad(b.index)) 1 else 0
                if (ba != bb) ba - bb
                else {
                    val sa = if (isStreamLink(a.episode.link)) 1 else 0
                    val sb = if (isStreamLink(b.episode.link)) 1 else 0
                    sb - sa // 流后缀优先
                }
            })
    }

    data class SpeedTag(val label: String, val cls: String)

    fun fmtSpeed(k: Long): String =
        if (k >= 1024) "%.1fMB/s".format(Locale.US, k / 1024.0) else "${k}KB/s"

    /** null=未测不显；-1=测速失败(不判死)；<150 或 0 红；≥1500 绿；中间黄 */
    fun lineSpeedTag(kbps: Long?): SpeedTag? {
        if (kbps == null) return null
        if (kbps < 0) return SpeedTag("测速失败", "sp-dim")
        if (kbps == 0L || kbps < SPEED_FAIR) return SpeedTag((if (kbps > 0) fmtSpeed(kbps) + " " else "") + "慢/不可用", "sp-bad")
        if (kbps >= SPEED_GOOD) return SpeedTag(fmtSpeed(kbps) + " 流畅", "sp-ok")
        return SpeedTag(fmtSpeed(kbps) + " 可能卡顿", "sp-mid")
    }

    /** 最快线：已测≥FAIR 且流后缀取最大；无有效测速退回首个流后缀线，再退首条非空线 */
    fun pickBestLine(lines: List<PlayLine>?, speeds: List<Long?>?, curEp: Int = 0): Int {
        val eps = lines ?: emptyList()
        var bi = -1
        var best = 0L
        eps.forEachIndexed { i, g ->
            val k = speeds?.getOrNull(i)
            if (k == null || k < SPEED_FAIR) return@forEachIndexed
            val u = g.list.getOrNull(curEp) ?: g.list.firstOrNull()
            if (u == null || !isStreamLink(u.link)) return@forEachIndexed
            if (k > best) { best = k; bi = i }
        }
        if (bi >= 0) return bi
        val fi = eps.indexOfFirst { g -> g.list.isNotEmpty() && g.list.any { isStreamLink(it.link) } }
        if (fi >= 0) return fi
        val fi2 = eps.indexOfFirst { g -> g.list.isNotEmpty() }
        return if (fi2 < 0) 0 else fi2
    }

    // ===== 地区分类中间件 =====
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

    fun regionLabels(name: String?): List<String> {
        val s = name ?: ""
        return REGION_PAT.filter { (_, ws) -> ws.any { s.contains(it) } }.map { it.first }
    }

    fun regionOfArea(a: String?): String? {
        for (t in (a ?: "").split(Regex("[,，、/;；|]+")).map { it.trim() }.filter { it.isNotEmpty() }) {
            AREA_MAP[t]?.let { return it }
            regionLabels(t).firstOrNull()?.let { return it }
        }
        return null
    }

    /** 条目地区：typeName > area > type/class；无信号 UNKNOWN */
    fun classifyVod(typeName: String?, area: String?, type: String? = null, clazz: String? = null): String {
        if (!typeName.isNullOrEmpty()) { regionLabels(typeName).firstOrNull()?.let { return it } }
        regionOfArea(area)?.let { return it }
        val n3 = type?.ifEmpty { null } ?: clazz?.ifEmpty { null }
        if (n3 != null) { regionLabels(n3).firstOrNull()?.let { return it } }
        return "UNKNOWN"
    }

    /** 三类海报墙预设（港台归国内、美国归欧美）；空列表 = 全部 */
    val VOD_FILTERS: Map<String, List<Pair<String, List<String>>>> = mapOf(
        "电影" to listOf("全部" to emptyList(), "国内" to listOf("DOM"), "国外" to listOf("EUUS", "JP", "KR", "OTHER"), "日影" to listOf("JP"), "韩影" to listOf("KR"), "欧美" to listOf("EUUS")),
        "电视剧" to listOf("全部" to emptyList(), "国内" to listOf("DOM"), "欧美剧" to listOf("EUUS"), "日剧" to listOf("JP"), "韩剧" to listOf("KR"), "小语种" to listOf("OTHER")),
        "综艺" to listOf("全部" to emptyList(), "国内" to listOf("DOM"), "国外" to listOf("EUUS", "JP", "KR", "OTHER"), "日本" to listOf("JP"), "韩国" to listOf("KR")),
        "动漫" to listOf("全部" to emptyList(), "国产" to listOf("DOM"), "日漫" to listOf("JP"), "韩漫" to listOf("KR"), "欧美" to listOf("EUUS")),
    )
}
