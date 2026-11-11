package com.tzk1986.libretvaz.data

import android.content.Context
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 源自维护引擎（web sourceGuard/refreshSubsIfStale/probeRestBackground/pruneDeadSources 等价）：
 * 启动 1.5s 后跑一轮（MainActivity 触发）——清死满 7 天的源 → 24h 订阅自动重拉合并 →
 * 当前源无档案或超 6h 复检 → 当前源失效自动切最快健康源（跳过专区）→ 后台补测其余源。
 * 「自动维护」关（autoMaintain=false）时整体跳过；互斥锁防重入。
 */
object SourceGuard {

    // E46（审计 2026-11-11）：14 仓预设迁 SubRepos.DEFAULT——清单用户可编（源管理「✎ 编辑预设」），
    // 守护补录改读 SubRepos.editable(settings.subReposRaw)，不再静默合并不可删的硬编码

    private const val BG_FRESH_MS = 60_000L
    private val guardMutex = Mutex()

    suspend fun run(context: Context, store: SourceStore, settings: SettingsStore) {
        if (!settings.autoMaintain) return
        if (guardMutex.isLocked) return
        guardMutex.withLock {
            val health = HealthStore(context)
            val repo = CmsRepository()
            pruneDeadSources(store, context)
            refreshSubsIfStale(store, settings, context)
            liveCheck(store, settings, context)
            // P0-1 坐标统一：siteIdx=点播池（vodPool 非专区）下标，守护全链按池定位/写回
            val pool = store.vodPool()
            if (pool.isEmpty()) return
            var idx = settings.siteIdx.coerceIn(0, pool.size - 1)
            var s = pool.getOrNull(idx)
            val h = s?.let { health.of(it.url) }
            if (s != null && (h == null || !h.ok || System.currentTimeMillis() - h.ts > HealthStore.RECHECK_MS))
                health.probeOne(repo, s)
            s = store.vodPool().getOrNull(idx)
            if (s != null && health.isDead(s.url)) {
                var best = pickBestSource(store, health)
                if (best < 0) {
                    bgProbe(store, health, repo)
                    best = pickBestSource(store, health)
                }
                if (best >= 0) {
                    settings.siteIdx = best
                    val name = store.vodPool().getOrNull(best)?.name
                    note("当前源已失效，自动切换到「$name」（翻页或重进后生效）")
                } else note("所有点播源均不可用：请检查网络，或在源管理重新导入")
            }
            bgProbe(store, health, repo)
        }
    }

    /** 死满 7 天清理（专区豁免）；有移除时记日志（web pruneDeadSources 弹 toast，此处按用户要求静默化） */
    fun pruneDeadSources(store: SourceStore, context: Context) {
        val vod = store.vodSources()
        val (keep, removed) = HealthStore(context).pruneDead(vod)
        if (removed > 0) {
            store.replaceVod(keep)
            val settings = SettingsStore(context)
            // P0-1：钳位按池尺寸（siteIdx=池下标；旧代码按全量会漏钳缩小的池）
            if (settings.siteIdx >= keep.count { !it.isAdult }) settings.siteIdx = 0
            note("已清理 $removed 个连续失效满 7 天的源（翻页或重进后生效）")
        }
    }

    /** 预设订阅补录 + 24h 自动重拉合并（web refreshSubsIfStale 等价） */
    private suspend fun refreshSubsIfStale(store: SourceStore, settings: SettingsStore, context: Context) {
        val have = store.subs().toMutableSet()
        val missing = SubRepos.editable(settings.subReposRaw).map { PerfCore.norm(it.second) }.filter { it !in have }
        if (missing.isNotEmpty()) {
            missing.forEach { addSubPref(context, it) }
            settings.subsTs = 0L
        }
        if (store.vodSources().isEmpty() && settings.subsTs != 0L) return
        val now = System.currentTimeMillis()
        if (!SubsGate.due(now, settings.subsTs)) return
        // P1-3：戳由结果定——任一订阅拉成功才消费满 24h，全败落退避戳 30 分钟后重试（旧式先写后刷=弱网整轮失败仍等一天）
        val (added, anyOk) = store.refreshSubs()
        settings.subsTs = SubsGate.stamp(now, anyOk)
        if (added > 0) note("订阅自动更新：新增 $added 个源（翻页或重进后生效）")
        if (!anyOk) note("订阅重拉全部失败，${SubsGate.BACKOFF_MS / 60_000} 分钟后自动重试")
    }

    /**
     * 直播源 24h 探活（B16 端上维护）：逐源拉表验证可达且非空，仅提示不自动删
     * （直播源少且用户手配，删源风险高；asset:// 内置种子恒本地成功）。
     */
    private suspend fun liveCheck(store: SourceStore, settings: SettingsStore, context: Context) = coroutineScope {
        val now = System.currentTimeMillis()
        if (!SubsGate.due(now, settings.liveTs)) return@coroutineScope
        val live = store.liveSources()
        if (live.size < 2) { settings.liveTs = SubsGate.stamp(now, ok = true); return@coroutineScope } // 空/单源无对比意义，消费满周期
        val repo = LiveRepository(context)
        val sem = Semaphore(3)
        val dead = live.map { s ->
            async(Dispatchers.IO) {
                sem.withPermit { runCatching { repo.load(s).channels.isNotEmpty() }.getOrDefault(false) }
            }
        }.count { !it.await() }
        // P1-3：全灭=大概率本机断网，落退避戳短周期重试而非再等 24h（部分可达属源本身故障，正常消费周期）
        settings.liveTs = SubsGate.stamp(now, ok = dead < live.size)
        if (dead > 0) note("直播源健康检查：$dead/${live.size} 个源暂不可达（下个周期自动重试）")
    }

    private fun addSubPref(context: Context, url: String) {
        val prefs = context.applicationContext.getSharedPreferences("libretvaz_sources", Context.MODE_PRIVATE)
        val arr = org.json.JSONArray(prefs.getString("subs", "[]") ?: "[]")
        val list = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        if (url !in list) { list.add(url); prefs.edit().putString("subs", org.json.JSONArray(list).toString()).apply() }
    }

    /** 最快健康非专区源下标（web pickBestSource 等价），无则 -1 */
    /** 池下标（P0-1：siteIdx 坐标系=vodPool，本函数返回序与池一致，可直接写回 settings.siteIdx） */
    private fun pickBestSource(store: SourceStore, health: HealthStore): Int {
        val ranked = store.vodPool().withIndex()
            .filter { (_, s) -> health.of(s.url)?.ok == true && (health.of(s.url)?.fails ?: 0) < HealthStore.DEAD_FAILS }
            .sortedBy { health.of(it.value.url)?.ms ?: Long.MAX_VALUE }
        return ranked.firstOrNull()?.index ?: -1
    }

    /** 后台补测（web probeRestBackground 等价）：并发 2，60s 内已测跳过 */
    private suspend fun bgProbe(store: SourceStore, health: HealthStore, repo: CmsRepository) = coroutineScope {
        val sources = store.vodSources()
        val sem = Semaphore(2)
        val now = System.currentTimeMillis()
        sources.filter { health.of(it.url)?.let { h -> now - h.ts < BG_FRESH_MS } != true }.forEach { s ->
            launch(Dispatchers.IO) { sem.withPermit { runCatching { health.probeOne(repo, s) } } }
        }
    }

    /** 后台维护提示静默化（用户 2026-11-04 定稿，有意偏离 web toast）：不弹 Toast 防打断直播/播放，只记「guard」日志供设置页查看 */
    private fun note(msg: String) = ErrLog.add("guard", msg)
}
