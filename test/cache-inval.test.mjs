// perf-core 纯函数单元断言：去重、源排序稳定性、分批、搜索 SWR 分片、epoch 丢弃
import '../debug-web/perf-core.js';
const P = globalThis.PerfCore;

let fails = 0;
const ok = (cond, name) => { console.log(`${cond ? 'PASS' : 'FAIL'} ${name}`); if (!cond) fails++; };
const eq = (a, b, name) => ok(JSON.stringify(a) === JSON.stringify(b), `${name} (got ${JSON.stringify(a)})`);

// 1. createDedupe：同 key 并发只执行一次，不同 key 互不影响
{
  let calls = 0;
  const d = P.createDedupe(async key => { calls++; await new Promise(r => setTimeout(r, 20)); return key.toUpperCase(); });
  const [a, b, c] = await Promise.all([d('x'), d('x'), d('y')]);
  ok(calls === 2, 'dedupe: 同 key 合并为一次调用');
  eq([a, b, c], ['X', 'X', 'Y'], 'dedupe: 返回值正确');
  await d('x');
  ok(calls === 3, 'dedupe: 完成后清槽，下次重新执行');
  // 失败也要清槽，不能卡死
  const d2 = P.createDedupe(async () => { throw new Error('boom'); });
  await d2('z').catch(() => {});
  let again = false;
  await d2('z').catch(() => { again = true; });
  ok(again, 'dedupe: 失败后不残留 promise');
}

// 2. orderSources：当前源置顶 → ms 升序 → 未测(null)按原序；稳定
{
  const items = [{ si: 0 }, { si: 1 }, { si: 2 }, { si: 3 }];
  const ms = { 3: 100, 1: 50, 0: null, 2: null };
  const out = P.orderSources(items, x => ms[x.si], -1);
  eq(out.map(x => x.si), [1, 3, 0, 2], 'order: ms 升序，未测保持源序');
  const out2 = P.orderSources(items, x => ms[x.si], 2);
  eq(out2.map(x => x.si), [2, 1, 3, 0], 'order: 当前源最优先');
  const out3 = P.orderSources(items, () => null, -1);
  eq(out3.map(x => x.si), [0, 1, 2, 3], 'order: 全无健康数据=原序（回退保底）');
}

// 3. splitBatches
{
  const [a, b] = P.splitBatches([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 8);
  ok(a.length === 8 && b.length === 2, 'batch: top8 首批 + 其余次批');
  const [c, d] = P.splitBatches([1], 8);
  ok(c.length === 1 && d.length === 0, 'batch: 不足 top 时次批为空');
}

// 4. searchKey 归一化 + splitTtl
{
  ok(P.searchKey(' 庆余年 A ') === 'search:庆余年 a', 'searchKey: trim+小写归一');
  const now = 1000000;
  const shards = {
    'http://a': { t: now - 1000, d: [{ n: 1 }] },
    'http://b': { t: now - P.SEARCH_TTL - 1, d: [{ n: 2 }] },
    'http://c': { t: now, d: 'not-array' },
  };
  const { fresh, stale } = P.splitTtl(shards, P.SEARCH_TTL, now);
  eq(Object.keys(fresh), ['http://a'], 'splitTtl: TTL 内为 fresh');
  eq(Object.keys(stale), ['http://b'], 'splitTtl: 超 TTL 进 stale（仍可先显）');
  ok(!('http://c' in fresh) && !('http://c' in stale), 'splitTtl: 损坏分片跳过');
}

// 5. epoch：bump 后旧 token 全部判过期
{
  const e = P.createEpoch();
  const t1 = e.bump();
  ok(!e.stale(t1), 'epoch: 当前 token 有效');
  const t2 = e.bump();
  ok(e.stale(t1) && !e.stale(t2), 'epoch: 换词后旧回调丢弃');
}

console.log(fails ? `cache-inval: ${fails} 条失败` : 'cache-inval: 全部通过');
process.exit(fails ? 1 : 0);
