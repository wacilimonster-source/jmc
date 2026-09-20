#!/usr/bin/env node
/**
 * DTO 契约校验 —— 把「Kotlin DTO 声明的类型」与「线上实测形态」对账。
 *
 * 为什么需要它（2026-09-21 真机事故的根因）：
 *   jm-shape-probe.mjs 能发现「线上形态变了」，但发现不了「DTO 声明跟线上不符」——
 *   因为它的基线本来就录的是线上形态，两边永远一致。
 *   于是 app_shunts（线上是对象数组、DTO 写成 List<String>）这种错误，
 *   shape-probe 报「无漂移」、单测也绿（fixture 是手写的错形状），
 *   一直潜伏到真机上 /setting 整体解析失败、域名自愈静默瘫痪。
 *
 * 本脚本直接解析 JmModels.kt 的 data class 声明，推导每个字段的期望 JSON 类型，
 * 与 tools/.jm-shape-baseline.json 逐字段比对。类型不符 → 退出码 1。
 *
 * 用法：
 *   node tools/jm-dto-contract.mjs            # 与基线对账
 *   node tools/jm-dto-contract.mjs --verbose  # 打印所有字段（含通过项）
 *   node tools/jm-dto-contract.mjs --dto <path>  # 指定另一份 JmModels.kt（负向控制用）
 *
 * 前置：先 `node tools/jm-shape-probe.mjs --update` 刷新基线，再跑本脚本。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.join(__dirname, '..');
const BASELINE_FILE = path.join(__dirname, '.jm-shape-baseline.json');
const ARGV = process.argv.slice(2);
const VERBOSE = ARGV.includes('--verbose');
/** --dto <path>：指向另一份 JmModels.kt（用于负向控制测试：验证本脚本真能抓到类型不符） */
const dtoArgIdx = ARGV.indexOf('--dto');
const DTO_FILE = dtoArgIdx >= 0 && ARGV[dtoArgIdx + 1]
  ? path.resolve(ARGV[dtoArgIdx + 1])
  : path.join(ROOT, 'app/src/main/java/com/jmread/network/JmModels.kt');

// ---------- 1. 解析 JmModels.kt ----------

/** 读取 data class 的构造参数（跳过类体内的 get() 计算属性） */
function parseDtoClasses(source) {
  const classes = new Map();
  const lines = source.split(/\r?\n/);

  for (let i = 0; i < lines.length; i++) {
    const m = /^\s*(?:internal\s+)?data class\s+(\w+)\s*\(/.exec(lines[i]);
    if (!m) continue;
    const name = m[1];

    // 从 '(' 开始收集到括号闭合，得到构造参数文本
    let depth = 0;
    let started = false;
    let body = '';
    for (let j = i; j < lines.length; j++) {
      for (const ch of lines[j]) {
        if (ch === '(') {
          depth += 1;
          if (depth === 1) {
            started = true;
            continue;
          }
        } else if (ch === ')') {
          depth -= 1;
          if (depth === 0) {
            started = false;
            break;
          }
        }
        if (started) body += ch;
      }
      if (!started && depth === 0) break;
      body += '\n';
    }

    classes.set(name, parseParams(body));
  }
  return classes;
}

/** 在深度 0 处按逗号切分构造参数 */
function parseParams(body) {
  const parts = [];
  let depth = 0;
  let cur = '';
  for (const ch of body) {
    if ('<(['.includes(ch)) depth += 1;
    else if ('>)]'.includes(ch)) depth -= 1;
    if (ch === ',' && depth === 0) {
      parts.push(cur);
      cur = '';
      continue;
    }
    cur += ch;
  }
  if (cur.trim()) parts.push(cur);

  const params = [];
  for (const raw of parts) {
    const text = raw.trim();
    if (!text) continue;
    const sn = /@SerialName\("([^"]+)"\)/.exec(text);
    const prop = /(?:override\s+)?val\s+(\w+)\s*:\s*([^=]+)/.exec(text);
    if (!prop) continue;
    params.push({
      jsonKey: sn ? sn[1] : prop[1],
      kotlinName: prop[1],
      type: prop[2].trim(),
      // 宽松序列化器：数字/字符串都能读成 String（见 JmFlexibleStringSerializer）
      flexible: /@Serializable\(with\s*=\s*JmFlexibleStringSerializer::class\)/.test(text),
    });
  }
  return params;
}

/** Kotlin 类型 -> 期望的 JSON 类型；返回 {kind, elem, nullable} */
function classify(kt) {
  const nullable = kt.trim().endsWith('?');
  const t = kt.trim().replace(/\?$/, '').trim();
  const list = /^(?:List|Set|Collection|Array)<(.+)>$/.exec(t);
  if (list) return { kind: 'array', elem: list[1].trim(), nullable };
  if (t === 'String' || t === 'Char') return { kind: 'string', nullable };
  if (['Int', 'Long', 'Short', 'Byte', 'Double', 'Float'].includes(t)) return { kind: 'number', nullable };
  if (t === 'Boolean') return { kind: 'boolean', nullable };
  return { kind: 'object', cls: t, nullable };
}

// ---------- 2. 端点 -> 根 DTO ----------

const ENDPOINTS = [
  { label: 'setting', cls: 'JmSettingData' },
  { label: 'categories', cls: 'JmCategoriesData' },
  { label: 'list', cls: 'JmListData' },
  { label: 'search', cls: 'JmListData' },
  { label: 'weekFilter', cls: 'JmListData' },
  { label: 'album', cls: 'JmAlbumDetail' },
  { label: 'chapter', cls: 'JmChapterData' },
  { label: 'forum', cls: 'JmForumData' },
  { label: 'week', cls: 'JmWeekData' },
];

// ---------- 3. 递归比对 ----------

const pad = (s, n) => String(s ?? '').padEnd(n);
const members = (union) => (union ? union.split('|') : []);

/**
 * 允许的线上类型集合 —— 依据 app/src/test/.../KotlinxCoercionSemanticsTest.kt 实测：
 *   数字  -> String   抛异常（唯一会炸的方向，必须严格）
 *   字符串-> Int/Long ✓ 自动转
 *   字符串-> Boolean   ✓ 自动转
 * 所以数字/布尔字段可以容忍线上给字符串；String 字段不能容忍线上给数字。
 */
function allowedFor(c) {
  const base = [c.kind];
  if (c.kind === 'number' || c.kind === 'boolean') base.push('string');
  if (c.nullable) base.push('null');
  return base;
}

/** 声明为 String 但带宽松序列化器：数字/字符串都读得进来 */
function allowedForParam(p) {
  const c = classify(p.type);
  const base = p.flexible ? ['string', 'number', 'boolean'] : allowedFor(c);
  if (c.nullable && !base.includes('null')) base.push('null');
  return base;
}

function checkClass(cls, prefix, classes, shapes, issues, trail) {
  if (trail.includes(cls)) return; // 防自引用死循环
  const params = classes.get(cls);
  if (!params) {
    issues.push({ level: 'ERR', path: prefix, msg: `找不到 DTO 类 ${cls} 的声明` });
    return;
  }

  for (const p of params) {
    const fieldPath = prefix ? `${prefix}.${p.jsonKey}` : p.jsonKey;
    const c = classify(p.type);

    if (c.kind === 'array') {
      const arrKey = `${fieldPath}[]`;
      const union = shapes[arrKey];
      if (!union) {
        issues.push({
          level: 'WARN', path: fieldPath,
          msg: `声明 ${p.type}，但基线里没有该字段（服务端可能已下线，靠默认值兜底）`,
        });
        continue;
      }
      const ms = members(union);
      if (!ms.includes('array')) {
        issues.push({ level: 'ERR', path: fieldPath, msg: `声明 ${p.type}，线上却是 ${union}` });
        continue;
      }
      const elem = classify(c.elem);
      const children = Object.keys(shapes).filter((k) => k.startsWith(`${arrKey}.`));
      const stray = ms.filter((m) => m !== 'array' && m !== 'null');
      // 线上该数组为空：元素类型无从判定（album.images/works/actors 常态为空），只提示不报错
      const undecidable = ms.length === 1 && ms[0] === 'array' && !children.length;
      if (undecidable) {
        issues.push({ level: 'WARN', path: fieldPath, msg: `声明 ${p.type}，线上该数组为空，元素类型无法判定` });
        continue;
      }
      if (elem.kind === 'object') {
        // 对象元素在摊平结果里不会有 `path[]` 这个标量条目，只有子路径
        if (stray.length) {
          issues.push({
            level: 'ERR', path: fieldPath,
            msg: `声明 ${p.type}（对象元素），线上却是 ${stray.join('/')}`,
          });
          continue;
        }
        checkClass(elem.cls, arrKey, classes, shapes, issues, [...trail, cls]);
      } else {
        // 元素是标量：该标量类型必须真的出现在线上并集里。
        // 这一条正是 2026-09-21 事故的检出点：声明 List<String> 而线上是对象数组时，
        // 并集只有 'array'（对象元素不产生标量条目），'string' 缺席 → 报错。
        const missing = allowedFor(elem).filter((m) => m !== 'null' && !ms.includes(m));
        if (missing.length) {
          issues.push({
            level: 'ERR', path: fieldPath,
            msg: `声明 ${p.type}，线上元素却是 ${ms.filter((m) => m !== 'array').join('/') || '对象'}` +
              `${children.length ? '（对象数组）' : ''}`,
          });
        } else if (VERBOSE) {
          issues.push({ level: 'OK', path: fieldPath, msg: `${p.type} ~ ${union}` });
        }
      }
      continue;
    }

    const union = shapes[fieldPath];
    const children = Object.keys(shapes).filter((k) => k.startsWith(`${fieldPath}.`));
    if (!union && !children.length) {
      issues.push({
        level: 'WARN', path: fieldPath,
        msg: `声明 ${p.type}，但基线里没有该字段（服务端可能已下线，靠默认值兜底）`,
      });
      continue;
    }
    if (c.kind === 'object') {
      // 对象字段在摊平结果里没有自己的条目，只有 `path.xxx` 子路径
      if (union) {
        issues.push({ level: 'ERR', path: fieldPath, msg: `声明 ${p.type}（对象），线上却是标量 ${union}` });
        continue;
      }
      checkClass(c.cls, fieldPath, classes, shapes, issues, [...trail, cls]);
      continue;
    }
    const ms = members(union);
    const bad = ms.filter((m) => !allowedForParam(p).includes(m));
    if (bad.length) {
      issues.push({ level: 'ERR', path: fieldPath, msg: `声明 ${p.type}，线上却是 ${union}` });
    } else if (VERBOSE) {
      issues.push({ level: 'OK', path: fieldPath, msg: `${p.type} ~ ${union}` });
    }
  }
}

// ---------- 4. main ----------

const classes = parseDtoClasses(fs.readFileSync(DTO_FILE, 'utf8'));
const baseline = JSON.parse(fs.readFileSync(BASELINE_FILE, 'utf8')).shapes ?? {};

console.log('DTO 契约校验（JmModels.kt 声明 vs 线上实测基线）');
console.log(`DTO 类 ${classes.size} 个 · 基线端点 ${Object.keys(baseline).length} 个`);
console.log('─'.repeat(78));

let errors = 0;
let warns = 0;

for (const { label, cls } of ENDPOINTS) {
  const shapes = baseline[label];
  if (!shapes) {
    console.log(`\n[${label}] 基线缺失，跳过`);
    continue;
  }
  const issues = [];
  checkClass(cls, 'data', classes, shapes, issues, []);
  const bad = issues.filter((i) => i.level !== 'OK');
  if (!bad.length) {
    if (VERBOSE) console.log(`\n[${label}] ${cls} 全部字段类型一致`);
    continue;
  }
  console.log(`\n[${label}]  (${cls})`);
  for (const i of issues) {
    if (i.level === 'OK' && !VERBOSE) continue;
    const tag = i.level === 'ERR' ? '~ 类型不符' : '? 基线缺字段';
    console.log(`  ${pad(tag, 12)} ${pad(i.path, 48)} ${i.msg}`);
    if (i.level === 'ERR') errors += 1;
    else warns += 1;
  }
}

console.log('\n' + '─'.repeat(78));
if (errors === 0) {
  console.log(`无 DTO 类型不符（${warns} 处基线缺字段，靠默认值兜底，属可接受）。`);
  process.exit(0);
}
console.log(`发现 ${errors} 处 DTO 声明与线上形态不符 —— 必须改 DTO 类型，不要放宽解析。`);
process.exit(1);
