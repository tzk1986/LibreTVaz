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

    /** 订阅仓库预设（web fongmi.js PRESETS 对齐，源管理页共用；tvyuan=master 分支，main 已 404）。
     *  后 9 条来自 2026-11-10「极客小盒」接口合集 xlsx：全部经三级实测（本 app 同款协议：
     *  ac=videolist 搜索有结果 → vod_id 详情取 vod_play_url → 播放地址真拉流验视频流），
     *  剔除 18+/爬虫主导(type3/csp)/格式不兼容(jsm模板)/vicp穿透域/重复镜像后择优收录。 */
    val PRESETS = listOf(
        "noimank 多仓" to "https://gitlab.com/noimank/tvbox/-/raw/main/tvboxmuti.json",
        "Liucn 单仓" to "https://raw.liucn.cc/box/m.json",
        "Qist 综合" to "https://gh-proxy.com/https://raw.githubusercontent.com/qist/tvbox/master/0821.json",
        "tvyuan 体检聚合" to "https://gh-proxy.com/https://raw.githubusercontent.com/25175/tvyuan/master/tvbox_collect.json",
        "51源合集" to "https://raw.githubusercontent.com/fadai9991/tvbox-sub/main/tvbox.json",
        "Easy涛聚合" to "https://raw.githubusercontent.com/EasyXC/EasyTVbox/refs/heads/main/api/tao.json",
        "Clun 盒子" to "https://clun.top/box.json",
        "非凡 1024" to "https://raw.githubusercontent.com/jigedos/1024/master/jsm.json",
        "zheng 合集" to "https://raw.githubusercontent.com/zheng197600/tvbox/main/0820.json",
        "平安 2万" to "https://cnb.cool/cnlv/tv/-/git/raw/main/20000.json",
        "牛角 TV" to "https://raw.githubusercontent.com/niubihu1/tvbox-/main/1.json",
        "陆城影视" to "https://jianhancloud.cn/lcys/lcjk.json",
        "letian 盒子" to "https://raw.githubusercontent.com/letian1650/mybox/main/m.json",
        "猫 map" to "https://raw.githubusercontent.com/supermeguo/BoxRes/main/Myuse/catcr.json",
    )

    private const val SUBS_REFRESH_MS = 24 * 3600_000L
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
            val vod = store.vodSources()
            if (vod.isEmpty()) return
            var idx = settings.siteIdx.coerceIn(0, (vod.size - 1).coerceAtLeast(0))
            var s = vod.getOrNull(idx)
            val h = s?.let { health.of(it.url) }
            if (s != null && (h == null || !h.ok || System.currentTimeMillis() - h.ts > HealthStore.RECHECK_MS))
                health.probeOne(repo, s)
            s = store.vodSources().getOrNull(idx)
            if (s != null && health.isDead(s.url)) {
                var best = pickBestSource(store, health)
                if (best < 0) {
                    bgProbe(store, health, repo)
                    best = pickBestSource(store, health)
                }
                if (best >= 0) {
                    settings.siteIdx = best
                    val name = store.vodSources().getOrNull(best)?.name
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
            if (settings.siteIdx >= keep.size) settings.siteIdx = 0
            note("已清理 $removed 个连续失效满 7 天的源（翻页或重进后生效）")
        }
    }

    /** 预设订阅补录 + 24h 自动重拉合并（web refreshSubsIfStale 等价） */
    private suspend fun refreshSubsIfStale(store: SourceStore, settings: SettingsStore, context: Context) {
        val have = store.subs().toMutableSet()
        val missing = PRESETS.map { PerfCore.norm(it.second) }.filter { it !in have }
        if (missing.isNotEmpty()) {
            missing.forEach { addSubPref(context, it) }
            settings.subsTs = 0L
        }
        if (store.vodSources().isEmpty() && settings.subsTs != 0L) return
        val now = System.currentTimeMillis()
        if (now - settings.subsTs < SUBS_REFRESH_MS) return
        settings.subsTs = now
        val added = store.refreshSubs()
        if (added > 0) note("订阅自动更新：新增 $added 个源（翻页或重进后生效）")
    }

    /**
     * 直播源 24h 探活（B16 端上维护）：逐源拉表验证可达且非空，仅提示不自动删
     * （直播源少且用户手配，删源风险高；asset:// 内置种子恒本地成功）。
     */
    private suspend fun liveCheck(store: SourceStore, settings: SettingsStore, context: Context) = coroutineScope {
        val now = System.currentTimeMillis()
        if (now - settings.liveTs < SUBS_REFRESH_MS) return@coroutineScope
        settings.liveTs = now
        val live = store.liveSources()
        if (live.size < 2) return@coroutineScope // 空/单源无对比意义
        val repo = LiveRepository(context)
        val sem = Semaphore(3)
        val dead = live.map { s ->
            async(Dispatchers.IO) {
                sem.withPermit { runCatching { repo.load(s).channels.isNotEmpty() }.getOrDefault(false) }
            }
        }.count { !it.await() }
        if (dead > 0) note("直播源健康检查：$dead/${live.size} 个源暂不可达（下个周期自动重试）")
    }

    private fun addSubPref(context: Context, url: String) {
        val prefs = context.applicationContext.getSharedPreferences("libretvaz_sources", Context.MODE_PRIVATE)
        val arr = org.json.JSONArray(prefs.getString("subs", "[]") ?: "[]")
        val list = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        if (url !in list) { list.add(url); prefs.edit().putString("subs", org.json.JSONArray(list).toString()).apply() }
    }

    /** 最快健康非专区源下标（web pickBestSource 等价），无则 -1 */
    private fun pickBestSource(store: SourceStore, health: HealthStore): Int {
        val ranked = store.vodSources().withIndex()
            .filter { (_, s) -> !s.isAdult && health.of(s.url)?.ok == true && (health.of(s.url)?.fails ?: 0) < HealthStore.DEAD_FAILS }
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
