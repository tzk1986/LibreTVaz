// A1 线路测速单测：perf-core 纯函数（lineSpeedTag/pickBestLine/pickNextLine speeds）
// + /proxy Range 采样端到端（本地 256KB 静态 fixture 测得 KB/s>0 且 206、挂起源判失败）
// 用法：node test/linespeed.test.mjs [port]（默认 8799，由 run.mjs 启动）
import { writeFile, unlink } from 'node:fs/promises';
import '../debug-web/perf-core.js';

const PORT = +(process.argv[2] || process.env.PROXY_PORT || 8799);
const BASE = `http://127.0.0.1:${PORT}`;
const P = globalThis.PerfCore;
let fails = 0;
const ok = (cond, msg) => { if (!cond) { fails++; console.log('FAIL linespeed.' + msg); } else console.log('PASS linespeed.' + msg); };
const eq = (a, b, msg) => ok(JSON.stringify(a) === JSON.stringify(b), `${msg} (got ${JSON.stringify(a)} want ${JSON.stringify(b)})`);

// ---- lineSpeedTag 阈值三档 + 假阴性 -1 ----
eq(P.lineSpeedTag(null), null, '未测=null');
eq(P.lineSpeedTag(-1), { label: '测速失败', cls: 'sp-dim' }, '负=采样失败但播放正常→测速失败不判死');
eq(P.lineSpeedTag(0), { label: '慢/不可用', cls: 'sp-bad' }, '0=失败 慢/不可用');
eq(P.lineSpeedTag(9000), { label: '8.8MB/s 流畅', cls: 'sp-ok' }, '9000KB/s→MB 显示流畅');
eq(P.lineSpeedTag(1500), { label: '1.5MB/s 流畅', cls: 'sp-ok' }, '1500 边界→流畅');
eq(P.lineSpeedTag(1499), { label: '1.5MB/s 可能卡顿', cls: 'sp-mid' }, '1499→可能卡顿（1499/1024 进位显示 1.5MB/s）');
eq(P.lineSpeedTag(702), { label: '702KB/s 可能卡顿', cls: 'sp-mid' }, '702KB/s→可能卡顿');
eq(P.lineSpeedTag(150), { label: '150KB/s 可能卡顿', cls: 'sp-mid' }, '150 边界→黄');
eq(P.lineSpeedTag(149), { label: '149KB/s 慢/不可用', cls: 'sp-bad' }, '149→红');

// ---- pickBestLine ----
const eps = [
  { flag: 'a', list: [{ name: '1', link: 'https://x.com/share/1' }] }, // 0 最快但 HTML 壳
  { flag: 'b', list: [{ name: '1', link: 'https://b.com/1.m3u8' }] },  // 1 中速流
  { flag: 'c', list: [{ name: '1', link: 'https://c.com/1.mp4' }] },   // 2 最快流
  { flag: 'd', list: [{ name: '1', link: 'https://d.com/1.mp4' }] },   // 3 慢
  { flag: 'e', list: [{ name: '1', link: 'https://e.com/1.mp4' }] },   // 4 失败
];
eq(P.pickBestLine(eps, [99999, 400, 3000, 100, 0], 0), 2, '取最快流后缀线，HTML 壳/慢/失败排除');
eq(P.pickBestLine(eps, [null, null, 200, null, null], 0), 2, '部分未测只从已测合格线取');
eq(P.pickBestLine(eps, [null, null, null, null, null], 0), 1, '全无测速→退回首个流后缀线');
eq(P.pickBestLine(eps, [99999, -1, 400, null, null], 0), 2, '播放中采样失败线(-1)与HTML壳不参与最快线');
eq(P.pickBestLine([{ flag: 's', list: [{ name: '1', link: 'https://s/1.html' }] }], [null], 0), 0, '无流后缀无线可退→0');

// ---- pickNextLine speeds：测得慢/失败线排最后，未测与正常线维持流后缀优先 ----
{
  const cand = P.pickNextLine(eps, { line: 0, curEp: 0, tried: new Set(), speeds: [5000, 0, 4000, 100, null] });
  eq(cand.map(x => x.i), [2, 4, 1, 3], '不可用线(1,3)沉底，流后缀快线(2)居首，未测(4)次之（同档稳定序）');
}
{
  const cand = P.pickNextLine(eps, { line: 0, curEp: 0, tried: new Set(), speeds: [5000, -1, 4000, 100, null] });
  eq(cand.map(x => x.i), [1, 2, 4, 3], '-1(播放中测速失败)按未知不沉底，仅低速/0 沉底');
}
{
  const cand = P.pickNextLine(eps, { line: 0, curEp: 0, tried: new Set() });
  eq(cand.map(x => x.i).slice(0, 1), [1], '不传 speeds 维持原行为（流后缀优先）');
}

// ---- /proxy Range 采样端到端（fongmi sampleKbps 同逻辑复刻） ----
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
  } catch (e) { if (!bytes) throw e; } finally { clearTimeout(timer); }
  if (!bytes) return 0;
  return bytes * 1000 / Math.max(1, Date.now() - t0) / 1024;
}
const px = u => `${BASE}/proxy?url=${encodeURIComponent(u)}`;
const FIX = new Uint8Array(262144);
try {
  await writeFile('debug-web/linespeed.fixture.bin', FIX);
  // Range 透传断言：206 + content-range
  {
    const r = await fetch(px(`${BASE}/linespeed.fixture.bin`), { headers: { Range: 'bytes=0-131071' } });
    ok(r.status === 206, 'fixture 经 /proxy Range 返回 206');
    ok(r.headers.get('content-range') === 'bytes 0-131071/262144', 'content-range 透传正确');
  }
  const k = await sampleKbps(px(`${BASE}/linespeed.fixture.bin`));
  ok(k > 0, `本地 fixture 采样 KB/s>0（实测 ${Math.round(k)}KB/s）`);
  let deadErr = false;
  try { await sampleKbps(px('http://127.0.0.1:1/dead.mp4')); } catch { deadErr = true; }
  ok(deadErr, '挂起源采样抛错→调用侧计 0 判不可用');
} finally {
  await unlink('debug-web/linespeed.fixture.bin').catch(() => {});
}

console.log(fails ? `linespeed: ${fails} 个失败` : 'linespeed: 全部通过');
// 用 exitCode 而非 process.exit：fetch 连接池句柄未关时强制退出在 Windows 触发 libuv 断言崩溃（假失败）
process.exitCode = fails ? 1 : 0;
