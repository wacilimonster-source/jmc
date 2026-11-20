# JM Reader × PiKA 对标优化 · 十轮路线图（2026-11-21）

> 目标：对标 pika 项目进行全面优化。每轮 = 分析可学习点 → 开发 → 复测（模拟数据）→ 发版。
> 基线：JM Reader 0.7.3（versionCode 23，main @ a15f83d，origin 同步）。
> 版本计划：0.8.0 → 0.8.1 → 0.8.2 → 0.8.3 → 0.8.4 → 0.8.5 → 0.8.6 → 0.8.7 → 0.8.8 → **0.9.0**（共 10 版）。

---

## 0. 分析材料（本轮提炼依据）

| 材料 | 内容 |
|---|---|
| pika AGENTS.md + docs/ENGINEERING-LESSONS.md | 工程硬规则、10 条「假验证」清单、检查清单 |
| pika release-notes v1.10.0–v1.12.3 | 作者署名拆分 / 复制标题作者 / 错误文案映射 / 三态 / 封面重试 / 续读刷新 / 登录保留 / 日志基线事件 |
| pika reports/perf-architecture-20261024.md | Top10 性能清单 + 图片/网络/启动/Compose 四专项设计 |
| pika reports/auto-retry-design-20261024.md | 自动重试五轮事故复盘（层位选择/并发限额/取证落盘/截断代理注入） |
| pika reports/ux-polish-design-20261024.md | F/Q 系列体验修复与质感清单 |
| pika reports/pika-vnext-delivery-20261104.md | 设计令牌主题完整化 / 底栏形态 / 自适应网格间距 |
| pika reports/author-partial-search-design-20261102.md、copy-author-title-design-20261104.md | 两项已发布交互的完整设计 |
| JM reports/jm-vs-pika-feature-gap-20261102.html | 0.5.0 已对齐 24 项 + 9 项源能力差异 + 13 项 JM 独有优势（避免重复对齐） |
| JM reports/fulltest-emulator-2026-11-13.md | 0.7.2 全量实测结论与遗留观察项（条漫黑边提示未修） |

## 1. JM 现状基线（0.7.3）

- 100 个主源码文件 / 30 个测试文件 / 约 25k 行主代码；`./gbuild.sh release`（单测门禁 + release APK）28s 绿。
- 已有：章节抽屉、音量键、保存分享、下载通知深链、Wi-Fi 限载、ZIP 导出、榜单缓存兜底、书架撤销、乱序还原、域名池自愈、Coil 管线级重试 + 合流、DTO fixture 单测 + 金丝雀脚本。
- 已确认缺口（本轮逐项核实）：0 处 `collectAsStateWithLifecycle`（19 文件裸 collect）、无 Splash、`ui/common` 仅 2 组件、`core/source/` 空目录残留、无工程门禁脚本、无模拟数据模拟器通道。

## 2. 十轮路线图

| 轮 | 版本 | 主题 | 学习点（pika 出处） | 复测方式 |
|---|---|---|---|---|
| R1 | 0.8.0 | 模拟数据复测基建 + 故障注入 | pika AGENTS §2「构建成功≠可运行」+ 截断代理故障注入（auto-retry §8）；JM 复测要求模拟数据 | fixture 驱动 JVM 单测 + debug 模拟数据模式下的模拟器全链路冒烟 |
| R2 | 0.8.1 | 错误文案映射 + 加载/失败/空三态 | v1.12.3-1/7（错误码映射、三态准确） | 故障注入（断网/5xx/延迟）模拟器实测 + 单测 |
| R3 | 0.8.2 | 详情页交互对齐 v1.12 | v1.12.3-3 续读 ON_RESUME 刷新、v1.12.0 复制标题/作者、v1.12.3-9 封面失败重试覆盖层 | 模拟器走查 + 单测 |
| R4 | 0.8.3 | 作者署名解析与片段搜索 | v1.10.0（AuthorName 解析器/选择层/片段切换/按片段收藏，18 条单测） | AuthorNameTest JVM 单测 + 模拟器流程 |
| R5 | 0.8.4 | 性能①：生命周期感知与重组热点 | perf-arch P1-7（collectAsStateWithLifecycle 全量）、P1-8 重组热点（derivedStateOf/蒙层拆分）、P2 contentType | gfxinfo 帧率对比 + 单测 |
| R6 | 0.8.5 | 性能②：启动链路与写放大 | perf-arch P0-3/P1-10（Splash、启动路径）、P1-2 进度写放大、P2 manifest 防抖、P2-7 onTrimMemory | 冷启动计时 + 单测 |
| R7 | 0.8.6 | 工程门禁：AGENTS.md + code-health | AGENTS.md 硬规则 + tools/code-health.py 门禁 + 2026-11 清理范式（收敛/死代码/全限定名） | code-health --check + 全量单测 |
| R8 | 0.8.7 | 反馈与操作打磨 | ux-polish Q8/Q10/Q11/Q4（进度平滑/Snackbar 动作/撤销扩展/animateItem） | 模拟器走查 + 单测 |
| R9 | 0.8.8 | 阅读器深水区 | ux-polish F2/F3（章节失败不清页/切章竞态快照）、v1.11.1-5 翻页页码小标、v1.11.2 自适应网格间距、0.7.3 遗留观察项（条漫黑边提示） | 模拟器阅读流程 + 单测 |
| R10 | 0.9.0 | 发版工程化 + 全量回归 | ENGINEERING-LESSONS §2.8（发版清单脚本化）+ AGENTS §2 硬规则固化 | 全量模拟数据回归（深浅色/字体1.3/断网/冷启动）+ 十版本收口报告 |

## 3. 约定

- 每轮发版：bump `versionCode/versionName` → `./gbuild.sh release`（单测门禁）→ 模拟器复测（jm_gpu_full，debug 模拟数据）→ 提交 `release: x.y.z …` → push origin（raw update.json 即更新通道）。
- 复测一律使用模拟数据（debug-only MockApi + fixture / 本地占位图），不依赖真实账号与真实内容。
- 回归红线：阅读器进度保存/恢复、下载调度、域名自愈不得回退；发版 APK sha256 必须与 update.json 一致。
