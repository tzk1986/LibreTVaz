// 一次性探活脚本：检验各预置订阅在本机环境有多少 CMS 源真正可用
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

async function probe(srcs) {
  const ok = [];
  await Promise.all(srcs.map(async s => {
    try {
      const b = s.api.replace(/\?[^?]*$/, '').replace(/\/+$/, '');
      const join = q => b + (b.includes('?') ? '&' : '?') + q;
      const t = await get(join('ac=list'));
      if (t.trimStart().startsWith('<')) return;
      const cls = JSON.parse(t).class || [];
      if (!cls.length) return;
      let list = '列表无';
      try {
        const t2 = await get(join('ac=videolist&t=' + cls[0].type_id + '&pg=1'));
        if (!t2.trimStart().startsWith('<') && (JSON.parse(t2).list || []).length > 0) list = '列表OK';
      } catch { /* 忽略 */ }
      ok.push(`${s.name}(${cls.length}分类,${list})`);
    } catch { /* 忽略 */ }
  }));
  return ok;
}

for (const [name, u] of [
  ['Liucn', 'https://raw.liucn.cc/box/m.json'],
  ['Qist', 'https://gh-proxy.com/https://raw.githubusercontent.com/qist/tvbox/master/0821.json'],
  ['noimank', 'https://gitlab.com/noimank/tvbox/-/raw/main/tvboxmuti.json'],
]) {
  try {
    const j = loose(await get(u, 15000));
    const srcs = await expand(j);
    const ok = await probe(srcs.slice(0, 60));
    console.log(`[${name}] 可导入 ${srcs.length} · 可用 ${ok.length}：`, ok.slice(0, 10).join('、') || '无');
  } catch (e) {
    console.log(`[${name}] 失败`, e.message);
  }
}
