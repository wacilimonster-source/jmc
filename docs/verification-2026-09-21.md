# JM Reader · 线上验证报告

> 验证日期：2026-09-21
> 验证方式：新建 `tools/` 金丝雀脚本实跑线上（17 个请求，全部走内置镜像 www.cdngwc.cc）
> 依据：`docs/tech-architecture.md` §3 记录的 2026-09-19 实测基线
> 脚本：`tools/jm-api-check.mjs`（端点存活 + 不变量 + 能力位）、`tools/jm-shape-probe.mjs`（字段形态）

---

## 0. 一页纸结论

线上读链路整体**健康**：全部读端点 200、DTO 声明类型与真实响应**逐字段吻合**、
负向端点确实不存在。但发现 **1 个用户可见故障 + 1 个静默失败 + 1 个测试能力缺口**。

| # | 问题 | 级别 | 状态 |
|---|---|---|---|
| F1 | 排行榜**日榜 / 周榜返回空列表**，而首页默认 Tab 就是排行榜 | P0 | **已缓解**（默认档位改月榜 + 补空态），根因在服务端 |
| F2 | `/week/filter` 列表键是 `list`，客户端读 `content` → 每周必看某期永远空白 | P1 | 已修 |
| F3 | 8 份 DTO fixture 是**手写样本**，不具备防漂移能力 | P1 | 已提供 `dump-fixtures.mjs`，待执行 |
| F4 | `JmClient` 不检查 `code` / `errorMsg`，无法识别 "Not legal" | P2 | 已修 |
| F5 | `category_sub` 线上为 `{id:null,title:null}`，靠 `coerceInputValues` 兜住 | P2 | 已修 |

---

## 1. F1 · 排行榜日榜/周榜返回空（P0，未修）

**实测**（稳定复现，各测 3 次，换页/去 `t=` 参数都一样）：

| 参数 | 档位 | total | 条数 |
|---|---|---|---|
| `o=mv_t` | 日榜 | **0** | **0** |
| `o=mv_w` | 周榜 | **0** | **0** |
| `o=mv_m` | 月榜 | 3498 | 80 |
| `o=mv` / `o=tf` / `o=mr` / 任意未知值 | — | 10000 | 80 |

**影响**：`HomeScreen` 的默认 Tab 是「排行榜」（`selectedTab = 1`），
`HomeViewModel._rankType` 默认 `"H24"` → `JmRepository.rank` 映射为 `mv_t`。
即**用户打开 App 第一屏是空列表**。`RankScreen` 同样默认 `H24`。
周榜（`D7`→`mv_w`）同样空。

**注意**：未知 `o` 值不报错，而是静默回落到浏览流（total=10000）。
所以如果映射写错，表现是「排行榜显示了浏览流内容」而不是报错——必须靠 total 值识别。

### 已排除「参数写错」的可能

参数名由两个独立参考库确认无误：

- `JMComic-Crawler-Python/src/jmcomic/jm_config.py`：
  `ORDER_MONTH_RANKING='mv_m'` / `ORDER_WEEK_RANKING='mv_w'` / `ORDER_DAY_RANKING='mv_t'`
- `JMComic-qt/src/view/category/category_view.py`：
  `sortList = ["mr","mv","mv_m","mv_w","mv_t","mp","tf"]`

参考库的参数构造是 `o = f'{order_by}_{time}'`（time='a' 时退化为裸 `order_by`），
且**不发 `t` 参数**，只发 `page` / `order=`（空串）/ `c` / `o`。
按这个确切形态复测，`mv_t` / `mv_w` 依然空：

| 参数形态 | mv_t | mv_w | mv_m |
|---|---|---|---|
| `?page=1&o=X&t=a` | 0 | 0 | 3498 |
| `?page=1&o=X`（无 t） | 0 | 0 | — |
| `?page=1&order=&c=&o=X`（参考库形态） | 0 | 0 | 3498 |
| `?page=1&o=mv&t=t / t=w / t=m` | 全部回落 10000 | | |
| `?page=1&o=X&c=doujin`（带分类） | 0 | — | — |

**结论：服务端这两档当前没有数据，客户端参数与映射均正确。**

### 处置（已实施，最小可逆）

没有隐藏入口——能力本身存在（端点正常、参数正确、9-19 时还有 143/812/3328），
这属于**服务端数据暂时缺失**而非「源不支持」，按产品原则①不该删入口。

- `HomeViewModel._rankType` 默认档位 `"H24"` → `"D30"`：首屏立刻有内容（月榜）；
  代码注释写明原因，服务端恢复后改回 `"H24"` 即可。
- `RankScreen` 默认档位同步改 `"D30"`（提取为 `DEFAULT_RANK_TYPE` 常量）。
- 空态文案由「排行榜暂无数据」改为「该档位暂无数据 / 可切换上方其它档位」。
- **顺带发现**：`ComicGridView` 自身没有空态处理——`RankScreen` 原来在
  「无 error 且列表为空」时直接落到 `ComicGridView`，会渲染出**纯白区域**。
  已在 `RankScreen` 补上空态分支。

产品设计 §5.8 记的 total 是 143/812/3328，本次月榜 3498 —— 说明这些值本来就在漂。

---

## 2. F2 · `/week/filter` 键名不一致导致静默空列表（P1，已修）

**实测**：`/week/filter?id=258&page=1&t=a` 返回 `data = { total: 11, list: [...] }`。

而 `JmRepository.weeklyContent` 读的是 `data.data.content`。

**为什么没报错**：`JmListData.content` 声明为 `List<JmAlbumSummary> = emptyList()`，
有默认值 → kotlinx 反序列化**不抛异常**，只给出空列表。
用户看到的是「每周必看某期永远没有内容」，日志里一行错误都没有。

**修复**（已提交）：
- `JmListData` 增加 `list` 字段，并新增统一取值口 `val items get() = if (content.isNotEmpty()) content else list`；
- `JmRepository` 6 处 `data.data.content` → `data.data.items`；
- `JmClient` 的 `ComicSort.DA` 倒序兜底同步改用 `items`；
- 金丝雀新增断言：线上列表键必须落在客户端支持的键集合 `{content, list}` 内。

> **教训**：DTO 字段带默认值 = 静默失败温床。任何「列表键名/字段名写错」都表现为空数据而非异常。
> `jm-api-check.mjs` 的「数组键」列就是为暴露这类问题加的——它打印服务端**实际**用的键名，
> 而不是客户端**以为**的键名。

---

## 3. F3 · fixture 是手写样本，不具备回归能力（P1）

`app/src/test/resources/jm/` 下 8 份 fixture 合计仅 5.6 KB，内含
`"测试本子A"` / `"评论者甲"` / `"搜索结果甲"` / `"多作者测试本"` / `"作者甲"` 等明显手写痕迹
（4 份可直接确认，另 4 份为同类小样本）。

`JmDtoFixtureTest` 的注释写着「7 份真实响应 fixture」「服务端字段一变先红在这里」——
**实际不成立**：手写 fixture 只能证明「DTO 能解析我以为的形态」，
服务端字段真漂移时它依然全绿。这正是它声称要防的 PiKA D1 故障模式。

**已提供**：`tools/dump-fixtures.mjs` 抓真实响应覆盖这 8 份。
覆盖后 `JmDtoFixtureTest` 里写死的断言值（id / total / 作者名）会红，需按新数据同步更新。

> 本次**没有**执行覆盖：真实响应含成人向作品标题，是否入库请自行决定。
> 作为替代，本次直接逐字段核对了 DTO 声明类型与线上真实类型（见 §4），
> 结论比 fixture 更强——但那是一次性人工核对，不能替代自动回归。

---

## 4. 已确认无误的部分

逐字段核对 `JmModels.kt` 声明类型 vs 线上真实类型，**全部吻合**：

**列表项（`/categories/filter` → `JmAlbumSummary`）**

| 字段 | 声明 | 线上 |
|---|---|---|
| id / name / author / image | String | string ✅ |
| category / category_sub | JmNamedItem（对象） | object ✅ |
| liked / is_favorite | Boolean | boolean ✅ |
| update_at | Long | number ✅ |
| adddate | String | string ✅ |
| description | String? | undefined（有默认值）✅ |

**详情（`/album` → `JmAlbumDetail`）**：`id` 裸数字 ✅、`author` 数组 ✅、
统计字段全是字符串 ✅、`series[]` 带 `sort` ✅、`price`/`purchased` 字符串 ✅、
`related_list` 数组 ✅。**PiKA D1 的两类根因（String→Object、String→Array）在本项目已不存在。**

**其它不变量**：浏览流 80 条/页 ✅、total 恒 10000 ✅、10000/80=125 页 ✅、
搜索 total 真实（722）✅、`/categories` 10 大类 ✅、
`/chapter` 图片名 `00001.webp` 连续 ✅、`/forum` 主键 `CID` + `expinfo` 对象 ✅、
`/week` 257 期 ✅、`/week/filter` 11 条 ✅。

**负向端点全部确认不存在**（返回 `HTTP 200 + code 200 + data=[] + errorMsg="Not legal.xxx"`）：
`/random` `/hot_search` `/tags` `/user` `/api/v3/*`。对应能力位 `hasRandom` / `hasHotWords` = false 正确。

---

## 5. F4 · `JmClient` 不检查 `code` / `errorMsg`（P2，已修）

服务端对不存在的端点返回 **HTTP 200 且 `code=200`**，唯一线索是 `errorMsg = "Not legal.xxx"`。
`JmClient.execute()` 原本只判断 HTTP 状态码与「响应首字符是否为 `{`」，
因此这类响应会被当成正常响应送进 DTO → 静默得到空数据，表现是「点了没反应」而不是报错。

**修复**：`decryptEnvelope()` 里解析出信封后立刻检查 `errorMsg`，
以 `Not legal` 开头则抛 `JmException`。

> 只加了这一条，**没有**加通用的 `code != 200` 判断 —— 业务错误的 `code` 取值尚未探明，
> 贸然加会在登录/写侧引入误报。等拿到测试账号探清后再补。

---

## 6. F5 · `category_sub` 为 null 依赖 `coerceInputValues`（P2，已修）

线上列表项的 `category_sub` 是 `{"id": null, "title": null}`
（实测：浏览流 23 条、搜索 20 条均如此），而 `JmNamedItem` 原本声明为
`id: String = ""` / `title: String = ""`（非空）。

这能跑通**完全依赖** `Json { coerceInputValues = true }` —— 该选项会把 null 强转成默认值。
`tech-architecture.md` ADR-6 的意图是「移除对宽容解析的依赖，让类型漂移在测试期就红」，
但 `coerceInputValues` 保留着，且在这里是**承重**的：去掉它，列表页立刻崩。

**全量扫描结论**：把所有端点响应摊平后，落在「DTO 非空字段」上的 null 只有
`category_sub.id` / `category_sub.title` 两处
（`search` 的 `description` 已声明 `String?`；`forum` 的 `BID`/`NCID`/`NID` 未进 DTO，被 `ignoreUnknownKeys` 忽略）。

**修复**：`JmNamedItem.id` / `title` 显式声明为 `String? = null`，
新增非空取值口 `titleText` / `idText`（null 回退空串），
`JmRepository` 与 `JmDtoFixtureTest` 改走 `titleText`。
并新增一条内联 JSON 的回归测试 `category_sub 为 null 时可解且回退空串` ——
手写 fixture 里没有 null，盖不住这个形态。

> `coerceInputValues = true` 暂时保留（其余字段是否还有隐藏依赖未全量验证），
> 但它已不再对已知路径承重。后续可以单独评估关闭。

---

## 7. 数据漂移记录（与 2026-09-19 基线对比）

| 项 | 设计文档 | 2026-09-21 实测 | 处置 |
|---|---|---|---|
| 标签墙标签数 | 43 个 | **47 个** | 无需改代码（动态渲染），更新文档 |
| 月榜 total | 3328 | **3498** | 无需改代码（真实 total 动态显示） |
| 日榜/周榜 | 143 / 812 | **0 / 0** | 见 F1 |
| `/week` 最新期号格式 | `第257期` | `2026第257期09.18 - 09.11` | 无需改代码（直接展示 `time`） |
| `jm3_version` | 2.1.8 | 2.1.8 | 一致 |

---

## 8. 复现方式

```bash
node tools/jm-api-check.mjs              # 17 请求，约 6 秒，退出码 1 表示有断言失败
node tools/jm-api-check.mjs --deep       # 加验 125/126 页与 t= 四取值
node tools/jm-api-check.mjs --selftest-only   # 只跑离线自检，不联网
node tools/jm-shape-probe.mjs            # 字段形态漂移比对（基线 tools/.jm-shape-baseline.json）
node tools/dump-fixtures.mjs --dry       # 预览真实 fixture，不落盘
```

---

## 9. 遗留待办

- [x] ~~F2 修 `/week/filter` 键名~~ 已完成
- [x] ~~F4 加固 `errorMsg` 判断~~ 已完成
- [x] ~~F5 `JmNamedItem` 改可空~~ 已完成
- [x] ~~F1 首屏空白~~ 已缓解（默认档位改月榜 + 补空态）
- [ ] **F1 根因**：服务端日榜/周榜何时恢复？恢复后把 `_rankType` 默认值改回 `"H24"`。
      可以把这个判断交给金丝雀——`jm-api-check.mjs` 的那两条断言会变绿，是恢复的信号。
- [ ] 跑 `dump-fixtures.mjs` 用真实数据替换手写 fixture，并同步更新断言值
- [ ] 探明业务错误的 `code` 取值，再给 `JmClient` 加通用 code 判断
- [ ] 真机验收 checklist（产品设计 §8）仍未执行，仍是 v1.0 的唯一完成定义
- [ ] `update.json` 发布链路未落地（`gbuild.sh` + `docs/release.md` 已就位，
      缺更新仓库地址 —— 仓库名与可见性待确认）
