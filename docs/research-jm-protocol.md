# 禁漫协议原始调研笔记（参考项目提取）

> 来源：JMComic-Crawler-Python（commit e3c7e40）与 JMComic-qt（2026-08-04）源码提取 + PiKA 2026-09-19 实测报告。
> 定位：实现阶段的协议速查。**冲突时以 2026-09-19 实测报告为准**（社区库部分知识已过时，标注 ⚠）。

## 端点全集（社区库视角）

移动端（jm_client_impl.py JmApiClient L631-931）：
- GET `/search?main_tag=0&search_query=&page=&o=mr|mv|mp|tf&t=t|w|m|a`；搜车号返回 redirect_aid
- GET `/categories/filter?page=&c=doujin|single|short|another|hanman|meiman|doujin_cosplay|3D|0&o=…`
- GET `/album?id={aid}`、GET `/chapter?id={chapter_id}`
- GET `/chapter_view_template?id={photo}&mode=vertical&page=0&app_img_shunt=1&express=off&v={ts}` → HTML 明文，`var scramble_id = (\d+);`（另一密钥 18comicAPPContent）
- GET `/favorite?page=&folder_id=0&o=mr|mp`、POST `/favorite(aid=)`、POST `/favorite_folder(folder_name / folder_id+type=add|del|move)`
- GET `/forum?mode=all&aid=&page=`、POST `/login(username,password)`、GET `/setting`（返回初始 Set-Cookie）
- 其他：`/promote?page=`、`/latest?page=`、`/watch_list?page=`、`/week`、`/week/filter`、`/blogs`、`/blog?id=`、`/daily?user_id=`、POST `/daily_chk`

桌面端 JMComic-qt 额外（src/server/req.py）：`/comment`(L844)、`/coin_buy_comics`(L867)、网页端 `/signup /confirm /lost /captcha`(L353-465)。

## 加密与签名

- data 解密：base64 → AES-256-ECB，key=md5hex(`"{ts}{secret}"`) 32 字节小写 hex → 去 PKCS7（`data[:-data[-1]]`）
- APP_TOKEN_SECRET = APP_DATA_SECRET = `185Hcomic3PAPP7R`（jm_config.py L141/143）
- token = md5hex(`"{ts}" + secret`)；tokenparam = `"{ts},{APP_VERSION}"`；ts 秒级
- 域名服务器文本密钥 = `diosfjckwpqpdfjkvnqQjsik`（jm_config.py L144）
- /chapter_view_template 密钥 = `18comicAPPContent`；桌面端 token secret 写作 `18comicAPP`（同值异写）
- ⚠ 桌面端 HeaderVer=2.0.26 且每请求重取 ts；jmcomic 库 FLAG_USE_FIX_TIMESTAMP=True 会话内复用同一 ts——两行为线上均接受（实测报告：2.1.5/2.1.8/1.8.2/9.9.9 形态一致）

## scramble 乱序（社区库规则 ⚠ 与 2026-09 实测不符，仅作历史参考）

- 判定（jm_toolkit.py get_num L1097-1118）：aid < scramble_id → 0（不乱）；aid < 268850 → 10；否则 x = 10(aid<421926) | 8，num = ord(md5(`"{aid}{filename}"`)[-1]) % x * 2 + 2（filename 不含扩展名）
- scramble_id 实时获取：/chapter_view_template 正则提取，按 album 缓存；失败回退 220980
- **实测报告 E6 结论（优先）**：打乱逐图生效（photo 800000 同章第 2 页乱、3~5 页正常），id 阈值/md5 规则已过时；实测乱序样本全部为十等分倒序；采用逐图接缝判定（详见 tech-architecture.md §6）
- 还原算法（jm_toolkit decode L1037-1088）：高 h 分 num 块（块高 floor(h/num)，余数给第 0 块），从底到顶逆序贴：`y_src = h - move*(i+1) - over`，`y_dst = move*i`（i=0 时 move+=over，否则 y_dst+=over）

## 图片 URL

- 章节 `https://{img_domain}/media/photos/{photo_id}/{img_name}`；封面 `…/media/albums/{aid}.jpg`；搜索列表封面 `{aid}_3x4.jpg`
- ⚠ 社区库：URL 必须带 `?v={ts}` 否则空图（2023-07 起，jm_entity.py L426-439）；2026-09-19 实测裸请求 200。**采用：默认裸请求，空白图兜底时补 ?v={ts} + `X-Requested-With: com.JMComic3.app` 头重试一次**
- 社区库图片头（兜底用）：`X-Requested-With: com.JMComic3.app`、`Referer: https://www.cdnhjk.net`、`Accept: image/avif,image/webp,...`
- 桌面端：空白图自动加 ?v={ts}（req.py L327-328）；下载头 Accept-Encoding: None

## 域名

- API 内置 4：`www.cdnhjk.net / www.cdngwc.cc / www.cdngwc.net / www.cdngwc.club`（实测全活）
- 图片内置：`cdn-msp.jmapiproxy1.cc / jmapiproxy2.cc / jmapiproxy3.cc(qt) / cdn-msp2.jmapiproxy2.cc / cdn-msp3.jmapiproxy2.cc / cdn-msp.jmapinodeudzn.net / cdn-msp3.jmapinodeudzn.net / cdn-msp.jmdanjonproxy.xyz(qt，实测报告亦见此族)`
- 桌面端反代：`jm2-api.jpacg.cc / jm2-img.jpacg.cc`（ImgAutoUrl 索引 6 时走 proxyUrl）
- 远端域名服务器：`https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt`（c02/c03 镜像；桌面端回退 parse.jpacg.cc/jmserve、parse2.jpacg.cc/jmserve）→ 去非 ASCII 前导 → AES-ECB（key 见上）→ `{"Server":[...]}`
- 桌面端 DoH：parse.jpacg.cc/parse、doh.pub/dns-query、dot.pub；ECH cloudflare-ech.com；解析出 IP 后 curl RESOLVE 直连（应对 DNS 污染，本 App 暂不做，记录备查）
- 网页域名：jm365.work/3YeBdF 重定向、发布页 jmcomicog.net / jmcomicgo.org

## 登录 / 会话

- 移动 POST /login → data.s → `Cookie: AVS={s}`
- ⚠ jmcomic 库：移动端请求必须带 cookies 否则跳「禁漫娘」页；初始 cookies 取自 /setting 的 Set-Cookie（L1042-1126）。桌面端存整个 cookie jar 随请求携带。**实测报告未复现该问题，但 JmClient 应保留 OkHttp CookieJar（至少装 /setting 的初始 cookie），作为已知坑**
- 重复登录有 AVS 丢失问题，桌面端保留旧值（L413-414）

## 常量

- APP_VERSION：库 2.0.30 / 桌面 2.0.26 / PiKA 2.1.5 / 线上 jm3_version 2.1.8 → **本 App 兜底 2.1.8 + /setting 动态刷新**
- UA（移动）：`okhttp/3.12.0 leak(200.0);Android version:9.0;MAX2;100;jmc;3.23.0`；device：`ANDROID;9.0;SMR;unknown;deadbeef12345678;2.1.3`；platform ANDROID；channel app；app-version 3.4.17
- 网页 UA（兜底）：Android 9 Chrome 91 WebView 串
- 搜索页大小 80 / 收藏页 20；Accept-Encoding: gzip, deflate
- 错误码：403 地区禁、500/520/524 服务器异常；不存在端点返回 errorMsg `Not legal.xxx`
- 响应第一个有效字符必须是 `{`，否则强制重试（jm_client_impl.py L1004-1040）

## 参考项目概况

| 项目 | 是什么 | 可借鉴 |
|---|---|---|
| JMComic-Crawler-Python | 禁漫协议库（pip jmcomic），协议最全 | 协议细节、还原算法、域名服务器 |
| JMComic-qt | PySide6 桌面客户端 | 国内网络工程（DoH/ECH/CF优选/反代）、域名容错 |
| comic-app | = haka_comic v1.2.6 副本 | — |
| haka_comic | 哔咔第三方客户端，Flutter + Rust，GPL-3.0 | 架构参考意义有限（GPL 传染，**不抄代码**） |
| picacg-qt | 哔咔 Qt 客户端 | 与本 App 无关 |
