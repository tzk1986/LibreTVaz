package com.tzk1986.libretvaz.data

import com.tzk1986.libretvaz.data.perf.MemCache
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 收藏更新检测（web checkKeeps 等价）：进收藏页触发，非轮询。
 * 每件 30min 只联网一次（keepchk: 门缓存）；并发 3；拉详情比对集数/更新备注，
 * 与已存基线不同则标「已更新」，同时把基线推到最新。失败静默（可预期网络错不进日志）。
 */
object KeepChk {
    private const val TTL = 30 * 60_000L
    private val gate = DiskBacked(MemCache<String>(256), "keepchk:", { it }, { it })

    /** 返回是否有条目基线/徽标变化（调用方据此刷新列表） */
    suspend fun run(repo: CmsRepository, store: KeepStore, zone: Boolean): Boolean {
        val items = store.list(zone)
        if (items.isEmpty()) return false
        val sem = Semaphore(3)
        val chks = java.util.concurrent.ConcurrentHashMap<String, KeepChkResult>()
        withContext(Dispatchers.IO) {
            coroutineScope {
                items.map { x ->
                    async {
                        sem.withPermit {
                            val key = "keepchk:" + PerfCore.norm(x.vod.sourceUrl) + "|" + x.vod.vodId
                            if (gate.get(key, TTL) != null) return@withPermit
                            val src = VodSource(x.vod.sourceName, x.vod.sourceUrl, isAdult = x.vod.isAdult)
                            val d = runCatching { repo.detail(src, x.vod.vodId) }.getOrNull()
                                ?: return@withPermit
                            val eps = d.lines.maxOfOrNull { it.list.size } ?: 0
                            val last = d.remarks ?: ""
                            val u = x.eps != null && (eps > x.eps || (last.isNotEmpty() && !x.last.isNullOrEmpty() && last != x.last))
                            chks[x.vod.sourceUrl + "|" + x.vod.vodId] = KeepChkResult(eps, last, u)
                            gate.set(key,"1")
                        }
                    }
                }
            }
        }
        return store.applyChk(chks)
    }
}
