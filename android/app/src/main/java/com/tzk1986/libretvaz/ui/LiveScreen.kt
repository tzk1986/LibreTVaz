package com.tzk1986.libretvaz.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tzk1986.libretvaz.data.Epg
import com.tzk1986.libretvaz.data.LiveChannel
import com.tzk1986.libretvaz.data.LiveCtx
import com.tzk1986.libretvaz.data.LiveGeo
import com.tzk1986.libretvaz.data.LivePlaylist
import com.tzk1986.libretvaz.data.LiveRepository
import com.tzk1986.libretvaz.data.LiveStore
import com.tzk1986.libretvaz.data.SourceStore
import com.tzk1986.libretvaz.data.perf.PerfCore
import kotlinx.coroutines.delay

@Composable
fun LiveScreen(
    store: SourceStore,
    onBack: () -> Unit,
    onPlay: (idx: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val live = remember { LiveStore(context) }
    val sources = remember { store.liveSources() }
    val repo = remember { LiveRepository(context) }
    // web Live.srcIdx = LS('fongmi_live_idx')：当前直播源下标跨会话记忆
    var selectedIndex by remember {
        mutableIntStateOf(live.srcIdx().coerceIn(0, maxOf(0, sources.size - 1)))
    }
    var playlist by remember { mutableStateOf<LivePlaylist?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var group by remember { mutableStateOf<String?>(null) } // LiveStore.LIVE_FAV_GROUP=★收藏伪组
    var cat by remember(selectedIndex) { mutableStateOf<String?>(null) } // B16 国家分类（中国/日本/…/其他；null=全部）
    var epgIdx by remember { mutableStateOf<PerfCore.EpgIndex?>(null) }
    var epgNote by remember { mutableStateOf<String?>(null) } // 顶栏提示：未配置/未取到
    var epgChan by remember { mutableStateOf<LiveChannel?>(null) } // 节目单弹窗频道
    var minuteTick by remember { mutableIntStateOf(0) }
    var favUrls by remember(selectedIndex) { mutableStateOf(live.favUrls(selectedIndex)) }
    var cur by remember { mutableIntStateOf(LiveCtx.cur) } // 正在播出台高亮（web Live.cur .on）

    BackHandler { onBack() }

    LaunchedEffect(selectedIndex, sources) {
        val src = sources.getOrNull(selectedIndex) ?: return@LaunchedEffect
        loading = true; error = null; playlist = null; group = null
        runCatching { repo.load(src) }
            .onSuccess { playlist = it }
            .onFailure { e ->
                error = "加载失败：${e.message}"
                // 订阅地址不可达/超时=可预期，仅未知错误进日志（用户 2026-11-03）
                if (!com.tzk1986.libretvaz.data.ErrLog.isExpectedNet(e)) {
                    com.tzk1986.libretvaz.data.ErrLog.add("live", "${src.name} ${src.url} → ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        loading = false
        // B16 默认落「中国」分类（无则首个非空分类；全无码订阅=全部）
        val pl = playlist
        if (pl != null) {
            val cs = LiveGeo.partition(pl.channels).map { it.first }
            cat = if ("中国" in cs) "中国" else cs.firstOrNull()
        }
    }

    /** 起播=web playChan：填运行时上下文供播放页换台/选台，再交路由 */
    fun playChan(i: Int) {
        val pl = playlist ?: return
        val src = sources.getOrNull(selectedIndex) ?: return
        LiveCtx.srcIdx = selectedIndex
        LiveCtx.srcName = src.name
        LiveCtx.chans = pl.channels
        LiveCtx.favOnly = cat == LiveStore.LIVE_FAV_GROUP
        LiveCtx.cat = if (LiveCtx.favOnly) null else cat
        LiveCtx.group = group
        LiveCtx.favUrls = favUrls
        LiveCtx.epgIdx = epgIdx
        LiveCtx.cur = i
        cur = i
        live.setSrcIdx(selectedIndex)
        onPlay(i)
    }

    // O2 进页自动续播（web loadLiveList→playChan(start)）：上次台属本源且仍存在则续播，否则频道 0
    LaunchedEffect(playlist) {
        val pl = playlist ?: return@LaunchedEffect
        if (pl.channels.isEmpty() || !LiveCtx.wantAuto) return@LaunchedEffect
        LiveCtx.wantAuto = false
        val last = live.last()
        val idx = if (last != null && last.first == selectedIndex) pl.channels.indexOfFirst { it.url == last.second } else -1
        playChan(if (idx >= 0) idx else 0)
    }

    // EPG 异步拉取不阻塞频道列表（web ensureEpg 等价，5 分钟缓存）
    LaunchedEffect(selectedIndex, sources) {
        val url = sources.getOrNull(selectedIndex)?.epg
        if (url.isNullOrBlank()) { epgIdx = null; epgNote = "当前直播源未配置节目单" }
        else {
            val idx = Epg.load(url)
            if (sources.getOrNull(selectedIndex)?.epg == url) { // 加载期间切了源则丢弃
                epgIdx = idx
                epgNote = if (idx == null) "节目单未取到（网络/格式）" else null
            }
        }
    }

    // 顶栏/行内「正在」每分钟自然滚动（web epgTimer 等价）
    LaunchedEffect(Unit) {
        while (true) { delay(60_000); minuteTick++ }
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TmBtn(onClick = onBack) { Text("返回") }
            Text("看电视", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            epgNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
        }

        if (sources.isEmpty()) {
            Text("尚未配置直播源，请到「源管理」添加或导入订阅。")
            return@Column
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources.mapIndexed { i, s -> i to s }) { (i, s) ->
                TmChip(
                    selected = i == selectedIndex,
                    onClick = {
                        selectedIndex = i
                        LiveCtx.wantAuto = true // web 切源 renderLive→自动起播
                        LiveCtx.cur = -1; cur = -1
                    },
                    label = s.name,
                )
            }
        }

        val pl = playlist
        when {
            loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            pl != null -> {
                val hasFav = favUrls.isNotEmpty()
                // B16 国家分类行：★收藏(虚拟跨类)+全部+中/日/韩/美/欧/其他（仅列非空，展示序固定）
                val cats = LiveGeo.partition(pl.channels).map { it.first }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasFav) item {
                        TmChip(
                            selected = cat == LiveStore.LIVE_FAV_GROUP,
                            onClick = { cat = LiveStore.LIVE_FAV_GROUP; group = null },
                            label = LiveStore.LIVE_FAV_GROUP,
                        )
                    }
                    if (cats.size > 1) {
                        item {
                            TmChip(selected = cat == null, onClick = { cat = null; group = null }, label = "全部")
                        }
                        items(cats) { c ->
                            TmChip(selected = c == cat, onClick = { cat = c; group = null }, label = c)
                        }
                    }
                }
                // 二级：组（group-title）chip，取当前分类内成员
                val favOnly = cat == LiveStore.LIVE_FAV_GROUP
                val catChans = pl.channels.filterIndexed { i, ch ->
                    when {
                        favOnly -> favUrls.contains(ch.url)
                        cat == null -> true
                        else -> LiveGeo.catOf(ch.tvgId) == cat
                    }
                }
                val catGroups = pl.groups.filter { g -> catChans.any { it.group == g } }
                if (catGroups.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            TmChip(selected = group == null, onClick = { group = null }, label = "全部")
                        }
                        items(catGroups) { g ->
                            TmChip(selected = g == group, onClick = { group = g }, label = g)
                        }
                    }
                }
                // 用户 2026-11-06 走查 #5：原 indexOf/indexOfFirst/行内 count 三重 O(n²) →
                // remember 单次遍历预计算可见下标表+逐名线数表（cat/group/fav 谓词与原链逐条等价；
                // B16 同名多线合并语义保留：只列首个，其余作备用线路）
                val (visIdx, lineCounts) = remember(pl, cat, group, favUrls) {
                    val first = HashMap<String, Int>()
                    val counts = HashMap<String, Int>()
                    pl.channels.forEachIndexed { i, ch ->
                        if (!first.containsKey(ch.name)) first[ch.name] = i
                        counts[ch.name] = (counts[ch.name] ?: 0) + 1
                    }
                    val fav = cat == LiveStore.LIVE_FAV_GROUP
                    val idxs = pl.channels.indices.filter { i ->
                        val ch = pl.channels[i]
                        val inCat = when {
                            fav -> favUrls.contains(ch.url)
                            cat == null -> true
                            else -> LiveGeo.catOf(ch.tvgId) == cat
                        }
                        inCat && (group == null || ch.group == group) && first[ch.name] == i
                    }
                    idxs to counts
                }
                Text("共 ${visIdx.size} 个频道", style = MaterialTheme.typography.bodySmall)
                minuteTick // 每分钟订阅刷新行内「正在」
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(visIdx) { i ->
                        val ch = pl.channels[i]
                        val nLines = lineCounts[ch.name] ?: 1
                        ChannelRow(
                            channel = ch,
                            now = Epg.now(epgIdx, ch.name),
                            playing = i == cur,
                            lines = nLines,
                            fav = favUrls.contains(ch.url),
                            onFav = {
                                val added = live.toggleFav(selectedIndex, ch)
                                favUrls = live.favUrls(selectedIndex)
                                Toast.makeText(context, if (added) "已收藏频道" else "已取消收藏频道", Toast.LENGTH_SHORT).show()
                            },
                            onClick = { playChan(i) },
                            onEpg = if (epgIdx != null) ({ epgChan = ch }) else null,
                        )
                    }
                }
            }
        }
    }

    epgChan?.let { ch ->
        val list = Epg.dayList(epgIdx, ch.name)
        val now = Epg.now(epgIdx, ch.name)
        val next = list.firstOrNull { it.t > PerfCore.nowNum() }
        TmDialog(
            onDismissRequest = { epgChan = null },
            confirmButton = {},
            dismissButton = { TmBtn(onClick = { epgChan = null }) { Text("关闭") } },
            title = { Text("${ch.name} · 今日节目单") },
            text = {
                if (list.isEmpty()) Text("本频道今日无节目数据（台名未匹配或节目单无当日内容）")
                else LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(list) { p ->
                        val mark = when {
                            p === now -> " ·正在"
                            p === next -> " ·即将"
                            else -> ""
                        }
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                "${Epg.hm(p.t)}${if (p.s > 0) "–${Epg.hm(p.s)}" else ""}  ${p.title}$mark",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (p === now) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                            if (p === now && p.desc.isNotEmpty()) Text(
                                p.desc.take(160),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ChannelRow(
    channel: LiveChannel,
    now: PerfCore.EpgProgram?,
    playing: Boolean,
    lines: Int = 1,
    fav: Boolean,
    onFav: () -> Unit,
    onClick: () -> Unit,
    onEpg: (() -> Unit)? = null,
) {
    // 用户 2026-11-09 #133：整行遥控焦点=白描边+40%红底（TVBox 统一）
    var rowF by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .onFocusEvent { rowF = it.isFocused }
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .then(if (rowF) Modifier.border(com.tzk1986.libretvaz.ui.theme.TvColors.StrokeW, Color.White, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) else Modifier)
            .background(if (rowF) com.tzk1986.libretvaz.ui.theme.TvColors.Red40 else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // web 行 on 高亮=正在播出台；★ 行尾切换（触屏替代 web 面板顶 favCur 按钮）
        Text(
            channel.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (now != null) Text(
            "正在 ${Epg.hm(now.t)} ${now.title}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (lines > 1) Text(
            "${lines}线",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        channel.group?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        Text(
            if (fav) "★" else "☆",
            style = MaterialTheme.typography.titleMedium,
            color = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            // 走查 #14：TV 点★难命中——glyph 触控面撑到 ~42dp（foundation 1.12.1 无
            // minimumInteractiveTouchArea，按简报用内层 padding 等效；padding 须在 clickable 内侧才计入手势界）
            modifier = Modifier.clickable(onClick = onFav)
                .tmFocusBorder(focusedBg = com.tzk1986.libretvaz.ui.theme.TvColors.Red40)
                .padding(horizontal = 8.dp, vertical = 14.dp),
        )
        onEpg?.let {
            TmBtn(onClick = it) { Text("节目单") }
        }
    }
}
