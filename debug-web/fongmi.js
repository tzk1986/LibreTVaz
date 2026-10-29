// FongMi/TV leanback 界面交互模拟：结构对照 app/src/leanback（HomeActivity/VodActivity/CollectActivity/LiveActivity/VideoActivity）
'use strict';
const $ = s => document.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

async function httpGet(url, timeout = 12000) {
  let r;
  try { r = await fetch('/proxy?url=' + encodeURIComponent(url), { signal: AbortSignal.timeout(timeout) }); }
  catch (e) { throw new Error(e?.name === 'TimeoutError' ? `请求超时（${timeout / 1000}s）` : '网络失败/域名无法解析'); }
  const text = await r.text();
  if (!r.ok) throw new Error('HTTP ' + r.status);
  if (text.trimStart().startsWith('<')) throw new Error('源返回网页而非JSON（站点可能已失效）');
  return text;
}

/* ================= 数据层（与 CmsRepository 同规则） ================= */
const loadSrc = () => { try { const o = JSON.parse(localStorage.getItem('libretvaz_sources')) || {}; return { vod: o.vod || [], live: o.live || [], subs: o.subs || [] }; } catch { return { vod: [], live: [], subs: [] }; } };
let SRC = loadSrc();
const syncSrc = () => { SRC = loadSrc(); };
// 多标签页共用 localStorage：聚焦时重同步，否则本窗旧快照会在 saveSrc 时把别的标签页删掉的源写回去
window.addEventListener('focus', () => {
  const before = JSON.stringify(SRC);
  syncSrc();
  if (JSON.stringify(SRC) === before) return;
  if (Home.srcIdx >= SRC.vod.length) { Home.srcIdx = 0; Home.tab = null; Vod.tab = null; }
  if (Live.srcIdx >= SRC.live.length) Live.srcIdx = 0;
  $('#siteName').textContent = SRC.vod[Home.srcIdx]?.name || '未配置点播源';
  if (App.view === 'home') renderHome();
  else if (App.view === 'vod') renderVod();
  else if (App.view === 'live') renderLive();
});
const LS = (k, d) => { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } };
const norm = u => String(u || '').trim().replace(/\/+$/, '');
const apiUrl = (base, query) => { const b = norm(base).replace(/\?+$/, ''); return b + (b.includes('?') ? '&' : '?') + query; };
// CMS 无参/简化列表不返回 vod_pic，海报墙必须带 ac=videolist；源 api 自带 ac= 时先剔除避免重复
const listUrl = (u, query) => apiUrl(norm(u).replace(/([?&])ac=[^&#]*/gi, '$1').replace(/[?&]+$/, ''), query);

function parseList(text, source) {
  const j = JSON.parse(text);
  return (j.list || []).map(v => ({
    id: String(v.vod_id ?? ''), name: v.vod_name || '', pic: v.vod_pic || '',
    remarks: v.vod_remarks || null, year: v.vod_year || null, area: v.vod_area || null,
    typeName: v.type_name || null, source,
  })).filter(x => x.id && x.name);
}
function parseTypes(text) {
  const j = JSON.parse(text);
  return (j.class || []).filter(c => c && c.type_id != null).map(c => ({ id: String(c.type_id), name: c.type_name || '' }));
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
/* 拼音首字母：用 ICU 拼音排序与 22 个声母锚点字比较（Chrome/Edge 支持；不可用时联想退化为子串匹配） */
const PIN_ANCHORS = [['A', '阿'], ['B', '八'], ['C', '嚓'], ['D', '多'], ['E', '讹'], ['F', '发'], ['G', '噶'], ['H', '哈'], ['J', '击'], ['K', '喀'], ['L', '垃'], ['M', '妈'], ['N', '拿'], ['O', '哦'], ['P', '啪'], ['Q', '期'], ['R', '然'], ['S', '撒'], ['T', '塌'], ['X', '昔'], ['Y', '压'], ['Z', '匝']];
let pinCollator = null;
try { pinCollator = new Intl.Collator('zh-Hans-CN-u-co-pinyin'); } catch { /* 退化 */ }
const _iniCache = {};
function initialOf(ch) {
  if (/[0-9a-z]/i.test(ch)) return ch.toLowerCase();
  if (pinCollator && /[㐀-䶿一-鿿]/.test(ch)) {
    if (_iniCache[ch]) return _iniCache[ch];
    let res = '';
    for (const [init, anchor] of PIN_ANCHORS) { if (pinCollator.compare(ch, anchor) >= 0) res = init; else break; }
    _iniCache[ch] = res;
    return res;
  }
  return '';
}
const initialsOf = s => Array.from(String(s)).map(initialOf).join('');
/* 词库：浏览/搜索中见过的片名滚动持久化，供拼音首字母联想 */
let VOCAB = LS('fongmi_words', []);
const addWords = names => {
  const set = new Set(VOCAB); let dirty = false;
  for (const n of names) { if (n && !set.has(n)) { set.add(n); VOCAB.push(n); dirty = true; } }
  if (VOCAB.length > 800) VOCAB = VOCAB.slice(-800);
  if (dirty) { try { localStorage.setItem('fongmi_words', JSON.stringify(VOCAB)); } catch { /* 满 */ } }
};
function splitPlayUrl(url) {
  return String(url || '').split('#').map(seg => {
    const i = seg.indexOf('$'); if (i < 0) return null;
    return { name: seg.slice(0, i), link: seg.slice(i + 1) };
  }).filter(Boolean);
}
function parseDetail(text, source) {
  const j = JSON.parse(text); const v = (j.list || [])[0] || {};
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
    this.el?.classList.remove('focus');
    this.el = el || null;
    if (el) { el.classList.add('focus'); el.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }
  },
  items() {
    const scope = $('#siteModal').classList.contains('open') ? '#siteModal'
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

/* ================= 按键路由 ================= */
const LiveKey = { num: '', timer: null };
document.addEventListener('keydown', e => {
  if ($('#siteModal').classList.contains('open')) {
    if (e.key === 'Escape') { closeSiteDialog(); return; }
  } else if (App.view === 'live' && document.activeElement?.tagName !== 'INPUT') {
    if (Live.panelOpen) { /* 面板开时方向键走通用焦点 */ }
    else if (e.key === 'ArrowUp') { chanStep(-1); e.preventDefault(); return; }
    else if (e.key === 'ArrowDown') { chanStep(1); e.preventDefault(); return; }
    else if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') { toast('当前直播源仅 1 条线路'); e.preventDefault(); return; }
    else if (e.key === 'Enter') { togglePanel(true); e.preventDefault(); return; }
    else if (/^[0-9]$/.test(e.key)) { inputDigit(e.key); e.preventDefault(); return; }
  }
  if (e.key === 'Escape') { onEsc(); return; }
  if (e.key === 'Enter' && document.activeElement?.tagName !== 'INPUT') { Focus.el?.click(); e.preventDefault(); }
  const D = { ArrowLeft: 'L', ArrowRight: 'R', ArrowUp: 'U', ArrowDown: 'D' }[e.key];
  if (D && document.activeElement?.tagName !== 'INPUT') { Focus.move(D); e.preventDefault(); }
});
document.addEventListener('click', e => { const t = e.target.closest('[data-focus]'); if (t) Focus.set(t); });
function onEsc() {
  if ($('#srcModal').classList.contains('open')) return closeSrcMgr();
  if ($('#siteModal').classList.contains('open')) return closeSiteDialog();
  if (App.view === 'live' && Live.panelOpen) return togglePanel(false);
  if (App.view === 'video') return switchView(App.videoBack || 'vod');
  if (App.view === 'collect') { Collect.stop = true; return switchView('search'); }
  if (App.view !== 'home') return switchView('home');
}

/* ================= 播放 ================= */
let hls = null;
function playInto(video, url, title) {
  if (hls) { hls.destroy(); hls = null; }
  video.pause(); video.removeAttribute('src');
  if (/\.(m3u8|mpd)/i.test(url) && window.Hls && Hls.isSupported()) {
    hls = new Hls({ maxBufferLength: 30 });
    hls.loadSource(url); hls.attachMedia(video);
    hls.on(Hls.Events.MANIFEST_PARSED, () => video.play().catch(() => {}));
  } else { video.src = url; video.play().catch(() => {}); }
  const t = $('#vtitle'); if (t) t.textContent = title || '';
}
function stopPlayer() { if (hls) { hls.destroy(); hls = null; } document.querySelectorAll('video').forEach(v => { v.pause(); v.removeAttribute('src'); v.load(); }); }

/* ================= 视图框架 ================= */
const App = { view: 'home', videoBack: 'vod' };
const body = $('#body');
function switchView(v) {
  stopPlayer();
  $('#siteModal').classList.remove('open');
  App.view = v;
  Focus.set(null);
  $('#siteName').textContent = SRC.vod[Home.srcIdx]?.name || '未配置点播源';
  if (v === 'home') renderHome();
  else if (v === 'vod') renderVod();
  else if (v === 'search') renderSearch();
  else if (v === 'collect') renderCollect();
  else if (v === 'live') renderLive();
  else if (v === 'keep') renderKeep();
  else if (v === 'probe') renderProbe();
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
  const order = SRC.vod.map((_, i) => i).sort((a, b) => rank(a) - rank(b) || msOf(a) - msOf(b));
  $('#siteGrid').innerHTML = order.map(i => {
    const s = SRC.vod[i], h = healthOf(s.url), dead = rank(i) === 2;
    return `<button class="siteitem${i === Home.srcIdx ? ' on' : ''}${dead ? ' dead' : ''}" data-focus data-i="${i}">${esc(s.name)}${s.isAdult ? ' ·成人' : ''}${h ? `<small style="display:block;font-size:11px;color:${dead ? '#f28b82' : '#81c995'}">${dead ? '失效' : (h.ms ?? '') + 'ms'}</small>` : ''}</button>`;
  }).join('');
  $('#siteGrid').onclick = e => {
    const b = e.target.closest('[data-i]'); if (!b) return;
    Home.srcIdx = +b.dataset.i;
    closeSiteDialog();
    $('#siteName').textContent = SRC.vod[Home.srcIdx].name;
    if (App.view === 'vod' || App.view === 'home') { Home.tab = null; Vod.tab = null; loadSiteData(); }
    if (App.view === 'vod') renderVod(); else if (App.view === 'home') renderHome();
  };
  Focus.set($('#siteGrid').querySelector('[data-focus]'));
}
function closeSiteDialog() { $('#siteModal').classList.remove('open'); }

/* ================= 源管理（对应功能行「源管理」按钮，Android 端即 SourcesScreen） ================= */
const BUILTIN_SOURCES = [
  { name: '非凡资源', url: 'https://cj.ffzyapi.com/api.php/provide/vod' },
  { name: '量子资源', url: 'https://cj.lziapi.com/api.php/provide/vod' },
  { name: '360采集', url: 'https://360zy.com/api.php/provide/vod' },
  { name: '索尼采集', url: 'https://suoniapi.com/api.php/provide/vod' },
  { name: '天天采集', url: 'http://caiji.dyttzyapi.com/api.php/provide/vod' },
  { name: '茅台采集', url: 'https://mtzy.me/api.php/provide/vod' },
  { name: '火狐采集', url: 'https://hhzyapi.com/api.php/provide/vod' },
  { name: '无尽资源', url: 'https://api.wujinapi.me/api.php/provide/vod?ac=list' },
  { name: '速播采集', url: 'https://subocaiji.com/api.php/provide/vod' },
];
const saveSrc = () => localStorage.setItem('libretvaz_sources', JSON.stringify(SRC));

/* ---- 订阅解析：从完整调试台 app.js 原样移植（对应 SubscriptionParser.kt） ---- */
const PRESETS = [
  { name: 'noimank 多仓（5源可用）', url: 'https://gitlab.com/noimank/tvbox/-/raw/main/tvboxmuti.json' },
  { name: 'Liucn 单仓（2源可用）', url: 'https://raw.liucn.cc/box/m.json' },
  { name: 'Qist 综合（点播+直播）', url: 'https://gh-proxy.com/https://raw.githubusercontent.com/qist/tvbox/master/0821.json' },
  { name: 'tvyuan 云端体检聚合（Actions 每小时测速，实测14源可用）', url: 'https://gh-proxy.com/https://raw.githubusercontent.com/25175/tvyuan/main/tvbox_collect.json' },
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
  return matchesPrepared(nn, q);
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
        if (o && o.baseUrl && o.enabled !== false && isCmsApi(o.baseUrl)) pushVod({ name: o.name || o.baseUrl, url: normUrl(o.baseUrl), isAdult: !!o.isAdult });
        else skipped++;
      }
    } else {
      for (const o of root) {
        if (o && o.url && isCmsApi(o.url)) pushVod({ name: o.name || o.url, url: o.url, detail: o.detail || null, isAdult: !!o.isAdult });
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
      for (const o of root.sources) if (o && o.url) pushVod({ name: o.name || o.url, url: o.url, detail: o.detail || null, isAdult: !!o.isAdult }); else skipped++;
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
    <h3>点播源（${SRC.vod.length}/200）</h3>
    ${SRC.vod.map((s, i) => `<div class="srcrow"><span class="n">${esc(s.name)}${s.isAdult ? ' ·成人' : ''}</span>${hb(s)}<span class="url">${esc(s.url)}</span><button class="rm" data-focus data-rmv="${i}">删除</button></div>`).join('') || '<p class="muted">无点播源</p>'}
    <h3>直播源（${SRC.live.length}）</h3>
    ${SRC.live.map((l, i) => `<div class="srcrow"><span class="n">${esc(l.name)}</span><span class="url">${esc(l.url)}</span><button class="rm" data-focus data-rml="${i}">删除</button></div>`).join('') || '<p class="muted">无直播源</p>'}`;
  $('#smAdd').onclick = () => {
    const has = new Set(SRC.vod.map(v => norm(v.url)));
    const add = BUILTIN_SOURCES.filter(s => !has.has(norm(s.url)));
    if (!add.length) return toast('内置源已全部添加');
    SRC.vod = [...SRC.vod, ...add].slice(0, 200);
    saveSrc(); toast(`已添加 ${add.length} 个已测源`); drawSrcMgr(); refreshAfterSourceChange();
  };
  $('#smClear').onclick = () => {
    if (!confirm('确认清除所有点播源？\n（将同时关闭自动维护，避免订阅自动回填——方便手动测试导入；可在「自动维护」按钮再打开）')) return;
    localStorage.setItem('fongmi_auto', '0');
    syncSrc();
    SRC.vod = []; saveSrc();
    Home.srcIdx = 0; Home.items = []; Home.page = 1; Home.more = false; Home.tab = null;
    Vod.items = []; Vod.page = 1; Vod.more = false; Vod.tab = null;
    drawSrcMgr(); refreshAfterSourceChange();
  };
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
  setTimeout(() => $('#subUrl')?.focus(), 50);
}

/* 订阅导入：解析器从完整调试台 app.js 移植（jsonLoose/parseRoot/parseExpanded） */
async function doImport(url) {
  url = (url || '').trim();
  const msg = $('#impMsg'); if (!url) return;
  if (msg) msg.textContent = '拉取解析中…';
  try {
    const text = await httpGet(url, 20000);
    const p = await parseExpanded(text, u => httpGet(u, 20000));
    syncSrc(); // 拉取期间其它标签页可能已改动，合并前以最新存储为准
    const oldVod = new Set(SRC.vod.map(v => norm(v.url)));
    const oldLive = new Set(SRC.live.map(l => norm(l.url)));
    const nv = p.vod.filter(v => !oldVod.has(norm(v.url)));
    const nl = p.live.filter(l => !oldLive.has(norm(l.url)));
    SRC.vod = [...SRC.vod, ...nv].slice(0, 200);
    SRC.live = [...SRC.live, ...nl].slice(0, 50);
    if (!SRC.subs.includes(normUrl(url))) SRC.subs.push(normUrl(url));
    saveSrc();
    if (msg) msg.innerHTML = `<span class="ok">导入成功（${p.format}）：点播 +${nv.length} · 直播 +${nl.length}${p.repos ? ` · 仓库 ${p.repos - p.reposFailed}/${p.repos}` : ''}${p.skipped ? ` · 跳过 ${p.skipped}` : ''}</span>`;
    drawSrcMgr(); refreshAfterSourceChange();
  } catch (e) {
    if (msg) msg.innerHTML = `<span class="err">${esc(e.message || String(e))}</span>`;
  }
}
function refreshAfterSourceChange() {
  Probe.rows = null;
  $('#siteName').textContent = SRC.vod[Home.srcIdx]?.name || '未配置点播源';
  if (App.view === 'vod') { Home.tab = null; Vod.tab = null; renderVod(); }
  else if (App.view === 'home') renderHome();
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
function pruneDeadSources() {
  const keep = SRC.vod.filter(s => { const h = healthOf(s.url); return !(h && h.deadSince && Date.now() - h.deadSince > DEAD_REMOVE_MS); });
  const removed = SRC.vod.length - keep.length;
  if (removed) {
    SRC.vod = keep;
    if (Home.srcIdx >= SRC.vod.length) Home.srcIdx = 0;
    saveSrc(); refreshAfterSourceChange();
    toast(`已清理 ${removed} 个连续失效满 7 天的源`);
  }
}
async function refreshSubsIfStale() {
  const missing = PRESETS.map(p => normUrl(p.url)).filter(u => !SRC.subs.includes(u));
  if (missing.length) { SRC.subs = [...SRC.subs, ...missing]; localStorage.setItem('fongmi_subs_ts', '0'); saveSrc(); }
  const ts = +LS('fongmi_subs_ts', 0);
  if (SRC.vod.length && Date.now() - ts < SUBS_REFRESH_MS) return;
  localStorage.setItem('fongmi_subs_ts', String(Date.now()));
  let added = 0;
  for (const sub of SRC.subs.slice(0, 5)) {
    try {
      const p = await parseExpanded(await httpGet(sub, 20000), u => httpGet(u, 20000));
      const have = new Set(SRC.vod.map(v => normUrl(v.url)));
      const nv = p.vod.filter(v => !have.has(normUrl(v.url)));
      if (nv.length) { SRC.vod = [...SRC.vod, ...nv].slice(0, 200); added += nv.length; }
    } catch { /* 订阅暂时不可达，下个周期再试 */ }
  }
  if (added) { saveSrc(); refreshAfterSourceChange(); toast(`订阅自动更新：新增 ${added} 个源`); }
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
        toast(`当前源已失效，自动切换到「${SRC.vod[best].name}」`);
        refreshAfterSourceChange();
      } else toast('所有点播源均不可用：请检查网络，或在源管理重新导入');
    }
    probeRestBackground();
  } finally { guarding = false; }
}

/* ================= 卡片渲染（adapter_vod_rect 同款） ================= */
function cardHtml(it, i, opts = {}) {
  return `<div class="card" data-focus data-i="${i}">
    ${opts.del ? '<button class="del" data-del="1">×</button>' : ''}
    <div class="pic"><div class="ph">${esc(it.name)}</div>${it.pic ? `<img src="/proxy?url=${encodeURIComponent(it.pic)}" loading="lazy" onerror="this.remove()">` : ''}
      ${it.year ? `<span class="year">${esc(it.year)}</span>` : ''}
      ${opts.showSite && it.source ? `<span class="site">${esc(it.source.name)}</span>` : ''}
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

/* ================= 首页（HomeActivity：功能行 + 最近观看 + 更新推荐） ================= */
const Home = { srcIdx: 0, types: [], tab: null, page: 1, items: [], loading: false, more: false, reqId: 0, histLoaded: false };
function renderHome() {
  const hist = LS('fongmi_hist', []).filter(h => SRC.vod[h.si]);
  body.innerHTML = `<main id="scroller">
    <div class="funcs" id="funcs">
      <div class="func" data-focus data-nav="vod"><b>点播</b><i>VodActivity</i></div>
      <div class="func" data-focus data-nav="live"><b>直播</b><i>LiveActivity</i></div>
      <div class="func" data-focus data-nav="search"><b>搜索</b><i>SearchActivity</i></div>
      <div class="func" data-focus data-nav="keep"><b>收藏</b><i>${LS('fongmi_keep', []).length} 部</i></div>
      <div class="func" data-focus data-nav="probe"><b>连接</b><i>源测速探活</i></div>
      <div class="func" data-focus data-nav="sources"><b>源管理</b><i>导入订阅/源设置</i></div>
      <!-- 服务器模式入口保留备用：调试台退役后暂隐藏，恢复时去掉 display:none 即可 -->
      <div class="func" data-focus data-nav="server" style="display:none"><b>服务器模式</b><i>打开调试台</i></div>
    </div>
    ${hist.length ? `<div class="rowhead">最近观看 <small>焦点按 ×(Enter) 删除</small></div><div class="grid" id="histGrid">${hist.map((h, i) => cardHtml(h, i, { del: true })).join('')}</div>` : ''}
    <div class="rowhead">更新推荐 <small>来自源「${esc(SRC.vod[Home.srcIdx]?.name || '无')}」无参请求，滚动到底自动加载</small></div>
    <div class="grid" id="recGrid"><p class="muted">加载中…</p></div></main>`;
  $('#funcs').onclick = e => {
    const f = e.target.closest('[data-focus]'); if (!f) return;
    const nav = f.dataset.nav;
    if (nav === 'sources') return openSrcMgr();
    if (nav === 'server') return void window.open('/', '_blank');
    if (nav) switchView(nav);
  };
  const hg = $('#histGrid');
  if (hg) bindGrid(hg, hist, h => openDetail({ id: h.id, name: h.name, pic: h.pic, source: SRC.vod[h.si] }), i => {
    const list = LS('fongmi_hist', []); list.splice(i, 1); localStorage.setItem('fongmi_hist', JSON.stringify(list)); renderHome();
  });
  $('#scroller').addEventListener('scroll', onScroll);
  loadRec();
}
function onScroll() {
  const sc = $('#scroller'); if (!sc) return;
  $('#bar').classList.toggle('hide', sc.scrollTop > 90);
  if (App.view === 'home') {
    if (Home.more && !Home.loading && sc.scrollTop + sc.clientHeight > sc.scrollHeight - 200) loadRec(Home.page + 1);
  } else if (App.view === 'vod') {
    if (Vod.more && !Vod.loading && sc.scrollTop + sc.clientHeight > sc.scrollHeight - 200) loadVod(Vod.page + 1);
  }
}
async function loadRec(pg = 1) {
  const s = SRC.vod[Home.srcIdx];
  const grid = $('#recGrid'); if (!grid) return;
  if (!s) { grid.innerHTML = '<p class="muted">无点播源</p>'; return; }
  Home.loading = true;
  if (pg === 1) { grid.innerHTML = '<p class="muted">加载中…</p>'; Home.items = []; }
  let items = [], err = null;
  try { items = parseList(await httpGet(listUrl(s.url, `ac=videolist&pg=${pg}`)), s); } catch (e) { err = e.message; }
  if (App.view !== 'home' || !$('#recGrid')) return;
  Home.loading = false; Home.page = pg;
  addWords(items.map(it => it.name));
  Home.items = pg === 1 ? items : Home.items.concat(items);
  Home.more = items.length > 0;
  grid.innerHTML = (err && pg === 1 ? `<p class="err">${esc(err)}</p>` : '') + Home.items.map((it, i) => cardHtml(it, i, { showSite: false })).join('');
  bindGrid(grid, Home.items, it => openDetail(it));
}

/* ================= 点播页（VodActivity：类型 chips + 网格） ================= */
const Vod = { types: [], tab: null, page: 1, items: [], loading: false, more: false, reqId: 0, filters: null, area: '', year: '', lang: '' };
function renderVod() {
  body.innerHTML = `<div class="hrow" id="typeRow" style="padding:6px 28px"><span class="muted" style="padding:6px 0">分类加载中…</span></div>
    <div class="hrow" id="subRow" style="padding:0 28px 6px"></div>
    <main id="scroller"><div class="grid" id="vodGrid"></div></main>`;
  $('#scroller').addEventListener('scroll', onScroll);
  loadTypes();
}
function loadSiteData() { Vod.reqId++; Home.reqId++; }
async function loadTypes() {
  const s = SRC.vod[Home.srcIdx]; if (!s) return;
  const rid = ++Vod.reqId;
  Vod.types = [];
  try { Vod.types = parseTypes(await httpGet(apiUrl(s.url, 'ac=list'))); } catch { /* 无分类 */ }
  if (rid !== Vod.reqId || App.view !== 'vod') return;
  const tabs = [{ id: '', name: '首页' }, ...Vod.types.map(t => ({ id: t.id, name: t.name }))];
  const cur = Vod.tab?.id ?? '';
  $('#typeRow').innerHTML = tabs.map(t => `<button class="chip${t.id === cur ? ' on' : ''}" data-focus data-tid="${esc(t.id)}">${esc(t.name)}</button>`).join('');
  $('#typeRow').onclick = e => {
    const b = e.target.closest('[data-tid]'); if (!b) return;
    Vod.tab = b.dataset.tid === '' ? null : Vod.types.find(t => t.id === b.dataset.tid);
    Vod.filters = null; Vod.area = ''; Vod.year = ''; Vod.lang = '';
    const sr = $('#subRow'); if (sr) sr.innerHTML = '';
    document.querySelectorAll('#typeRow .chip').forEach(c => c.classList.toggle('on', c === b));
    loadVod(1);
  };
  loadVod(1);
}
async function loadVod(pg = 1) {
  const s = SRC.vod[Home.srcIdx]; if (!s) return;
  const rid = Vod.reqId;
  const grid = $('#vodGrid'); if (!grid) return;
  Vod.loading = true;
  if (pg === 1) { grid.innerHTML = '<p class="muted">加载中…</p>'; Vod.items = []; }
  const t = Vod.tab;
  let q = t ? `ac=videolist&t=${encodeURIComponent(t.id)}` : 'ac=videolist';
  if (t && Vod.area) q += `&area=${encodeURIComponent(Vod.area)}`;
  if (t && Vod.year) q += `&year=${encodeURIComponent(Vod.year)}`;
  if (t && Vod.lang) q += `&lang=${encodeURIComponent(Vod.lang)}`;
  q += `&pg=${pg}`;
  let items = [], err = null, raw = '';
  try { raw = await httpGet(listUrl(s.url, q)); items = parseList(raw, s); } catch (e) { err = e.message; }
  if (rid !== Vod.reqId || App.view !== 'vod' || !$('#vodGrid')) return;
  Vod.loading = false; Vod.page = pg;
  if (pg === 1 && t) { Vod.filters = parseFilterMeta(raw); drawSubRow(); }
  addWords(items.map(it => it.name));
  Vod.items = pg === 1 ? items : Vod.items.concat(items);
  Vod.more = items.length > 0;
  drawVodGrid();
}
function drawVodGrid() {
  const grid = $('#vodGrid'); if (!grid) return;
  const t = Vod.tab;
  let shown = Vod.items, html = '';
  const yr = String(new Date().getFullYear());
  const canPin = !!t && !Vod.area && !Vod.year && !Vod.lang;
  if (canPin) {
    const fresh = Vod.items.filter(it => it.year === yr);
    const rest = Vod.items.filter(it => it.year !== yr);
    if (fresh.length) {
      shown = [...fresh, ...rest];
      html = `<div class="secthead">🆕 ${yr} 最新 · 直接看</div>${fresh.map((it, i) => cardHtml(it, i)).join('')}<div class="secthead">全部 · 按更新排序</div>${rest.map((it, i) => cardHtml(it, i + fresh.length)).join('')}`;
    }
  }
  if (!html) html = shown.map((it, i) => cardHtml(it, i)).join('');
  grid.innerHTML = html || (Vod.items.length ? '' : '<p class="muted">该分类暂无数据</p>');
  bindGrid(grid, shown, it => openDetail(it));
}
function drawSubRow() {
  const el = $('#subRow'); if (!el) return;
  const f = Vod.filters;
  if (!Vod.tab || !f) { el.innerHTML = ''; return; }
  const yr = String(new Date().getFullYear());
  const groups = [];
  if (f.area.length) groups.push(['area', '地区', f.area]);
  if (f.year.length) groups.push(['year', '年份', [...new Set([yr, ...f.year])]]);
  if (f.lang.length) groups.push(['lang', '语言', f.lang]);
  el.innerHTML = groups.map(([k, label, vals]) =>
    `<span class="muted" style="font-size:12px;white-space:nowrap">${label}</span>` +
    `<button class="chip${!Vod[k] ? ' on' : ''}" data-focus data-f="${k}" data-v="">全部</button>` +
    vals.slice(0, 24).map(v => `<button class="chip${Vod[k] === v ? ' on' : ''}" data-focus data-f="${k}" data-v="${esc(v)}">${v === yr ? `本年(${yr})` : esc(v)}</button>`).join('')
  ).join('<span style="width:12px"></span>');
  el.onclick = e => {
    const b = e.target.closest('[data-f]'); if (!b) return;
    Vod[b.dataset.f] = b.dataset.v;
    drawSubRow();
    loadVod(1);
  };
}

/* ================= 搜索输入页（SearchActivity） ================= */
function renderSearch() {
  const KB = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');
  body.innerHTML = `<div class="sbox"><input id="kw" placeholder="输入或点选下方键盘字母，回车搜索" data-focus></div>
    <div class="hrow" id="kbRow" style="padding:0 28px 6px;flex-wrap:wrap">
      ${KB.map(c => `<button class="btn kb" data-focus data-kb="${c}">${c}</button>`).join('')}
      <button class="btn" data-focus data-kb="⌫">退格</button>
      <button class="btn" data-focus data-kb="C">清空</button>
    </div>
    <div class="hrow" id="sugRow" style="padding:0 28px 6px"></div>
    <main id="scroller">
      ${LS('fongmi_kw', []).length ? `<div class="rowhead">搜索历史</div><div class="hrow">${LS('fongmi_kw', []).map(k => `<button class="btn" data-focus data-k="${esc(k)}">${esc(k)}</button>`).join('')}</div>` : ''}
      <div class="rowhead">拼音选词</div><p class="muted">依次点选片名的拼音首字母（如「庆余年」按 QYN），下方会实时联想浏览过的片名，遥控选中即填入。词库来自你浏览/搜索积累的资源名${pinCollator ? '' : '（当前浏览器不支持拼音排序，联想退化为子串匹配）'}。</p>
    </main>`;
  const run = () => {
    const kw = $('#kw').value.trim(); if (!kw) return;
    const h = LS('fongmi_kw', []).filter(x => x !== kw); h.unshift(kw);
    localStorage.setItem('fongmi_kw', JSON.stringify(h.slice(0, 10)));
    Collect.kw = kw; Collect.done = 0; Collect.stop = false; Collect.bySite = {};
    switchView('collect'); renderCollect();
  };
  const drawSug = () => {
    const q = $('#kw').value.trim().toLowerCase();
    const row = $('#sugRow'); if (!row) return;
    if (!q) { row.innerHTML = ''; return; }
    let hits = [];
    if (/^[a-z]+$/.test(q) && pinCollator) {
      hits = VOCAB.filter(w => initialsOf(w).toLowerCase().startsWith(q));
    }
    if (!hits.length) hits = VOCAB.filter(w => w.toLowerCase().includes(q));
    hits = [...new Set(hits)].slice(0, 14);
    row.innerHTML = hits.length
      ? hits.map(w => `<button class="chip" data-focus data-w="${esc(w)}">${esc(w)}</button>`).join('')
      : (VOCAB.length ? '<span class="muted" style="font-size:12px">没有匹配的已收录片名</span>' : '<span class="muted" style="font-size:12px">词库为空：先去首页/分类浏览一些资源再回来联想</span>');
  };
  $('#kw').onkeydown = e => { if (e.key === 'Enter') run(); };
  $('#kw').oninput = drawSug;
  body.onclick = e => {
    const kb = e.target.closest('[data-kb]');
    if (kb) {
      const inp = $('#kw'); const v = inp.value;
      if (kb.dataset.kb === '⌫') inp.value = v.slice(0, -1);
      else if (kb.dataset.kb === 'C') inp.value = '';
      else inp.value = v + kb.dataset.kb;
      drawSug(); return;
    }
    const w = e.target.closest('[data-w]');
    if (w) { $('#kw').value = w.dataset.w; drawSug(); return; }
    const b = e.target.closest('[data-k]'); if (b) { $('#kw').value = b.dataset.k; run(); }
  };
  setTimeout(() => $('#kw').focus(), 50);
}

/* ================= 搜索结果页（CollectActivity：一源一 Tab 渐进追加） ================= */
const Collect = { kw: '', stop: false, done: 0, bySite: {}, tabIdx: '' };
function renderCollect() {
  body.innerHTML = `<div class="colhead">“${esc(Collect.kw)}”的搜索结果 <span class="muted" style="font-size:13px">Esc 停止并返回</span></div>
    <div class="hrow" id="cTabs"><button class="chip on" data-focus data-t="">全部</button></div>
    <main id="cGrid"><p class="muted" style="padding:20px 0">等待各源返回…</p></main>`;
  $('#cTabs').onclick = e => {
    const b = e.target.closest('[data-t]'); if (!b) return;
    Collect.tabIdx = b.dataset.t;
    document.querySelectorAll('#cTabs .chip').forEach(c => c.classList.toggle('on', c === b));
    drawCollectTab();
  };
  const sources = SRC.vod.map((s, si) => ({ s, si })).filter(x => autoOn() ? !isDead(x.s.url) : true);
  if (!sources.length) { $('#cGrid').innerHTML = '<p class="err">无点播源</p>'; return; }
  sources.forEach(({ s, si }) => {
    (async () => {
      try {
        const t = await httpGet(listUrl(s.url, `ac=videolist&wd=${encodeURIComponent(Collect.kw)}&pg=1`));
        const list = parseList(t, s).filter(v => isRelevant(v.name, Collect.kw));
        if (Collect.stop || !list.length || App.view !== 'collect') return;
        addWords(list.map(v => v.name));
        Collect.bySite[si] = list;
        const tab = document.createElement('button');
        tab.className = 'chip'; tab.dataset.focus = ''; tab.dataset.t = si; tab.textContent = `${s.name}(${list.length})`;
        $('#cTabs')?.appendChild(tab);
        if (Collect.tabIdx === '') drawCollectTab();
      } catch { /* 单源失败静默，与 FongMi 一致 */ }
      finally { Collect.done++; }
    })();
  });
}
function drawCollectTab() {
  const grid = $('#cGrid'); if (!grid) return;
  let items;
  if (Collect.tabIdx === '') items = Object.values(Collect.bySite).flat();
  else items = Collect.bySite[+Collect.tabIdx] || [];
  if (!items.length) { grid.innerHTML = '<p class="muted" style="padding:20px 0">该 Tab 暂无数据</p>'; return; }
  grid.innerHTML = `<div class="grid">${items.map((it, i) => cardHtml(it, i, { showSite: Collect.tabIdx === '' })).join('')}</div>`;
  bindGrid(grid.querySelector('.grid'), items, it => { App.videoBack = 'collect'; openDetail(it); });
}

/* ================= 详情/播放一体页（VideoActivity） ================= */
async function openDetail(item) {
  const s = item.source || SRC.vod[Home.srcIdx];
  App.videoBack = App.view === 'collect' ? 'collect' : (App.view === 'home' ? 'home' : App.view === 'keep' ? 'keep' : 'vod');
  App.view = 'video';
  stopPlayer();
  const hist = LS('fongmi_hist', []).filter(h => !(h.id === String(item.id) && h.si === SRC.vod.indexOf(s)));
  hist.unshift({ id: String(item.id), name: item.name, pic: item.pic, si: SRC.vod.indexOf(s) });
  localStorage.setItem('fongmi_hist', JSON.stringify(hist.slice(0, 20)));
  body.innerHTML = `<main id="scroller" style="overflow-y:auto">
    <div class="vtop"><video id="player" controls autoplay></video>
      <div class="txt" id="vtxt"><h2>${esc(item.name)}</h2><p class="meta">详情加载中…</p></div></div>
    <div id="vrows"></div></main>`;
  let d;
  try { d = parseDetail(await httpGet(listUrl(s.url, `ac=videolist&ids=${encodeURIComponent(item.id)}`)), s); }
  catch (e) { $('#vtxt').innerHTML = `<h2>${esc(item.name)}</h2><p class="err">详情失败：${esc(e.message)}</p>`; return; }
  if (!d.id) { $('#vtxt').innerHTML = `<h2>${esc(item.name)}</h2><p class="err">源未返回详情</p>`; return; }
  if (App.view !== 'video') return;
  const si = SRC.vod.indexOf(s);
  $('#vtxt').innerHTML = `
    <h2>${esc(d.title)} <small class="muted" style="font-size:12px">${esc(s.name)}</small></h2>
    <p class="meta">${esc([d.remarks || d.notes, d.year, d.area, d.type].filter(Boolean).join(' / '))}</p>
    ${d.director ? `<p class="meta">导演：${esc(d.director)}</p>` : ''}
    ${d.actor ? `<p class="meta">主演：${esc(String(d.actor).slice(0, 100))}</p>` : ''}
    <p class="content">简介：${esc(d.desc.slice(0, 200))}</p><div id="vtitle" class="muted"></div>
    <div class="hrow" style="margin-top:8px"><button class="btn${LS('fongmi_keep', []).some(k => k.id === d.id && k.si === si) ? ' on' : ''}" data-focus id="keepBtn">收藏</button><button class="btn" data-focus id="changeBtn">换源</button></div>`;
  $('#keepBtn').onclick = () => {
    let k = LS('fongmi_keep', []);
    const at = k.findIndex(x => x.id === d.id && x.si === si);
    if (at >= 0) { k.splice(at, 1); toast('已取消收藏'); } else { k.unshift({ id: d.id, name: d.title, pic: d.pic, si }); toast('已加入收藏'); }
    localStorage.setItem('fongmi_keep', JSON.stringify(k.slice(0, 50)));
    $('#keepBtn').classList.toggle('on', at < 0);
  };
  $('#changeBtn').onclick = doChangeSource;
  let line = d.episodes.findIndex(g => g.list.length); if (line < 0) line = 0;
  const rows = $('#vrows');
  rows.innerHTML = `
    <div class="eprow"><span class="slabel">线路</span><div class="hrow" id="flagRow" style="flex:1"></div></div>
    <div class="eprow" style="align-items:flex-start"><span class="slabel">选集</span><div class="hrow" id="epRow" style="flex:1;flex-wrap:wrap"></div></div>`;
  const draw = () => {
    $('#flagRow').innerHTML = d.episodes.map((g, i) => `<button class="btn${i === line ? ' on' : ''}" data-focus data-l="${i}">${esc(g.flag)}(${g.list.length})</button>`).join('');
    $('#epRow').innerHTML = d.episodes[line].list.map((u, i) => `<button class="btn${i === 0 ? ' on' : ''}" data-focus data-e="${i}">${esc(u.name)}</button>`).join('');
    $('#flagRow').onclick = e => {
      const b = e.target.closest('[data-l]'); if (!b) return;
      line = +b.dataset.l;
      toast(`正在切换线路至「${d.episodes[line].flag}」`);
      draw();
      const f = d.episodes[line].list[0];
      if (f) playInto($('#player'), f.link, `${d.title} · ${f.name}`);
      $('#epRow').querySelector('[data-e="0"]')?.classList.add('on');
    };
    $('#epRow').onclick = e => {
      const b = e.target.closest('[data-e]'); if (!b) return;
      document.querySelectorAll('#epRow .btn').forEach(x => x.classList.toggle('on', x === b));
      const u = d.episodes[line].list[+b.dataset.e];
      playInto($('#player'), u.link, `${d.title} · ${u.name}`);
    };
  };
  draw();
  const first = d.episodes[line].list[0];
  if (first) playInto($('#player'), first.link, `${d.title} · ${first.name}`);
  Focus.set($('#player'));
}
async function doChangeSource() {
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
  $('#vrows').insertAdjacentHTML('afterbegin', `<div class="eprow"><span class="slabel">换源</span><div class="hrow" id="chRow" style="flex:1">${found.map((f, i) => `<button class="btn" data-focus data-c="${i}">${esc(f.source.name)} ${esc(f.remarks || '')}</button>`).join('')}</div></div>`);
  $('#chRow').onclick = e => { const b = e.target.closest('[data-c]'); if (b) openDetail(found[+b.dataset.c]); };
}

/* ================= 连接：点播源测速探活（原完整调试台 probeAll/probeTable 移植） ================= */
const Probe = { rows: null, running: false, reqId: 0 };
function renderProbe() {
  body.innerHTML = `<div class="colhead">连接 · 点播源测速 <small class="muted" style="font-size:13px;font-weight:400">逐源请求 ac=list + 首分类列表</small></div>
    <main style="flex:1;overflow-y:auto;padding:6px 28px"><div class="hrow" style="margin-bottom:8px"><button class="btn" data-focus id="probeGo">重新检测</button></div><div id="probeTable"></div></main>`;
  $('#probeGo').onclick = () => probeAll();
  drawProbeRows();
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
  for (let i = 0; i < sources.length; i++) {
    const s = sources[i];
    if (rid !== Probe.reqId || App.view !== 'probe') break;
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
    drawProbeRows();
  }
  Probe.running = false;
}

/* ================= 收藏页（KeepActivity） ================= */
function renderKeep() {
  const keep = LS('fongmi_keep', []);
  body.innerHTML = `<main id="scroller"><div class="rowhead">我的收藏 <small>焦点按 ×(Enter) 取消</small></div>
    <div class="grid" id="keepGrid">${keep.map((h, i) => cardHtml(h, i, { del: true, showSite: true })).join('') || '<p class="muted">空空如也</p>'}</div></main>`;
  bindGrid($('#keepGrid'), keep, h => openDetail({ id: h.id, name: h.name, pic: h.pic, source: SRC.vod[h.si] }), i => {
    const k = LS('fongmi_keep', []); k.splice(i, 1);
    localStorage.setItem('fongmi_keep', JSON.stringify(k)); renderKeep();
  });
}

/* ================= 直播（LiveActivity：全屏+叠加层+三列面板） ================= */
const Live = { srcIdx: 0, groups: [], cur: -1, chans: [], panelOpen: false, gi: 0 };
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
function renderLive() {
  const lives = SRC.live;
  body.innerHTML = `<div class="livewrap">
    <video id="player" autoplay controls></video>
    <div class="ltop"><span class="t" id="ltTitle">${lives.length ? esc(lives[Live.srcIdx]?.name) : '未导入直播源'}</span><span class="s" id="ltEpg"></span><span class="c" id="ltClock"></span></div>
    <div class="lbottom" id="lbBar"><span class="num" id="lbNum">000</span><b id="lbName">—</b><span id="lbPlay">正在播放：—</span><span class="muted" id="lbLine">线路 1</span></div>
    <div class="lpanel" id="lPanel">
      <div class="col" id="colGroup"></div><div class="col" id="colChan"></div><div class="col" id="colEpg"><div class="li muted">EPG：本直播源未配置节目单<br>(真实 FongMi 需订阅 lives 里带 epg，XMLTV 拉取后此处显示 18:00~19:00 节目名，点节目可回看)</div></div>
    </div>
    <div class="digital" id="digital"></div>
  </div>`;
  setInterval(() => { const el = $('#ltClock'); if (el) { const d = new Date(); el.textContent = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0') + ':' + String(d.getSeconds()).padStart(2, '0'); } }, 1000);
  if (!lives.length) { toast('无直播源，请到普通调试台导入含直播的订阅'); return; }
  if (lives.length > 1) {
    body.insertAdjacentHTML('afterbegin', `<div class="hrow" style="padding:6px 28px">${lives.map((x, i) => `<button class="chip${i === Live.srcIdx ? ' on' : ''}" data-focus data-i="${i}">${esc(x.name)}</button>`).join('')}</div>`);
    body.querySelector('[data-i]').parentElement.onclick = e => { const b = e.target.closest('[data-i]'); if (!b) return; Live.srcIdx = +b.dataset.i; Live.cur = -1; renderLive(); };
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
  Live.gi = 0;
  drawPanel();
  if (chans.length) playChan(0, true);
  Focus.set($('#player'));
  toast('Enter 打开频道列表 · ↑↓ 换台 · 数字键输入频道号');
}
function drawPanel() {
  if (!$('#colGroup')) return;
  $('#colGroup').innerHTML = Live.groups.map((g, i) => `<div class="li${i === Live.gi ? ' on' : ''}" data-focus data-g="${i}">${esc(g)}</div>`).join('');
  const list = Live.chans.map((c, i) => ({ c, i })).filter(x => x.c.group === Live.groups[Live.gi]);
  $('#colChan').innerHTML = list.map(x => `<div class="li${x.i === Live.cur ? ' on' : ''}" data-focus data-i="${x.i}">${esc(x.c.name)}</div>`).join('');
  $('#colGroup').onclick = e => { const b = e.target.closest('[data-g]'); if (b) { Live.gi = +b.dataset.g; drawPanel(); } };
  $('#colChan').onclick = e => { const b = e.target.closest('[data-i]'); if (b) { playChan(+b.dataset.i); togglePanel(false); } };
}
function togglePanel(open) {
  Live.panelOpen = open ?? !Live.panelOpen;
  $('#lPanel')?.classList.toggle('open', Live.panelOpen);
  if (Live.panelOpen) { Focus.set($('#colGroup')?.querySelector('[data-focus]') || $('#player')); drawPanel(); }
  else Focus.set($('#player'));
}
function playChan(i, keepPanel) {
  const c = Live.chans[i]; if (!c) return;
  Live.cur = i;
  playInto($('#player'), c.url, c.name);
  $('#ltTitle').textContent = c.name;
  $('#ltEpg').textContent = '18:00 ~ 19:00 节目单需订阅配置 EPG 接口（模拟未含）';
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
switchView('home');
setTimeout(sourceGuard, 1500); // 首页先出，再静默维护（测活/订阅更新/自动切换）
