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
const SRC = (() => { try { return JSON.parse(localStorage.getItem('libretvaz_sources')) || { vod: [], live: [] }; } catch { return { vod: [], live: [] }; } })();
const LS = (k, d) => { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } };
const norm = u => String(u || '').trim().replace(/\/+$/, '');
const apiUrl = (base, query) => { const b = norm(base).replace(/\?+$/, ''); return b + (b.includes('?') ? '&' : '?') + query; };

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
    const scope = $('#siteModal').classList.contains('open') ? '#siteModal' : '#app';
    return [...document.querySelectorAll(`${scope} [data-focus], #modalPlayer [data-focus]`)].filter(e => e.offsetParent !== null);
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
  else if (v === 'video') { /* 由 openDetail 渲染 */ }
}
$('#siteName').onclick = openSiteDialog;
setInterval(() => { const d = new Date(); $('#clock').textContent = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'); }, 1000);

function openSiteDialog() {
  if (!SRC.vod.length) return toast('无点播源，请到普通调试台「内置已测源」导入');
  $('#siteModal').classList.add('open');
  $('#siteGrid').innerHTML = SRC.vod.map((s, i) => `<button class="siteitem${i === Home.srcIdx ? ' on' : ''}" data-focus data-i="${i}">${esc(s.name)}${s.isAdult ? ' ·成人' : ''}</button>`).join('');
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

/* ================= 卡片渲染（adapter_vod_rect 同款） ================= */
function cardHtml(it, i, opts = {}) {
  return `<div class="card" data-focus data-i="${i}">
    ${opts.del ? '<button class="del" data-del="1">×</button>' : ''}
    <div class="pic">${it.pic ? `<img src="/proxy?url=${encodeURIComponent(it.pic)}" loading="lazy" onerror="this.remove()">` : ''}
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
  const hist = LS('fongmi_hist', []);
  body.innerHTML = `<main id="scroller">
    <div class="funcs" id="funcs">
      <div class="func" data-focus data-nav="vod"><b>点播</b><i>VodActivity</i></div>
      <div class="func" data-focus data-nav="live"><b>直播</b><i>LiveActivity</i></div>
      <div class="func" data-focus data-nav="search"><b>搜索</b><i>SearchActivity</i></div>
      <div class="func" data-focus data-nav="keep"><b>收藏</b><i>${LS('fongmi_keep', []).length} 部</i></div>
      <div class="func" data-focus data-dim="连接"><b>连接</b><i>手机扫码(模拟无)</i></div>
      <div class="func" data-focus data-dim="设置"><b>设置</b><i>SettingActivity(模拟无)</i></div>
    </div>
    ${hist.length ? `<div class="rowhead">最近观看 <small>焦点按 ×(Enter) 删除</small></div><div class="grid" id="histGrid">${hist.map((h, i) => cardHtml(h, i, { del: true })).join('')}</div>` : ''}
    <div class="rowhead">更新推荐 <small>来自源「${esc(SRC.vod[Home.srcIdx]?.name || '无')}」无参请求，滚动到底自动加载</small></div>
    <div class="grid" id="recGrid"><p class="muted">加载中…</p></div></main>`;
  $('#funcs').onclick = e => {
    const f = e.target.closest('[data-focus]'); if (!f) return;
    if (f.dataset.nav) switchView(f.dataset.nav);
    else toast(`「${f.dataset.dim}」真实版在 FongMi 里是 ${f.dataset.dim === '连接' ? '手机网页遥控' : '配置偏好页'}，模拟版未含`);
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
  try { items = parseList(await httpGet(apiUrl(s.url, `pg=${pg}`)), s); } catch (e) { err = e.message; }
  if (App.view !== 'home' || !$('#recGrid')) return;
  Home.loading = false; Home.page = pg;
  Home.items = pg === 1 ? items : Home.items.concat(items);
  Home.more = items.length > 0;
  grid.innerHTML = (err && pg === 1 ? `<p class="err">${esc(err)}</p>` : '') + Home.items.map((it, i) => cardHtml(it, i, { showSite: false })).join('');
  bindGrid(grid, Home.items, it => openDetail(it));
}

/* ================= 点播页（VodActivity：类型 chips + 网格） ================= */
const Vod = { types: [], tab: null, page: 1, items: [], loading: false, more: false, reqId: 0 };
function renderVod() {
  body.innerHTML = `<div class="hrow" id="typeRow" style="padding:6px 28px"><span class="muted" style="padding:6px 0">分类加载中…</span></div>
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
  const q = Vod.tab ? `ac=videolist&t=${encodeURIComponent(Vod.tab.id)}&pg=${pg}` : `pg=${pg}`;
  let items = [], err = null;
  try { items = parseList(await httpGet(apiUrl(s.url, q)), s); } catch (e) { err = e.message; }
  if (rid !== Vod.reqId || App.view !== 'vod' || !$('#vodGrid')) return;
  Vod.loading = false; Vod.page = pg;
  Vod.items = pg === 1 ? items : Vod.items.concat(items);
  Vod.more = items.length > 0;
  grid.innerHTML = (err && pg === 1 ? `<p class="err">${esc(err)}</p>` : '') + Vod.items.map((it, i) => cardHtml(it, i)).join('');
  bindGrid(grid, Vod.items, it => openDetail(it));
}

/* ================= 搜索输入页（SearchActivity） ================= */
function renderSearch() {
  body.innerHTML = `<div class="sbox"><input id="kw" placeholder="输入关键词，回车搜索（真实版此处是 7 列虚拟键盘）" data-focus></div>
    <main id="scroller">
      ${LS('fongmi_kw', []).length ? `<div class="rowhead">搜索历史</div><div class="hrow">${LS('fongmi_kw', []).map(k => `<button class="btn" data-focus data-k="${esc(k)}">${esc(k)}</button>`).join('')}</div>` : ''}
      <div class="rowhead">说明</div><p class="muted">FongMi 在此页左侧是麦克风+输入框+虚拟键盘，右侧为历史/热门/联想词；提交后进入「结果页」，一个源一个 Tab，源陆续返回、Tab 陆续追加。</p>
    </main>`;
  const run = () => {
    const kw = $('#kw').value.trim(); if (!kw) return;
    const h = LS('fongmi_kw', []).filter(x => x !== kw); h.unshift(kw);
    localStorage.setItem('fongmi_kw', JSON.stringify(h.slice(0, 10)));
    Collect.kw = kw; Collect.done = 0; Collect.stop = false; Collect.bySite = {};
    switchView('collect'); renderCollect();
  };
  $('#kw').onkeydown = e => { if (e.key === 'Enter') run(); };
  body.onclick = e => { const b = e.target.closest('[data-k]'); if (b) { $('#kw').value = b.dataset.k; run(); } };
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
  const sources = SRC.vod;
  if (!sources.length) { $('#cGrid').innerHTML = '<p class="err">无点播源</p>'; return; }
  sources.forEach((s, si) => {
    (async () => {
      try {
        const t = await httpGet(apiUrl(s.url, `ac=videolist&wd=${encodeURIComponent(Collect.kw)}&pg=1`));
        const list = parseList(t, s).filter(v => v.name.toLowerCase().includes(Collect.kw.toLowerCase()));
        if (Collect.stop || !list.length || App.view !== 'collect') return;
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
  try { d = parseDetail(await httpGet(apiUrl(s.url, `ac=videolist&ids=${encodeURIComponent(item.id)}`)), s); }
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
  const others = SRC.vod.filter((_, i) => i !== Home.srcIdx);
  const title = ($('#vtxt h2')?.firstChild?.textContent || '').trim();
  if (!title) return;
  const found = [];
  await Promise.all(others.map(async s => {
    try {
      const list = parseList(await httpGet(apiUrl(s.url, `ac=videolist&wd=${encodeURIComponent(title)}&pg=1`), 8000), s);
      const m = list.find(v => v.name === title) || list.find(v => v.name.includes(title));
      if (m) found.push({ ...m, source: s });
    } catch { /* 忽略 */ }
  }));
  if (!found.length) return toast(`其它源未找到「${title}」`);
  $('#vrows').insertAdjacentHTML('afterbegin', `<div class="eprow"><span class="slabel">换源</span><div class="hrow" id="chRow" style="flex:1">${found.map((f, i) => `<button class="btn" data-focus data-c="${i}">${esc(f.source.name)} ${esc(f.remarks || '')}</button>`).join('')}</div></div>`);
  $('#chRow').onclick = e => { const b = e.target.closest('[data-c]'); if (b) openDetail(found[+b.dataset.c]); };
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
