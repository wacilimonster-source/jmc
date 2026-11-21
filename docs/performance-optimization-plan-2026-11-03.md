# JM Reader 性能优化技术方案与 Agent 执行任务书

日期：2026-11-03（北京时间）  
源码基线：`3f9367f531d35e5fd73d3d654c60d1f1500d0581`，当前工作区 `versionName=0.5.2`。  
范围：Android 客户端的启动、首页、阅读器、图片处理、下载及本地状态；本轮仅交付方案，不修改业务代码。

## 1. 决策摘要

建议先做 **图片资源预算与请求取消、重复请求治理、下载状态去扫描、首页缓存先展示**，再做阅读器重组和启动优化。暂不整体升级 Compose/Coil，不引入大规模数据库迁移，不重写网络协议。

当前已有磁盘图片缓存、同图请求合并、还原模式缓存隔离、翻页预取取消、低内存预取降级、下载清单原子替换和 release 混淆。优化必须在这些能力上增量进行。

最需要修正的实现事实：

1. **网络并发限制未覆盖自定义图片同步调用。** `BcTls.imageLoaderClient` 设置的 Dispatcher 上限约束异步队列；自定义 Fetcher 使用 `Call.execute()`，并不经过该队列的限流。不能继续把“每主机 8 路”当作这条路径的资源护栏。
2. **还原峰值远大于一张位图。** 处理链包含源 Bitmap、两个全图 IntArray、目标 Bitmap，以及压缩字节；后续 Coil 还要解码。当前仅限制单图宽高，缺少跨阅读、预取、下载的总处理预算。
3. **逻辑取消不等于传输取消。** 预取有 `dispose()`，VM 有代际保护，但同步 `execute()` 未绑定协程取消，仍可能占用连接和带宽直到返回。
4. **目录扫描与高频下载状态耦合。** 两个 VM 监听整个下载任务流，再逐章 `isDownloaded()`；后者每次遍历目录和检查文件。速度采样或逐页进度变化也会触发扫描。

上述是源码确认的机制问题。实际卡顿占比、耗时和优化收益尚未实机测量，本文所有收益目标均为候选验收线，不能作为已实现结果宣传。

## 2. 证据与优先级

以下路径均相对仓库根目录 `G:\game\nw\pka\JM`；执行前按函数名重新定位，避免行号随并行修改失效。

| ID | 优先级 | 代码证据 | 影响与判断 |
|---|---|---|---|
| PERF-00 | P0 | `app/build.gradle.kts`；当前无 Macrobenchmark 模块 | 缺少可重复性能门禁；先建立基线 |
| PERF-01 | P0 | `core/scramble/ImageScrambler.kt:processIfNeeded/processBytesIfNeeded`；`JmScramble.kt:restore` | 全图像素数组与 Bitmap 多份分配，且处理运行在 IO dispatcher；分配行为确定，峰值需测 |
| PERF-02 | P0 | `network/BcTls.kt:imageLoaderClient`；`JmImageFetcher.kt:fetchJmImageOnce`；`network/JmClient.kt:execute` | 同步请求未绑定取消；图片 Dispatcher 上限未限制该同步路径 |
| PERF-03 | P0 | `ImageRetryInterceptor.kt`；`CoilImageInterceptors.kt:CoilRetryInterceptor/CoalescingInterceptor`；`JmImageFetcher.kt:fetchJmImageBytes` | HTTP 层重试、CDN 换线、管线重试、失败后重新竞选叠加；存在长尾和放大请求风险 |
| PERF-04 | P1 | `core/JmRepository.kt:albumCached`；`ui/detail/ComicDetailViewModel.kt:load` | TTL 缓存没有同 key 在途合并；详情与目录并发首次请求可能重复访问 `/album` |
| PERF-05 | P1 | `ui/home/HomeViewModel.kt:loadRank`；`data/RankCache.kt` | 只有网络失败后才读缓存；切榜有代际校验，但旧请求 Job 未保存并取消 |
| PERF-06 | P1 | `ui/reader/ReaderViewModel.kt:preloadNearby` | 每次页码变化先取消全部旧窗口，再请求前后共最多 5 页，包含当前页；窗口交集也被重建 |
| PERF-07 | P1 | `DownloadManager.kt:isDownloaded/startSpeedSampler`；两个 VM 的 `observeDownloaded` | 进度/速度触发逐章目录扫描；下载器与阅读器使用不同传输通道和独立处理配额 |
| PERF-08 | P1 | `ui/reader/ReaderScreen.kt:rows/onPageMeasured`；`data/WebtoonSliceCache.kt:putAll` | `rows` 在组合体中全量构造；测量回调可能在主线程序列化整章稀疏记录 |
| PERF-09 | P1 | `JmApp.kt:onCreate`；`core/log/CrashReporter.kt:install`；`ReaderPrefs.kt:readerMode/brightness` | 启动同步汇总诊断；设置缓存未就绪时 getter 回退 `runBlocking`；实际主线程成本需 trace |
| PERF-10 | P2 | `ReaderViewModel.kt:recordRecentRead`；`LibraryRepository.kt:items` | 逐页写历史并触发全书架合并；大书架下成本需测 |
| PERF-11 | P2 | `app/build.gradle.kts`、`gradle/libs.versions.toml` | 尚无项目生成的 Baseline Profile 配置，待主要执行路径稳定后引入 |

表内省略前缀的源码均位于 `app/src/main/java/com/jmread/`。

## 3. 测量与验收协议（PERF-00）

### 3.1 测试环境与样本

- 使用接近 release 的 `benchmark` 构建：继承 release 的 minify/resource shrink，设为 profileable；测试开关和固定响应仅用于该构建。禁止用 debug 的绝对耗时作上线验收。
- 至少两台真机：一台 `isLowRamDevice=true` 或较小 memoryClass 的设备、一台普通中端设备。无低内存真机时明确标记未覆盖，模拟器仅做功能冒烟。
- 固定刷新率、亮度、温度区间与后台状态；记录机型、Android、ABI、memoryClass、电量、构建 hash、编译模式、缓存条件。A/B 顺序交错，避开热降频。
- 固定数据集：普通页 100 张；含余数条带的乱序图；2160×8192 长图及更大原图；1000 章目录；200 个下载章节；5000 条本地书架条目。合成图片使用可逐像素比较的条纹/坐标格，无需真实用户内容。
- 固定网络响应与故障注入作为回归门禁；真实 CDN 单独跑观察组，记录线路，避免把网络波动当客户端收益。
- 区分四类状态：冷进程＋空图片缓存、冷进程＋热磁盘缓存、热进程＋热内存缓存、完整离线。冷进程通过停止应用实现，不能用清数据替代，清数据会改变登录与历史规模。

### 3.2 指标定义

| 指标 | 测量边界 | 首轮候选目标 |
|---|---|---|
| TTID | 进程启动到首个应用帧 | 中端设备 P50 比基线降低 ≥15%；任何设备 P95 不恶化超过 5% |
| 首页内容可见 | 启动到有效榜单条目布局完成，另记首个封面绘制 | 热磁盘缓存时 P95 比原实现降低 ≥30%；网络刷新不挡缓存展示 |
| 阅读器首图 | 点击进入到恢复目标页图片首次绘制；另记 pages-ready | 固定网络/冷图缓存 P95 降低 ≥20%；离线不等待详情网络结束 |
| 滚动流畅度 | 固定手势 60 秒，FrameTimingMetric/frameOverrun 分布 | 中端 60Hz 场景超预算帧比例 <5%；同时记录 P95/P99，不能只看平均 FPS |
| 图片内存 | Java heap、native heap、PSS 和单次处理 allocation | 压力场景峰值 PSS 降低 ≥25%；持续阅读 30 分钟无 OOM/ANR，无持续增长趋势 |
| 无效传输 | 离开页面/切章后仍在进行的专属请求 | 正在传输的 Call 在取消后 P95 ≤500ms 进入取消/关闭；排队任务不再启动 |
| 重复请求 | 同 albumId/图片身份的同时发起数 | 同 key 同时缓存未命中仅一次有效加载；强制刷新边界单独统计 |
| 下载目录 IO | 计数 `listFiles/stat` 的调用次数 | 仅速度变动的 60 秒期间，下载标记观察链新增目录扫描为 0 |

这些目标须先跑基线。若原值已足够好或波动超过目标，不强行制造百分比收益；记录置信范围和原因，以无回归及资源上限验收。

### 3.3 埋点与产物

增加有限数量的 Trace section：`app.init.*`、`repo.album`、`reader.pagesReady`、`image.queue`、`image.fetch`、`image.restore`、`image.encode`、`image.decode`、`download.reconcile`、`library.merge`。跨线程/挂起阶段用可配对的异步 trace 或明确的分段，不能把同步 section 跨线程闭合。

每个图片逻辑请求记录：匿名内容 key、来源（可见/预取/下载）、排队时间、缓存层、实际传输次数、取消时间、处理像素数、处理耗时。默认仅内存聚合计数，按需导出，避免高频同步日志成为新瓶颈。

交付 `reports/performance/baseline-2026-11-03/`（执行日期变化时另建目录）：环境说明、原始 benchmark JSON、Perfetto trace 索引、数据集说明、汇总 CSV、失败记录。常规每组至少 10 次；P95 结论使用至少 30 次独立样本，30 分钟稳定性测试另计。没有设备时交付脚本并标注“待实测”，不得填造测量结果。

官方方法依据：[Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)。

## 4. 详细实现方案

### PERF-01：减少图片还原副本，建立总处理预算

**现状**：处理上限 2160×8192 时，单份 ARGB_8888 像素约 67.5 MiB。当前链涉及 2 个 Bitmap 与 2 个全图 IntArray，像素存储分配量可达约 270 MiB，尚未计压缩 ByteArray、输出流及 Coil 解码；这不是实测同时驻留峰值，GC 时机和尺寸会影响结果。Android 26+ Bitmap 像素在 native heap，不能只观察 Java heap。

**第一阶段（必做）**：

1. 新增 `ImageProcessingBudget`（也可并入 `MemoryBudget`）：按 bounds、sample、源/目标位图、工作缓冲估算字节，限制**所有调用方共用**的在途处理预算。先取保守配置，低内存只允许一张进入还原；普通设备也不能直接按 IO 线程数放开。等待必须可取消，释放用 `finally`，重任务不得永久饥饿。若单任务估算已超过总预算，必须进一步降采样或明确拒绝，禁止无限等待永远无法满足的配额；预算同时考虑现有图片缓存与可见 Bitmap 的驻留余量。
2. 网络 IO 与像素计算分离；像素还原/压缩进入受限 CPU 执行器。只用 `limitedParallelism` 不能保证跨入口总字节预算，因此预算器是唯一入口。
3. 用源 Bitmap 到目标 Bitmap 的整数矩形条带搬运替代两个全图 IntArray。源/目标高度和余数行映射完全沿用现有规则，关闭滤波，禁止条带各自缩放。也可用固定行缓冲实现，选实测更优方案。
4. Bitmap、输出流与预算 token 都用 `try/finally` 管理；处理阶段间检查取消。`Bitmap.compress` 不保证立刻可中断，只承诺其返回后不继续编码/缓存下一阶段。
5. bounds 校验用 Long 计算像素/字节，拒绝异常尺寸、溢出和过大响应；响应大小上限先按固定数据集实测定值，并给出明确错误，不能无限 `bytes()`。

**验收**：条带搬运与旧纯函数在解码后的像素逐像素一致，覆盖各 segments、余数、宽高边界、强制原图/还原、非 photo/GIF；lossy WebP 不做字节相等断言。压力测试预算从不超发，取消/异常不泄漏 token。至少测单图峰值与“阅读＋双下载”峰值。

**第二阶段（有证据再做）**：在线从“还原→有损 WebP 编码→Coil 二次解码”改为缓存编码源、按解码结果还原，或提供可直接返回图像的分支。此项会改变磁盘内容、Keyer 和解码顺序，必须独立 ADR、缓存版本、画质与 CPU 对比后立项，不能混入第一阶段。超长图区域解码也放在此阶段，需要先解决条带映射和完整图坐标问题。

### PERF-02：可取消传输与全局资源调度

**实现**：

1. 新增协程网络适配器：`Call.enqueue()` 配合 `suspendCancellableCoroutine`，`invokeOnCancellation { call.cancel() }`。处理回调/取消竞态；已收到但无法交付的 Response 必须关闭；响应体读取期间也必须维持取消绑定，不能收到响应头就解除绑定。
2. API、自定义图片 Fetcher 统一采用适配器。下载器在本阶段接入共享调度入口；迁移到同一 OkHttp client 时保留 BC TLS、UA、超时和 CDN 行为。
3. 新增 `ImageWorkScheduler`，网络配额与处理配额分开。初始普通档全局图片传输 4、同 host ≤4；预取≤1、下载≤1，至少给可见页保留 2 个位置。低内存档全局 2、预取 0、下载最多 1，并保证可见页可用。数值是可调起点。
4. 调度优先级：可见页 > 相邻预取 > 后台下载；不因低优先级排队占住处理 token。网络完成后释放网络配额再申请处理预算，不能拿着处理名额等待网络；重试退避释放网络槽位。
5. 调度全局配额跨 CDN host 生效；切换 host 不会额外获得新配额。可见请求遇到已有低优先级同内容任务时提升其优先级；先完成合并身份设计再实现提升。

**验收**：慢速 body、无响应、快速切章、取消排队、取消与 onResponse 同时发生都无泄漏；取消不标记 CDN 故障，不写 FAILED，不再换线；两个下载与阅读并存时可见页始终有资源。保留 BC TLS 兼容性，不能以换回系统 TLS 作为优化手段。

依据：[OkHttp 4.12 Dispatcher 源码](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Dispatcher.kt)。其中异步队列的提升逻辑检查并发上限，同步调用只登记 runningSyncCalls。

### PERF-03：收敛重试与同图合并语义

1. 先加计数确定最坏放大路径。现有 HTTP 层最多初始＋4 次、管线初始＋3 次，还叠加 CDN host 和合流失败重新竞选，不能把各层预算分别视为总预算。
2. 图片逻辑请求定义共享 `RetryBudget`：初始建议最多 4 次实际传输、总 deadline 20 秒；每次换 host、重新竞选和管线解码失败后的 fresh fetch 都计入同一预算。20 秒不是服务成功保证，超出后给出可重试错误。
3. 一个协调器负责重试与可取消退避；关闭被覆盖的 HTTP `Thread.sleep` 重试。区分网络错误、403/429/5xx、确定性 404、取消与坏缓存。坏缓存最多作一次失效重拉，且消耗总预算。429 尊重有效 Retry-After，但不能超出 deadline。
4. 同图合并分两种身份：内容传输/还原可按规范内容 key 合并；最终解码结果还需包含 size、precision、scale、转换参数及影响结果的配置。保留自动、`#m0`、`#m1` 隔离和 `#retry` 不进入持久缓存的现有约定。
5. 等待者独立取消不取消其他消费者；最后一个消费者退出才取消共享传输。失败后按统一预算重试，不能每个切片各自重新获得完整预算。共享对象用订阅计数和明确作用域管理，异常后清理表项。
6. 首轮不擅自把不同 host 的 URL 合并为相同内容；若要去 host，先验证所有候选 CDN 同路径内容一致，并设计版本/查询参数保留规则。

**验收**：六个同页切片同时失败时传输总量仍符合预算；失败风暴不锁死；某切片离屏不影响其余切片；取消不重试。非幂等收藏翻转等写接口完全不进入此重试策略。

### PERF-04：Repository 同 key 在途合并

- 优先只改 `albumCached`：短锁维护 `key -> Deferred`，锁外执行网络；双重检查 TTL，成功后写缓存并删除 in-flight；失败不缓存，表项必须清理。禁止用全局 Mutex 跨网络串行所有 album。
- 共享任务由受控 scope 持有，采用订阅计数或明确的短 deadline，避免首个调用者取消影响其他调用者，同时避免无消费者仍永久运行。以 PERF-03 的合并约定为准。
- 使用单调时钟计算进程内 TTL，缓存增加明确容量上限（初始 128 album，可调整），避免仅以访问时清过期代替容量限制。
- 只对已确认可共享的只读请求使用；将来涉及账号权限的缓存 key 包含 sessionGeneration，登录/退出主动失效。旧会话响应不能回填新会话缓存。

**验收**：同 id 20 个并发调用只有一次上游；不同 id 并发正常；首个订阅者取消、全体取消、异常、TTL 到期、退出登录时均符合约定。详情首次加载时目录和正文复用同一 album。

### PERF-05：首页缓存先展示，切榜取消旧加载

- `loadRank` 先在 IO 读对应 type 缓存，在代际仍有效时发布；缓存可用则进入 refreshing，保留条目，网络成功后更新。首次完全无内容才用全屏 loading。
- 显示缓存时间，保留已有 7 天有效期；成功空结果覆盖旧缓存，用户不能误以为旧榜单仍有效。刷新失败保留缓存并给可重试提示。
- 保存 `rankJob`，切榜取消上一 job；代际校验继续保留。JSON 编解码和缓存读取移到 IO，禁止在主线程做列表序列化。
- 首屏优先加载当前榜单及其封面。其他分区、榜单能力探测在首屏内容可见后低优先级触发；保留必要入口，不能因懒加载导致功能永久消失。已有依赖关系的 week→content 请求不强行并发。

**验收**：慢网且已有缓存，首次榜单显示不等网络；快速切榜不出现旧响应；无缓存、过期、损坏、服务端空榜均有确定行为。与 PERF-02 联测旧请求实际取消。

### PERF-06：差量、方向性预取

- 以规范图片 key 维护窗口，只 dispose 离开窗口的项，保留交集。当前可见页由展示管线负责；若正在预取则提升/复用，避免另起传输。
- 正常档先前进 1 页，空闲且预算允许时再前进第 2 页，后退最多 1 页；快速 fling 时暂停新增低优先级任务，停止后恢复。不要只根据页码抖动反复重建窗口。
- 压力/低内存下沿用禁预取；网络受限时缩到 0～1 页。压力恢复需主动通知窗口更新，不能等用户下一次翻页才生效。
- 解码尺寸、还原模式与展示保持一致；手动翻转、章节、viewport 或模式变化使对应窗口失效。可探索“仅预取编码字节到磁盘”，但须确认当前自定义 Fetcher 的实际执行行为，不能只换一个请求标志就假定没有解码。

**验收**：页码 10→11 时交集请求不被取消重建；切章后窗口为空；手动模式不命中错误缓存；预取命中率和浪费字节可报告，首图和总峰值不回退。

### PERF-07：下载领域状态与展示状态分流

1. `DownloadManager` 提供按章节的轻量 `downloadedChapterIndex` 或 `downloadStructureVersion`，仅在新增/删除/完成/校验失效时变化。字节进度和速度保留独立流，阅读器/详情目录只订阅结构流。
2. 启动后台校验一次并建立内存索引；任务完成后立即更新并持久化。实际打开离线章节时仍校验该章文件，缺失时使索引失效并回退，不用“相信缓存”取代完整性检查。
3. 目录只在恢复、打开、删除和显式校验时扫描；同一次校验复用 `listFiles` 结果，避免 count、length、walkTopDown 多次遍历。大量任务分批恢复，先提供清单元数据，再做完整校验，标记待校验状态。
4. 数据量大时按 key 更新运行态，再输出必要的 UI 快照；先用 profiler 确认 `.map` 全任务列表和 JSON 全量 persist 成本，不立即迁移 Room。
5. `persist` 的实际写盘与编码移到单写者 IO worker；状态变更携带版本，按序合并非关键写，关键完成/删除有可 await 的持久化边界。保留 `.part` 隔离、恢复合并与 tombstone 语义，不能把已删除任务恢复回来。
6. 空闲速度采样改为由活跃状态启动/停止，避免目前常驻每 5 秒空转；只更新变化的速度值。

**验收**：速度更新不触发目录扫描；200 章目录下观察流只做内存查询；外部删页后再次打开可检测；杀进程恢复、并发入队/删除/恢复、断网等待、通知与离线页数均通过既有回归。

### PERF-08：阅读器模型缓存与切片缓存批量写

- 将 `rows` 和 prefix 放进按“章节身份＋pages 版本＋sliceCounts 版本＋模式”更新的派生模型。页码、面板、亮度变化不能重建全章 rows。禁止只用 `pages.size` 作完整 key，同页数换章也必须更新。
- 保留现有整数像素切片边界、同页统一 ratio、稳定 lazy key；同样身份的项使用合适 contentType。不要先通过删切片或改变缩放掩盖性能问题。
- 测量回调只更新内存并发出事件。`WebtoonSliceCache` 在单写者 IO 中合并约 300～500ms 的变化，一次序列化一章；退出/切章按旧章节身份提交，不把旧结果写入新章。
- 移除仅凭比值对象不变就重复上报的成本；保留必要的 ratio 容差和变化检测。修正容差逻辑前补边界测试，确保旧缓存能被真实测量纠正。
- 将工具栏、页码、弹窗状态尽量收敛到小作用域；使用 Compose trace 定位后再做状态读取下沉，禁止机械添加 `@Stable/@Immutable` 或为所有便宜表达式加 remember。

**验收**：1000 页模式切换/跳页映射正确；常规页码变化不重新构造 rows；同页数切章、旋转、恢复进度、强制翻转、多切片重试、最后切片已读判定无回归。以 `docs/reader-webtoon-overlap-2026-11-01.md` 的边界行为为兼容基准。

依据：[Compose 性能最佳实践](https://developer.android.com/develop/ui/compose/performance/bestpractices)，建议缓存昂贵计算、提供 lazy key/contentType，并减少高频状态对大组合范围的影响。

### PERF-09：启动关键路径与设置异步加载

- 用 PERF-00 trace 给所有 init 分类。首帧前保留依赖容器、异常 handler 安装、必要设置状态与图片管线装配；同步轻量操作未经测量不用全改成后台。
- `CrashReporter.install` 拆成“立即装 handler”和“后台汇总历史证据”；汇总与新会话写文件要有隔离/顺序，不能丢失首帧前崩溃，也不能清理掉新产生的诊断。
- `readerMode/brightness` 以及 UI 可达的同类 getter 不再在 cache miss 时 `runBlocking`。用 `StateFlow<SettingsState>` 或集中加载结果，明确 Loading/Ready；阅读器可等小型设置状态就绪后显示，避免先默认亮度/模式再跳变。
- 设置加载、书架预热、下载恢复、签到、setting 刷新明确依赖和优先级；后台网络及磁盘恢复不能同时抢占首屏图片预算。BC TLS 的必要安装顺序保持正确。

**验收**：冷启动直达阅读器、慢磁盘、损坏缓存、设置尚未预热时无主线程阻塞；阅读模式和亮度不闪变；首帧前崩溃仍可取证。StrictMode 只在测试构建启用，观察日志不能影响 release 性能。

### PERF-10：历史持久化与书架重算降频（数据支持后实施）

- 分离“内存进度实时变化”与“历史元数据落盘”。历史按作品会话首次进入、必要标题补全、约 1 秒合并及切章/后台/退出 flush；最终页码仍按现有修订号规则提交。
- 书架只在归属、排序、已读状态等必要数据变化时合并；独立进度变化优先更新该作品。先测 `library.merge`，5000 条仍低成本则只做写盘合并，不实施复杂增量索引。
- 清除历史的 revision、撤销恢复、账号代际优先于性能；退出后的旧任务不能复活已清除记录。强杀进程允许的进度丢失窗口需记录，候选最多 1 秒，关键生命周期事件立即 flush。

**验收**：连翻 100 页写入次数显著减少；正常退出进度精确；清除/撤销/退出竞态沿用现有回归。把持久化窗口变化写入 PR，不能静默改变语义。

### PERF-11：Baseline Profile

- PERF-01～09 稳定后建立 baseline profile 生成模块，覆盖冷启动、首页列表、详情到阅读、条漫滚动与书架；采用确定数据，生成过程不依赖实时 CDN。
- 选择与现有 AGP 8.9.0、Kotlin 2.1.20 兼容的插件版本并锁定；不为接 profile 顺手升级整套依赖。
- release 包确认包含生成产物，分别比较无 profile 与有 profile 的编译模式。应用通过 APK 分发，必须验证实际安装路径的 profile 生效情况，不能只根据 Play 分发假设收益。

**验收**：生成步骤可重复、APK 产物可检查、目标路径有 A/B 数据；体积增量与生成耗时记录。官方说明：[Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)。

## 5. Agent 分工、依赖与合并顺序

以下是交给后续 agent 的分工建议，本次没有启动或派发其他 agent。

| 执行包 | 包含任务 | 主要文件归属 | 开始条件 | 交付/估算工作量 |
|---|---|---|---|---|
| A：性能基线 | PERF-00 | benchmark 模块、trace 接口、测试 fixtures、reports | 立即 | 基线/脚本/指标口径；1～2 人日＋设备测试 |
| B：图片计算 | PERF-01 | ImageScrambler、MemoryBudget、新处理预算器 | A 定义计数与数据集后 | 还原正确性、预算与峰值对比；2～3 人日 |
| C：网络调度 | PERF-02/03 | BcTls、JmClient、Fetcher、两个重试/合并拦截器、新 scheduler | A 完成故障夹具；先约定 B 接口 | 取消、并发、重试统一；3～5 人日 |
| D：首页与数据请求 | PERF-04/05 | JmRepository、HomeViewModel、RankCache | 可与 B/C 并行；接 C 取消接口后联测 | 合流与缓存先展示；1～2 人日 |
| E：下载状态 | PERF-07 | DownloadManager、下载索引及持久化 worker | C 先提交接口约定 | 扫描去耦、恢复回归；2～3 人日 |
| F：阅读与启动 | PERF-06/08/09 | ReaderScreen、ReaderViewModel、WebtoonSliceCache、JmApp、ReaderPrefs、CrashReporter | B/C/E 接口稳定 | 预取/重组/启动；3～4 人日 |
| G：后续收益 | PERF-10/11 | LibraryRepository、profile 模块 | A 证明成本且前述路径稳定 | 独立 PR；1～3 人日 |

估算为熟悉 Android 的执行者工作量，不是日历承诺；首次设备环境搭建、疑难兼容问题另计。推荐分三批集成：

1. **批次一：A → B/C/D**。A 的测试夹具完成即可并行开发，源码改动前保存基线构建。
2. **批次二：E → F**。C 与 E 都会修改 DownloadManager 的传输部分，由 E 拥有文件，C 只提供新传输 API 与适配补丁；E 向 VM 提供状态流，F 修改 ReaderViewModel，D 修改详情 VM 的对应观察点。禁止两包直接同时编辑同一文件。
3. **批次三：G＋统一设备验收**。每批都重跑受影响场景，最终再跑整套；收益不明确的 P2 可不合入。

建议共享接口先写小 PR：`ImageRequestClass`、`ImageWorkScheduler`、`ImageProcessingBudget`、`downloadedChapterIndex`、指标 key；实现者可以调整命名，但语义需先达成一致。每项优化独立提交，不能把全部任务堆成一个无法定位收益来源的大 PR。

## 6. 回归门禁与交付模板

### 6.1 必须保持的行为

- 缓存 key 自动/强制原图/强制还原隔离；人工重试可绕过坏缓存；不同解码尺寸不错误共享 Bitmap。
- 条带还原的余数行正确；条漫相邻切片无缝无重叠；恢复原章节进度准确。
- 完整离线章不受后续详情/目录接口失败影响；不完整离线页不能误判读完。
- 取消、删除、重试的旧执行者不写回新任务状态；`.part` 文件不计页数；清单恢复不复活已删除任务。
- 收藏翻转等非幂等操作不重放；账号切换不共享旧权限数据；阅读记录清除修订号继续生效。
- 低内存关闭预取与系统 trim 行为保留，并扩展到所有图片处理入口。

### 6.2 验证命令与设备流程

仓库现有 JVM 回归：在根目录执行 `./gradlew.bat :app:testDebugUnitTest`。涉及 release/R8、BC TLS、Coil 自定义组件时，再执行 `./gradlew.bat :app:assembleRelease` 并安装设备验证。

PERF-00 新增模块后提供真正可运行的 Macrobenchmark/仪器测试命令；当前仓库没有这些任务，不能把示例命令当已存在任务。MockWebServer、AndroidX benchmark 等新增测试依赖由 A 选取兼容版本并锁定。纯算法走 JVM 测试，Bitmap/Canvas 和实际取消链走仪器测试，性能走真机 release-like 测试。

每个执行包提交以下内容：

```text
任务 ID / 实际提交 hash：
修改文件与行为变化：
变更前后环境、数据集、缓存/编译条件：
原始数据、trace 和截图路径：
指标对比（样本量 / P50 / P95 / 峰值 / 失败次数）：
正确性与异常场景测试结果：
未覆盖的设备或场景：
参数默认值、启用边界及回滚方法：
```

单元测试通过不等于性能通过；缺少设备时仅可报告“实现与 JVM 回归完成，设备性能待验收”。未观察到 OOM 不能证明预算正确，需有预算 token 峰值和 native/Java/PSS 数据。

### 6.3 回滚与发布

- 首轮保持现有磁盘图片格式及 `#de2` 语义；算法输出内容或缓存格式真正变化时才升级 key 版本。旧缓存自然淘汰，避免升级时全量删图导致首屏流量尖峰。
- 调度配额、预取策略和新算法提供内部构建开关或单独可回退提交；不强加用户设置入口。每个回退路径也要通过基本正确性检查。
- 发布前使用同基线数据复验低内存阅读、双下载并行和断网恢复；若首图 P95、失败率或内存显著恶化，回退对应提交/配置，再定位原因。
- 不同时进行 Compose/Coil/BC 版本升级、大型 UI 改版和本方案优化，便于归因。后续如需引入 Room、图像区域解码或新缓存格式，另写迁移与回退方案。

## 7. 技术资料与证据边界

本方案通过本地静态阅读确认实现路径，未在本次分析中运行应用、构建、基准或真实 CDN 测试。官方资料用于核对工具和库语义，项目优化收益必须由第 3 节测试产生。

- [Android Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)：端到端性能与 trace 产物。
- [Compose best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)：昂贵计算缓存、lazy 布局与状态读取范围。
- [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)：关键执行路径预编译。
- [Android Bitmap 内存](https://developer.android.com/topic/performance/graphics/manage-memory)：API 26+ 像素存储位于 native heap；不要回收仍被渲染/缓存引用的图像。
- [OkHttp 4.12 Dispatcher](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Dispatcher.kt)：当前项目版本的同步/异步并发语义。

优先执行 PERF-00～03；PERF-04/05 可并行取得较直接的请求数与感知速度收益。其余按基线成本推进，验收数据决定最终是否合入。
