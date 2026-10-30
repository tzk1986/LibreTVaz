// R8 地区分类中间件单测（docs/2026-10-30-遥控可用性复盘与优化方案.md）
// 语义定稿：港台算国内（DOM）、美国统一归欧美（EUUS）、无信号 UNKNOWN
import '../debug-web/perf-core.js';

const P = globalThis.PerfCore;
let fails = 0;
const ok = (cond, msg) => { if (!cond) { fails++; console.log('FAIL ' + msg); } else console.log('PASS ' + msg); };
const eq = (a, b, msg) => ok(JSON.stringify(a) === JSON.stringify(b), `${msg} (got ${JSON.stringify(a)} want ${JSON.stringify(b)})`);

// 信号优先级：typeName > area > type/class
eq(P.classifyVod({ typeName: '内地剧', area: '美国' }), 'DOM', 'typeName 优先于 area');
eq(P.classifyVod({ typeName: '剧情', area: '美国' }), 'EUUS', 'area 美国→EUUS（美国归欧美）');
eq(P.classifyVod({ typeName: '其它', type: '韩国综艺' }), 'KR', 'type 兜底 韩国综艺→KR');
eq(P.classifyVod({}), 'UNKNOWN', '全空→UNKNOWN');
eq(P.classifyVod({ typeName: '动作片' }), 'UNKNOWN', '无地区信号类型名→UNKNOWN');

// DOM：港澳台算国内
for (const w of ['港剧', '台湾', '澳门', '港台', '国产', '内地', '大陆', '粤语']) {
  eq(P.classifyVod({ area: w }), 'DOM', `area=${w}→DOM（含港澳台）`);
}
// EUUS：美国/英国/欧洲词族
for (const w of ['美国', '英国', '欧美', '美剧', '法国', '好莱坞']) {
  eq(P.classifyVod({ area: w }), 'EUUS', `area=${w}→EUUS`);
}
// 单字 area token
eq(P.classifyVod({ area: '港' }), 'DOM', '单字 area 港→DOM');
eq(P.classifyVod({ area: '美' }), 'EUUS', '单字 area 美→EUUS');
eq(P.classifyVod({ area: '其它' }), 'OTHER', 'area 其它→OTHER');

// JP/KR/OTHER
eq(P.classifyVod({ typeName: '日剧' }), 'JP', '日剧→JP');
eq(P.classifyVod({ typeName: '韩剧' }), 'KR', '韩剧→KR');
eq(P.classifyVod({ area: '泰国' }), 'OTHER', '泰国→OTHER');
eq(P.classifyVod({ area: '日本,美国' }), 'JP', '多 token area 按序取首个命中');

// regionLabels 多命中（表序）
eq(P.regionLabels('日韩综艺'), ['JP', 'KR'], '「日韩综艺」全命中 [JP,KR]');
eq(P.regionLabels('剧情'), [], '无信号词→[]');

// 预设表：国外 = 非 DOM 并集；小语种 = OTHER；全部 = 空数组
const abroad = P.VOD_FILTERS.电影.find(x => x[0] === '国外')[1];
eq(abroad.slice().sort(), ['EUUS', 'JP', 'KR', 'OTHER'].sort(), '电影「国外」=非DOM四区并集');
ok(P.VOD_FILTERS.电视剧.find(x => x[0] === '小语种')[1].join() === 'OTHER', '电视剧「小语种」=OTHER');
eq(P.VOD_FILTERS.电影.find(x => x[0] === '全部')[1], [], '「全部」=空数组');
ok(!P.VOD_FILTERS.电影.some(x => x[0] === '美国'), '预设中无独立「美国」（已并入欧美）');

console.log(fails ? `\n${fails} 个断言失败` : '\n分类中间件全部通过');
process.exit(fails ? 1 : 0);
