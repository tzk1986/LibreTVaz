package com.tzk1986.libretvaz.data

import com.tzk1986.libretvaz.data.perf.PerfCore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 搜索分片双层缓存（原 ui/CollectScreen 顶层对象，用户 2026-11-06 走查 #2/#3 批准迁至 data 层）：
 * 外层 ck→(norm(url)→分片) 按访问序 LRU 封顶 30 词（逐出只丢内存，盘上副本 shardsOf 可回灌）；
 * 落盘 persistSoon 500ms 防抖+后台单线程编码（原「每源到达整包重写」O(N²) 同步编解码消除）。
 */
object SearchShards {

    private const val MAX_KEYS = 30
    private const val DEBOUNCE_MS = 500L

    /** 盘读写接缝（生产接 DiskCache；单测注入桩） */
    @Volatile internal var sink: (String, Long, String) -> Unit = { k, t, p -> DiskCache.write(k, t, p) }
    @Volatile internal var source: (String) -> Pair<Long, String>? = { DiskCache.read(it) }

    private val mem = object : LinkedHashMap<String, ConcurrentHashMap<String, PerfCore.Shard<VodSummary>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ConcurrentHashMap<String, PerfCore.Shard<VodSummary>>>?) =
            size > MAX_KEYS
    }
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "search-shards-persist").apply { isDaemon = true } }
    private val pending = HashMap<String, ScheduledFuture<*>>()
    private val queued = HashMap<String, Map<String, PerfCore.Shard<VodSummary>>>()

    @Synchronized
    fun shardsOf(ck: String): ConcurrentHashMap<String, PerfCore.Shard<VodSummary>> =
        mem.getOrPut(ck) {
            ConcurrentHashMap<String, PerfCore.Shard<VodSummary>>().apply {
                runCatching {
                    val (_, payload) = source(ck) ?: return@runCatching
                    val o = org.json.JSONObject(payload)
                    for (u in o.keys()) {
                        val e = o.optJSONObject(u) ?: continue
                        val list = CmsCodecs.decSummaries(e.optJSONArray("v")?.toString() ?: continue) ?: continue
                        put(u, PerfCore.Shard(e.getLong("t"), list))
                    }
                }
            }
        }

    /** 排防抖落盘：按 ck 合并 500ms 内多次调用，快照当前表后交后台线程编码 */
    fun persistSoon(ck: String, map: Map<String, PerfCore.Shard<VodSummary>>) {
        val snap = LinkedHashMap(map)
        val task = Runnable { runCatching { sink(ck, System.currentTimeMillis(), encode(snap)) } }
        synchronized(pending) {
            pending[ck]?.cancel(false)
            pending[ck] = exec.schedule(task, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            queued[ck] = snap
        }
    }

    /** 立即冲全部在排条目（测试/退出前兜底） */
    fun flushAll() {
        val todo = synchronized(pending) {
            pending.keys.toList().map { ck -> Triple(ck, pending.remove(ck)!!, queued.remove(ck)!!) }
        }
        todo.forEach { (ck, fut, snap) ->
            fut.cancel(false)
            runCatching { sink(ck, System.currentTimeMillis(), encode(snap)) }
        }
    }

    /** 设置页「清理缓存」钩子（web cache_purge 等价：内存+磁盘分片一起清；内存清=LRU 表+在排任务） */
    fun clearAll() {
        // 终审 #3：先取消在排防抖并丢快照，否则清理后 500ms 内任务回写使分片复活
        synchronized(pending) {
            pending.values.forEach { it.cancel(false) }
            pending.clear(); queued.clear()
        }
        clearMem()
        DiskCache.removePrefix("search:")
    }

    @Synchronized
    internal fun clearMem() { mem.clear() }

    private fun encode(map: Map<String, PerfCore.Shard<VodSummary>>): String {
        val o = org.json.JSONObject()
        map.forEach { (u, sh) ->
            o.put(u, org.json.JSONObject().put("t", sh.t).put("v", org.json.JSONArray(CmsCodecs.encSummaries(sh.d))))
        }
        return o.toString()
    }

    /** 单测复位接缝 */
    internal fun resetForTests(sink: (String, Long, String) -> Unit, source: (String) -> Pair<Long, String>?) {
        synchronized(pending) { pending.values.forEach { it.cancel(false) }; pending.clear(); queued.clear() }
        this.sink = sink
        this.source = source
    }
}
