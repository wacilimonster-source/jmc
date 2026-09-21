# JM Reader · 发版流程

> 参照 PiKA 的发布方式（`pika/gbuild.sh` + `pika/update.json`），独立仓库、独立更新通道。
> 与 PiKA 无版本对齐关系：versionCode 从 1 起独立演进。

---

## 0. 现状（2026-09-21 晚更新）

| 环节 | 状态 |
|---|---|
| release 构建配置 | ✅ 已有（`app/build.gradle.kts`：minify + shrinkResources + 签名可环境变量覆盖） |
| `gbuild.sh` 构建脚本 | ✅ 已有 |
| 更新检查代码 | ✅ 已有（`core/update/UpdateManager.kt` + `ui/update/UpdateDialog.kt` + FileProvider） |
| `update.json` | ✅ 已建立并**已跑通真实升级**（`wacilimonster-source/jmc`） |
| 更新仓库 | ✅ `git@github.com:wacilimonster-source/jmc.git`（origin） |
| 真机验收 checklist | 🟡 主链路已过（见 `verification-2026-09-21.md` §10.5），剩余 125 页上限 / 坏域名恢复 / 乱序命中率 |

当前版本：`versionCode 3` / `versionName 0.1.2`。

> **已验证的应用内升级链路**（2026-09-21 真机）：
> 0.1.0 装机的 App 点「检查更新」→ 检测到 `v0.1.1` → 弹窗展示 `update.json.notes` →
> 下载 → 校验 sha256 → 系统安装确认 → 升级到 `versionCode 2`。
> 发布链路另经 curl 验证：raw `update.json` 200、raw APK 200（6,446,754 B），
> **下载后的 APK sha256 与 `update.json` 声明值一致**。
>
> 注意：0.1.1 与 0.1.2 的 APK 都在仓库里，`update.json` 只指向最新那个。
> 旧 APK 可以删（raw 直链会随之失效，但已升级的用户不再需要）。

---

## 1. 发版步骤

```bash
# 1. 发版前先跑金丝雀，确认线上前提没变（退出码 1 表示有断言失败，先查再发）
node tools/jm-shape-probe.mjs --update   # 刷新线上形态基线
node tools/jm-dto-contract.mjs           # DTO 声明 vs 线上形态对账（离线、秒级，必须为 0）
node tools/jm-api-check.mjs              # 端点能力与数值断言

# 2. 先改版本号（app/build.gradle.kts 的 versionCode / versionName 都要 +1）
#    已发布的 APK 内容不可原地替换 —— 同一 version 换内容会让 sha256 与用户已装版本错位

# 3. 单测 + release 构建，并自动产出 jm-reader-v{版本号}.apk
./gbuild.sh release

# 4. 真机验收（产品设计 §8 的 12 项 checklist）
#    重点：photo 220981 页面横条顺序正确、300000/1474353 不被误翻、
#          photo 800000 同章混合逐页正确、翻到第 125 页出现「到底了」、
#          手填坏域名后自动切镜像恢复

# 5. 提交 APK 到更新仓库，记下 sha256（gbuild.sh 已打印）

# 6. 更新 update.json（见下），提交推送

# 7. 真机点一次「检查更新」验证整条链路（改版本号后必然触发下载）
```

> **`jm-dto-contract.mjs` 为什么必须进发版门禁**：它拦的是一整族静默故障 ——
> DTO 字段类型与线上形态不符会让**整个接口**解析失败，而调用方普遍用
> `runCatching{}.getOrDefault(空)` 兜底，表现为「列表空白 / 点了没反应」而不是崩溃。
> 2026-09-21 一次就查出 4 个（`/setting` 域名自愈、`/categories` 分类列表、
> `/chapter` 阅读器、`/forum` 评论页），全部是「线上给数字/对象，DTO 写成 String/List<String>」。
> `jm-shape-probe.mjs` 发现不了这类问题：它的基线录的就是线上形态，两边永远一致。

---

## 2. `update.json` 格式

与 PiKA 完全一致，`UpdateManager` 直接消费：

```json
{
  "sha256": "<gbuild.sh 打印的 sha256>",
  "version": "0.1.2",
  "apkUrl": "https://raw.githubusercontent.com/wacilimonster-source/jmc/main/jm-reader-v0.1.2.apk",
  "notes": "v0.1.2 更新：\n1. …\n2. …"
}
```

字段含义：

| 字段 | 说明 |
|---|---|
| `sha256` | APK 的 SHA-256。下载后校验，不匹配则拒绝安装 |
| `version` | 展示用的版本名，与 `app/build.gradle.kts` 的 `versionName` 保持一致 |
| `apkUrl` | APK 直链。走 GitHub raw 时必须是 `raw.githubusercontent.com`，不能用网页链接 |
| `notes` | 更新说明，支持 `\n` 换行，会显示在更新弹窗里 |

> **签名要求**：`app/build.gradle.kts` 默认用 Android 公共 debug keystore 签 release，
> 目的是让应用内更新能**覆盖安装**已装的 App（同签名）。换签名会导致老用户必须卸载重装。
> 需要正式签名时用环境变量覆盖：`JM_KEYSTORE` / `JM_KS_PW` / `JM_KEY_ALIAS` / `JM_KEY_PW`。

---

## 3. 仓库与地址（已确定）

| 项 | 值 |
|---|---|
| 代码仓库 | `git@github.com:wacilimonster-source/jmc.git`（remote: origin） |
| update.json 地址 | `https://raw.githubusercontent.com/wacilimonster-source/jmc/main/update.json` |
| APK 直链格式 | `https://raw.githubusercontent.com/wacilimonster-source/jmc/main/jm-reader-v{版本号}.apk` |
| 对应代码常量 | `core/update/UpdateManager.kt` 的 `UPDATE_URL` |

> **2026-09-21 修掉的一个串台问题**：`UPDATE_URL` 此前指向
> `wacilimonster-source/pika`，导致 JM 会读到 PiKA 的 update.json
> （version 1.6.0 > JM 的 0.1.0），提示用户「发现新版本」并下载安装 **PiKA 的 APK**，
> 而且 sha256 校验也会通过（校验的就是 PiKA 那个包）。
> 产品设计 §2 要求的「独立仓库、独立 update.json」当时漏了实现。

APK 必须提交到仓库根目录才能被 raw 直链访问（PiKA 也是这个做法）。
`gbuild.sh release` 会自动产出这个文件名的 APK。

---

## 4. 分发边界

按产品设计 §9：纯技术自托管分发，**不上架任何应用商店**。
应用内已有 18+ 首启确认门（`MainScreen.AgeGate`）与免责声明。
