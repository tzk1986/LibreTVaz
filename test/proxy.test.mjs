// /proxy 回归：转发正确性、400/502、SWR 缓存命中与 x-proxy-cache 头、nc=1 旁路、PROXY_CACHE=0 关闭态、gzip、流式三路分流（Range206/图片头/m3u8改写）、env 代理启动
// 用法：node test/proxy.test.mjs [port]（默认 8799，由 run.mjs 启动；关闭态用例自起 8798 子服务）
import { spawn } from 'node:child_process';
import { request } from 'node:http';

const PORT = +(process.argv[2] || process.env.PROXY_PORT || 8799);
const BASE = `http://127.0.0.1:${PORT}`;
let fails = 0;
const ok = (c, name, extra = '') => { console.log(`${c ? 'PASS' : 'FAIL'} proxy.${name}${extra ? ' — ' + extra : ''}`); if (!c) fails++; };

function rawGet(path, headers = {}, port = PORT) {
  return new Promise((resolve, reject) => {
    const req = request({ method: 'GET', path, port, headers }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
    });
    req.on('error', reject);
    req.end();
  });
}
const proxyPath = target => `/proxy?url=${encodeURIComponent(target)}`;

// 用服务器自身的 JSON 端点做被测目标：确定性强、不依赖外网
const selfJson = proxyPath(`${BASE}/api/remote/base`);

// 1. 转发正确性
{
  const r = await rawGet(selfJson);
  let j = null;
  try { j = JSON.parse(r.body.toString()); } catch { /* noop */ }
  ok(r.status === 200 && j && 'host' in j, 'forward', `status=${r.status}`);
  ok(r.headers['access-control-allow-origin'] === '*', 'cors-header');
}

// 2. 非法 url → 400
{
  const r = await rawGet('/proxy?url=javascript:alert(1)');
  ok(r.status === 400, 'bad-url-400', `status=${r.status}`);
}

// 3. 目标不可达 → 502（不是崩溃）
{
  const r = await rawGet(proxyPath('http://127.0.0.1:1/x'));
  ok(r.status === 502, 'unreachable-502', `status=${r.status}`);
}

// 4. 缓存：首次 miss，二次应命中（hit/stale）且内容一致、更快
{
  const uniq = proxyPath(`${BASE}/api/remote/base?id=t${Date.now()}`);
  const t0 = Date.now();
  const a = await rawGet(uniq);
  const msA = Date.now() - t0;
  const cacheA = a.headers['x-proxy-cache'];
  const t1 = Date.now();
  const b = await rawGet(uniq);
  const msB = Date.now() - t1;
  ok(cacheA === 'miss', 'cache-first-miss', `got ${cacheA}`);
  ok(['hit', 'stale'].includes(b.headers['x-proxy-cache']), 'cache-second-hit', `got ${b.headers['x-proxy-cache']}`);
  ok(a.body.toString() === b.body.toString(), 'cache-content-same');
  ok(msB <= Math.max(msA, 50), 'cache-second-faster', `${msA}ms → ${msB}ms`);
}

// 5. nc=1 旁路：不读不写缓存
{
  const u = proxyPath(`${BASE}/api/remote/base`);
  await rawGet(u); // 先确保已有缓存
  const r = await rawGet(u + '&nc=1');
  ok(r.headers['x-proxy-cache'] === 'nc', 'nc-bypass', `got ${r.headers['x-proxy-cache']}`);
  const r2 = await rawGet(u); // 旁路请求不应污染
  ok(r2.headers['x-proxy-cache'] !== 'miss', 'nc-no-pollute', `got ${r2.headers['x-proxy-cache']}`);
}

// 6. gzip：大于阈值的 JSON/JS 响应带 content-encoding: gzip
{
  const r = await rawGet('/fongmi.js', { 'accept-encoding': 'gzip' });
  ok(r.headers['content-encoding'] === 'gzip' && r.body.length > 1000, 'static-gzip', `enc=${r.headers['content-encoding']} len=${r.body.length}`);
  const big = proxyPath(`${BASE}/fongmi.js`); // 代理 js 也压缩（非 JSON 但文本大；若仅缓存 JSON，gzip 仍应生效）
  const p = await rawGet(big, { 'accept-encoding': 'gzip' });
  ok(p.status === 200, 'proxy-big-text-ok', `enc=${p.headers['content-encoding']}`);
}

// 7. PROXY_CACHE=0 关闭态：无缓存头、二次不命中（自起子服务）
{
  const child = spawn(process.execPath, ['debug-web/server.mjs'], { env: { ...process.env, PORT: '8798', PROXY_CACHE: '0' }, stdio: 'ignore' });
  let up = false;
  for (let i = 0; i < 40 && !up; i++) { await new Promise(r => setTimeout(r, 100)); try { await rawGet('/api/remote/base', {}, 8798); up = true; } catch { /* 还没起 */ } }
  if (!up) ok(false, 'disabled-server-start');
  else {
    const u = proxyPath('http://127.0.0.1:8798/api/remote/base');
    const r1 = await rawGet(u, {}, 8798);
    const r2 = await rawGet(u, {}, 8798);
    ok(!r1.headers['x-proxy-cache'] || r1.headers['x-proxy-cache'] === 'off', 'cache-disabled-nohit', `got ${r1.headers['x-proxy-cache']}`);
    ok(!r2.headers['x-proxy-cache'] || !['hit', 'stale'].includes(r2.headers['x-proxy-cache']), 'cache-disabled-second-nohit', `got ${r2.headers['x-proxy-cache']}`);
  }
  child.kill();
}

// 8. 流式三路分流（方案②/④）：内置 mini-origin（8797）验证 Range 206 / 图片 30d 头 / m3u8 改写 / 非 JSON 不进缓存
{
  const { createServer } = await import('node:http');
  const origin = createServer((rq, rs) => {
    if (rq.url.startsWith('/img.png')) { rs.writeHead(200, { 'content-type': 'image/png' }); return rs.end(Buffer.alloc(2048, 7)); }
    if (rq.url.startsWith('/a.m3u8')) {
      rs.writeHead(200, { 'content-type': 'application/vnd.apple.mpegurl' });
      return rs.end('#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="k.bin"\n#EXTINF:10,\nseg1.ts\n');
    }
    if (rq.url.startsWith('/media')) {
      const total = 4096, buf = Buffer.alloc(total, 9);
      const m = /^bytes=(\d*)-(\d*)$/.exec(rq.headers.range || '');
      if (m) {
        const s = +m[1], e = Math.min(+m[2], total - 1);
        rs.writeHead(206, { 'content-type': 'video/mp2t', 'content-range': `bytes ${s}-${e}/${total}`, 'content-length': e - s + 1 });
        return rs.end(buf.subarray(s, e + 1));
      }
      rs.writeHead(200, { 'content-type': 'video/mp2t', 'content-length': total });
      return rs.end(buf);
    }
    rs.writeHead(404); rs.end();
  });
  await new Promise(r => origin.listen(8797, '127.0.0.1', r));
  const ORIGIN = 'http://127.0.0.1:8797';
  try {
    const r1 = await rawGet(proxyPath(`${ORIGIN}/media`), { range: 'bytes=0-99' });
    ok(r1.status === 206 && r1.body.length === 100 && /bytes 0-99\/4096/.test(r1.headers['content-range'] || ''), 'stream-range-206', `status=${r1.status} len=${r1.body.length}`);
    const im = await rawGet(proxyPath(`${ORIGIN}/img.png`));
    ok(im.headers['content-type'] === 'image/png' && /max-age=2592000/.test(im.headers['cache-control'] || ''), 'img-cache-30d', `cc=${im.headers['cache-control']}`);
    ok(!['hit', 'miss', 'stale', 'refresh'].includes(im.headers['x-proxy-cache']), 'non-json-not-cached', `got ${im.headers['x-proxy-cache']}`);
    const m3 = await rawGet(proxyPath(`${ORIGIN}/a.m3u8`));
    const body3 = m3.body.toString();
    ok(body3.includes('/proxy?url=' + encodeURIComponent(`${ORIGIN}/seg1.ts`)), 'm3u8-seg-rewritten');
    ok(body3.includes('/proxy?url=' + encodeURIComponent(`${ORIGIN}/k.bin`)), 'm3u8-key-rewritten');
    ok(/no-store/.test(m3.headers['cache-control'] || ''), 'm3u8-no-store', `cc=${m3.headers['cache-control']}`);
  } finally { origin.close(); }
}

// 9. env 代理启动态：带 HTTPS_PROXY+NO_PROXY 起 8796 子服务，本机目标仍直连（不崩、可用）
{
  const child = spawn(process.execPath, ['debug-web/server.mjs'], { env: { ...process.env, PORT: '8796', HTTPS_PROXY: 'http://127.0.0.1:7890', NO_PROXY: 'localhost,127.0.0.1' }, stdio: 'ignore' });
  let up = false;
  for (let i = 0; i < 40 && !up; i++) { await new Promise(r => setTimeout(r, 100)); try { await rawGet('/api/remote/base', {}, 8796); up = true; } catch { /* 还没起 */ } }
  if (!up) ok(false, 'env-proxy-server-start');
  else {
    const r = await rawGet(proxyPath('http://127.0.0.1:8796/api/remote/base'), {}, 8796);
    ok(r.status === 200, 'env-proxy-localhost-bypass', `status=${r.status}`);
  }
  child.kill();
}

// 10. 外网图床冒烟（可选，SKIP 不算失败）：TEST_OSS_IMG=1 时经 /proxy 拉 fffgood 图应 200（上游代理链路证据）
{
  if (process.env.TEST_OSS_IMG !== '1') console.log('SKIP proxy.oss-img（设 TEST_OSS_IMG=1 开启）');
  else {
    const r = await rawGet(proxyPath('https://p.fffgood.com/upload/vod/20240101-1/x.jpg'));
    ok(r.status === 200 || r.status === 404, 'oss-img-via-proxy', `status=${r.status}`); // 404 说明 TLS/链路通、仅图没了
  }
}

console.log(fails ? `proxy: ${fails} 条失败` : 'proxy: 全部通过');
process.exit(fails ? 1 : 0);
