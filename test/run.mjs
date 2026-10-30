// 测试总入口：node test/run.mjs [--record-baseline] [--smoke]
// 起 PORT=8799 调试服务器子进程 → 依次跑 cache-inval / proxy / search-perf → 汇总 PASS/FAIL，退出码非 0 即失败
// --record-baseline：以当前代码实测覆盖 test/baseline.json（改代码前跑一次做优化前基线）
// --smoke：附加跑 inspect-source.mjs 单源冒烟（外网慢，默认跳过）
import { spawn } from 'node:child_process';
import { once } from 'node:events';

const PORT = 8799;
const args = process.argv.slice(2);

const server = spawn(process.execPath, ['debug-web/server.mjs'], { env: { ...process.env, PORT: String(PORT) }, stdio: ['ignore', 'pipe', 'pipe'] });
let serverLog = '';
server.stdout.on('data', d => { serverLog += d; });
server.stderr.on('data', d => { serverLog += d; });

const waitUp = async () => {
  for (let i = 0; i < 60; i++) {
    try { const r = await fetch(`http://127.0.0.1:${PORT}/api/remote/base`, { signal: AbortSignal.timeout(500) }); if (r.ok) return true; } catch { /* retry */ }
    await new Promise(r => setTimeout(r, 150));
  }
  return false;
};

if (!await waitUp()) {
  console.log('FAIL: 测试服务器 8799 启动失败\n' + serverLog);
  server.kill();
  process.exit(1);
}

// 冒烟源：与 fongmi.js BUILTIN_SOURCES 第一个一致（非凡，实测 ac=list/videolist/搜索全通）
const firstCmsApi = () => 'https://cj.ffzyapi.com/api.php/provide/vod';

const jobs = [
  ['cache-inval', ['test/cache-inval.test.mjs'], {}],
  ['proxy', ['test/proxy.test.mjs', String(PORT)], {}],
  ['remote', ['test/remote.test.mjs', String(PORT)], {}],
];
if (args.includes('--record-baseline')) jobs.push(['search-perf(record)', ['test/search-perf.test.mjs', '--record', String(PORT)], {}]);
else jobs.push(['search-perf', ['test/search-perf.test.mjs', String(PORT)], {}]);
if (args.includes('--smoke')) {
  const one = await firstCmsApi();
  if (one) jobs.push(['inspect-smoke', ['debug-web/inspect-source.mjs', one, '12000'], {}]);
}

let failed = 0;
const results = [];
for (const [name, argv] of jobs) {
  const t = Date.now();
  const child = spawn(process.execPath, argv, { env: { ...process.env, PROXY_PORT: String(PORT) } });
  child.stdout.pipe(process.stdout);
  child.stderr.pipe(process.stderr);
  const [code] = await once(child, 'exit');
  results.push(`${name}: ${code === 0 ? 'PASS' : 'FAIL'} (${((Date.now() - t) / 1000).toFixed(1)}s)`);
  if (code !== 0) failed++;
}

server.kill();
console.log('\n===== 汇总 =====');
results.forEach(r => console.log(r));
console.log(failed ? `${failed} 个测试套失败` : '全部通过');
process.exit(failed ? 1 : 0);
