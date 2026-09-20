#!/usr/bin/env node
/**
 * 一次性探针：定位 /setting 在真机上解析失败的原因。
 *
 * 背景：真机日志报
 *   /setting 刷新失败：Unexpected JSON token at offset 1591:
 *   Expected beginning of the string, but got ...
 * 说明 JmSettingData 六个非空 String 字段里，至少有一个线上实际不是字符串。
 *
 * 本脚本把 /setting 的每个字段的「实际 JSON 类型」打出来，
 * 并单独标出 DTO 已声明的六个字段。
 */
import { JmApi } from '../jm-lib.mjs';

const DECLARED = {
  base_url: 'String',
  cn_base_url: 'String',
  main_web_host: 'String',
  img_host: 'String',
  jm3_version: 'String',
  app_shunts: 'List<String>',
};

const typeOf = (v) => {
  if (v === null) return 'null';
  if (Array.isArray(v)) {
    if (v.length === 0) return 'array<empty>';
    return `array<${typeOf(v[0])}>`;
  }
  return typeof v;
};

const main = async () => {
  const api = new JmApi({ verbose: true });
  const { host, envelope } = await api.setting();
  const data = envelope.data ?? envelope;
  console.log(`host=${host}`);
  console.log(`top-level keys: ${Object.keys(envelope).join(', ')}`);
  if (envelope.data && typeof envelope.data === 'object') {
    console.log(`data keys (${Object.keys(data).length}):`);
    let mismatch = 0;
    for (const [k, v] of Object.entries(data)) {
      const t = typeOf(v);
      const decl = DECLARED[k];
      let flag = '';
      if (decl) {
        const expected = decl === 'List<String>' ? 'array' : 'string';
        const actual = t.startsWith('array') ? 'array' : t;
        if (expected !== actual) {
          flag = `  <== 与 DTO 声明(${decl})不符`;
          mismatch += 1;
        } else {
          flag = '  [DTO 声明字段，类型一致]';
        }
      }
      console.log(`  ${k}: ${t}${flag}`);
    }
    console.log(`\n声明字段类型不符数量：${mismatch}`);
    console.log(`\n=== 完整 JSON（前 3000 字符）===`);
    console.log(JSON.stringify(data, null, 2).slice(0, 3000));
  } else {
    console.log('data 不是对象：', typeof envelope.data);
    console.log(JSON.stringify(envelope).slice(0, 3000));
  }
};

main().catch((e) => {
  console.error('探针失败：', e);
  process.exit(1);
});
