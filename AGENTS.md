# JM Reader 工程守则（供所有 agent / 开发者）

> 与 PiKA 的 AGENTS.md 同源同宗旨：这不是建议清单，而是本仓库的硬约束。
> 违反其中任何一条，都会让后续维护者付出比本次改动更高的成本。
> 事故复盘与「假验证」清单见 PiKA 仓库 `docs/ENGINEERING-LESSONS.md`（同源方法论）。

## 1. 项目速览

- 单模块 Android App：Kotlin + Jetpack Compose + Material 3，`minSdk 26 / targetSdk 35`。
- 单 Activity + Navigation Compose；入口 `app/src/main/java/com/jmread/MainActivity.kt`。
- 分层（依赖方向只能向下）：
  - `ui/`：Compose 页面与 ViewModel（只依赖 core/data/network 的公开接口）
  - `core/`：JmRepository（数据门面）、download、library、scramble（图片乱序管线）、netconfig、update、log、checkin
  - `network/`：JmClient（签名/解密/换域）、BcTls、DTO
  - `data/`：DataStore / SharedPreferences 持久化
- 禁漫单源，无源抽象。能力开关集中在 `core/JmCapabilities.kt`（能力说真话：端点不存在就不渲染入口）。
- 图片乱序还原是本项目特有管线：`core/scramble/`，规则与缓存键见 `JmImageFetcher.kt` 头注释。

## 2. 构建与验证

```bash
# 编译（最快反馈）
./gbuild.sh :app:compileDebugKotlin

# 单元测试（纯 JVM）
./gbuild.sh :app:testDebugUnitTest

# 打 release APK（单测门禁 + 按版本重命名）
./gbuild.sh release
```

- 正式发版：bump `versionCode/versionName` → `./gbuild.sh release` → 复测 → commit（`release: x.y.z …`）→ push。
- 更新通道：仓库根 `update.json`（sha256/version/apkUrl/notes），raw 直链即应用内检查更新的数据源。

硬规则（同 PiKA）：

1. **构建成功 ≠ 可运行**。涉及启动、Compose 状态、生命周期、DataStore 初始化的改动，必须在模拟器冷启动一次。
2. **UI 改动必须实测深浅色 + 至少一次字体缩放 1.3**；涉及阅读器的还要测滚动流与横滑翻页。
3. **网络/重试/下载改动必须做故障注入**（debug 变体深链 `jmread://mock?fault=offline|slow|error|truncate`），不能只测正常网络。
4. 提交前跑 `python tools/code-health.py --check`；结构指标变差或测试文件数减少视为回归（基线 `tools/code-health-baseline.json`）。
5. 修复 bug 必须补一个能复现该 bug 的纯 JVM 单测；无法单测的，在提交说明里写清手动复现步骤与实测结果。
6. **复测一律用模拟数据**（debug 模拟层与单测 fixture 同源，见 `app/src/debug/java/com/jmread/mock/`），不依赖真实账号与真实内容。

## 3. 代码硬规则

### 3.1 先找现成的，再写新的

- 写筛选/排序/分页/重试/图片缓存键前，先全局搜索现有实现：
  `userMessage`（错误文案映射）、`filterThenPage`（筛选分页）、`pagesOf`、`jmImageCacheKey`（缓存键）、
  `AuthorName.parse`（署名解析）、`MockTemplates`（模拟模板）、`RetryableCoverImage`（封面重试）。
- **禁止复制粘贴后改一处**。同一语义出现第二份实现时，先提取共享函数。
- **禁止新增「兼容旧引用」别名/死代码**；`core/source/` 这类空目录不留。

### 3.2 导入与命名

- 业务代码中**禁止全限定名**（`com.jmread.xxx.Yyy`、`androidx.xxx.Yyy`），一律 import
  （KDoc 链接与字符串字面量除外）。code-health 的 `fqn_lines` 指标盯这个。
- 排序/分页/页大小等常量只能有一个来源；魔法数字进常量并注明出处。

### 3.3 协程与取消

- 协程内捕获异常用 `runCatchingCancellable`（`core/CoroutineExt.kt`），不要把取消当失败继续写状态。
- 后台落盘用 `ioScope`/`Dispatchers.IO`；主线程不做磁盘 IO。
- 共享可变状态落盘走已有「单写者 + 防抖」范式（`UpdatedAtCache`、`WebtoonSliceCache`、`DownloadManager` manifest）。

### 3.4 失败语义

- 用户可见错误一律走 `Throwable.userMessage(action)`（`core/model/UserMessages.kt`），原始异常只进调试日志。
- 加载/失败/空三态必须区分：失败不许伪装成空态（R2 的 newArrivals 教训）。
- 非幂等 POST（收藏翻转）不得自动重放。

## 4. 模拟数据层（debug 变体）

- `app/src/debug/java/com/jmread/mock/`：MockJmApp（Manifest 接管）+ MockApiInterceptor（fixture 路由）+ MockImages + MockState。
- fixture 与单测同源：`app/src/test/resources/jm/`（`tools/dump-fixtures.mjs` 抓取并脱敏），debug assets 指向同一目录。
- 故障注入深链：`jmread://mock`（状态）/ `?on=1|off=1` / `?fault=offline|slow|error|truncate|none`。
- 模拟约束：章节 photoId 必须 < 220980（不触发乱序还原，`MockTemplates.photoIdFor` 已固化并有单测）。

## 5. 给未来 agent 的检查清单

改动前：
- [ ] 这个逻辑是否已有共享实现？（搜索 3.1 节列出的符号）
- [ ] 失败会发生在哪一层（网络/协议/解析/状态/UI）？
- [ ] 这段代码在协程里吗？取消会被吞吗？
- [ ] 分页/循环的退出条件覆盖「空页成功」和「全部失败」了吗？

改动后：
- [ ] `./gbuild.sh release`（单测门禁）通过。
- [ ] 新逻辑有能复现 bug 的单测。
- [ ] 模拟器冷启动实测；主题/字号/阅读器矩阵按需覆盖。
- [ ] 网络/下载改动做了故障注入（`jmread://mock?fault=…`）。
- [ ] `python tools/code-health.py --check` 无回归。
- [ ] 提交说明写清验证方式；没测就写「未测」。

## 6. 一句话备忘

> 不要用「看起来对」证明对；用能复现的证据证明对。
