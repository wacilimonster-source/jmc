# JM Reader · 技术架构设计文档

> 项目：独立禁漫阅读器（与 PiKA 拆分的单源 App）
> 代码基线参考：PiKA v1.5.51 · 协议参考：JMComic-Crawler-Python / JMComic-qt
> 实测依据：`pika/reports/jm-source-design-20260919.html`（端点 / 字段形态 / 乱序规则均为 2026-09-19 实机探测）
> 版本：v1.0 · 2026-09-20

---

## 0. 一页纸结论

- **单模块 Kotlin + Jetpack Compose App**，fork PiKA 代码起步，做「减法改造」：删除多源机制（SourceManager / Source 接口 / ComicRef 源前缀），换上单源 `JmRepository` + 静态能力声明 `JmCapabilities`。
- 网络层移植 PiKA 的 OkHttp + BcTls（过 Cloudflare 指纹），为 JmClient 补上 PiKA 哔咔侧已有的**失败换域重试引擎**与结构化异常；域名走四级策略（内置镜像 → 远端域名服务器 → /setting 下发 → 手填）。
- 三个决定性技术件：**DTO 形态修正**（报告 E2，配 fixture 回归）、**分页硬上限 + 去重**（E3）、**逐图接缝判定乱序还原**（E6，纯 Kotlin 实现、JVM 可测）。
- 本地持久化沿用 DataStore + SharedPreferences 组合；单源 App **无源前缀**，键设计直接用裸 comicId。
- P0–M5 约 7–9 天；写侧（收藏 / 签到 / 历史 / 发评论）卡测试账号，架构上预留但入口不渲染。

---

## 1. 关键架构决策（ADR）

| # | 决策 | 理由 | 放弃的替代 |
|---|---|---|---|
| ADR-1 | **fork PiKA 代码做减法**，不建共享 module | 两 App 独立演进；共享模块的构建/发布复杂度 > 重复代码成本 | Gradle 复合构建共享 core 模块 |
| ADR-2 | **删除 Source 抽象与 SourceManager**，UI 直连 `JmRepository` | 单源 App 里「源切换状态机 / 路由源命名空间 / 本地键源前缀 / 按源隐藏 UI」全部是死重；调研报告 D4/D5/D6 在单源形态下整体消失 | 保留 Source 接口单实现（接口无第二个消费者=过早抽象） |
| ADR-3 | 保留**声明式能力对象 `JmCapabilities`**（常量，非接口方法） | 「能力说真话」的产品原则需要单一事实来源；UI 只读能力不硬判；未来若加源再升级为接口 | 无能力对象直接写死 |
| ADR-4 | 乱序还原做成**纯 Kotlin 模块**（`core/scramble`），输入输出是像素数组（`IntArray`/宽高），不 import android.graphics | JVM 单测可跑（样本图 fixture 直接喂像素）；Android 侧 Bitmap↔IntArray 转换在更薄的一层做 | 依赖 Bitmap 的实现（无法单测，回归靠真机） |
| ADR-5 | 网络层 **OkHttp + BcTls**（移植），禁漫限速宽松 → **不做**哔咔式 20s 全局冷却 / 250ms 节拍，只做失败换域 + 有限重试 | 实测 20 连发零失败；禁漫主要断服原因是镜像域名轮换，不是限流 | 照抄 PicaClient 全套冷却 |
| ADR-6 | DTO 全部按线上真实形态声明（对象/数组/数字），**移除对 isLenient 的依赖**（保留 ignoreUnknownKeys） | PiKA 的 D1 根因就是「照社区库猜类型」+ isLenient 掩盖；fixture 回归防复发 | 继续宽容解析 |
| ADR-7 | 持久化：DataStore（会话/进度/设置）+ SharedPreferences（小状态缓存），**不加 Room** | 数据形态简单（kv + 小列表），PiKA 同栈验证过 | Room / SQLite |
| ADR-8 | 独立包名 `com.jmread`、独立 update.json 仓库 | 与 PiKA 共存安装、独立发版节奏 | 复用 com.pika（会覆盖安装） |

---

## 2. 工程结构

```
JM/
├─ app/                                # 单模块（settings.gradle.kts 只 include(":app")）
│  └─ src/
│     ├─ main/java/com/jmread/
│     │  ├─ JmApp.kt                   # Application：init 各 Prefs → BcTls → Coil 装配（唯一图片管线装配点）
│     │  ├─ MainActivity.kt            # crash.log + setContent { JmTheme { MainScreen() } }
│     │  ├─ network/
│     │  │  ├─ JmCrypto.kt             # 签名/解密/URL 拼接（常量集中、版本号可被 /setting 刷新）
│     │  │  ├─ JmClient.kt             # HTTP + 信封解密 + 换域重试引擎 + 全端点
│     │  │  ├─ JmModels.kt             # DTO（按实测形态，见 §5）
│     │  │  ├─ JmException.kt          # 携带 httpCode 的结构化异常
│     │  │  └─ BcTls.kt                # BouncyCastle JSSE（移植）
│     │  ├─ core/
│     │  │  ├─ model/Comic.kt          # 领域模型（精简自 SourceModels.kt，无 SourceType）
│     │  │  ├─ JmRepository.kt         # 唯一数据门面：DTO→领域模型映射 + album 短 TTL 缓存
│     │  │  ├─ JmCapabilities.kt       # 能力声明常量（UI 渲染开关的唯一事实来源）
│     │  │  ├─ scramble/               # 纯 Kotlin 乱序判定与还原（见 §6）
│     │  │  ├─ netconfig/DomainPool.kt # 四级域名池 + 换域状态机（见 §4.4）
│     │  │  ├─ download/DownloadManager.kt  # 章节下载（存还原后字节，移植+修正）
│     │  │  ├─ update/UpdateManager.kt # update.json + sha256 + FileProvider 安装（移植）
│     │  │  └─ log/LogStore.kt         # 环形日志（移植）
│     │  ├─ data/                      # ReaderPrefs / RecentReads / Bookmarks / ReaderStatus /
│     │  │                             # UpdatedAtCache / WebtoonSliceCache / GridSettings /
│     │  │                             # AccountPrefs(AVS+凭据) / NetPrefs(域名/手动覆盖)
│     │  └─ ui/                        # Compose 页面（§8 路由表）
│     ├─ test/java/com/jmread/
│     │  ├─ dto/JmDtoFixtureTest.kt    # 7 份真实响应 fixture 反序列化回归（防 D1 复发）
│     │  ├─ pagination/PagesOfTest.kt
│     │  └─ scramble/ScrambleDetectorTest.kt  # 样本像素 fixture：还原/不误翻双断言
│     └─ test/resources/jm/*.json      # list / search / album / chapter / forum / categories / week
├─ tools/                              # 金丝雀脚本（2026-09-21 落地）
│  ├─ jm-lib.mjs                       # 协议公共库（签名/解密/换域请求），与 JmCrypto+JmClient 等价
│  ├─ jm-api-check.mjs                 # 端点存活 + 关键不变量 + 能力位复核（含 --deep / --selftest-only）
│  ├─ jm-shape-probe.mjs               # 字段形态漂移比对（基线 .jm-shape-baseline.json）
│  ├─ dump-fixtures.mjs                # 拉真实响应覆盖 test/resources/jm/*.json
│  └─ oneoff/                          # 一次性改源码补丁脚本，已执行完，仅历史留档
├─ docs/                               # 本文档 + product-design.md + research-jm-protocol.md + release.md
│                                      # + verification-2026-09-21.md（线上验证报告）
└─ gbuild.sh                           # 构建脚本（含 release 后按版本号重命名 APK）
```

**构建配置**（对齐 PiKA，只改身份项）：AGP 8.9.0 / Kotlin 2.1.20 / Compose BOM 2024.12.01 / Gradle 8.11.1；compileSdk 35 / minSdk 26 / targetSdk 35；依赖：Compose + Navigation-Compose、DataStore Preferences、kotlinx-serialization-json 1.7.3、OkHttp 4.12.0、Coil 2.7.0、BouncyCastle bcprov/bctls/bcutil 1.78.1、**新增 junit + kotlinx-coroutines-test**（PiKA 零测试是 D14 教训）。release 开 minify + shrinkResources；签名沿用「默认 debug keystore 或环境变量注入」机制。

---

## 3. 禁漫协议参考（实现依据，全部为实测/社区库双源确认）

### 3.1 请求

| 项 | 值 |
|---|---|
| API 基址 | `https://{api-host}`，**无 /api/v3 前缀**。内置镜像 4 个：`www.cdngwc.cc / www.cdnhjk.net / www.cdngwc.net / www.cdngwc.club`（2026-09-19 实测全部 /setting 200） |
| 签名头 | `token = md5("{ts}" + SECRET)`；`tokenparam = "{ts},{APP_VERSION}"`；ts=秒级。SECRET=`185Hcomic3PAPP7R` |
| 版本号 | 常量兜底 `2.1.8`（**不写死 2.1.5**）；运行时以 `/setting.jm3_version` 刷新后携带（实测 2.1.5/2.1.8/1.8.2/9.9.9 形态一致，但旧版本号是风控隐患） |
| 固定头 | `device: ANDROID;9.0;SMR;unknown;deadbeef12345678;2.1.3`、`platform: ANDROID`、`channel: app`、`app-version: 3.4.17`、`User-Agent: okhttp/3.12.0 leak(200.0);Android version:9.0;MAX2;100;jmc;3.23.0`（集中一处定义） |
| 会话 | `Cookie: AVS={data.s}`；仅登录端点依赖 |
| 响应 | 外层明文 `{code, errorMsg, data}`；**data 为 base64 字符串时**走解密：`AES-256-ECB`，key=`md5("{ts}" + SECRET)` 的 32 字节小写 hex，手动剥 PKCS7；`/setting` 与 `/chapter_view_template` 的 data 是明文/HTML，不做类型误判 |
| 限速 | 宽松（20 连发 8.8s 零 429）→ 不做全局限流冷却 |

### 3.2 端点清单（本 App 使用面）

| 端点 | 鉴权 | 用途 / 要点 |
|---|---|---|
| GET `/setting` | 免 | 域名自愈数据源：base_url / cn_base_url / img_host / app_shunts / jm3_version。data 明文。App 启动 + 换域成功后刷新 |
| GET `/categories` | 免 | 10 大类 + sub_categories + **blocks（4 组 43 官方标签）** + total_albums |
| GET `/categories/filter?page&c&o&t` | 免 | 浏览/分类流。页大小 80；**硬上限 125 页**；total 恒 10000 不可信但页数可信 |
| GET `/search?search_query&page&main_tag&c&o` | 免 | main_tag：0 综合 / 1 作品 / 2 作者 / 3 标签；total 真实 |
| GET `/album?id=` | 免 | 详情。author 数组；series[]{id,name,sort}；related_list≈12；price/purchased/is_aids；统计数字段是字符串 |
| GET `/chapter?id={photoId}` | 免 | 整章 images[]（文件名连续 `00001.webp…`） |
| GET `/forum?mode=all&page&aid=` | 免 | 评论。主键 **CID**；content 为 HTML；replys/parent_CID 内嵌；expinfo 对象 |
| GET `/week` | 免 | 每周必看期数列表（期内容流用 /categories/filter + 期参数，实现时探明具体传参） |
| POST `/login` | 免 | form username/password → data.s |
| GET `/logout` | 登 | 尽力而为 |
| GET `/favorite?page&folder_id=0&o=` | 登 | 云端收藏列表（验证后放开） |
| GET/POST `/favorite?aid&type=` | 登 | 收藏/取消——**参数未验证，隐藏** |
| GET `/daily` `/daily_chk` | 登 | 签到状态/执行——未登录 data=null，**必须与「未签到」区分** |
| GET `/watch_list?page=` | 登 | 云端历史 |
| （不存在） | — | `/random` `/hot_search` `/tags` `/user` `/member` `/message` `/history` `/api/v3/*` 全部 Not legal → 对应功能一律不做 |

### 3.3 图片

| 项 | 规则 |
|---|---|
| 章节图 | `https://{img-host}/media/photos/{photoId}/{00001.webp…}` |
| 封面 | `…/media/albums/{albumId}.jpg`；高清 `_3x4.jpg`；列表封面回退普通封面 |
| 图片 host | 动态：`/setting.img_host` 优先 → 内置 6 个候选（`cdn-msp.jmapiproxy1.cc / jmapiproxy2.cc / cdn-msp2.jmapiproxy2.cc / cdn-msp3.jmapiproxy2.cc / cdn-msp.jmapinodeudzn.net / cdn-msp3.jmapinodeudzn.net`；参考项目另含 `jmdanjonproxy.xyz` 族与 `jmapiproxy3.cc`，纳入候选表） |
| 无防盗链 | 实测裸请求 200，不需要 Referer/UA。**兜底杠杆**（社区库行为，默认不开）：空白图时追加 `?v={ts}` 与 `X-Requested-With: com.JMComic3.app` 头重试一次 |
| 后缀陷阱 | `.jpg` 后缀 502、`_b.jpg` 0 字节——**不做后缀变换重试**，文件名严格按 images[] 原样 |
| web 主站 | 不做兜底（18comic.vip 有 CF 盾、.org 证书过期） |

---

## 4. 网络层设计

### 4.1 JmClient（重构点相对 PiKA）

```
execute(relative, form):
  host = DomainPool.current()                 // 手填 > 下发 > 内置，含冷却期
  ts   = now
  请求（签名头 + 固定头 + AVS Cookie）
  ├─ 401            → onUnauthorized()（静默重登一次）→ 重试一次；仍 401 → JmException(httpCode=401)
  ├─ 网络失败/超时   → DomainPool.markBad(host) → 换下一个 host 重试（≤ 镜像数-1 次，间隔 3s 去抖）
  ├─ HTTP 非 2xx     → JmException(httpCode, body 摘要)
  └─ 2xx            → decryptEnvelope()（data 是字符串才解密）→ 交给 DTO
成功后：DomainPool.remember(host) 持久化；每 6h 或换域成功后异步拉 /setting 刷新域名/版本号/img_host
```

- `JmException(message, httpCode)`：结构化。会话失效判断走 `httpCode == 401`，**删除 PiKA 的中文文案匹配**（D9）。
- 重试引擎范式从 `PicaClient.kt:123-192` 移植（失败换域 + 3s 去抖 + 记住可用域），**去掉** 20s 冷却与限流计数。
- DTO 解码 `Json { ignoreUnknownKeys = true }`；isLenient=false，让形态漂移在测试期就红。

### 4.2 四级域名池（DomainPool）

| 级 | 来源 | 说明 |
|---|---|---|
| 1 | 用户手填（NetPrefs） | 保留设置页输入框，优先级最高（PiKA 习惯延续） |
| 2 | `/setting` 下发 base_url | 换域成功或启动 6h 定时刷新 |
| 3 | 远端域名服务器 | `https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt`（c02/c03 镜像），内容 AES-ECB 解密 key=`diosfjckwpqpdfjkvnqQjsik` → `{"Server":[...]}`。社区库/桌面端多年验证的路径，实现为「级 2 失败才触发」的低频兜底 |
| 4 | 内置 4 API 镜像 | 编译期兜底 |

> 级 3 属增强项，放在 M4 实现、失败静默降级到级 4；**不作为 P0 依赖**。

### 4.3 图片域与网安配置

- 图片 host 表 = `/setting.img_host`（每次刷新更新表首）+ 内置候选（含 jmdanjonproxy.xyz 族）。
- **`network_security_config.xml` 必须同步放宽**：API 域名保持精确白名单；图片域名改为通配 `<domain includeSubdomains>true</domain>` 风格的宽松条目（或对图片加载走单独的 `OkHttpClient` 且不启用该配置约束）。这是 PiKA 调研明确点名的「最容易漏的一步」——新域名族不进白名单会被系统拦，表现为「只有别人能看图」。
- 权衡记录：图片域名通配放宽后，图片连接不再受白名单限制；可接受性依据：图片 CDN 无敏感凭据、响应是公开静态资源，且 CoW 风险低于断图风险。

### 4.4 TLS（移植）

BcTls.install()（BouncyCastleJsseProvider 替换 Conscrypt/BoringSSL 指纹）应用于：JmClient OkHttp、Coil ImageLoader（JmApp.onCreate 唯一装配点）、DownloadManager（openConnection）。证书校验保持系统 CA（PiKA v1.5.44 起的口径）。

---

## 5. DTO 与领域模型（D1 修复清单）

### 5.1 与 PiKA 现模型的差异（照此实现，fixture 断言）

```kotlin
// 列表项：category / category_sub 是 {id,title} 对象（PiKA 声明成 String → 全链路挂）
@Serializable data class JmNamedItem(val id: String = "", val title: String = "")
@Serializable data class JmAlbumSummary(
    val id: String = "", val name: String = "", val author: String = "", val image: String = "",
    val category: JmNamedItem = JmNamedItem(),
    @SerialName("category_sub") val categorySub: JmNamedItem = JmNamedItem(),
    val liked: Boolean = false,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("update_at") val updateAt: Long = 0, val adddate: String = "",
)

// 详情：author 是数组（["美娜讚","鋼鐵王","NTR"]）；id 线上是裸数字 → 用 Long；
// 统计是字符串数字 → 用 String + 安全转 Long；series 带 sort；补 price/purchased/is_aids/description
@Serializable data class JmAlbumDetail(
    val id: Long = 0, val name: String = "",
    val author: List<String> = emptyList(),
    val description: String? = null,
    val series: List<JmSeriesItem> = emptyList(),
    @SerialName("series_id") val seriesId: String = "",
    @SerialName("total_views") val totalViews: String = "0",
    @SerialName("total_photos") val totalPhotos: Int = 0,
    val likes: String = "0",
    @SerialName("comment_total") val commentTotal: String = "0",
    val tags: List<String> = emptyList(), val works: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val price: String = "", val purchased: String = "",
    @SerialName("is_aids") val isAids: Boolean = false,
    @SerialName("related_list") val relatedList: List<JmRelatedItem> = emptyList(),
)
@Serializable data class JmSeriesItem(val id: String = "", val name: String = "", val sort: String = "0")

// 评论：主键 CID；expinfo 是对象（等级徽章在里面）；replys 内嵌；badges 在 expinfo 内
@Serializable data class JmForumComment(
    @SerialName("CID") val cid: String = "",
    @SerialName("AID") val aid: String = "",
    @SerialName("parent_CID") val parentCid: String = "0",
    val username: String = "", val nickname: String = "", val content: String = "",
    val addtime: String = "", val likes: String = "0", val photo: String = "",
    val spoiler: String = "0",
    val expinfo: JmExpInfo = JmExpInfo(),
    val replys: List<JmForumComment> = emptyList(),
)
@Serializable data class JmExpInfo(
    @SerialName("level_name") val levelName: String = "", val level: Int = 0,
    @SerialName("exp") val exp: String = "0", val badges: List<String> = emptyList(),
)

// 分类：data 里有 categories 与 blocks（官方标签墙）
@Serializable data class JmCategoriesData(
    val categories: List<JmCategory> = emptyList(),
    val blocks: List<JmTagGroup> = emptyList(),
)
@Serializable data class JmTagGroup(val title: String = "", val content: List<String> = emptyList())
```

### 5.2 领域模型（core/model，精简自 PiKA SourceModels）

`ComicSummary(id, title, authors, coverUrl, categoryName, updatedText, isFavourite, isRead)` / `ComicDetail(+ description, tags, works, actors, chapters, related, stats, isPaid)` / `ComicChapter(id, title, order)` / `ComicPage(index, imageUrl, cacheKey)` / `PageResult(items, page, pages, total)` / `ComicComment(...)`。无 SourceType 字段。

---

## 6. 图片乱序判定与还原（D3 规格）

### 6.1 背景（实测 E6）

服务端对部分图做「十等分倒序」打乱；**逐图生效**（photo 800000 同章第 2 页乱、3~5 页正常）；与 CDN 线路无关；社区库的 id 阈值规则（<220980 不乱 / 268850 421926 分界 / md5(aid+filename) 定块数）与 2026-09 线上行为不符，**不采用 id 规则**。56 页实测：11 页乱序样本判定代价差 5~14 倍，45 页正常样本双门全不触发。

### 6.2 判定算法（纯 Kotlin，`core/scramble/ScrambleDetector.kt`）

```
输入：灰度像素 IntArray + 宽高（Android 侧 Bitmap→灰度 IntArray，均分 10 条）
对两种候选顺序（恒等 / 完全倒序）分别求 9 个接缝的平均绝对差：
  cost(order) = Σ_{i=1..9} mean(|row(block_i 首 2 行) - row(block_{i-1} 末 2 行)|)
判定（双门）：restore = (costRev * 2 < costIdent) && (costIdent >= 20)
还原：新 Bitmap 按 (9,8,7,6,5,4,3,2,1,0) 逐条 drawBitmap / 像素行搬移
成本：≈10×宽 次像素比较（1280 宽 ≈1.3 万次），远低于一帧
```

- 候选块数扩展位：算法参数化 `splits ∈ {10}`，预留 `{8}`（社区库 421926+ 段为 8 等分的历史行为）；若埋点显示漏判率上升，开启双候选取代价最小者。
- **判定结果缓存**：内存 LRU + 磁盘（`scramble_cache` SP，key=`{photoId}/{filename}`，值=0/1），同一章再进零开销。
- **Coil 集成**（`JmApp.onCreate` 唯一装配点）：注册自定义 `Fetcher`（或 `Keyer + Transformation` 组合）——URL 属 `media/photos/` 才启用判定；**还原后图像的 memoryCacheKey 必须换成 `url + "#de"`**，否则错乱版本会被缓存（PiKA ReaderViewModel 缓存 key=url 的坑）。
- **下载一致性**：DownloadManager 落盘前执行同一判定器，存还原后字节；文件扩展名按魔数判定（webp/jpg），不再硬编码 `page_N.jpg`。
- **可观测**：LogStore 记录「判定乱序页 / 总页」比例；该指标归零说明服务端已停止打乱，届时可整体关闭（不删码）。
- 手动兜底：阅读器「本页横条倒序」开关，直接在渲染层做一次倒序重排，结果不写缓存。

### 6.3 测试

`app/src/test` 内以样本像素 fixture 驱动：photo 220981（乱，还原后接缝代价显著下降）、photo 300000（不乱，不触发）、合成图（确定性：手工生成 10 条不同颜色横条倒序后喂入）。判定器不依赖 Android 类，JVM 直接跑。

---

## 7. 分页与列表（D2 规格）

```kotlin
// 唯一 helper，替换 PiKA 散落 5 处的 `if (items.size < 80) page else page + 1`
fun pagesOf(total: Int, items: Int, page: Int, pageSize: Int, hardCap: Int): Int =
    if (total > 0 && total < HARD_TOTAL_BROWSE)   // 真实 total（搜索/榜单/收藏）
        min(ceil(total / pageSize.toDouble()).toInt(), hardCap)
    else if (items < pageSize) page else min(page + 1, hardCap)

// 浏览流：hardCap=125（=10000/80，第 126 页起服务端重发第 125 页）
// 搜索/榜单：total 真实（「姐姐」721 / 日榜 143）直接 ceil
// 保险层：请求页 > pages 时 Repository 直接返回空，不发网络
// 去重层：列表聚合处按 comicId 页内+跨页去重（深页码存在轻微重叠，实测）
```

---

## 8. UI 架构

- Compose + Material3 + Navigation-Compose，`MainScreen` 持 NavHost（路由表见产品设计 §4.2）。底部 4 tab：home / category / search / mine。
- 每屏一个 ViewModel（继承 PiKA 模式：StateFlow + viewModelScope；不引 DI 框架，object 单例仓库直接注入）。
- **页面进入即固化数据口径**：ViewModel 持有打开时的 comicId/albumId 参数（构造传入），渲染期间不受任何全局态影响——这是 PiKA「页面运行时读全局源」教训的单源等价物（切设置不影响在读书）。
- 阅读器（移植 ReaderScreen/ReaderViewModel/WebtoonSplitPage）：滚动 + 翻页双模式、长图切片缓存（WebtoonSliceCache key `{albumId}#{order}`）、预取 ±2 页、进度即时落盘。
- 主题：PiKA 深色系移植，品牌色改禁漫金（可后调）。
- 未登录：`mine` Tab 渲染本地功能全集；云端区块读 `AccountPrefs.hasSession` 决定是否渲染；`login` 路由仅在点击「登录」时可达——**删除 PiKA 的「未登录强跳 login」逻辑**。

---

## 9. 数据持久化清单

| 存储 | 技术 | key 设计 |
|---|---|---|
| AccountPrefs | DataStore `jm_prefs` | `avs_session`、`account_name`、凭据（SecureAccountStore 移植：AndroidKeyStore AES-256-GCM，alias `jm_cred`） |
| NetPrefs | DataStore `jm_prefs` | `manual_api_host`、`resolved_api_host`、`resolved_img_host`、`jm3_version`、`last_setting_fetch` |
| ReaderPrefs | DataStore `jm_reader` | `progress_{albumId}` = "order:pageIndex"、`finished_{albumId}`、reader_mode、brightness、hide_bottom_bar |
| RecentReads | DataStore `jm_reader` | JSON 列表 ≤12 条：{albumId, title, cover, order, page, ts} |
| Bookmarks | DataStore `jm_bookmarks` | JSON 列表：{albumId, title, authors, cover, addedAt}（本地书架主形态） |
| ReaderStatus | 内存预热 | 来自 ReaderPrefs 全量（移植） |
| UpdatedAtCache | SP `jm_updated_at` | 单 JSON Map，LRU 2000，key=albumId（单源无前缀） |
| WebtoonSliceCache | SP `jm_webtoon_slices` | `{albumId}#{order}` → "page:count,…"，≤512 章 |
| ScrambleCache | SP `jm_scramble` | `{photoId}/{filename}` → 0/1 |
| DownloadManager | 文件系统 | `downloads/{albumId}/{order}/page_{n}.{webp\|jpg}` + manifest.json（原子写，含 title/cover/pages/done） |

> 迁移：全新 App 无历史数据，**不做** PiKA 的源前缀迁移方案（裁决 4 整体作废）。未来若并回多源，再引入键前缀。

---

## 10. 测试与质量

| 层 | 手段 |
|---|---|
| DTO 回归（防 D1 复发） | `tools/dump-fixtures.mjs` 拉真实响应（浏览/搜索/详情/章节/评论/分类/周必看 7 份）存 `app/src/test/resources/jm/`；`JmDtoFixtureTest` 断言 7 个模型可解 + 关键字段非空（category.title、author 是数组、CID 存在、expinfo.level_name 非空…）。服务端形态一变先红在 CI 而不是用户手机 |
| 纯逻辑单测 | pagesOf 边界（125 上限 / total 真实 / 空页）；ScrambleDetector 双门（乱序样本触发、正常样本不触发、8 等分预留）；DomainPool 换域状态机 |
| 金丝雀脚本 | `tools/jm-*.mjs/py` 从 PiKA 移植，加一条「脚本输出与 fixture 一致性」检查；发版前手动跑一遍（或后续挂 GitHub Actions cron） |
| 真机 | 产品设计 §8 checklist 为 v1.0 完成定义；重点：photo 220981 / 800000 / 300000 三样本阅读验证 |
| crash | MainActivity 全局 handler 写 filesDir/crash.log（移植），设置页可查看/导出 |

---

## 11. 构建与发布

- `gbuild.sh` 移植改包名；release 输出 `jm-reader-v{ver}.apk`。
- 独立 GitHub 仓库 + 独立 `update.json`（versionCode/versionName/sha256/url），UpdateManager 移植（UPDATE_URL 换新地址）；REQUEST_INSTALL_PACKAGES 权限保留。
- 版本号策略：versionCode 从 1 起，独立演进，与 PiKA 无对齐关系。

---

## 12. 风险登记（技术侧）

| 风险 | 概率 | 处置 |
|---|---|---|
| 图片乱序规则变化（块数/算法） | 中 | 判定器参数化 splits + 双候选预留；乱序占比埋点；手动翻转兜底 |
| 网安配置挡新图片域名 | 高 | §4.3 与域名自愈**同批实现**；真机 checklist 含「改坏域名自动恢复」项 |
| 签名/版本号风控 | 中 | jm3_version 动态携带；头常量集中；金丝雀脚本 |
| /week 期内容流端点未探明 | 低 | M5 实现时用 jm-shape-probe 扩一组探测；探不明则该入口延后 |
| 禁漫字段再漂移 | 中 | fixture 回归 + 金丝雀脚本双防线（PiKA D1 的教训制度化） |
| 写侧参数误操作 | 中 | 未验证写能力不渲染入口（能力对象中声明为 false） |
| fork 后两 App 重复代码腐化 | 低 | 接受 fork 分叉；共享修复按需人工 cherry-pick，不建共享模块 |

---

## 13. 里程碑与工作量

| 里程碑 | 内容 | 估时 |
|---|---|---|
| M0 | 脚手架：fork 剥多源、包名/身份项、构建跑通、JmApp 装配点 | 0.5 天 |
| M1 | 读链路通：DTO 修正 + fixture 回归 + JmRepository + 浏览/搜索/排行/详情/章节 + 分页/去重 | 1.5 天 |
| M2 | 图片正确性：ScrambleDetector + Coil 集成 + 缓存 key + 下载一致性 | 1 天 |
| M3 | 本地体系：阅读器移植 + 进度/最近/已读/书架/下载管理 | 1.5 天 |
| M4 | 韧性与设置：域名四级 + 重试引擎 + 网安配置 + 设置页/日志/更新/18+ | 1 天 |
| M5 | 完整性：评论只读治理 + 每周必看 + 标签墙 + 四维搜索闭环 + 收藏态回填 + 真机 checklist | 1.5 天 |
| M6 | 写侧（登录/云端收藏/签到/云端历史/发评论） | 卡测试账号，账号到位后 0.5~2 天 |
| **合计** | M0–M5 ≈ **7 天** → v1.0 | |

> 依赖关系：M1 必须先落 fixture 回归再改 DTO（先有网再改代码，PiKA D14 教训）；M2 与 M3 可并行；M6 严格后置。
