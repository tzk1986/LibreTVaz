package com.tzk1986.libretvaz.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.tzk1986.libretvaz.data.VodSummary
import com.tzk1986.libretvaz.data.VodType

/**
 * 墙类页签跨页记忆（用户 2026-11-04 修复「返回不按路径」）：
 * 导航为单状态条件渲染，进详情/播放再返回会重建 Browse/Zone，remember 态复位到默认「最新」。
 * 页签选择提升为进程级，返回即回原分类；进程终止复位属预期。
 * 2026-11-05 底部筛选面板：地区预设选择同样进程级——重开面板须显上次选中（只有刷新/选全部才重置）。
 */
object WallMem {
    val browseCat = mutableIntStateOf(-1) // 同 aggCat：-1=最新，0..3=固定四大类
    val zoneCat = mutableIntStateOf(-1)   // 资源口一级：-1=最新，0..=Zone.ZONE_CATS
    val zoneFlt = mutableIntStateOf(-1)   // 资源口二级筛选：-1=全部，0..=Zone.ZONE_FILTERS
    val aggPreset = mutableStateOf<Pair<String, List<String>>?>(null) // 聚合墙地区预设
    val typePreset = mutableStateOf<Pair<String, List<String>>?>(null) // 单源地区预设（preset）
    val typePresetSrv = mutableStateOf<List<VodType>?>(null)           // 对应 pSrv（服务端合并 ids）
    // 用户 2026-11-06 走查 #11：进详情/播放返回会重建 Browse，remember 态复位→墙清空重拉、滚动丢。
    // 墙数据/滚动位置按「墙 key」进程级保留（key 含 nonce 以外全部重触发参数；
    // 🔄nonce++ 与换源/切类/换预设自然换 key=干净首屏；聚合↔单源两 key 不串数据）
    // （minor#5 走查：browseDrawn 槽只写无读已删，seed 由 browseKeep 现算）
    val browseKeep = mutableStateOf<Pair<String, List<VodSummary>>?>(null)   // key→墙条目
    val browseScroll = mutableStateOf<Triple<String, Int, Int>?>(null)       // key→(index, offset)
    // 用户 2026-11-06：搜索结果墙同款（资源口/点播搜→点卡播放→返回不再整页重搜复位顶）；
    // 滚动用 map 分 tab 记忆（页内切源 Tab 不丢各自位置），快照单槽同 browseKeep 语义
    internal val collectKeep = mutableStateOf<Pair<String, CollectSnap>?>(null) // key(kw|zone|src)→结果快照
    internal val collectScroll = mutableStateMapOf<String, Pair<Int, Int>>()    // 右墙 key(kw|zone|src#tab)→(index, offset)
    internal val collectSrcScroll = mutableStateMapOf<String, Int>()             // 左源栏 key(kw|zone|src)→index
}

/** 方案A（用户 2026-11-06）：主动发起搜索时清搜索墙跨页记忆——返回=还原，再搜=干净首屏 */
internal fun dropCollectMem() {
    WallMem.collectKeep.value = null
    WallMem.collectScroll.clear()
    WallMem.collectSrcScroll.clear()
}

/** 搜索结果墙跨页快照（CollectScreen 重建后按 key 还原，免重搜复位） */
internal data class CollectSnap(
    val seq: List<Int>,
    val bySite: Map<Int, List<VodSummary>>,
    val tabIdx: Int?,
    val yearSort: YearOrder,
    val done: Int,
    val total: Int,
    val fails: Int,
    val noSources: Boolean,
)
