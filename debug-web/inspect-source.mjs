// 通用点播源体检：node debug-web/inspect-source.mjs <CMS接口地址|订阅链接> [timeoutMs]
// 判定各父分类的展示策略（直查 / 聚合子类），检查 type_extend、最新年份、搜索冒烟——
// 与 fongmi.js 页面逻辑同规则，方便新源接入时快速确认体验一致。
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36';
const url = process.argv[2] || '';
const TIMEOUT = +(process.argv[3] || 15000);
if (!/^https?:\/\//i.test(url)) { console.log('用法: node debug-web/inspect-source.mjs <http(s)地址> [timeoutMs]'); process.exit(1); }

const get = async u => {
  const r = await fetch(u, { headers: { 'User-Agent': UA, Referer: new URL(u).origin + '/' }, signal: AbortSignal.timeout(TIMEOUT) });
  const t = await r.text();
  if (!r.ok) throw new Error('HTTP ' + r.status);
  if (t.trimStart().startsWith('<')) throw new Error('返回网页而非 JSON（可能失效/需 Cookie）');
  return JSON.parse(t);
};
const norm = u => String(u).trim().replace(/[?&]+$/, '').replace(/\/+$/, '');
const apiOf = (base, q) => { const b = norm(base); return b + (b.includes('?') ? '&' : '?') + q; };
const stripAc = u => norm(u).replace(/([?&])ac=[^&#]*/gi, '$1').replace(/[?&]+$/, '');

/* 订阅配置（tvbox/多仓/SourceList）→ 提取可测的 CMS api 列表 */
function extractApis(root) {
  if (!root || typeof root !== 'object') return null;
  const out = [];
  if (Array.isArray(root)) { root.forEach(x => { if (x?.url && /^https?:/.test(x.url)) out.push({ name: x.name || x.url, url: x.url }); }); return out; }
  if (root?.urls) { console.log(`多仓配置：${root.urls.length} 个仓库（脚本只测第一层，展开请用页面导入）`); }
  (root?.sites || []).forEach(s => {
    const api = s.api;
    if (s.type === 3 || /^csp_|\.(jar|js|py)/i.test(api || '')) return; // spider 类无法直测
    if (/^https?:/.test(api || '')) out.push({ name: s.name, url: api });
  });
  (root?.sources || []).forEach(s => { if (/^https?:/.test(s.url || '')) out.push({ name: s.name, url: s.url }); });
  return out.length ? out : null;
}

async function inspect(one) {
  const { name, url: raw } = one;
  console.log(`\n=== ${name} — ${raw}`);
  let list;
  try { list = await get(apiOf(stripAc(raw), 'ac=list')); }
  catch (e) { console.log('  ac=list 失败：' + e.message + '（将按无分类源处理，页面直接 ac=videolist 全量）'); return; }
  const cls = list.class || [];
  const kidsOf = pid => cls.filter(c => String(c.type_pid) === String(pid));
  const parents = cls.filter(c => !c.type_pid || String(c.type_pid) === '0');
  const hasExtend = cls.some(c => c.type_extend && Object.keys(typeof c.type_extend === 'string' ? JSON.parse(c.type_extend || '{}') || {} : c.type_extend).length);
  console.log(`  分类 ${cls.length}（父 ${parents.length}）· type_extend ${hasExtend ? '有（支持地区/年份筛选）' : '无'}`);
  const yrOf = y => parseInt(y, 10) || 0;
  for (const p of parents.slice(0, 8)) {
    const kids = kidsOf(p.type_id);
    let own = null, ownErr = '';
    try { own = await get(apiOf(stripAc(raw), `ac=videolist&t=${p.type_id}&pg=1`)); } catch (e) { ownErr = e.message; }
    const ownN = own?.list?.length ?? -1;
    const ownTotal = own?.total ?? 0;
    let kidSample = '', kidMaxYear = 0, verdict;
    if (kids.length) {
      try {
        const k = await get(apiOf(stripAc(raw), `ac=videolist&t=${kids[0].type_id}&pg=1`));
        kidMaxYear = Math.max(...(k.list || []).map(v => yrOf(v.vod_year)), 0);
        kidSample = `子类[${kids[0].type_name}] total=${k.total}`;
      } catch (e) { kidSample = '子类查询失败:' + e.message; }
    }
    // 与页面同规则：有子类 → 聚合；父直查条数少于等于子类首页水平也视为空壳
    if (!kids.length && ownN > 0) verdict = '直查可用（平级分类）';
    else if (!kids.length) verdict = '无数据且无子类';
    else if (ownTotal > kids.length * 20) verdict = `父直查丰富（total=${ownTotal}），页面仍按子类聚合以保证覆盖`;
    else verdict = `空壳父类（直查 total=${ownTotal}${ownN >= 0 ? '，首页 ' + ownN + ' 条' : ''}）→ 页面聚合 ${kids.length} 个子类`;
    console.log(`  · ${p.type_name}(t=${p.type_id}) 子类${kids.length} ${kidSample}${kidMaxYear ? ' 最新年' + kidMaxYear : ''} → ${verdict}${ownErr ? ' [父查询错误:' + ownErr + ']' : ''}`);
  }
  try {
    const v = await get(apiOf(stripAc(raw), 'ac=videolist&pg=1'));
    const years = (v.list || []).map(x => yrOf(x.vod_year));
    console.log(`  ac=videolist 全量：total=${v.total} 首页年份 max=${Math.max(...years, 0)}（若为当前年说明含最新资源，年份倒序即可置顶）`);
  } catch (e) { console.log('  ac=videolist 全量失败：' + e.message); }
  try {
    const s = await get(apiOf(stripAc(raw), 'ac=videolist&wd=' + encodeURIComponent('战') + '&pg=1'));
    console.log(`  搜索冒烟 wd=战：命中 ${s.list?.length ?? 0} 条${s.list?.length ? '' : '（源可能不支持搜索，页面跨源搜索将主要依赖其它源）'}`);
  } catch (e) { console.log('  搜索冒烟失败：' + e.message); }
}

const root = await get(url).catch(() => null);
let targets = root ? extractApis(root) : null;
if (!targets) targets = [{ name: '直连接口', url }];
console.log(`体检对象 ${targets.length} 个 CMS 接口${targets.length > 1 ? '（只测前 10 个）' : ''}`);
for (const t of targets.slice(0, 10)) await inspect(t);
