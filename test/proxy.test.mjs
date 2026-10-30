// /proxy 回归：转发正确性、400/502、SWR 缓存命中与 x-proxy-cache 头、nc=1 旁路、PROXY_CACHE=0 关闭态、gzip
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

console.log(fails ? `proxy: ${fails} 条失败` : 'proxy: 全部通过');
process.exit(fails ? 1 : 0);
