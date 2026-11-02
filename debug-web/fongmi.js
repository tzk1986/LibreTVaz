// FongMi/TV leanback 界面交互模拟：结构对照 app/src/leanback（HomeActivity/VodActivity/CollectActivity/LiveActivity/VideoActivity）
'use strict';
const $ = s => document.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

/* 同 URL 并发请求合并（in-flight 去重）：底层共享一次 fetch（30s 上限），各调用方按自己的 timeout 竞速；
   超时方放弃等待但请求结果仍可被复用——切 Tab/聚合与搜索重叠时不再重复打同一接口 */
const rawGet = async url => {
  let r;
  try { r = await fetch('/proxy?url=' + encodeURIComponent(url), { signal: AbortSignal.timeout(30000) }); }
  catch (e) { throw new Error(e?.name === 'TimeoutError' ? '上游 30s 无响应' : '网络失败/域名无法解析'); }
  const text = await r.text();
  if (!r.ok) throw new Error('HTTP ' + r.status);
  if (text.trimStart().startsWith('<')) throw new Error('源返回网页而非JSON（站点可能已失效）');
  return text;
};
const sharedGet = (typeof PerfCore !== 'undefined' ? PerfCore.createDedupe(rawGet) : rawGet);
async function httpGet(url, timeout = 12000) {
  let timer;
  const to = new Promise((_, rej) => { timer = setTimeout(() => rej(new Error(`请求超时（${timeout / 1000}s）`)), timeout); });
  try { return await Promise.race([Promise.resolve().then(() => sharedGet(url)), to]); }
  finally { clearTimeout(timer); }
}

/* ================= 数据层（与 CmsRepository 同规则） ================= */
const loadSrc = () => { try { const o = JSON.parse(localStorage.getItem('libretvaz_sources')) || {}; return { vod: o.vod || [], live: o.live || [], subs: o.subs || [] }; } catch { return { vod: [], live: [], subs: [] }; } };
let SRC = loadSrc();
const syncSrc = () => { SRC = loadSrc(); if (alignZonePool()) return; fixZoneIdx(); };
/* 专区源不进常规上下文（只在资源口展示）：当前下标指向专区源时改指首个非专区源 */
function fixZoneIdx() {
  if (!SRC.vod[Home.srcIdx]?.isAdult) return;
  const i = SRC.vod.findIndex(s => !s.isAdult);
  if (i >= 0) { Home.srcIdx = i; localStorage.setItem('fongmi_site_idx', String(i)); }
}
/* 首次进资源口种入预置源（ZONE_SOURCES，站单存本地文件 sources.local.js，不入版本库）：没主动加过时资源口只剩空墙，此处自动补全；
   按预置版本号（ZONE_SEED_VER）种——源管理手动删除后不再重复加回（尊重删除），
   清单扩充时升版本号才会补种新站。常规视图隔离由 isAdult 过滤保证，不受种入影响；
   用户自行导入的专区接口与种入站并存，资源口聚合/搜索取全部 isAdult 源 */
function seedZoneSources() {
  if (localStorage.getItem('fongmi_zone_seed') === ZONE_SEED_VER) return;
  localStorage.setItem('fongmi_zone_seed', ZONE_SEED_VER);
  const has = new Set(SRC.vod.map(s => norm(s.url)));
  const add = ZONE_SOURCES.filter(s => !has.has(norm(s.url)));
  if (!add.length) return;
  SRC.vod.push(...add);
  saveSrc();
}
/* 专区池对齐（只升不降）：凡主机名命中资源口预置站单的源强制 isAdult=true——各导入路径按精确 URL
   跳过已存在源、从不回写标记，专区源不会被重复导入改回普通；但同站不同路径的变体 URL 不相等会漏进常规池，此处按主机名收编。
   同站多路径变体**保留**（互为备份：一路接口挂时另一路仍可聚合/搜索），仅按精确 URL 去重；
   墙/Tab 展示层已有按名/按 id 去重，池内双份不会重复上屏 */
const hostOf = u => { try { return new URL(u).hostname.toLowerCase(); } catch { return null; } };
function alignZonePool() {
  if (!ZONE_SOURCES.length) return false;
  const zoneHosts = new Set(ZONE_SOURCES.map(s => hostOf(s.url)).filter(Boolean));
  const before = JSON.stringify(SRC.vod);
  SRC.vod.forEach(s => { const h = hostOf(s.url); if (h && zoneHosts.has(h)) s.isAdult = true; });
  const seenU = new Set();
  SRC.vod = SRC.vod.filter(s => {
    const h = hostOf(s.url);
    if (!h || !zoneHosts.has(h)) return true;
    const k = norm(s.url);
    if (seenU.has(k)) return false;
    seenU.add(k);
    return true;
  });
  if (Home.srcIdx >= SRC.vod.length) { Home.srcIdx = 0; localStorage.setItem('fongmi_site_idx', '0'); } // 去重删行后下标防越界
  if (JSON.stringify(SRC.vod) === before) return false;
  saveSrc(); fixZoneIdx();
  return true;
}
// 多标签页共用 localStorage：聚焦时重同步，否则本窗旧快照会在 saveSrc 时把别的标签页删掉的源写回去
window.addEventListener('focus', () => {
  const before = JSON.stringify(SRC);
  syncSrc();
  if (JSON.stringify(SRC) === before) return;
  if (Home.srcIdx >= SRC.vod.length) { Home.srcIdx = 0; Vod.tab = null; }
  if (Live.srcIdx >= SRC.live.length) Live.srcIdx = 0;
  $('#siteName').textContent = siteLabel();
  if (App.view === 'vod') renderVod();
  else if (App.view === 'live') renderLive();
});
const LS = (k, d) => { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } };
const siteLabel = () => Home.aggMode ? '🌐 聚合' : (SRC.vod[Home.srcIdx]?.name || '未配置点播源');
/* 本地缓存（SWR 模式）：有上次数据先秒显，后台拉新成功后替换并重存；安卓端对应 DiskCache + stale 首屏 */
const cacheGet = k => { try { const v = JSON.parse(localStorage.getItem('fongmi_cache:' + k)); return (v && v.d) || null; } catch { return null; } };
const cacheRaw = k => { try { return JSON.parse(localStorage.getItem('fongmi_cache:' + k)) || null; } catch { return null; } };
/* 源级聚合分片缓存 TTL：走设置页「缓存时长」档位（aggTtl，默认标准档=3分钟） */
const cacheSet = (k, d) => {
  const key = 'fongmi_cache:' + k;
  const set = () => { try { localStorage.setItem(key, JSON.stringify({ t: Date.now(), d })); return true; } catch { return false; } };
  if (set()) return;
  /* 配额满按写入顺序成批逐出旧分片（近似 LRU）：保留「返回点播/搜索旧数据秒显」能力。
     原先一把清空——51 源订阅写满配额后缓存反复归零，返回时全走网络 */
  const drop = n => { let c = 0; for (const x of Object.keys(localStorage)) { if (c >= n) break; if (x.startsWith('fongmi_cache:') && x !== key) { localStorage.removeItem(x); c++; } } return c; };
  let moved;
  do { moved = drop(20); } while (moved && !set());
};
/* 按前缀清缓存分片 / 统计占用（设置页「清理缓存」与点播刷新钮共用） */
const purgeCache = p => Object.keys(localStorage).forEach(x => { if (x.startsWith('fongmi_cache:' + p)) localStorage.removeItem(x); });
const cacheBytes = () => { let n = 0; for (const x of Object.keys(localStorage)) if (x.startsWith('fongmi_cache:')) n += x.length + (localStorage.getItem(x) || '').length; return n; };
/* 过期分片定时硬清（2026-11-02 用户需求两端对齐：缓存不能只增不减）：
   7 天未更新的 fongmi_cache:* 分片批量删除——SWR 的 fresh/stale 窗口逻辑不变，这只是防 localStorage 无界膨胀的兜底；
   页面加载 4s 后跑一次、以后每 30 分钟一次。APK 侧等价物见方案 B11（DiskCache 退出修剪+WorkManager 周期清理+视频 SimpleCache 200MB LRU） */
function pruneExpiredCache() {
  const cut = Date.now() - 7 * 864e5;
  let n = 0;
  for (const x of Object.keys(localStorage)) {
    if (!x.startsWith('fongmi_cache:')) continue;
    const v = cacheRaw(x.slice(13));
    if (!v || !v.t || v.t < cut) { localStorage.removeItem(x); n++; }
  }
  return n;
}
setTimeout(pruneExpiredCache, 4000);
setInterval(pruneExpiredCache, 30 * 60e3);
const fmtBytes = b => (b > 1048576 ? (b / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(b / 1024)) + ' KB');
const norm = u => String(u || '').trim().replace(/\/+$/, '');
const apiUrl = (base, query) => { const b = norm(base).replace(/\?+$/, ''); return b + (b.includes('?') ? '&' : '?') + query; };
// CMS 无参/简化列表不返回 vod_pic，海报墙必须带 ac=videolist；源 api 自带 ac= 时先剔除避免重复
const listUrl = (u, query) => apiUrl(norm(u).replace(/([?&])ac=[^&#]*/gi, '$1').replace(/[?&]+$/, ''), query);

/* ============ 源响应解码扩展注册表（预留缝，docs/2026-10-30-源解码扩展待办与预留缝.md L2 路线） ============
   条目 { id, match(source), decode(text, source) }：把非标准源响应转成标准 CMS JSON 文本再走现有解析。
   默认空数组=空转零开销；对接规则写 perf-core.js 纯函数保证 node 可测；不加载 jar 本体 */
const RESP_CODECS = [];
function decodeResp(text, source) {
  for (const c of RESP_CODECS) {
    try { if (c.match(source)) { const t = c.decode(text, source); if (typeof t === 'string') return t; } } catch { /* 解码失败静默跳过，走原解析 */ }
  }
  return text;
}
function parseList(text, source) {
  const j = JSON.parse(decodeResp(text, source));
  return (j.list || []).map(v => ({
    id: String(v.vod_id ?? ''), name: v.vod_name || '', pic: v.vod_pic || '',
    remarks: v.vod_remarks || null, year: v.vod_year || null, area: v.vod_area || null,
    typeName: v.type_name || null, type: v.type || v.vod_class || null, source, // type/class 为 R8 分类中间件第三信号
  })).filter(x => x.id && x.name);
}
function parseTypes(text) {
  const j = JSON.parse(text);
  return (j.class || []).filter(c => c && c.type_id != null)
    .map(c => ({ id: String(c.type_id), name: c.type_name || '', pid: String(c.type_pid ?? '0'), extend: parseExtend(c.type_extend) }));
}
/* ac=videolist 的 class[].type_extend 携带 area/year/lang 可选值（影视仓二级筛选数据源） */
const parseExtend = v => {
  if (!v) return {};
  if (typeof v === 'object') return v;
  try { return JSON.parse(v); } catch { const o = {}; String(v).split(';').forEach(kv => { const i = kv.indexOf(','); if (i > 0) o[kv.slice(0, i)] = kv.slice(i + 1); }); return o; }
};
function parseFilterMeta(text) {
  try {
    for (const c of (JSON.parse(text).class || [])) {
      const e = parseExtend(c.type_extend);
      if (e.area || e.year || e.lang) return {
        area: String(e.area || '').split(',').filter(Boolean),
        year: String(e.year || '').split(',').filter(Boolean),
        lang: String(e.lang || '').split(',').filter(Boolean),
      };
    }
    return null;
  } catch { return null; }
}
/* 词库与拼音联想双池（docs/2026-10-30-图片播放与拼音候选提速方案.md ③.5 + 2026-11-02 搜索双池隔离）：
   预计算索引在 PerfCore.createPinIndex（每词一次算首字母、多音字变体、前2字母分桶、词频权重）；
   搜索出结果 +1 / 进详情 +2；点播池键 fongmi_words/fongmi_wfreq，专区池键加 _zone 后缀，互不可见；
   索引不可用时退化子串匹配。z=true 路由专区池 */
// ①核心词归一化：片名入库时同步派生核心词（复仇者联盟4：终局之战→复仇者联盟），简拼可直中本体
const coreNameOf = n => (typeof PerfCore !== 'undefined' && PerfCore.coreName ? PerfCore.coreName(n) : '');
function createVocabPool(wordsKey, wfreqKey) {
  const index = (typeof PerfCore !== 'undefined' && PerfCore.createPinIndex)
    ? PerfCore.createPinIndex({ weights: LS(wfreqKey, {}), max: 8000 }) : null; // 目录全量片名做底，热词靠权重在淘汰时存活
  let words = index ? index.list() : LS(wordsKey, []);
  if (index) { // 恢复存量词库时补派生一次，老 localStorage 立即可用核心词
    const saved = LS(wordsKey, []);
    index.addMany(saved);
    for (const w of saved) { const c = coreNameOf(w); if (c && c !== w) index.add(c); }
  }
  let timer = null;
  const persist = () => {
    try {
      if (index) { localStorage.setItem(wordsKey, JSON.stringify(index.list())); localStorage.setItem(wfreqKey, JSON.stringify(index.weights())); }
      else localStorage.setItem(wordsKey, JSON.stringify(words));
    } catch { /* 满 */ }
  };
  const persistSoon = () => { clearTimeout(timer); timer = setTimeout(persist, 500); };
  return {
    index,
    add(names) {
      if (index) {
        for (const n of names) {
          if (!n) continue;
          index.add(n); index.bump(n, 1);
          const c = coreNameOf(n);
          if (c && c !== n) { index.add(c); index.bump(c, 1); }
        }
        persistSoon();
      } else {
        const set = new Set(words); let dirty = false;
        const pushW = t => { if (t && !set.has(t)) { set.add(t); words.push(t); dirty = true; } };
        for (const n of names) { pushW(n); pushW(coreNameOf(n)); }
        if (words.length > 2000) words = words.slice(-2000);
        if (dirty) persistSoon();
      }
    },
    bump(n, k) { if (index && n) { index.bump(n, k); persistSoon(); } },
    size: () => (index ? index.size() : words.length),
    list: () => (index ? index.list() : words),
  };
}
const VocabPools = { vod: createVocabPool('fongmi_words', 'fongmi_wfreq'), zone: createVocabPool('fongmi_words_zone', 'fongmi_wfreq_zone') };
const vocab = z => (z ? VocabPools.zone : VocabPools.vod);
const addWords = (names, z) => vocab(z).add(names);
const vocabSize = z => vocab(z).size();
const pushKw = (kw, z) => {
  const k = z ? 'fongmi_kw_zone' : 'fongmi_kw';
  const h = LS(k, []).filter(x => x !== kw); h.unshift(kw);
  localStorage.setItem(k, JSON.stringify(h.slice(0, 10)));
};
/* 扫码远程输入：「远程输入」按钮弹大码窗（同一 sid，URL 带 m=search/sub，手机扫码直达对应输入框并聚焦） */
const RemoteIn = { sid: Math.random().toString(36).slice(2, 10), base: '', onSearch: null };
async function remoteInit() {
  try {
    const j = await (await fetch('/api/remote/base')).json();
    const hn = location.hostname;
    const loop = /^(127\.|::1$|0\.0\.0\.0|localhost$)/i.test(hn); // TV 用 127.0.0.1 打开时 QR 必须回落实例局域网 IP，否则手机打不开
    const host = (/^(\d+\.\d+\.\d+\.\d+|[a-f0-9:.]+)$/i.test(hn) && !loop) ? hn : (j.host || 'localhost');
    RemoteIn.base = `http://${host}:${j.port || 8787}`;
  } catch { RemoteIn.base = location.origin; }
  try { // 注册会话：手机页据此显示在线状态，send 未注册直接拒（tv_offline）
    await fetch(`${RemoteIn.base}/api/remote/open`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ sid: RemoteIn.sid }) });
  } catch { /* 轮询本身兼作注册，失败不致命 */ }
  remoteLoop();
}
const remoteUrl = mode => `${RemoteIn.base}/remote?id=${RemoteIn.sid}&m=${mode}`;
// qrcode-generator 自绘（原 CDN qrcode@1.5.4 build 恒 404，码从未画出过）
function qrCanvas(text, px) {
  try {
    const qr = window.qrcode(0, 'M'); qr.addData(text); qr.make();
    const n = qr.getModuleCount(), cell = Math.max(2, Math.floor(px / (n + 2))), sz = cell * (n + 2);
    const c = document.createElement('canvas'); c.width = c.height = sz;
    const g = c.getContext('2d');
    g.fillStyle = '#14171a'; g.fillRect(0, 0, sz, sz);
    g.fillStyle = '#e8eaed';
    for (let r = 0; r < n; r++) for (let q = 0; q < n; q++) if (qr.isDark(r, q)) g.fillRect((q + 1) * cell, (r + 1) * cell, cell, cell);
    return c;
  } catch { return null; }
}
function openQrModal(mode) {
  const url = remoteUrl(mode);
  $('#qrHint').textContent = mode === 'sub'
    ? '手机相机扫码 → 粘贴/输入订阅地址发送，电视收到后自动导入'
    : '手机相机扫码 → 输入关键词发送，电视收到后自动搜索';
  const a = $('#qrUrl'); a.href = url; a.textContent = url;
  const big = $('#qrBig'); big.innerHTML = '';
  const c = window.qrcode ? qrCanvas(url, 300) : null;
  if (c) big.appendChild(c); else big.innerHTML = '<p class="err">二维码库未加载，请点击上方链接在手机浏览器打开</p>';
  $('#qrModal').classList.add('open');
  Focus.set($('#qrClose'));
}
const closeQrModal = () => $('#qrModal').classList.remove('open');
const rsleep = ms => new Promise(r => setTimeout(r, ms));
async function remoteLoop() { // 长轮询：服务端挂起≤30s，send 即唤醒秒级送达；页面隐藏暂停续轮省资源
  while (RemoteIn.base) {
    if (document.hidden) { await rsleep(2000); continue; }
    let hit = null;
    try {
      hit = await (await fetch(`${RemoteIn.base}/api/remote/poll?id=${RemoteIn.sid}`, { signal: AbortSignal.timeout(35000) })).json();
    } catch { await rsleep(3000); continue; } // 断连/超时：短暂退避后续轮
    if (!hit || !hit.text) continue;
    if (hit.mode === 'sub') {
      // 用户定调：扫码推送后自动导入/搜索；如需 TV 端先确认，设 localStorage fongmi_qrauto=0（页内 askModal，遥控可操作）
      const runSub = () => {
        toast('开始导入远程订阅…');
        const inp = $('#subUrl'); if (inp) inp.value = hit.text;
        doImport(hit.text);
      };
      if (LS('fongmi_qrauto', 1) === 1) runSub();
      else askYes(`收到订阅链接，确认导入？\n${String(hit.text).slice(0, 160)}`, runSub, () => toast('已忽略远程订阅导入'));
    } else {
      if (App.view !== 'search') switchView('search');
      setTimeout(() => RemoteIn.onSearch && RemoteIn.onSearch(hit.text), 80);
    }
  }
}
function splitPlayUrl(url) {
  return String(url || '').split('#').map(seg => {
    const i = seg.indexOf('$'); if (i < 0) return null;
    return { name: seg.slice(0, i), link: seg.slice(i + 1) };
  }).filter(Boolean);
}
function parseDetail(text, source) {
  const j = JSON.parse(decodeResp(text, source)); const v = (j.list || [])[0] || {};
  const flags = String(v.vod_play_from || '').split('$$$');
  const groups = String(v.vod_play_url || '').split('$$$');
  const episodes = flags.map((f, i) => ({ flag: f || `第${i + 1}线路`, list: splitPlayUrl(groups[i] || '') }));
  return {
    id: String(v.vod_id ?? ''), title: v.vod_name || '', pic: v.vod_pic || '',
    year: v.vod_year || '', area: v.vod_area || '', notes: v.vod_remarks || '',
    type: v.vod_type || '', director: v.vod_director || '', actor: v.vod_actor || '',
    desc: (v.vod_content || '').replace(/<[^>]+>/g, ''), episodes, source,
  };
}

/* ================= 焦点引擎 ================= */
const Focus = {
  el: null,
  set(el) {
    if (this.el === el) return;
    /* 选中变化即退出输入态：编辑中切到别的控件时 blur，防 IME/光标残留（editing 类由 focusout 清） */
    const ae = document.activeElement;
    if (ae && ae !== el && isEditable(ae)) ae.blur();
    this.el?.classList.remove('focus');
    this.el = el || null;
    if (el) { el.classList.add('focus'); el.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }
  },
  items() {
    const scope = (document.fullscreenElement && document.fullscreenElement.id === 'fsStage') ? '#fsStage'
      : $('#askModal').classList.contains('open') ? '#askModal'
      : $('#cfgModal').classList.contains('open') ? '#cfgModal'
      : $('#qrModal').classList.contains('open') ? '#qrModal'
      : $('#findModal').classList.contains('open') ? '#findModal'
      : $('#siteModal').classList.contains('open') ? '#siteModal'
      : $('#srcModal').classList.contains('open') ? '#srcModal' : '#app';
    return [...document.querySelectorAll(`${scope} [data-focus]`)].filter(e => e.offsetParent !== null);
  },
  move(dir) {
    const items = this.items(); if (!items.length) return;
    const cur = this.el && items.includes(this.el) ? this.el : null;
    if (!cur) { this.set(items[0]); return; }
    const r = cur.getBoundingClientRect();
    const cx0 = r.left + r.width / 2, cy0 = r.top + r.height / 2;
    const V = { L: [-1, 0], R: [1, 0], U: [0, -1], D: [0, 1] }[dir];
    let best = null, bestScore = Infinity;
    for (const it of items) {
      if (it === cur) continue;
      const b = it.getBoundingClientRect();
      const dx = b.left + b.width / 2 - cx0, dy = b.top + b.height / 2 - cy0;
      const along = dx * V[0] + dy * V[1];
      if (along <= 2) continue;
      const across = Math.abs(dx * V[1] + dy * V[0]);
      const score = along + across * 3;
      if (score < bestScore) { bestScore = score; best = it; }
    }
    if (best) this.set(best);
  },
};
const toast = (() => { let t; return msg => { $('#toast').textContent = msg; $('#toast').style.display = 'block'; clearTimeout(t); t = setTimeout(() => $('#toast').style.display = 'none', 2200); }; })();

/* 页内确认框（R4 替代原生 confirm()，TV 浏览器原生弹层遥控不可操作）：默认焦点=取消防误删；Esc/点遮罩=取消 */
let askCb = null;
function askYes(msg, onOk, onCancel) {
  $('#askMsg').textContent = msg;
  askCb = { onOk, onCancel };
  $('#askModal').classList.add('open');
  Focus.set($('#askCancel'));
}
function closeAsk(ok) {
  if (!$('#askModal').classList.contains('open')) return;
  $('#askModal').classList.remove('open');
  const cb = askCb; askCb = null;
  if (ok) cb?.onOk?.(); else cb?.onCancel?.();
}

/* ================= 按键路由 =================
   两段式（docs/2026-10-30-遥控可用性复盘与优化方案.md R1，对齐 Leanback/Kodi/Samsung 模型）：
   选中≠编辑——Focus.set 只画选中框；Enter 才 enterEdit 进输入态；Esc 第一层退编辑（选中框保留），
   编辑态方向键归光标原生放行；鼠标点输入框仍一步进编辑（原生 focus，click 委托只挪选中框） */
const LiveKey = { num: '', timer: null };
const isEditable = el => !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA');
function enterEdit(el) { if (!isEditable(el)) return; el.focus(); el.classList.add('editing'); }
document.addEventListener('focusout', e => { if (isEditable(e.target)) e.target.classList.remove('editing'); });
document.addEventListener('keydown', e => {
  if (document.fullscreenElement) showFsBar(); // 全屏内任意按键唤回播控浮层（TV 惯例）
  const editing = isEditable(document.activeElement);
  if ($('#siteModal').classList.contains('open')) { /* 弹窗开：方向/Enter 照旧走焦点环，Escape 由 backLayer 统一分层关闭 */ }
  else if (App.view === 'live' && !editing && !document.fullscreenElement && (!Focus.el || Focus.el.tagName === 'VIDEO')) { // 仅画面/无焦点时劫持键位；焦点在控件（返回/全屏/源chip）放行通用 Enter 点击（补漏#5）；全屏时放行=暂停/seek（补漏#2）
    if (Live.panelOpen) { /* 面板开时方向键走通用焦点 */ }
    else if (e.key === 'ArrowUp') { chanStep(-1); e.preventDefault(); return; }
    else if (e.key === 'ArrowDown') { chanStep(1); e.preventDefault(); return; }
    else if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') { togglePanel(true); e.preventDefault(); return; } // R6：←→ 也开面板，回点播/收藏经面板可达（原 toast 死键）
    else if (e.key === 'Enter') { togglePanel(true); e.preventDefault(); return; }
    else if (/^[0-9]$/.test(e.key)) { inputDigit(e.key); e.preventDefault(); return; }
  }
  if (e.key === 'Escape') { backLayer(); e.preventDefault(); return; } // Back/Esc 统一分层链（P2：浏览器 Back 亦经 popstate 汇入同链）
  if (e.key === 'Enter') {
    if (editing) return; // 输入框内 Enter 由控件自身处理（搜索/导入/全源搜索）
    if (isEditable(Focus.el)) { enterEdit(Focus.el); e.preventDefault(); return; }
    if (Focus.el?.tagName === 'VIDEO') { togglePlayVideo(Focus.el); e.preventDefault(); return; } // R5：画面 OK=播放/暂停（click() 对 video 无效）
    Focus.el?.click(); e.preventDefault();
  }
  if (e.key === ' ' || e.code === 'Space') {
    if (Focus.el?.tagName === 'VIDEO' && !editing) { togglePlayVideo(Focus.el); e.preventDefault(); } // Space=暂停，杜绝滚页
    return;
  }
  const D = { ArrowLeft: 'L', ArrowRight: 'R', ArrowUp: 'U', ArrowDown: 'D' }[e.key];
  if (D) {
    if (editing) return; // 编辑态方向键=光标/选区，放行原生
    if (Focus.el?.tagName === 'VIDEO' && (App.view === 'video' || document.fullscreenElement) && (D === 'L' || D === 'R')) { seekVideo(Focus.el, D === 'L' ? -15 : 15); e.preventDefault(); return; } // R5 详情/全屏 ←→=±15s
    const prevF = Focus.el;
    Focus.move(D); e.preventDefault();
    // S4：墙已到底、焦点无处可去时，继续按 ↓ 直接触发哨兵加载（无需先对准按钮）
    if (D === 'D' && Focus.el === prevF && (App.view === 'vod' || App.view === 'zone') && prevF?.closest?.('#vodGrid')) {
      const sc = $('#scroller'), mb = $('#moreBtn');
      if (sc && mb && sc.scrollHeight - sc.scrollTop - sc.clientHeight < 48) { Focus.set(mb); mb.click(); }
    }
    const fe = Focus.el; // 键盘移动后同样记录最后聚焦频道行（favCur 定位用）
    if (App.view === 'live' && fe?.dataset?.i != null && fe.closest?.('#colChan')) Live.hi = Number(fe.dataset.i);
  }
});
document.addEventListener('click', e => {
  if (document.fullscreenElement) showFsBar(); // 触屏/鼠标点击同样唤回浮层
  const t = e.target.closest('[data-focus]'); if (!t) return;
  Focus.set(t);
  if (t.dataset.i != null && t.closest?.('#colChan')) Live.hi = Number(t.dataset.i); // R6：记录最后聚焦频道行，供面板顶 favCur 按钮定位
});
function onEsc() {
  if ($('#askModal').classList.contains('open')) return closeAsk(false);
  if ($('#cfgModal').classList.contains('open')) return closeCfg();
  if ($('#qrModal').classList.contains('open')) return closeQrModal();
  if ($('#srcModal').classList.contains('open')) return closeSrcMgr();
  if ($('#siteModal').classList.contains('open')) return closeSiteDialog();
  if ($('#findModal').classList.contains('open')) return closeFindModal();
  if (App.view === 'live' && Live.panelOpen) return togglePanel(false);
  if (App.view === 'video') return switchView(App.videoBack || 'vod');
  if (App.view === 'collect') { Collect.stop = true; return switchView(Collect.back || 'search'); }
  if (App.view === 'search') return switchView(Search.mode === 'zone' ? 'zone' : 'vod'); // 资源口池搜索页 Esc 回资源口
  if (App.view === 'zone') return switchView('settings');
  if (App.view === 'zonehist' || App.view === 'zonekeep') return switchView('zone'); // 资源口子页 Esc 回资源口
  if (App.view !== 'vod') return switchView('vod'); // 点播即首页：其余视图 Esc 一律回点播（P8）
}
/* P2 浏览器 Back 接管（History 捕获层）：遥控 Back 键/浏览器后退/手机返回手势都会先发 popstate，
   页面加载即多挂一层捕获历史，Back 先被 popstate 收回不退出页面，再映射与 Esc 同款分层链：
   关换源弹窗→退全屏→退编辑→收筛选行→关其余弹窗→视图逐级返回；底层=点播首页（Back=回顶） */
function backLayer() {
  if ($('#siteModal').classList.contains('open')) return closeSiteDialog();
  if (document.fullscreenElement) { document.exitFullscreen().catch(() => {}); return; } // 第一层=退全屏（部分 TV 浏览器不发原生退全屏事件）
  if (isEditable(document.activeElement)) { document.activeElement.blur(); return; } // 退编辑，Focus.el 选中框保留
  if (App.view === 'vod' && Vod.showFilters && Focus.el && $('#subRow')?.contains(Focus.el)) { // R8：筛选行内=收起并回到当前分类 chip
    Vod.showFilters = false; drawSubRow();
    const want = Vod.agg && Vod.tab ? 'cat:' + Vod.tab.id : String(Vod.tab?.id ?? '');
    const chip = [...document.querySelectorAll('#typeRow [data-tid]')].find(c => c.dataset.tid === want);
    Focus.set(chip || $('#typeRow').querySelector('[data-focus]') || $('#vodRefresh'));
    return;
  }
  if (App.view === 'vod') { // 点播首页底层：不退出页面，回顶+焦点进分类行（杜绝 Back 死键/误关）
    $('#scroller')?.scrollTo({ top: 0 });
    Focus.set($('#typeRow').querySelector('[data-focus]') || $('#vodRefresh'));
    return;
  }
  onEsc();
}
history.pushState({ bk: 1 }, ''); // 捕获层：当前页之上多挂一层，Back 先弹掉它并经 popstate 归我们处理
window.addEventListener('popstate', () => { history.pushState({ bk: 1 }, ''); backLayer(); });

/* ================= 播放 =================
   渐进容错（docs/2026-10-30-图片播放与拼音候选提速方案.md ③）：
   hls 配置对齐主站 player-shell（超时/重试/worker）；先直连，fatal 网络/清单错误升级 /proxy 重建一次；
   直链(mp4 等)同样 error 后切 /proxy 一次；再失败明确提示换线，不再无限转圈；起播前显示「缓冲中…」 */
let hls = null, playCleanup = null;
function stopHls() { if (hls) { hls.destroy(); hls = null; } if (playCleanup) { playCleanup(); playCleanup = null; } }
/* 播放链接解码注册表（异步预留缝，同上待办文档 L2/L3 路线）：条目 { id, match(link), resolve(link)→真实地址 }。
   默认空数组时 playInto 不进入 await 分支，行为与同步版完全一致；非空时 playSeq 防解码期竞态 */
const PLAY_CODECS = [];
async function resolvePlay(link) {
  for (const c of PLAY_CODECS) {
    try { if (c.match(link)) { const u = await c.resolve(link); if (typeof u === 'string' && u) return u; } } catch { /* 失败回落下一条或原链 */ }
  }
  return link;
}
let playSeq = 0;
async function playInto(video, url, title, onDead) {
  const seq = ++playSeq;
  stopHls();
  video.pause(); video.removeAttribute('src');
  const t = $('#vtitle'); const base = title || '';
  const mark = on => { if (t) t.textContent = base + (on ? ' · 缓冲中…' : ''); };
  if (PLAY_CODECS.length) {
    mark(true);
    url = await resolvePlay(url);
    if (seq !== playSeq) return; // 解码期间被新的播放/停止顶掉，丢弃本次
  }
  const px = '/proxy?url=' + encodeURIComponent(url);
  // O1b：onDead 返回 true 表示外层已改播其它线路，不再弹手动提示
  const dead = () => { stopHls(); mark(false); if (!onDead || !onDead()) toast('该线路无法播放，请换线路或换源'); };
  if (/\.(m3u8|mpd)/i.test(url) && window.Hls && Hls.isSupported()) {
    mark(true);
    const start = usePx => {
      // maxBufferLength 30→45：更深缓冲减少弱网分片间隙卡顿；ABR 自动逐级抬清晰度（capLevelToPlayerSize 关，全屏不封顶）
      hls = new Hls({ enableWorker: true, maxBufferLength: 45, manifestLoadingTimeOut: 10000, fragLoadingTimeOut: 15000, manifestLoadingMaxRetry: 2, fragLoadingMaxRetry: 6, abrEwmaDefaultEstimate: 800000 });
      hls.loadSource(usePx ? px : url);
      hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED, () => video.play().catch(() => {}));
      hls.on(Hls.Events.FRAG_BUFFERED, () => mark(false));
      hls.on(Hls.Events.ERROR, (ev, data) => {
        if (!data.fatal) return;
        const upgrade = !usePx && (data.type === Hls.ErrorTypes.NETWORK_ERROR || data.type === Hls.ErrorTypes.MANIFEST_ERROR);
        stopHls();
        if (upgrade) start(true); else dead();
      });
    };
    start(false);
  } else {
    mark(true);
    let triedPx = false;
    const onErr = () => {
      if (!triedPx) { triedPx = true; video.removeAttribute('src'); video.src = px; video.play().catch(() => {}); return; }
      dead();
    };
    const onOk = () => mark(false);
    video.addEventListener('error', onErr);
    video.addEventListener('canplay', onOk, { once: true });
    playCleanup = () => video.removeEventListener('error', onErr);
    video.src = url; video.play().catch(() => {});
  }
}
function stopPlayer() { playSeq++; stopHls(); document.querySelectorAll('video').forEach(v => { v.pause(); v.removeAttribute('src'); v.load(); }); }
/* A1 线路吞吐采样（docs/2026-11-02-TVBox对标设置页与线路测速方案.md）：/proxy 同源避免 CORS；
   Range 0-131071 限 128KB 成本（源站不支持则全速读、2.5s 预算到点即断，同样得 KB/s）；
   m3u8 先经 /proxy 拉改写后清单、取首分片采样——分片比清单文本更贴近真实带宽 */
const lsPx = u => '/proxy?url=' + encodeURIComponent(u);
const LINE_SPEED = new Map(); // 「详情键|集号」→ 按线下标的 KB/s 数组（null 未测/0 失败）；只存内存，随会话结束丢弃
let curFindState = null; // 当前播放页 {line,ep,sec} 快照函数（实验室自动换源续播用，openDetail 每次进入重设）
async function sampleKbps(u, budget = 2500) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), budget + 500);
  const t0 = Date.now();
  let bytes = 0;
  try {
    const r = await fetch(u, { headers: { Range: 'bytes=0-131071' }, signal: ctrl.signal });
    if (!r.ok && r.status !== 206) throw new Error('HTTP ' + r.status);
    const rd = r.body && r.body.getReader();
    if (!rd) throw new Error('无响应体');
    for (;;) {
      const { done, value } = await rd.read();
      if (done) break;
      bytes += value ? value.length : 0;
      if (Date.now() - t0 >= budget) break;
    }
    rd.cancel().catch(() => {});
  } catch (e) { if (!bytes) throw e; } // 掐表/中断前已收到字节：按已收部分计速
  finally { clearTimeout(timer); }
  if (!bytes) return 0;
  return bytes * 1000 / Math.max(1, Date.now() - t0) / 1024; // KB/s
}
async function lineSpeed(link) {
  if (/\.m3u8(\?|$)/i.test(link)) {
    const t = await (await fetch(lsPx(link), { signal: AbortSignal.timeout(8000) })).text();
    const first = t.split('\n').map(s => s.trim()).find(s => s && !s.startsWith('#'));
    if (!first) throw new Error('清单无分片');
    const seg = /(^|[=/])proxy\?url=/i.test(first) ? first : lsPx(new URL(first, link).href);
    return sampleKbps(seg);
  }
  return sampleKbps(lsPx(link));
}
/* R5 遥控播控：焦点在画面时 Enter/Space=播放暂停 toggle，详情页 ←→=±15s seek（对标 Emby/ExoPlayer 键位） */
function togglePlayVideo(v) { if (v.paused) v.play().catch(() => {}); else v.pause(); }
function seekVideo(v, d) {
  if (!isFinite(v.duration) || v.duration <= 0) { toast('快进/快退暂不可用（流未就绪）'); return; }
  v.currentTime = Math.max(0, Math.min(v.duration - 1, (v.currentTime || 0) + d));
  const fmt = s => Math.floor(s / 60) + ':' + String(Math.floor(s % 60)).padStart(2, '0');
  toast((d < 0 ? '« 15s ' : '15s » ') + fmt(v.currentTime) + ' / ' + fmt(v.duration)); // 审计补漏#3：带当前位置/总时长
}
/* 遥控全屏（docs/2026-10-30-全屏播控浮层方案.md）：#fsStage 容器全屏 + 底部播控浮层（上/下集/暂停/退出），
   video 运行时移入、退全屏归位；iOS 等无元素全屏 API 的浏览器回退 video 原生全屏（无浮层）。
   键位：全屏内任意键唤回浮层（5s 自动淡出）；画面焦点 ←→=±15s seek、Enter/Space=暂停；Esc=浏览器原生退全屏 */
let fsHome = null, fsCtl = null, fsHideT = 0;
function showFsBar() {
  const bar = $('#fsBar'); if (!bar || !document.fullscreenElement) return;
  bar.classList.add('show'); bar.classList.remove('fade');
  clearTimeout(fsHideT);
  fsHideT = setTimeout(() => bar.classList.add('fade'), 5000);
}
/* 浮层与页面上下集按钮的边界置灰：首集灰⏮、末集灰⏭（保留显示不隐藏，点击只提示）；直播（fsCtl=null）隐藏换集键 */
function syncFsBar() {
  const prev = $('#fsPrev'); if (!prev) return;
  const st = fsCtl && fsCtl.state();
  const next = $('#fsNext'), info = $('#fsInfo');
  prev.style.display = next.style.display = st ? '' : 'none';
  if (!st) { info.textContent = App.view === 'live' ? '直播播放中' : ''; return; }
  prev.classList.toggle('off', st.cur <= 0);
  next.classList.toggle('off', st.cur >= st.total - 1);
  const pe = $('#prevEp'), ne = $('#nextEp');
  if (pe) pe.classList.toggle('off', st.cur <= 0);
  if (ne) ne.classList.toggle('off', st.cur >= st.total - 1);
  info.textContent = `${st.title}${st.flag ? ' · ' + st.flag : ''} · 第${st.cur + 1}/${st.total}集`;
}
function toggleFullscreen() {
  if (document.fullscreenElement) { document.exitFullscreen().catch(() => {}); return; }
  const v = $('#player'); if (!v) return;
  const st = $('#fsStage');
  if (st && st.requestFullscreen) {
    fsHome = { p: v.parentNode, n: v.nextSibling };
    st.insertBefore(v, st.firstChild);
    st.requestFullscreen().then(() => { Focus.set(v); showFsBar(); syncFsBar(); })
      .catch(() => { if (v.parentNode === st) { try { fsHome.p.insertBefore(v, fsHome.n); } catch {} } fsHome = null; toast('进入全屏失败'); });
    return;
  }
  if (v.requestFullscreen) v.requestFullscreen().then(() => Focus.set(v)).catch(() => toast('进入全屏失败'));
  else if (v.webkitEnterFullscreen) v.webkitEnterFullscreen();
  else toast('该浏览器不支持全屏');
}
document.addEventListener('fullscreenchange', () => { // 含 Esc 原生退出：video 归位防滞留黑屏；详情/直播两处入口文案同步
  const isFs = !!document.fullscreenElement;
  const v = $('#player');
  if (!isFs && v && fsHome) {
    const { p, n } = fsHome; fsHome = null;
    try { p.insertBefore(v, n && p.contains(n) ? n : null); } catch {}
    clearTimeout(fsHideT); $('#fsBar').classList.remove('show', 'fade');
  }
  const txt = isFs ? '⛶ 退出全屏' : '⛶ 全屏';
  for (const id of ['fsBtn', 'liveFs']) { const b = $('#' + id); if (b) b.textContent = txt; }
});
$('#fsPrev').onclick = () => { if ($('#fsPrev').classList.contains('off')) return toast('已是第一集'); fsCtl && fsCtl.go(-1); };
$('#fsNext').onclick = () => { if ($('#fsNext').classList.contains('off')) return toast('已是最后一集'); fsCtl && fsCtl.go(1); };
$('#fsPlay').onclick = () => { const v = $('#player'); if (v) togglePlayVideo(v); };
$('#fsExit').onclick = () => document.exitFullscreen().catch(() => {});

/* ================= 视图框架 ================= */
const App = { view: 'vod', videoBack: 'vod', srcDirty: false, curZone: false };
/* 资源口（二级专区）：仅经设置页实验室进入；active=当前在专区墙视图，池按 isAdult 取反 */
const Zone = { active: false, seq: 0, tags: {} }; // tags：本轮墙测速结果（键同 adsp: 缓存），聚合墙增量重绘后回贴标签
/* 历史/收藏条目专区标记（数据隔离）：写入时带 a:1/0；无标记的旧条目按当时源下标推断。
   常规收藏/历史/点播导航计数只显示非专区条目，资源口只显示专区条目 */
const isZoneRec = h => h.a === 1 || (h.a == null && !!SRC.vod[h.si]?.isAdult);
const body = $('#body');
/* 遥控友好返回：非点播首页视图都在头部放可聚焦「← 点播/返回」按钮（实体遥控器没有 Esc 键，需可选中点确认） */
const bindBack = (id, fn) => { const b = $('#' + id); if (b) b.onclick = fn; };
function switchView(v) {
  if (document.fullscreenElement && document.fullscreenElement.id === 'fsStage') document.exitFullscreen().catch(() => {}); // 切视图先退全屏，video 经 fullscreenchange 归位防孤儿
  fsCtl = null;
  stopPlayer();
  $('#siteModal').classList.remove('open');
  $('#cfgModal').classList.remove('open');
  App.view = v;
  Zone.active = v === 'zone';
  Focus.set(null);
  $('#siteName').textContent = siteLabel();
  if (v === 'vod') renderVod(); // 点播即 App 首页（P8）
  else if (v === 'zone') renderZone();
  else if (v === 'search') renderSearch();
  else if (v === 'collect') renderCollect();
  else if (v === 'live') renderLive();
  else if (v === 'keep') renderKeep();
  else if (v === 'hist') renderHist();
  else if (v === 'zonekeep') renderKeep(true);
  else if (v === 'zonehist') renderHist(true);
  else if (v === 'probe') renderProbe();
  else if (v === 'settings') renderSettings();
  else if (v === 'video') { /* 由 openDetail 渲染 */ }
}
$('#siteName').onclick = openSiteDialog;
setInterval(() => { const d = new Date(); $('#clock').textContent = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'); }, 1000);

function openSiteDialog() {
  syncSrc();
  if (!SRC.vod.length) return toast('无点播源，可在功能行「源管理」导入订阅或添加内置源');
  $('#siteModal').classList.add('open');
  const rank = i => { const h = healthOf(SRC.vod[i].url); return h ? (h.fails >= DEAD_FAILS ? 2 : 0) : 1; };
  const msOf = i => healthOf(SRC.vod[i].url)?.ms ?? 1e9;
  // 专区源不进常规换源列表（专区资源只展示到资源口）
  const order = SRC.vod.map((_, i) => i).filter(i => !SRC.vod[i].isAdult).sort((a, b) => rank(a) - rank(b) || msOf(a) - msOf(b));
  $('#siteGrid').innerHTML = `<p style="grid-column:1/-1;margin:0 0 4px;color:var(--dim);font-size:13px">当前使用接口数据 · 🗑 可删除（需确认）</p><button class="siteitem${Home.aggMode ? ' on' : ''}" data-focus data-i="-1">🌐 聚合<small style="display:block;font-size:11px;color:var(--dim)">全源合并·点卡片跨源搜索</small></button>` + order.map(i => {
    const s = SRC.vod[i], h = healthOf(s.url), dead = rank(i) === 2;
    // A4 对标图12：行内删除钮（聚合等内置项无删除钮）；button 不能套 button，删除钮用 span+data-focus 保持遥控可达
    return `<button class="siteitem${!Home.aggMode && i === Home.srcIdx ? ' on' : ''}${dead ? ' dead' : ''}" data-focus data-i="${i}">${esc(s.name)}${h ? `<small style="display:block;font-size:11px;color:${dead ? '#f28b82' : '#81c995'}">${dead ? '失效' : (h.ms ?? '') + 'ms'}</small>` : ''}<span class="sdel" data-focus data-del="${i}" title="删除该源">🗑</span></button>`;
  }).join('');
  $('#siteGrid').onclick = e => {
    const d = e.target.closest('[data-del]');
    if (d) { // 行内删除：确认→splice→修正下标→重开弹窗即见新列表（同 drawSrcMgr 删除语义但带确认）
      e.stopPropagation();
      const i = +d.dataset.del, s = SRC.vod[i]; if (!s) return;
      askYes(`确定删除点播源「${s.name}」？`, () => {
        syncSrc();
        const at = SRC.vod.findIndex(x => x.url === s.url);
        if (at < 0) return openSiteDialog(); // 他页已删，刷新列表即可
        SRC.vod.splice(at, 1); saveSrc();
        if (Home.srcIdx >= SRC.vod.length || at === Home.srcIdx) Home.srcIdx = 0;
        else if (at < Home.srcIdx) Home.srcIdx--;
        localStorage.setItem('fongmi_site_idx', String(Home.srcIdx));
        openSiteDialog();
        refreshAfterSourceChange();
      });
      return;
    }
    const b = e.target.closest('[data-i]'); if (!b) return;
    const i = +b.dataset.i;
    Home.aggMode = i === -1;
    if (i >= 0) { Home.srcIdx = i; localStorage.setItem('fongmi_site_idx', String(i)); }
    localStorage.setItem('fongmi_agg', Home.aggMode ? '1' : '0');
    closeSiteDialog();
    $('#siteName').textContent = siteLabel();
    if (App.view === 'vod') { Vod.tab = null; loadSiteData(); renderVod(); }
  };
  Focus.set($('#siteGrid').querySelector('[data-focus]'));
}
function closeSiteDialog() { $('#siteModal').classList.remove('open'); }

/* ================= 源管理（对应功能行「源管理」按钮，Android 端即 SourcesScreen） ================= */
/* 2026-11-02 按《可用点播源清单》重建：一节「直接可播」17 站（裸 m3u8）。
   移除：量子/火狐(/play/加密页仅搜索)、索尼(ac=detail返HTML失效)、天天主域(换东泰m3u8备域裸流)、茅台(实测搜索0命中)。
   爱坤/瑞源当日笔记可播、本机当前直连即被 RST（疑似临时封锁），保留由自动维护兜底剔除 */
const BUILTIN_SOURCES = [
  { name: '暴风资源', url: 'https://bfzyapi.com/api.php/provide/vod' },
  { name: '最大资源', url: 'https://api.zuidapi.com/api.php/provide/vod' },
  { name: '爱坤资源', url: 'https://ikunzyapi.com/api.php/provide/vod' },
  { name: '无尽资源', url: 'https://api.wujinapi.com/api.php/provide/vod' },
  { name: '瑞源资源', url: 'http://cj.rycjapi.com/api.php/provide/vod' },
  { name: '速播资源', url: 'https://www.subocaiji.com/api.php/provide/vod' },
  { name: '红牛资源', url: 'https://www.hongniuzy3.com/api.php/provide/vod' },
  { name: '非凡资源', url: 'https://cj.ffzyapi.com/api.php/provide/vod' },
  { name: '360资源', url: 'https://360zy.com/api.php/provide/vod' },
  { name: '东泰m3u8', url: 'http://dyttzy5.tv/api.php/provide/vod/from/dyttm3u8/at/m3u8' },
  { name: '金鹰资源', url: 'http://jinyingzy.com/provide/vod' },
  { name: 'p2100', url: 'https://p2100.net/api.php/provide/vod' },
  { name: '魔都资源', url: 'https://caiji.moduapi.cc/api.php/provide/vod' },
  { name: '爱奇艺备', url: 'https://iqiyizyapi.com/api.php/provide/vod' },
  { name: '百度资源', url: 'https://api.apibdzy.com/api.php/provide/vod' },
  { name: '猫眼资源', url: 'https://api.maoyanapi.top/api.php/provide/vod' },
  { name: '虎牙资源', url: 'https://huyazy.net/api.php/provide/vod' },
];
/* 资源口预置源清单：站单数据在 debug-web/sources.local.js（本地文件，不入版本库），缺失时为空表；
   首次/升版本进资源口自动种入源列表，仅供资源口聚合/搜索/播放（isAdult 隔离常规视图）；扩充清单时改 ZONE_SEED_VER 触发补种 */
const ZONE_SEED_VER = 'v4';
const ZONE_SOURCES = (window.__ZONE_SEED || []).map(([name, url]) => ({ name, url, isAdult: true }));
const saveSrc = () => localStorage.setItem('libretvaz_sources', JSON.stringify(SRC));

/* ---- 订阅解析：从完整调试台 app.js 原样移植（对应 SubscriptionParser.kt） ---- */
const PRESETS = [
  { name: 'noimank 多仓（5源可用）', url: 'https://gitlab.com/noimank/tvbox/-/raw/main/tvboxmuti.json' },
  { name: 'Liucn 单仓（2源可用）', url: 'https://raw.liucn.cc/box/m.json' },
  { name: 'Qist 综合（点播+直播）', url: 'https://gh-proxy.com/https://raw.githubusercontent.com/qist/tvbox/master/0821.json' },
  { name: 'tvyuan 云端体检聚合（Actions 每小时测速，实测14源可用）', url: 'https://gh-proxy.com/https://raw.githubusercontent.com/25175/tvyuan/master/tvbox_collect.json' },
  { name: '51源合集（实测35可用）', url: 'https://raw.githubusercontent.com/fadai9991/tvbox-sub/main/tvbox.json' },
];
const ADULT_KEYWORDS = ['伦理片','福利','倫理片','福利片','日本伦理','日本福利','日本福利片','日本伦理片','擦边短剧','擦边','韩国伦理','里番动漫','门事件','萝莉少女','制服诱惑','国产传媒','cosplay','黑丝诱惑','无码','日本无码','有码','日本有码','SWAG','网红主播','色情片','同性片','福利视频','无码高清','有码高清','91视频','自拍偷拍','mini传媒'];
const isAdultType = t => !!t && ADULT_KEYWORDS.some(k => t.includes(k));
const normUrl = norm;
/* 搜索相关性过滤：从完整调试台 app.js 原样移植（对应 CmsParser.isRelevant） */
const normalizeTitle = s => s.toLowerCase().replace(/[\s\p{P}\p{S}]+/gu, '');
function includesKeyword(title, kw) {
  if (!title || !kw) return false;
  const sd = kw[0] >= '0' && kw[0] <= '9', ed = kw[kw.length - 1] >= '0' && kw[kw.length - 1] <= '9';
  let i = title.indexOf(kw);
  while (i >= 0) {
    const before = i > 0 ? title[i - 1] : ' ';
    const after = i + kw.length < title.length ? title[i + kw.length] : ' ';
    if ((!sd || !(before >= '0' && before <= '9')) && (!ed || !(after >= '0' && after <= '9'))) return true;
    i = title.indexOf(kw, i + 1);
  }
  return false;
}
function cnNum(v) {
  const digits = '零一二三四五六七八九';
  const c = v.replace(/两/g, '二');
  if (c.length === 1 && digits.indexOf(c) > 0) return digits.indexOf(c);
  const m = /^([一二三四五六七八九]?)十([一二三四五六七八九]?)$/.exec(c);
  if (m) return (m[1] ? digits.indexOf(m[1]) : 1) * 10 + (m[2] ? digits.indexOf(m[2]) : 0);
  return null;
}
function parseSeasonSuffix(text, allowAnnotations, bareNeedsSpace) {
  const t = text.trim();
  const explicit = new RegExp(allowAnnotations
    ? '^(.*?)\\s*第\\s*([1-9]\\d?|[一二两三四五六七八九十]{1,3})\\s*季.*$'
    : '^(.*?)\\s*第\\s*([1-9]\\d?|[一二两三四五六七八九十]{1,3})\\s*季$').exec(t);
  const bare = new RegExp(bareNeedsSpace ? '^(.*?)\\s+([1-9]\\d?)$' : '^(.*?)\\s*([1-9]\\d?)$').exec(t);
  const m = explicit ? [t, explicit[1], explicit[2]] : bare ? [t, bare[1], bare[2]] : null;
  if (!m) return null;
  if (!normalizeTitle(m[1])) return null;
  const season = /^\d+$/.test(m[2]) ? parseInt(m[2], 10) : cnNum(m[2]);
  if (season == null) return null;
  return { base: m[1], season, explicit: !!explicit };
}
function prepareQuery(wd) {
  const n = normalizeTitle(wd);
  if (!n) return null;
  return { n, season: parseSeasonSuffix(wd, false, false), tokens: wd.trim().split(/\s+/).map(normalizeTitle).filter(Boolean) };
}
function matchesPrepared(nn, q) {
  if (includesKeyword(nn, q.n)) return true;
  return q.tokens.length > 1 && q.tokens.every(t => includesKeyword(nn, t));
}
function isRelevant(name, wd) {
  const q = prepareQuery(wd);
  if (!q) return true;
  const nn = normalizeTitle(name);
  if (!nn) return false;
  const ts = parseSeasonSuffix(name, true, true);
  if (q.season && ts) {
    if (q.season.season !== ts.season) return false;
    if (q.season.explicit || ts.explicit) {
      const bq = prepareQuery(q.season.base);
      if (bq) return matchesPrepared(normalizeTitle(ts.base), bq);
    }
  }
  const rel = matchesPrepared(nn, q);
  if (rel || LS('fongmi_fuzzy', 1) === 0) return rel;
  // 实验室「模糊搜索」：核心片名互含也算命中（源片名常带【高清】/导演名等尾缀）
  const cq = PerfCore.coreName(q.n), cn = PerfCore.coreName(name);
  return !!cq && !!cn && (cn.includes(cq) || cq.includes(cn));
}
const isCmsApi = u => /\/api\.php\/provide\/vod(?![^?#]*\/at\/xml)(?:[\/?#]|$)/i.test(String(u || '').trim());
function jsonLoose(text) {
  let t = text.replace(/^\s*\/\/.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '');
  t = t.replace(/,(\s*[}\]])/g, '$1');
  return JSON.parse(t);
}
function parseRoot(root) {
  const vod = [], live = []; let skipped = 0; let name = null; let format = 'libretv';
  const pushVod = v => { const key = normUrl(v.url); if (!key || vod.some(x => normUrl(x.url) === key)) { skipped++; return; } if (vod.length >= 200) { skipped++; return; } vod.push(v); };
  const pushLive = l => { const key = normUrl(l.url); if (!key || live.some(x => normUrl(x.url) === key)) { skipped++; return; } if (live.length >= 50) { skipped++; return; } live.push(l); };
  if (Array.isArray(root)) {
    const has = root.some(o => o && o.baseUrl);
    if (has && !root.some(o => o && o.url && !o.baseUrl)) {
      format = 'source-catalog';
      for (const o of root) {
        if (o && o.baseUrl && o.enabled !== false && isCmsApi(o.baseUrl)) pushVod({ name: o.name || o.baseUrl, url: normUrl(o.baseUrl), isAdult: !!o.isAdult || isAdultType(o.name) });
        else skipped++;
      }
    } else {
      for (const o of root) {
        if (o && o.url && isCmsApi(o.url)) pushVod({ name: o.name || o.url, url: o.url, detail: o.detail || null, isAdult: !!o.isAdult || isAdultType(o.name) });
        else skipped++;
      }
    }
  } else if (root && typeof root === 'object') {
    name = root.name || null;
    if (root.sites) {
      format = 'tvbox';
      for (const o of root.sites) {
        const api = o && o.api || '';
        if ((o.type === 0 || o.type === 1) && !/^csp_/i.test(api) && isCmsApi(api) && o.searchable !== 0)
          pushVod({ name: o.name || api, url: normUrl(api), isAdult: isAdultType(o.name) });
        else skipped++;
      }
      for (const o of root.lives || []) {
        if (o && /^https?:\/\//i.test(o.url || '') && !/^proxy:\/\//i.test(o.url))
          pushLive({ name: o.name || o.url, url: o.url, epg: o.epg || null });
        else skipped++;
      }
    } else if (root.sources) {
      for (const o of root.sources) if (o && o.url) pushVod({ name: o.name || o.url, url: o.url, detail: o.detail || null, isAdult: !!o.isAdult || isAdultType(o.name) }); else skipped++;
      for (const o of root.liveSources || []) if (o && o.url) pushLive({ name: o.name || o.url, url: o.url, epg: o.epg || null }); else skipped++;
    } else skipped++;
  }
  if (!vod.length && !live.length) throw new Error(`未找到可导入的数据源（跳过 ${skipped} 个不兼容项）`);
  return { name, format, vod, live, skipped, repos: 0, reposFailed: 0 };
}
async function parseExpanded(text, fetcher) {
  const root = jsonLoose(text);
  if (root && !Array.isArray(root) && !root.sites && !root.sources && Array.isArray(root.urls)) {
    const entries = root.urls.filter(o => o && /^https?:\/\//i.test(o.url || '')).slice(0, 20);
    const settled = await Promise.allSettled(entries.map(async e => parseRoot(jsonLoose(await fetcher(e.url)))));
    const vod = [], live = []; let reposFailed = 0; const seenV = new Set(), seenL = new Set();
    for (const r of settled) {
      if (r.status !== 'fulfilled') { reposFailed++; continue; }
      for (const v of r.value.vod) { const k = normUrl(v.url); if (!seenV.has(k) && vod.length < 200) { seenV.add(k); vod.push(v); } }
      for (const l of r.value.live) { const k = normUrl(l.url); if (!seenL.has(k) && live.length < 50) { seenL.add(k); live.push(l); } }
    }
    if (!vod.length && !live.length) throw new Error(`多仓展开失败 ${reposFailed}/${entries.length}，且无可导入源`);
    return { name: root.name || null, format: 'multi-repo', vod, live, skipped: entries.length - settled.filter(r => r.status === 'fulfilled').length, repos: entries.length, reposFailed };
  }
  return parseRoot(root);
}
function openSrcMgr() {
  syncSrc();
  if (Home.srcIdx >= SRC.vod.length) Home.srcIdx = 0;
  $('#srcModal').classList.add('open');
  drawSrcMgr();
  Focus.set($('#srcMgr').querySelector('[data-focus]'));
}
function closeSrcMgr() { $('#srcModal').classList.remove('open'); }
function drawSrcMgr() {
  const idx = SRC.vod.map((s, i) => [i, s]);
  const srcRows = list => list.map(([i, s]) => `<div class="srcrow"><span class="n">${esc(s.name)}</span>${hb(s)}<span class="url">${esc(s.url)}</span><button class="rm" data-focus data-rmv="${i}">删除</button></div>`).join('');
  const zoneRows = idx.filter(([, s]) => s.isAdult);
  const normals = idx.filter(([, s]) => !s.isAdult);
  $('#srcMgr').innerHTML = `
    <div class="hrow" style="margin-bottom:10px;flex-wrap:wrap">
      <button class="btn" data-focus id="smAdd">＋ 添加内置已测源</button>
      <button class="btn" data-focus id="smSubNow">立即更新订阅</button>
      <button class="btn${autoOn() ? ' on' : ''}" data-focus id="smAuto">自动维护：${autoOn() ? '开' : '关'}</button>
      <button class="btn" data-focus id="smClear">清除所有点播源</button>
      <button class="btn" data-focus id="smClose">关闭(Esc)</button>
    </div>
    <p class="muted" style="margin:0 0 4px">订阅 ${SRC.subs.length} 条（每 24h 自动重拉合并新源）· 死源连续失败 ${DEAD_FAILS} 次标失效，满 7 天自动移除 · 当前源失效自动切换最快健康源（跳过成人源）</p>
    <h3>导入订阅（复用完整调试台同款解析：tvbox / 多仓 urls / 内置格式）</h3>
    <div class="sbox" style="padding:6px 0">
      <input id="subUrl" placeholder="粘贴订阅地址（.json 配置或 TVBox 多仓）" data-focus style="flex:1">
      <button class="btn" data-focus id="smImport">导入</button>
    </div>
    <div class="hrow" id="presetRow" style="flex-wrap:wrap">
      ${PRESETS.map(p => `<button class="btn" data-focus data-preset="${esc(p.url)}">${esc(p.name)}</button>`).join('')}
    </div>
    <p id="impMsg" class="muted" style="margin:6px 0;min-height:20px"></p>
    <div style="margin:4px 0 10px"><button class="btn" data-focus id="smQrBtn">📱 手机推送订阅</button></div>
    <h3>点播源 · 常规（${normals.length}）</h3>
    ${srcRows(normals) || '<p class="muted">无点播源</p>'}
    ${zoneRows.length ? `<h3>专区源 · 仅资源口可见（${zoneRows.length}）</h3>${srcRows(zoneRows)}` : ''}
    <h3>直播源（${SRC.live.length}）</h3>
    ${SRC.live.map((l, i) => `<div class="srcrow"><span class="n">${esc(l.name)}</span><span class="url">${esc(l.url)}</span><button class="rm" data-focus data-rml="${i}">删除</button></div>`).join('') || '<p class="muted">无直播源</p>'}`;
  $('#smAdd').onclick = () => {
    const has = new Set(SRC.vod.map(v => norm(v.url)));
    const add = BUILTIN_SOURCES.filter(s => !has.has(norm(s.url)));
    if (!add.length) return toast('内置源已全部添加');
    SRC.vod = [...SRC.vod, ...add].slice(0, 200);
    saveSrc(); toast(`已添加 ${add.length} 个已测源`); drawSrcMgr(); refreshAfterSourceChange();
  };
  $('#smClear').onclick = () => askYes('确认清除所有点播源？\n（将同时关闭自动维护，避免订阅自动回填——方便手动测试导入；可在「自动维护」按钮再打开）', () => {
    localStorage.setItem('fongmi_auto', '0');
    syncSrc();
    SRC.vod = []; saveSrc();
    Home.srcIdx = 0;
    Vod.items = []; Vod.page = 1; Vod.more = false; Vod.tab = null;
    drawSrcMgr(); refreshAfterSourceChange();
  });
  $('#smClose').onclick = closeSrcMgr;
  $('#smAuto').onclick = () => {
    localStorage.setItem('fongmi_auto', autoOn() ? '0' : '1');
    drawSrcMgr();
    toast(autoOn() ? '源自动维护已开启' : '自动维护已关闭：不再测活/更新订阅/自动切换');
  };
  $('#smSubNow').onclick = () => { localStorage.setItem('fongmi_subs_ts', '0'); toast('正在更新订阅…'); sourceGuard(); };
  $('#smImport').onclick = () => doImport($('#subUrl').value);
  $('#subUrl').onkeydown = e => { if (e.key === 'Enter') doImport($('#subUrl').value); };
  $('#presetRow').onclick = e => { const b = e.target.closest('[data-preset]'); if (b) { $('#subUrl').value = b.dataset.preset; doImport(b.dataset.preset); } };
  $('#srcMgr').onclick = e => {
    const rv = e.target.closest('[data-rmv]'); if (rv) { SRC.vod.splice(+rv.dataset.rmv, 1); saveSrc(); if (Home.srcIdx >= SRC.vod.length) Home.srcIdx = 0; drawSrcMgr(); refreshAfterSourceChange(); }
    const rl = e.target.closest('[data-rml]'); if (rl) { SRC.live.splice(+rl.dataset.rml, 1); saveSrc(); if (Live.srcIdx >= SRC.live.length) Live.srcIdx = 0; drawSrcMgr(); }
  };
  $('#smQrBtn').onclick = () => openQrModal('sub');
}

/* 订阅导入：解析器从完整调试台 app.js 移植（jsonLoose/parseRoot/parseExpanded） */
async function doImport(url) {
  url = (url || '').trim();
  const msg = $('#impMsg'); if (!url) return;
  if (msg) msg.textContent = '拉取解析中…';
  // raw.githubusercontent 直连不通时自动换 gh-proxy 前缀（A5 51源合集预设兜底）
  const getSub = async u => {
    try { return await httpGet(u, 20000); }
    catch (e) {
      if (/^https:\/\/raw\.githubusercontent\.com\//i.test(u)) return await httpGet('https://gh-proxy.com/' + u, 20000);
      throw e;
    }
  };
  try {
    const text = await getSub(url);
    const p = await parseExpanded(text, u => getSub(u));
    syncSrc(); // 拉取期间其它标签页可能已改动，合并前以最新存储为准
    const oldVod = new Set(SRC.vod.map(v => norm(v.url)));
    const oldLive = new Set(SRC.live.map(l => norm(l.url)));
    const nv = p.vod.filter(v => !oldVod.has(norm(v.url)));
    const nl = p.live.filter(l => !oldLive.has(norm(l.url)));
    SRC.vod = [...SRC.vod, ...nv].slice(0, 200);
    SRC.live = [...SRC.live, ...nl].slice(0, 50);
    if (!SRC.subs.includes(normUrl(url))) SRC.subs.push(normUrl(url));
    saveSrc();
    alignZonePool(); // 订阅内命中预置站单的源（含同站路径变体）即收进专区池并去重
    if (msg) msg.innerHTML = `<span class="ok">导入成功（${p.format}）：点播 +${nv.length} · 直播 +${nl.length}${p.repos ? ` · 仓库 ${p.repos - p.reposFailed}/${p.repos}` : ''}${p.skipped ? ` · 跳过 ${p.skipped}` : ''}</span>`;
    drawSrcMgr(); refreshAfterSourceChange();
  } catch (e) {
    if (msg) msg.innerHTML = `<span class="err">${esc(e.message || String(e))}</span>`;
  }
}
/* P3 源变更惰性化：增删源/后台维护只更新顶栏标签+置脏，绝不重绘正在看的页面；
   重进点播时消费（等效原强刷含 tab 重置），浏览中按下拉翻页则从第 1 页含新源重拉 */
function refreshAfterSourceChange() {
  Probe.rows = null;
  $('#siteName').textContent = siteLabel();
  App.srcDirty = true;
}

/* ================= 源自维护引擎（基础版） =================
 * 健康档案 + 启动守护（当前源失效自动切最快健康源）+ 后台补测 + 订阅自动更新 + 死源清理
 * 基础版先保证源范围与自动化，播放中暂停探测等性能优化后置 */
const DEAD_FAILS = 3, DEAD_REMOVE_MS = 7 * 864e5, RECHECK_MS = 6 * 36e5, SUBS_REFRESH_MS = 24 * 36e5;
let HEALTH = LS('fongmi_health', {});
const saveHealth = () => localStorage.setItem('fongmi_health', JSON.stringify(HEALTH));
const healthOf = u => HEALTH[normUrl(u)] || null;
const isDead = u => { const h = healthOf(u); return !!h && h.fails >= DEAD_FAILS; };
const autoOn = () => !!LS('fongmi_auto', 1);
const hb = s => {
  const h = healthOf(s.url); if (!h) return '';
  const dead = h.fails >= DEAD_FAILS;
  return `<span class="${dead ? 'err' : 'ok'}" style="font-size:11px;flex:none">${dead ? '✗失效' : '✓' + (h.ms ?? '') + 'ms'}</span>`;
};
function recordOk(s, ms) { HEALTH[normUrl(s.url)] = { ok: true, ms, fails: 0, err: null, ts: Date.now(), deadSince: null }; saveHealth(); }
function recordFail(s, err) {
  const old = healthOf(s.url) || {};
  const fails = (old.fails || 0) + 1;
  HEALTH[normUrl(s.url)] = { ok: false, ms: old.ms ?? null, fails, err, ts: Date.now(), deadSince: fails >= DEAD_FAILS ? (old.deadSince || Date.now()) : null };
  saveHealth();
}
async function probeOne(s) {
  const t0 = performance.now();
  try {
    const types = parseTypes(await httpGet(listUrl(s.url, 'ac=list'), 8000));
    if (!types.length) throw new Error('ac=list 未返回分类');
    recordOk(s, Math.round(performance.now() - t0));
    return true;
  } catch (e) { recordFail(s, String(e.message || e).slice(0, 60)); return false; }
}
const BgProbe = { running: false };
async function probeRestBackground() {
  if (BgProbe.running) return;
  BgProbe.running = true;
  let qi = 0;
  const worker = async () => {
    while (qi < SRC.vod.length) {
      const s = SRC.vod[qi++];
      const h = healthOf(s.url);
      if (h && Date.now() - h.ts < 60e3) continue;
      await probeOne(s);
      if (App.view === 'probe') drawProbeRows();
    }
  };
  await Promise.all([worker(), worker()]);
  BgProbe.running = false;
}
function pickBestSource() {
  const ranked = SRC.vod
    .map((s, i) => ({ s, i, h: healthOf(s.url) }))
    .filter(x => !x.s.isAdult && x.h && x.h.ok && x.h.fails < DEAD_FAILS)
    .sort((a, b) => (a.h.ms ?? 1e9) - (b.h.ms ?? 1e9));
  return ranked.length ? ranked[0].i : -1;
}
/* 专区源不参与 7 天自动移除（P14）：测挂只标死沉底——专区接口受网络波动影响频繁，
   清掉后按版本号种子不会自动补回，会导致资源口永久变空 */
function pruneDeadSources() {
  const keep = SRC.vod.filter(s => { if (s.isAdult) return true; const h = healthOf(s.url); return !(h && h.deadSince && Date.now() - h.deadSince > DEAD_REMOVE_MS); });
  const removed = SRC.vod.length - keep.length;
  if (removed) {
    SRC.vod = keep;
    if (Home.srcIdx >= SRC.vod.length) Home.srcIdx = 0;
    saveSrc(); refreshAfterSourceChange();
    toast(`已清理 ${removed} 个连续失效满 7 天的源（翻页或重进后生效）`);
  }
}
async function refreshSubsIfStale() {
  const missing = PRESETS.map(p => normUrl(p.url)).filter(u => !SRC.subs.includes(u));
  if (missing.length) { SRC.subs = [...SRC.subs, ...missing]; localStorage.setItem('fongmi_subs_ts', '0'); saveSrc(); }
  const ts = +LS('fongmi_subs_ts', 0);
  if (SRC.vod.length && Date.now() - ts < SUBS_REFRESH_MS) return;
  localStorage.setItem('fongmi_subs_ts', String(Date.now()));
  let added = 0;
  await pMap(SRC.subs.slice(0, 5), async sub => {
    try {
      const p = await parseExpanded(await httpGet(sub, 20000), u => httpGet(u, 20000));
      const have = new Set(SRC.vod.map(v => normUrl(v.url)));
      const nv = p.vod.filter(v => !have.has(normUrl(v.url)));
      if (nv.length) { SRC.vod = [...SRC.vod, ...nv].slice(0, 200); added += nv.length; }
    } catch { /* 订阅暂时不可达，下个周期再试 */ }
  }, 3);
  if (added) { // 并发去重：两个订阅同时返回同 URL 时只留一个
    const seen = new Set();
    SRC.vod = SRC.vod.filter(v => !seen.has(normUrl(v.url)) && seen.add(normUrl(v.url)));
    saveSrc(); alignZonePool(); refreshAfterSourceChange(); toast(`订阅自动更新：新增 ${added} 个源（翻页或重进后生效）`);
  }
}
let guarding = false;
async function sourceGuard() {
  if (guarding || !autoOn()) return;
  guarding = true;
  try {
    pruneDeadSources();
    await refreshSubsIfStale();
    if (!SRC.vod.length) return;
    let s = SRC.vod[Home.srcIdx];
    const h = s && healthOf(s.url);
    if (s && (!h || !h.ok || Date.now() - h.ts > RECHECK_MS)) await probeOne(s);
    s = SRC.vod[Home.srcIdx];
    if (s && isDead(s.url)) {
      let best = pickBestSource();
      if (best < 0) { await probeRestBackground(); best = pickBestSource(); }
      if (best >= 0) {
        Home.srcIdx = best;
        localStorage.setItem('fongmi_site_idx', String(best));
        toast(`当前源已失效，自动切换到「${SRC.vod[best].name}」（翻页或重进后生效）`);
        refreshAfterSourceChange();
      } else toast('所有点播源均不可用：请检查网络，或在源管理重新导入');
    }
    probeRestBackground();
  } finally { guarding = false; }
}

/* ================= 卡片渲染（adapter_vod_rect 同款） ================= */
/* 海报双向降级（docs/2026-10-30-图片播放与拼音候选提速方案.md ①）：
   浏览器直连原图（可借系统代理规则、多域名并发不受同源 6 连接限）→ 失败切 /proxy（服务端补 Referer 解防盗链）→ 再错保留 .ph 片名占位 */
function picHtml(pic) {
  if (!pic) return '';
  const p = esc(pic);
  return `<img data-ox="${p}" data-pi="0" src="${p}" loading="lazy" decoding="async" referrerpolicy="no-referrer">`;
}
window.addEventListener('error', e => {
  const img = e.target;
  if (!img || img.tagName !== 'IMG' || img.dataset.pi == null) return;
  if (img.dataset.pi === '0') { img.dataset.pi = '1'; img.src = '/proxy?url=' + encodeURIComponent(img.dataset.ox); }
  else img.remove();
}, true);
function cardHtml(it, i, opts = {}) {
  return `<div class="card" data-focus data-i="${i}" data-aid="${esc(String(it.id ?? ''))}">
    ${opts.del ? '<button class="del" data-focus data-del="1" title="删除">×</button>' : ''}
    <div class="pic"><div class="ph">${esc(it.name)}</div>${picHtml(it.pic)}
      ${it.year ? `<span class="year">${esc(it.year)}</span>` : ''}
      ${opts.showSite && it.source ? `<span class="site">${esc(it.source.name)}</span>` : ''}
      ${it.updated ? '<span class="upd"></span>' : ''}
      ${it.remarks ? `<span class="remark">${esc(it.remarks)}</span>` : ''}</div>
    <div class="name">${esc(it.name)}</div></div>`;
}
function bindGrid(el, items, onPick, onDel) {
  el.onclick = e => {
    const c = e.target.closest('[data-i]'); if (!c) return;
    if (e.target.closest('[data-del]')) { onDel?.(+c.dataset.i); return; }
    onPick(items[+c.dataset.i]);
  };
}

/* ================= 设置页（A2 四列「标签+值」网格，对标影视仓设置页；仅实装真实行为项，无占位） ================= */
const CACHE_TIERS = [
  { label: '短（聚合1分/搜索3分）', agg: 60e3, search: 3 * 60e3 },
  { label: '标准（聚合3分/搜索10分）', agg: 3 * 60e3, search: 10 * 60e3 },
  { label: '长（聚合10分/搜索30分）', agg: 10 * 60e3, search: 30 * 60e3 },
  { label: '超长（聚合30分/搜索2小时）', agg: 30 * 60e3, search: 2 * 3600e3 },
];
const cacheTier = () => { const c = LS('fongmi_cache', null); if (!Array.isArray(c)) return 1; const i = CACHE_TIERS.findIndex(t => t.agg === c[0] && t.search === c[1]); return i < 0 ? 1 : i; };
const aggTtl = () => CACHE_TIERS[cacheTier()].agg;
const searchTtl = () => CACHE_TIERS[cacheTier()].search;
/* 通用配置弹窗（枚举/Tab排序/实验室共用一个容器）：openCfg(title, html, onPick(v)) */
let cfgOnPick = null;
function openCfg(title, html, onPick) {
  $('#cfgTitle').textContent = title;
  $('#cfgBody').innerHTML = html;
  cfgOnPick = onPick || null;
  $('#cfgModal').classList.add('open');
  Focus.set($('#cfgBody').querySelector('[data-focus]') || $('#cfgCancel'));
}
function closeCfg() {
  if (!$('#cfgModal').classList.contains('open')) return;
  $('#cfgModal').classList.remove('open');
  cfgOnPick = null;
  if (App.view === 'settings') Focus.set($('.setrow'));
}
/* A3 顶部导航 Tab 配置（P8 后为点播页顶部导航栏）：fongmi_tabs=[{nav,show}]，顺序即优先级 */
const TAB_META = [
  ['vod', '点播', 'VodActivity'], ['live', '直播', 'LiveActivity'], ['search', '搜索', 'SearchActivity'],
  ['keep', '收藏', ''], ['hist', '历史', ''], ['probe', '连接', '源测速探活'],
  ['sources', '源管理', '导入订阅/源设置'], ['settings', '设置', '播放与界面偏好'],
];
/* P9：点播即首页，导航栏不再出现「点播」项（存档里的旧 vod 项一并滤掉） */
const getTabs = () => {
  const saved = LS('fongmi_tabs', null);
  const known = n => n !== 'vod' && TAB_META.some(t => t[0] === n);
  const list = Array.isArray(saved) && saved.length ? saved.filter(t => t && known(t.nav)) : [];
  for (const [nav] of TAB_META) if (known(nav) && !list.some(t => t.nav === nav)) list.push({ nav, show: 1 });
  return list;
};
const saveTabs = arr => localStorage.setItem('fongmi_tabs', JSON.stringify(arr.map(t => ({ nav: t.nav, show: t.show }))));
function openTabCfg() {
  const render = () => {
    const tabs = getTabs();
    return tabs.map((t, i) => {
      const m = TAB_META.find(x => x[0] === t.nav);
      return `<div class="tabrow"><span>${i + 1}. ${m[1]}${t.show ? '' : '（已隐藏）'}</span>`
        + `<button class="chip" data-focus data-v="tg:${t.nav}">${t.show ? '隐藏' : '显示'}</button>`
        + `<button class="chip${i === 0 ? ' off' : ''}" data-focus data-v="up:${t.nav}">↑</button>`
        + `<button class="chip${i === tabs.length - 1 ? ' off' : ''}" data-focus data-v="dn:${t.nav}">↓</button></div>`;
    }).join('') + '<p class="muted" style="font-size:12px;margin:8px 0 0">↑↓ 调整优先级，「隐藏/显示」控制点播页顶部导航栏（点播为首页不入栏，刷新固定在栏首）</p>';
  };
  openCfg('导航栏（顺序=优先级）', render(), v => {
    const [act, nav] = String(v).split(':');
    const arr = getTabs(); const at = arr.findIndex(x => x.nav === nav); if (at < 0) return;
    if (act === 'tg') arr[at].show = arr[at].show ? 0 : 1;
    else if (act === 'up' && at > 0) [arr[at - 1], arr[at]] = [arr[at], arr[at - 1]];
    else if (act === 'dn' && at < arr.length - 1) [arr[at + 1], arr[at]] = [arr[at], arr[at + 1]];
    saveTabs(arr);
    $('#cfgBody').innerHTML = render();
    const nb = $('#cfgBody').querySelector(`[data-v="${v}"]`); if (nb) Focus.set(nb);
  });
}
/* A3 实验室开关组：全部有真实行为（默认值=影视仓文案「已开启××/已关闭××」口径，自动换源默认关） */
const LAB_DEFS = [
  ['fongmi_linespeed', '线路测速（播放页显示各线速度）', 1],
  ['fongmi_autobestline', '测速后自动选最快线', 1],
  ['fongmi_fuzzy', '模糊搜索（片名核心词容错）', 1],
  ['fongmi_autosrc', '全线失败自动换源（搜关联切源续播）', 0],
  ['fongmi_histmerge', '历史记录合并（按片名去重）', 1],
  ['fongmi_epfull', '播放页点击剧集自动全屏', 1],
];
function openLabCfg() {
  const render = () => LAB_DEFS.map(([k, label, def]) => {
    const on = LS(k, def) !== 0;
    return `<div class="tabrow"><span>${label}</span><button class="chip${on ? ' on' : ''}" data-focus data-v="${k}">${on ? '已开启' : '已关闭'}</button></div>`;
  }).join('') + '<div class="tabrow"><span>🎦 资源口（二级专区聚合）</span><button class="chip" data-focus data-v="__zone">进入</button></div>';
  openCfg('影视仓实验室', render(), v => {
    if (v === '__zone') { closeCfg(); switchView('zone'); return; }
    const d = LAB_DEFS.find(x => x[0] === v); if (!d) return;
    localStorage.setItem(v, JSON.stringify(LS(v, d[2]) !== 0 ? 0 : 1));
    $('#cfgBody').innerHTML = render();
    const nb = $('#cfgBody').querySelector(`[data-v="${v}"]`); if (nb) Focus.set(nb);
  });
}
const BOOL2 = [[1, '开启'], [0, '关闭']];
const SETTING_DEFS = [
  { k: 'fongmi_autonext', label: '自动连播', def: 1, opts: BOOL2 },
  { k: 'fongmi_autoline', label: '失败自动换线', def: 1, opts: BOOL2 },
  { k: 'fongmi_resume', label: '跨会话续播', def: 1, opts: BOOL2 },
  { k: 'fongmi_qrauto', label: '扫码自动导入', def: 1, opts: BOOL2 },
  { k: 'fongmi_threads', label: '搜索线程', def: 8, opts: [[8, '8'], [12, '12'], [16, '16'], [24, '24'], [36, '36'], [48, '48']] },
  { k: 'fongmi_histmax', label: '历史记录', def: 60, opts: [[30, '30条'], [60, '60条'], [100, '100条']] },
  { k: 'fongmi_cache', label: '缓存时长', render: () => CACHE_TIERS[cacheTier()].label },
  { k: 'cache_purge', label: '清理缓存', render: () => fmtBytes(cacheBytes()) + ' · 点击清理' },
  { k: 'tabs', label: '导航栏', render: () => '显示/隐藏 · 优先级' },
  { k: 'lab', label: '影视仓实验室', render: () => '模糊搜索/自动换源/全屏…' },
];
function settingValue(def) {
  if (def.render) return def.render();
  const v = LS(def.k, def.def);
  const o = def.opts.find(([x]) => x === v);
  return o ? o[1] : String(v);
}
function openSetting(def) {
  if (def.k === 'tabs') return openTabCfg();
  if (def.k === 'lab') return openLabCfg();
  if (def.k === 'cache_purge') {
    const sz = fmtBytes(cacheBytes());
    return askYes(`确认清空全部本地缓存？（当前约 ${sz}；分类/列表/详情/搜索分片，播放进度与收藏不受影响）`, () => {
      purgeCache(''); typesCache.clear(); toast('缓存已清空'); renderSettings();
    });
  }
  if (def.k === 'fongmi_cache') {
    return openCfg('缓存时长', CACHE_TIERS.map((t, i) => `<button class="chip${i === cacheTier() ? ' on' : ''}" data-focus data-v="${i}">${t.label}</button>`).join(''), v => {
      const t = CACHE_TIERS[+v]; localStorage.setItem('fongmi_cache', JSON.stringify([t.agg, t.search]));
      closeCfg(); renderSettings();
    });
  }
  openCfg(def.label, def.opts.map(([v, l]) => `<button class="chip${LS(def.k, def.def) === v ? ' on' : ''}" data-focus data-v="${v}">${l}</button>`).join(''), v => {
    localStorage.setItem(def.k, JSON.stringify(+v));
    closeCfg(); renderSettings();
  });
}
function renderSettings() {
  body.innerHTML = `<div class="eprow" style="padding:14px 28px 0"><button class="btn" data-focus id="setBack">← 点播</button></div>
    <main id="scroller"><div class="setgrid" id="setGrid">${SETTING_DEFS.map((d, i) => `<div class="setrow" data-focus data-s="${i}"><span>${d.label}</span><b>${esc(settingValue(d))}</b></div>`).join('')}</div></main>`;
  bindBack('setBack', () => switchView('vod'));
  $('#setGrid').onclick = e => {
    const r = e.target.closest('[data-s]'); if (!r) return;
    openSetting(SETTING_DEFS[+r.dataset.s]);
  };
  Focus.set($('.setrow'));
}

/* ================= 首页状态（P8 起点播页即 App 首页，Home 仅存当前源/聚合模式） ================= */
const Home = { srcIdx: Math.max(0, LS('fongmi_site_idx', 0)), aggMode: localStorage.getItem('fongmi_agg') === '1' };
function onScroll() {
  const sc = $('#scroller'); if (!sc) return;
  $('#bar').classList.toggle('hide', sc.scrollTop > 90);
  if (App.view !== 'vod' && App.view !== 'zone') return;
  const bottom = sc.scrollTop + sc.clientHeight > sc.scrollHeight - 200;
  if (Vod.agg) { if (Vod.more && !Vod.loading && !Vod.loadingMore && bottom) aggLoadMore(); return; } // 聚合墙（点播聚合/资源口）滚到底续轮
  if (App.view !== 'vod') return;
  if (Vod.preset && !Vod.pSrv && Vod.page >= 10) return; // R8 三级 (b)：客户端地区过滤 10 页封顶停止翻页
  if (Vod.more && !Vod.loading && bottom) loadVod(Vod.page + 1);
}

/* ================= 点播页（VodActivity：类型 chips + 网格） ================= */
const Vod = { types: [], tab: null, page: 1, items: [], loading: false, more: false, reqId: 0, filters: null, sub: '', area: '', year: '', lang: '', showFilters: false, merged: null, agg: false, preset: '', pSrv: null, presetInfo: '', aggCat: null, loadingMore: false, win: 240 };
function renderVod() {
  if (App.srcDirty) { App.srcDirty = false; Vod.tab = null; } // 重进点播消费源变更（等效原强刷的 tab 重置）
  const hist = LS('fongmi_hist', []).filter(h => SRC.vod[h.si] && !isZoneRec(h));
  const keepAll = LS('fongmi_keep', []).filter(x => !isZoneRec(x)); const keepNu = keepAll.filter(x => x.updated).length;
  const subs = { keep: `${keepAll.length} 部${keepNu ? ' · ' + keepNu + ' 部更新' : ''}`, hist: `${hist.length} 部` };
  const navs = getTabs().filter(t => t.show).map(t => {
    const m = TAB_META.find(x => x[0] === t.nav);
    return `<div class="func" data-focus data-nav="${t.nav}"><b>${m[1]}</b><i>${subs[t.nav] ?? m[2]}</i></div>`;
  }).join('');
  body.innerHTML = `<div class="funcs" id="vodNav" style="padding:4px 28px 0">
      <div class="func" data-focus id="vodRefresh" title="清除本页相关缓存分片并重新拉取"><b>🔄 刷新</b><i>清缓存重拉本页</i></div>${navs}
      <!-- 服务器模式入口保留备用：调试台退役后暂隐藏，恢复时去掉 display:none 即可 -->
      <div class="func" data-focus data-nav="server" style="display:none"><b>服务器模式</b><i>打开调试台</i></div>
    </div>
    <div class="hrow" id="typeRow" style="padding:6px 28px"><span class="muted" style="padding:6px 0">分类加载中…</span></div>
    <div class="hrow" id="subRow" style="padding:0 28px 6px"></div>
    <main id="scroller"><div class="grid" id="vodGrid"></div></main>`;
  $('#vodNav').onclick = e => {
    const f = e.target.closest('[data-nav]'); if (!f) return;
    const nav = f.dataset.nav;
    if (nav === 'sources') return openSrcMgr();
    if (nav === 'server') return void window.open('/', '_blank');
    if (nav === 'search') return openSearch(false); // 点播池入口（三栏搜索页）
    switchView(nav);
  };
  $('#vodRefresh').onclick = () => { // 只清点播相关分片（历史/收藏/测速不动），重走 loadTypes→缓存已空即全量拉新
    if (Home.aggMode) purgeCache('aggs:');
    else {
      const s = SRC.vod[Home.srcIdx];
      if (s) { typesCache.delete(s.url); purgeCache('types:' + s.url); purgeCache(`vod:${s.url}|`); purgeCache(`mrg:${s.url}|`); }
    }
    toast('已清本页缓存，重新拉取中…');
    loadTypes();
  };
  $('#scroller').addEventListener('scroll', onScroll);
  loadTypes();
  Focus.set($('#vodNav').querySelector('[data-focus]') || $('#typeRow').querySelector('[data-focus]') || $('#vodRefresh')); // 初始焦点=导航栏首项（FongMi 顶栏式）
}
function loadSiteData() { Vod.reqId++; }
/* 有子类的父分类一律聚合子类展示（非凡/量子等 CMS 父 id 直查为空壳或仅 1~3 条杂项） */
const applyTabMerged = () => {
  const kids = Vod.tab ? Vod.types.filter(c => c.pid === Vod.tab.id && c.id !== Vod.tab.id) : [];
  Vod.merged = kids.length ? kids : null;
};
const isTopType = t => t.pid === '0' || t.pid === '' || t.pid === 'null';
async function loadTypes() {
  const s = SRC.vod[Home.srcIdx];
  if (!s) { $('#typeRow') && ($('#typeRow').innerHTML = '<span class="muted">无点播源：点顶部导航「源管理」导入订阅或添加内置源</span>'); const g = $('#vodGrid'); if (g) g.innerHTML = ''; return; }
  const rid = ++Vod.reqId;
  if (Home.aggMode) { // 聚合模式（源选择弹窗进入）：固定四大类标签，数据跨全部健康源合并
    Vod.agg = true; Vod.types = []; Vod.filters = null; Vod.merged = null;
    const tabs = [{ id: '', name: '🌐 最新' }, ...AGG_CATS.map(c => ({ id: 'cat:' + c.key, name: c.key }))];
    const cur = Vod.tab ? 'cat:' + Vod.tab.id : '';
    $('#typeRow').innerHTML = tabs.map(t => `<button class="chip${t.id === cur ? ' on' : ''}" data-focus data-tid="${esc(t.id)}">${esc(t.name)}</button>`).join('');
    $('#typeRow').onclick = e => {
      const b = e.target.closest('[data-tid]'); if (!b) return;
      const id = b.dataset.tid;
      if ((Vod.tab && 'cat:' + Vod.tab.id === id) || (!Vod.tab && id === '')) { // R8(agg)：再点当前分类=展开/收起地区筛选行（数据在客户端，只重绘不重拉）
        if (!id || !PerfCore.VOD_FILTERS[Vod.tab.name]) return;
        Vod.showFilters = !Vod.showFilters;
        drawSubRow();
        Focus.set(Vod.showFilters ? ($('#subRow')?.querySelector('[data-focus]') || b) : b);
        return;
      }
      document.querySelectorAll('#typeRow .chip').forEach(c => c.classList.toggle('on', c === b));
      $('#subRow').innerHTML = '';
      Vod.showFilters = false; resetFilterVals();
      if (id === '') { Vod.tab = null; loadAgg(); }
      else { const cat = AGG_CATS.find(c => 'cat:' + c.key === id); Vod.tab = { id: cat.key, name: cat.key }; loadAggCat(cat); }
    };
    if (Vod.tab && !AGG_CATS.some(c => c.key === Vod.tab.id)) Vod.tab = null;
    if (Vod.tab) loadAggCat(AGG_CATS.find(c => c.key === Vod.tab.id)); else loadAgg();
    return;
  }
  Vod.agg = false;
  Vod.types = cacheGet('types:' + s.url) || []; // 先用缓存分类秒出标签，拉新后替换
  const drawTabs = () => {
    if (Vod.tab) { // 换源/刷新后按 id 重解析当前分类，聚合集合随新分类树重建
      Vod.tab = Vod.types.find(t => t.id === Vod.tab.id) || null;
      applyTabMerged();
    }
    const tops = Vod.types.filter(isTopType);
    const tabList = tops.length ? tops : Vod.types;
    const tabs = [{ id: '', name: '🏠 最新' }, ...tabList.map(t => ({ id: t.id, name: t.name }))]; // 原「首页」chip：无分类全量最新，即更新推荐等效
    const cur = Vod.tab?.id ?? '';
    const row = $('#typeRow'); if (!row || App.view !== 'vod') return;
    row.innerHTML = tabs.map(t => `<button class="chip${t.id === cur ? ' on' : ''}" data-focus data-tid="${esc(t.id)}">${esc(t.name)}</button>`).join('');
    if (!document.contains(Focus.el) || Focus.el?.id === 'vodRefresh') Focus.set(row.querySelector('[data-focus]') || Focus.el); // 重绘后焦点归位首 chip（R7）
    row.onclick = e => {
      const b = e.target.closest('[data-tid]'); if (!b) return;
      const id = b.dataset.tid;
      if (id === '') { // 最新：无分类，直接全量最新
        Vod.tab = null; Vod.filters = null; Vod.showFilters = false; Vod.merged = null;
        resetFilterVals(); $('#subRow').innerHTML = '';
        document.querySelectorAll('#typeRow .chip').forEach(c => c.classList.toggle('on', c === b));
        loadVod(1); return;
      }
      if (Vod.tab && Vod.tab.id === id) { // 再次点击当前分类：展开/收起二级筛选，焦点随行进/归位（两段式统一用 Focus.set）
        Vod.showFilters = !Vod.showFilters;
        drawSubRow();
        Focus.set(Vod.showFilters ? ($('#subRow')?.querySelector('[data-focus]') || b) : b);
        return;
      }
      Vod.tab = Vod.types.find(t => t.id === id) || null;
      Vod.showFilters = false; resetFilterVals();
      Vod.filters = null; applyTabMerged();
      document.querySelectorAll('#typeRow .chip').forEach(c => c.classList.toggle('on', c === b));
      loadVod(1);
    };
  };
  drawTabs();
  loadVod(1);
  try {
    const fresh = parseTypes(await httpGet(apiUrl(s.url, 'ac=list')));
    cacheSet('types:' + s.url, fresh);
    if (rid !== Vod.reqId || App.view !== 'vod') return;
    Vod.types = fresh;
    drawTabs();
  } catch { /* 保留缓存分类树 */ }
}
/* 聚合数据源提供者：本期为站内多源合并；将来接豆瓣榜单只需新增一个 provider（接口实测：
   https://movie.douban.com/j/search_subjects?type=movie|tv&tag=热门&sort=recommend&page_limit=20
   需浏览器 UA + Referer https://movie.douban.com/，且 proxy 需透传请求头、结果要缓存防限流） */
const AGG_CATS = [
  { key: '电影', kw: ['电影', '影片'] },
  { key: '电视剧', kw: ['电视剧', '连续剧', '剧集', '网剧'] },
  { key: '综艺', kw: ['综艺', '节目'] },
  { key: '动漫', kw: ['动漫', '动画', '番'] },
];
/* 聚合墙源池：按测速排前 6，不排除失效源（死的自然沉底）；未测过的按中性 4s 排在快源之后 */
const aggPool = () => {
  const ms = s => {
    const h = healthOf(s.url);
    if (!h) return 4000;
    if (h.fails >= DEAD_FAILS) return 1e9;
    return h.ms ?? 4000;
  };
  return SRC.vod.filter(s => Zone.active ? s.isAdult : !s.isAdult).sort((a, b) => ms(a) - ms(b)).slice(0, 6);
};
/* 各源分类树缓存：聚合分类墙按名称匹配每个源的父分类，再取其子类首页合并 */
const typesCache = new Map();
const getSrcTypes = s => {
  if (!typesCache.has(s.url)) typesCache.set(s.url, (async () => {
    const ck = 'types:' + s.url;
    try { const d = parseTypes(await httpGet(apiUrl(s.url, 'ac=list'), 8000)); cacheSet(ck, d); return d; }
    catch { return cacheGet(ck) || []; } // 慢源/死源用上次分类树兜底
  })());
  return typesCache.get(s.url);
};
const dedupByName = arr => { const seen = new Set(); return arr.filter(it => { const k = normalizeTitle(it.name); return k && !seen.has(k) && seen.add(k); }); };
/* 单个源在聚合墙中贡献的数据：cat=null 取全量最新，否则按名称匹配其父分类并取前 3 个子类首页。
   cat.rlabs（地区预设，如 日剧）：只拉区命中子类（每源前4个）；源无区子类→回落父类混拉靠条目 area/typeName 客户端过滤。
   实测 360/非凡/量子/天天/茅台/速播 电视剧子树均含日剧子类，但默认 slice(0,3) 拉不到——按地区定向抓取修复 */
/* 源的分类树里是否存在命中 cat.rlabs 的地区子类（=该源「收录」了该区资源），聚合墙据此排优先级 */
function hasRegionKid(types, cat) {
  if (!cat || !cat.rlabs) return false;
  const p = types.find(t => isTopType(t) && cat.kw.some(k => (t.name || '').includes(k)));
  if (!p) return false;
  return types.some(c => c.pid === p.id && c.id !== p.id && PerfCore.regionLabels(c.name).some(l => cat.rlabs.includes(l)));
}
async function fetchAggSource(s, cat, pg = 1) {
  if (!cat) return httpGet(listUrl(s.url, `ac=videolist&pg=${pg}`), 8000).then(raw => parseList(raw, s)).catch(() => []);
  const types = await getSrcTypes(s);
  const p = types.find(t => isTopType(t) && cat.kw.some(k => (t.name || '').includes(k)));
  if (!p) return [];
  const kids = types.filter(c => c.pid === p.id && c.id !== p.id);
  let qs;
  if (cat.rlabs) {
    const reg = kids.filter(c => PerfCore.regionLabels(c.name).some(l => cat.rlabs.includes(l)));
    qs = reg.length ? reg.slice(0, 4) : [p];
  } else qs = kids.length ? kids.slice(0, 3) : [p];
  const sub = await pMap(qs, c =>
    httpGet(listUrl(s.url, `ac=videolist&t=${encodeURIComponent(c.id)}&pg=${pg}`), 8000).then(raw => parseList(raw, s)), qs.length); // 子类全并行+8s 快断：慢源不再拖聚合首屏
  return sub.flat();
}
const aggKey = (s, cat) => 'aggs:' + s.url + '|' + (cat ? cat.key + (cat.rlabs ? '@' + cat.rlabs.join(',') : '') : 'latest');
/* 聚合墙（按源分片缓存 + 渐进渲染）：
   ① 先用各源上次分片立即拼出整墙（秒开）② 逐源拉新，一源返回就重画一次，不等最慢源
   ③ 全部分片 3 分钟内新鲜则跳过请求；渲染完后台低并发预取其余分类，切分类同样秒开 */
let aggToken = 0;
async function runAggWall(cat) {
  const grid = $('#vodGrid'); if (!grid) return;
  const rid = ++Vod.reqId, token = ++aggToken;
  const pool = aggPool();
  Vod.aggCat = cat || null; Vod.win = 240; // S4 无限下拉：加载更多按同一分类续页；新墙重置渲染窗口
  const alive = () => rid === Vod.reqId && token === aggToken && Vod.agg && (App.view === 'vod' || App.view === 'zone') && $('#vodGrid');
  /* 队列化渲染：本墙首绘整体按年降序一次（默认最新），此后每个源返回只把「没上墙的片名」按到达序追加队尾——
     已有卡片位置不动，消除逐源合并时的全墙重排闪动 */
  const drawn = new Set(); let firstDrawn = false;
  const merge = () => {
    const all = [];
    for (const s of pool) { const v = cacheRaw(aggKey(s, cat)); if (v && Array.isArray(v.d)) all.push(...v.d); }
    const items = dedupByName(all);
    if (!items.length) return false;
    if (!firstDrawn) { firstDrawn = true; Vod.items = sortByYearDesc(items); }
    else for (const it of items) { const k = normalizeTitle(it.name); if (k && !drawn.has(k)) { drawn.add(k); Vod.items.push(it); } }
    items.forEach(it => drawn.add(normalizeTitle(it.name)));
    Vod.page = 1; Vod.more = true; Vod.loading = false; // S4：聚合墙可无限下拉（拉到空轮才关）
    addWords(items.map(it => it.name), Zone.active);
    drawVodGrid();
    return true;
  };
  let need = [];
  for (const s of pool) {
    const v = cacheRaw(aggKey(s, cat));
    if (!(v && Array.isArray(v.d) && v.d.length && Date.now() - v.t < aggTtl())) need.push(s);
  }
  let mergedOnce = merge(); // 本次运行内是否有过分片合并（全失败时据此给空态，不让网格永久停在「正在聚合…」）
  if (!mergedOnce && need.length) grid.innerHTML = `<p class="muted">正在聚合各源${cat ? '「' + cat.key + (Vod.preset ? '·' + Vod.preset : '') : '最新'}，拉到即显示…</p>`;
  if (cat && cat.rlabs && need.length > 1) { // 地区预设：分类树收录该区的源排前优先拉，先出海报墙（分类树 typesCache 已缓存，不额外增开销）
    const caps = new Map();
    await Promise.all(need.map(async s => caps.set(s.url, hasRegionKid(await getSrcTypes(s), cat))));
    if (!alive()) return;
    need = need.sort((a, b) => (caps.get(b.url) ? 1 : 0) - (caps.get(a.url) ? 1 : 0));
    const hitN = need.filter(s => caps.get(s.url)).length;
    if (!hitN && !mergedOnce) { grid.innerHTML = `<p class="muted">聚合无数据：「${cat.key}·${Vod.preset}」聚合池 ${pool.length} 源均未收录该地区分类，可选「全部」或换源重试<br><span class="muted" style="font-size:12px">聚合池：${esc(pool.map(s => s.name).join('、'))}</span></p>`; toast('聚合无数据'); return; }
  }
  await pMap(need, async s => {
    const fresh = await fetchAggSource(s, cat);
    if (fresh.length) cacheSet(aggKey(s, cat), fresh);
    if (alive()) mergedOnce = merge() || mergedOnce; // 一源返回就重画，新数据去重后增量上墙
  }, 6); // 池上限即 6，全并行：首墙只等最快的那批源返回，逐源增量
  if (alive() && !mergedOnce) { // 无数据也要有明确提示：网格不能停留在「正在聚合…」或空白（分类/筛选行本就保持显示）
    grid.innerHTML = `<p class="muted">聚合无数据：${pool.length} 个源均未返回「${cat ? cat.key + (Vod.preset ? '·' + Vod.preset : '') : '最新'}」（源无此地区分类或拉取超时），可选「全部」或换源重试<br><span class="muted" style="font-size:12px">已尝试源：${esc(pool.map(s => s.name).join('、')) || '（聚合池为空，请先在源设置中导入点播源）'}</span></p>`;
    toast('聚合无数据');
  }
  if (alive() && !cat) aggPrefetch(rid, token, cat);
  else if (alive() && cat) aggPrefetch(rid, token, null);
}
const loadAgg = () => runAggWall(null);
const loadAggCat = cat => runAggWall(cat);
/* S4 无限下拉（图片流式）：聚合墙（点播聚合/资源口）哨兵「加载更多」OK 触发——各源同分类拉下一轮页，
   去重后追加进分片缓存再整墙重并；**只要本轮整墙有新作品就能一直按下去（无轮次上限）**，
   「各源均无新增」才判到底（有些 CMS 无视 pg 恒回首页，按增量判重才不会空转死循环）。
   并发 6、单源 8s 快断、失败静默；epoch/token 防切类换墙串数据；单源模式走既有 loadVod(pg+1)。
   深度上界=每源分片 800 部（防内存/localStorage 膨胀，到界即无新增→自然到底）；300 轮仅为极端保险丝 */
async function aggLoadMore() {
  if (!Vod.agg || Vod.loadingMore || !Vod.more) return;
  const cat = Vod.aggCat || null;
  const rid = Vod.reqId, token = aggToken;
  const pg = (Vod.page || 1) + 1;
  if (pg > 300) { Vod.more = false; drawVodGrid(); return; }
  Vod.loadingMore = true;
  const btn = $('#moreBtn'); if (btn) btn.textContent = '加载中…';
  await pMap(aggPool(), async s => {
    try {
      const more = await fetchAggSource(s, cat, pg);
      if (!more.length) return;
      const k = aggKey(s, cat);
      const old = (cacheRaw(k) && cacheRaw(k).d) || [];
      cacheSet(k, dedupById(old.concat(more)).slice(0, 800)); // 分片深度 800 部/源封顶
    } catch { /* 单源失败跳过 */ }
  }, 6);
  Vod.loadingMore = false;
  if (rid !== Vod.reqId || token !== aggToken || (App.view !== 'vod' && App.view !== 'zone') || !Vod.agg) return;
  const all = [];
  for (const s of aggPool()) { const v = cacheRaw(aggKey(s, cat)); if (v && Array.isArray(v.d)) all.push(...v.d); }
  const items = dedupByName(all);
  const onWall = new Set((Vod.items || []).map(it => normalizeTitle(it.name)));
  const added = items.filter(it => { const k = normalizeTitle(it.name); return k && !onWall.has(k); }); // 队列化：本轮只见过的不再上墙
  Vod.page = pg;
  Vod.more = added.length > 0; // 有新增=还能继续无限拉；全是重复/空=到底
  if (added.length) { Vod.items = (Vod.items || []).concat(added); addWords(added.map(it => it.name), Zone.active); } // 只追加队尾，已有卡片不动
  drawVodGrid();
  toast(Vod.more ? `第 ${pg} 轮：共 ${Vod.items.length} 部（+${added.length}）` : '各源已无新内容（到底了）');
}
/* 预取其余分类的源分片（只入缓存不渲染），低并发避免抢当前请求带宽 */
async function aggPrefetch(rid, token, skip) {
  if (rid !== Vod.reqId || token !== aggToken) return;
  await new Promise(r => setTimeout(r, 2000));
  for (const c of [null, ...AGG_CATS]) {
    if (!c && skip) continue; // 只从最新墙发起「全量+各类」预取
    if (c === skip) continue;
    if (rid !== Vod.reqId || token !== aggToken || (!Home.aggMode && !Zone.active) || (App.view !== 'vod' && App.view !== 'zone')) return; // 资源口同样后台预取分类，切类秒开（P10）
    await pMap(aggPool(), async s => {
      const k = aggKey(s, c); const v = cacheRaw(k);
      if (v && Array.isArray(v.d) && v.d.length && Date.now() - v.t < aggTtl()) return;
      const fresh = await fetchAggSource(s, c);
      if (fresh.length) cacheSet(k, fresh);
    }, 2);
  }
}
const resetFilterVals = () => { Vod.sub = ''; Vod.area = ''; Vod.year = ''; Vod.lang = ''; Vod.preset = ''; Vod.pSrv = null; Vod.presetInfo = ''; };
/* ===== R8 地区预设筛选（docs/2026-10-30-遥控可用性复盘与优化方案.md）=====
   预设族识别：聚合墙 tab.name 即分类键直配 VOD_FILTERS；单源沿 pid 上溯根，根名+本名匹配预设族 */
function presetKeyForTab() {
  if (Vod.agg) return Vod.tab && PerfCore.VOD_FILTERS[Vod.tab.name] ? Vod.tab.name : null;
  if (!Vod.tab) return null;
  let cur = Vod.tab, root = null, hop = 0;
  while (cur && hop++ <= 5) { root = cur; const p = cur.pid; cur = p && p !== '0' && p !== '' && p !== 'null' ? Vod.types.find(t => t.id === p) : null; }
  const nm = ((root && root.name) || '') + '|' + Vod.tab.name;
  if (/电影|影片/.test(nm) && !/电视剧|连续剧/.test(nm)) return '电影';
  if (/电视剧|连续剧|剧集/.test(nm)) return '电视剧';
  if (/综艺|脱口秀|真人秀/.test(nm)) return '综艺';
  return null;
}
const isDescendantOfTab = t => {
  if (!Vod.tab || !t) return false;
  let c = t, hop = 0;
  while (c && hop++ <= 6) { if (c.id === Vod.tab.id) return true; c = Vod.types.find(x => x.id === c.pid); }
  return false;
};
// 分类树中属于当前 tab 子树且名字带地区信号的子分类；无命中返回 null → 走客户端过滤
const presetIds = labels => {
  const hit = Vod.types.filter(c => (!Vod.tab || c.id !== Vod.tab.id) && isDescendantOfTab(c) && PerfCore.regionLabels(c.name).some(l => labels.includes(l)));
  return hit.length ? hit : null;
};
const rgOf = it => it._rg || (it._rg = PerfCore.classifyVod(it));
/* 二级筛选数据源：①分类树子类（type_pid 指向当前分类，影视仓式）②type_extend 的地区/年份/语言 */
function buildFilters(t, raw) {
  const subs = Vod.types.filter(c => c.pid === t.id && c.id !== t.id);
  const meta = parseFilterMeta(raw) || {};
  const e = Object.assign({}, t.extend || {}, meta);
  const arr = v => (Array.isArray(v) ? v : String(v || '')).toString().split(',').filter(Boolean);
  return { subs, area: arr(e.area), year: arr(e.year), lang: arr(e.lang) };
}
const dedupById = arr => { const seen = new Set(); return arr.filter(it => !seen.has(it.id) && seen.add(it.id)); };
async function pMap(arr, fn, limit = 6) {
  const out = new Array(arr.length); let i = 0;
  await Promise.all(Array.from({ length: Math.min(limit, arr.length) }, async () => {
    while (i < arr.length) { const j = i++; out[j] = await fn(arr[j]).catch(() => []); }
  }));
  return out;
}
/* 父分类聚合：如非凡源「电影片」父 id 查询无数据，并行拉取其全部子类合并为真实分类页 */
async function loadMerged(pg, srvIds) { // srvIds=R8 预设命中子分类数组：服务端按这些 id 合并拉取（优先于 Vod.merged 全子类）
  const s = SRC.vod[Home.srcIdx]; if (!s || !(srvIds || Vod.merged)) return;
  const rid = Vod.reqId;
  const grid = $('#vodGrid'); if (!grid) return;
  const t = Vod.tab;
  const kids = srvIds || Vod.merged;
  Vod.loading = true;
  const ck = `mrg:${s.url}|${t ? t.id : ''}|${Vod.area}|${Vod.year}|${Vod.lang}|${(srvIds && Vod.preset) || ''}`;
  let cachedShown = false;
  if (pg === 1) {
    Vod.items = [];
    const cached = cacheGet(ck);
    if (cached && cached.length) { cachedShown = true; Vod.items = sortByYearDesc(cached); drawVodGrid(); }
    else grid.innerHTML = '<p class="muted">加载中…</p>';
  }
  let extra = '';
  if (Vod.area) extra += `&area=${encodeURIComponent(Vod.area)}`;
  if (Vod.year) extra += `&year=${encodeURIComponent(Vod.year)}`;
  if (Vod.lang) extra += `&lang=${encodeURIComponent(Vod.lang)}`;
  const pages = await pMap(kids, c =>
    httpGet(listUrl(s.url, `ac=videolist&t=${encodeURIComponent(c.id)}${extra}&pg=${pg}`))
      .then(raw => parseList(raw, s)));
  if (rid !== Vod.reqId || App.view !== 'vod' || !$('#vodGrid')) return;
  const fresh = dedupByName(dedupById(pages.flat())); // 页内先按 id+片名去重，防子类合并撞车
  if (pg === 1 && !fresh.length && cachedShown) { Vod.loading = false; return; } // 拉新为空保留缓存首屏
  Vod.loading = false; Vod.page = pg;
  if (pg === 1 && fresh.length) cacheSet(ck, fresh);
  if (pg === 1) { Vod.filters = buildFilters(t, null); if (Vod.showFilters) drawSubRow(); }
  addWords(fresh.map(it => it.name));
  const prevLen = pg === 1 ? 0 : Vod.items.length;
  Vod.items = pg === 1 ? sortByYearDesc(fresh) : dedupByName(dedupById(Vod.items.concat(fresh))); // 首屏排一次年降序；追加轮保留旧序、新条目进队尾
  Vod.more = pg === 1 ? pages.some(p => p.length > 0) : Vod.items.length > prevLen; // pg>1 有新增才能继续（防无视 pg 恒回首页的源空转）
  drawVodGrid();
}
async function loadVod(pg = 1) {
  if (pg > 1 && App.srcDirty) { App.srcDirty = false; Vod.items = []; Vod.page = 1; Vod.more = false; return loadVod(1); } // 翻页遇源变更：从第1页含新源重拉
  const s = SRC.vod[Home.srcIdx]; if (!s) return;
  const rid = Vod.reqId;
  const grid = $('#vodGrid'); if (!grid) return;
  const t = Vod.tab;
  if (Vod.preset && Vod.pSrv) return loadMerged(pg, Vod.pSrv); // R8：预设命中子分类→服务端合并拉取（三级数据解析之 (a)）
  if (t && !Vod.sub && Vod.merged) return loadMerged(pg);
  Vod.loading = true;
  const ck = `vod:${s.url}|${Vod.sub || (t ? t.id : 'all')}|${t ? Vod.area + '|' + Vod.year + '|' + Vod.lang : ''}`;
  let cachedShown = false;
  if (pg === 1) {
    Vod.items = [];
    const cached = cacheGet(ck);
    if (cached && cached.length) { cachedShown = true; Vod.items = sortByYearDesc(cached); drawVodGrid(); }
    else grid.innerHTML = '<p class="muted">加载中…</p>';
  }
  const effT = Vod.sub || (t ? t.id : '');
  let q = effT ? `ac=videolist&t=${encodeURIComponent(effT)}` : 'ac=videolist';
  if (t && Vod.area) q += `&area=${encodeURIComponent(Vod.area)}`;
  if (t && Vod.year) q += `&year=${encodeURIComponent(Vod.year)}`;
  if (t && Vod.lang) q += `&lang=${encodeURIComponent(Vod.lang)}`;
  q += `&pg=${pg}`;
  let items = [], err = null, raw = '';
  try { raw = await httpGet(listUrl(s.url, q)); items = parseList(raw, s); } catch (e) { err = e.message; }
  if (rid !== Vod.reqId || App.view !== 'vod' || !$('#vodGrid')) return;
  if (err) {
    Vod.loading = false;
    if (pg === 1 && cachedShown) return; // 拉新失败保留缓存首屏
    grid.innerHTML = `<p class="err">${esc(err)}</p>`; return;
  }
  Vod.loading = false; Vod.page = pg;
  if (pg === 1 && items.length) cacheSet(ck, dedupByName(items));
  if (pg === 1 && t) { Vod.filters = buildFilters(t, raw); if (Vod.showFilters) drawSubRow(); }
  addWords(items.map(it => it.name));
  const prevLen = pg === 1 ? 0 : Vod.items.length;
  Vod.items = pg === 1 ? sortByYearDesc(dedupByName(items)) : dedupByName(dedupById(Vod.items.concat(items))); // 首屏排一次年降序；追加轮保留旧序、新条目进队尾
  Vod.more = pg === 1 ? items.length > 0 : Vod.items.length > prevLen; // pg>1 以“是否有新增”判底，全重复=到底，防无限空转
  drawVodGrid();
}
/* 默认呈现最新：按 vod_year 倒序（同年保持源返回的更新时间序），无需再点筛选 */
const yearNum = it => parseInt(it.year, 10) || 0;
const sortByYearDesc = items =>
  items.map((it, i) => [it, i]).sort((a, b) => (yearNum(b[0]) - yearNum(a[0])) || (a[1] - b[1])).map(p => p[0]);
function drawVodGrid() {
  const grid = $('#vodGrid'); if (!grid) return;
  const sc = $('#scroller'); const st = sc ? sc.scrollTop : 0; // 增量重绘不重置滚动（配合队列化，屏上内容纹丝不动）
  let shown = Vod.items; // 队列化：排序已在取数时做一次，渲染不再全墙重排
  /* R8 三级 (b)：预设生效但分类树无地区分类→客户端 classifyVod 过滤；UNKNOWN 保守剔除并计数 */
  let unknown = 0;
  if (Vod.preset && !Vod.pSrv) {
    const entry = (PerfCore.VOD_FILTERS[presetKeyForTab()] || []).find(p => p[0] === Vod.preset);
    const labs = entry && entry[1].length ? entry[1] : null;
    if (labs) shown = shown.filter(it => { const r = rgOf(it); if (r === 'UNKNOWN') { unknown++; return false; } return labs.includes(r); });
  }
  if (Vod.preset) {
    Vod.presetInfo = Vod.pSrv
      ? `地区「${Vod.preset}」：服务端 ${Vod.pSrv.length} 个分类合并`
      : `地区「${Vod.preset}」：${Vod.agg ? '聚合' : `已拉 ${Vod.page} 页`} ${Vod.items.length} 部，筛出 ${shown.length} 部${unknown ? ` · ${unknown} 部无地区信息未显示` : ''}${!Vod.agg && Vod.page >= 10 ? ' · 已拉满 10 页上限' : ''}`;
  } else Vod.presetInfo = '';
  const wasMore = Focus.el && Focus.el.id === 'moreBtn'; // S4 哨兵重绘后焦点归位，遥控可连续 OK 下拉
  /* S4 窗口渲染：聚合/专区墙每次只挂 240 部进 DOM，哨兵先本地展开（零请求），展完再联网续轮——
     连按到底也不卡 TV 浏览器；单源模式本就逐页追加不设窗 */
  const total = shown.length;
  const win = Vod.agg ? Math.min(Vod.win || 240, total) : total;
  const view = win < total ? shown.slice(0, win) : shown;
  const atCap = !Vod.agg && Vod.preset && !Vod.pSrv && Vod.page >= 10; // R8 客户端地区过滤 10 页封顶（性能护栏，非内容上限）
  const canMore = !!Vod.more && !atCap;
  grid.innerHTML = (total
    ? view.map((it, i) => cardHtml(it, i, { showSite: Vod.agg })).join('')
    : `<p class="muted">${Vod.preset ? esc(Vod.presetInfo) + '（仍无数据可继续下滑拉取或选「全部」）' : (Vod.agg ? '聚合无数据：请检查点播源' : '该分类暂无数据')}</p>`)
    + (total ? `<button class="btn morebtn" data-focus id="moreBtn">${Vod.loadingMore ? '加载中…' : (win < total ? `⌄ 展开更多（本墙还有 ${total - win} 部）` : (canMore ? '⌄ 加载更多（OK 继续无限下刷）' : '已到底（各源无新内容）'))}</button>` : '');
  if (Zone.active) reapplyZoneTags(view); // 资源口墙增量重绘后回贴测速标签
  $('#presetInfo') && ($('#presetInfo').textContent = Vod.presetInfo || '');
  const mb = $('#moreBtn');
  if (mb) {
    mb.onclick = () => {
      if (Vod.agg && win < total) { Vod.win = win + 240; drawVodGrid(); return; } // 本地展开，不发请求
      if (Vod.agg) aggLoadMore();
      else if (!Vod.loading && canMore) loadVod(Vod.page + 1);
    };
    if (wasMore) Focus.set(mb);
  }
  bindGrid(grid, view, it => {
    if (!Vod.agg) { openDetail(it); return; }
    // 聚合墙：点击片名进入跨源搜索（影视仓豆瓣式：先统一展示，选中资源再全源查找）
    Collect.kw = it.name; Collect.done = 0; Collect.stop = false; Collect.bySite = {};
    Collect.back = Zone.active ? 'zone' : 'vod'; Collect.zone = Zone.active;
    switchView('collect'); renderCollect();
  });
  if (sc) sc.scrollTop = st;
}
function drawSubRow() {
  const el = $('#subRow'); if (!el) return;
  const pf = Focus.el && el.contains(Focus.el) && Focus.el.dataset ? [Focus.el.dataset.f ?? '', Focus.el.dataset.v ?? ''] : null; // 重绘后焦点归位原 chip
  const f = Vod.filters;
  if (!Vod.tab || !Vod.showFilters) { el.innerHTML = ''; return; }
  const key = presetKeyForTab();
  const presets = key ? PerfCore.VOD_FILTERS[key] : null;
  const hasGrps = !!(f && (f.subs.length || f.area.length || f.year.length || f.lang.length));
  if ((!presets || !presets.length) && !hasGrps) {
    el.innerHTML = '<span class="muted" style="font-size:12px;padding:6px 28px">该分类不支持二级筛选（源未提供子分类或扩展字段）</span>'; return;
  }
  const yr = String(new Date().getFullYear());
  const grp = (k, label, opts) =>
    `<span class="muted" style="font-size:12px;white-space:nowrap">${label}</span>` +
    `<button class="chip${!Vod[k] ? ' on' : ''}" data-focus data-f="${k}" data-v="">全部</button>` +
    opts.slice(0, 30).map(([v, name, off]) =>
      `<button class="chip${String(Vod[k]) === String(v) ? ' on' : ''}${off ? ' off' : ''}" data-focus data-f="${k}" data-v="${esc(v)}">${esc(name === yr ? `本年(${yr})` : name)}</button>`
    ).join('');
  /* R8：预设行置于最左；置灰条件=分类树无命中且当前已加载条目既无匹配也无 UNKNOWN（数据不足时不误判为不支持） */
  const presetRow = presets && presets.length ? '<span class="muted" style="font-size:12px;white-space:nowrap">地区</span>' +
    presets.map(([label, labs]) => {
      let off = false;
      // 聚合墙不置灰：默认墙只拉每源前 3 子类，浅采样必然误判欧美/日/韩「未收录」反而挡死点击入口（聚合模式点谁都能触发定向抓取，真无数据由空态提示兜底）
      if (!Vod.agg && labs.length && !presetIds(labs)) off = Vod.items.length > 0 && !Vod.items.some(it => labs.includes(rgOf(it)) || rgOf(it) === 'UNKNOWN');
      const on = !Vod.preset ? label === '全部' : Vod.preset === label;
      return `<button class="chip${on ? ' on' : ''}${off ? ' off' : ''}" data-focus data-f="preset" data-v="${esc(label)}">${esc(label)}</button>`;
    }).join('') : '';
  el.innerHTML = [
    presetRow,
    hasGrps && grp('sub', '子类', f.subs.map(s => [s.id, s.name])),
    hasGrps && f.area.length && grp('area', '地区(源扩展)', f.area.map(v => [v, v])),
    hasGrps && f.year.length && grp('year', '年份', [...new Set([yr, ...f.year])].map(v => [v, v])),
    hasGrps && f.lang.length && grp('lang', '语言', f.lang.map(v => [v, v])),
    '<span class="muted" id="presetInfo" style="font-size:12px;white-space:nowrap;margin-left:6px"></span>',
  ].filter(Boolean).join('<span style="width:12px"></span>');
  $('#presetInfo').textContent = Vod.presetInfo || '';
  el.onclick = e => {
    const b = e.target.closest('[data-f]'); if (!b) return;
    if (b.classList.contains('off')) { toast('该源分类未收录此地区内容'); return; }
    const k = b.dataset.f, v = b.dataset.v;
    if (k === 'preset') {
      const entry = (presets || []).find(p => p[0] === v);
      if (!v || !entry || !entry[1].length || Vod.preset === v) { Vod.preset = ''; Vod.pSrv = null; } // 全部/再点同项=取消
      else { Vod.preset = v; Vod.pSrv = presetIds(entry[1]) || null; } // 三级 (a)：树里有地区分类走服务端合并
    } else if (k === 'sub') { Vod.sub = v; }
    else { if (Vod[k] === v) Vod[k] = ''; else Vod[k] = v; } // 同项再按=取消（TVBox 交互）
    drawSubRow(); // R8 改版：不再收起，焦点归位可连续调整
    if (Vod.agg) { // R8(agg)：地区预设按各源区域子类定向抓取（默认墙只拉前3子类，日剧/欧美等排在后面拉不到）
      const base = AGG_CATS.find(c => c.key === (Vod.tab && Vod.tab.name));
      if (!base) drawVodGrid();
      else {
        const e2 = Vod.preset ? (presets || []).find(p => p[0] === Vod.preset) : null;
        loadAggCat(e2 && e2[1].length ? { ...base, rlabs: e2[1] } : base);
      }
    } else loadVod(1);
  };
  if (pf) { const target = [...el.querySelectorAll('[data-f]')].find(x => x.dataset.f === pf[0] && x.dataset.v === pf[1]); if (target) Focus.set(target); }
}

/* ================= 资源口（二级专区，仅经 设置→影视仓实验室「进入」可达） =================
   界面与点播聚合墙一致：分类 chips + 海报墙（复用 runAggWall，池自动取专区源 top6）+ 搜索 + 测速。
   点卡片→跨源结果页（Collect.back='zone'）→详情播放；测速为线路同款 Range 采样打标签，不移除条目 */
function renderZone() {
  syncSrc();
  seedZoneSources(); // 没种过专区源则首次进入自动补预置站，保证资源口开箱有数据（P10）
  Vod.agg = true; Vod.types = []; Vod.filters = null; Vod.merged = null; Vod.tab = null;
  Zone.tags = {}; // 每次进入重建标签（localStorage 7 天缓存仍命中，不重复采样）
  resetFilterVals();
  body.innerHTML = `<div class="hrow" style="padding:6px 28px 0;justify-content:flex-end"><button class="btn" data-focus id="zoneBack">← 返回</button><button class="btn" data-focus id="zoneRefresh" title="清除资源口聚合分片并重新拉取当前墙">🔄 刷新</button><button class="btn" data-focus id="zoneFind" title="在当前聚合墙内按片名定位">🔎 找片</button><button class="btn" data-focus id="zoneSearch" title="资源口聚合搜索（与点播搜索页同构，历史/词库/热门榜数据互相隔离）">🔍 搜索</button><button class="btn" data-focus id="zoneSpeed" title="重新测当前墙条目的起播速度">⚡ 测速</button><button class="btn" data-focus id="zoneHist" title="仅显示资源口观看历史">🕛 历史</button><button class="btn" data-focus id="zoneKeep" title="仅显示资源口收藏">⭐ 收藏</button></div>
    <div class="hrow" id="typeRow" style="padding:6px 28px"></div>
    <main id="scroller"><div class="grid" id="vodGrid"></div></main>`;
  const tabs = [{ id: '', name: '🔞 最新' }, ...AGG_CATS.map(c => ({ id: 'cat:' + c.key, name: c.key }))];
  $('#typeRow').innerHTML = tabs.map(t => `<button class="chip${t.id === '' ? ' on' : ''}" data-focus data-tid="${esc(t.id)}">${esc(t.name)}</button>`).join('');
  $('#typeRow').onclick = e => {
    const b = e.target.closest('[data-tid]'); if (!b) return;
    document.querySelectorAll('#typeRow .chip').forEach(c => c.classList.toggle('on', c === b));
    const id = b.dataset.tid;
    const p = id === '' ? (Vod.tab = null, runAggWall(null))
      : (() => { const cat = AGG_CATS.find(c => 'cat:' + c.key === id); Vod.tab = { id: cat.key, name: cat.key }; return runAggWall(cat); })();
    zoneSpeedWall(); // 热缓存时首屏 merge 同步填充，立即可测
    p.then(() => { if (App.view === 'zone') zoneSpeedWall(); }); // 冷启动墙逐源增量后补起测（已测条目走缓存秒回）
  };
  bindBack('zoneBack', () => switchView('settings'));
  $('#zoneRefresh').onclick = () => { // 与点播聚合刷新对称：只清本池源的分片（详情/测速/搜索缓存不动），当前分类立即重拉
    aggPool().forEach(s => purgeCache('aggs:' + s.url));
    const cat = (Vod.tab && AGG_CATS.find(c => 'cat:' + c.key === Vod.tab.id)) || null;
    const p = runAggWall(cat);
    zoneSpeedWall();
    p.then(() => { if (App.view === 'zone') zoneSpeedWall(); });
    toast('已清资源口缓存，重新拉取中…');
  };
  $('#zoneFind').onclick = () => openFindBox('', () => ({ line: 0, ep: 0, sec: 0 }), true);
  $('#zoneSearch').onclick = () => openSearch(true); // 资源口池搜索页（与点播同构三栏，数据隔离）
  $('#scroller').addEventListener('scroll', onScroll); // S4：资源口墙滚到底自动续轮（与点播墙同款无限下拉）
  $('#zoneSpeed').onclick = () => zoneSpeedWall(true);
  $('#zoneHist').onclick = () => switchView('zonehist');
  $('#zoneKeep').onclick = () => switchView('zonekeep');
  Focus.set($('#zoneBack'));
  if (!SRC.vod.some(s => s.isAdult)) {
    $('#vodGrid').innerHTML = '<p class="muted" style="padding:20px 28px">资源口暂无预置源<br><span class="muted" style="font-size:12px"><button class="btn" data-focus id="zoneReseed">↻ 恢复预置源</button>　或导入订阅后自动聚合海报墙</span></p>';
    $('#zoneReseed').onclick = () => { localStorage.removeItem('fongmi_zone_seed'); seedZoneSources(); renderZone(); }; // 预置按版本号只种一次，删除后经此恢复
    return;
  }
  const p0 = runAggWall(null);
  zoneSpeedWall();
  p0.then(() => { if (App.view === 'zone') zoneSpeedWall(); }); // 冷启动墙逐源增量完成后补起测（已测条目走缓存秒回）
}
/* 墙内条目测速：取首线路首集 /proxy Range 采样（与线路测速同一 lineSpeed，2.5s 预算），
   海报角标绿/黄/红速度标签；-1=采样失败不判死；失败条目同样保留（用户要求不移除）。
   并发 3、每次最多测前 60 条（按年份新的优先），结果按 源+id 缓存 7 天 */
async function zoneSpeedWall(force) {
  if (App.view !== 'zone' || LS('fongmi_linespeed', 1) === 0) return;
  const seq = ++Zone.seq;
  const shown = sortByYearDesc(Vod.items).slice(0, 60);
  await pMap(shown, async (it, i) => {
    if (seq !== Zone.seq || App.view !== 'zone') return;
    const ck = 'adsp:' + normUrl(it.source?.url || '') + '|' + it.id;
    let kb = null, hit = false;
    if (!force && Zone.tags[ck] != null) { kb = Zone.tags[ck]; hit = true; } // 本轮已测：直接复用
    if (!hit && !force) { const c = cacheRaw(ck); if (c && typeof c.d === 'number' && Date.now() - c.t < 7 * 864e5) { kb = c.d; hit = true; } }
    if (kb != null) Zone.tags[ck] = kb;
    if (!hit) {
      try {
        const dk = `detail:${normUrl(it.source.url)}|${it.id}`;
        let d = cacheGet(dk);
        if (!d) { d = parseDetail(await httpGet(listUrl(it.source.url, `ac=videolist&ids=${encodeURIComponent(it.id)}`), 8000), it.source); if (d && d.episodes) cacheSet(dk, d); }
        const link = d?.episodes?.[0]?.list?.[0]?.link;
        kb = link ? Math.round(await lineSpeed(link)) : 0;
      } catch { kb = -1; }
      cacheSet(ck, kb);
      Zone.tags[ck] = kb;
    }
    if (seq !== Zone.seq || App.view !== 'zone') return;
    const t = PerfCore.lineSpeedTag(kb);
    // 按条目 id 定位卡片（聚合墙逐源增量重绘会改变索引顺序，不能用下标）
    const pic = document.querySelector(`#vodGrid .card[data-aid="${CSS.escape(String(it.id))}"]`)?.querySelector('.pic');
    if (pic && t) { pic.querySelector('.spTag')?.remove(); pic.insertAdjacentHTML('beforeend', `<span class="spTag ${t.cls}">${esc(t.label)}</span>`); }
  }, 3);
}
/* 聚合墙逐源增量重绘会重建 innerHTML 抹掉角标：按 源|id 从 Zone.tags 回贴，不重测 */
function reapplyZoneTags(shown) {
  for (const it of shown) {
    const kb = Zone.tags['adsp:' + normUrl(it.source?.url || '') + '|' + it.id];
    if (kb == null) continue;
    const t = PerfCore.lineSpeedTag(kb);
    const pic = document.querySelector(`#vodGrid .card[data-aid="${CSS.escape(String(it.id))}"]`)?.querySelector('.pic');
    if (pic && t && !pic.querySelector('.spTag')) pic.insertAdjacentHTML('beforeend', `<span class="spTag ${t.cls}">${esc(t.label)}</span>`);
  }
}

/* 词库自举（方案：现有资源剧名全量做底 + 新数据增量 + 热门搜索加权，一词库三用途）：
   从至多 10 个健康源各拉至多 8 页目录片名入库；拉满 min 即停；不足 min 也记录本轮完成时间，
   6h 内不再重复整轮爬（源库本就那么多，防每次进搜索页空耗带宽）；按池隔离（专区词只进专区池） */
const vocabSeeding = { vod: false, zone: false };
const vocabSeedAt = { vod: 0, zone: 0 };
async function seedVocabPool(zone, min = 3000) {
  const pk = zone ? 'zone' : 'vod';
  if (vocabSeeding[pk] || vocabSize(zone) >= min) return;
  if (vocabSeedAt[pk] && Date.now() - vocabSeedAt[pk] < 6 * 3600e3) return;
  vocabSeeding[pk] = true;
  try {
    const pool = SRC.vod.filter(s => !isDead(s.url) && !!s.isAdult === !!zone);
    const cur = SRC.vod[Home.srcIdx];
    const order = (zone ? pool : [cur && !cur.isAdult ? cur : null, ...pool]).filter(Boolean);
    const seen = new Set();
    const uniq = [];
    for (const s of order) {
      if (uniq.length >= 10) break;
      if (seen.has(norm(s.url))) continue;
      seen.add(norm(s.url)); uniq.push(s);
    }
    await pMap(uniq, async s => { // 并发 3：词库尽早可用；每源至多 8 页目录
      for (let pg = 1; pg <= 8; pg++) {
        if (vocabSize(zone) >= min) return;
        try { addWords(parseList(await httpGet(listUrl(s.url, `ac=videolist&pg=${pg}`), s), 12000).map(it => it.name).filter(Boolean), zone); }
        catch { break; /* 单源失败/翻页到头跳过 */ }
      }
    }, 3);
    vocabSeedAt[pk] = Date.now();
  } finally { vocabSeeding[pk] = false; }
}
const seedVocab = (min = 3000) => seedVocabPool(false, min);
const seedZoneVocab = (min = 1500) => seedVocabPool(true, min);
/* ================= 搜索输入页（SearchActivity：三栏，对标影视仓搜索页） =================
   左列搜索历史（竖排+清空）｜中列指定源+按钮组+六列矩阵键盘（A-Z0-9 共36键）+拼音联想｜右列热门搜索榜
   点播/资源口两池共用本页同构布局，数据全隔离：历史 fongmi_kw(_zone)、词库/词频 fongmi_words(_zone)/fongmi_wfreq(_zone)、源池与结果均由 Search.mode 决定 */
const Search = { mode: 'vod', srcSel: null }; // srcSel={si,name}|null（null=全部源聚合）
function openSearch(zone) {
  const m = zone ? 'zone' : 'vod';
  if (Search.mode !== m) { Search.mode = m; Search.srcSel = null; } // 跨池切换清空指定源
  switchView('search');
}
function renderSearch() {
  const zone = Search.mode === 'zone';
  const pool = vocab(zone);
  const pi = pool.index;
  const kwKey = zone ? 'fongmi_kw_zone' : 'fongmi_kw';
  const KEYS = [...'ABCDEFGHIJKLMNOPQRSTUVWXYZ', ...'0123456789'];
  const krows = [];
  for (let i = 0; i < 36; i += 6) krows.push(KEYS.slice(i, i + 6));
  body.innerHTML = `<div class="sbox"><input id="kw" placeholder="输入/点选键盘/手机扫码输入，回车搜索（${zone ? '资源口' : '点播'}池）" data-focus><button class="btn" data-focus id="searchBack">← ${zone ? '资源口' : '点播'}</button></div>
    <main id="scroller"><div class="search3">
      <section class="scol"><div class="rowhead">搜索历史 <button class="chip" data-focus id="histClear">清空</button></div><div class="wcol" id="histList"></div></section>
      <section class="scol">
        <div class="hrow"><button class="btn srcpick" data-focus id="srcName">全部源（聚合）</button><button class="btn" data-focus id="srcPick">指定源</button></div>
        <div class="hrow" style="padding-top:8px"><button class="btn" data-focus id="goBtn">🔍 搜索</button><button class="btn" data-focus data-kb="⌫">⌫ 退格</button><button class="btn" data-focus data-kb="CLR">清空</button><button class="btn" data-focus id="qrBtn">📱 远程输入</button></div>
        <div class="kbg">${krows.map(r => r.map(c => `<button class="btn kb" data-focus data-kb="${c}">${c}</button>`).join('')).join('')}</div>
        <p class="muted" style="font-size:12px;padding-top:6px">依次点选片名拼音首字母（如「庆余年」按 QYN）或用数字键，右侧实时联想本池片名（空输入时为热门词）；手机扫码可与电视同步输入。</p>
      </section>
      <section class="scol"><div class="rowhead"><span id="hotHead">热门搜索</span> <button class="chip" data-focus id="delHist" title="清空本池搜索历史">删除历史</button></div><div class="wcol" id="hotList"></div></section>
    </div></main>`;
  bindBack('searchBack', () => switchView(zone ? 'zone' : 'vod'));
  const run = () => {
    const kw = $('#kw').value.trim(); if (!kw) return;
    pushKw(kw, zone); drawHist();
    Collect.kw = kw; Collect.done = 0; Collect.stop = false; Collect.bySite = {};
    Collect.back = 'search'; Collect.zone = zone; Collect.srcSel = Search.srcSel;
    switchView('collect'); renderCollect();
  };
  const drawHist = () => {
    const el = $('#histList'); if (!el) return;
    const h = LS(kwKey, []);
    el.innerHTML = h.length
      ? h.map(k => `<button class="btn" data-focus data-k="${esc(k)}">${esc(k)}</button>`).join('')
      : '<span class="muted" style="font-size:12px">暂无本池搜索历史</span>';
  };
  const drawHot = () => {
    const el = $('#hotList'); if (!el) return;
    const words = pi ? pi.top(40) : pool.list().slice(-40).reverse();
    el.innerHTML = words.length
      ? words.map((w, i) => `<button class="btn" data-focus data-k="${esc(w)}">${i < 3 ? '🔥 ' : ''}${esc(w)}</button>`).join('')
      : '<span class="muted" style="font-size:12px">词库建设中…（搜索/进墙自动积累本池片名）</span>';
  };
  /* 右列复用（对标真机搜索页）：输入框为空显示热门词榜；有输入即切换为关联词，样式同款词流 */
  const drawSug = () => {
    const inp = $('#kw'); if (!inp) return;
    const el = $('#hotList'); if (!el) return;
    const head = $('#hotHead');
    const q = inp.value.trim().toLowerCase();
    if (!q) { if (head) head.textContent = '热门搜索'; drawHot(); return; }
    if (head) head.textContent = '关联词';
    let hits = /^[a-z0-9]+$/.test(q) && pi ? pi.suggest(q, 40) : [];
    if (!hits.length) hits = pool.list().filter(w => w.toLowerCase().includes(q));
    else { // ②模糊补足：首字母串包含查询的相关词（fcz→非凡创作者类），排精确命中后
      const seen = new Set(hits);
      for (const w of pi.fuzzy(q, 40 - hits.length)) if (!seen.has(w)) { hits.push(w); seen.add(w); }
    }
    hits = [...new Set(hits)];
    // ②截断核心词：查询在首字母串的匹配位置对齐截断（fcz→复仇者、lm→联盟），置顶带标，点选即搜
    const truncs = /^[a-z0-9]+$/.test(q) && typeof PerfCore !== 'undefined' ? PerfCore.truncateByQuery(q, hits, 3, 2, pi && (w => pi.variants(w))) : [];
    const marked = new Set(truncs);
    hits = [...truncs, ...hits.filter(w => !marked.has(w))].slice(0, 40);
    el.innerHTML = hits.length
      ? hits.map(w => `<button class="btn" data-focus data-w="${esc(w)}">${esc(w)}${marked.has(w) ? '<span style="font-size:10px;color:var(--accent)">·截断</span>' : ''}</button>`).join('')
      : '<span class="muted" style="font-size:12px">没有关联词，清空输入可看热榜，或换字母试试</span>';
  };
  const drawSrc = () => { const b = $('#srcName'); if (b) b.textContent = Search.srcSel ? `📌 ${Search.srcSel.name}` : '全部源（聚合）'; };
  const clearHist = () => { localStorage.removeItem(kwKey); drawHist(); toast(`已清空${zone ? '资源口' : '点播'}搜索历史`); };
  $('#histClear').onclick = $('#delHist').onclick = clearHist;
  $('#goBtn').onclick = run;
  const openSrcPick = () => {
    const items = SRC.vod.map((s, si) => ({ s, si })).filter(x => zone ? x.s.isAdult : !x.s.isAdult);
    openCfg(`指定搜索源（${zone ? '资源口' : '点播'}）`,
      `<div class="tabrow"><span>全部源（聚合）</span><button class="chip${Search.srcSel ? '' : ' on'}" data-focus data-v="-1">${Search.srcSel ? '改回' : '当前'}</button></div>`
      + items.map(({ s, si }) => `<div class="tabrow"><span>${esc(s.name)}</span><button class="chip${Search.srcSel && Search.srcSel.si === si ? ' on' : ''}" data-focus data-v="${si}">${Search.srcSel && Search.srcSel.si === si ? '当前' : '选择'}</button></div>`).join('')
      + '<p class="muted" style="font-size:12px">选定后本次仅搜该源；结果页仍按源分 Tab（只有一页）</p>',
      v => {
        const si = +v;
        Search.srcSel = si < 0 ? null : { si, name: (SRC.vod[si] && SRC.vod[si].name) || '' };
        drawSrc(); closeCfg(); Focus.set($('#srcPick'));
      });
  };
  $('#srcPick').onclick = $('#srcName').onclick = openSrcPick;
  let sugTimer = null;
  const onKwInput = () => { clearTimeout(sugTimer); sugTimer = setTimeout(drawSug, 80); }; // 80ms 防抖：连打不卡
  RemoteIn.onSearch = t => { const inp = $('#kw'); if (!inp) return; inp.value = t; drawSug(); run(); };
  $('#kw').onkeydown = e => { if (e.key === 'Enter') return run(); }; // 两段式：编辑态方向键归光标，退编辑靠 Esc，无需再特例跳按钮
  $('#kw').oninput = onKwInput;
  body.onclick = e => {
    const kb = e.target.closest('[data-kb]');
    if (kb) {
      const inp = $('#kw'); const v = inp.value;
      if (kb.dataset.kb === '⌫') inp.value = v.slice(0, -1);
      else if (kb.dataset.kb === 'CLR') inp.value = '';
      else inp.value = v + kb.dataset.kb.toLowerCase();
      drawSug(); return;
    }
    const w = e.target.closest('[data-w]');
    if (w) { $('#kw').value = w.dataset.w; run(); return; } // 点联想片名直接以其搜索
    const b = e.target.closest('[data-k]'); if (b) { $('#kw').value = b.dataset.k; run(); }
  };
  $('#qrBtn').onclick = () => openQrModal('search');
  drawHist(); drawHot(); drawSrc();
  (zone ? seedZoneVocab() : seedVocab()).then(() => { if (App.view === 'search') drawSug(); }); // 空输入=热榜、有输入=关联词，单入口防热榜覆盖联想
  Focus.set($('#kw')); // 仅选中不弹键盘：OK 才进编辑（两段式 R1）
}

/* ================= 搜索结果页（CollectActivity：一源一 Tab 渐进追加） =================
   提速策略（docs/2026-10-30-搜索与加载提速方案.md ①）：
   源序=当前源→测速 ms 升序；首批 top8 立即发、其余 1.2s 次批；超时 8s；
   SWR 分片缓存 search:<词> 先上屏后台替换；epoch 丢弃换词后的旧回调（对齐 FongMi ViewModelSearchRunner） */
const Collect = { kw: '', stop: false, done: 0, total: 0, bySite: {}, seq: [], tabIdx: '', epoch: 0, back: 'search', zone: false, srcSel: null, fails: 0 };
function collectSources(zone) { // zone=true 只搜专区源（资源口/专区作品）；常规搜索排除专区源——专区内容仅在设置·实验室「资源口」入口可见
  const items = SRC.vod.map((s, si) => ({ s, si }))
    .filter(x => zone ? x.s.isAdult : !x.s.isAdult)
    .filter(x => autoOn() ? !isDead(x.s.url) : true);
  return PerfCore.orderSources(items, x => healthOf(x.s.url)?.ms ?? null, Home.srcIdx);
}
function searchShardSet(ck, u, items) { const v = cacheRaw(ck) || {}; v[u] = { t: Date.now(), d: items }; cacheSet(ck, v); }
function addSiteTab(s, si, list) {
  if (!list.length) return;
  Collect.bySite[si] = list;
  if (!Collect.seq.includes(si)) Collect.seq.push(si); // 队列化：全部 Tab 按源「到达序」拼接，慢源后到只进尾部不插前
  addWords(list.map(v => v.name), !!Collect.zone);
  const tabs = $('#cTabs'); if (!tabs) return;
  let tab = tabs.querySelector(`[data-t="${si}"]`);
  if (!tab) { tab = document.createElement('button'); tab.className = 'chip'; tab.dataset.focus = ''; tab.dataset.t = si; tabs.appendChild(tab); }
  tab.textContent = `${s.name}(${list.length})`;
}
function renderCollect() {
  body.innerHTML = `<div class="colhead" style="display:flex;align-items:center;gap:10px">“${esc(Collect.kw)}”的搜索结果 <span class="muted" style="font-size:13px;font-weight:400">Esc 停止并返回</span><button class="btn" data-focus id="collectBack" style="margin-left:auto">← 返回</button></div>
    <div class="hrow" id="cTabs"><button class="chip on" data-focus data-t="">全部</button></div>
    <main id="scroller"><div class="grid" id="cGrid"><p class="muted" style="padding:20px 0">等待各源返回…</p></div></main>`;
  bindBack('collectBack', () => { Collect.stop = true; switchView(Collect.back || 'search'); });
  $('#cTabs').onclick = e => {
    const b = e.target.closest('[data-t]'); if (!b) return;
    Collect.tabIdx = b.dataset.t;
    document.querySelectorAll('#cTabs .chip').forEach(c => c.classList.toggle('on', c === b));
    drawCollectTab();
  };
  const ep = ++Collect.epoch;
  Collect.bySite = {}; Collect.seq = []; Collect.tabIdx = ''; Collect.done = 0; Collect.fails = 0;
  const failed = []; let retried = false; // 超时假空修复：首轮全源返回仍空且有失败源时自动补试一轮，不再把「慢」当「无」
  let sources = collectSources(!!Collect.zone);
  if (Collect.srcSel && sources.some(x => x.si === Collect.srcSel.si)) sources = sources.filter(x => x.si === Collect.srcSel.si); // 指定源生效：仅搜该源；源已删除/失效则回退聚合
  Collect.total = sources.length; // O3c 判「各源已返回完」做无结果推荐
  if (!sources.length) { $('#cGrid').innerHTML = '<p class="err">无点播源</p>'; return; }
  const ck = PerfCore.searchKey(Collect.kw);
  const { fresh, stale } = PerfCore.splitTtl(cacheRaw(ck), searchTtl());
  const siOf = new Map(sources.map(({ s, si }) => [normUrl(s.url), { s, si }]));
  for (const [u, d] of [...Object.entries(fresh), ...Object.entries(stale)]) {
    const x = siOf.get(u); if (x) addSiteTab(x.s, x.si, d);
  }
  if (Object.keys(Collect.bySite).length) {
    $('#cGrid').innerHTML = '';
    drawCollectTab();
  }
  const fire = ({ s, si }) => (async () => {
    try {
      const t = await httpGet(listUrl(s.url, `ac=videolist&wd=${encodeURIComponent(Collect.kw)}&pg=1`), 8000);
      const list = parseList(t, s).filter(v => isRelevant(v.name, Collect.kw));
      searchShardSet(ck, normUrl(s.url), list);
      if (ep !== Collect.epoch || Collect.stop || App.view !== 'collect') return;
      addSiteTab(s, si, list);
      if (Collect.tabIdx === '') drawCollectTab();
    } catch { Collect.fails++; if (ep === Collect.epoch) failed.push({ s, si }); }
    finally {
      if (ep !== Collect.epoch) return;
      Collect.done++;
      // 全部返回且无任何命中：有失败源先自动补试一轮（8s 超时会把「慢」误判成「无」，二次搜索靠迟到缓存才出数据）；仍空才绘空态（含 O3c 拼音推荐）
      if (!Collect.stop && Collect.tabIdx === '' && Collect.done >= Collect.total && !Object.keys(Collect.bySite).length && App.view === 'collect') {
        if (!retried && failed.length) {
          retried = true;
          Collect.done = Collect.total - failed.length; Collect.fails = 0;
          const again = failed.splice(0);
          const g = $('#cGrid'); if (g) g.innerHTML = `<p class="muted" style="padding:20px 0">${again.length} 个源超时/失败，正在重试…</p>`;
          again.forEach(fire);
        } else drawCollectTab();
      }
    }
  })();
  const [first, rest] = PerfCore.splitBatches(sources, LS('fongmi_threads', 8));
  first.forEach(fire);
  if (rest.length) setTimeout(() => { if (ep === Collect.epoch) rest.forEach(fire); }, 1200);
  Focus.set($('#cTabs').querySelector('[data-focus]')); // R7：结果页初始焦点=Tab「全部」
}
function drawCollectTab() {
  const grid = $('#cGrid'); if (!grid) return;
  const sc = $('#scroller'); const st = sc ? sc.scrollTop : 0; // 各源陆续返回重绘时保持滚动位置，画面不跳
  let items;
  if (Collect.tabIdx === '') items = Collect.seq.flatMap(si => Collect.bySite[si] || []); // 到达序拼接：新源结果进队尾，不插到前面
  else items = Collect.bySite[+Collect.tabIdx] || [];
  if (!items.length) {
    /* O3c 无结果推荐（docs/2026-10-30-O1-O3播放与直播体验方案.md）：全部 Tab+各源返回完+纯字母简拼 →
       截断核心词（fcz→复仇者）或词库命中词做成「试试搜『XX』?」chip，点击以该词重跑聚合（衔接拼音链） */
    const allDone = Collect.done >= (Collect.total || 0);
    const kw = String(Collect.kw || '').trim();
    const q = kw.toLowerCase();
    let sug = [];
    const pi = vocab(!!Collect.zone).index; // 无结果推荐按本池词库出词
    if (Collect.tabIdx === '' && allDone && /^[a-z0-9]{2,}$/.test(q) && pi && typeof PerfCore !== 'undefined') {
      const hits = pi.suggest(q, 30);
      const cand = [...new Set([...hits, ...pi.fuzzy(q, 30)])];
      sug = PerfCore.truncateByQuery(q, cand, 2, 2, w => pi.variants(w));
      if (!sug.length) sug = hits.slice(0, 2);
    }
    grid.innerHTML = `<p class="muted" style="padding:20px 0">${allDone ? (Collect.fails ? `各源无命中（${Collect.fails} 个源失败/超时，可点「搜索」再试）` : '各源均无结果') : '等待各源返回…'}${Collect.tabIdx !== '' ? ' · 该 Tab 暂无数据' : ''}<br>`
      + (sug.length ? sug.map(w => `<button class="chip" data-focus data-kw="${esc(w)}">试试搜「${esc(w)}」?</button>`).join(' ') : (allDone ? '可换首字母简拼、直接输入中文名或换源重试' : '')) + '</p>';
    grid.onclick = e => {
      const b = e.target.closest('[data-kw]'); if (!b) return;
      const w = b.dataset.kw;
      pushKw(w, !!Collect.zone);
      Collect.kw = w; Collect.done = 0; Collect.stop = false; Collect.bySite = {};
      renderCollect();
    };
    return;
  }
  grid.innerHTML = items.map((it, i) => cardHtml(it, i, { showSite: Collect.tabIdx === '' })).join('');
  bindGrid(grid, items, it => { App.videoBack = 'collect'; openDetail(it); });
  if (sc) sc.scrollTop = st;
}

/* ================= 详情/播放一体页（VideoActivity） ================= */
async function openDetail(item) {
  vocab(Zone.active || !!(item.source && item.source.isAdult)).bump(item.name, 2); // 进详情 +2 权重（方案③.5，按池路由）
  const s = item.source || SRC.vod[Home.srcIdx];
  // 播放页内「搜关联/换源/自动换源」再进详情：App.view 已是 video，保持原返回目标不丢
  if (App.view !== 'video') App.videoBack = ({ collect: 'collect', keep: 'keep', hist: 'hist', search: 'search', zone: 'zone', zonehist: 'zonehist', zonekeep: 'zonekeep' })[App.view] || 'vod';
  App.curZone = Zone.active || !!(s && s.isAdult); // 专区作品的「搜关联/换源」池限定专区源；常规作品排除专区源
  App.view = 'video';
  stopPlayer();
  // 实验室「历史合并」：开时按片名去重（同名跨源只留最新一条）；条数上限走设置页 fongmi_histmax
  const mergeH = LS('fongmi_histmerge', 1) !== 0;
  const hist = LS('fongmi_hist', []).filter(h => !(h.id === String(item.id) && h.si === SRC.vod.indexOf(s)) && !(mergeH && h.name === item.name));
  hist.unshift({ id: String(item.id), name: item.name, pic: item.pic, si: SRC.vod.indexOf(s), a: s && s.isAdult ? 1 : 0 });
  const hmax = LS('fongmi_histmax', 60); let hn = 0, ha = 0;
  localStorage.setItem('fongmi_hist', JSON.stringify(hist.filter(h => h.a ? ++ha <= hmax : ++hn <= hmax))); // 两池各按上限截断
  body.innerHTML = `<main id="scroller" style="overflow-y:auto">
    <div class="eprow"><button class="btn" data-focus id="vidBack">← 返回</button><button class="btn" data-focus id="findBtn">🔎 搜关联</button></div>
    <div class="vtop"><video id="player" controls autoplay data-focus></video>
      <div class="txt" id="vtxt"><h2>${esc(item.name)}</h2><p class="meta">详情加载中…</p></div></div>
    <div id="vrows"></div></main>`;
  bindBack('vidBack', () => switchView(App.videoBack || 'vod')); // 常驻返回：加载失败/超时态也能退出，不必刷新
  // 常驻搜关联：详情失败/加载中的源也能直接跨源搜同名片续播；paint 成功后升级为精确片名与当前线路/集号/进度
  let findTitle = item.name || '';
  let findState = () => ({ line: 0, ep: 0, sec: 0 });
  curFindState = () => findState(); // 始终读最新 findState（paint 会重绑精确版）
  $('#findBtn').onclick = () => openFindBox(findTitle, findState, App.curZone);
  // SWR 详情缓存：上次结果先上屏秒显（含起播），8s 内拉新成功且数据有变化才重绘；失败保留缓存
  const dk = `detail:${normUrl(s.url)}|${item.id}`;
  const cached = cacheGet(dk);
  let painted = false;
  /* O1a 跨会话进度（docs/2026-10-30-O1-O3播放与直播体验方案.md）：fongmi_prog { "<id>|<si>": {ep,sec,dur,ts} }
     timeupdate 5s 节流 / pause 即写 / ended 清除；写时顺带清理超 30 天或已脱离历史+收藏的孤儿项 */
  let progKey = '', progEp = () => 0, progLine = () => 0, progFlag = () => '', onEndedExtra = null; // onEndedExtra 由 paint 注入（连播/提示），监听只绑一次防重绘双触发
  const progSave = () => {
    if (!progKey) return;
    const v = $('#player'); if (!v) return;
    if (!isFinite(v.duration) || v.duration <= 0 || v.currentTime < 5) return;
    const all = LS('fongmi_prog', {});
    // 记线路（含 flag 名，线路表顺序变化时按名还原）：下次从历史/收藏进入直接在快线上续播
    all[progKey] = { ep: progEp(), sec: Math.round(v.currentTime), dur: Math.round(v.duration), ts: Date.now(), line: progLine(), flag: progFlag() };
    const alive = new Set([...LS('fongmi_hist', []), ...LS('fongmi_keep', [])].map(h => `${h.id}|${h.si}`));
    for (const k in all) { if (!alive.has(k) || Date.now() - (all[k].ts || 0) > 2592e6) delete all[k]; }
    localStorage.setItem('fongmi_prog', JSON.stringify(all));
  };
  const progClear = () => {
    if (!progKey) return;
    const all = LS('fongmi_prog', {});
    if (all[progKey]) { delete all[progKey]; localStorage.setItem('fongmi_prog', JSON.stringify(all)); }
  };
  {
    const pv = $('#player'); let lastProg = 0;
    pv.addEventListener('timeupdate', () => { const n = Date.now(); if (n - lastProg >= 5000) { lastProg = n; progSave(); } });
    pv.addEventListener('pause', progSave);
    pv.addEventListener('ended', () => { progClear(); if (onEndedExtra) onEndedExtra(); });
  }
  const paint = d => {
  if (!d || App.view !== 'video') return;
  painted = true;
  const si = SRC.vod.indexOf(s);
  progKey = `${d.id}|${si}`;
  $('#vtxt').innerHTML = `
    <h2>${esc(d.title)} <small class="muted" style="font-size:12px">${esc(s.name)}</small></h2>
    <p class="meta">${esc([d.remarks || d.notes, d.year, d.area, d.type].filter(Boolean).join(' / '))}</p>
    ${d.director ? `<p class="meta">导演：${esc(d.director)}</p>` : ''}
    ${d.actor ? `<p class="meta">主演：${esc(String(d.actor).slice(0, 100))}</p>` : ''}
    <p class="content">简介：${esc(d.desc.slice(0, 200))}</p><div id="vtitle" class="muted"></div>
    <div class="hrow" style="margin-top:8px"><button class="btn${LS('fongmi_keep', []).some(k => k.id === d.id && k.si === si) ? ' on' : ''}" data-focus id="keepBtn">收藏</button><button class="btn" data-focus id="changeBtn">换源</button><button class="btn" data-focus id="reSpeed" title="重新测当前集各线路速度">⚡ 测速</button><button class="btn" data-focus id="fsBtn">⛶ 全屏</button><button class="btn" data-focus id="prevEp">« 上一集</button><button class="btn" data-focus id="nextEp">下一集 »</button></div>`;
  $('#fsBtn').onclick = toggleFullscreen;
  $('#keepBtn').onclick = () => {
    let k = LS('fongmi_keep', []);
    const at = k.findIndex(x => x.id === d.id && x.si === si);
    if (at >= 0) { k.splice(at, 1); toast('已取消收藏'); } else { k.unshift({ id: d.id, name: d.title, pic: d.pic, si, eps: d.episodes[line]?.list.length || 0, flag: d.episodes[line]?.flag || '', last: d.remarks || d.notes || '', ts: Date.now(), a: s && s.isAdult ? 1 : 0 }); toast('已加入收藏'); }
    let kn = 0, ka = 0;
    localStorage.setItem('fongmi_keep', JSON.stringify(k.filter(x => x.a ? ++ka <= 50 : ++kn <= 50))); // 两池各 50 上限
    $('#keepBtn').classList.toggle('on', at < 0);
  };
  $('#changeBtn').onclick = () => doChangeSource();
  // 选线优先：首个含流后缀(.m3u8/.mp4 等)地址的线路，避免默认撞 HTML 壳页线路（方案③）
  const isStreamLink = PerfCore.isStreamLink;
  let line = d.episodes.findIndex(g => g.list.length && g.list.some(x => isStreamLink(x.link)));
  if (line < 0) line = d.episodes.findIndex(g => g.list.length);
  if (line < 0) line = 0;
  // 6h 内持久化测速复用：线路按钮直接带旧速度标签；若旧记录有合格快线，默认就选它（不再盲进慢线）
  if (LS('fongmi_linespeed', 1) !== 0) {
    const pe = LS('fongmi_lspeed', {})[dk];
    if (pe && Array.isArray(pe.sp) && Date.now() - (pe.t || 0) < 6 * 36e5) {
      LINE_SPEED.set(`${dk}|${pe.ep || 0}`, pe.sp);
      if (pe.sp.some(k => k != null && k >= PerfCore.SPEED_FAIR)) line = PerfCore.pickBestLine(d.episodes, pe.sp, pe.ep || 0);
    }
  }
  let curEp = 0; // 当前播放集序号，跨源跳转/跨会话续播按它归属
  progEp = () => curEp; progLine = () => line; progFlag = () => d.episodes[line]?.flag || '';
  let tried = new Set(); // O1b 本次失败换链中已试过的线路下标（手动换线/选集会重置）
  findTitle = d.title;
  findState = () => ({ line, ep: curEp, sec: $('#player')?.currentTime || 0 });
  const rows = $('#vrows');
  rows.innerHTML = `
    <div class="eprow"><span class="slabel">线路(共 ${d.episodes.length} 条)</span><div class="hrow" id="flagRow" style="flex:1"></div></div>
    <div class="eprow" style="align-items:flex-start"><span class="slabel">选集</span><div class="hrow" id="epRow" style="flex:1;flex-wrap:wrap"></div></div>`;
  /* A1 线路测速：paint 后异步启动，完成一线原位刷按钮第二行（不阻塞起播）；全测完且最快线≠当前、
     用户未手动换线时自动跳最快线并续回进度（fongmi_autobestline=0 关；fongmi_linespeed=0 整体关）。
     假阴性防护：采样是服务端 /proxy 代发，部分 CDN 拒 403/不支持 Range 会测出 0，但播放器实际在流畅播——
     此时该线重分类为 -1（「测速失败」灰标，不判死、不沉底），显示层以实际播放为准 */
  let userPickedLine = false, speedKey = '', speedSeq = 0, autoSrcTried = false;
  const playingHealthy = () => { const pv = $('#player'); return !!pv && pv.readyState >= 3 && !pv.error && pv.currentTime > 0; };
  const flagBtnHtml = (g, i, sp) => {
    const k = sp ? sp[i] : null;
    let t = k != null ? PerfCore.lineSpeedTag(k) : null;
    // 当前线判慢但播放器流畅 → 黄标「·播放中」，不红叉误导
    if (t && t.cls === 'sp-bad' && i === line && playingHealthy()) t = { label: k > 0 ? PerfCore.fmtSpeed(k) + ' · 播放中' : '播放中（测速失败）', cls: 'sp-mid' };
    return `${esc(g.flag)}(${g.list.length})${t ? `<span class="spsub ${t.cls}">${t.label}</span>` : ''}`; // 速度独立一行（对标影视仓播放页）
  };
  // 测速常早于起播完成：起播后再回刷一次当前线标签，把红「慢/不可用」降级为黄「播放中」
  const pvEl = $('#player');
  if (pvEl) {
    const onPlaying = () => {
      const b = document.querySelector(`#flagRow [data-l="${line}"]`);
      const sp2 = LINE_SPEED.get(`${dk}|${curEp}`);
      if (b && sp2 && sp2[line] != null && sp2[line] >= 0 && sp2[line] < PerfCore.SPEED_FAIR) b.innerHTML = flagBtnHtml(d.episodes[line], line, sp2);
    };
    if (pvEl._lsPlaying) pvEl.removeEventListener('playing', pvEl._lsPlaying); // paint 可能多次：换掉旧闭包防重复
    pvEl._lsPlaying = onPlaying;
    pvEl.addEventListener('playing', onPlaying);
  }
  const speedProbe = ep => {
    if (LS('fongmi_linespeed', 1) === 0) return;
    const key = `${dk}|${ep}`; speedKey = key; const seq = ++speedSeq;
    if (LINE_SPEED.has(key)) return; // 本作品本集已测/在测（SWR 重绘复用）
    const n = Math.min(d.episodes.length, 10), sp = new Array(n).fill(null);
    LINE_SPEED.set(key, sp);
    pMap([...Array(n).keys()], async i => {
      const g = d.episodes[i], u = g && (g.list[ep] || g.list[0]);
      let v = 0;
      if (u && u.link) { try { v = Math.round(await lineSpeed(u.link)); } catch { v = 0; } }
      if (v === 0 && i === line && playingHealthy()) v = -1; // 当前线测失败但播放正常→测速失败不判死
      sp[i] = v;
      if (seq === speedSeq && speedKey === key && App.view === 'video') {
        const b = document.querySelector(`#flagRow [data-l="${i}"]`);
        if (b) b.innerHTML = flagBtnHtml(g, i, sp); // 原位刷新，不整行重建（防焦点/滚动跳动）
      }
    }, 4).then(() => {
      if (sp.some(k => k != null)) { // 结果持久化 6h：历史/收藏再进详情页直接复用旧速度与旧快线
        const all = LS('fongmi_lspeed', {}); const now = Date.now();
        for (const k in all) if (now - (all[k].t || 0) > 6 * 36e5) delete all[k];
        all[dk] = { ep, sp, t: now };
        localStorage.setItem('fongmi_lspeed', JSON.stringify(all));
      }
      if (seq !== speedSeq || speedKey !== key || App.view !== 'video' || ep !== curEp) return;
      if (sp[line] === 0 && playingHealthy()) { sp[line] = -1; draw(curEp); } // 起播偏晚的流畅线测后补判
      if (userPickedLine || LS('fongmi_autobestline', 1) === 0) return;
      const best = PerfCore.pickBestLine(d.episodes, sp, ep);
      if (best === line) {
        // 实验室「自动换源」扩展：本作品线少且全部实测判慢（无未测/失败未知）→自动搜本作换源（默认关）
        const allBad = n && sp.every(k => k != null && k >= 0 && k < PerfCore.SPEED_FAIR);
        if (allBad && d.episodes.length <= 2 && LS('fongmi_autosrc', 0) !== 0 && !autoSrcTried) {
          autoSrcTried = true;
          toast('本作该源各线路均偏慢，自动搜本作换源…');
          doChangeSource(true);
        }
        return;
      }
      const sec = $('#player')?.currentTime || 0;
      line = best; tried = new Set([line]);
      toast(`已切换到最快线路「${d.episodes[line].flag}」`);
      draw(curEp);
      const u = d.episodes[line].list[curEp] || d.episodes[line].list[0];
      if (u) {
        playInto($('#player'), u.link, `${d.title} · ${u.name}`, nextLine);
        if (sec > 5) { const vv = $('#player'), f = () => { if (isFinite(vv.duration) && vv.duration > sec + 1) vv.currentTime = sec; }; vv.addEventListener('loadedmetadata', f, { once: true }); }
      }
    });
  };
  const draw = (ep = 0) => {
    const spNow = LINE_SPEED.get(`${dk}|${ep}`);
    // 单线作品判慢：本源没有第二条线可跳，行内直接给「搜本作换源」出口，避免孤线红标困惑
    const swapHint = d.episodes.length === 1 && spNow && spNow[0] != null && spNow[0] >= 0 && spNow[0] < PerfCore.SPEED_FAIR
      ? '<button class="chip" data-focus id="swapHint" style="margin-left:8px">⚡ 该源仅此1条线 · 搜关联换源</button>' : '';
    $('#flagRow').innerHTML = d.episodes.map((g, i) => `<button class="btn${i === line ? ' on' : ''}" data-focus data-l="${i}">${flagBtnHtml(g, i, spNow)}</button>`).join('') + swapHint;
    const progHere = LS('fongmi_prog', {})[`${d.id}|${si}`];
    $('#epRow').innerHTML = d.episodes[line].list.map((u, i) => `<button class="btn${i === ep ? ' on' : ''}${progHere && progHere.ep === i ? ' prog' : ''}" data-focus data-e="${i}">${esc(u.name)}</button>`).join('');
    syncFsBar(); // 换集/换线/重绘后同步全屏浮层集号信息与上下集置灰（含页面 #prevEp/#nextEp）
    $('#flagRow').onclick = e => {
      if (e.target.closest('#swapHint')) { openFindBox(findTitle, findState, App.curZone); return; }
      const b = e.target.closest('[data-l]'); if (!b) return;
      line = +b.dataset.l; curEp = 0; tried = new Set([line]); userPickedLine = true;
      toast(`正在切换线路至「${d.episodes[line].flag}」`);
      draw();
      speedProbe(0);
      const f = d.episodes[line].list[0];
      if (f) playInto($('#player'), f.link, `${d.title} · ${f.name}`, nextLine);
    };
    $('#epRow').onclick = e => {
      const b = e.target.closest('[data-e]'); if (!b) return;
      document.querySelectorAll('#epRow .btn').forEach(x => x.classList.toggle('on', x === b));
      curEp = +b.dataset.e; tried = new Set([line]);
      speedProbe(curEp);
      const u = d.episodes[line].list[curEp];
      playInto($('#player'), u.link, `${d.title} · ${u.name}`, nextLine);
      // 实验室「点击剧集自动全屏」：用户手势内直接进全屏浮层
      if (LS('fongmi_epfull', 1) !== 0 && !document.fullscreenElement) toggleFullscreen();
    };
  };
  $('#reSpeed').onclick = () => {
    LINE_SPEED.delete(`${dk}|${curEp}`);
    userPickedLine = false;
    draw(curEp); speedProbe(curEp);
    toast('重新测速各线路…');
  };
  /* O1b 播放失败自动换线：按「未试过≠当前」取候选，优先同集且带流后缀的线路；单次失败只跳一条，
     重试上限=线路数（tried 集天然封顶）；fongmi_autoline=0 关闭回退原 toast */
  const nextLine = () => {
    if (LS('fongmi_autoline', 1) === 0) return false;
    const cand = PerfCore.pickNextLine(d.episodes, { line, curEp, tried, speeds: LINE_SPEED.get(`${dk}|${curEp}`) || null });
    if (!cand.length) {
      // 实验室「自动换源」：全部线路失败→自动搜本作切首个同名源续播（默认关，每次进入详情页最多触发一次）
      if (LS('fongmi_autosrc', 0) !== 0 && !autoSrcTried) {
        autoSrcTried = true;
        toast('所有线路均无法播放，自动换源搜索本作…');
        doChangeSource(true);
      }
      return false;
    }
    const ni = cand[0].i;
    tried.add(ni);
    line = ni;
    draw(curEp);
    const u = d.episodes[line].list[curEp] || d.episodes[line].list[0];
    toast(`该线路无法播放，3 秒后自动切换「${d.episodes[line].flag}」`);
    // 秒挂型线路间隔 3s 再跳下一条防快闪；tried 集封顶=线路数，不会无限循环；期间手动/换台操作由守卫丢弃
    setTimeout(() => {
      if (App.view === 'video' && line === ni) playInto($('#player'), u.link, `${d.title} · ${u.name}`, nextLine);
    }, 3000);
    return true;
  };
  /* 播控补齐（用户批准）：上/下集按钮 + 本集播完自动连播下一集（fongmi_autonext=0 关闭自动，仅提示） */
  const goEp = step => {
    const g = d.episodes[line]; if (!g || !g.list.length) return;
    const ni = curEp + step;
    if (ni < 0) { toast('已是第一集'); return; }
    if (ni >= g.list.length) { toast('已是最后一集'); return; }
    curEp = ni; tried = new Set([line]);
    draw(curEp);
    speedProbe(curEp);
    playInto($('#player'), g.list[curEp].link, `${d.title} · ${g.list[curEp].name}`, nextLine);
  };
  $('#prevEp').onclick = () => goEp(-1);
  $('#nextEp').onclick = () => goEp(1);
  onEndedExtra = () => {
    const g = d.episodes[line];
    if (!g || curEp + 1 >= g.list.length) { toast('已播完全部剧集'); return; }
    if (LS('fongmi_autonext', 1) === 0) { toast('本集结束（自动连播已关，按下集按钮继续）'); return; }
    toast('本集结束，自动播放下一集'); goEp(1);
  };
  // 全屏浮层钩子：换集复用 goEp（含 tried 重置/连播链路零改动），state 供 syncFsBar 读边界集号
  fsCtl = {
    go: goEp,
    state: () => { const g = d.episodes[line]; return g && g.list.length ? { cur: curEp, total: g.list.length, title: d.title, flag: g.flag || '' } : null; },
  };
  let startEp = 0, seekTo = 0, fromProg = false;
  if (App.resume) { // 从"搜本作"跨源跳转过来：尽力对齐线路/集号/进度（优先于跨会话进度）
    const r = App.resume; App.resume = null;
    if (d.episodes[r.line]?.list.length) line = r.line;
    startEp = Math.min(r.ep || 0, d.episodes[line].list.length - 1);
  } else if (LS('fongmi_resume', 1) !== 0) { // O1a 跨会话续播（fongmi_resume=0 关闭读取）
    const p = LS('fongmi_prog', {})[`${d.id}|${si}`];
    if (p && d.episodes.length) {
      // 续播先还原上次线路：flag 精确匹配优先（线路表顺序可能变化），退化旧下标
      const fi = p.flag ? d.episodes.findIndex(g => g.flag === p.flag) : -1;
      if (fi >= 0) line = fi;
      else if (Number.isInteger(p.line) && p.line >= 0 && p.line < d.episodes.length) line = p.line;
    }
    if (p && d.episodes[line]?.list.length) {
      startEp = Math.min(p.ep || 0, d.episodes[line].list.length - 1);
      seekTo = p.sec || 0;
      fromProg = true;
    }
  }
  curEp = startEp;
  tried = new Set([line]);
  draw(startEp);
  const cur = d.episodes[line].list[startEp];
  if (cur) playInto($('#player'), cur.link, `${d.title} · ${cur.name}`, nextLine);
  speedProbe(startEp); // 首帧起播后再测速：完成一线刷一线，全部测完按开关自动跳最快线
  if (fromProg) {
    if (seekTo >= 5) { // hls 时长在 MANIFEST_PARSED 后可用，loadedmetadata/canplay 双保险
      const vv = $('#player'); let ok = false;
      const f = () => { if (ok) return; const D = vv.duration; if (isFinite(D) && D > seekTo + 1) { ok = true; vv.currentTime = seekTo; } };
      vv.addEventListener('loadedmetadata', f); vv.addEventListener('canplay', f);
    }
    if (startEp > 0 || seekTo >= 5) {
      toast(`继续播放 第${startEp + 1}集 ${Math.floor(seekTo / 60)}:${String(Math.floor(seekTo % 60)).padStart(2, '0')}`);
    }
  } else if (startEp > 0) toast(`已在新源第 ${startEp + 1} 集续播`);
  Focus.set($('#player'));
  };
  if (cached) paint(cached);
  let d;
  try { d = parseDetail(await httpGet(listUrl(s.url, `ac=videolist&ids=${encodeURIComponent(item.id)}`), 8000), s); }
  catch (e) { if (!painted) $('#vtxt').innerHTML = `<h2>${esc(item.name)}</h2><p class="err">详情失败：${esc(e.message)}</p>`; return; }
  if (!d.id) { if (!painted) $('#vtxt').innerHTML = `<h2>${esc(item.name)}</h2><p class="err">源未返回详情</p>`; return; }
  cacheSet(dk, d);
  if (painted && JSON.stringify(d) === JSON.stringify(cached)) return; // 数据未变不打断缓存首帧的播放
  paint(d);
}
/* 播放页"搜关联"：弹窗（对应 FongMi 播放器菜单搜索对话框）→ 全源搜索 → 选中结果带当前线路/集号/进度跳转续播
   片名打散为候选词词流（输入框下方，样式同搜索页关联词），点选即换词重搜；原名兜底＝旧"搜本作" */
function closeFindModal() { $('#findModal').classList.remove('open'); if (App.view === 'video') Focus.set($('#player')); }
function openFindBox(title, getState, zone) {
  const m = $('#findModal');
  if (m.classList.contains('open')) return closeFindModal(); // 再点一次收起
  m.classList.add('open');
  m.onclick = e => { if (e.target === m) closeFindModal(); };
  const kw = $('#findKw'); kw.value = title || '';
  const res = $('#findRes'); res.innerHTML = '';
  const rel = $('#relRow');
  const cands = PerfCore.relCandidates(title);
  const drawRel = () => { rel.innerHTML = cands.map(w => `<button class="btn${w === kw.value.trim() ? ' on' : ''}" data-focus data-w="${esc(w)}">${esc(w)}</button>`).join(''); };
  drawRel();
  kw.addEventListener('input', drawRel); // 手输词与候选一致时同步高亮
  let runToken = 0, curFound = [];
  const drawFind = (found, pending) => {
    res.innerHTML = found.length
      ? found.map((f, i) => `<button class="chip" data-focus data-f="${i}">${esc(f.name)}｜${esc(f.source?.name || '')}${f.remarks ? '·' + esc(f.remarks) : ''}</button>`).join('') + (pending ? '<span class="muted" style="font-size:12px">陆续返回中…</span>' : '')
      : `<span class="muted" style="font-size:12px">${pending ? '全源搜索中…' : '各源均无结果'}</span>`;
  };
  res.onclick = e => {
    const b = e.target.closest('[data-f]'); if (!b) return;
    const it = curFound[+b.dataset.f]; if (!it) return;
    const st = getState();
    App.resume = { line: st.line, ep: st.ep, sec: st.sec };
    closeFindModal();
    openDetail(it);
  };
  // 渐进渲染+SWR：缓存分片先上屏，每源返回即追加；分批防争抢，runToken 丢弃旧请求回调
  const runFind = () => {
    const q = kw.value.trim(); if (!q) return;
    const tk = ++runToken;
    curFound = [];
    const sources = collectSources(!!zone);
    if (!sources.length) { drawFind([], false); return; }
    const bySi = {};
    let left = sources.length;
    const flush = pending => {
      if (tk !== runToken || !m.classList.contains('open')) return;
      curFound = Object.values(bySi).flat().slice(0, 30);
      drawFind(curFound, pending);
    };
    const ck = PerfCore.searchKey(q);
    const { fresh, stale } = PerfCore.splitTtl(cacheRaw(ck), searchTtl());
    const srcOf = new Map(sources.map(({ s, si }) => [normUrl(s.url), { s, si }]));
    for (const [u, d] of [...Object.entries(fresh), ...Object.entries(stale)]) {
      const x = srcOf.get(u); if (x && d.length) bySi[x.si] = d.map(v => ({ ...v, source: x.s }));
    }
    flush(true);
    const fire = ({ s, si }) => (async () => {
      try {
        const raw = await httpGet(listUrl(s.url, `ac=videolist&wd=${encodeURIComponent(q)}&pg=1`), 8000);
        const list = parseList(raw, s).filter(v => isRelevant(v.name, q)).map(v => ({ ...v, source: s }));
        searchShardSet(ck, normUrl(s.url), list);
        if (tk === runToken) { bySi[si] = list; flush(true); }
      } catch { /* 单源失败静默 */ }
      finally { if (tk === runToken && --left <= 0) flush(false); }
    })();
    const [first, rest] = PerfCore.splitBatches(sources, LS('fongmi_threads', 8));
    first.forEach(fire);
    if (rest.length) setTimeout(() => { if (tk === runToken) rest.forEach(fire); }, 1200);
  };
  $('#findGo').onclick = runFind;
  rel.onclick = e => { const b = e.target.closest('[data-w]'); if (!b) return; kw.value = b.dataset.w; drawRel(); runFind(); };
  kw.onkeydown = e => { if (e.key === 'Enter') runFind(); };
  Focus.set(kw); // 两段式 R1：仅选中；OK 进编辑（编辑态 Enter 触发全源搜索）
  if (title) runFind();
}
async function doChangeSource(autoPick) {
  toast('换源：正在其它源查找同名…');
  const others = SRC.vod.filter((s, i) => i !== Home.srcIdx && !isDead(s.url));
  const title = ($('#vtxt h2')?.firstChild?.textContent || '').trim();
  if (!title) return;
  const found = [];
  await Promise.all(others.map(async s => {
    try {
      const list = parseList(await httpGet(listUrl(s.url, `ac=videolist&wd=${encodeURIComponent(title)}&pg=1`), 8000), s);
      const m = list.find(v => v.name === title) || list.find(v => v.name.includes(title));
      if (m) found.push({ ...m, source: s });
    } catch { /* 忽略 */ }
  }));
  if (!found.length) return toast(`其它源未找到「${title}」`);
  if (autoPick) { // 实验室「自动换源」：取首个同名结果，带当前线路/集/进度跳转续播
    App.resume = curFindState ? curFindState() : null;
    return openDetail(found[0]);
  }
  $('#vrows').insertAdjacentHTML('afterbegin', `<div class="eprow"><span class="slabel">换源</span><div class="hrow" id="chRow" style="flex:1">${found.map((f, i) => `<button class="btn" data-focus data-c="${i}">${esc(f.source.name)} ${esc(f.remarks || '')}</button>`).join('')}</div></div>`);
  $('#chRow').onclick = e => { const b = e.target.closest('[data-c]'); if (b) openDetail(found[+b.dataset.c]); };
}

/* ================= 连接：点播源测速探活（原完整调试台 probeAll/probeTable 移植） ================= */
const Probe = { rows: null, running: false, reqId: 0 };
function renderProbe() {
  body.innerHTML = `<div class="colhead">连接 · 点播源测速 <small class="muted" style="font-size:13px;font-weight:400">逐源请求 ac=list + 首分类列表</small></div>
    <main style="flex:1;overflow-y:auto;padding:6px 28px"><div class="hrow" style="margin-bottom:8px"><button class="btn" data-focus id="probeGo">重新检测</button><button class="btn" data-focus id="probeBack" style="margin-left:auto">← 点播</button></div><div id="probeTable"></div></main>`;
  $('#probeGo').onclick = () => probeAll();
  bindBack('probeBack', () => switchView('vod'));
  drawProbeRows();
  Focus.set($('#probeGo')); // R7
  if (!Probe.rows) probeAll();
}
const probeCls = st => /^OK/.test(st) ? 'ok' : /失败|失效|为空/.test(st) ? 'err' : 'muted';
function drawProbeRows() {
  const el = $('#probeTable'); if (!el) return;
  if (!Probe.rows) { el.innerHTML = SRC.vod.length ? '' : '<p class="muted">无点播源，请先在「源管理」导入</p>'; return; }
  el.innerHTML = Probe.rows.map(p => `<div class="srcrow"><span class="n">${esc(p.name)}</span><span class="url">${esc(p.url)}</span><span class="${probeCls(p.state)}" style="flex:0 0 auto">${p.ms != null ? p.ms + 'ms' : '—'}</span><span class="${probeCls(p.state)}" style="flex:1 1 30%">${esc(p.state)}</span></div>`).join('') || '<p class="muted">无点播源</p>';
}
async function probeAll() {
  const rid = ++Probe.reqId;
  const sources = SRC.vod;
  if (!sources.length) { Probe.rows = []; drawProbeRows(); return; }
  Probe.running = true;
  Probe.rows = sources.map(s => ({ name: s.name, url: s.url, state: '检测中…' }));
  drawProbeRows();
  await pMap(sources.map((s, i) => ({ s, i })), async ({ s, i }) => {
    if (rid !== Probe.reqId || App.view !== 'probe') return;
    const t0 = performance.now();
    try {
      const types = parseTypes(await httpGet(listUrl(s.url, 'ac=list'), 12000));
      let list = [];
      if (types.length) list = parseList(await httpGet(listUrl(s.url, `ac=videolist&t=${encodeURIComponent(types[0].id)}&pg=1`), 12000), s);
      Probe.rows[i] = { name: s.name, url: s.url, ms: Math.round(performance.now() - t0), state: list.length ? `OK：${types.length} 分类 / 列表 ${list.length} 条` : (types.length ? '仅分类可用，列表为空' : 'ac=list 与列表均无数据 → 源可能已失效') };
      if (types.length) recordOk(s, Probe.rows[i].ms); else recordFail(s, 'ac=list 与列表均无数据');
    } catch (e) {
      Probe.rows[i] = { name: s.name, url: s.url, ms: Math.round(performance.now() - t0), state: '失败：' + (e.message || e) };
      recordFail(s, String(e.message || e).slice(0, 60));
    }
    drawProbeRows(); // 逐源完成即刷新表格（并发 4，不再串行拖满全屏）
  }, 4);
  Probe.running = false;
}

/* ================= 收藏页（KeepActivity） ================= */
function renderKeep(zone = false) {
  // （入口：点播页导航「收藏」=普通池；资源口「⭐ 收藏」=专区池，两池数据隔离）
  const keep = LS('fongmi_keep', []).filter(x => isZoneRec(x) === zone);
  body.innerHTML = `<main id="scroller"><div class="rowhead">${zone ? '资源口收藏' : '我的收藏'} <small>选中卡片后按 ↑ 再确定可取消收藏</small>
    <button class="btn" data-focus id="keepBack" style="margin-left:auto">← ${zone ? '资源口' : '点播'}</button></div>
    <div class="grid" id="keepGrid">${keep.map((h, i) => cardHtml(h, i, { del: true, showSite: true })).join('') || '<p class="muted">空空如也</p>'}</div></main>`;
  bindBack('keepBack', () => switchView(zone ? 'zone' : 'vod'));
  bindGrid($('#keepGrid'), keep, h => {
    const k = LS('fongmi_keep', []); const x = k.find(y => y.id === h.id && y.si === h.si);
    if (x && x.updated) { x.updated = false; localStorage.setItem('fongmi_keep', JSON.stringify(k)); } // 进详情即清角标
    openDetail({ id: h.id, name: h.name, pic: h.pic, source: SRC.vod[h.si] });
  }, i => {
    const x = keep[i]; if (!x) return; // 分池过滤后显示序与存储序不一致，按 id+si 定位删除
    const k = LS('fongmi_keep', []); const at = k.findIndex(y => y.id === x.id && y.si === x.si);
    if (at >= 0) { k.splice(at, 1); localStorage.setItem('fongmi_keep', JSON.stringify(k)); }
    renderKeep(zone);
  });
  Focus.set($('#keepGrid').querySelector('[data-focus]') || $('#keepBack')); // R7+R2：初始/删后焦点=网格首卡，空则返回钮
  checkKeeps(keep);
}
/* O3a 追剧更新检测（docs/2026-10-30-O1-O3播放与直播体验方案.md）：仅收藏页触发非轮询；
   keepchk 分片 30min SWR；比对同线路集数增长或 remarks 变化；旧项无基线首次仅记基线；单源失败静默 */
const KEEPCHK_TTL = 30 * 60 * 1000;
let keepChkSeq = 0;
async function checkKeeps(items) {
  const tk = ++keepChkSeq;
  const results = [];
  await pMap(items.filter(x => x && x.id && SRC.vod[x.si]), async it => {
    const s = SRC.vod[it.si];
    const ck = `keepchk:${it.id}|${it.si}`;
    const c = cacheRaw(ck);
    if (c && Date.now() - c.t < KEEPCHK_TTL) { results.push({ it, ...c.d, fresh: false }); return; }
    const d = parseDetail(await httpGet(listUrl(s.url, `ac=videolist&ids=${encodeURIComponent(it.id)}`), 8000), s);
    const maxEps = Math.max(0, ...d.episodes.map(g => g.list.length));
    const eps = it.flag ? (d.episodes.find(g => g.flag === it.flag)?.list.length ?? maxEps) : maxEps;
    const last = d.remarks || d.notes || '';
    const u = it.eps == null ? false : (eps > (it.eps || 0) || (!!last && !!it.last && last !== it.last));
    cacheSet(ck, { eps, last, u });
    results.push({ it, eps, last, u, fresh: true });
  }, 3);
  if (tk !== keepChkSeq) return; // 新一轮检测已启动，丢弃旧结果
  if (!results.length || !results.some(r => !!r.u !== !!r.it.updated || (r.fresh && r.it.eps == null))) return;
  const k = LS('fongmi_keep', []);
  for (const r of results) {
    const x = k.find(y => y.id === r.it.id && y.si === r.it.si); if (!x) continue;
    if (x.eps == null && r.fresh) { x.eps = r.eps; x.last = r.last; x.ts = Date.now(); }
    x.updated = !!r.u;
  }
  localStorage.setItem('fongmi_keep', JSON.stringify(k));
  if (App.view === 'keep') renderKeep();
  else if (App.view === 'zonekeep') renderKeep(true); // 二次进入全走缓存、changed=false 不再循环
}

/* ================= 历史页（O3b：点播页导航「历史」进入，grid 管理+清空，删除连带清续播位） ================= */
function renderHist(zone = false) {
  // （入口：点播页导航「历史」=普通池；资源口「🕛 历史」=专区池，两池数据隔离）
  const hist = LS('fongmi_hist', []).filter(h => SRC.vod[h.si] && isZoneRec(h) === zone).map(h => ({ ...h, source: SRC.vod[h.si] }));
  body.innerHTML = `<main id="scroller"><div class="rowhead">${zone ? '资源口历史' : '观看历史'} <small>共 ${hist.length} 条 · 最新在最前 · 选中卡片按 ↑ 再确定删除</small>
    <button class="btn" data-focus id="histBack" style="margin-left:auto">← ${zone ? '资源口' : '点播'}</button><button class="btn" data-focus id="histClear">清空本列表</button></div>
    <div class="grid" id="histAllGrid">${hist.map((h, i) => cardHtml(h, i, { del: true, showSite: true })).join('') || '<p class="muted">暂无观看历史</p>'}</div></main>`;
  $('#histClear').onclick = () => askYes(`清空${zone ? '资源口' : '普通'}观看历史？（另一类历史与收藏的续播进度不受影响）`, () => {
    const all = LS('fongmi_hist', []);
    const gone = new Set(all.filter(h => isZoneRec(h) === zone).map(h => `${h.id}|${h.si}`));
    localStorage.setItem('fongmi_hist', JSON.stringify(all.filter(h => isZoneRec(h) !== zone)));
    const keepAlive = new Set(LS('fongmi_keep', []).map(k => `${k.id}|${k.si}`));
    const prog = LS('fongmi_prog', {});
    for (const pk in prog) { if (gone.has(pk) && !keepAlive.has(pk)) delete prog[pk]; }
    localStorage.setItem('fongmi_prog', JSON.stringify(prog));
    renderHist(zone);
  });
  bindGrid($('#histAllGrid'), hist, h => openDetail({ id: h.id, name: h.name, pic: h.pic, source: SRC.vod[h.si] }), i => {
    const x = hist[i]; if (!x) return;
    localStorage.setItem('fongmi_hist', JSON.stringify(LS('fongmi_hist', []).filter(h => !(h.id === x.id && h.si === x.si))));
    const prog = LS('fongmi_prog', {});
    delete prog[`${x.id}|${x.si}`];
    localStorage.setItem('fongmi_prog', JSON.stringify(prog));
    renderHist(zone);
  });
  bindBack('histBack', () => switchView(zone ? 'zone' : 'vod'));
  Focus.set($('#histAllGrid').querySelector('[data-focus]') || $('#histBack')); // R7：初始/删后焦点=网格首卡，空则返回钮
}

/* ================= 直播（LiveActivity：全屏+叠加层+三列面板） ================= */
const Live = { srcIdx: Math.max(0, LS('fongmi_live_idx', 0)), groups: [], cur: -1, chans: [], panelOpen: false, gi: 0, epgIndex: null, epgTimer: null, hi: -1 };
function parseLiveChannels(text) {
  const t = text.trim();
  const chans = [];
  if (/^#EXTM3U/i.test(t) || /#EXTINF/i.test(t)) {
    const lines = t.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (!line.startsWith('#EXTINF')) continue;
      const nm = /,(.+)$/.exec(line)?.[1]?.trim();
      if (!nm) continue;
      const group = /group-title="([^"]*)"/.exec(line)?.[1] || '未分组';
      let url = '';
      for (let j = i + 1; j < lines.length && !url; j++) if (!lines[j].startsWith('#')) url = lines[j].trim();
      if (url) chans.push({ group, name: nm, url });
    }
  } else {
    let group = '未分组';
    for (const raw of t.split(/\r?\n/)) {
      let l = raw.trim(); if (!l || l.toUpperCase().startsWith('#EXTM3U')) continue;
      const hi = l.indexOf('#');
      if (hi >= 0 && !/https?:\/\//i.test(l)) { group = (l.slice(0, hi) || '未分组').trim(); l = l.slice(hi + 1).trim(); if (!l) continue; }
      const m = /^(.+?),(.+)$/.exec(l);
      if (m && /^https?:\/\//i.test(m[2].trim())) chans.push({ group, name: m[1].trim(), url: m[2].trim() });
    }
  }
  return chans;
}
/* ============ O2 直播 EPG + 上次频道 + 频道收藏（docs/2026-10-30-O1-O3播放与直播体验方案.md） ============
   订阅 lives[i].epg 为 XMLTV 地址（可能 .gz：gzip 魔数走 DecompressionStream）；解析纯函数在 perf-core，可 node 测 */
const LIVE_FAV_GROUP = '★收藏';
const EPG_TTL = 5 * 60 * 1000;
const liveFav = () => LS('fongmi_live_fav', []);
async function fetchEpgText(url) {
  // httpGet 对 '<' 开头判网页抛错，XMLTV 是合法 XML 文本，单独走原始取
  const r = await fetch('/proxy?url=' + encodeURIComponent(url), { signal: AbortSignal.timeout(30000) });
  if (!r.ok) throw new Error('HTTP ' + r.status);
  let buf = new Uint8Array(await r.arrayBuffer());
  if (buf.length > 2 && buf[0] === 0x1f && buf[1] === 0x8b) {
    if (typeof DecompressionStream !== 'function') throw new Error('浏览器不支持 gzip EPG');
    buf = new Uint8Array(await new Response(new Blob([buf]).stream().pipeThrough(new DecompressionStream('gzip'))).arrayBuffer());
  }
  const text = new TextDecoder('utf-8').decode(buf);
  if (!/programme/i.test(text)) throw new Error('非 XMLTV 内容');
  return text;
}
async function ensureEpg(src) {
  Live.epgIndex = null;
  const url = src && src.epg;
  if (!url || typeof PerfCore === 'undefined' || !PerfCore.parseXmltv) { drawEpgCol(); return; }
  const ck = 'fongmi_epg:' + url;
  let text = null;
  try { const c = JSON.parse(localStorage.getItem(ck)); if (c && Date.now() - c.t < EPG_TTL) text = c.d; } catch { /* 坏缓存忽略 */ }
  if (!text) {
    try { text = await fetchEpgText(url); } catch { return; } // 失败静默降级为占位
    if (text.length < 2e6) { try { localStorage.setItem(ck, JSON.stringify({ t: Date.now(), d: text })); } catch { try { localStorage.removeItem(ck); } catch { /* 配额满即放弃缓存 */ } } }
  }
  if (App.view !== 'live' || SRC.live[Live.srcIdx]?.epg !== url) return;
  try { Live.epgIndex = PerfCore.parseXmltv(text); } catch { Live.epgIndex = null; }
  drawEpgCol();
  updateEpgTop();
}
const epHm = n => { const s = String(n || '').padStart(14, '0'); return s.slice(8, 10) + ':' + s.slice(10, 12); };
function updateEpgTop() {
  const el = $('#ltEpg'); if (!el) return;
  const c = Live.chans[Live.cur];
  if (!c || !Live.epgIndex) { el.textContent = ''; return; }
  const now = PerfCore.epgNow(PerfCore.epgFor(Live.epgIndex, c.name));
  el.textContent = now ? `现在 · ${now.title} (${epHm(now.t)}–${now.s ? epHm(now.s) : '··'})` : '';
}
function drawEpgCol() {
  const el = $('#colEpg'); if (!el) return;
  const c = Live.chans[Live.cur];
  if (!c || !Live.epgIndex) {
    el.innerHTML = `<div class="li muted">EPG：${SRC.live[Live.srcIdx]?.epg ? '节目单未取到或本台无匹配' : '当前直播源未配置节目单'}<br>（订阅 lives 带 epg=XMLTV 地址后显示节目单；回看/时移依赖源能力）</div>`;
    return;
  }
  const list = PerfCore.epgFor(Live.epgIndex, c.name);
  if (!list.length) { el.innerHTML = '<div class="li muted">EPG：本频道无节目单</div>'; return; }
  const now = PerfCore.epgNow(list);
  el.innerHTML = list.map((p, i) => `<div class="li${p === now ? ' on' : ''}" data-focus data-e="${i}"><span>${epHm(p.t)} ${esc(p.title)}${p === now ? '<b style="color:#e53935"> ·正在</b>' : ''}</span>${p.desc ? `<div class="epg-desc" style="display:none">${esc(p.desc.slice(0, 300))}</div>` : ''}</div>`).join('');
  el.querySelector('.on')?.scrollIntoView({ block: 'nearest' });
  el.onclick = e => {
    const b = e.target.closest('[data-e]'); if (!b) return;
    const d = b.querySelector('.epg-desc');
    if (d) d.style.display = d.style.display === 'none' ? 'block' : 'none';
  };
}
function toggleLiveFav(i) {
  const c = Live.chans[i]; if (!c) return;
  const k = liveFav();
  const at = k.findIndex(x => x.url === c.url && x.srcIdx === Live.srcIdx);
  if (at >= 0) { k.splice(at, 1); toast('已取消收藏频道'); }
  else { k.unshift({ name: c.name, url: c.url, group: c.group, srcIdx: Live.srcIdx }); if (k.length > 100) k.length = 100; toast('已收藏频道'); }
  localStorage.setItem('fongmi_live_fav', JSON.stringify(k));
  const has = k.some(x => x.srcIdx === Live.srcIdx);
  if (has && !Live.groups.includes(LIVE_FAV_GROUP)) Live.groups.unshift(LIVE_FAV_GROUP);
  if (!has) Live.groups = Live.groups.filter(g => g !== LIVE_FAV_GROUP);
  if (Live.gi >= Live.groups.length) Live.gi = Math.max(0, Live.groups.length - 1);
  const wasFav = Focus.el === $('#favCur'); // 经 favCur 按钮触发时重绘后焦点归位按钮，防 F7 式漂移
  drawPanel();
  if (wasFav) Focus.set($('#favCur'));
}
function renderLive() {
  const lives = SRC.live;
  body.innerHTML = `<div class="livewrap">
    <video id="player" autoplay controls data-focus></video>
    <div class="ltop"><span class="t" id="ltTitle">${lives.length ? esc(lives[Live.srcIdx]?.name) : '未导入直播源'}</span><span class="s" id="ltEpg"></span><span class="c" id="ltClock"></span><button class="btn" data-focus id="liveBack" style="pointer-events:auto">← 点播</button></div>
    <div class="lbottom" id="lbBar"><span class="num" id="lbNum">000</span><b id="lbName">—</b><span id="lbPlay">正在播放：—</span><span class="muted" id="lbLine">线路 1</span><button class="btn" data-focus id="liveFs" style="pointer-events:auto;margin-left:auto">⛶ 全屏</button></div>
    <div class="lpanel" id="lPanel">
      <div class="col" id="colGroup"></div><div class="col" id="colChan"></div><div class="col" id="colEpg"></div>
    </div>
    <div class="digital" id="digital"></div>
  </div>`;
  setInterval(() => { const el = $('#ltClock'); if (el) { const d = new Date(); el.textContent = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0') + ':' + String(d.getSeconds()).padStart(2, '0'); } }, 1000);
  clearInterval(Live.epgTimer); // 每分钟刷新顶栏「现在·节目」
  Live.epgTimer = setInterval(() => { if (App.view === 'live') updateEpgTop(); }, 60000);
  drawEpgCol();
  bindBack('liveBack', () => switchView('vod'));
  $('#liveFs').onclick = toggleFullscreen; // 审计补漏#1：直播也有 OS 全屏入口（toggleFullscreen 认 #player，直播画面同为该 id）
  if (!lives.length) { toast('无直播源，请到普通调试台导入含直播的订阅'); return; }
  if (lives.length > 1) {
    body.insertAdjacentHTML('afterbegin', `<div class="hrow" style="padding:6px 28px">${lives.map((x, i) => `<button class="chip${i === Live.srcIdx ? ' on' : ''}" data-focus data-i="${i}">${esc(x.name)}</button>`).join('')}</div>`);
    body.querySelector('[data-i]').parentElement.onclick = e => { const b = e.target.closest('[data-i]'); if (!b) return; Live.srcIdx = +b.dataset.i; localStorage.setItem('fongmi_live_idx', String(Live.srcIdx)); Live.cur = -1; renderLive(); };
  }
  loadLiveList();
}
async function loadLiveList() {
  const src = SRC.live[Live.srcIdx]; if (!src) return;
  toast('频道表拉取中…');
  let chans = [];
  try { chans = parseLiveChannels(await httpGet(src.url, 25000)); } catch (e) { toast('直播源失败：' + e.message); return; }
  if (App.view !== 'live') return;
  Live.chans = chans;
  Live.groups = [...new Set(chans.map(c => c.group))];
  if (liveFav().some(x => x.srcIdx === Live.srcIdx)) Live.groups.unshift(LIVE_FAV_GROUP);
  Live.gi = 0;
  drawPanel();
  ensureEpg(src); // 异步拉 EPG，不阻塞起播
  if (chans.length) {
    let start = 0; // O2 上次频道恢复：仅当上次台在本源，否则回落频道 0
    const last = LS('fongmi_live_last', null);
    if (last && last.srcIdx === Live.srcIdx) { const li = chans.findIndex(c => c.url === last.url); if (li >= 0) start = li; }
    playChan(start, true);
  }
  Focus.set($('#player'));
  toast('Enter/←→ 打开频道列表 · ↑↓ 换台 · 数字键输入频道号');
}
function drawPanel() {
  if (!$('#colGroup')) return;
  /* R6 遥控可达：分组列顶插「← 点播」行（面板关时 liveBack 不可达的替代通道）；频道列顶插收藏按钮，
     目标=最后聚焦频道行（Live.hi），无则正在播出频道（Live.cur）；★/☆ 文案随目标收藏态 */
  $('#colGroup').innerHTML = '<div class="li" data-focus data-home="1">← 点播</div>'
    + '<div class="li" data-focus data-fs="1">⛶ 全屏播放</div>' // 补漏#1：面板关时底栏按钮键盘不可达（↑↓被换台劫持），全屏经面板可达
    + (SRC.live.length > 1 ? SRC.live.map((x, i) => `<div class="li${i === Live.srcIdx ? ' on' : ''}" data-focus data-src="${i}">📡 ${esc(x.name)}</div>`).join('') : '') // 换源同样经面板可达（对齐 FongMi 直播菜单）
    + Live.groups.map((g, i) => `<div class="li${i === Live.gi ? ' on' : ''}" data-focus data-g="${i}">${esc(g)}</div>`).join('');
  const favUrls = new Set(liveFav().filter(x => x.srcIdx === Live.srcIdx).map(x => x.url));
  const ti = Live.chans[Live.hi] ? Live.hi : Live.cur;
  const favOn = ti >= 0 && Live.chans[ti] && favUrls.has(Live.chans[ti].url);
  const list = Live.chans.map((c, i) => ({ c, i })).filter(x => Live.groups[Live.gi] === LIVE_FAV_GROUP ? favUrls.has(x.c.url) : x.c.group === Live.groups[Live.gi]);
  $('#colChan').innerHTML = `<div class="li"><button class="btn" data-focus id="favCur">${favOn ? '★ 取消收藏选中频道' : '☆ 收藏选中频道'}</button></div>`
    + list.map(x => `<div class="li${x.i === Live.cur ? ' on' : ''}" data-focus data-i="${x.i}">${esc(x.c.name)}<span class="fav${favUrls.has(x.c.url) ? ' on' : ''}" data-f="${x.i}">★</span></div>`).join('');
  $('#colGroup').onclick = e => {
    if (e.target.closest('[data-home]')) return switchView('vod');
    if (e.target.closest('[data-fs]')) { togglePanel(false); toggleFullscreen(); return; }
    const sc = e.target.closest('[data-src]'); if (sc) { Live.srcIdx = +sc.dataset.src; localStorage.setItem('fongmi_live_idx', String(Live.srcIdx)); Live.cur = -1; renderLive(); return; }
    const b = e.target.closest('[data-g]'); if (b) { Live.gi = +b.dataset.g; drawPanel(); }
  };
  $('#colChan').onclick = e => {
    if (e.target.closest('#favCur')) { toggleLiveFav(Live.chans[Live.hi] ? Live.hi : Live.cur); return; }
    const f = e.target.closest('[data-f]'); if (f) { toggleLiveFav(+f.dataset.f); return; }
    const b = e.target.closest('[data-i]'); if (b) { playChan(+b.dataset.i); togglePanel(false); }
  };
}
function togglePanel(open) {
  Live.panelOpen = open ?? !Live.panelOpen;
  $('#lPanel')?.classList.toggle('open', Live.panelOpen);
  if (Live.panelOpen) { Focus.set($('#colGroup')?.querySelector('[data-g]') || $('#player')); drawPanel(); } // 落首分组而非「点播」行，↓↑ 即可回看导航行
  else Focus.set($('#player'));
}
function playChan(i, keepPanel) {
  const c = Live.chans[i]; if (!c) return;
  Live.cur = i;
  playInto($('#player'), c.url, c.name);
  try { localStorage.setItem('fongmi_live_last', JSON.stringify({ srcIdx: Live.srcIdx, url: c.url })); } catch { /* 存储满忽略 */ }
  $('#ltTitle').textContent = c.name;
  updateEpgTop();
  drawEpgCol();
  $('#lbNum').textContent = String(i + 1).padStart(3, '0');
  $('#lbName').textContent = c.name;
  $('#lbPlay').textContent = `正在播放：${c.name}`;
  if (!keepPanel) drawPanel();
}
function chanStep(d) {
  if (!Live.chans.length) return;
  const inGroup = Live.chans.map((c, i) => ({ c, i })).filter(x => x.c.group === Live.groups[Live.gi]);
  const pos = inGroup.findIndex(x => x.i === Live.cur);
  const next = inGroup[(pos + d + inGroup.length) % inGroup.length];
  if (next) playChan(next.i);
}
function inputDigit(k) {
  LiveKey.num += k;
  $('#digital').textContent = LiveKey.num;
  $('#digital').style.display = 'block';
  clearTimeout(LiveKey.timer);
  LiveKey.timer = setTimeout(() => {
    $('#digital').style.display = 'none';
    const n = parseInt(LiveKey.num, 10); LiveKey.num = '';
    if (n >= 1 && n <= Live.chans.length) playChan(n - 1);
    else if (n) toast(`无效频道号 ${n}（本流仅 ${Live.chans.length} 个频道）`);
  }, 1400);
}

/* ================= 启动 ================= */
if (alignZonePool()) toast('源列表已按资源口站单自动归池去重'); // 订阅里混入的预置站（含同站路径变体）收进专区池
fixZoneIdx(); // 存档/导入后的当前下标若落在专区源，先纠正（专区源仅资源口可达）
switchView('vod');
setTimeout(sourceGuard, 1500); // 点播首页先出，再静默维护（测活/订阅更新/自动切换）
setTimeout(seedVocab, 2500);   // 后台预取片名，进搜索页即有候选词
$('#qrModal').onclick = e => { if (e.target === $('#qrModal')) closeQrModal(); }; // 点遮罩关窗
$('#qrClose').onclick = closeQrModal; // 关闭按钮（此前只给了焦点漏绑事件，点/Enter 无效）
$('#askOk').onclick = () => closeAsk(true);
$('#askCancel').onclick = () => closeAsk(false);
$('#askModal').onclick = e => { if (e.target === $('#askModal')) closeAsk(false); };
$('#cfgModal').onclick = e => {
  if (e.target === $('#cfgModal')) return closeCfg();
  const b = e.target.closest('[data-v]');
  if (b && cfgOnPick) cfgOnPick(b.dataset.v);
};
$('#cfgCancel').onclick = closeCfg;
remoteInit(); // 扫码远程输入（sid 共用，弹窗按场景生成带 m 参数的码）
