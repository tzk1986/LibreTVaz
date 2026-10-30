// 本地调试代理 + 静态服务器：node debug-web/server.mjs → http://localhost:8787
// /proxy?url=<编码后的地址> 转发 GET，补 CORS 头，供页面直连各 CMS 源/直播源
import { createServer } from 'node:http';
import { connect } from 'node:net';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { networkInterfaces } from 'node:os';
import { gzipSync } from 'node:zlib';
import { Readable } from 'node:stream';

const ROOT = fileURLToPath(new URL('.', import.meta.url));
const PORT = process.env.PORT || 8787;
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css', '.json': 'application/json' };

/* 连接池 + 上游代理（docs/2026-10-30-图片播放与拼音候选提速方案.md ②）：
   env 有 HTTPS_PROXY/HTTP_PROXY → 直接用；env 无 → TCP 探测 127.0.0.1:7890（200ms），通则自动注入（NO_PROXY 排除本机）；
   都不通维持直连。undici 缺席时退回 Node 默认 fetch（行为不变） */
const tcpOk = (host, port, ms = 200) => new Promise(res => {
  const s = connect({ host, port });
  const t = setTimeout(() => { s.destroy(); res(false); }, ms);
  s.once('connect', () => { clearTimeout(t); s.destroy(); res(true); });
  s.once('error', () => { clearTimeout(t); res(false); });
});
const hasProxyEnv = ['HTTPS_PROXY', 'https_proxy', 'HTTP_PROXY', 'http_proxy'].some(k => process.env[k]);
let poolNote = 'undici 未安装，使用默认连接池';
try {
  const { setGlobalDispatcher, EnvHttpProxyAgent, Agent } = await import('undici');
  const opts = { connections: 90, keepAliveTimeout: 15000, keepAliveInitialDelay: 10000 };
  let mode = '直连';
  if (hasProxyEnv) { // env 代理：未设 NO_PROXY 时保底本机回环直连，避免 /proxy 访问本地目标被甩进代理
    mode = 'env 代理';
    if (!process.env.NO_PROXY && !process.env.no_proxy) process.env.NO_PROXY = process.env.no_proxy = 'localhost,127.0.0.1';
  }
  else if (EnvHttpProxyAgent && await tcpOk('127.0.0.1', 7890)) {
    process.env.HTTP_PROXY = process.env.HTTPS_PROXY = 'http://127.0.0.1:7890';
    process.env.NO_PROXY = process.env.no_proxy = 'localhost,127.0.0.1';
    mode = '自动探测 127.0.0.1:7890';
  }
  const Ctor = EnvHttpProxyAgent || Agent;
  setGlobalDispatcher(new Ctor(opts));
  poolNote = `undici ${Ctor === EnvHttpProxyAgent ? 'EnvHttpProxyAgent' : 'Agent'} ${mode} connections=90 keepAlive=15s`;
} catch { /* 保底：默认全局池 */ }

/* m3u8 改写纯函数复用 perf-core（node ESM import 副作用挂 globalThis） */
let rewriteM3u8 = null;
try { await import('./perf-core.js'); rewriteM3u8 = globalThis.PerfCore?.rewriteM3u8 || null; } catch { /* 缺文件时 m3u8 原样透传 */ }

/* /proxy 缓存（方案②）：LRU 200 条；JSON 200 响应 30s 内直回 hit，过期至 5min 回 stale 并后台刷新；
   &nc=1 旁路；PROXY_CACHE=0 整体关闭；任何缓存异常路径回退直连 */
const PROXY_CACHE = process.env.PROXY_CACHE !== '0';
const PROXY_STREAM = process.env.PROXY_STREAM !== '0'; // =0 回退旧全缓冲代理（方案⑤兜底开关）
const CACHE_MAX = 200, CACHE_TTL = 30e3, CACHE_STALE_MAX = 5 * 60e3;
const pc = new Map();
const pcGet = k => { const v = pc.get(k); if (v) { pc.delete(k); pc.set(k, v); } return v; };
const pcSet = (k, v) => { pc.delete(k); pc.set(k, v); if (pc.size > CACHE_MAX) pc.delete(pc.keys().next().value); };
const isJsonCt = ct => /json/i.test(ct || '');
const revalidating = new Set();

/* 超时只作用于"等响应头"15s：头到手即放行，大文件/流不再被整体超时切断（方案②） */
async function fetchUp(target, req = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(new Error('上游 15s 无响应')), 15000);
  const headers = {
    'user-agent': req.headers?.['user-agent'] || 'Mozilla/5.0 (LibreTVaz-debug)',
    'referer': (() => { try { return new URL(target).origin + '/'; } catch { return undefined; } })(),
    'accept': '*/*',
  };
  if (req.headers?.range) headers.range = req.headers.range; // Range 透传：边下边播/拖动
  try { const r = await fetch(target, { redirect: 'follow', signal: ctrl.signal, headers }); clearTimeout(timer); return r; }
  catch (e) { clearTimeout(timer); throw e; }
}

/* 全量缓冲取 JSON/小文本（缓存路径用） */
async function fetchTarget(target, req) {
  const r = await fetchUp(target, req);
  return { status: r.status, contentType: r.headers.get('content-type') || 'text/plain; charset=utf-8', body: Buffer.from(await r.arrayBuffer()) };
}

const isM3u8Ct = (ct, target) => {
  let p = ''; try { p = new URL(target).pathname; } catch { /* noop */ }
  return /mpegurl/i.test(ct || '') || /\.m3u8$/i.test(p);
};

function send(req, res, { status = 200, contentType, body, cache, extra }, { cors = true, gz = true } = {}) {
  const headers = { 'content-type': contentType };
  if (cors) headers['access-control-allow-origin'] = '*';
  if (cache) headers['x-proxy-cache'] = cache;
  if (extra) Object.assign(headers, extra);
  let payload = body;
  if (gz && body.length > 860 && /gzip/.test(req.headers['accept-encoding'] || '') && /(json|javascript|text\/|html|mpegurl)/.test(contentType)) {
    try { payload = gzipSync(body); headers['content-encoding'] = 'gzip'; } catch { /* 压缩失败原样输出 */ }
  }
  res.writeHead(status, headers);
  res.end(req.method === 'HEAD' ? undefined : payload);
}

/* 图片/媒体等二进制：流式 pipe，不缓冲；图片 30 天缓存头，其余透传 Range/206 */
async function pipeStream(req, res, r) {
  const ct = r.headers.get('content-type') || 'application/octet-stream';
  const headers = { 'content-type': ct, 'access-control-allow-origin': '*', 'x-accel-buffering': 'no', 'x-proxy-cache': 'stream' };
  for (const k of ['content-length', 'content-range', 'accept-ranges', 'etag', 'last-modified']) {
    const v = r.headers.get(k); if (v) headers[k] = v;
  }
  if (!headers['cache-control']) headers['cache-control'] = /^image\//i.test(ct) ? 'public, max-age=2592000' : 'public, max-age=3600';
  res.writeHead(r.status, headers);
  if (req.method === 'HEAD' || !r.body) return res.end();
  const node = Readable.fromWeb(r.body);
  node.on('error', () => res.destroy());
  req.on('close', () => node.destroy()); // 浏览器停播/拖动即断开上游
  node.pipe(res);
}

/* 扫码远程输入：手机页面 POST 进来，TV 页面轮询取走（每个 sid 一份暂存） */
const remoteBox = new Map();
function lanHost() {
  for (const list of Object.values(networkInterfaces())) {
    for (const n of list || []) if (n.family === 'IPv4' && !n.internal) return n.address;
  }
  return 'localhost';
}
function remotePage() {
  return `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LibreTVaz 远程输入</title><style>
body{font:16px/1.6 system-ui,'Microsoft YaHei',sans-serif;background:#14171a;color:#e8eaed;margin:0;padding:20px;max-width:520px;margin:auto}
h2{font-size:18px}.tabs{display:flex;gap:8px;margin:12px 0}
.tab{flex:1;padding:10px;border-radius:8px;background:#26292d;border:2px solid transparent;text-align:center;cursor:pointer;color:#9aa0a6}
.tab.on{border-color:#3ea6ff;color:#fff;background:#1d2a3a}
textarea{width:100%;box-sizing:border-box;height:96px;background:#26292d;color:#e8eaed;border:1px solid #3a3f44;border-radius:8px;padding:10px;font:inherit}
button.go{width:100%;margin-top:12px;padding:12px;border:none;border-radius:8px;background:#3ea6ff;color:#000;font-size:16px;font-weight:600;cursor:pointer}
#msg{margin-top:10px;color:#81c995;min-height:22px;font-size:14px}.hint{color:#9aa0a6;font-size:13px;margin-top:10px}
</style></head><body>
<h2>LibreTVaz · 远程输入</h2>
<div class="tabs"><div class="tab on" data-m="search">搜索关键词</div><div class="tab" data-m="sub">订阅配置链接</div></div>
<div id="p-search"><textarea id="t-search" placeholder="输入片名或拼音关键词，发送到电视直接搜索"></textarea></div>
<div id="p-sub" style="display:none"><textarea id="t-sub" placeholder="粘贴订阅地址（.json / TVBox 多仓），发送到电视自动导入"></textarea></div>
<button class="go" id="send">发送到电视</button>
<div id="msg"></div>
<p class="hint">电视端搜索页与源管理共用本页面（同一个二维码），切换上方 Tab 即可，无需重复扫码。</p>
<script>
const q = new URLSearchParams(location.search), sid = q.get('id') || '';
let mode = 'search';
document.querySelectorAll('.tab').forEach(t => t.onclick = () => {
  mode = t.dataset.m;
  document.querySelectorAll('.tab').forEach(x => x.classList.toggle('on', x === t));
  document.getElementById('p-search').style.display = mode === 'search' ? '' : 'none';
  document.getElementById('p-sub').style.display = mode === 'sub' ? '' : 'none';
});
document.getElementById('send').onclick = async () => {
  const text = document.getElementById(mode === 'search' ? 't-search' : 't-sub').value.trim();
  const msg = document.getElementById('msg');
  if (!text) { msg.style.color = '#f28b82'; msg.textContent = '内容为空'; return; }
  msg.style.color = '#81c995';
  try {
    const r = await fetch('/api/remote/send', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ sid, mode, text }) });
    const j = await r.json();
    msg.textContent = j.ok ? (mode === 'search' ? '已发送，电视将直接搜索' : '已发送，电视将自动导入') : '失败：电视页面未打开或已刷新';
  } catch (e) { msg.style.color = '#f28b82'; msg.textContent = '发送失败 ' + e.message; }
};
</script></body></html>`;
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');

  if (url.pathname === '/remote') {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    return res.end(remotePage());
  }
  if (url.pathname === '/api/remote/base') {
    res.writeHead(200, { 'content-type': 'application/json', 'access-control-allow-origin': '*' });
    return res.end(JSON.stringify({ host: lanHost(), port: +PORT }));
  }
  if (url.pathname === '/api/remote/send' && req.method === 'POST') {
    let raw = '';
    req.on('data', c => { raw += c; if (raw.length > 1e6) req.destroy(); });
    req.on('end', () => {
      let ok = false;
      try {
        const { sid, mode, text } = JSON.parse(raw);
        if (sid && text) { remoteBox.set(String(sid), { mode: mode === 'sub' ? 'sub' : 'search', text: String(text).slice(0, 4000), t: Date.now() }); ok = true; }
      } catch { /* 忽略非法 body */ }
      res.writeHead(200, { 'content-type': 'application/json', 'access-control-allow-origin': '*' });
      res.end(JSON.stringify({ ok }));
    });
    return;
  }
  if (url.pathname === '/api/remote/poll') {
    const sid = url.searchParams.get('id') || '';
    const hit = remoteBox.get(sid);
    if (hit && Date.now() - hit.t < 120000) remoteBox.delete(sid);
    res.writeHead(200, { 'content-type': 'application/json', 'access-control-allow-origin': '*' });
    return res.end(JSON.stringify(hit && Date.now() - hit.t < 120000 ? hit : {}));
  }

  if (url.pathname === '/proxy') {
    const target = url.searchParams.get('url') || '';
    if (!/^https?:\/\//i.test(target)) {
      res.writeHead(400, { 'access-control-allow-origin': '*' });
      return res.end('bad url');
    }
    const fail = e => { res.writeHead(502, { 'access-control-allow-origin': '*', 'content-type': 'text/plain; charset=utf-8' }); res.end('proxy error: ' + (e.message || e)); };
    const bypass = !PROXY_CACHE || url.searchParams.get('nc') === '1';
    const jsonCacheFlow = async () => { // JSON SWR：hit / stale+后台刷 / miss 拉新（原方案②逻辑）
      const hit = bypass ? null : pcGet(target);
      if (hit && isJsonCt(hit.contentType)) {
        const age = Date.now() - hit.t;
        if (age <= CACHE_TTL) { send(req, res, { ...hit, cache: 'hit' }); return; }
        if (age <= CACHE_STALE_MAX) { // SWR：stale 先行，后台异步回源（同 URL 并发只刷一次）
          send(req, res, { ...hit, cache: 'stale' });
          if (!revalidating.has(target)) {
            revalidating.add(target);
            fetchTarget(target).then(f => {
              if (f.status === 200 && isJsonCt(f.contentType)) pcSet(target, { ...f, t: Date.now() });
            }).catch(() => {}).finally(() => revalidating.delete(target));
          }
          return;
        }
        pc.delete(target);
      }
      try {
        const f = await fetchTarget(target, req);
        if (!bypass && f.status === 200 && isJsonCt(f.contentType)) pcSet(target, { ...f, t: Date.now() });
        send(req, res, { ...f, cache: bypass ? (PROXY_CACHE ? 'nc' : undefined) : (hit ? 'refresh' : 'miss') });
      } catch (e) {
        if (hit) { send(req, res, { ...hit, cache: 'stale-err' }); return; } // 回源失败用旧缓存兜底
        fail(e);
      }
    };
    if (!PROXY_STREAM) { // 兜底开关：旧全缓冲、不分流
      if (bypass) { try { send(req, res, { ...(await fetchTarget(target, req)), cache: PROXY_CACHE ? 'nc' : undefined }); } catch (e) { fail(e); } return; }
      return jsonCacheFlow();
    }
    // 三路分流：类型要等响应头才知道，先取头；JSON→缓冲+SWR；m3u8→改写；其余→流式
    if (bypass) return jsonCacheFlow(); // bypass 时命中路径不变（缓存只存 JSON，直接回源判型）
    const hit = pcGet(target);
    if (hit && isJsonCt(hit.contentType)) return jsonCacheFlow();
    try {
      const r = await fetchUp(target, req);
      const ct = r.headers.get('content-type') || 'text/plain; charset=utf-8';
      if (isJsonCt(ct)) {
        const f = { status: r.status, contentType: ct, body: Buffer.from(await r.arrayBuffer()) };
        if (r.status === 200) pcSet(target, { ...f, t: Date.now() });
        send(req, res, { ...f, cache: 'miss' });
      } else if (isM3u8Ct(ct, target)) { // 以重定向后 finalUrl 为 base 改写分片/KEY/MAP；不入缓存、强制 no-store
        const text = await r.text();
        const out = rewriteM3u8 ? rewriteM3u8(text, r.url || target) : text;
        send(req, res, { status: r.status, contentType: ct, body: Buffer.from(out), extra: { 'cache-control': 'no-store' } });
      } else {
        await pipeStream(req, res, r);
      }
    } catch (e) {
      if (hit && isJsonCt(hit.contentType)) { send(req, res, { ...hit, cache: 'stale-err' }); return; }
      fail(e);
    }
    return;
  }

  const file = normalize(url.pathname === '/' ? 'index.html' : url.pathname.slice(1));
  if (file.includes('..')) { res.writeHead(403); return res.end(); }
  try {
    const data = await readFile(join(ROOT, file));
    const ct = MIME[extname(file)] || 'application/octet-stream';
    const m = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range || '');
    if (m && (m[1] || m[2])) { // Range 透传配套：静态也回 206，使 /proxy 流式媒体链路可端到端验证
      const total = data.length;
      const start = m[1] ? +m[1] : Math.max(0, total - (+m[2] || 0));
      const end = m[1] && m[2] ? Math.min(+m[2], total - 1) : total - 1;
      if (start >= total || start > end) { res.writeHead(416, { 'content-range': `bytes */${total}` }); return res.end(); }
      res.writeHead(206, { 'content-type': ct, 'content-range': `bytes ${start}-${end}/${total}`, 'accept-ranges': 'bytes', 'content-length': end - start + 1 });
      return res.end(req.method === 'HEAD' ? undefined : data.subarray(start, end + 1));
    }
    send(req, res, { contentType: ct, body: data }, { cors: false });
  } catch {
    res.writeHead(404); res.end('not found');
  }
});

server.listen(PORT, () => console.log(`LibreTVaz 调试台: http://localhost:${PORT}（${poolNote}，代理缓存 ${PROXY_CACHE ? '开' : '关'}，流式代理 ${PROXY_STREAM ? '开' : '关'}）`));
