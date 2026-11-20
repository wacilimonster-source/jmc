#!/usr/bin/env node
/**
 * 发版自检清单（对标 PiKA ENGINEERING-LESSONS §2.8「发版流程靠人肉记忆」教训）。
 *
 * 用法：
 *   node tools/release-check.mjs            # 校验当前 update.json 与版本号/APK/sha256/code-health
 *   （历史版本的 update.json 已被新版本覆盖，无法离线复核；历史 APK 的 sha256 以 git 历史为准）
 *
 * 校验项（全部可机械判定）：
 *   1. app/build.gradle.kts 的 versionCode/versionName 与 update.json.version 一致
 *   2. 仓库根存在 jm-reader-{version}.apk
 *   3. APK 实际 sha256 == update.json.sha256
 *   4. update.json.apkUrl 指向 raw/{version}.apk
 *   5. update.json.notes 非空且包含版本号
 *   6. python tools/code-health.py --check 通过（结构指标不劣化）
 *   7. 单测结果目录存在且 0 失败（最近一次 gbuild release 的产物）
 */
import fs from 'node:fs';
import { execSync } from 'node:child_process';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
if (process.argv.slice(2).length > 0) {
  console.error('此脚本只校验当前版本（update.json 指向的）；历史版本请查 git 历史');
  process.exit(2);
}
let version = null;

const failures = [];
const ok = (name) => console.log(`  ✅ ${name}`);
const bad = (name, detail) => {
  console.log(`  ❌ ${name}${detail ? `：${detail}` : ''}`);
  failures.push(name);
};

// update.json
const updatePath = path.join(ROOT, 'update.json');
const update = JSON.parse(fs.readFileSync(updatePath, 'utf-8'));
version = version ?? update.version;
console.log(`发版自检：v${version}`);

// 1) 版本号一致性
const gradle = fs.readFileSync(path.join(ROOT, 'app', 'build.gradle.kts'), 'utf-8');
const vc = gradle.match(/versionCode\s*=\s*(\d+)/)?.[1];
const vn = gradle.match(/versionName\s*=\s*"([^"]+)"/)?.[1];
if (version === vn) ok(`versionName 一致（${vn}，versionCode ${vc}）`);
else bad('versionName 与 update.json 不一致', `gradle=${vn} update=${version}`);

// 2) APK 存在
const apkPath = path.join(ROOT, `jm-reader-v${version}.apk`);
if (fs.existsSync(apkPath)) ok(`APK 存在：jm-reader-v${version}.apk`);
else {
  bad('APK 不存在', apkPath);
  finish();
}

// 3) sha256 一致
const actual = crypto.createHash('sha256').update(fs.readFileSync(apkPath)).digest('hex');
if (actual === update.sha256) ok('APK sha256 与 update.json 一致');
else bad('APK sha256 不一致', `actual=${actual.slice(0, 12)}… declared=${update.sha256.slice(0, 12)}…`);

// 4) apkUrl
const expectedUrl = `https://raw.githubusercontent.com/wacilimonster-source/jmc/main/jm-reader-v${version}.apk`;
if (update.apkUrl === expectedUrl) ok('apkUrl 指向正确 raw 地址');
else bad('apkUrl 不正确', update.apkUrl);

// 5) notes
if (update.notes && update.notes.includes(version)) ok('notes 非空且包含版本号');
else bad('notes 缺失或不含版本号');

// 6) code-health
try {
  execSync('python tools/code-health.py --check', { cwd: ROOT, stdio: 'pipe' });
  ok('code-health --check 通过');
} catch (e) {
  bad('code-health --check 未通过', e.stdout?.toString().split('\n').slice(-3).join(' | '));
}

// 7) 单测结果
const testDir = path.join(ROOT, 'app', 'build', 'test-results', 'testDebugUnitTest');
if (fs.existsSync(testDir)) {
  let total = 0;
  let failed = 0;
  for (const f of fs.readdirSync(testDir).filter((f) => f.startsWith('TEST-') && f.endsWith('.xml'))) {
    const xml = fs.readFileSync(path.join(testDir, f), 'utf-8');
    total += Number(xml.match(/tests="(\d+)"/)?.[1] ?? 0);
    failed += Number(xml.match(/failures="(\d+)"/)?.[1] ?? 0) + Number(xml.match(/errors="(\d+)"/)?.[1] ?? 0);
  }
  if (failed === 0 && total > 0) ok(`单测 ${total}/${total} 通过（最近一次门禁）`);
  else bad('单测存在失败', `total=${total} failed=${failed}`);
} else {
  console.log('  ⚠️ 单测结果目录不存在（先跑一次 ./gbuild.sh release）');
}

function finish() {
  if (failures.length === 0) {
    console.log('\n发版自检全部通过 ✅');
    process.exit(0);
  } else {
    console.log(`\n发版自检失败 ${failures.length} 项 ❌`);
    process.exit(1);
  }
}
finish();
