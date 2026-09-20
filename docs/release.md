# JM Reader · 发版流程

> 参照 PiKA 的发布方式（`pika/gbuild.sh` + `pika/update.json`），独立仓库、独立更新通道。
> 与 PiKA 无版本对齐关系：versionCode 从 1 起独立演进。

---

## 0. 现状（2026-09-21）

| 环节 | 状态 |
|---|---|
| release 构建配置 | ✅ 已有（`app/build.gradle.kts`：minify + shrinkResources + 签名可环境变量覆盖） |
| `gbuild.sh` 构建脚本 | ✅ 已有 |
| 更新检查代码 | ✅ 已有（`core/update/UpdateManager.kt` + `ui/update/UpdateDialog.kt` + FileProvider） |
| **`update.json`** | ❌ **缺失** —— UpdateManager 已实现但没有任何数据源 |
| **更新仓库 / update.json 托管地址** | ❌ **缺失** —— 需新建仓库并确定 URL |
| 真机验收 checklist | ❌ 未执行（产品设计 §8，v1.0 的唯一完成定义） |

**发布链路目前是断的**：`UpdateManager` 会去拉一个还不存在的 `update.json`，
用户点「检查更新」只会得到网络错误提示。

---

## 1. 发版步骤

```bash
# 1. 发版前先跑金丝雀，确认线上前提没变（退出码 1 表示有断言失败，先查再发）
node tools/jm-api-check.mjs
node tools/jm-shape-probe.mjs

# 2. 单测 + release 构建，并自动产出 jm-reader-v{版本号}.apk
./gbuild.sh release

# 3. 真机验收（产品设计 §8 的 12 项 checklist）
#    重点：photo 220981 页面横条顺序正确、300000/1474353 不被误翻、
#          photo 800000 同章混合逐页正确、翻到第 125 页出现「到底了」、
#          手填坏域名后自动切镜像恢复

# 4. 提交 APK 到更新仓库，记下 sha256（gbuild.sh 已打印）

# 5. 更新 update.json（见下），提交推送

# 6. 真机点一次「检查更新」验证整条链路
```

---

## 2. `update.json` 格式

与 PiKA 完全一致，`UpdateManager` 直接消费：

```json
{
  "sha256": "<gbuild.sh 打印的 sha256>",
  "version": "0.1.0",
  "apkUrl": "https://raw.githubusercontent.com/<owner>/<repo>/main/jm-reader-v0.1.0.apk",
  "notes": "v0.1.0 更新：\n1. …\n2. …"
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

## 3. 待你确认的信息

`update.json` 的 `apkUrl` 需要一个真实的仓库地址，目前还不存在。参照 PiKA 的形态
（`raw.githubusercontent.com/wacilimonster-source/pika/main/…`），需要确认：

- **仓库名**：`JM` / `jm-reader` / 其它？
- **仓库可见性**：公开（raw 直链才能匿名访问，应用内更新无需 token）还是私有？

确认后即可生成 `update.json` 并把 `UpdateManager` 里的 `UPDATE_URL` 指向它。

---

## 4. 分发边界

按产品设计 §9：纯技术自托管分发，**不上架任何应用商店**。
应用内已有 18+ 首启确认门（`MainScreen.AgeGate`）与免责声明。
