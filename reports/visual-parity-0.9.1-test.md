# 交互与视觉对标复测报告 · v0.9.1（第 11 轮：交互/视觉对标 pika）

> 日期：2026-11-22 ｜ 基线：JM 0.9.0 vs PiKA v1.11.x（vnext-delivery-20261104 / v1.11.1 复核 / v1.11.2 自适应间距）
> 被测：debug 模拟层 APK（模拟数据）+ release APK（单测 + code-health 双门禁）
> 设备：jm_gpu_full（emulator-5556，Pixel 6 / Android 14 / API 34 / GPU host）
> 设计依据：`docs/pika-visual-parity-20261122.md`（逐项对比与取舍清单）

## 1. 本轮交付（全部展示层，ViewModel/路由/持久化零改动）

| # | 对标项（pika 出处） | 实现 | 文件 |
|---|---|---|---|
| V1 | 网格自适应间距：两列 24/20/20，三列 12/8/16（v1.11.2） | `ComicGridSpec.metricsFor(columns)` 单一来源，网格+骨架+首页网格共用 | ComicGridView / StateViews / HomeScreen |
| V2 | 底栏：实心/描边双态图标 22dp、标签 11sp、显式指示胶囊色（v1.11.0） | TabItem 双图标 + NavigationBarItemDefaults 五色 + surface 容器色 | MainScreen |
| V3 | 区头双行文案：关注更新「你关注的故事，接着发生」+有更新徽标、榜单「在这里，发现你的偏爱」、随便看看「偶遇下一本喜欢的故事」（v1.11.1-8/2） | 三 Tab 区头改「标题 titleMedium + 副文案 bodySmall」结构 | HomeScreen |
| V4 | 详情推荐标题「你可能也喜欢」（v1.11.1-8） | 文案替换 | ComicDetailScreen |
| V5 | eyebrow 装饰小标：搜索 DISCOVER、我的 MY COLLECTION（v1.11.1-7） | 搜索页新增大标题行+eyebrow；我的页标题行加 eyebrow（10sp/字距2sp） | SearchScreen / MineScreen |
| V6 | 搜索框内「搜索」提交按钮（v1.11.1-1） | trailingIcon 组合 [搜索][清空]，`submitSearch()` 与键盘搜索同一路径 | SearchScreen |
| V7 | 全局 Shapes 6/10/14/18/24（v1.11.0） | `MaterialTheme(shapes = JmShapes)` 挂载；存量组件按需渐进切换 | Theme.kt |
| V8 | 字阶对齐：bodyLarge 14sp、辅助字 Medium（v1.11.0） | Type.kt bodyLarge 15→14、labelSmall/labelMedium 加 FontWeight.Medium | Type.kt |
| V9/V10 | 分类图底渐变卡、Mine 64dp 图标行 | 不采纳：JM 分类为紧凑 chips 产品决策；Mine 已对齐 | — |

## 2. 复测结果（模拟数据，全绿）

| # | 场景 | 结果 | 证据 |
|---|---|---|---|
| 1 | 底栏双态图标/胶囊/标签 | ✅ 选中实心+胶囊，未选中描边 | `01-home-light.png`、`04-search.png` |
| 2 | 榜单区头双行文案 | ✅「人气榜单 / 在这里，发现你的偏爱」 | `02-home-rank.png` |
| 3 | 关注区头双行文案 | ✅「关注更新 / 你关注的故事，接着发生」+ 管理入口 | `03-follow-header.png` |
| 4 | 搜索页头（大标题+DISCOVER eyebrow） | ✅ | `05-search-header.png` |
| 5 | 搜索框内「搜索」按钮 | ✅ 输入 test 出现按钮，点击触发搜索 240 条模拟结果 | `07-submit-button.png` / `08-submit-results.png` |
| 6 | 我的页 MY COLLECTION eyebrow | ✅（深色） | `10-dark-mine.png` |
| 7 | 深色模式全量（搜索/我的/首页） | ✅ 新组件全部跟随语义色，对比度正常 | `09-dark-search.png` / `10` / `12` |
| 8 | 字体缩放 1.3 + 深色（我的+首页） | ✅ 无裁切，JM. 短字标降级正常 | `11-dark-font13-mine.png` / `12-dark-font13-home.png` |
| 9 | 浅色冷启动（恢复设置后） | ✅ | `13-light-coldstart.png` |
| 10 | 单测门禁（release） | ✅ 221/221 | gbuild release EXIT=0 |
| 11 | code-health --check | ✅ 不劣化（FQN 门禁抓出本轮新增 1 处并修复） | 门禁输出 |
| 12 | logcat 崩溃/FATAL | ✅ 无 | android_logs |

## 3. 过程中发现并修复的问题

1. **补丁脚本变量覆盖丢改动**：R11 首个补丁脚本先改 SearchScreen 再改 MineScreen，但把搜索段的 `t` 在 Mine 段重新赋值覆盖，搜索段改动从未写盘——首轮实测框内只见 X 不见「搜索」按钮暴露。修复：重新独立应用搜索补丁。教训与 pika「自动化补丁的局部正确」一致：一次脚本只做一个文件，写盘后再进行下一个。
2. code-health 门禁在自身开发中再次抓出新增 FQN（MainScreen ImageVector），已转 import。

## 4. 边界与已知约束

- Shapes 全局挂载后，存量组件中显式 `RoundedCornerShape(...)` 的写法不受影响（显式值优先），渐进收敛不强制。
- V9（分类图底卡）不采纳理由：JM 分类页是「紧凑 chips + 官方标签墙」信息架构（0.3.x 产品决策），硬搬 pika 卡片样式会破坏既有交互。

## 5. 发版

- versionCode 35 / versionName 0.9.1；release APK sha256 `3b604ad4a1694e33010e1ed22e4239ca6184ef8902e14a7378bb16804f597c48`；update.json 同步；release-check 七项自检。
