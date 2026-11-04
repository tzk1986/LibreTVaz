/* 地区过滤中间模块（自 perf-core.js 迁出，2026-11-04；APK 对应 data/perf/Region.kt）。
   纯函数、与具体源无关：任何源/任何墙（单源墙、聚合墙、搜索、专区）都可复用。
   判定优先级（2026-11-04 改为 area 优先）：vod_area 是权威地区信号，
   typeName/type/class 仅作兜底——修复「分类名带国产/港台等字样把外国条目混进国内」的漏洞。 */
(function (g) {
  'use strict';

  const UNKNOWN = 'UNKNOWN';
  /* 条目级地区判定的纯函数。
     地区枚举：DOM(大陆+港澳台，港台算国内) / EUUS(欧美，美国统一归此) / JP / KR / OTHER(东南亚印度等) / UNKNOWN(无信号，保守剔除) */
  const REGION_PAT = [
    ['DOM', ['大陆', '内地', '国产', '中国', '华语', '粤语', '香港', '港剧', '港产', '港台', '澳门', '台湾', '台剧', '台影']],
    ['EUUS', ['欧美', '美国', '美剧', '英剧', '英国', '欧洲', '法国', '德国', '意大利', '西班牙', '俄罗斯', '加拿大', '澳大利亚', '新西兰', '好莱坞']],
    ['JP', ['日本', '日剧', '日影', '日韩']],
    ['KR', ['韩国', '韩剧', '韩影', '日韩']],
    ['OTHER', ['泰国', '泰剧', '马来', '菲律宾', '越南', '印度', '新加坡', '印尼', '东南亚', '拉丁', '非洲', '中东']],
  ];
  const AREA_MAP = {};
  for (const [lab, ws] of REGION_PAT) for (const w of ws) if (!(w in AREA_MAP)) AREA_MAP[w] = lab;
  Object.assign(AREA_MAP, { 国产剧: 'DOM', 港: 'DOM', 台: 'DOM', 澳: 'DOM', 美: 'EUUS', 英: 'EUUS', 日: 'JP', 韩: 'KR', 其它: 'OTHER', 其他: 'OTHER' });

  // 名称中含有的全部地区标签（多命中按表序，如「日韩综艺」→[JP,KR]）
  function labels(name) { const s = String(name || ''); return REGION_PAT.filter(([, ws]) => ws.some(w => s.includes(w))).map(([lab]) => lab); }

  // 单条 vod_area：按分隔符拆 token，精确 AREA_MAP 优先，其次整词 labels；全空返回 null
  function ofArea(a) {
    for (const t of String(a || '').split(/[,，、/;；|]+/).map(x => x.trim()).filter(Boolean)) {
      if (AREA_MAP[t]) return AREA_MAP[t];
      const h = labels(t); if (h.length) return h[0];
    }
    return null;
  }

  // 条目地区：area > typeName > type/class；无信号 UNKNOWN（渲染期保守剔除并计数）
  function classify(it) {
    const r = ofArea(it && it.area); if (r) return r;
    const n1 = it && it.typeName; if (n1) { const h = labels(n1); if (h.length) return h[0]; }
    const n3 = it && (it.type || it.class); if (n3) { const h = labels(String(n3)); if (h.length) return h[0]; }
    return UNKNOWN;
  }

  // 便捷入口：是否命中给定地区组（组空=全部放行）
  function matches(it, labs) { return !labs || !labs.length || labs.indexOf(classify(it)) >= 0; }

  // 四类海报墙预设（用户定稿语义：港台澳归国内、美国归欧美）；[] = 全部
  const PRESETS = {
    电影: [['全部', []], ['国内', ['DOM']], ['国外', ['EUUS', 'JP', 'KR', 'OTHER']], ['日影', ['JP']], ['韩影', ['KR']], ['欧美', ['EUUS']]],
    电视剧: [['全部', []], ['国内', ['DOM']], ['欧美剧', ['EUUS']], ['日剧', ['JP']], ['韩剧', ['KR']], ['小语种', ['OTHER']]],
    综艺: [['全部', []], ['国内', ['DOM']], ['国外', ['EUUS', 'JP', 'KR', 'OTHER']], ['日本', ['JP']], ['韩国', ['KR']]],
    动漫: [['全部', []], ['国产', ['DOM']], ['日漫', ['JP']], ['韩漫', ['KR']], ['欧美', ['EUUS']]],
  };

  const Region = { UNKNOWN, labels, ofArea, classify, matches, PRESETS };
  g.Region = Region;
})(typeof globalThis !== 'undefined' ? globalThis : window);
