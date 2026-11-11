package com.tzk1986.libretvaz.data.perf

import java.util.Locale

/**
 * 拼音首字母索引（perf-core.js createPinIndex 的 Kotlin 等价）。
 * 首字母=PinTable 离线预生成查表（审计 2026-11-11 P0-1：原 ICU Collator(zh-Hans-CN) 锚点区间法
 * 已在构建期逐字烘成 assets/pin_index.bin，PinTableParityTest 全表校验一致，运行时零 ICU 依赖）。
 * 锚点表 PIN_ANCHORS 为 2026-11-02 修正版（D=大、补 W=蛙），系重生成 pin_index.bin 的算法源数据，勿用旧 22 锚。
 * 前 2 字母分桶：≥2 字母查询只扫小桶；词频权重排序与超量淘汰；与 web 行为逐一对齐。
 */
class PinIndex(
    private val max: Int = 8000,
    weights: Map<String, Long> = emptyMap(),
) {
    companion object {
        val PIN_ANCHORS: List<Pair<Char, String>> = listOf(
            'A' to "阿", 'B' to "八", 'C' to "嚓", 'D' to "大", 'E' to "讹", 'F' to "发",
            'G' to "噶", 'H' to "哈", 'J' to "击", 'K' to "喀", 'L' to "垃", 'M' to "妈",
            'N' to "拿", 'O' to "哦", 'P' to "啪", 'Q' to "期", 'R' to "然", 'S' to "撒",
            'T' to "塌", 'W' to "蛙", 'X' to "昔", 'Y' to "压", 'Z' to "匝",
        )
        // 多音字全部读音首字母（与默认重合的自动跳过；zh/ch/sh 归入单字母 z/c/s）
        val POLY_INITS: Map<Char, List<Char>> = mapOf(
            '长' to listOf('c', 'z'), '重' to listOf('c', 'z'), '乐' to listOf('l', 'y'),
            '行' to listOf('x', 'h'), '朝' to listOf('c', 'z'), '传' to listOf('c', 'z'),
            '单' to listOf('d', 's'), '解' to listOf('j', 'x'), '调' to listOf('d', 't'),
            '藏' to listOf('c', 'z'), '参' to listOf('c', 's'), '率' to listOf('l', 's'),
            '差' to listOf('c', 'z'), '系' to listOf('x', 'j'), '曾' to listOf('c', 'z'),
            '屏' to listOf('p', 'b'), '弹' to listOf('d', 't'),
        )

        fun isCjk(ch: Char): Boolean = ch in '㐀'..'䶿' || ch in '一'..'鿿'
    }

    /** 表已装即拼音可用（未装/读败=降级无联想，语义同原 coll==null） */
    val ok: Boolean get() = PinTable.ok

    /** 单字首字母：ASCII 数字字母原样小写；CJK 查预生成表；其余（标点等）返回 NUL 不占位 */
    private fun initialOf(ch: Char): Char {
        if (ch in '0'..'9' || ch in 'a'..'z') return ch
        if (ch in 'A'..'Z') return 'a' + (ch - 'A')
        if (isCjk(ch)) return PinTable.initialOf(ch)
        return 0.toChar()
    }

    private fun variantsOf(word: String): List<String> {
        val chars = word.toList()
        val base = buildString { for (c in chars) initialOf(c).takeIf { it != 0.toChar() }?.let { append(it) } }
        val out = mutableListOf(base)
        var i = 0
        while (i < chars.size && out.size < 4) {
            for (a in POLY_INITS[chars[i]] ?: emptyList()) {
                if (base.getOrNull(i) == a) continue
                val v = if (i < base.length) base.substring(0, i) + a + base.substring(i + 1)
                        else base + a
                if (!out.contains(v)) out.add(v)
                if (out.size >= 4) break
            }
            i++
        }
        return out
    }

    private val words = mutableListOf<String>()
    private val py = HashMap<String, List<String>>()          // word → 变体数组
    private val seq = HashMap<String, Int>()                  // word → 加入序（淘汰时同权重保新）
    private val wf = HashMap<String, Long>()                  // word → 词频权重
    private val buckets = HashMap<String, MutableSet<String>>() // 前2字母 → words
    private var order = 0

    init {
        for ((w, n) in weights) if (n != 0L) wf[w] = n
    }

    private fun bucketAdd(w: String, v: String) {
        val k = if (v.length >= 2) v.substring(0, 2) else if (v.isEmpty()) "_" else v
        buckets.getOrPut(k) { LinkedHashSet() }.add(w)
    }

    private fun evict() {
        if (words.size <= max) return
        val sorted = words.sortedWith(compareBy<String> { wf[it] ?: 0L }.thenBy { seq[it] ?: 0 })
        val remove = sorted.take(words.size - max).toHashSet()
        words.removeAll(remove)
        for (w in remove) {
            for (v in py[w] ?: emptyList()) {
                val k = if (v.length >= 2) v.substring(0, 2) else if (v.isEmpty()) "_" else v
                buckets[k]?.remove(w)
            }
            py.remove(w); seq.remove(w); wf.remove(w)
        }
    }

    // 用户 2026-11-06 走查 #8：persist 落盘移后台线程后与主线程 addWords 并发 →
    // 全部公开读写面 @Synchronized（evict 私有由 add 持锁可重入），并暴露一致性拷贝 snapshot 供后台编码
    @Synchronized fun add(word: String?): Boolean {
        val w = (word ?: "").trim()
        if (w.isEmpty() || py.containsKey(w)) return false
        words.add(w); seq[w] = ++order
        val vs = variantsOf(w); py[w] = vs
        for (v in vs) bucketAdd(w, v)
        evict()
        return true
    }

    @Synchronized fun addMany(list: List<String>?) { for (w in list ?: emptyList()) add(w) }

    @Synchronized fun bump(word: String?, d: Long = 1) {
        val w = (word ?: "").trim()
        if (w.isNotEmpty()) wf[w] = (wf[w] ?: 0L) + d
    }

    private val rank: Comparator<String> = compareByDescending<String> { wf[it] ?: 0L }.thenByDescending { seq[it] ?: 0 }

    // minor#8 走查：suggest 高频调用（联想逐键触发），字母校验模式提升为常量免逐调用现编
    private val alphaRe = Regex("^[a-z]+$")

    /** 首字母前缀联想：≥2 字母只扫前缀桶；按权重降序、同权重新词优先，上限 limit */
    @Synchronized fun suggest(q: String?, limit: Int = 30): List<String> {
        val ql = (q ?: "").trim().lowercase(Locale.ROOT)
        if (ql.isEmpty() || !alphaRe.matches(ql) || !ok) return emptyList()
        val cand: List<String> = if (ql.length >= 2) (buckets[ql.substring(0, 2)] ?: emptySet()).toList() else words
        val hits = cand.filter { w -> py[w]?.any { it.startsWith(ql) } == true }
        return hits.sortedWith(rank).take(limit)
    }

    /** 模糊召回：首字母串包含查询的「相关词」（非前缀也算，如 ffczz 含 fcz） */
    @Synchronized fun fuzzy(q: String?, limit: Int = 30): List<String> {
        val ql = (q ?: "").trim().lowercase(Locale.ROOT)
        if (ql.isEmpty() || !ok) return emptyList()
        val hits = words.filter { w -> py[w]?.any { it.contains(ql) } == true }
        return hits.sortedWith(rank).take(limit)
    }

    @Synchronized fun top(n: Int): List<String> = words.sortedWith(rank).take(n)

    @Synchronized fun initials(word: String?): String =
        buildString { for (c in (word ?: "")) initialOf(c).takeIf { it != 0.toChar() }?.let { append(it) } }

    @Synchronized fun variants(word: String?): List<String> = py[(word ?: "").trim()] ?: emptyList()

    @Synchronized fun list(): List<String> = words.toList()
    @Synchronized fun size(): Int = words.size
    @Synchronized fun weightsMap(): Map<String, Long> = wf.toMap()

    /** 后台落盘用一致性拷贝：词表+权重各自独立快照，返回后与实例变更互不影响 */
    @Synchronized fun snapshot(): Pair<List<String>, Map<String, Long>> = words.toList() to HashMap(wf)
}
