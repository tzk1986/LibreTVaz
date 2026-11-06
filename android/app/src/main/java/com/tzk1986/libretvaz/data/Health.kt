package com.tzk1986.libretvaz.data

import android.content.Context
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** 单源健康档案（对齐 web fongmi_health 条目）：ms=null 未测出；deadSince=连续失效满 7 天移除的起算点 */
data class HealthRec(
    val ok: Boolean,
    val ms: Long?,
    val fails: Int,
    val err: String?,
    val ts: Long,
    val deadSince: Long?,
)

/** 测速墙行：ms=null 表示检测中/未测 */
data class ProbeRow(val name: String, val url: String, val ms: Long?, val state: String)

/**
 * 源自维护健康档案（web 健康模型 fongmi_health 的 Kotlin 等价）：
 * ac=list 往返探活记 ms/失败次数，连续失败 3 次标死，死满 7 天自动移除（专区源豁免——
 * 专区接口波动频繁，清掉后种子不会自动补回）；orderSources/聚合池按 ms 排序消费。
 */
class HealthStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("libretvaz_health", Context.MODE_PRIVATE)
    private val map = ConcurrentHashMap<String, HealthRec>()

    init {
        runCatching {
            val root = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
            for (k in root.keys()) {
                val r = root.optJSONObject(k) ?: continue
                map[k] = HealthRec(
                    ok = r.optBoolean("ok"),
                    ms = r.optLong("ms", -1L).takeIf { it >= 0 },
                    fails = r.optInt("fails"),
                    err = r.optString("err").ifBlank { null },
                    ts = r.optLong("ts"),
                    deadSince = r.optLong("deadSince", -1L).takeIf { it >= 0 },
                )
            }
        }
    }

    // 用户 2026-11-06 走查 #7：recordOk/Fail 每条全表重编码+写盘（probeAll 批测=O(N²)）→ 500ms 防抖后台合并（同 SearchShards 模式）
    private val persistExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "health-persist").apply { isDaemon = true }
    }
    private var pendingSave: java.util.concurrent.ScheduledFuture<*>? = null

    private fun save() {
        synchronized(persistExec) {
            pendingSave?.cancel(false)
            pendingSave = persistExec.schedule({ runCatching { writeAll() } }, 500L, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private fun writeAll() {
        val root = JSONObject()
        map.forEach { (k, h) ->
            root.put(k, JSONObject().apply {
                put("ok", h.ok)
                put("ms", h.ms ?: -1L)
                put("fails", h.fails)
                put("err", h.err ?: "")
                put("ts", h.ts)
                put("deadSince", h.deadSince ?: -1L)
            })
        }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    fun of(url: String): HealthRec? = map[PerfCore.norm(url)]

    fun isDead(url: String): Boolean = (of(url)?.fails ?: 0) >= DEAD_FAILS

    fun recordOk(url: String, ms: Long) {
        map[PerfCore.norm(url)] = HealthRec(true, ms, 0, null, System.currentTimeMillis(), null)
        save()
    }

    fun recordFail(url: String, err: String) {
        val old = of(url)
        val fails = (old?.fails ?: 0) + 1
        val deadSince = if (fails >= DEAD_FAILS) old?.deadSince ?: System.currentTimeMillis() else null
        map[PerfCore.norm(url)] = HealthRec(false, old?.ms, fails, err.take(60), System.currentTimeMillis(), deadSince)
        save()
    }

    /** 死满 7 天的普通源移除（专区源只标死不清理）；返回保留表与移除数 */
    fun pruneDead(vod: List<VodSource>): Pair<List<VodSource>, Int> {
        val now = System.currentTimeMillis()
        val keep = vod.filter { s ->
            if (s.isAdult) return@filter true
            val h = of(s.url)
            h?.deadSince?.let { now - it > DEAD_REMOVE_MS } != true
        }
        return keep to (vod.size - keep.size)
    }

    /** 单源探活（对齐 web probeOne/probeAll 行）：ac=list 分类 + 首分类列表各验一次，往返 ms 记账 */
    suspend fun probeOne(repo: CmsRepository, s: VodSource): ProbeRow {
        val t0 = System.currentTimeMillis()
        val types = runCatching { repo.types(s) }.getOrDefault(emptyList())
        val listCount = if (types.isNotEmpty())
            runCatching { repo.listByType(s, types[0].id, 1) }.getOrDefault(emptyList()).size
        else 0
        val ms = System.currentTimeMillis() - t0
        val state = when {
            types.isEmpty() -> "ac=list 与列表均无数据 → 源可能已失效"
            listCount > 0 -> "OK：${types.size} 分类 / 列表 $listCount 条"
            else -> "仅分类可用，列表为空"
        }
        if (types.isNotEmpty()) recordOk(s.url, ms) else recordFail(s.url, state)
        return ProbeRow(s.name, s.url, ms, state)
    }

    /** 并发 4 逐源检测，完成一源回调一行（测速墙原位刷新用） */
    suspend fun probeAll(
        repo: CmsRepository,
        sources: List<VodSource>,
        onRow: (index: Int, row: ProbeRow) -> Unit,
    ) = coroutineScope {
        val sem = Semaphore(CONCURRENCY)
        sources.mapIndexed { i, s ->
            launch(Dispatchers.IO) {
                sem.withPermit { onRow(i, probeOne(repo, s)) }
            }
        }
        Unit
    }

    companion object {
        // 用户 2026-11-06 走查 #7：10 处调用点各 new（每次全表 JSON 解析）→ 进程单例；
        // 私有构造下外部 HealthStore(context) 语法自动落此处 invoke，调用点零改动
        @Volatile private var inst: HealthStore? = null

        operator fun invoke(context: Context): HealthStore = inst ?: synchronized(this) {
            inst ?: HealthStore(context.applicationContext).also { inst = it }
        }

        const val DEAD_FAILS = 3
        const val DEAD_REMOVE_MS = 7 * 24 * 3600_000L
        const val RECHECK_MS = 6 * 3600_000L
        private const val CONCURRENCY = 4
        private const val KEY = "health"
    }
}
