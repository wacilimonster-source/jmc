#!/usr/bin/env node
/**
 * 补充探针（2026-09-25 第二轮）：修复首轮的形态盲区。
 *   1) /favorite 与 /watch_list 的真实 data 键名（首轮 content 为空但 total=26/20）
 *   2) /daily_chk 完整信封（签到语义）
 *   3) 子分类 slug（禁漫去码 / 禁漫上色 → c= 参数值）并验证 c= 过滤
 *   4) /promote 全部 block（首页频道设计输入）
 *   5) 分类 id=9「禁漫汉化组」的浏览参数（c= 中文 slug 直试）
 *   6) 用户已收藏的一本：/album.is_favorite 是否会为 true（首轮加收藏后 /album 恒 false）
 * 凭据仍只走环境变量。
 */
import { JmApi } from '../jm-lib.mjs';

const api = new JmApi({});
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const out = (...a) => console.log(...a);
const cut = (s, n = 110) => String(s ?? '').replace(/\s+/g, ' ').slice(0, n);

const USER = process.env.JM_USER || '';
const PASS = process.env.JM_PASS || '';

// 登录
const login = await api.request('/login', { form: { username: USER, password: PASS } });
const session = login.envelope.data?.s ?? '';
out(`[login] s=${session ? 'ok' : 'FAIL'}`);
await sleep(250);
const auth = new JmApi({ session });

out('\n[1] /favorite data 全键名 + 首项');
{
  const r = await auth.request('/favorite?page=1&folder_id=0&o=mr');
  const d = r.envelope.data;
  out(`  data 键=${Object.keys(d).join(',')}`);
  for (const k of Object.keys(d)) {
    const v = d[k];
    if (Array.isArray(v) && v.length) {
      out(`  ${k}[${v.length}] 首项=${cut(JSON.stringify(v[0]), 260)}`);
    } else if (!Array.isArray(v)) {
      out(`  ${k}=${cut(JSON.stringify(v), 60)}`);
    }
  }
}
await sleep(250);

out('\n[2] /watch_list data 全键名');
{
  const r = await auth.request('/watch_list?page=1');
  const d = r.envelope.data;
  out(`  data 键=${Object.keys(d).join(',')}`);
  for (const k of Object.keys(d)) {
    const v = d[k];
    if (Array.isArray(v) && v.length) out(`  ${k}[${v.length}] 首项=${cut(JSON.stringify(v[0]), 240)}`);
    else if (!Array.isArray(v)) out(`  ${k}=${cut(JSON.stringify(v), 60)}`);
  }
}
await sleep(250);

out('\n[3] /daily 与 /daily_chk 完整信封');
{
  const r1 = await auth.request('/daily');
  out(`  /daily 信封=${cut(JSON.stringify(r1.envelope), 240)}`);
  await sleep(300);
  const r2 = await auth.request('/daily_chk', { form: {} });
  out(`  /daily_chk 信封=${cut(JSON.stringify(r2.envelope), 300)}`);
}
await sleep(250);

out('\n[4] 子分类 slug（禁漫去码 / 禁漫上色）');
{
  const r = await api.request('/categories');
  for (const c of r.envelope.data.categories ?? []) {
    const subs = (c.sub_categories ?? []).map((s) => `${s.name}=${s.slug}`).filter((s) => s.includes('禁漫') || s.includes('上色') || s.includes('去碼') || s.includes('去码'));
    if (subs.length || c.id === 9) out(`  分类${c.id}(${c.slug}): ${subs.join(' / ') || '(无相关子分类)'}`);
  }
}
await sleep(250);

out('\n[5] c= 过滤验证（禁漫去码 / 禁漫汉化组）');
for (const c of ['禁漫去码', '禁漫汉化组', 'jm-hanhua']) {
  try {
    const r = await api.request(`/categories/filter?page=1&c=${encodeURIComponent(c)}&o=mr&t=a`);
    const d = r.envelope.data;
    const first = d.content?.[0];
    out(`  c=${c}: total=${d.total} 首=${first ? cut(first.name, 40) : '空'}`);
  } catch (e) { out(`  c=${c}: FAIL ${cut(e.message, 80)}`); }
  await sleep(250);
}

out('\n[6] /promote 全部 block 标题与 filter_val');
{
  const r = await api.request('/promote?page=1');
  for (const b of r.envelope.data?.list ?? []) {
    out(`  id=${String(b.id).padEnd(3)} type=${String(b.type).padEnd(8)} 「${b.title}」 filter_val=${b.filter_val} 内含=${(b.content ?? []).length}本`);
  }
}
await sleep(250);

out('\n[7] 用户已收藏本子的 /album.is_favorite');
{
  const r = await auth.request('/favorite?page=1&folder_id=0&o=mr');
  const d = r.envelope.data;
  const list = (d.content ?? d.list ?? d.albums ?? []).slice(0, 3);
  if (!list.length) {
    out('  收藏列表仍为空结构，跳过');
  } else {
    for (const it of list) {
      const aid = it.id ?? it.aid;
      if (!aid) continue;
      const ar = await api.request(`/album?id=${aid}`);
      out(`  aid=${aid} 列表项is_favorite=${it.is_favorite} /album.is_favorite=${ar.envelope.data?.is_favorite} liked=${ar.envelope.data?.liked}`);
      await sleep(250);
    }
  }
}

out(`\n完成，共 ${api.requestCount + auth.requestCount} 次请求；会话仅存内存未落盘。`);
