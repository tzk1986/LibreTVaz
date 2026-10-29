// 本地调试代理 + 静态服务器：node debug-web/server.mjs → http://localhost:8787
// /proxy?url=<编码后的地址> 转发 GET，补 CORS 头，供页面直连各 CMS 源/直播源
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { networkInterfaces } from 'node:os';

const ROOT = fileURLToPath(new URL('.', import.meta.url));
const PORT = process.env.PORT || 8787;
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css', '.json': 'application/json' };

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
    try {
      const r = await fetch(target, {
        redirect: 'follow',
        signal: AbortSignal.timeout(15000),
        headers: { 'User-Agent': 'Mozilla/5.0 (LibreTVaz-debug)' },
      });
      const body = Buffer.from(await r.arrayBuffer());
      res.writeHead(r.status, {
        'content-type': r.headers.get('content-type') || 'text/plain; charset=utf-8',
        'access-control-allow-origin': '*',
      });
      res.end(body);
    } catch (e) {
      res.writeHead(502, { 'access-control-allow-origin': '*', 'content-type': 'text/plain; charset=utf-8' });
      res.end('proxy error: ' + (e.message || e));
    }
    return;
  }

  const file = normalize(url.pathname === '/' ? 'index.html' : url.pathname.slice(1));
  if (file.includes('..')) { res.writeHead(403); return res.end(); }
  try {
    const data = await readFile(join(ROOT, file));
    res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(data);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});

server.listen(PORT, () => console.log(`LibreTVaz 调试台: http://localhost:${PORT}`));
