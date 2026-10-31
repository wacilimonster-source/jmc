# 条漫（滚动流）图片错位重叠 —— 排查记录与交接文档

- 日期：2026-11-01
- 范围：阅读器滚动流（Webtoon / 条漫）长图自动分屏
- 状态：**主要根因已定位并修复，已在 MuMu 模拟器（127.0.0.1:7555）上验证通过；改动未提交**
- 相关文件：
  - `app/src/main/java/com/jmread/ui/reader/ReaderScreen.kt`
  - `app/src/main/java/com/jmread/ui/reader/WebtoonSplitPage.kt`
  - `app/src/main/java/com/jmread/ui/reader/ReaderViewModel.kt`
  - `app/src/main/java/com/jmread/data/WebtoonSliceCache.kt`

---

## 1. 结论（TL;DR）

用户反馈「图片没有正确接着排列，而是重叠了」，根因不是图片本身、也不是乱序还原，
而是**同一页的多个切片各自使用不同的高宽比计算高度与偏移**：

- 先加载完成的切片用图片实测比例（例如 5.833）；
- 后组合出来的切片（LazyColumn 回收后再滚回来）先拿「切片数 × 视口比」占位（例如 4 × 1.7778 = 7.111），
  过一帧才被自身测得的真值纠正；
- 一页之内相邻切片按不同比例摆放，内容互相叠压约几百像素，滚动时表现为横向接缝处内容重复/错位。

修复方式：**把「每页实测高宽比」提升到外层按页共享**（并持久化到切片缓存），
同页所有切片任何时刻都使用同一个比例；比例一旦测到，所有切片同帧重排，不再有错位窗口。

---

## 2. 故障现象与复现

### 2.1 现象

- 只在**滚动流**模式下出现；横滑翻页模式正常（每页整图渲染，没有切片）。
- 只出现在**长图页**（切片数 > 1）；普通单屏页不会触发。
- 快滚、弱网时最明显：新滚入屏幕的切片先是「按估算比例」渲染，随后才跳变纠正。
- 视觉上表现为一页内部的横向接缝处内容重复、错位，像两张图叠在一起。

### 2.2 复现步骤（模拟器）

设备：`127.0.0.1:7555`（MuMu，Android 15，1080×1920），应用 `com.jmread`（debug 包，可用 `run-as`）。

1. 登录账号后，进入书架 →「秘密教學 / 秘密の授業」（album id `1114751`，共 359 话）。
2. 打开第 1 话 → 控制面板切到「滚动流」。
3. 该章图片是长条：`720×4200`（部分页 `720×4100` / `720×2892` / `720×1600`），
   在 1080×1920 竖屏下 `viewportAspect = 1.7778`，`computeSliceCount` 得到 **4 屏**（或 3 屏）。
4. 快速上下滚动，观察同一页切片交界处。

图片直链（无需登录、CDN 直出）：

```
https://cdn-msp3.jmdanjonproxy.vip/media/photos/1114751/00001.webp
```

> 注意：album `1114751` 是彩色条漫长篇（「秘密教學」）；
> album `1487104` 是另一个同名单本（灰白漫画、960×1355、每页 1 屏）。
> 两者都出现在最近阅读里，排查时不要混淆——**灰白单本本来就不会触发本 bug**。

### 2.3 根因的数学说明

以 1080 宽屏、真实比例 `R = 5.8333`、切片数 `n = 4`、`viewportAspect = V = 1.7778` 为例：

| 切片状态 | `heightRatio` | 整页盒高 (px) | 单切片高 (px) | 图片内容实际高 (px) | 内容在盒内偏移 |
|---|---|---|---|---|---|
| 已测量 | 5.8333 | 6300 | 1575 | 6300（正好填满） | 0 |
| 未测量 | 4 × 1.7778 = 7.1111 | 7680 | 1920 | 6300（居中） | 690 |

- 已测量切片 0 显示图片行 `[0, 1575]`；
- 未测量切片 1 的窗口是盒内 `[1920, 3840]`，对应图片行 `[1230, 3150]`；
- 正确应是 `[1575, 3150]`，因此**重复显示了约 345px 的内容**，这就是「重叠」。

当多个切片同时处于不同状态时，错位量沿页累积；每个切片加载完成后各自跳变纠正，
所以用户看到的不是稳定的错位，而是滚动过程中不断变化的接缝/重影。

---

## 3. 排查过程与关键证据（含已排除的误报）

### 3.1 先确认「是不是图片本身/乱序还原的问题」

- 直接下载 CDN 原图：`720×4200`，内容连续、未乱序。
- `shared_prefs/jm_scramble.xml` 中 `1114751/0000x.webp` 判定均为 `false`（未打乱），
  与页面显示一致。
- 把横滑翻页模式的截图与「原图缩放到 1080 宽、居中」逐像素比对：
  **平均绝对误差 4.4（纯 JPEG 压缩噪声）** → 翻页模式渲染正确，排除乱序/解码问题。

### 3.2 已排除的误报（重要，避免下一位重复踩坑）

- **磁盘缓存里出现「噪点图」**：一开始用
  `adb shell "run-as com.jmread cat cache/image_cache/xxx.1" > out.bin`
  拉取 Coil 缓存，解出来是花屏噪点、尺寸 960×1355。
  **结论是假象**：`adb shell` 的 pty 会对二进制做换行转换，字节被破坏。
  用 `adb exec-out` 重新拉取后，文件 SHA-256 与 CDN 原图**完全一致**。
  → 以后拉二进制一律用 `adb exec-out`，不要用 `adb shell cat`。
- 这也顺带确认：`JmImageFetcher` 的磁盘缓存写入、乱序判定、解码链路都正常。

### 3.3 确认问题在布局层

- 用 `WebtoonSlice` 日志记录每张图的 `intrinsicSize / ratio / count`，发现：
  - 同一页不同切片测出的比例可能**不完全一致**：
    `720×4200 → 5.8333335` 与 `1053×6144 → 5.834758`（同一 URL 出现了两种解码尺寸，见 §6.1）。
  - 更关键的是：切片是各自 `remember` 自己的 `measuredRatio` 的，
    未加载的切片用估算比例、已加载的用实测比例 → 必然出现 §2.3 的错位。
- 把滚动流截图与「原图按页纵向拼接」的长图做滑动窗口比对：
  修复后各帧在正确偏移处的平均绝对误差为 `0.00 / 8.00 / 1.14 / 12.81 / 0.64`（JPEG/渲染噪声量级），
  即页面之间严格连续、无重叠。

### 3.4 参考 PiKA 项目

本仓库是 PiKA 的 fork。本机存在 PiKA 源码：`G:\game\nw\pka\pika`。
对照 `com.pika.ui.reader.WebtoonSplitPage` / `WebtoonRows` 后确认：

- PiKA 的切片高度算法相同，也存在「每切片各自测量比例」的隐患，但
  PicaCG 源基本没有超长条漫，所以没有暴露；
- PiKA 更值得借鉴的两点已在本轮移植/参考：
  1. **解码宽高双封顶**（`MAX_DECODE_WIDTH_PX = 2160`），防超宽长图解码成巨图触发
     `Canvas: trying to draw too large bitmap`；
  2. **切片回调快照章号**（避免切章亚帧窗口把旧章计数写进新章）。
- PiKA 还有 `WebtoonRows`（前缀和 + 二分，避免组合期物化 `rows` 列表）的性能优化，
  本次未移植（非本 bug 必需），可作为后续优化项。

---

## 4. 已实施的修改

> 以下改动当前在工作区，**尚未提交**。

### 4.1 `WebtoonSliceCache.kt` —— 缓存「切片数 + 高宽比」

- 新增 `data class SliceInfo(count: Int, ratio: Float? = null)`。
- 序列化格式从 `页码:切片数` 扩展为 `页码:切片数[:高宽比]`，
  **向后兼容**旧数据（两段 = 无比例，首次加载后补测）。
- 比例用 `Float.toString()` 序列化，固定 `.` 小数点，不受系统区域设置影响。
- 只缓存 `count > 1` 的页（单屏页不需要行换算）。

### 4.2 `ReaderScreen.kt` —— 按页共享比例

- 新增 `pageRatios: SnapshotStateMap<Int, Float>`（第 185 行附近）。
- 切章 / 退出滚动流时与 `sliceCounts` 一起清空。
- 从 `WebtoonSliceCache.load()` 恢复：`sliceCounts[page] = info.count`，
  `info.ratio?.let { pageRatios[page] = it }`（第 197–203 行）。
- `WebtoonSplitPage` 调用新增 `measuredRatio = pageRatios[pageIndex]`（第 569 行）。
- 回调改为 `onPageMeasured = { p, ratio, n -> ... }`（第 572–588 行）：
  - 用组合期快照 `orderSnapshot` 拦住切章后的旧章回调；
  - 只有比例/切片数**发生变化**时才写缓存，避免同页多个切片重复写盘。

### 4.3 `WebtoonSplitPage.kt` —— 不再各自记忆比例

- 删除组件内部的 `var measuredRatio by remember(...)`，改为入参 `measuredRatio: Float?`。
- 高度比例优先级（第 103–109 行）：
  1. 外层按页共享的 `measuredRatio`；
  2. **本切片画师已解出的真实尺寸** `liveRatio`（图片已在内存/磁盘缓存时首帧即准确）；
  3. 才回退到 `safeSliceCount * viewportAspect` 占位。
- 任一已组合的切片加载成功都会上报 `(page, ratio, count)`（保留用户此前去掉 `isPrimary`
  的改动：滚到后续切片时 0 号片可能不在组合范围内）。
- 解码尺寸改为宽高双封顶：`.size(Size(MAX_DECODE_WIDTH_PX, decodeHeightCap))`，
  新增 `internal const val MAX_DECODE_WIDTH_PX = 2160`（第 241 行）。

### 4.4 `ReaderViewModel.kt` —— 预取尺寸与展示一致

- `preloadNearby` 的 `.size()` 宽度从 `Int.MAX_VALUE` 改为 `MAX_DECODE_WIDTH_PX`（第 341 行）。

---

## 5. 验证结果（MuMu 127.0.0.1:7555）

1. **同页比例一致**（logcat tag `WebtoonSlice`）：

   ```
   page=4 slice=0 cache=4 intrinsic=720.0x4200.0 ratio=5.8333335 count=4
   page=4 slice=1 cache=4 intrinsic=1053.0x6144.0 ratio=5.834758 count=4
   page=5 slice=0 cache=1 intrinsic=720.0x4200.0 ratio=5.8333335 count=4
   page=5 slice=1 cache=4 intrinsic=720.0x4200.0 ratio=5.8333335 count=4
   page=6 slice=0 cache=1 intrinsic=1530.0x6144.0 ratio=4.0156865 count=3
   ```

2. **截图与源图拼接严格对齐**：滚动流 5 帧截图对「原图按页拼接的长图」做偏移匹配，
   最佳平均绝对误差 `0.00 / 8.00 / 1.14 / 12.81 / 0.64`，偏移落在各页的正确区间内 → 无重叠。

3. **缓存格式升级成功**（`shared_prefs/jm_webtoon_slices.xml`）：

   ```
   1114751#1 = 1:4,2:4,3:4,4:4:5.834758,5:4:5.834758,6:3:4.0156865
   ```

   前四项是旧格式（无比例），新写入的项带比例，向后兼容。

4. **重启恢复**：force-stop 后重新进入同一话，按缓存行号恢复到第 6 页，比例日志正常，
   首帧不再经过估算高度阶段。

5. **构建/单测**：

   ```
   ./gbuild.sh :app:compileDebugKotlin      # BUILD SUCCESSFUL
   ./gbuild.sh :app:testDebugUnitTest       # BUILD SUCCESSFUL
   ./gbuild.sh :app:assembleDebug           # BUILD SUCCESSFUL
   ```

   debug APK：`app/build/outputs/apk/debug/app-debug.apk`（已安装到 127.0.0.1:7555）。

---

## 6. 未解决 / 待确认事项（建议下一位继续）

### 6.1 同一 URL 出现两种解码尺寸（建议优先确认）

日志中同一长图页分别出现：

- `720×4200`（无缩放）
- `1053×6144`（按 6144 高、等比放大 1.4629 倍）

`6144` 正好是 `maxDecodeHeightPx` 的中档上限（memoryClass 128–255）。
推断链路：`preloadNearby` 显式设置了 `memoryCacheKey(cacheKey)`，
而 Coil 2.7 的内存缓存键**不含解码尺寸**，于是预取产生的 6144 高放大位图
可能被展示请求命中，造成：

- 实际解码尺寸超出需要（`1053×6144 × 4B ≈ 25MB`，而原图 `720×4200 ≈ 12MB`）；
- 同一页不同切片测出的比例有微小差异（5.8333 vs 5.8348）。

本轮修复已让布局不受比例微差影响，但**内存与清晰度仍有浪费**。
建议：

1. 确认 Coil 2.7 `memoryCacheKey` 的实际语义（是否需要把尺寸编进 key）；
2. 参照 PiKA，给展示/翻页/预取三处统一显式 `memoryCacheKey` + `diskCacheKey`，
   并保证 `.size()` 完全一致；
3. 确认 Coil 2.7 是否会把小于目标尺寸的图**放大**；若是，
   考虑改用 `Precision.INEXACT` 或按图片实际尺寸构造 `Size`，避免无谓放大。

### 6.2 首次进入本章的布局跳变（非重叠）

无缓存（首次阅读）时，长图页先按「1 屏」占位并显示「加载中…」，
图片到达后 `liveRatio` 立即给出真实高度、同时上报切片数把该页展开成 4 个 item。
这一过程会有一次高度跳变（因为占位高度 1 屏，真实高度 4 屏），但**不会出现内容重叠**。
如需进一步优化，可考虑：

- 下载/预取成功时就把比例写入 `WebtoonSliceCache`；
- 或在比例未知时用「虚高占位」/骨架屏，减少跳变观感。

### 6.3 旧缓存数据

旧版本缓存只有 `页码:切片数`，没有比例。升级后首次进入这些章节仍会经历一帧估算；
进入一次后即写入比例。若想彻底避免，可考虑加缓存版本号强制重测（收益有限）。

### 6.4 性能优化（PiKA 已有，未移植）

- `WebtoonRows`：前缀和 + 二分，替代每帧 `buildList` 物化 `rows`；
  PiKA 注释称该改动显著改善大章节快滚掉帧。
- 本仓库 `ReaderScreen` 目前仍在组合期 `buildList` 出 `rows`（页数多时 O(总切片数) 分配）。
  与本 bug 无直接关系，但可作为后续优化。

### 6.5 回归建议

请重点回归以下场景：

1. 长图章（本文件 §2.2）滚动、快滚、弱网、切后台再回来；
2. 单屏章（如 album `1487104`）正常不受影响；
3. 切章（下一话/上一话）后页码、进度、切片缓存不串章；
4. 横滑/滚动模式来回切换后定位一致；
5. 已下载章节（`file:` URL）与在线章节都正常；
6. 手动「翻转本页横条」（`#m1/#m0`）仍生效，且比例缓存不被后缀污染。

---

## 7. 环境与常用命令

```bash
# 构建（必须关闭沙箱；脚本会绕过 gradle wrapper 的锁问题）
cd G:/game/nw/pka/JM
./gbuild.sh :app:assembleDebug

# 安装（应用是 debug 包，可覆盖安装并保留数据）
ADB=/g/Android/platform-tools/adb.exe
$ADB -s 127.0.0.1:7555 install -r app/build/outputs/apk/debug/app-debug.apk

# 抓阅读器日志
$ADB -s 127.0.0.1:7555 logcat -c
$ADB -s 127.0.0.1:7555 logcat -d | grep WebtoonSlice

# 安全地拉取应用私有文件（务必 exec-out，不能 shell cat）
MSYS_NO_PATHCONV=1 $ADB -s 127.0.0.1:7555 exec-out \
  "run-as com.jmread cat shared_prefs/jm_webtoon_slices.xml"

# 公网 API / 图片走宿主代理（Node 脚本需要）
HTTPS_PROXY=http://127.0.0.1:7897 HTTP_PROXY=http://127.0.0.1:7897 \
NODE_USE_ENV_PROXY=1 node tools/xxx.mjs
```

其他环境事实：

- `local.properties` 的 SDK：`G:\Android`，adb 在 `G:\Android\platform-tools\adb.exe`。
- 模拟器 `127.0.0.1:7555` 已登录账号 `wacilimonster`（会话 token 在
  `files/datastore/jm_prefs.preferences_pb` 的 `jm_token`；仅排障用，勿外传）。
- 阅读器偏好 `reader_mode` 存于 `files/datastore/jm_reader.preferences_pb`
  （0=滚动流，1=横滑翻页）。
- 期间遇到过 `/album`、`/chapter` 返回 HTTP 403（疑似风控/代理 IP 限流），
  过一段时间自行恢复；图片 CDN 一直可用。

---

## 8. 交接注意事项（踩坑清单）

1. **二进制文件一律用 `adb exec-out`**。`adb shell cat` 会做换行转换，把图片拉成噪点。
2. `1114751` 与 `1487104` 是两部同名作品：前者彩色条漫（会触发切片 bug），
   后者灰白单本（960×1355、单屏，不会触发）。
3. 横滑翻页模式可作为「图片是否正常」的对照组：翻页正确 = 图片与乱序还原没问题，
   问题在滚动流切片布局。
4. 判断是否还有重叠，不要只靠肉眼：用「原图按页纵向拼接 → 与截图做滑动窗口匹配」，
   误差在 JPEG 噪声量级即为连续。
5. 阅读器默认模式是持久的。调试验证前先确认当前是「滚动流」，
   否则看不到切片逻辑（面板底部会显示模式）。
