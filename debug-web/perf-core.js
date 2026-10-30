// 性能相关纯函数库：浏览器经 <script src="perf-core.js"> 挂 globalThis.PerfCore；node 测试直接 import（副作用同样写 globalThis）
// 对应方案 docs/2026-10-30-搜索与加载提速方案.md ①②；安卓端移植时以本文件为规则对照
(function (g) {
  'use strict';

  const norm = u => String(u || '').trim().replace(/\/+$/, '');

  // 同 key 并发请求合并为一个 promise，完成后自动清槽
  function createDedupe(invoke) {
    const inflight = new Map();
    return (key, ...args) => {
      const hit = inflight.get(key);
      if (hit) return hit;
      const p = Promise.resolve().then(() => invoke(key, ...args))
        .finally(() => { if (inflight.get(key) === p) inflight.delete(key); });
      inflight.set(key, p);
      return p;
    };
  }

  // 搜索源顺序：当前源 → health ms 升序 → 未测(ms=null)按原源序；排序稳定
  function orderSources(items, msOf, currentSi) {
    return items
      .map((it, i) => ({ it, i, ms: msOf(it) }))
      .sort((a, b) => {
        const ca = a.it.si === currentSi ? 0 : 1, cb = b.it.si === currentSi ? 0 : 1;
        if (ca !== cb) return ca - cb;
        const ma = a.ms == null ? Infinity : a.ms, mb = b.ms == null ? Infinity : b.ms;
        if (ma !== mb) return ma - mb;
        return a.i - b.i;
      })
      .map(x => x.it);
  }

  const splitBatches = (list, top = 8) => [list.slice(0, top), list.slice(top)];

  const searchKey = kw => 'search:' + String(kw).trim().toLowerCase();
  const SEARCH_TTL = 10 * 60 * 1000;

  // 搜索 SWR 分片：存 {url: {t, d:[items]}}，按 TTL 拆 fresh/stale（两桶均保留原条目供立即渲染，仅 d 数组）
  function splitTtl(shards, ttl, now = Date.now()) {
    const fresh = {}, stale = {};
    for (const [u, v] of Object.entries(shards || {})) {
      if (!v || !Array.isArray(v.d)) continue;
      (now - (v.t || 0) <= ttl ? fresh : stale)[u] = v.d;
    }
    return { fresh, stale };
  }

  // 换词/重进取消旧回调：bump 后旧 token 视为过期（对齐 FongMi ViewModelSearchRunner epoch）
  function createEpoch() {
    let e = 0;
    return { bump: () => ++e, cur: () => e, stale: t => t !== e };
  }

  const PerfCore = { norm, createDedupe, orderSources, splitBatches, searchKey, SEARCH_TTL, splitTtl, createEpoch };
  g.PerfCore = PerfCore;
})(typeof globalThis !== 'undefined' ? globalThis : window);
