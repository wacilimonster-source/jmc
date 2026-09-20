# tools/oneoff —— 一次性改源码补丁脚本（已执行完，仅作历史留档）

这些脚本是 **fork PiKA 做单源减法改造** 期间用来批量改 `.kt` 源文件的一次性脚本，
全部**已经执行完毕**，其改动已固化在 `app/src/main/` 的源码里。

> ⚠️ 不要重跑这些脚本。它们基于 `str.replace()` 做文本替换，
> 对应的旧代码片段早已不存在，重跑会静默失败或产生错误结果。

## 清单

| 脚本 | 作用 |
|---|---|
| `_edit_reader.py` | ReaderViewModel 单源改造（去 SourceManager / SourceType） |
| `_edit_search.py` | SearchScreen 四维搜索改造 |
| `_edit_settings.py` | SettingsScreen 数据源组 → 网络组（域名自愈 + 手填覆盖） |
| `_edit_misc.py` | DownloadScreen / FollowManageScreen / CategoryScreen 单源改造 |
| `_fix1.py` | JmApp 组件注册签名、Coil 管线装配修正 |
| `_fix2.py` | CategoryScreen 去 activeSource 依赖 |
| `_age.py` | 18+ 首启确认门（SourcePrefs.ageConfirmed + MainScreen AgeGate） |
| `_flip.py` | 阅读器手动「翻转本页横条」兜底（ImageScrambler 强制覆盖 + Fetcher 后缀协议 + UI） |

## 为什么留档

这些脚本记录了「PiKA → JM」改造过程中每处替换的**原始片段与目标片段**，
排查历史回归时可以直接看出某处改动是怎么来的，比 git blame 更直观。

## 新的改动不要再写成这种脚本

后续修改请直接编辑源文件并走 git 提交。需要批量改代码时用 IDE 的重构，
不要再用 `str.replace()` 打补丁——它没有语法校验，出错时是静默的。

真正的金丝雀探测脚本（`jm-api-check.mjs` / `jm-shape-probe.mjs` /
`jm-scramble-rule.py` / `dump-fixtures.mjs`）放在 `tools/` 根目录下。
