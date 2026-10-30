// 扫码远程输入会话状态机回归（docs/2026-10-30-二维码扫码输入调研与优化方案.md P5）
// 依赖 run.mjs 起的调试服务器（PORT 参数或 PROXY_PORT env），纯 HTTP 无 DOM
const PORT = +(process.argv.find(a => /^\d{4,5}$/.test(a)) || process.env.PROXY_PORT || 8799);
const BASE = `http://127.0.0.1:${PORT}`;

let fails = 0;
const ok = (cond, name) => { console.log(`${cond ? 'PASS' : 'FAIL'} remote: ${name}`); if (!cond) fails++; };
const post = (p, body) => fetch(BASE + p, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body), signal: AbortSignal.timeout(40000) }).then(r => r.json());
const getj = (p, ms = 40000) => fetch(BASE + p, { signal: AbortSignal.timeout(ms) }).then(r => r.json());
const S = () => 'test' + Math.random().toString(36).slice(2, 10);
const sleep = ms => new Promise(r => setTimeout(r, ms));

{ // P1：未注册 sid 拒发并给出原因；status 初始离线
  const sid = S();
  const r = await post('/api/remote/send', { sid, text: 'x' });
  ok(r.ok === false && r.reason === 'tv_offline', '未注册 sid send → tv_offline（原死代码提示已复活）');
  const st = await getj(`/api/remote/status?sid=${sid}`);
  ok(st.online === false && st.ackedSeq === 0, '未知 sid status=离线/ack0');
}

{ // P1/P2 主流程：open → send(seq) → poll 一次取走 → status ack；读取即删；覆盖留最新
  const sid = S();
  await post('/api/remote/open', { sid });
  const s1 = await post('/api/remote/send', { sid, mode: 'search', text: '第一条' });
  ok(s1.ok === true && s1.seq >= 1, '注册后 send → ok+seq');
  const hit = await getj(`/api/remote/poll?id=${sid}&nowait=1`);
  ok(hit.text === '第一条' && hit.mode === 'search' && hit.seq === s1.seq, 'poll 消费消息且字段完整');
  const again = await getj(`/api/remote/poll?id=${sid}&nowait=1`);
  ok(!again.text, '读取即删，TV 不重复触发');
  const st = await getj(`/api/remote/status?sid=${sid}`);
  ok(st.online === true && st.ackedSeq === s1.seq, 'status 在线 + ackedSeq 回执');
  await post('/api/remote/send', { sid, text: 'A' });
  await post('/api/remote/send', { sid, text: 'B' });
  const h2 = await getj(`/api/remote/poll?id=${sid}&nowait=1`);
  ok(h2.text === 'B', 'TV 未取走时连发两条：仅保留最新（覆盖语义）');
}

{ // P2 长轮询即时性：先挂 poll 再 send，应 <2.5s 唤醒（旧实现为最长 1.5s 轮询间隔）
  const sid = S();
  await post('/api/remote/open', { sid });
  const t0 = Date.now();
  const p = getj(`/api/remote/poll?id=${sid}`);
  await sleep(300);
  await post('/api/remote/send', { sid, text: '长轮询' });
  const hit = await p;
  const ms = Date.now() - t0;
  ok(hit.text === '长轮询' && ms < 2500, `挂起中的 poll 被 send 即时唤醒（${ms}ms）`);
}

{ // 兼容：未 open、仅 poll 也算注册（旧 TV 页无 open 时链路不回归）
  const sid = S();
  const h0 = await getj(`/api/remote/poll?id=${sid}&nowait=1`);
  ok(!h0.text, '无消息 poll 返回空对象');
  const r = await post('/api/remote/send', { sid, text: 'y' });
  ok(r.ok === true, '仅 poll 也完成会话注册');
}

{ // P4 限速（放最后：全部用例共用同一 IP 的 20 次/分额度）
  const sid = S();
  let rateAt = 0;
  for (let i = 1; i <= 30; i++) {
    const r = await post('/api/remote/send', { sid, text: 'z' });
    if (r.reason === 'rate') { rateAt = i; break; }
  }
  ok(rateAt > 0, `${rateAt} 次连发触发 rate 限速（20 次/分/IP，前述用例已占用部分额度）`);
}

console.log(fails ? `remote: ${fails} 条失败` : 'remote: 全部通过');
process.exit(fails ? 1 : 0);
