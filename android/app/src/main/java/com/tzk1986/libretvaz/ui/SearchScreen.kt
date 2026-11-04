package com.tzk1986.libretvaz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tzk1986.libretvaz.data.*
import com.tzk1986.libretvaz.data.perf.PerfCore
import com.tzk1986.libretvaz.data.perf.VocabSeed
import kotlinx.coroutines.launch

/** 六列矩阵键盘：A-Z+0-9 共 36 键 6×6（web renderSearch KEYS 等价） */
private val KB_KEYS = ('A'..'Z').toSet() + ('0'..'9').toSet()
private val KB_ROWS = KB_KEYS.toList().chunked(6)

/** 词 chip：热门/关联/历史共用（用户 2026-11-04 终稿：全称直显——允许两行、禁省略号；点击=直接搜索） */
@Composable
private fun WordChip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 2, style = MaterialTheme.typography.bodyMedium) },
    )
}

/** 首页 FuncCard 同款小钮（用户 2026-11-04：搜索页样式统一首页——surfaceVariant 圆角10+labelMedium，不再用大 Button） */
@Composable
private fun HomeBtn(label: String, modifier: Modifier = Modifier, tight: Boolean = false, fill: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Box(
            modifier = (if (fill) Modifier.fillMaxWidth() else Modifier).padding(
                horizontal = if (tight) 2.dp else 10.dp,
                vertical = if (tight) 2.dp else 5.dp,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = if (tight) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 搜索输入页三栏（web renderSearch 等价，点播/资源口两池同构、数据全隔离）：
 * 左=本池搜索历史（竖排+清空）｜中=输入框+指定源+按钮组+36 键矩阵键盘｜
 * 右=单入口词流：空输入=热门搜索 top(40)，有输入=关联词（suggest→fuzzy 补足→截断词置顶带标）。
 * 发起搜索经 onCollect 跳结果页；远程输入钮暂留提示（B8 接扫码）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    store: SourceStore,
    zone: Boolean,
    onBack: () -> Unit,
    onCollect: (kw: String, srcSel: SpecSrc?) -> Unit,
    modifier: Modifier = Modifier,
    remoteKw: String? = null,
    onRemoteConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val repo = remember { CmsRepository() }
    val health = remember { HealthStore(context) }
    val vocab = remember { VocabPools.get(context) }
    // 触屏判定仅作 36 键默认态与是否自动聚焦的依据：触屏默认关内建键盘用系统输入法（TVBox 手机版同款，
    // 且横屏浮动 IME 不派发 insets 侦不到，键盘常显会裁按钮——用户手机实测）；非触屏默认开+进页自动聚焦供遥控直输
    val hasTouch = remember {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    }

    var query by remember { mutableStateOf("") }
    var srcSel by remember { mutableStateOf<SpecSrc?>(null) }
    var showSrcPick by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var kws by remember { mutableStateOf(vocab.kws(zone)) }
    val focus = remember { FocusRequester() }

    // 遥控器两段返回（用户 2026-11-04）：Back 先取消当前焦点选中，再按才返回上页；屏面返回钮保留供手机点按
    val focusManager = LocalFocusManager.current
    var backStage by remember { mutableIntStateOf(0) }
    BackHandler {
        if (backStage == 0) { focusManager.clearFocus(true); backStage = 1 }
        else onBack()
    }
    // 触屏不自动弹软键盘（TVBox 手机版交互：进页先看全布局，点输入框再出键盘）；非触屏 TV/投影保持进页即聚焦供遥控直输
    LaunchedEffect(Unit) { if (!hasTouch) runCatching { focus.requestFocus() } }

    // 进页词库种子（web seedVocab/seedZoneVocab 等价：本池健康源目录剧名，6h 节流，达标早停）
    LaunchedEffect(zone) {
        val min = if (zone) 1500 else 3000
        val pi = vocab.index(zone)
        if (pi.size() >= min) return@LaunchedEffect
        // web 同款排序：健康 ms 升序 + 当前源强制排首
        val all = store.vodSources()
        val curUrl = all.getOrNull(SettingsStore(context).siteIdx)?.url
        val pool = all.filter { it.isAdult == zone && !health.isDead(it.url) }
            .sortedBy { health.of(it.url)?.ms ?: 4000L }
            .sortedByDescending { it.url == curUrl }
        val byUrl = pool.associateBy { it.url }
        val t = VocabSeed.run(
            sources = pool.map { it.url }.take(VocabSeed.MAX_SOURCES),
            fetchPage = { u, pg ->
                val src = byUrl[u] ?: return@run emptyList()
                runCatching { repo.home(src, pg).map { it.name } }.getOrDefault(emptyList())
            },
            index = pi,
            min = min,
            lastSeedAt = vocab.seedAt(zone),
        )
        if (t != null) vocab.setSeedAt(t, zone)
        kws = vocab.kws(zone) // 触底重绘词流
    }

    fun search(w: String) {
        val t = w.trim()
        if (t.isEmpty()) { hint = "请先输入或点选关键词"; return }
        hint = null
        vocab.pushKw(t, zone)
        kws = vocab.kws(zone)
        onCollect(t, srcSel)
    }

    // 扫码推送词：填入输入框并走正常搜索流（web RemoteIn.onSearch 等价）
    LaunchedEffect(remoteKw) {
        remoteKw?.let {
            query = it
            search(it)
            onRemoteConsumed()
        }
    }

    fun pressKey(c: String) {
        query = when (c) {
            "⌫" -> query.dropLast(1)
            "CLR" -> ""
            else -> query + c.lowercase()
        }
    }

    // 右列单入口词流：空=热榜，有输入=关联词（suggest→fuzzy→截断置顶带标）
    val q = query.trim().lowercase()
    val pi = vocab.index(zone)
    val rightWords: List<Pair<String, Boolean>> = remember(q, pi.size(), zone) {
        if (q.isEmpty()) pi.top(40).map { it to false }
        else {
            val alphaNum = Regex("^[a-z0-9]+$").matches(q)
            var hits = if (alphaNum) pi.suggest(q, 40) else emptyList()
            if (hits.isEmpty()) hits = pi.list().filter { it.lowercase().contains(q) }
            else {
                val seen = hits.toHashSet()
                hits = hits + pi.fuzzy(q, (40 - hits.size).coerceAtLeast(0)).filter { it !in seen }
            }
            hits = hits.distinct()
            val truncs = if (alphaNum) PerfCore.truncateByQuery(q, hits, 3, 2) { pi.variants(it) } else emptyList()
            val marked = truncs.toHashSet()
            (truncs + hits.filter { it !in marked }).take(40).map { it to (it in marked) }
        }
    }

    // 手机/投影适配（用户 2026-11-04 包c 实测：多数投影仪 FEATURE_TOUCHSCREEN 也为真，触屏默认隐藏 36 键后
    // 遥控无键可输）：36 键改「⌨键盘」钮手动开合，默认=非触屏开/触屏关；系统 IME 真弹出时自动收起我方键盘防双键盘
    val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var kbShow by remember { mutableStateOf(!hasTouch) }
    val swDp = LocalConfiguration.current.screenWidthDp
    val leftW = if (swDp >= 820) 300.dp else 260.dp
    val rightW = if (swDp >= 820) 200.dp else 170.dp

    // 与首页 CompactChips 同款：最小触控 48→26dp，钮不再比 tab 大（用户 2026-11-04 样式统一首页）
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 26.dp) {
    Column(
        // imePadding+adjustResize（用户 2026-11-04 13T 实测：软键盘弹出遮住左列搜索/清空钮）
        modifier = modifier.fillMaxSize().imePadding().padding(16.dp)
            .onPreviewKeyEvent { e ->
                // 重新移焦/确认后回到两段返回第一段（再按 Back 先取消选中而非直接退页）
                if (e.type == KeyEventType.KeyDown &&
                    e.key in setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.Enter, Key.Spacebar)
                ) backStage = 0
                false
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 参考图样式定稿（用户 2026-11-04）：左=操作栏（全局搜索条+输入+选源钮+2×2 大按钮+36 键大键盘）｜中=热门搜索词流｜右=搜索历史（🗑清空）
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // 左列 400→300dp（用户 2026-11-04：键盘占比对齐手机截图+让宽热门词全称）；矮屏/大字号可滚兜底
            Column(
                modifier = Modifier.width(leftW).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 首页 StatCard 同款（labelMedium+surfaceVariant0.5）：不再比 tab 高（用户 2026-11-04 对齐首页）
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("全局搜索 · ${if (zone) "资源口" else "点播"}", style = MaterialTheme.typography.labelMedium) }
                    // 参考图无返回钮，按用户要求保留供手机端点按
                    TextButton(onClick = onBack) { Text("← 返回", style = MaterialTheme.typography.bodyMedium) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 输入框缩高（用户 2026-11-04：默认 OutlinedTextField 56dp 太大，与热门/历史 chip 同级）：
                    // m3 不支持改内容内边距→自绘描边容器+BasicTextField，字号与 IME 行为原样保留
                    val tfSrc = remember { MutableInteractionSource() }
                    val tfFocused by tfSrc.collectIsFocusedAsState()
                    Box(
                        modifier = Modifier.weight(1f).height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                if (tfFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                "请输入要搜索的内容",
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            interactionSource = tfSrc,
                            // 手机横屏软键盘 Enter=搜索（用户 2026-11-04 适配：IME 占半屏时左列按钮被遮，✓ 键直发；web 无软键盘不涉及）
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onSearch = { search(query) },
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    HomeBtn(if (kbShow) "⌨隐藏" else "⌨键盘", fill = false, onClick = { kbShow = !kbShow })
                    HomeBtn(srcSel?.let { "📌${it.name.take(4)}" } ?: "⚙", fill = false, onClick = { showSrcPick = true })
                }
                // 首页同款小方块钮（用户 2026-11-04 统一样式）：搜索/清空/远程/删除 + 36 键键盘全走 HomeBtn
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeBtn("搜索", modifier = Modifier.weight(1f)) { search(query) }
                    HomeBtn("清空", modifier = Modifier.weight(1f)) { pressKey("CLR") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeBtn("远程搜索", modifier = Modifier.weight(1f)) { showQr = true }
                    HomeBtn("⌫ 删除", modifier = Modifier.weight(1f)) { pressKey("⌫") }
                }
                // 「⌨键盘」开且系统 IME 未弹出时显示 36 键矩阵（web KEYS 等价）；投影仪默认触屏=true 也可手动开键后用遥控输入
                if (kbShow && !imeUp) KB_ROWS.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { c ->
                            HomeBtn(c.toString(), modifier = Modifier.weight(1f), tight = true) { pressKey(c.toString()) }
                        }
                        repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                hint?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            // 中列：热门榜/关联词（参考图「热门搜索」居中占主宽）
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (q.isEmpty()) "热门搜索" else "关联词", style = MaterialTheme.typography.labelMedium)
                if (rightWords.isEmpty()) {
                    Text(
                        if (q.isEmpty()) "词库建设中…（搜索/进墙自动积累本池片名）" else "没有关联词，清空输入可看热榜，或换字母试试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                // 中列宽≈屏宽-400-240-48，3 列会把 chip 文本挤没（f11 空白框教训），定 2 列
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    gridItemsIndexed(rightWords) { i, (w, trunc) ->
                        WordChip((if (i < 3 && q.isEmpty()) "🔥 " else "") + w + (if (trunc) " ·截断" else "")) { query = w; search(w) }
                    }
                }
            }
            // 右列：搜索历史+🗑清空（参考图置最右，标题旁垃圾桶；单列大 chip）
            Column(modifier = Modifier.width(rightW), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("搜索历史", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vocab.clearKw(zone); kws = emptyList() }) { Text("🗑 清空", style = MaterialTheme.typography.bodySmall) }
                }
                if (kws.isEmpty()) Text("暂无本池搜索历史", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f, fill = false)) {
                    items(kws) { k -> WordChip(k) { search(k) } }
                }
            }
        }
    }

    if (showQr) RemoteQrDialog(mode = "search", onDismiss = { showQr = false })

    if (showSrcPick) {
        val all = store.vodSources().mapIndexed { si, s -> si to s }.filter { it.second.isAdult == zone }
        AlertDialog(
            onDismissRequest = { showSrcPick = false },
            confirmButton = { TextButton(onClick = { showSrcPick = false }) { Text("关闭") } },
            title = { Text("指定搜索源（${if (zone) "资源口" else "点播"}）") },
            text = {
                LazyColumn {
                    item {
                        SrcPickRow(
                            name = "全部源（聚合）",
                            selected = srcSel == null,
                            onClick = { srcSel = null; showSrcPick = false },
                        )
                    }
                    items(all) { (si, s) ->
                        SrcPickRow(
                            name = s.name,
                            selected = srcSel?.si == si,
                            onClick = { srcSel = SpecSrc(si, s.name); showSrcPick = false },
                        )
                    }
                    item {
                        Text("选定后本次仅搜该源；结果页仍按源分 Tab（只有一列）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                    }
                }
            },
        )
    }
    } // CompositionLocalProvider(26dp 最小触控)
}

// 源选择弹窗行（用户 2026-11-04 修复：默认 Button 48dp 高+零行距导致「选择」钮重叠，改 HomeBtn 小方块+8dp 行距）
@Composable
private fun SrcPickRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Text("当前", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        else HomeBtn("选择", fill = false, onClick = onClick)
    }
}
