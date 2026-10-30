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

  /* 拼音首字母索引（docs/2026-10-30-图片播放与拼音候选提速方案.md ③.5）：
     每词一次算首字母（ICU pinyin Collator + 22 声母锚点），多音字生成 ≤4 个变体；
     前 2 字母分桶：≥2 字母查询只扫小桶；词频权重排序与淘汰；纯字符串比较，击键 <5ms */
  const PIN_ANCHORS = [['A', '阿'], ['B', '八'], ['C', '嚓'], ['D', '多'], ['E', '讹'], ['F', '发'], ['G', '噶'], ['H', '哈'], ['J', '击'], ['K', '喀'], ['L', '垃'], ['M', '妈'], ['N', '拿'], ['O', '哦'], ['P', '啪'], ['Q', '期'], ['R', '然'], ['S', '撒'], ['T', '塌'], ['X', '昔'], ['Y', '压'], ['Z', '匝']];
  // 多音字全部读音首字母（与默认重合的自动跳过；zh/ch/sh 归入单字母 z/c/s）
  const POLY_INITS = { 长: ['c', 'z'], 重: ['c', 'z'], 乐: ['l', 'y'], 行: ['x', 'h'], 朝: ['c', 'z'], 传: ['c', 'z'], 单: ['d', 's'], 解: ['j', 'x'], 调: ['d', 't'], 藏: ['c', 'z'], 参: ['c', 's'], 率: ['l', 's'], 差: ['c', 'z'], 系: ['x', 'j'], 曾: ['c', 'z'], 屏: ['p', 'b'], 弹: ['d', 't'] };

  function createPinIndex({ max = 2000, weights = {} } = {}) {
    let coll = null;
    try { coll = new Intl.Collator('zh-Hans-CN-u-co-pinyin'); } catch { /* 无 ICU：py 为空，suggest 返回空由调用方退化子串匹配 */ }
    const iniCache = {};
    function initialOf(ch) {
      if (/[0-9a-z]/i.test(ch)) return ch.toLowerCase();
      if (coll && /[㐀-䶿一-鿿]/.test(ch)) {
        if (iniCache[ch]) return iniCache[ch];
        let res = '';
        for (const [init, anchor] of PIN_ANCHORS) { if (coll.compare(ch, anchor) >= 0) res = init.toLowerCase(); else break; }
        iniCache[ch] = res;
        return res;
      }
      return '';
    }
    function variantsOf(word) {
      const chars = Array.from(word);
      const base = chars.map(initialOf).join('');
      const out = [base];
      for (let i = 0; i < chars.length && out.length < 4; i++) {
        for (const a of POLY_INITS[chars[i]] || []) {
          if (a === base[i]) continue;
          const v = base.slice(0, i) + a + base.slice(i + 1);
          if (!out.includes(v)) out.push(v);
          if (out.length >= 4) break;
        }
      }
      return out;
    }
    let words = [];
    const py = new Map();      // word → 变体数组
    const seq = new Map();     // word → 加入序（淘汰时同权重保新）
    const wf = new Map();      // word → 词频权重
    const buckets = new Map(); // 前2字母 → Set(word)
    let order = 0;
    for (const [w, n] of Object.entries(weights || {})) if (n) wf.set(w, +n || 0);
    const bucketAdd = (w, v) => { const k = v.slice(0, 2) || '_'; let b = buckets.get(k); if (!b) buckets.set(k, b = new Set()); b.add(w); };
    function evict() {
      if (words.length <= max) return;
      const sorted = [...words].sort((a, b) => ((wf.get(a) || 0) - (wf.get(b) || 0)) || (seq.get(a) - seq.get(b)));
      const remove = new Set(sorted.slice(0, words.length - max));
      words = words.filter(w => !remove.has(w));
      for (const w of remove) {
        for (const v of py.get(w) || []) { const b = buckets.get(v.slice(0, 2) || '_'); if (b) b.delete(w); }
        py.delete(w); seq.delete(w); wf.delete(w);
      }
    }
    function add(word) {
      word = String(word || '').trim();
      if (!word || py.has(word)) return false;
      words.push(word); seq.set(word, ++order);
      const vs = variantsOf(word); py.set(word, vs);
      for (const v of vs) bucketAdd(word, v);
      evict();
      return true;
    }
    const rank = (a, b) => ((wf.get(b) || 0) - (wf.get(a) || 0)) || (seq.get(b) - seq.get(a));
    return {
      ok: !!coll,
      add, addMany: list => { for (const w of list || []) add(w); },
      bump(word, d = 1) { word = String(word || '').trim(); if (word) wf.set(word, (wf.get(word) || 0) + d); },
      // 首字母前缀联想：≥2 字母只扫前缀桶；按权重降序、同权重新词优先，上限 limit
      suggest(q, limit = 30) {
        q = String(q || '').toLowerCase().trim();
        if (!q || !/^[a-z]+$/.test(q) || !coll) return [];
        const cand = q.length >= 2 ? (buckets.get(q.slice(0, 2)) || []) : words;
        const hits = [];
        for (const w of cand) { const vs = py.get(w); if (vs && vs.some(v => v.startsWith(q))) hits.push(w); }
        hits.sort(rank);
        return hits.slice(0, limit);
      },
      // 模糊召回：首字母串包含查询的「相关词」（非前缀也算，如 ffczz 含 fcz）；全扫 2000 词×≤4 变体 <1ms
      fuzzy(q, limit = 30) {
        q = String(q || '').toLowerCase().trim();
        if (!q || !coll) return [];
        const hits = [];
        for (const w of words) { const vs = py.get(w); if (vs && vs.some(v => v.includes(q))) hits.push(w); }
        hits.sort(rank);
        return hits.slice(0, limit);
      },
      top: n => [...words].sort(rank).slice(0, n),
      initials: word => Array.from(String(word || '')).map(initialOf).join(''),
      variants: word => py.get(String(word || '').trim()) || [],
      list: () => words.slice(),
      size: () => words.length,
      weights: () => Object.fromEntries(wf),
    };
  }

  // 资源片名核心词归一化（docs/2026-10-30-资源搜索简拼直搜优化方案.md ①）：片名=核心名+模板装饰
  // 复仇者联盟4：终局之战→复仇者联盟；庆余年 第二季→庆余年；（）注释、更新至N集、版本号/版本词剥离
  const CORE_BRACKET = /[《〈「『【\[（(][^》〉」』】\])）]*[》〉」』】\])）]/g;
  const CORE_TOKEN = /(第[0-9一二三四五六七八九十百零两]+[季部集篇期章弹]|[0-9]+[季部]|更新至[0-9]+[集话]?|剧场版|特别篇|特别版|未删减版?|终极版|加长版|导演剪辑版|完整版|正片|预告片|花絮|片花|完结版?|抢先版|高清版?|超清|标清|蓝光|杜比|粤语|国语|中字|双字)/g;
  const CORE_CODE = /(^|[^A-Za-z0-9])(HD|BD|TC|TS|MKV|MP4|4K|8K|VIP|[0-9]{3,4}P)(?![A-Za-z0-9])/gi;
  function coreName(title) {
    let s = String(title || '').trim();
    if (!s) return '';
    s = s.replace(CORE_BRACKET, '').replace(CORE_TOKEN, '').replace(CORE_CODE, '$1');
    s = s.split(/[，。、！!？?；;：:．·\s|｜/\\-]+/)[0].trim().replace(/[0-9]+$/, '').trim();
    return s.length >= 2 ? s : '';
  }

  // 截断核心词（方案②）：把查询 q 在词首字母串中的**匹配位置**对齐截到 q 等长汉字并聚合
  // 'fcz'+复仇者联盟→复仇者（idx0）；'lm'+复仇者联盟 fczlm→联盟（idx3）——模糊命中不再从词首错截
  // variantsOf(w) 由调用方传 pinIndex.variants（含多音字变体）；缺省退化为词首前缀截断
  function truncateByQuery(q, hits, limit = 3, minCount = 2, variantsOf) {
    q = String(q || '').toLowerCase();
    if (!/^[a-z]+$/.test(q) || q.length < 2 || !hits || hits.length < minCount) return [];
    const inHits = new Set(hits);
    const hasIni = ch => /[0-9a-z]/i.test(ch) || /[㐀-䶿一-鿿]/.test(ch); // 占一个首字母位的字（与 initialOf 判定一致；标点/符号不占）
    const count = new Map();
    for (const w of hits) {
      let core = '';
      if (variantsOf) {
        for (const v of variantsOf(w) || []) {
          const idx = v.indexOf(q);
          if (idx < 0) continue;
          let vi = -1; const out = [];
          for (const ch of Array.from(String(w))) {
            if (!hasIni(ch)) { if (out.length) out.push(ch); continue; }
            vi++;
            if (vi < idx) continue;
            if (vi >= idx + q.length) break;
            out.push(ch);
          }
          if (vi >= idx + q.length - 1) core = out.join('').trim();
          if (core) break;
        }
      } else {
        const chars = Array.from(String(w));
        if (chars.length > q.length) core = chars.slice(0, q.length).join('').trim();
      }
      if (!core || core.length < 2 || inHits.has(core)) continue;
      count.set(core, (count.get(core) || 0) + 1);
    }
    return [...count].filter(([, n]) => n >= minCount).sort((a, b) => b[1] - a[1]).map(([c]) => c).slice(0, limit);
  }

  // m3u8 改写（对标主站 src/lib/m3u8.ts + proxy-handlers.ts:117-131）：
  // 分片行与 #EXT-X-KEY/#EXT-X-MAP 的 URI 解析为绝对地址后包成 /proxy?url=；base 用重定向后的 finalUrl；已代理 URI 不二次改写
  const PX_RE = /(^|[=/])proxy\?url=/i;
  function rewriteM3u8(text, base) {
    const px = u => '/proxy?url=' + encodeURIComponent(u);
    const resolve = u => { try { return new URL(u, base).href; } catch { return null; } };
    const rwAttr = line => line.replace(/URI="([^"]+)"/i, (mm, u) => {
      if (PX_RE.test(u)) return mm;
      const a = resolve(u);
      return a ? mm.replace('"' + u + '"', '"' + px(a) + '"') : mm;
    });
    return String(text).split('\n').map(raw => {
      const cr = raw.endsWith('\r'); const line = cr ? raw.slice(0, -1) : raw;
      const s = line.trim();
      if (!s) return raw;
      if (s.startsWith('#')) return /^#EXT-X-(KEY|MAP)\b/i.test(s) ? rwAttr(line) + (cr ? '\r' : '') : raw;
      if (PX_RE.test(s)) return raw;
      const a = resolve(s);
      return (a ? px(a) : s) + (cr ? '\r' : '');
    }).join('\n');
  }

  const PerfCore = { norm, createDedupe, orderSources, splitBatches, searchKey, SEARCH_TTL, splitTtl, createEpoch, rewriteM3u8, createPinIndex, coreName, truncateByQuery };
  g.PerfCore = PerfCore;
})(typeof globalThis !== 'undefined' ? globalThis : window);
