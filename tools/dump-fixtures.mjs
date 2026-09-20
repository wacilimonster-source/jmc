#!/usr/bin/env node
/**
 * 拉取真实响应，覆盖 app/src/test/resources/jm/ 下的 8 份 DTO fixture。
 *
 * 为什么必须用真数据：
 *   当前这 8 份 fixture 是**手写的形态样本**（里面的 "测试本子A" / "评论者甲" /
 *   "多作者测试本" 就是证据），只能证明「DTO 能解析我以为的形态」。
 *   服务端字段一漂移，手写 fixture 依然全绿 —— 这正是 PiKA D1 潜伏 16 天的原因。
 *   本脚本把 fixture 换成抓包结果，JmDtoFixtureTest 才真正具备回归能力。
 *
 * 用法：
 *   node tools/dump-fixtures.mjs            # 抓取并写入
 *   node tools/dump-fixtures.mjs --dry      # 只抓取并展示，不落盘
 *   node tools/dump-fixtures.mjs --verbose  # 打印换域过程
 *
 * ⚠ 覆盖后 JmDtoFixtureTest 里的具体断言值（id / total / 作者名）会失效，
 *   需要按新数据同步更新断言 —— 脚本会在最后提示哪些断言值变了。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { JmApi, firstId } from './jm-lib.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FIXTURE_DIR = path.join(__dirname, '..', 'app', 'src', 'test', 'resources', 'jm');
const argv = new Set(process.argv.slice(2));
const DRY = argv.has('--dry');
const VERBOSE = argv.has('--verbose');

const pad = (s, n) => String(s ?? '').padEnd(n);

/** 旧 fixture 是否带手写痕迹（用于在覆盖前留个证据） */
function looksSynthetic(text) {
  return /测试本子|评论者甲|搜索结果甲|多作者测试本|作者甲|作者丙|往返测试/.test(text);
}

const api = new JmApi({ verbose: VERBOSE });
const written = [];
const provenance = [];

async function save(name, promise, note) {
  const r = await promise;
  const json = JSON.stringify(r.envelope, null, 2) + '\n';
  const file = path.join(FIXTURE_DIR, `${name}.json`);
  const before = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
  const wasSynthetic = before ? looksSynthetic(before) : false;
  if (!DRY) fs.writeFileSync(file, json, 'utf8');
  written.push({ name, bytes: Buffer.byteLength(json), wasSynthetic, changed: before !== json });
  provenance.push(note);
  console.log(
    `  ${pad(name + '.json', 18)} ${pad(json.length + 'B', 8)} ` +
    `${wasSynthetic ? '（旧的是手写样本）' : before ? '' : '（新建）'}  ${note}`,
  );
  return r.envelope;
}

console.log('拉取真实响应 -> app/src/test/resources/jm/' + (DRY ? '   [--dry 不落盘]' : ''));
console.log('─'.repeat(78));

// /setting 与 /categories 不依赖其他请求
await save('setting', api.setting(), '/setting 明文 data');
const categoriesEnv = await save('categories', api.categories(),
  `${(await api.categories()).envelope?.data?.categories?.length ?? '-'} 大类`);

// 浏览流：拿一个真实 album id 供后续探测
const browseEnv = await save('list', api.browse(1), '浏览流 p1（total 恒 10000）');
const albumId = firstId(browseEnv);
if (!albumId) {
  console.error('\n浏览流没有返回条目，无法继续抓取 album/chapter/forum。');
  process.exit(1);
}

// 搜索：用 fixture 里原本的关键词，保持断言可读
await save('search', api.search('姐姐', { mainTag: 0 }), '搜索 综合维度 main_tag=0');

const albumEnv = await save('album', api.album(albumId), `album id=${albumId}`);
const photoId = albumEnv?.data?.series?.[0]?.id ?? albumId;
await save('chapter', api.chapter(photoId), `chapter photoId=${photoId}`);
await save('forum', api.forum(albumId, 1), `forum aid=${albumId} p1`);

const weekEnv = await save('week', api.week(), '期数列表');
const weekId = weekEnv?.data?.categories?.[0]?.id;
if (weekId) {
  // 注意：/week/filter 返回的是 data.list（不是 content），与客户端读取的键不一致。
  // 这里不生成 fixture，只提示——修完客户端再决定要不要加这份回归。
  const wk = await api.weekFilter(weekId, 1);
  const keys = Object.keys(wk.envelope?.data ?? {});
  console.log(`\n  提示：/week/filter 的 data 键是 [${keys.join(', ')}]，` +
    `客户端 JmRepository.weeklyContent 读的是 data.content —— 键名不一致，请先修客户端。`);
}

console.log('─'.repeat(78));
console.log(`共 ${written.length} 份 fixture${DRY ? '（未落盘）' : ' 已写入'}；` +
  `其中 ${written.filter((w) => w.wasSynthetic).length} 份原本是手写样本。`);

if (!DRY) {
  console.log('\n下一步：');
  console.log('  1. 跑 `./gradlew :app:testDebugUnitTest` —— 断言里写死的 id / total / 作者名大概率会红');
  console.log('  2. 按新数据更新 JmDtoFixtureTest.kt 的断言值（只改值，不要放宽成「非空即可」）');
  console.log('  3. 把 tools/.jm-shape-baseline.json 一起更新：node tools/jm-shape-probe.mjs --update');
}
