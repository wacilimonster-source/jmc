# 性能基线报告（2026-11-03）

源码基线：`3f9367f531d35e5fd73d3d654c60d1f1500d0581`（`versionName=0.5.2`）
本轮改动：PERF-01～05、PERF-00 trace 埋点（见文末“已落地改动”）。

> **状态：待实测。**
> 本次执行环境没有连接 Android 真机/模拟器，也没有建立 Macrobenchmark 模块。
> 本文只交付测量口径、数据集说明与采集脚本；**未产生任何性能数字**，
> 不得把候选目标当作已实现收益对外宣传。

## 1. 环境记录（执行时填写）

| 字段 | 设备 A（低内存） | 设备 B（中端） |
|---|---|---|
| 机型 / 代号 | 待填 | 待填 |
| Android / ABI | 待填 | 待填 |
| memoryClass / isLowRamDevice | 待填 | 待填 |
| 刷新率 / 亮度 / 温度 | 待填 | 待填 |
| 构建 hash / 编译模式 | 待填 | 待填 |
| 缓存条件（冷进程/热磁盘/热内存/离线） | 待填 | 待填 |

要求：

- 使用接近 release 的 `benchmark` 构建（继承 minify/resource shrink，profileable）；
  禁止用 debug 绝对耗时做上线验收。
- 至少两台真机：一台 `isLowRamDevice=true` 或较小 memoryClass；无低内存真机时明确标记“未覆盖”。
- 固定刷新率、亮度、温度区间与后台状态；A/B 顺序交错，避开热降频。
- 冷进程通过 `am force-stop` 实现，不能用清数据替代（会改变登录与历史规模）。

## 2. 固定数据集

| 数据 | 规模 | 用途 |
|---|---|---|
| 普通页 | 100 张 | 阅读器首图、滚动 |
| 含余数条带的乱序图 | 覆盖 2/4/10/12/14/20 条 | 条带还原正确性 |
| 长图 | 2160×8192 及更大原图 | 图片内存峰值 |
| 目录 | 1000 章 | 详情/目录扫描 |
| 下载章节 | 200 章 | 下载状态流 |
| 本地书架 | 5000 条 | `library.merge` |

合成图片使用可逐像素比较的条纹/坐标格，无需真实用户内容。
固定网络响应与故障注入作为回归门禁；真实 CDN 只跑观察组并记录线路。

## 3. 指标与首轮候选目标

| 指标 | 测量边界 | 首轮候选目标 | 本次结果 |
|---|---|---|---|
| TTID | 进程启动到首个应用帧 | 中端 P50 比基线降低 ≥15%；任何设备 P95 不恶化 >5% | 待实测 |
| 首页内容可见 | 启动到有效榜单条目布局完成 | 热磁盘缓存 P95 降低 ≥30%；网络刷新不挡缓存展示 | 待实测 |
| 阅读器首图 | 点击进入 → 恢复目标页首次绘制 | 固定网络/冷图缓存 P95 降低 ≥20%；离线不等待详情网络 | 待实测 |
| 滚动流畅度 | 固定手势 60s，FrameTimingMetric | 中端 60Hz 超预算帧 <5%，同时记录 P95/P99 | 待实测 |
| 图片内存 | Java heap / native heap / PSS / 单次处理 allocation | 压力场景峰值 PSS 降低 ≥25%；30 分钟无 OOM/ANR | 待实测 |
| 无效传输 | 离开页面/切章后仍在进行的专属请求 | 取消后 P95 ≤500ms 进入取消/关闭；排队任务不再启动 | 待实测 |
| 重复请求 | 同 albumId/图片身份的同时发起数 | 同 key 缓存未命中仅一次有效加载 | 待实测 |
| 下载目录 IO | `listFiles/stat` 调用次数 | 仅速度变动的 60s 内新增目录扫描为 0 | 待实测（PERF-07 未实施） |

若原值已足够好或波动超过目标，不强行制造百分比收益；记录置信范围与原因，
以“无回归 + 资源上限”验收。

## 4. 采集方法

### 4.1 现有 JVM 回归

```bash
./gbuild.sh :app:testDebugUnitTest
```

本轮新增：

- `JmScrambleStripTest`：条带计划与旧全图 `arraycopy` 逐像素一致；
- `ImageProcessingBudgetTest`：预算不超发、等待可取消、超预算明确拒绝；
- `ImageWorkSchedulerTest`：全局/每 host/预取/下载配额与保留槽位；
- `ImageRetryBudgetTest`：4 次传输上限、deadline、同 key 共享、手动重试新预算；
- `AlbumCoalescingTest`：20 个并发同 id 只打一次上游、不同 id 并发、订阅者取消语义、失败不缓存。

### 4.2 真机采集脚本

```bash
# 连接设备后（release 或 profileable 构建）
tools/perf/collect-baseline.sh --serial <serial> --runs 10 \
  --out reports/performance/baseline-2026-11-03
```

脚本产出：

- `raw/ttid-<run>.txt`：`am start -W` 原始输出（TotalTime/WaitTime）；
- `raw/gfxinfo-<run>.txt`：`dumpsys gfxinfo framestats`；
- `raw/perfetto-<run>.perfetto-trace`：冷启动 + 首页 + 阅读器路径；
- `summary.csv`：每次运行的 TTID 与帧统计汇总。

Perfetto 配置见 `tools/perf/perfetto-config.pbtxt`，包含 atrace 类别
`am wm gfx view input res dalvik bionic power` 与 sched/freq/idle。

> 说明：仓库当前没有 Macrobenchmark / 仪器测试模块。脚本是 adb 级别的
> 可运行替代方案；建立 benchmark 模块（含 MockWebServer 固定响应）需单独 PR，
> 不能把示例 Gradle 命令当作已存在任务。

## 5. Trace section 约定

代码已埋入以下固定名称（`PerfTrace`，`Trace.isEnabled()` 为 false 时零开销）：

| Section | 类型 | 位置 |
|---|---|---|
| `app.init.crash` / `.memory` / `.settings` / `.data` / `.image` | 同步 | `JmApp.onCreate` |
| `repo.album` | 异步 | `JmRepository.albumCached` 共享请求等待 |
| `reader.pagesReady` | 异步 | 点击进入 → 本章 pages 发布 |
| `image.queue` | 异步 | `ImageWorkScheduler.withSlot` 外层（含排队） |
| `image.fetch` | 异步 | 单次 HTTP 传输 |
| `image.restore` / `image.encode` | 同步 | 条带搬运 / WebP 编码 |
| `download.reconcile` | 异步 | 启动清单恢复与目录校验 |
| `library.merge` | 同步 | 书架四源合并 |

## 6. 已落地改动（本轮）

- **PERF-01**：`ImageProcessingBudget` 跨阅读/预取/下载共享字节预算；
  还原改为源→目标 Bitmap 整数条带搬运（不再分配两份全图 `IntArray`）；
  解码/还原/压缩进入受限 CPU 执行器；bounds 用 Long 估算；响应体分块读取并限 48MB。
- **PERF-02**：新增 `OkHttpClient.withCall`（enqueue + 取消绑定到整个调用生命周期）；
  `JmClient` 与图片 Fetcher 全部改用；新增 `ImageWorkScheduler`
  （普通档全局 4 / 同 host 4 / 预取 1 / 下载 1 / 可见保留 2；低内存全局 2 / 预取 0）。
- **PERF-03**：删除 HTTP 层 `Thread.sleep` 重试；新增 `ImageRetryLedger/ImageRetryBudget`
  （同 key 共享 4 次实际传输 / 20s deadline），管线重试只消耗同一预算；
  404 不重试，429 尊重 Retry-After 且不超 deadline。
- **PERF-04**：`albumCached` 同 key 在途合并（`Deferred` + 订阅计数 + 应用级 scope），
  TTL 改单调时钟，LRU 上限 128，缓存 key 含 `sessionGeneration`。
- **PERF-05**：首页先读对应档位缓存再网络刷新；保存 `rankJob` 并取消上一请求；
  RankCache 读写/JSON 解码移到 IO；成功空结果覆盖旧缓存；
  榜单内容可见后才触发档位探测与分区加载，避免与首屏抢请求。
- **PERF-00**：`PerfTrace` 与上表 section；本目录与采集脚本。
- **PERF-07（2026-11-04 补充）**：`DownloadManager` 新增 `downloadedStructure`
  内存索引（comicId → 已完成章号集合），阅读器/详情目录改订阅结构流，
  进度/速度更新不再触发逐章 `isDownloaded()` 目录扫描；打开离线章仍做文件级校验，
  缺失时把该章从索引摘除；速度采样改为活跃信号启停（空闲零唤醒）且只写变化的速度值；
  `persist` 移到单写者协程，非关键写合并、完成/删除有可 await 的落盘边界。
- **PERF-06（2026-11-04 补充）**：预取窗口差量维护（保留交集、只 dispose 离窗项），
  窗口=后退 1 页+前进≤2 页；快速 fling 暂停新增；章节/模式/内存压力整窗失效。
- **PERF-08（2026-11-04 补充）**：阅读器 `rows`/前缀和改为派生模型
  （读 `sliceCounts` 参与订阅，页码/面板/亮度变化不再全章重建 List；
  remember 以 pages 身份+模式为 key，同页数换章也会更新）；LazyColumn 行加稳定 contentType；
  `WebtoonSliceCache` 测量写入进内存缓冲，由单写者 IO 合并约 400ms 一次性序列化，
  切章/退出按旧章节身份 `flushNow`。
- **PERF-09（2026-11-04 补充）**：`CrashReporter.install` 拆成「立即装崩溃钩子 +
  后台汇总历史证据」，消费型清空保证不丢新产生的诊断；
  `ReaderPrefs.readerMode/brightness` getter 移除 `runBlocking` 回退，
  改为 `hotSettings`（ready/mode/brightness）状态流，阅读器等就绪后一次性应用，
  避免默认值先渲染再跳变；DataStore 读取 2s 超时降级默认值。

## 6.1 本机验证结果

| 检查 | 结果 |
|---|---|
| `./gbuild.sh :app:testDebugUnitTest` | 通过（含本轮新增 5 个测试类） |
| `./gbuild.sh release`（R8 + resource shrink） | 通过 |
| 真机 TTID / 帧率 / PSS / 取消时延 | **待实测**（无设备） |

单元测试通过不等于性能通过；设备性能仍需按第 4 节采集。

## 7. 未覆盖 / 后续

- 未在真机运行，所有性能目标待实测；图片内存需同时记录 native/Java/PSS。
- PERF-10（历史/书架写盘降频）、PERF-11（Baseline Profile）未实施，
  按文档属批次三：PERF-10 需先测 `library.merge` 成本，PERF-11 待前述路径稳定后立项。
- PERF-02 的「可见请求提升已有低优先级同内容任务」未实现（合并身份已具备，优先级提升待下一批）。
- `image.decode` trace 未加（Coil 内部解码阶段，需自定义 Decoder 包装）。
- PERF-06 的「压力恢复主动通知窗口更新」未做：压力解除后由下一次翻页回调重建窗口（60s TTL）。
- Macrobenchmark 模块未建立（无设备且需新增依赖）；adb 采集脚本为可运行替代。
- 低内存真机缺失时，低内存档位（预算 64MB、全局 2 路、预取 0）只能靠 JVM 单测与代码审查验证。
