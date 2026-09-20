#!/usr/bin/env node
/**
 * 禁漫移动端 API 公共协议库 —— tools/ 下各金丝雀脚本共用。
 *
 * ⚠ 本文件是 App 网络层的「等价实现」，不是 App 的一部分。
 *   修改 app/src/main/java/com/jmread/network/JmCrypto.kt 或 JmClient.kt 的
 *   签名/解密/请求头逻辑时，必须同步改这里，否则金丝雀会给出错误结论。
 *   对应关系：
 *     JmCrypto.sign()          <-> sign()
 *     JmCrypto.decrypt()       <-> decryptData()
 *     JmCrypto.imageUrl()      <-> imageUrl() / coverUrl()
 *     JmClient.execute()       <-> JmApi.request()
 *     JmClient.headers         <-> FIXED_HEADERS
 *     DomainPool.BUILTIN_*     <-> BUILTIN_API_HOSTS / BUILTIN_IMAGE_HOSTS
 *
 * 协议依据：docs/tech-architecture.md §3（2026-09-19 线上实测）
 *
 * 用法（被 import，不直接执行）：
 *   import { JmApi, sign, decryptData } from './jm-lib.mjs';
 */
import crypto from 'node:crypto';

/** 签名密钥（与 JmCrypto.APP_SECRET 一致） */
export const APP_SECRET = '185Hcomic3PAPP7R';

/** 版本号兜底值（与 JmCrypto.FALLBACK_VERSION 一致；运行时以 /setting.jm3_version 为准） */
export const FALLBACK_VERSION = '2.1.8';

/** 编译期内置镜像（与 DomainPool.BUILTIN_API_HOSTS 一致） */
export const BUILTIN_API_HOSTS = [
  'www.cdngwc.cc',
  'www.cdnhjk.net',
  'www.cdngwc.net',
  'www.cdngwc.club',
];

/** 图片 CDN host 候选（与 DomainPool.BUILTIN_IMAGE_HOSTS 一致） */
export const BUILTIN_IMAGE_HOSTS = [
  'cdn-msp.jmapiproxy1.cc',
  'cdn-msp.jmapiproxy2.cc',
  'cdn-msp2.jmapiproxy2.cc',
  'cdn-msp3.jmapiproxy2.cc',
  'cdn-msp.jmapinodeudzn.net',
  'cdn-msp3.jmapinodeudzn.net',
  'cdn-msp.jmdanjonproxy.xyz',
];

/** 固定请求头（与 JmClient.headers 一致） */
export const FIXED_HEADERS = {
  device: 'ANDROID;9.0;SMR;unknown;deadbeef12345678;2.1.3',
  'os-version': '9.0',
  platform: 'ANDROID',
  'app-version': '3.4.17',
  channel: 'app',
  'User-Agent': 'okhttp/3.12.0 leak(200.0);Android version:9.0;MAX2;100;jmc;3.23.0',
};

/** 浏览流页大小（实测 80） */
export const PAGE_SIZE = 80;
/** 浏览流页数硬上限（实测第 126 页起服务器原样重发第 125 页） */
export const BROWSE_PAGE_LIMIT = 125;
/** 浏览流 total 哨兵值（恒为 10000，不可信） */
export const BROWSE_TOTAL_SENTINEL = 10000;

/** 携带 HTTP 状态码的结构化错误（对应 JmException） */
export class JmError extends Error {
  constructor(message, httpCode = 0) {
    super(message);
    this.name = 'JmError';
    this.httpCode = httpCode;
  }
}

export const md5 = (s) => crypto.createHash('md5').update(s, 'utf8').digest('hex');

/**
 * 生成请求签名。ts 为秒级时间戳，token = md5(ts + APP_SECRET)。
 * 解密也必须用同一个 ts，所以调用方要把 ts 一路带下去。
 */
export function sign(version = FALLBACK_VERSION) {
  const ts = Math.floor(Date.now() / 1000);
  return { ts, token: md5(`${ts}${APP_SECRET}`), tokenparam: `${ts},${version}` };
}

/**
 * base64 -> AES-256-ECB -> 去 PKCS7 padding。
 * key 是 md5(ts + APP_SECRET) 的 32 字符小写 hex（当 UTF-8 字节用，正好 32 字节）。
 * 与 JmCrypto.decrypt() 逐行对应（含 padding 合法性校验）。
 */
export function decryptData(base64, ts) {
  const key = Buffer.from(md5(`${ts}${APP_SECRET}`), 'utf8');
  const raw = Buffer.from(base64, 'base64');
  if (raw.length === 0 || raw.length % 16 !== 0) {
    throw new Error(`密文长度非法：${raw.length}（应为 16 的整数倍）`);
  }
  const decipher = crypto.createDecipheriv('aes-256-ecb', key, null);
  decipher.setAutoPadding(false);
  const out = Buffer.concat([decipher.update(raw), decipher.final()]);
  const pad = out[out.length - 1];
  if (pad < 1 || pad > 16 || pad > out.length) {
    throw new Error(`解密失败：padding 非法 (pad=${pad}, size=${out.length})，密钥或时间戳可能漂移`);
  }
  return out.subarray(0, out.length - pad).toString('utf8');
}

/** 章节图片直链（与 JmCrypto.imageUrl 一致） */
export function imageUrl(imgHost, photoId, filename) {
  return `https://${imgHost}/media/photos/${photoId}/${filename}`;
}

/** 封面直链（与 JmCrypto.coverUrl / coverUrlHd 一致） */
export function coverUrl(imgHost, albumId, hd = false) {
  return `https://${imgHost}/media/albums/${albumId}${hd ? '_3x4' : ''}.jpg`;
}

/** 列表条目的 id 提取（浏览/搜索/排行共用 data.content[]） */
export function firstId(envelope) {
  const list = envelope?.data?.content;
  return Array.isArray(list) && list.length ? String(list[0].id) : '';
}

/**
 * 禁漫 API 客户端（等价于 JmClient.execute 的失败换域引擎）。
 *
 * 不做限流冷却——实测禁漫 20 连发零 429，主要断服原因是镜像域名轮换。
 */
export class JmApi {
  constructor(opts = {}) {
    this.hosts = [...(opts.hosts ?? BUILTIN_API_HOSTS)];
    this.hostIdx = 0;
    this.version = opts.version ?? FALLBACK_VERSION;
    this.session = opts.session ?? '';
    this.timeoutMs = opts.timeoutMs ?? 20000;
    this.verbose = opts.verbose ?? false;
    this.requestCount = 0;
    this.hostStats = new Map();
  }

  get host() {
    return this.hosts[this.hostIdx];
  }

  get baseUrl() {
    return `https://${this.host}`;
  }

  /** 已用过的 host -> 成功/失败次数，供报告展示 */
  stat(host, ok) {
    const s = this.hostStats.get(host) ?? { ok: 0, fail: 0 };
    if (ok) s.ok += 1;
    else s.fail += 1;
    this.hostStats.set(host, s);
  }

  /**
   * 单次逻辑请求：按 host 顺序逐个尝试，网络错误/5xx/429/非 JSON 响应都触发换域。
   * 业务异常（其余 4xx）不换域，直接抛——换域改变不了业务错误。
   */
  async request(path, { form = null, method = null, noDecrypt = false } = {}) {
    let lastErr;
    for (let i = 0; i < this.hosts.length; i++) {
      const idx = (this.hostIdx + i) % this.hosts.length;
      const host = this.hosts[idx];
      const { ts, token, tokenparam } = sign(this.version);
      const headers = { ...FIXED_HEADERS, token, tokenparam };
      if (this.session) headers.Cookie = `AVS=${this.session}`;
      const ac = new AbortController();
      const timer = setTimeout(() => ac.abort(), this.timeoutMs);
      try {
        const init = { method: method ?? (form ? 'POST' : 'GET'), headers, signal: ac.signal };
        if (form) {
          headers['Content-Type'] = 'application/x-www-form-urlencoded';
          init.body = new URLSearchParams(form).toString();
        }
        const res = await fetch(`https://${host}${path}`, init);
        const text = await res.text();
        // 5xx / 429 = 服务端或网关故障 -> 换域重试（与 JmClient.isRetryableHttpStatus 一致）
        // 实测 2026-09-21：/search 在 4 个内置镜像上随机 500（空体），同时必有镜像 200。
        if (res.status >= 500 || res.status === 429) {
          throw new Error(`HTTP ${res.status}（服务端故障，换域重试）`);
        }
        // 其余 4xx = 业务错误，换域改变不了结果
        if (res.status >= 400) {
          throw new JmError(`HTTP ${res.status}: ${text.slice(0, 120)}`, res.status);
        }
        if (text.trimStart()[0] !== '{') {
          throw new Error(`响应非 JSON（疑似风控页/网关异常）：${text.trimStart().slice(0, 60)}`);
        }
        const envelope = JSON.parse(text);
        if (!noDecrypt && typeof envelope.data === 'string') {
          envelope.data = JSON.parse(decryptData(envelope.data, ts));
        }
        this.hostIdx = idx;
        this.requestCount += 1;
        this.stat(host, true);
        return { host, envelope, http: res.status, ts, bytes: text.length };
      } catch (e) {
        if (e instanceof JmError) throw e;
        lastErr = e;
        this.stat(host, false);
        if (this.verbose) console.error(`  [换域] ${host} 失败：${e.message}`);
      } finally {
        clearTimeout(timer);
      }
    }
    throw lastErr ?? new Error('禁漫接口不可用：所有线路均失败');
  }

  /** GET 便捷方法，失败不抛（用于负向控制探测） */
  async tryGet(path) {
    try {
      return { ok: true, ...(await this.request(path)) };
    } catch (e) {
      return { ok: false, error: e };
    }
  }

  async setting() {
    return this.request('/setting');
  }

  async categories() {
    return this.request('/categories');
  }

  async browse(page = 1, { category = null, o = 'mr' } = {}) {
    const q = new URLSearchParams({ page: String(page), t: 'a', o });
    if (category) q.set('c', category);
    return this.request(`/categories/filter?${q}`);
  }

  async rank(o = 'mv_t', page = 1) {
    return this.request(`/categories/filter?${new URLSearchParams({ page: String(page), o, t: 'a' })}`);
  }

  async search(keyword, { page = 1, mainTag = 0, o = 'mr', category = null } = {}) {
    const q = new URLSearchParams({ search_query: keyword, page: String(page), main_tag: String(mainTag), t: 'a', o });
    if (category) q.set('c', category);
    return this.request(`/search?${q}`);
  }

  async album(albumId) {
    return this.request(`/album?${new URLSearchParams({ id: albumId })}`);
  }

  async chapter(photoId) {
    return this.request(`/chapter?${new URLSearchParams({ id: photoId })}`);
  }

  async forum(albumId, page = 1) {
    return this.request(`/forum?${new URLSearchParams({ mode: 'all', page: String(page), aid: albumId })}`);
  }

  async week() {
    return this.request('/week');
  }

  async weekFilter(weekId, page = 1) {
    return this.request(`/week/filter?${new URLSearchParams({ id: weekId, page: String(page), t: 'a' })}`);
  }

  async login(username, password) {
    const r = await this.request('/login', { form: { username, password } });
    return { ...r, session: r.envelope?.data?.s ?? '' };
  }

  /** 已登录端点（能力未验证，仅探测用） */
  async favorites(page = 1) {
    return this.request(`/favorite?${new URLSearchParams({ page: String(page), folder_id: '0', o: 'mr' })}`);
  }

  async daily() {
    return this.request('/daily');
  }

  async watchList(page = 1) {
    return this.request(`/watch_list?${new URLSearchParams({ page: String(page) })}`);
  }
}

/** 离线自检：不联网验证签名与加解密逻辑自身是否自洽 */
export function selftest() {
  const results = [];
  const add = (name, ok, detail = '') => results.push({ name, ok, detail });

  // 1. md5 已知向量
  add('md5("") 已知向量', md5('') === 'd41d8cd98f00b204e9800998ecf8427e', md5(''));
  add('md5("abc") 已知向量', md5('abc') === '900150983cd24fb0d6963f7d28e17f72', md5('abc'));

  // 2. 签名形态：token 是 32 位小写 hex，tokenparam 是 "ts,version"
  const s = sign('2.1.8');
  add('token 为 32 位小写 hex', /^[0-9a-f]{32}$/.test(s.token), s.token);
  add('token 与 ts 一致', s.token === md5(`${s.ts}${APP_SECRET}`), `ts=${s.ts}`);
  add('tokenparam 形态正确', s.tokenparam === `${s.ts},2.1.8`, s.tokenparam);

  // 3. 加解密往返：用同一套 key 自己加密再解回来
  const ts = s.ts;
  const key = Buffer.from(md5(`${ts}${APP_SECRET}`), 'utf8');
  add('派生 key 为 32 字节', key.length === 32, `${key.length} 字节`);
  const plain = JSON.stringify({ content: [{ id: '441295', name: '往返测试' }], total: 10000 });
  const padLen = 16 - (Buffer.byteLength(plain) % 16);
  const padded = Buffer.concat([Buffer.from(plain, 'utf8'), Buffer.alloc(padLen, padLen)]);
  const cipher = crypto.createCipheriv('aes-256-ecb', key, null);
  cipher.setAutoPadding(false);
  const b64 = Buffer.concat([cipher.update(padded), cipher.final()]).toString('base64');
  const back = decryptData(b64, ts);
  add('AES-256-ECB 往返一致', back === plain, back === plain ? '' : back.slice(0, 60));

  // 4. 错误 ts 必须解密失败（否则说明 padding 校验形同虚设）
  let rejected = false;
  try {
    decryptData(b64, ts + 1);
  } catch {
    rejected = true;
  }
  add('错误 ts 被 padding 校验拒绝', rejected);

  return results;
}
