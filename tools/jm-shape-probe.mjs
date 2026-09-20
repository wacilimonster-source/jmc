#!/usr/bin/env node
/**
 * 字段形态漂移探测 —— 把「服务端悄悄改了字段名/类型」这件事变红。
 *
 * 做法：把每个端点的响应摊平成「路径 -> 类型」集合（数组元素取并集），
 *       与 tools/.jm-shape-baseline.json 比对，报出：
 *         + 新增字段（可能可以补 UI）
 *         - 消失字段（用到它的功能会静默变空或崩溃）
 *         ~ 类型变化（PiKA D1 的根因：String -> Object / String -> Array）
 *
 * 用法：
 *   node tools/jm-shape-probe.mjs            # 与基线比对，有漂移则退出码 1
 *   node tools/jm-shape-probe.mjs --update   # 用当前线上形态重建基线
 *   node tools/jm-shape-probe.mjs --full     # 打印全部字段路径，不只打印差异
 *
 * 与 jm-api-check.mjs 的分工：
 *   api-check  回答「功能还能不能用」（值层面的断言）
 *   shape-probe 回答「字段形态有没有变」（结构层面的断言）
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { JmApi, firstId } from './jm-lib.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BASELINE_FILE = path.join(__dirname, '.jm-shape-baseline.json');
const argv = new Set(process.argv.slice(2));
const UPDATE = argv.has('--update');
const FULL = argv.has('--full');
const VERBOSE = argv.has('--verbose');

const pad = (s, n) => String(s ?? '').padEnd(n);

/** 把 JSON 摊平成 { "data.content[].category.title": "string" }，数组元素取并集 */
function flatten(value, prefix, out) {
  if (Array.isArray(value)) {
    out[`${prefix}[]`] = 'array';
    for (const item of value) flatten(item, `${prefix}[]`, out);
    return;
  }
  if (value === null) {
    merge(out, prefix, 'null');
    return;
  }
  if (typeof value === 'object') {
    for (const [k, v] of Object.entries(value)) {
      flatten(v, prefix ? `${prefix}.${k}` : k, out);
    }
    return;
  }
  merge(out, prefix, typeof value);
}

function merge(out, key, type) {
  const prev = out[key];
  if (!prev) {
    out[key] = type;
  } else if (!prev.split('|').includes(type)) {
    out[key] = `${prev}|${type}`;
  }
}

const api = new JmApi({ verbose: VERBOSE });
const current = {};

async function probe(label, promise, note = '') {
  try {
    const r = await promise;
    const shapes = {};
    flatten(r.envelope, '', shapes);
    current[label] = shapes;
    const topKeys = Object.keys(r.envelope ?? {}).join(', ');
    const dataKeys = Object.keys(r.envelope?.data ?? {}).slice(0, 8).join(', ');
    console.log(`  ${pad(label, 14)} ${pad(Object.keys(shapes).length + ' 路径', 10)} data: ${dataKeys || topKeys}`);
    return r;
  } catch (e) {
    console.log(`  ${pad(label, 14)} ERR ${e.message.slice(0, 56)}`);
    return null;
  }
}

console.log('字段形态探测' + (UPDATE ? '  [--update 重建基线]' : ''));
console.log('─'.repeat(78));

await probe('setting', api.setting());
await probe('categories', api.categories());
const browse = await probe('list', api.browse(1));
const albumId = firstId(browse?.envelope ?? {});
await probe('search', api.search('姐姐', { mainTag: 0 }));
const album = albumId ? await probe('album', api.album(albumId)) : null;
const photoId = album?.envelope?.data?.series?.[0]?.id ?? albumId;
if (photoId) await probe('chapter', api.chapter(photoId));
if (albumId) await probe('forum', api.forum(albumId, 1));
const week = await probe('week', api.week());
const weekId = week?.envelope?.data?.categories?.[0]?.id;
if (weekId) await probe('weekFilter', api.weekFilter(weekId, 1));

console.log('─'.repeat(78));

if (UPDATE) {
  fs.writeFileSync(BASELINE_FILE, JSON.stringify({ shapes: current }, null, 2) + '\n', 'utf8');
  console.log(`基线已更新：${path.relative(process.cwd(), BASELINE_FILE)}`);
  process.exit(0);
}

if (!fs.existsSync(BASELINE_FILE)) {
  console.log('基线不存在。先跑一次 `node tools/jm-shape-probe.mjs --update` 建立基线。');
  process.exit(1);
}

const baseline = JSON.parse(fs.readFileSync(BASELINE_FILE, 'utf8')).shapes ?? {};
let drift = 0;

for (const label of Object.keys(current)) {
  const base = baseline[label];
  if (!base) {
    console.log(`\n[${label}] 基线里没有这个端点 —— 新增端点，请 --update 纳入`);
    drift += 1;
    continue;
  }
  const cur = current[label];
  const added = Object.keys(cur).filter((k) => !(k in base));
  const removed = Object.keys(base).filter((k) => !(k in cur));
  const changed = Object.keys(cur).filter((k) => k in base && base[k] !== cur[k]);

  if (!added.length && !removed.length && !changed.length) {
    if (FULL) {
      console.log(`\n[${label}] 无漂移（${Object.keys(cur).length} 路径）`);
      for (const k of Object.keys(cur).sort()) console.log(`    ${pad(k, 52)} ${cur[k]}`);
    }
    continue;
  }

  drift += added.length + removed.length + changed.length;
  console.log(`\n[${label}]`);
  for (const k of added.sort()) console.log(`  + 新增字段  ${pad(k, 50)} ${cur[k]}`);
  for (const k of removed.sort()) console.log(`  - 字段消失  ${pad(k, 50)} ${base[k]}`);
  for (const k of changed.sort()) console.log(`  ~ 类型变化  ${pad(k, 50)} ${base[k]} -> ${cur[k]}`);
}

for (const label of Object.keys(baseline)) {
  if (!(label in current)) {
    console.log(`\n[${label}] 本次未探测到（请求失败？）`);
    drift += 1;
  }
}

console.log('\n' + '─'.repeat(78));
if (drift === 0) {
  console.log('无字段形态漂移。');
  process.exit(0);
}
console.log(`发现 ${drift} 处形态漂移。逐条确认影响面后，再决定是否 --update 接受新形态。`);
console.log('提醒：类型变化（String -> Object/Array）是 PiKA D1 的根因，必须改 DTO 而不是放宽解析。');
process.exit(1);
