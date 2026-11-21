# JM × PiKA 交互与视觉对标（0.9.1 轮）

> 日期：2026-11-22 ｜ 基线：JM 0.9.0（versionCode 34）vs PiKA v1.11.x（vnext-delivery-20261104 + v1.11.1 复核 + v1.11.2 自适应间距）
> 范围：纯展示层（ui/），业务逻辑零改动。

## 0. 逐项对比与取舍

| # | PiKA 行为（出处） | JM 现状 | 决策 |
|---|---|---|---|
| V1 | 网格自适应间距：两列 页边距24/列距20/行距20（封面≈146dp）；三列 12/8/16（≈107dp）（v1.11.2） | 固定 padding 16 / 列距 12 / 行距 20 | **采纳**：ComicGridSpec 增加 `metricsFor(columns)` 单一来源，网格与骨架屏共用（pika comicGridMetrics 同构） |
| V2 | 底栏：图标 22dp 实心/描边双态、标签 11sp、指示胶囊显式 primaryContainer（v1.11.0） | 默认图标 24dp 单态、标签 12sp、默认指示色 | **采纳**（保留 JM 的 80dp 高度——64dp 会裁字的既有修复） |
| V3 | 区头文案+副文案：关注「关注更新/你关注的故事，接着发生+有更新」、榜单「人气榜单/在这里，发现你的偏爱」、随便看看「偶遇下一本喜欢的故事」（v1.11.1-8/2） | 仅单行标题（「我的关注」「人气榜单」「随便看看」） | **采纳**：区头双行（标题+副文案），关注区头带「有更新」徽标 |
| V4 | 详情推荐标题「你可能也喜欢」（v1.11.1-8） | 「猜你喜欢」 | **采纳**（文案级） |
| V5 | eyebrow 装饰小标：搜索页右上 DISCOVER、我的页 MY COLLECTION（v1.11.1-7） | 无 | **采纳**（装饰性 10sp 字距小标） |
| V6 | 搜索框内「搜索」提交按钮（v1.11.1-1，与键盘搜索同入口） | 仅键盘 ImeAction.Search | **采纳**：框内右缘「搜索」文字按钮 |
| V7 | 全局 Shapes 体系 6/10/14/18/24（v1.11.0） | 无全局 Shapes，组件各自硬编码 | **部分采纳**：主题挂 Shapes（6/10/14/18/24）；存量组件按需渐进切换，不做全量重写（避免大面积回归面） |
| V8 | 字阶：bodyLarge 14/22、labelSmall/Medium 11-12sp·Medium（v1.11.0） | bodyLarge 15sp、labelSmall 11sp Normal | **采纳**（正文回 14sp 与 pika 一致；辅助字加 Medium 提升角标可读性） |
| V9 | 关注流真分页布局差异、分类卡图底渐变样式 | JM 分类为紧凑 chips 布局（0.3.x 产品决策） | **不采纳**：JM 分类信息架构不同，样式硬搬会破坏既有交互 |
| V10 | Mine 资料卡三组菜单图标行 64dp | JM 我的页分组菜单已有图标行 | **不采纳**（已对齐） |

## 1. 实施清单

- `ui/theme/Theme.kt`：挂全局 Shapes；`ui/theme/Type.kt`：bodyLarge 14sp、labelSmall/Medium Medium。
- `ui/MainScreen.kt`：底栏 colors/11sp/22dp 双态图标（Home/Explore/Search/Person 实心-描边对）。
- `ui/browse/ComicGridView.kt`：`ComicGridSpec.metricsFor(columns)` + 网格/骨架屏共用。
- `ui/home/HomeScreen.kt`：三区头双行文案（关注/榜单/随便看看）+ 关注「有更新」徽标。
- `ui/detail/ComicDetailScreen.kt`：推荐标题「你可能也喜欢」。
- `ui/search/SearchScreen.kt`：eyebrow DISCOVER + 框内「搜索」提交按钮。
- `ui/mine/MineScreen.kt`：eyebrow MY COLLECTION。

## 2. 验收（AGENTS 硬规则 2）

深浅色 + 字体缩放 1.3 + 冷启动，模拟数据，模拟器实测截图；单测门禁 + code-health 不劣化；业务逻辑零改动（ViewModel/路由/持久化不触碰）。
