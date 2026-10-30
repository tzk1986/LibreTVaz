// 搜索性能基线：经 /proxy 用旧逻辑（8 源无上限并发、12s 超时、源序 Tab）搜「庆余年」
// 记录 firstTab（首个有结果 Tab 上屏时间）与 allDone，写/比对 test/baseline.json（--record 覆盖）
// 用法：node test/search-perf.test.mjs [--record] [port]
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const BASELINE = join(HERE, 'baseline.json');
const record = process.argv.includes('--record');
const PORT = +(process.argv.find(a => /^\d{4,5}$/.test(a)) || process.env.PROXY_PORT || 8799);
const BASE = `http://127.0.0.1:${PORT}`;
const KW = '庆余年';
const TOP = 8, TIMEOUT = 12000;
// 与 fongmi.js BUILTIN_SOURCES 保持一致（改内置源时同步这里）
const BUILTIN = [
  { name: '非凡资源', url: 'https://cj.ffzyapi.com/api.php/provide/vod' },
  { name: '量子资源', url: 'https://cj.lziapi.com/api.php/provide/vod' },
  { name: '360采集', url: 'https://360zy.com/api.php/provide/vod' },
  { name: '索尼采集', url: 'https://suoniapi.com/api.php/provide/vod' },
  { name: '天天采集', url: 'http://caiji.dyttzyapi.com/api.php/provide/vod' },
  { name: '茅台采集', url: 'https://mtzy.me/api.php/provide/vod' },
  { name: '火狐采集', url: 'https://hhzyapi.com/api.php/provide/vod' },
  { name: '无尽资源', url: 'https://api.wujinapi.me/api.php/provide/vod?ac=list' },
];

const pGet = async (u, ms = TIMEOUT) => {
  const r = await fetch(`${BASE}/proxy?url=${encodeURIComponent(u)}`, { signal: AbortSignal.timeout(ms), headers: { 'User-Agent': 'Mozilla/5.0 (LibreTVaz-test)' } });
  if (!r.ok) throw new Error('HTTP ' + r.status);
  return r.text();
};
const norm = s => String(s || '').trim().replace(/[?&]+$/, '').replace(/\/+$/, '');
const apiOf = (base, q) => { const b = norm(base); return b + (b.includes('?') ? '&' : '?') + q; };
const stripAc = u => norm(u).replace(/([?&])ac=[^&#]*/gi, '$1').replace(/[?&]+$/, '');

const srcs = BUILTIN.slice(0, TOP);
if (srcs.length < 3) { console.log('FAIL search-perf: 可用源不足，跳过基线'); process.exit(1); }
console.log(`search-perf: 被测源 ${srcs.length} 个，关键词「${KW}」（旧逻辑：无上限并发+源序）`);

const t0 = Date.now();
let firstTab = null;
const per = await Promise.all(srcs.map(async s => {
  const st = Date.now();
  try {
    const j = JSON.parse(await pGet(apiOf(stripAc(s.url), `ac=videolist&wd=${encodeURIComponent(KW)}&pg=1`)));
    const hits = (j.list || []).filter(v => String(v.vod_name || '').includes(KW)).length;
    const ms = Date.now() - st;
    if (hits && firstTab === null) firstTab = Date.now() - t0;
    return { name: s.name, ms, hits, err: null };
  } catch (e) {
    return { name: s.name, ms: Date.now() - st, hits: 0, err: String(e.message).slice(0, 40) };
  }
}));
const allDone = Date.now() - t0;
for (const p of per) console.log(`  · ${p.name}: ${p.ms}ms ${p.err ? '✗ ' + p.err : `命中 ${p.hits}`}`);
console.log(`  firstTab=${firstTab ?? '>12000'}ms allDone=${allDone}ms`);

const cur = { recordedAt: new Date().toISOString(), kw: KW, top: TOP, firstTab, allDone, per };
if (record || !(await readFile(BASELINE, 'utf8').catch(() => null))) {
  await writeFile(BASELINE, JSON.stringify(cur, null, 2));
  console.log(`已${record ? '更新' : '首次写入'}基线 test/baseline.json`);
  process.exit(0);
}

const base = JSON.parse(await readFile(BASELINE, 'utf8'));
let fails = 0;
const cmp = (name, now, before, tol = 1.3) => {
  if (before == null || now == null) { console.log(`SKIP ${name}（基线或当前无值）`); return; }
  const c = now <= before * tol;
  console.log(`${c ? 'PASS' : 'FAIL'} perf.${name}: 当前 ${now}ms vs 基线 ${before}ms（阈值 +30%）`);
  if (!c) fails++;
};
cmp('firstTab', cur.firstTab, base.firstTab);
cmp('allDone', cur.allDone, base.allDone);
console.log(fails ? `search-perf: ${fails} 项劣化` : 'search-perf: 无劣化');
process.exit(fails ? 1 : 0);
