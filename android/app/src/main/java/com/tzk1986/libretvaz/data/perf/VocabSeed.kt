package com.tzk1986.libretvaz.data.perf

/**
 * 词库三层一体之「目录剧名全量种子」（等价 web seedVocabPool，2026-11-02 定稿）：
 * ①种子——至多 10 个健康源 × 至多 8 页 ac=videolist 剧名入池，达 min 早停，6h 节流防空爬；
 * ②增量——进墙/搜索/详情 addWords（调用方直连 PinIndex.add）；③热搜=同池 top()。
 * 健康源筛选在调用方（B5 Health），此处纯策略可单测。
 */
object VocabSeed {
    const val THROTTLE_MS = 6 * 3600_000L
    const val MAX_SOURCES = 10
    const val MAX_PAGES = 8

    /**
     * @param sources 健康源标识（名称/URL，透传给 fetchPage）
     * @param fetchPage 拉一页剧名（源, 页码）；异常由调用方兜成空列表
     * @return 本次完成时间戳；被节流或未执行返回 null
     */
    suspend fun run(
        sources: List<String>,
        fetchPage: suspend (source: String, pg: Int) -> List<String>,
        index: PinIndex,
        min: Int,
        lastSeedAt: Long,
        now: Long = System.currentTimeMillis(),
    ): Long? {
        if (lastSeedAt != 0L && now - lastSeedAt < THROTTLE_MS) return null
        outer@ for (s in sources.take(MAX_SOURCES)) {
            for (pg in 1..MAX_PAGES) {
                val names = try { fetchPage(s, pg) } catch (e: Exception) { emptyList() }
                if (names.isNotEmpty()) index.addMany(names)
                if (index.size() >= min) break@outer
            }
        }
        return now
    }
}
