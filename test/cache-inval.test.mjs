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

// 6. rewriteM3u8：相对/绝对分片、KEY/MAP URI、防双改写、注释空行保留（方案④）
{
  const base = 'https://vod.example.com/a/b/index.m3u8';
  const px = u => '/proxy?url=' + encodeURIComponent(u);
  const src = [
    '#EXTM3U',
    '#EXT-X-KEY:METHOD=AES-128,URI="https://k.example.com/e.bin"',
    '#EXT-X-KEY:METHOD=AES-128,URI="/proxy?url=already"',
    '#EXT-X-MAP:URI="init.mp4"',
    '#EXT-X-PROGRAM-DATE-TIME:2020-01-01T00:00:00Z',
    '# 注释行',
    '',
    '#EXTINF:10.0,',
    'seg1.ts',
    'https://abs.example.com/seg2.ts?x=1',
    '/rel/seg3.ts',
  ].join('\n');
  const L = P.rewriteM3u8(src, base).split('\n');
  ok(L[0] === '#EXTM3U', 'm3u8: 头行保留');
  ok(L[1].includes(`URI="${px('https://k.example.com/e.bin')}"`), 'm3u8: KEY 绝对 URI 包代理');
  ok(L[2].includes('URI="/proxy?url=already"'), 'm3u8: 已代理 KEY 不双改写');
  ok(L[3].includes(encodeURIComponent('https://vod.example.com/a/b/init.mp4')), 'm3u8: MAP 相对 URI 按 base 解析');
  ok(L[4] === '#EXT-X-PROGRAM-DATE-TIME:2020-01-01T00:00:00Z', 'm3u8: 其它标签不动');
  ok(L[5] === '# 注释行' && L[6] === '', 'm3u8: 注释与空行保留');
  ok(L[8] === px('https://vod.example.com/a/b/seg1.ts'), 'm3u8: 相对分片改写');
  ok(L[9] === px('https://abs.example.com/seg2.ts?x=1'), 'm3u8: 绝对分片改写');
  ok(L[10] === px('https://vod.example.com/rel/seg3.ts'), 'm3u8: 根相对分片按主机根解析');
  const crlf = P.rewriteM3u8('#EXTM3U\r\nseg.ts\r\n', base);
  ok(crlf.includes('seg') && crlf.includes('\r\n'), 'm3u8: CRLF 保留');
}

// 7. createPinIndex：qyn 命中、多音字双读、权重排序/淘汰/还原、桶查询性能（方案③.5/④）
{
  const ix = P.createPinIndex();
  ok(ix.ok, 'pin: Intl 拼音 Collator 可用');
  ix.addMany(['庆余年', '长白串', '白雪公主', '欢乐颂']);
  eq(ix.suggest('qyn'), ['庆余年'], 'pin: qyn→庆余年');
  ok(ix.suggest('cb').includes('长白串') && ix.suggest('zb').includes('长白串'), 'pin: 多音字「长」cháng/zhǎng 双读命中');
  eq(ix.suggest('bx'), ['白雪公主'], 'pin: bx→白雪公主');
  eq(ix.suggest(''), [], 'pin: 空查询返回空');
  eq(ix.suggest('12a'), [], 'pin: 非纯字母查询返回空(退化由调用方处理)');
  ix.add('庆余年'); // 重复 add 应幂等
  eq(ix.size(), 4, 'pin: 幂等后规模不变');
  const ix2 = P.createPinIndex();
  ix2.addMany(['阿飞', '阿花']);
  ix2.bump('阿花', 5);
  eq(ix2.top(2), ['阿花', '阿飞'], 'pin: top() 按权重降序');
  eq(ix2.suggest('a'), ['阿花', '阿飞'], 'pin: suggest() 按权重排序');
  const ix3 = P.createPinIndex({ max: 3 });
  ix3.addMany(['甲乙', '丙丁']);
  ix3.bump('甲乙', 10);
  ix3.addMany(['戊己', '庚辛', '壬癸']);
  eq(ix3.size(), 3, 'pin: 超上限触发淘汰');
  ok(ix3.list().includes('甲乙'), 'pin: 高频词不被新词挤掉（权重淘汰）');
  ok(!ix3.list().includes('丙丁'), 'pin: 低频最旧优先淘汰');
  const ix4 = P.createPinIndex({ weights: { 阿花: 9 } });
  ix4.addMany(['阿飞', '阿花']);
  eq(ix4.top(1), ['阿花'], 'pin: 构造传入持久化权重即还原排序');
  const ix5 = P.createPinIndex();
  const AL = 'abcdefghijklmnopqrstuvwxyz';
  const names = Array.from({ length: 2000 }, (_, i) => '测试影片' + AL[i % 26] + i);
  const t0 = performance.now(); ix5.addMany(names); const addMs = performance.now() - t0;
  let q = 0;
  const t1 = performance.now(); for (let k = 0; k < 100; k++) q += ix5.suggest('csypz').length; const qMs = (performance.now() - t1) / 100;
  ok(addMs < 500, `pin: 2000 词建索引 ${addMs.toFixed(1)}ms < 500ms`);
  ok(qMs < 5, `pin: 前缀桶查询均 ${qMs.toFixed(2)}ms < 5ms（旧全量重算约 100-300ms）`);
  ok(q > 0, 'pin: 桶查询有命中');
}

// 8. commonPrefix：系列片名联想截断核心词
{
  eq(P.commonPrefix(['庆余年第一季 特别版', '庆余年 第二季', '庆余年，全世界都以为我是废物太子']), '庆余年', 'prefix: 庆余年系列截断');
  eq(P.commonPrefix(['独行月球']), '独行月球', 'prefix: 单词即自身');
  eq(P.commonPrefix(['甲方', '乙方']), '', 'prefix: 无公共前缀为空');
  ok(P.commonPrefix([]) === '' && P.commonPrefix(null) === '', 'prefix: 空输入安全');
}

console.log(fails ? `cache-inval: ${fails} 条失败` : 'cache-inval: 全部通过');
process.exit(fails ? 1 : 0);
