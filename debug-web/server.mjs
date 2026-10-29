// 本地调试代理 + 静态服务器：node debug-web/server.mjs → http://localhost:8787
// /proxy?url=<编码后的地址> 转发 GET，补 CORS 头，供页面直连各 CMS 源/直播源
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('.', import.meta.url));
const PORT = process.env.PORT || 8787;
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css', '.json': 'application/json' };

const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');

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
