// LibreTVaz Web 模拟调试台：逻辑移植自 android/ 端 Kotlin 实现，便于改代码前在浏览器验证
// 所有跨域请求走本地 /proxy（server.mjs）
'use strict';

const $ = s => document.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

async function httpGet(url, timeout = 15000) {
  const r = await fetch('/proxy?url=' + encodeURIComponent(url), { signal: AbortSignal.timeout(timeout) });
  const text = await r.text();
  if (!r.ok) throw new Error('HTTP ' + r.status + '：' + text.slice(0, 80));
  return text;
}

/* ================= 存储（对应 SourceStore） ================= */
const store = {
  load() {
    try { return JSON.parse(localStorage.getItem('libretvaz_sources')) || { vod: [], live: [] }; }
    catch { return { vod: [], live: [] }; }
  },
  save(d) { localStorage.setItem('libretvaz_sources', JSON.stringify(d)); },
};
let SRC = store.load();
const vodSources = () => SRC.vod;
const liveSources = () => SRC.live;

/* ================= CMS 解析（对应 CmsParser.kt） ================= */
const ADULT_KEYWORDS = ['伦理片','福利','倫理片','福利片','日本伦理','日本福利','日本福利片','日本伦理片','擦边短剧','擦边','韩国伦理','里番动漫','门事件','萝莉少女','制服诱惑','国产传媒','cosplay','黑丝诱惑','无码','日本无码','有码','日本有码','SWAG','网红主播','色情片','同性片','福利视频','无码高清','有码高清','91视频','自拍偷拍','mini传媒'];
const isAdultType = t => !!t && ADULT_KEYWORDS.some(k => t.includes(k));
const adultOf = it => it.isAdult || isAdultType(it.typeName);
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

function parseSeasonSuffix(text, allowAnnotations, bareNeedsSpace) {
  const t = text.trim();
  const explicit = new RegExp(allowAnnotations
    ? '^(.*?)\\s*第\\s*([1-9]\\d?|[一二两三四五六七八九十]{1,3})\\s*季.*$'
    : '^(.*?)\\s*第\\s*([1-9]\\d?|[一二两三四五六七八九十]{1,3})\\s*季$').exec(t);
  const bare = new RegExp(bareNeedsSpace ? '^(.*?)\\s+([1-9]\\d?)$' : '^(.*?)\\s*([1-9]\\d?)$').exec(t);
  const m = explicit ? [t, explicit[1], explicit[2]] : bare ? [t, bare[1], bare[2]] : null;
  if (!m) return null;
  if (!normalizeTitle(m[1])) return null;
  let season = /^\d+$/.test(m[2]) ? parseInt(m[2], 10) : cnNum(m[2]);
  if (season == null) return null;
  return { base: m[1], season, explicit: !!explicit };
}
function cnNum(v) {
  const digits = '零一二三四五六七八九';
  const c = v.replace(/两/g, '二');
  if (c.length === 1 && digits.indexOf(c) > 0) return digits.indexOf(c);
  const m = /^([一二三四五六七八九]?)十([一二三四五六七八九]?)$/.exec(c);
  if (m) return (m[1] ? digits.indexOf(m[1]) : 1) * 10 + (m[2] ? digits.indexOf(m[2]) : 0);
  return null;
}
function prepareQuery(wd) {
  const n = normalizeTitle(wd);
  if (!n) return null;
  return { n, season: parseSeasonSuffix(wd, false, false), tokens: wd.trim().split(/\s+/).map(normalizeTitle).filter(Boolean) };
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
function matchesPrepared(nn, q) {
  if (includesKeyword(nn, q.n)) return true;
  return q.tokens.length > 1 && q.tokens.every(t => includesKeyword(nn, t));
}

function parseTypes(data) {
  return (data.class || []).map(o => ({ id: String(o.type_id ?? ''), name: String(o.type_name ?? '') }))
    .filter(t => t.id && t.name);
}
function parseList(data, source) {
  return (data.list || []).map(v => ({
    sourceKey: source.url, sourceName: source.name, sourceUrl: source.url,
    vodId: String(v.vod_id ?? ''), name: v.vod_name || '', pic: v.vod_pic || null,
    typeName: v.type_name || null, year: v.vod_year || null, area: v.vod_area || null,
    remarks: v.vod_remarks || null, isAdult: !!source.isAdult,
  })).filter(v => v.vodId);
}
function extractEpisodes(playUrl) {
  if (!playUrl) return [];
  const groups = playUrl.split('$$$').map(g =>
    g.split('#').map(ep => (ep.split('$')[1] || '').trim())
      .filter(u => u.startsWith('http://') || u.startsWith('https://'))
  ).filter(g => g.length);
  if (!groups.length) return [];
  return groups.find(g => g.some(u => u.includes('.m3u8'))) || groups[0];
}
function parseDetail(data, source) {
  const v = (data.list || [])[0];
  if (!v) return null;
  let eps = extractEpisodes(v.vod_play_url || '');
  if (!eps.length) eps = ((v.vod_content || '').match(/https?:\/\/[^"'\s]+?\.m3u8/g) || []);
  return {
    title: v.vod_name || '', cover: v.vod_pic || null, desc: (v.vod_content || '').replace(/<[^>]*>/g, ''),
    typeName: v.type_name || null, year: v.vod_year || null, area: v.vod_area || null,
    actor: v.vod_actor || null, director: v.vod_director || null,
    sourceName: source.name, episodes: eps,
  };
}
const apiBase = s => s.url.replace(/\/+$/, '');
// TVBox 同款首页：源 api 无参请求返回「最近更新」，仅带 pg 翻页
const homeList = async (s, pg) => {
  const clean = s.url.replace(/\?+$/, '');
  const url = clean.includes('?') ? `${clean}&pg=${pg}` : `${clean}?pg=${pg}`;
  return parseList(JSON.parse(await httpGet(url, 10000)), s);
};
const getTypes = async s => parseTypes(JSON.parse(await httpGet(apiBase(s) + '?ac=list', 10000)));
const listByType = async (s, typeId, pg) => parseList(JSON.parse(await httpGet(`${apiBase(s)}?ac=videolist&t=${encodeURIComponent(typeId)}&pg=${pg}`, 10000)), s);
const getDetail = async (s, id) => parseDetail(JSON.parse(await httpGet(`${apiBase(s)}?ac=videolist&ids=${encodeURIComponent(id)}`, 10000)), s);

async function searchOneSource(s, wd) {
  const q = 'ac=videolist&wd=' + encodeURIComponent(wd);
  const data = JSON.parse(await httpGet(`${apiBase(s)}?${q}&pg=1`, 10000));
  let list = parseList(data, s);
  const pages = Math.min(Math.max(parseInt(data.pagecount || '1', 10) || 1, 1), 5);
  for (let p = 2; p <= pages; p++) {
    try { list = list.concat(parseList(JSON.parse(await httpGet(`${apiBase(s)}?${q}&pg=${p}`, 10000)), s)); } catch { /* 忽略翻页失败 */ }
  }
  return list;
}
async function searchAll(wd, onProgress) {
  const sources = vodSources();
  const done = { n: 0 }; const errors = [];
  const results = await Promise.all(sources.map(s =>
    Promise.race([searchOneSource(s, wd), new Promise((_, rej) => setTimeout(() => rej(new Error('请求超时')), 10000))])
      .catch(e => { errors.push(`${s.name}：${e?.message || String(e)}`); return []; })
      .finally(() => { done.n++; onProgress && onProgress(done.n, sources.length); })
  ));
  let all = [...new Map(results.flat().filter(Boolean).map(v => [v.sourceKey + v.vodId, v])).values()];
  all = all.filter(v => isRelevant(v.name, wd));
  const exact = normalizeTitle(wd);
  all.sort((a, b) => (normalizeTitle(a.name) === exact ? 0 : 1) - (normalizeTitle(b.name) === exact ? 0 : 1) || a.name.localeCompare(b.name) || a.sourceName.localeCompare(b.sourceName));
  return { list: all, errors };
}

/* ================= 订阅解析（对应 SubscriptionParser.kt） ================= */
function jsonLoose(text) {
  let t = text.replace(/^\s*\/\/.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '');
  t = t.replace(/,(\s*[}\]])/g, '$1');
  return JSON.parse(t);
}
const normUrl = u => String(u || '').trim().replace(/\/+$/, '');
const isCmsApi = u => /api\.php\/provide\/vod/i.test(u || '');

function parseRoot(root) {
  const vod = [], live = []; let skipped = 0; let name = null; let format = 'libretv';
  const pushVod = v => { const key = normUrl(v.url); if (!key || vod.some(x => normUrl(x.url) === key)) { skipped++; return; } if (vod.length >= 100) { skipped++; return; } vod.push(v); };
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
        // 与 TVBox 对齐：不按 type/扩展名过滤，仅剔除 proxy:// 内嵌条目
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
      for (const v of r.value.vod) { const k = normUrl(v.url); if (!seenV.has(k) && vod.length < 100) { seenV.add(k); vod.push(v); } }
      for (const l of r.value.live) { const k = normUrl(l.url); if (!seenL.has(k) && live.length < 50) { seenL.add(k); live.push(l); } }
    }
    if (!vod.length && !live.length) throw new Error(`多仓展开失败 ${reposFailed}/${entries.length}，且无可导入源`);
    return { name: root.name || null, format: 'multi-repo', vod, live, skipped: entries.length - settled.filter(r => r.status === 'fulfilled').length, repos: entries.length, reposFailed };
  }
  return parseRoot(root);
}

/* ================= 直播解析（对应 LivePlaylistParser.kt） ================= */
function parseM3u(text, fallbackName) {
  const out = []; const seen = new Set(); let pending = null;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith('#EXTINF')) {
      const name = line.slice(line.lastIndexOf(',') + 1).trim();
      const group = (/group="([^"]*)"/.exec(line) || [])[1] || null;
      const tvg = (/tvg-id="([^"]*)"/.exec(line) || [])[1] || null;
      const logo = (/tvg-logo="([^"]*)"/.exec(line) || [])[1] || null;
      pending = { name: name || fallbackName, group, tvgId: tvg, logo };
    } else if (line && !line.startsWith('#')) {
      const url = normalizeStreamUrl(line, null);
      if (url && !seen.has(url)) { seen.add(url); out.push({ id: stableId(url), name: (pending && pending.name) || url, url, group: pending && pending.group, logo: pending && pending.logo, tvgId: pending && pending.tvgId }); }
      pending = null;
    }
    if (line.toUpperCase() === '#EXTGRP' ) {} // 简化：EXTINF group 属性已覆盖
  }
  return out;
}
function parseTxt(text, fallbackName) {
  const out = []; const seen = new Set(); let group = fallbackName;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim(); if (!line || line.startsWith('#')) continue;
    if (/,#genre#$/.test(line)) { group = line.slice(0, line.indexOf(',')).trim() || fallbackName; continue; }
    const i = line.indexOf(','); if (i <= 0) continue;
    const name = line.slice(0, i).trim();
    const url = (line.slice(i + 1).split('#').map(s => s.trim()).find(u => /^https?:\/\//i.test(u)) || '');
    if (!url || seen.has(url)) continue;
    seen.add(url);
    out.push({ id: stableId(url), name, url, group, logo: null, tvgId: null });
  }
  return out;
}
function normalizeStreamUrl(u) { return /^https?:\/\//i.test(u) ? u : null; }
function stableId(s) { let h = 5381; for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0; return (h >>> 0).toString(36); }
function parseLiveText(text, fallbackName) {
  const t = text.trim();
  return t.startsWith('#EXTM3U') || t.includes('#EXTINF') ? parseM3u(text, fallbackName) : parseTxt(text, fallbackName);
}
async function loadLive(src) {
  const text = await httpGet(src.url, 15000);
  if (!text.trim()) throw new Error('播放列表为空');
  const chans = parseLiveText(text, src.name);
  if (!chans.length && /^\s*</.test(text)) throw new Error('该地址返回网页而非播放列表（可能需要浏览器访问或已失效）');
  return chans;
}

/* ================= UI ================= */
const state = { tab: 'browse', sourceIdx: 0, types: [], type: null, items: [], page: 1, loading: false, more: false, error: null, results: null, searchProg: '', searchErrs: [], liveSrcIdx: 0, liveCh: null, liveGroup: null, liveErr: null, detail: null, detailErr: null, probe: null };

function paintTabs() { $('#tabs').querySelectorAll('button').forEach(b => b.classList.toggle('active', b.dataset.tab === state.tab)); }
const V = () => $('#view');

async function switchTab(t) {
  state.tab = t; paintTabs();
  if (t === 'browse') await initBrowse();
  else if (t === 'search') render();
  else if (t === 'live') initLive();
  else if (t === 'sources') render();
}

async function initBrowse() {
  const sources = vodSources();
  if (state.sourceIdx >= sources.length) state.sourceIdx = 0;
  const s = sources[state.sourceIdx];
  state.items = []; state.error = null; state.types = []; state.type = null; state.page = 1;
  render();
  if (!s) return;
  state.loading = true; render();
  try {
    state.types = await getTypes(s);
    state.type = null; // 首页=最近更新墙（TVBox 同款），点分类才进列表
  } catch (e) { state.error = `分类加载失败：${e.message || e}（该源可能不支持 ac=list）`; }
  await loadPage(1);
}
async function loadPage(pg) {
  const s = vodSources()[state.sourceIdx];
  if (!s || !state.type) { state.loading = false; render(); return; }
  state.loading = true; state.error = null; render();
  try {
    const page = state.type ? await listByType(s, state.type.id, pg) : await homeList(s, pg);
    state.items = pg === 1 ? page : state.items.concat(page);
    state.more = page.length > 0; state.page = pg;
    if (!page.length && pg === 1) state.error = '该分类暂无数据，源可能不支持分类列表';
  } catch (e) { state.error = `列表加载失败：${e.message || e}`; }
  state.loading = false; render();
}

function render() {
  const v = V();
  if (state.tab === 'browse') v.innerHTML = viewBrowse();
  else if (state.tab === 'search') v.innerHTML = viewSearch();
  else if (state.tab === 'live') v.innerHTML = viewLive();
  else if (state.tab === 'sources') v.innerHTML = viewSources();
  else if (state.tab === 'detail') v.innerHTML = viewDetail();
  bind();
}

function chips(arr, idx, fn, key = o => o.name) {
  return arr.map((o, i) => `<span class="chip ${i === idx ? 'sel' : ''}" onclick="${fn}(${i})">${esc(key(o))}</span>`).join('');
}

function viewBrowse() {
  const sources = vodSources();
  let html = `<div class="chips">${chips(sources, state.sourceIdx, 'pickSource', o => o.name + (o.isAdult ? '·成人' : ''))}</div>`;
  if (!sources.length) return html + `<p class="muted" style="margin-top:20px">尚未导入点播源，请先到「源管理」导入订阅地址。</p>`;
  html += `<div class="chips" style="margin-top:8px"><span class="chip ${!state.type ? 'sel' : ''}" onclick="pickType(null)">首页（最近更新）</span>${state.types.map(t => `<span class="chip ${t.id === (state.type || {}).id ? 'sel' : ''}" onclick="pickType('${esc(t.id)}','${esc(t.name)}')">${esc(t.name)}</span>`).join('')}</div>`;
  html += `<div style="margin:8px 0">${state.loading ? '<span class="muted">加载中…</span>' : ''}${state.error ? `<span class="err">${esc(state.error)}</span>` : ''}</div>`;
  html += `<div class="grid">${state.items.map((it, i) => `
    <div class="card" onclick="openDetail(${i})">
      <div class="poster">${it.pic ? `<img src="${esc(it.pic)}" loading="lazy" onerror="this.remove()">` : ''}${it.remarks ? `<span class="badge">${esc(it.remarks)}</span>` : ''}</div>
      <div class="name">${adultOf(it) ? '<span class="adult">成人</span>' : ''}${esc(it.name)}</div>
      <div class="meta">${esc([it.year, it.area].filter(Boolean).join(' · '))}</div>
    </div>`).join('')}</div>`;
  if (state.more && !state.loading) html += `<p style="text-align:center;margin-top:14px"><button class="btn ghost" onclick="loadPage(${state.page + 1})">加载更多</button></p>`;
  return html;
}
window.pickSource = i => { state.sourceIdx = i; initBrowse(); };
window.pickType = (id, name) => { state.type = id ? { id, name } : null; loadPage(1); };
window.openDetail = async i => {
  const it = state.items[i] || (state.results || [])[i];
  state.detail = null; state.detailErr = null; state.detailItem = it;
  state.tab = 'detail'; render();
  const src = { name: it.sourceName, url: it.sourceUrl, isAdult: it.isAdult };
  try { state.detail = await getDetail(src, it.vodId); } catch (e) { state.detailErr = `详情加载失败：${e.message || e}`; }
  render();
};
window.backFromDetail = () => { state.tab = state.results && state._fromSearch ? 'search' : 'browse'; render(); };

function viewSearch() {
  let html = `<div class="row"><input type="text" id="q" placeholder="输入片名" value="${esc(state._q || '')}"><button class="btn" onclick="doSearch()">搜索</button><span class="muted">${esc(state.searchProg)}</span></div>`;
  if (state.searchErrs.length) html += `<p class="err">部分源失败：${esc(state.searchErrs.join('、').slice(0, 200))}</p>`;
  if (state.results) {
    html += state.results.length ? `<div class="list">${state.results.map((it, i) => `
      <div class="item" onclick="state._fromSearch=true;openDetail(${i})">
        <div><div>${adultOf(it) ? '<span class="adult">成人</span>' : ''}${esc(it.name)}</div><div class="muted">${esc([it.year, it.area, it.typeName, it.remarks].filter(Boolean).join(' · '))}</div></div>
        <span class="src">${esc(it.sourceName)}</span>
      </div>`).join('')}</div>` : '<p class="muted">没有结果：可尝试更换关键词或增加数据源</p>';
  }
  return html;
}
window.doSearch = async () => {
  const wd = $('#q').value.trim();
  if (!wd || !vodSources().length) { state.searchErrs = vodSources().length ? [] : ['尚未导入点播源']; render(); return; }
  state._q = wd; state.results = null; state.searchProg = `正在并行搜索 0/${vodSources().length} 个源…`; state.searchErrs = []; render();
  const out = await searchAll(wd, (d, t) => { state.searchProg = `正在并行搜索 ${d}/${t} 个源…`; const el = V().querySelector('.muted'); if (el) el.textContent = state.searchProg; });
  state.results = out.list; state.searchErrs = out.errors; state.searchProg = `完成，共 ${out.list.length} 条`; render();
};

function initLive() {
  state.liveSrcIdx = Math.min(state.liveSrcIdx, Math.max(liveSources().length - 1, 0));
  state.liveCh = null; state.liveErr = null; render();
}
window.pickLiveSrc = async i => {
  state.liveSrcIdx = i; state.liveCh = null; state.liveErr = null; render();
  const s = liveSources()[i]; if (!s) return;
  state.liveLoading = true; render();
  try { state.liveCh = await loadLive(s); state.liveLoading = false; render(); }
  catch (e) { state.liveLoading = false; state.liveErr = e.message || String(e); render(); }
};
function viewLive() {
  const sources = liveSources();
  let html = `<div class="chips">${sources.length ? chips(sources, state.liveSrcIdx, 'pickLiveSrc') : '<span class="muted">尚未导入直播源，请到「源管理」导入含 lives/liveSources 的订阅</span>'}</div>`;
  if (state.liveLoading) html += '<p class="muted">加载播放列表中…</p>';
  if (state.liveErr) html += `<p class="err">${esc(state.liveErr)}</p>`;
  if (state.liveCh) {
    const groups = [...new Set(state.liveCh.map(c => c.group).filter(Boolean))];
    html += `<div class="chips" style="margin-top:8px"><span class="chip ${!state.liveGroup ? 'sel' : ''}" onclick="state.liveGroup=null;render()">全部(${state.liveCh.length})</span>${groups.map(g => `<span class="chip ${state.liveGroup === g ? 'sel' : ''}" onclick="state.liveGroup='${esc(g)}';render()">${esc(g)}</span>`).join('')}</div>`;
    const list = state.liveCh.filter(c => !state.liveGroup || c.group === state.liveGroup);
    html += `<div class="list">${list.slice(0, 300).map(c => `<div class="item" onclick="playUrl('${esc(c.url)}','${esc(c.name)}')"><span>${esc(c.name)}</span><span class="src">${esc(c.group || '')}</span></div>`).join('')}</div>`;
    if (list.length > 300) html += `<p class="muted">仅显示前 300 个（共 ${list.length}）</p>`;
  }
  return html;
}
window.playUrl = async (url, title) => {
  $('#mtitle').textContent = title; $('#modal').classList.add('show');
  const video = $('#video'); video.pause(); video.removeAttribute('src'); video.load();
  if (/\.m3u8(\?|$)/i.test(url) && video.canPlayType('application/vnd.apple.mpegurl') === '' && window.Hls !== undefined) {
    const hls = new Hls(); hls.loadSource(url); hls.attachMedia(video);
  } else { video.src = url; }
  video.play().catch(() => {});
};
window.closePlayer = () => { const v = $('#video'); v.pause(); v.removeAttribute('src'); v.load(); $('#modal').classList.remove('show'); };

function viewDetail() {
  const it = state.detailItem;
  let html = `<button class="btn ghost" onclick="backFromDetail()">← 返回</button> <span class="muted">${esc(it.sourceName)}</span>`;
  html += `<h3>${adultOf(it) ? '<span class="adult">成人</span>' : ''}${esc(it.name)} ${esc([state.detail?.year, state.detail?.area].filter(Boolean).join(' · '))}</h3>`;
  if (state.detailErr) html += `<p class="err">${esc(state.detailErr)}</p>`;
  if (!state.detail && !state.detailErr) return html + '<p class="muted">详情加载中…</p>';
  const d = state.detail;
  if (d) {
    if (d.desc) html += `<p class="muted" style="max-height:120px;overflow:auto">${esc(d.desc.slice(0, 400))}</p>`;
    if (d.actor) html += `<p class="muted">主演：${esc(d.actor.slice(0, 100))}</p>`;
    html += d.episodes.length ? `<h3>选集（${d.episodes.length}）</h3>` + d.episodes.map((u, i) => `<span class="ep" onclick="playUrl('${esc(u)}','${esc(d.title)} 第${i + 1}集')">第${i + 1}集</span>`).join('') : '<p class="err">未解析出可播放地址</p>';
  }
  return html;
}

/* ---- 源管理 ---- */
window.addSourceUrl = null;
window.doImport = async () => {
  const url = $('#impUrl').value.trim();
  if (!url) return;
  const msg = $('#impMsg'); msg.textContent = '拉取中…';
  try {
    const text = await httpGet(url, 15000);
    const p = await parseExpanded(text, u => httpGet(u, 15000));
    const oldVod = new Set(vodSources().map(v => normUrl(v.url)));
    const oldLive = new Set(liveSources().map(l => normUrl(l.url)));
    const nv = p.vod.filter(v => !oldVod.has(normUrl(v.url)));
    const nl = p.live.filter(l => !oldLive.has(normUrl(l.url)));
    SRC = { vod: [...SRC.vod, ...nv].slice(0, 100), live: [...SRC.live, ...nl].slice(0, 50) };
    store.save(SRC);
    msg.innerHTML = `<span class="ok">已导入（${p.format}）：点播 +${nv.length} · 直播 +${nl.length}${p.repos ? ` · 仓库 ${p.repos - p.reposFailed}/${p.repos}` : ''}${p.skipped ? ` · 跳过 ${p.skipped}` : ''}</span>`;
    render();
  } catch (e) { msg.innerHTML = `<span class="err">${esc(e.message || String(e))}</span>`; }
};
window.delVod = i => { SRC.vod.splice(i, 1); store.save(SRC); render(); };
window.delLive = i => { SRC.live.splice(i, 1); store.save(SRC); render(); };
window.clearInput = () => { const el = $('#impUrl'); if (el) el.value = ''; };
window.clearAll = () => { if (confirm('清除全部点播/直播源？')) { SRC = { vod: [], live: [] }; store.save(SRC); state.probe = null; render(); } };
window.probeAll = async () => {
  const sources = vodSources();
  if (!sources.length) return;
  state.probe = sources.map(s => ({ name: s.name, url: s.url, state: '检测中…' })); render();
  for (let i = 0; i < sources.length; i++) {
    const s = sources[i];
    const t0 = performance.now();
    try {
      const types = await getTypes(s);
      let list = [];
      if (types.length) list = await listByType(s, types[0].id, 1);
      state.probe[i] = {
        name: s.name, url: s.url, ms: Math.round(performance.now() - t0),
        state: list.length ? `OK：${types.length} 分类 / 首页 ${list.length} 条` : (types.length ? '仅分类可用，列表为空' : 'ac=list 与列表均无数据 → 源可能已失效'),
      };
    } catch (e) { state.probe[i] = { name: s.name, url: s.url, ms: Math.round(performance.now() - t0), state: '失败：' + (e.message || e) }; }
    const el = document.querySelector('#probeTable'); if (el) el.innerHTML = probeTable();
  }
};
function probeTable() {
  if (!state.probe) return '';
  return `<table id="probeTable"><tr><th>源</th><th>耗时</th><th>结果</th></tr>${state.probe.map(p =>
    `<tr><td>${esc(p.name)}<div class="muted">${esc(p.url)}</div></td><td>${p.ms ?? '—'}</td><td class="${/^OK/.test(p.state) ? 'ok' : /失败|失效|为空/.test(p.state) ? 'err' : 'muted'}">${esc(p.state)}</td></tr>`).join('')}</table>`;
}
function viewSources() {
  return `
  <div class="row"><input type="text" id="impUrl" placeholder="订阅地址：TVBox / 多仓 / LibreTV / 源目录 JSON URL"><button class="btn" onclick="doImport()">导入</button><button class="btn ghost" onclick="clearInput()">清空输入</button><button class="btn ghost" onclick="probeAll()">检测全部点播源</button><button class="btn ghost" onclick="clearAll()">清除所有源</button></div>
  <p class="muted">导入结果：<span id="impMsg"></span></p>
  ${state.probe ? probeTable() : ''}
  <h3>点播源（${SRC.vod.length}/100）</h3>
  ${SRC.vod.map((s, i) => `<div class="item"><div>${s.isAdult ? '<span class="adult">成人</span>' : ''}${esc(s.name)}<div class="muted">${esc(s.url)}</div></div><button class="del" onclick="delVod(${i})">删除</button></div>`).join('') || '<p class="muted">无</p>'}
  <h3>直播源（${SRC.live.length}/50）</h3>
  ${SRC.live.map((s, i) => `<div class="item"><div>${esc(s.name)}<div class="muted">${esc(s.url)}</div></div><button class="del" onclick="delLive(${i})">删除</button></div>`).join('') || '<p class="muted">无</p>'}`;
}

function bind() {
  $('#tabs').querySelectorAll('button').forEach(b => b.onclick = () => switchTab(b.dataset.tab));
  const q = $('#q'); if (q) q.addEventListener('keydown', e => { if (e.key === 'Enter') doSearch(); });
}

(async function init() {
  // hls.js 供 m3u8 播放（CDN 不可达时降级原生播放）
  if (!window.Hls) {
    await new Promise(r => { const s = document.createElement('script'); s.src = 'https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js'; s.onload = s.onerror = r; document.head.appendChild(s); });
  }
  paintTabs(); await switchTab('browse');
})();
