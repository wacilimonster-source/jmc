#!/usr/bin/env node
/**
 * 一次性探针：官方 App 功能面 + 登录侧接口（2026-09-25）。
 *
 * 目标（用户提供账号，凭据只经环境变量 JM_USER / JM_PASS 传入，脚本与报告均不落盘）：
 *   A. 免登录新功能面：/promote（推荐本本）/ C108 线索 / 总排行 o=mv / 汉化组·去码·全彩 搜索维度
 *   B. 登录侧：login→AVS、收藏列表/收藏开关（验证 qt 库的 POST /favorite 翻转说法）、
 *      签到 /daily+/daily_chk、云端历史 /watch_list、此前 Not legal 的端点带会话复测、
 *      /comment 形态（只探参数错误文案，不实际发评）、/favorite_folder（收藏夹分组）
 *
 * 收藏开关实验会先记录 is_favorite 初值，实验后恢复原状。
 */
import { JmApi } from '../jm-lib.mjs';

const api = new JmApi({});
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const out = (...a) => console.log(...a);
const cut = (s, n = 90) => String(s ?? '').replace(/\s+/g, ' ').slice(0, n);

async function safe(label, fn) {
  try {
    const r = await fn();
    await sleep(250);
    return r;
  } catch (e) {
    out(`  [FAIL] ${label}: ${cut(e.message, 140)}`);
    return null;
  }
}

// ───────────────────────── A. 免登录功能面 ─────────────────────────

out('='.repeat(76));
out('A. 免登录功能面');
out('='.repeat(76));

out('\n[A1] /promote?page=1（官方 App「推荐本本」候选端点）');
{
  const r = await safe('promote', () => api.request('/promote?page=1'));
  if (r) {
    const d = r.envelope.data;
    const list = d?.list ?? d?.content ?? d;
    if (Array.isArray(list)) {
      out(`  list 长度=${list.length}  首项键=${Object.keys(list[0] ?? {}).join(',')}`);
      out(`  首项=${cut(JSON.stringify(list[0]), 200)}`);
    } else {
      out(`  data 类型=${typeof d}  键=${d ? Object.keys(d).slice(0, 12).join(',') : 'null'}`);
      out(`  data 预览=${cut(JSON.stringify(d), 300)}`);
    }
  }
}

out('\n[A2] /categories 全量（找 C108 / 特殊频道线索）');
{
  const r = await safe('categories', () => api.request('/categories'));
  if (r) {
    const d = r.envelope.data;
    for (const c of d.categories ?? []) {
      out(`  id=${String(c.id).padEnd(4)} slug=${String(c.slug).padEnd(14)} ${c.name}  存量=${c.total_albums}  子=${(c.sub_categories ?? []).map((s) => s.name).join('/')}`);
    }
    for (const b of d.blocks ?? []) {
      out(`  block「${b.title}」: ${(b.content ?? []).slice(0, 8).join(' / ')}`);
    }
    const other = Object.keys(d).filter((k) => k !== 'categories' && k !== 'blocks');
    if (other.length) out(`  其他键=${other.join(',')}`);
  }
}

out('\n[A3] 总排行 o=mv + 六档排序在 /categories/filter 上的行为');
{
  for (const o of ['mr', 'tf', 'mv', 'mv_m', 'mv_w', 'mv_t']) {
    const r = await safe(`o=${o}`, () => api.request(`/categories/filter?page=1&o=${o}&t=a`));
    if (r) {
      const d = r.envelope.data;
      const first = d.content?.[0];
      out(`  o=${o.padEnd(5)} total=${String(d.total).padEnd(7)} 首=${first ? `${first.id} ${cut(first.name, 24)} 观看=${first.total_views ?? ''}` : '空'}`);
    }
  }
}

out('\n[A4] 汉化组 / 去码 / 全彩：搜索维度定位');
{
  for (const [kw, mt] of [
    ['禁漫漢化組', 0], ['禁漫漢化組', 2], ['禁漫漢化組', 3],
    ['無碼', 3], ['全彩', 3], ['禁漫去碼', 0], ['漢化', 2],
  ]) {
    const r = await safe(`search ${kw} mt=${mt}`, () =>
      api.request(`/search?search_query=${encodeURIComponent(kw)}&page=1&main_tag=${mt}&t=a`));
    if (r) {
      const d = r.envelope.data;
      const first = d.content?.[0];
      out(`  kw=${kw} main_tag=${mt} total=${String(d.total).padEnd(6)} 首=${first ? `${first.id} ${cut(first.name, 20)}` : '空'}`);
    }
  }
}

// ───────────────────────── B. 登录侧 ─────────────────────────

const USER = process.env.JM_USER || '';
const PASS = process.env.JM_PASS || '';
out('\n' + '='.repeat(76));
out('B. 登录侧（账号经环境变量注入' + (USER ? `，用户=${USER}` : '，未提供——跳过') + '）');
out('='.repeat(76));

if (USER && PASS) {
  let session = '';
  out('\n[B1] POST /login');
  {
    const r = await safe('login', () => api.request('/login', { form: { username: USER, password: PASS } }));
    if (r) {
      const d = r.envelope.data ?? {};
      session = d.s ?? '';
      out(`  code=${r.envelope.code}  s=${session ? `${session.slice(0, 8)}…（长度${session.length}）` : '空'}  uid=${d.uid ?? ''}  errorMsg=${cut(r.envelope.errorMsg)}`);
    }
  }

  if (!session) {
    out('  登录失败，B 节终止。');
  } else {
    const auth = new JmApi({ session });

    out('\n[B2] GET /favorite?page=1&folder_id=0&o=mr（收藏列表真实形态）');
    {
      const r = await safe('favorite list', () => auth.request('/favorite?page=1&folder_id=0&o=mr'));
      if (r) {
        const d = r.envelope.data;
        const list = d.content ?? [];
        out(`  total=${d.total}  本页=${list.length}  首项键=${Object.keys(list[0] ?? {}).join(',')}`);
        if (list[0]) out(`  首项=${cut(JSON.stringify(list[0]), 260)}`);
      }
    }

    out('\n[B3] 收藏开关实验（选一本未收藏的，POST /favorite {aid} 两次=加+取消）');
    {
      const br = await safe('browse page1', () => api.request('/categories/filter?page=1&o=mr&t=a'));
      const items = br?.envelope.data?.content ?? [];
      let target = null;
      for (const it of items) {
        const ar = await safe(`album ${it.id}`, () => api.request(`/album?id=${it.id}`));
        if (ar && ar.envelope.data && ar.envelope.data.is_favorite === false) { target = it; break; }
      }
      if (!target) {
        out('  未找到未收藏样本，实验跳过');
      } else {
        const aid = target.id;
        out(`  样本 aid=${aid} ${cut(target.name, 24)}`);
        const toggle = async (label) => {
          const r = await safe(label, () => auth.request('/favorite', { form: { aid } }));
          if (r) {
            const d = r.envelope.data;
            out(`  ${label}: code=${r.envelope.code} data=${cut(JSON.stringify(d), 120)} err=${cut(r.envelope.errorMsg)}`);
            const ar = await safe(`verify ${aid}`, () => api.request(`/album?id=${aid}`));
            out(`    -> /album.is_favorite = ${ar?.envelope.data?.is_favorite}`);
          }
        };
        await toggle('POST /favorite {aid} 第一次(应为加入)');
        await toggle('POST /favorite {aid} 第二次(应为取消)');
      }
    }

    out('\n[B4] 签到：GET /daily + POST /daily_chk');
    {
      const r1 = await safe('daily', () => auth.request('/daily'));
      if (r1) out(`  /daily: ${cut(JSON.stringify(r1.envelope.data), 200)}`);
      const r2 = await safe('daily_chk', () => auth.request('/daily_chk', { form: {} }));
      if (r2) out(`  /daily_chk: code=${r2.envelope.code} data=${cut(JSON.stringify(r2.envelope.data), 220)} err=${cut(r2.envelope.errorMsg)}`);
    }

    out('\n[B5] GET /watch_list?page=1（云端历史）');
    {
      const r = await safe('watch_list', () => auth.request('/watch_list?page=1'));
      if (r) {
        const d = r.envelope.data;
        const list = d.content ?? [];
        out(`  total=${d.total}  本页=${list.length}  首项=${list[0] ? cut(JSON.stringify(list[0]), 200) : '空'}`);
      }
    }

    out('\n[B6] 此前免登录 Not legal 的端点，带会话复测');
    for (const p of ['/user', '/member', '/message', '/message_count', '/user_profile', '/history']) {
      const r = await safe(p, () => auth.request(p));
      if (r) {
        const d = r.envelope.data;
        out(`  ${p}: code=${r.envelope.code} data=${d === null ? 'null' : cut(JSON.stringify(d), 160)} err=${cut(r.envelope.errorMsg)}`);
      }
    }

    out('\n[B7] /comment 形态（空表单探错误文案，不实际发评）');
    {
      const r = await safe('comment empty', () => auth.request('/comment', { form: { aid: '0', content: '' } }));
      if (r) out(`  POST /comment 空表单: code=${r.envelope.code} err=${cut(r.envelope.errorMsg)} data=${cut(JSON.stringify(r.envelope.data), 120)}`);
      const r2 = await safe('forum post', () => auth.request('/forum', { form: { aid: '0', content: '' } }));
      if (r2) out(`  POST /forum 空表单: code=${r2.envelope.code} err=${cut(r2.envelope.errorMsg)}`);
    }

    out('\n[B8] /favorite_folder（收藏夹分组可行性）');
    {
      const r = await safe('favorite_folder GET', () => auth.request('/favorite_folder'));
      if (r) out(`  GET: code=${r.envelope.code} data=${cut(JSON.stringify(r.envelope.data), 200)} err=${cut(r.envelope.errorMsg)}`);
      const r2 = await safe('favorite list folder_id=1', () => auth.request('/favorite?page=1&folder_id=1&o=mr'));
      if (r2) out(`  /favorite folder_id=1: total=${r2.envelope.data?.total} err=${cut(r2.envelope.errorMsg)}`);
    }
  }
}

out('\n' + '='.repeat(76));
out(`探测完成，共 ${api.requestCount} 次请求；host 分布: ${
  [...api.hostStats.entries()].map(([h, s]) => `${h.split('.')[0]}:${s.ok}ok/${s.fail}fail`).join(' ')}`);
out('会话仅存于本进程内存，未落盘、未打印完整值。');
