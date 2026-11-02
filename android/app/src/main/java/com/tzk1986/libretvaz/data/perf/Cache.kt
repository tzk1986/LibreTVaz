package com.tzk1986.libretvaz.data.perf

/**
 * 内存 LRU + TTL 分片缓存（B0 地基；等价 web cacheSet/cacheGet/cacheRaw 语义）。
 * 键前缀对齐 web：types: / aggs: / detail: / zsp: / search: / vod: / mrg: / adsp:。
 * 条目记录写入时间 t，新鲜度由调用方按档位判定（aggTtl/searchTtl/CACHE_TIERS→B11）。
 * 磁盘持久层见 B11 DiskCache.kt。
 */
class MemCache<V>(private val maxEntries: Int = 400) {
    data class Entry<V>(val t: Long, val v: V)

    // accessOrder=true：get 也刷新序，超限从最久未用逐出
    private val map = object : LinkedHashMap<String, Entry<V>>(16, 0.75f, true) {}

    fun getRaw(key: String): Entry<V>? = synchronized(map) { map[key] }

    /** TTL 内命中返回值，过期/缺失返回 null */
    fun get(key: String, ttl: Long, now: Long = System.currentTimeMillis()): V? {
        val e = getRaw(key) ?: return null
        return if (now - e.t <= ttl) e.v else null
    }

    fun set(key: String, value: V, now: Long = System.currentTimeMillis()) = synchronized(map) {
        map[key] = Entry(now, value)
        while (map.size > maxEntries) {
            val it = map.keys.iterator()
            if (!it.hasNext()) break
            it.next(); it.remove()
        }
    }

    fun remove(key: String) = synchronized(map) { map.remove(key) }

    fun removePrefix(prefix: String) = synchronized(map) {
        val ks = map.keys.filter { it.startsWith(prefix) }
        ks.forEach { map.remove(it) }
        ks.size
    }

    fun clear() = synchronized(map) { map.clear() }

    fun size(): Int = synchronized(map) { map.size }
}

/**
 * 同 key 并发请求合并为一个执行，完成后自动清槽（等价 web createDedupe）。
 * 首个调用者执行 invoke 并广播结果，后来者挂接等待；失败同样广播并清槽。
 */
class KeyDedupe<T> {
    private val inflight = HashMap<String, kotlinx.coroutines.CompletableDeferred<T>>()

    suspend fun run(key: String, invoke: suspend () -> T): T {
        while (true) {
            val existing = synchronized(inflight) { inflight[key] }
            if (existing != null) return existing.await() // 挂起在锁外
            val d = kotlinx.coroutines.CompletableDeferred<T>()
            val raced = synchronized(inflight) {
                if (key in inflight) inflight[key]!! else { inflight[key] = d; null }
            }
            if (raced != null) continue // 竞态输了：等赢家，下一轮 await
            try {
                val v = invoke()
                synchronized(inflight) { if (inflight[key] === d) inflight.remove(key) }
                d.complete(v)
                return v
            } catch (e: Throwable) {
                synchronized(inflight) { if (inflight[key] === d) inflight.remove(key) }
                d.completeExceptionally(e)
                throw e
            }
        }
    }
}
