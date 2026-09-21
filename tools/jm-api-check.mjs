#!/usr/bin/env node
/**
 * 禁漫 API 金丝雀 —— 端点存活 + 关键不变量 + 能力位复核
 *
 * 目的：发版前跑一遍，回答三个问题
 *   1. 四个内置镜像和全部读端点今天还活着吗？
 *   2. 那几条「产品设计依赖的实测结论」还成立吗？
 *      （浏览流 80/页、125 页硬上限、total 恒 10000、四维搜索各有真实 total、
 *        t= 参数无效、/random 不存在……）
 *   3. JmCapabilities.kt 里写死的常量与今天线上是否一致？
 *
 * 用法：
 *   node tools/jm-api-check.mjs                 # 常规巡检（约 15 个请求）
 *   node tools/jm-api-check.mjs --deep          # 额外验证 125/126 页与 t= 四取值（+6 个请求）
 *   node tools/jm-api-check.mjs --verbose       # 打印换域过程
 *   node tools/jm-api-check.mjs --selftest-only # 只跑离线自检，不联网
 *
 * 退出码：0 = 全部通过；1 = 有断言失败（说明产品设计的前提变了，需要人工复核）
 *
 * 注意：本脚本只读，只发 GET（登录探测除外，默认不跑），不下载任何图片。
 */
import {
  BROWSE_PAGE_LIMIT,
  BROWSE_TOTAL_SENTINEL,
  JmApi,
  PAGE_SIZE,
  firstId,
  selftest,
} from './jm-lib.mjs';

const argv = new Set(process.argv.slice(2));
const DEEP = argv.has('--deep');
const VERBOSE = argv.has('--verbose');
const SELFTEST_ONLY = argv.has('--selftest-only');
const AS_JSON = argv.has('--json');

const LINE = '─'.repeat(78);
const checks = [];
const check = (name, ok, detail = '') => checks.push({ name, ok, detail });
/**
 * 信息项：只报告状态、不计入通过率。
 * 用于「已知死档位是否恢复」这类双向都算正常、但需要人看一眼的信号。
 */
const notes = [];
const note = (name, detail = '') => notes.push({ name, detail });
const pad = (s, n) => String(s ?? '').padEnd(n);
const num = (n) => (typeof n === 'number' ? n.toLocaleString('en-US') : String(n ?? '-'));

/** 章节图片文件名是否从 00001 连续递增且扩展名一致（下载/预取依赖这个顺序） */
function filenamesContinuous(names) {
  if (names.length === 0) return false;
  if (names.length === 1) return /^0*1\.[A-Za-z0-9]+$/.test(names[0]);
  const ext = names[0].match(/\.[A-Za-z0-9]+$/)?.[0] ?? '';
  return names.every((n, i) => n === String(i + 1).padStart(5, '0') + ext);
}

/**
 * 找出 data 里真正装列表的那个键 + 条数。
 * ⚠ 这个函数存在的意义：服务端不同端点用 content / list 两种键名，
 *   而 DTO 里两者都有 `= emptyList()` 默认值 —— 键名写错时反序列化**不报错**，
 *   只静默变成空列表。必须靠这里的显式识别把漂移暴露出来。
 */
const KNOWN_ARRAY_KEYS = ['content', 'list', 'categories', 'series', 'images'];

function arrayInfo(data) {
  if (Array.isArray(data)) return { key: '（裸数组）', count: data.length };
  if (!data || typeof data !== 'object') return { key: '（非对象）', count: undefined };
  const known = KNOWN_ARRAY_KEYS.filter((k) => Array.isArray(data[k]));
  const key = known.find((k) => data[k].length > 0)
    ?? known[0]
    ?? Object.keys(data).find((k) => Array.isArray(data[k]));
  if (!key) return { key: '（无数组）', count: undefined };
  return { key, count: data[key].length };
}

/** 服务端对不存在的端点返回 HTTP 200 + code 200 + data=[] + errorMsg="Not legal.xxx" */
const isNotLegal = (envelope) => /^Not legal/i.test(String(envelope?.errorMsg ?? ''));

// ─────────────────────────────────────────────────────────────
// 0. 离线自检：先证明签名/解密逻辑自洽，再决定要不要联网
// ─────────────────────────────────────────────────────────────
const selfResults = selftest();
const selfFailed = selfResults.filter((r) => !r.ok);
if (!AS_JSON) {
  console.log('禁漫 API 金丝雀   模式=' + (DEEP ? 'deep' : '常规') + (VERBOSE ? ' +verbose' : ''));
  console.log(LINE);
  console.log('\n[0] 离线自检（签名 / AES-256-ECB / padding 校验）');
  for (const r of selfResults) {
    console.log(`  ${r.ok ? 'OK  ' : 'FAIL'} ${r.name}${r.detail ? '   ' + r.detail : ''}`);
  }
}
if (selfFailed.length) {
  console.log('\n离线自检未通过，协议库本身有问题，停止联网探测。');
  process.exit(1);
}
if (SELFTEST_ONLY) {
  console.log('\n--selftest-only：跳过联网探测。');
  process.exit(0);
}

const api = new JmApi({ verbose: VERBOSE });
const t0 = Date.now();

// ─────────────────────────────────────────────────────────────
// 1. /setting —— 域名自愈的数据源
// ─────────────────────────────────────────────────────────────
const setting = await api.setting();
const sd = setting.envelope?.data ?? {};
check('/setting 返回 code=200', setting.envelope?.code === 200, `code=${setting.envelope?.code}`);
check('/setting 下发 base_url', typeof sd.base_url === 'string' && sd.base_url.length > 0, sd.base_url);
check('/setting 下发 jm3_version', typeof sd.jm3_version === 'string' && sd.jm3_version.length > 0, sd.jm3_version);
check('/setting 下发 img_host', typeof sd.img_host === 'string' && sd.img_host.length > 0, sd.img_host);
check(
  'jm3_version 与代码兜底值一致',
  sd.jm3_version === '2.1.8',
  sd.jm3_version === '2.1.8' ? '' : `线上=${sd.jm3_version}，JmCrypto.FALLBACK_VERSION=2.1.8`,
);

// /setting 下发的域名要能被采纳（否则域名自愈形同虚设）
const settingHost = String(sd.base_url ?? '').replace(/^https?:\/\//, '').replace(/\/$/, '');
if (settingHost && !api.hosts.includes(settingHost)) {
  api.hosts = [settingHost, ...api.hosts];
  if (VERBOSE) console.error(`  [域名池] 采纳 /setting 下发域名 ${settingHost}`);
}

// 版本号以 /setting 为准（与 JmClient.refreshSetting 行为一致）
if (typeof sd.jm3_version === 'string' && sd.jm3_version) api.version = sd.jm3_version;

// ─────────────────────────────────────────────────────────────
// 2. 端点存活表
// ─────────────────────────────────────────────────────────────
if (!AS_JSON) {
  console.log('\n[1] 端点存活');
  console.log('  ' + pad('端点', 38) + pad('code', 6) + pad('数组键', 12) + pad('条数', 7) + '摘要');
  console.log('  ' + '·'.repeat(76));
}

const rows = [];
/**
 * 跑一个探测并把结果记进表。
 * @param pickKey 指定这一行关心哪个数组键；不传则自动识别（自动识别能暴露键名漂移）
 */
async function probe(label, path, fn, pickKey = null) {
  const start = Date.now();
  try {
    const r = await fn();
    const d = r.envelope?.data;
    const auto = arrayInfo(d);
    const key = pickKey ?? auto.key;
    const count = pickKey ? (Array.isArray(d?.[pickKey]) ? d[pickKey].length : undefined) : auto.count;
    const total = d && typeof d === 'object' && !Array.isArray(d) ? d.total : undefined;
    const extra = r.envelope?.errorMsg ? String(r.envelope.errorMsg).slice(0, 24) : '';
    const summary = [total !== undefined ? `total=${num(total)}` : '', extra].filter(Boolean).join('  ');
    rows.push({ label, path, code: r.envelope?.code, key, count, total, ms: Date.now() - start });
    if (!AS_JSON) {
      console.log('  ' + pad(label, 38) + pad(r.envelope?.code, 6) + pad(key, 12) + pad(count ?? '-', 7) + summary);
    }
    return r;
  } catch (e) {
    rows.push({ label, path, error: e.message, ms: Date.now() - start });
    if (!AS_JSON) console.log('  ' + pad(label, 38) + pad('ERR', 6) + pad('-', 12) + pad('-', 7) + e.message.slice(0, 48));
    return null;
  }
}

const browse1 = await probe('/categories/filter 浏览流 p1', '/categories/filter?page=1&t=a&o=mr',
  () => api.browse(1), 'content');

const categories = await probe('/categories 分类树', '/categories', () => api.categories(), 'categories');

const search = await probe('/search 综合维度', '/search?search_query=…&main_tag=0',
  () => api.search('姐姐', { mainTag: 0 }), 'content');

// 排行榜：服务端真正可用的只有 周(mv_w) / 月(mv_m) 两档。
// 日榜 mv_t 恒空 —— 参考实现 jm_config.py 的 ORDER_DAY_RANKING='mv_t' 确认参数没写错，
// 是服务端该档没有数据。App 的「新晋热榜」档位不再直连它，而是由月榜数据本地按
// update_at 重排得到（JmRepository.newArrivals），所以这里只把 mv_t 当信息项观察。
const rankDay = await probe('/categories/filter 日榜 mv_t', '/categories/filter?page=1&o=mv_t&t=a',
  () => api.rank('mv_t'), 'content');
const rankWeek = await probe('/categories/filter 周榜 mv_w', '/categories/filter?page=1&o=mv_w&t=a',
  () => api.rank('mv_w'), 'content');
const rankMonth = await probe('/categories/filter 月榜 mv_m', '/categories/filter?page=1&o=mv_m&t=a',
  () => api.rank('mv_m'), 'content');

// 「新晋热榜」的数据前提：月榜热度池里必须有足够多「最近有更新」的作品。
// 该档位取月榜前 2 页按 update_at 倒序取前 40；池子若全是老作品，这个档位就名不副实。
const monthPool = [];
for (const p of [1, 2]) {
  try {
    const r = await api.rank('mv_m', p);
    monthPool.push(...(r.envelope?.data?.content ?? r.envelope?.data?.list ?? []));
  } catch { /* 单页失败不致命，下面按实际拿到的池子判定 */ }
}
const RECENT_WINDOW_S = 7 * 24 * 3600;
const recentInPool = monthPool.filter((x) => {
  const ts = Number(x?.update_at ?? 0);
  return ts > 0 && Date.now() / 1000 - ts <= RECENT_WINDOW_S;
}).length;

const sampleAlbumId = firstId(browse1?.envelope) || firstId(search?.envelope);
const album = sampleAlbumId
  ? await probe(`/album 详情 id=${sampleAlbumId}`, `/album?id=${sampleAlbumId}`, () => api.album(sampleAlbumId), 'series')
  : null;
if (album && !AS_JSON) {
  const a = album.envelope?.data ?? {};
  console.log(`  ${''.padEnd(38)}      author=${Array.isArray(a.author) ? '数组' : typeof a.author}  related=${a.related_list?.length ?? 0}  tags=${a.tags?.length ?? 0}`);
}

const samplePhotoId = album?.envelope?.data?.series?.[0]?.id ?? sampleAlbumId;
const chapter = samplePhotoId
  ? await probe(`/chapter 章节 id=${samplePhotoId}`, `/chapter?id=${samplePhotoId}`, () => api.chapter(samplePhotoId), 'images')
  : null;

const forum = sampleAlbumId
  ? await probe(`/forum 评论 aid=${sampleAlbumId}`, '/forum?mode=all&page=1&aid=…', () => api.forum(sampleAlbumId, 1), 'list')
  : null;

const week = await probe('/week 期数列表', '/week', () => api.week(), 'categories');

const weekId = week?.envelope?.data?.categories?.[0]?.id;
// 这一行故意不指定 pickKey：自动识别出的键名要和客户端读取的键名比对
const weekContent = weekId
  ? await probe(`/week/filter 期内容 id=${weekId}`, '/week/filter?id=…', () => api.weekFilter(weekId, 1))
  : null;

// ─────────────────────────────────────────────────────────────
// 3. 关键不变量
// ─────────────────────────────────────────────────────────────
if (!AS_JSON) console.log('\n[2] 关键不变量（产品设计依赖的实测结论）');

const b1 = browse1?.envelope?.data;
check('浏览流每页 80 条', b1?.content?.length === PAGE_SIZE, `实际 ${b1?.content?.length}`);
check('浏览流 total 恒为 10000（哨兵值）', b1?.total === BROWSE_TOTAL_SENTINEL, `实际 ${b1?.total}`);
check('浏览流 total 不可信但页数可信（10000/80=125）',
  Math.ceil((b1?.total ?? 0) / PAGE_SIZE) === BROWSE_PAGE_LIMIT,
  `推得 ${Math.ceil((b1?.total ?? 0) / PAGE_SIZE)} 页`);

// 服务端两档真实榜单：任一一档空 = 对应档位在 App 里会显示空白
for (const [label, r] of [['周榜 mv_w', rankWeek], ['月榜 mv_m', rankMonth]]) {
  const total = r?.envelope?.data?.total;
  check(`${label} 有数据且 total 真实`, typeof total === 'number' && total > 0 && total !== BROWSE_TOTAL_SENTINEL,
    total === 0 ? '⚠ 返回空列表，该档在 App 里会显示空白' : `total=${num(total)}`);
}

// 新晋热榜的数据前提（App 的 H24 档位由月榜本地重排得到）
check('月榜热度池含足够「近 7 天有更新」作品（新晋热榜的数据前提）',
  recentInPool >= 12,
  `池 ${monthPool.length} 条，近 7 天有更新 ${recentInPool} 条`);

// 日榜 mv_t：已知死档，恢复与否都只是信息 —— 恢复后应回来评估是否把 H24 切回直连
{
  const total = rankDay?.envelope?.data?.total;
  if (total === 0) {
    note('日榜 mv_t 仍为空', '已知死档；App 的「新晋热榜」用月榜本地重排替代');
  } else {
    note('日榜 mv_t 已恢复', `total=${num(total)} —— 可评估把 H24 档位切回直连 mv_t`);
  }
}

check('搜索 total 是真实条数（≠10000）', search?.envelope?.data?.total !== BROWSE_TOTAL_SENTINEL,
  `综合维度 total=${search?.envelope?.data?.total}`);

// 分类树：10 大类 + 标签墙
const cat = categories?.envelope?.data;
const tagCount = (cat?.blocks ?? []).reduce((n, b) => n + (b.content?.length ?? 0), 0);
check('分类树 10 大类', (cat?.categories?.length ?? 0) === 10, `实际 ${cat?.categories?.length}`);
check('标签墙 blocks 非空', (cat?.blocks?.length ?? 0) > 0, `${cat?.blocks?.length} 组 / ${tagCount} 个标签（设计文档记 4 组 43 个）`);

// category 是对象而非字符串（PiKA D1 的根因）
const c0 = b1?.content?.[0];
check('列表 category 是对象（不是字符串）', c0?.category !== null && typeof c0?.category === 'object',
  `实际 ${JSON.stringify(c0?.category)?.slice(0, 40)}`);
check('列表 author 字段存在', c0?.author !== undefined, `实际 ${JSON.stringify(c0?.author)?.slice(0, 40)}`);

const ad = album?.envelope?.data;
check('详情 author 是数组', Array.isArray(ad?.author), `实际 ${typeof ad?.author}`);
check('详情 series 带 sort 字段', ad?.series?.[0]?.sort !== undefined, `sort=${ad?.series?.[0]?.sort}`);
check('详情统计字段是字符串', typeof ad?.total_views === 'string', `total_views=${typeof ad?.total_views}`);
check('详情 is_favorite 可读（收藏态回填依据）', typeof ad?.is_favorite === 'boolean' || ad?.is_favorite !== undefined,
  `${typeof ad?.is_favorite} = ${ad?.is_favorite}`);

const cd = chapter?.envelope?.data;
const images = cd?.images ?? [];
check('章节 images 非空', images.length > 0, `${images.length} 张`);
check('章节图片文件名连续递增（00001 起、扩展名一致）', filenamesContinuous(images),
  images.length ? `首=${images[0]} 尾=${images[images.length - 1]}` : '无样本');

const fd = forum?.envelope?.data;
check('评论主键是 CID（不是 id）', fd?.list?.[0]?.CID !== undefined, `CID=${fd?.list?.[0]?.CID}`);
check('评论 expinfo 是对象', fd?.list?.[0]?.expinfo !== null && typeof fd?.list?.[0]?.expinfo === 'object',
  `level_name=${fd?.list?.[0]?.expinfo?.level_name}`);

check('/week 期数列表非空', (week?.envelope?.data?.categories?.length ?? 0) > 0,
  `${week?.envelope?.data?.categories?.length} 期`);

// 客户端 JmListData 同时接受 content 与 list（统一走 .items），线上用哪个都必须落在集合内。
// 历史事故：/week/filter 线上用 list，客户端只读 content —— 因 DTO 有 emptyList() 默认值，
// 不报错，只静默变空，「每周必看某期」永远显示空列表。
const CLIENT_LIST_KEYS = ['content', 'list'];
const wkInfo = arrayInfo(weekContent?.envelope?.data);
check('/week/filter 的列表键在客户端支持的键集合内', CLIENT_LIST_KEYS.includes(wkInfo.key),
  `线上数组键=${wkInfo.key}（${wkInfo.count ?? '-'} 条）；客户端支持 ${CLIENT_LIST_KEYS.join(' / ')}`);
check('/week/filter 有内容（每周必看入口可用）', (wkInfo.count ?? 0) > 0,
  `${wkInfo.count ?? 0} 条`);

// ─────────────────────────────────────────────────────────────
// 4. 负向控制：产品设计里「入口不渲染」的端点必须确实不存在
// ─────────────────────────────────────────────────────────────
if (!AS_JSON) console.log('\n[3] 负向控制（这些端点必须不存在，否则应重新评估是否补功能）');

const negatives = [
  ['/random 随机推荐', '/random'],
  ['/hot_search 热搜词', '/hot_search'],
  ['/tags 标签总表', '/tags'],
  ['/user 用户资料', '/user'],
  ['/api/v3/* 前缀', '/api/v3/categories'],
];
for (const [label, path] of negatives) {
  const r = await api.tryGet(path);
  // ⚠ 服务端对不存在的端点返回 HTTP 200 + code 200 + data=[] + errorMsg="Not legal.xxx"，
  //   所以不能靠 code 判断，只能靠 errorMsg。JmClient 目前也不看 code/errorMsg，
  //   这类端点若被调用会静默拿到空列表而不是报错。
  const notLegal = r.ok && isNotLegal(r.envelope);
  const absent = !r.ok || notLegal;
  const detail = !r.ok
    ? `HTTP 失败：${r.error.message.slice(0, 46)}`
    : notLegal
      ? `errorMsg="${r.envelope.errorMsg}"`
      : `⚠ code=${r.envelope?.code} 且 errorMsg 为空 —— 该端点可能存在，需复核产品设计`;
  check(`${label} 确实不存在`, absent, detail);
  if (!AS_JSON) console.log(`  ${absent ? 'OK  ' : 'WARN'} ${label}   ${detail}`);
}

// ─────────────────────────────────────────────────────────────
// 5. 深度验证（--deep）
// ─────────────────────────────────────────────────────────────
let tSame = null;
let dimsDistinct = null;
if (DEEP) {
  if (!AS_JSON) console.log('\n[4] 深度验证（--deep）');

  const p125 = await api.browse(BROWSE_PAGE_LIMIT);
  const p126 = await api.browse(BROWSE_PAGE_LIMIT + 1);
  const sameFirst = firstId(p125.envelope) === firstId(p126.envelope);
  check(`第 ${BROWSE_PAGE_LIMIT + 1} 页原样重发第 ${BROWSE_PAGE_LIMIT} 页（125 页硬上限依据）`, sameFirst,
    `p125 首=${firstId(p125.envelope)} p126 首=${firstId(p126.envelope)}`);

  const tValues = ['a', 'b', 'c', 'd'];
  const firsts = [];
  for (const t of tValues) {
    const r = await api.request(`/categories/filter?page=1&o=mr&t=${t}`);
    firsts.push(firstId(r.envelope));
  }
  tSame = new Set(firsts).size === 1;
  check('t= 参数无效果（时间筛选不应存在）', tSame,
    `t=${tValues.join('/')} 首页 id = ${firsts.join(' / ')}`);

  const dims = await Promise.all([
    api.search('姐姐', { mainTag: 0 }),
    api.search('姐姐', { mainTag: 1 }),
    api.search('姐姐', { mainTag: 2 }),
    api.search('姐姐', { mainTag: 3 }),
  ]);
  const totals = dims.map((d) => d.envelope?.data?.total);
  dimsDistinct = new Set(totals).size > 1;
  check('四维搜索各有独立 total', dimsDistinct, `综合/作品/作者/标签 = ${totals.join(' / ')}`);
}

// ─────────────────────────────────────────────────────────────
// 6. 能力位复核：JmCapabilities.kt 的常量 vs 今天线上
// ─────────────────────────────────────────────────────────────
if (!AS_JSON) {
  console.log('\n[5] 能力位复核（app/src/main/java/com/jmread/core/JmCapabilities.kt）');
  console.log('  ' + pad('能力位', 32) + pad('声明', 8) + pad('实测', 8) + '结论');
  console.log('  ' + '·'.repeat(74));
}

/** 取前面某条「确实不存在」断言的结论；true = 已确认该端点不存在 */
const negAbsent = (label) => checks.find((c) => c.name.startsWith(label))?.ok ?? null;

// 实测列：true/false = 本次探测得出；null = 需要真实账号，本次无法验证，保持声明值
const capabilities = [
  ['hasBrowse', true, !!browse1],
  // 榜单：App 只用 周(mv_w)/月(mv_m) 两档，H24 由月榜本地重排 —— 不再要求 mv_t 有数据
  ['hasRank', true, [rankWeek, rankMonth].every((r) => (r?.envelope?.data?.total ?? 0) > 0)],
  ['hasTagWall', true, (categories?.envelope?.data?.blocks?.length ?? 0) > 0],
  ['hasWeeklyPicks', true, (week?.envelope?.data?.categories?.length ?? 0) > 0],
  ['hasRelated', true, (ad?.related_list?.length ?? 0) > 0],
  // negAbsent 为 true 表示「端点不存在」，所以 has* 取反
  ['hasRandom', false, negAbsent('/random') === null ? null : !negAbsent('/random')],
  ['hasHotWords', false, negAbsent('/hot_search') === null ? null : !negAbsent('/hot_search')],
  ['supportsStatusFilter', false, null],
  ['supportsTimeFilter', false, DEEP ? tSame : null],
  ['supportsCategoryFilter', true, null],
  ['supportsDimensionSearch', true, DEEP ? dimsDistinct : null],
  ['hasCommentRead', true, (fd?.list?.length ?? 0) > 0],
  ['hasSubComment', true, (fd?.list ?? []).some((c) => (c.replys?.length ?? 0) > 0)],
  ['hasCommentWrite', false, null],
  ['hasFavouriteStateRead', true, ad?.is_favorite !== undefined],
  ['hasPaidNotice', true, ad?.price !== undefined],
  ['hasLogin', true, null],
  ['hasCloudFavouriteRead', false, null],
  ['hasCloudFavouriteWrite', false, null],
  ['hasDailyCheckIn', false, null],
  ['hasCloudHistory', false, null],
];

let mismatches = 0;
for (const [name, declared, actual] of capabilities) {
  const verdict = actual === null
    ? '需账号验证 · 保持 false'
    : actual === declared
      ? '一致'
      : '⚠ 不一致，需复核';
  if (actual !== null && actual !== declared) mismatches += 1;
  if (!AS_JSON) {
    console.log('  ' + pad(name, 32) + pad(String(declared), 8) + pad(actual === null ? '未测' : String(actual), 8) + verdict);
  }
}
if (!AS_JSON && mismatches) {
  console.log(`  → 有 ${mismatches} 项与线上不符，请同步修改 JmCapabilities.kt 后重新发版`);
}

// ─────────────────────────────────────────────────────────────
// 汇总
// ─────────────────────────────────────────────────────────────
const failed = checks.filter((c) => !c.ok);
const elapsed = ((Date.now() - t0) / 1000).toFixed(1);

if (AS_JSON) {
  console.log(JSON.stringify({ rows, checks, notes, hostStats: [...api.hostStats], elapsed }, null, 2));
} else {
  console.log('\n' + LINE);
  console.log(`[6] 断言汇总   ${checks.length - failed.length}/${checks.length} 通过   请求 ${api.requestCount} 次   耗时 ${elapsed}s`);
  for (const c of checks) {
    if (!c.ok) console.log(`  FAIL  ${c.name}${c.detail ? '   → ' + c.detail : ''}`);
  }
  for (const n of notes) {
    console.log(`  INFO  ${n.name}${n.detail ? '   → ' + n.detail : ''}`);
  }
  const stats = [...api.hostStats].map(([h, s]) => `${h}(${s.ok}✓${s.fail}✗)`).join('  ');
  console.log(`  域名使用：${stats || '(无)'}`);
  console.log(LINE);
  if (failed.length) {
    console.log('结论：有断言失败 —— 产品设计或 JmCapabilities 的前提可能已变，需要人工复核后再发版。');
  } else {
    console.log('结论：全部通过 —— 读链路前提与 2026-09-19 实测一致，可以发版。');
  }
}

process.exit(failed.length ? 1 : 0);
