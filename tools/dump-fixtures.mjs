#!/usr/bin/env node
/**
 * 拉取真实响应，生成 app/src/test/resources/jm/ 下的 DTO fixture。
 *
 * 为什么必须来自真实响应（而不是手写）：
 *   原先这 8 份 fixture 是**手写的形态样本**（"测试本子A" / "评论者甲" 就是证据），
 *   只能证明「DTO 能解析我以为的形态」。服务端形态一漂移，手写 fixture 依然全绿 ——
 *   这正是 PiKA D1 潜伏 16 天、以及 2026-09-21 真机 /setting 事故的原因。
 *   实测已累计被手写 fixture 掩盖 3 个致命类型错误：
 *     app_shunts  List<String> vs 对象数组      -> /setting 整体解析失败、域名自愈瘫痪
 *     expinfo.badges List<String> vs 对象数组   -> /forum 整体解析失败
 *     categories[].id String vs 数字 0          -> /categories 整体解析失败
 *
 * 但真实响应含用户生成内容（评论区导流广告、成人向标题），不适合入库。
 * 因此本脚本**保留全部类型与结构，只把自由文本换成占位符**：
 *   "[sample:<字段名>]"  —— 类型仍然是 string，数组长度、对象层级、数字/布尔/null 全部原样保留。
 * 结构保真才是 fixture 的价值所在，文案不是。
 *
 * 用法：
 *   node tools/dump-fixtures.mjs            # 抓取并写入
 *   node tools/dump-fixtures.mjs --dry      # 只抓取并展示，不落盘
 *   node tools/dump-fixtures.mjs --raw      # 不脱敏（本地临时排查用，勿提交）
 *   node tools/dump-fixtures.mjs --verbose  # 打印换域过程
 *
 * 写完必须跟着做：
 *   1. 跑 `./gbuild.sh :app:testDebugUnitTest`，按新数据同步 JmDtoFixtureTest 的断言值
 *   2. 跑 `node tools/jm-shape-probe.mjs --update` 重建形态基线
 *   3. 跑 `node tools/jm-dto-contract.mjs` 确认 DTO 声明与线上形态一致
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { JmApi, firstId } from './jm-lib.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FIXTURE_DIR = path.join(__dirname, '..', 'app', 'src', 'test', 'resources', 'jm');
const argv = new Set(process.argv.slice(2));
const DRY = argv.has('--dry');
const RAW = argv.has('--raw');
const VERBOSE = argv.has('--verbose');

const pad = (s, n) => String(s ?? '').padEnd(n);

/**
 * 自由文本字段 —— 值一律替换为占位符。
 * 判断标准：内容由用户或运营产生、可能含成人向或导流文案。
 * 不在表里的字符串（id / slug / sort / 日期 / 文件名 / URL / 短枚举）原样保留，
 * 因为测试断言要靠它们。
 */
const FREE_TEXT_KEYS = new Set([
  'content', 'description', 'name', 'title', 'username', 'nickname',
  'level_name', 'search_query', 'version_info', 'jm3_version_info',
  'author', 'actors', 'works', 'tags', 'tag',
]);

let scrubbed = 0;

/** 递归脱敏：保留类型/结构/数组长度，只换掉自由文本 */
function sanitize(value, key) {
  if (Array.isArray(value)) return value.map((v) => sanitize(v, key));
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = sanitize(v, k);
    return out;
  }
  if (typeof value === 'string' && FREE_TEXT_KEYS.has(key)) {
    scrubbed += 1;
    return `[sample:${key}]`;
  }
  return value;
}

/** 旧 fixture 是否带手写痕迹（覆盖前留个证据） */
function looksSynthetic(text) {
  return /测试本子|评论者甲|搜索结果甲|多作者测试本|作者甲|作者丙|往返测试/.test(text);
}

const api = new JmApi({ verbose: VERBOSE });
const written = [];

async function save(name, promise, note) {
  const r = await promise;
  const body = RAW ? r.envelope : sanitize(r.envelope, '');
  const json = JSON.stringify(body, null, 2) + '\n';
  const file = path.join(FIXTURE_DIR, `${name}.json`);
  const before = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
  const wasSynthetic = before ? looksSynthetic(before) : false;
  if (!DRY) fs.writeFileSync(file, json, 'utf8');
  written.push({ name, bytes: Buffer.byteLength(json), wasSynthetic, changed: before !== json });
  console.log(
    `  ${pad(name + '.json', 18)} ${pad(json.length + 'B', 8)} ` +
    `${wasSynthetic ? '（旧的是手写样本）' : before ? '' : '（新建）'}  ${note}`,
  );
  return r.envelope;
}

console.log('拉取真实响应 -> app/src/test/resources/jm/' +
  (DRY ? '   [--dry 不落盘]' : RAW ? '   [--raw 未脱敏，勿提交]' : '   [自由文本已脱敏]'));
console.log('─'.repeat(78));

// /setting 与 /categories 不依赖其他请求
await save('setting', api.setting(), '/setting 明文 data');
const cats = await api.categories();
await save('categories', Promise.resolve(cats), `${cats.envelope?.data?.categories?.length ?? '-'} 大类`);

// 浏览流：拿一个真实 album id 供后续探测
const browseEnv = await save('list', api.browse(1), '浏览流 p1（total 恒 10000）');
const albumId = firstId(browseEnv);
if (!albumId) {
  console.error('\n浏览流没有返回条目，无法继续抓取 album/chapter/forum。');
  process.exit(1);
}

await save('search', api.search('姐姐', { mainTag: 0 }), '搜索 综合维度 main_tag=0');

const albumEnv = await save('album', api.album(albumId), `album id=${albumId}`);
const photoId = albumEnv?.data?.series?.[0]?.id ?? albumId;
await save('chapter', api.chapter(photoId), `chapter photoId=${photoId}`);
await save('forum', api.forum(albumId, 1), `forum aid=${albumId} p1`);

const weekEnv = await save('week', api.week(), '期数列表');
const weekId = weekEnv?.data?.categories?.[0]?.id;
if (weekId) {
  // /week/filter 返回 data.list（不是 content）—— 客户端 JmListData 两个键都声明了，
  // 因此这里也纳入 fixture，把「列表键可能是 list」这件事固化成回归。
  await save('weekFilter', api.weekFilter(weekId, 1), `weekFilter id=${weekId}（键为 list）`);
}

console.log('─'.repeat(78));
console.log(`共 ${written.length} 份 fixture${DRY ? '（未落盘）' : ' 已写入'}；` +
  `其中 ${written.filter((w) => w.wasSynthetic).length} 份原本是手写样本；` +
  `${RAW ? '未脱敏' : `脱敏 ${scrubbed} 处自由文本`}。`);

if (!DRY) {
  console.log('\n下一步：');
  console.log('  1. ./gbuild.sh :app:testDebugUnitTest    —— 同步 JmDtoFixtureTest 的断言值');
  console.log('  2. node tools/jm-shape-probe.mjs --update —— 重建形态基线');
  console.log('  3. node tools/jm-dto-contract.mjs         —— 确认 DTO 声明与线上一致');
}
