// 一次性脚本：从预置订阅中提取本机实测可用的 CMS 源，输出 name+api 供内置
const proxy = u => 'http://localhost:8787/proxy?url=' + encodeURIComponent(u);
const get = (u, t = 10000) => fetch(proxy(u), { signal: AbortSignal.timeout(t) }).then(r => r.text());
const loose = t => JSON.parse(t.replace(/^\s*\/\/.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/,(\s*[}\]])/g, '$1'));
const norm = u => String(u || '').trim().replace(/\/+$/, '');
const isCms = u => /\/api\.php\/provide\/vod(?![^?#]*\/at\/xml)(?:[\/?#]|$)/i.test(u);

async function expand(j) {
  let sites = j.sites || [];
  if (!j.sites && Array.isArray(j.urls)) {
    sites = [];
    const rs = await Promise.allSettled(j.urls.slice(0, 20).map(async e => loose(await get(e.url, 15000)).sites || []));
    for (const r of rs) if (r.status === 'fulfilled') sites = sites.concat(r.value);
  }
  const seen = new Set(); const out = [];
  for (const s of sites) {
    const api = norm(s.api);
    if ((s.type === 0 || s.type === 1) && !/csp_|\.(jar|js|py)/i.test(api) && isCms(api) && s.searchable !== 0 && !seen.has(api)) {
      seen.add(api); out.push({ name: s.name, api });
    }
  }
  return out;
}

const candidates = [];
for (const u of [
  'https://gitlab.com/noimank/tvbox/-/raw/main/tvboxmuti.json',
  'https://raw.liucn.cc/box/m.json',
  'https://gh-proxy.com/https://raw.githubusercontent.com/qist/tvbox/master/0821.json',
]) {
  try { candidates.push(...await expand(loose(await get(u, 15000)))); } catch { /* 忽略 */ }
}
const seen = new Set(); const good = [];
await Promise.all(candidates.map(async s => {
  if (seen.has(s.api)) return; seen.add(s.api);
  try {
    const b = s.api.replace(/\?[^?]*$/, '').replace(/\/+$/, '');
    const join = q => b + (b.includes('?') ? '&' : '?') + q;
    const t = await get(join('ac=list'));
    if (t.trimStart().startsWith('<')) return;
    const cls = JSON.parse(t).class || [];
    if (!cls.length) return;
    good.push(s);
  } catch { /* 忽略 */ }
}));
console.log(JSON.stringify(good.map(s => ({ name: s.name.replace(/[❤🥑🎏┃\s]+/g, '').slice(0, 12), url: s.api })), null, 0));
