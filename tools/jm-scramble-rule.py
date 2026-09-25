#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
禁漫图片分片(scramble)规则金丝雀。

不猜置换表、不按 id 阈值——直接对样本章节逐图做「十等分接缝梯度」判定：
  cost(order)：按指定块序读图，相邻块边界行的平均绝对差（恒等序 vs 倒序序）
  判定（与 App 内 ScrambleDetector 双门一致）：reversed*2 < identity 且 identity >= 20

用途：
  1) 发版前抽查线上打乱规则是否漂移（App 侧判定器是否仍然成立）；
  2) 统计「判定为乱序的页占比」——服务端哪天全量恢复不打乱，这个数会掉到 0，
     届时可以把 App 内整块解码逻辑关掉而不是删掉。

用法:
  NODE 类脚本同款代理用法:
  HTTPS_PROXY=http://127.0.0.1:7897 PYTHONIOENCODING=utf-8 python tools/jm-scramble-rule.py
  可选: --photos 220981,268000,800000 --pages 2 --host cdn-msp.jmapiproxy1.cc

依赖: Pillow + numpy（灰度与梯度）、openssl 命令行（AES-256-ECB 信封解密）
"""
import argparse
import base64
import hashlib
import io
import json
import os
import subprocess
import sys
import time
import urllib.request

import numpy as np
from PIL import Image

SECRET = "185Hcomic3PAPP7R"
VER = "2.1.8"
API = os.environ.get("JM_API", "www.cdngwc.cc")

IMG_HOSTS = [
    "cdn-msp.jmapiproxy1.cc",
    "cdn-msp.jmapiproxy2.cc",
    "cdn-msp2.jmapiproxy2.cc",
    "cdn-msp3.jmapiproxy2.cc",
    "cdn-msp.jmapinodeudzn.net",
    "cdn-msp3.jmapinodeudzn.net",
    "cdn-msp.jmdanjonproxy.xyz",
]

HDR = {
    "user-agent": "okhttp/3.12.0 leak(200.0);Android version:9.0;MAX2;100;jmc;3.23.0",
    "device": "ANDROID;9.0;SMR;unknown;deadbeef12345678;2.1.3",
    "platform": "ANDROID", "app-version": "3.4.17", "channel": "app", "os-version": "9.0",
    "Accept-Encoding": "identity",
}

# Windows 的 urllib 默认优先读注册表代理，显式按环境变量建 opener
_PROXIES = {k.lower().replace("_proxy", ""): v for k, v in os.environ.items()
            if k.upper() in ("HTTPS_PROXY", "HTTP_PROXY") and v}
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler(_PROXIES or None))


def md5hex(s: str) -> str:
    return hashlib.md5(s.encode("utf-8")).hexdigest()


def api_decrypt(b64: str, ts: int) -> str:
    """AES-256-ECB 解 base64 密文，去 PKCS7。走 openssl 命令行。

    注意密钥形态：与服务端一致的做法是「md5(ts+SECRET) 十六进制字符串的 ASCII 字节」
    （32 字节 → AES-256），不是 hex 解码后的 16 字节（那会变成 AES-128 且密钥错误）。"""
    key_str = md5hex(f"{ts}{SECRET}")
    key_hex = key_str.encode("utf-8").hex()  # ASCII 字节的 hex，供 openssl -K
    raw = base64.b64decode(b64)
    p = subprocess.run(
        ["openssl", "enc", "-d", "-aes-256-ecb", "-nopad", "-K", key_hex, "-nosalt"],
        input=raw, capture_output=True, check=True,
    )
    out = p.stdout
    pad = out[-1]
    if not 1 <= pad <= 16:
        raise ValueError(f"padding 非法: {pad}")
    return out[:-pad].decode("utf-8")


def api(path: str, timeout: int = 30) -> dict:
    """签名 GET，解密 data 信封后返回明文 JSON。"""
    ts = int(time.time())
    req = urllib.request.Request(
        f"https://{API}{path}",
        headers={**HDR, "token": md5hex(f"{ts}{SECRET}"), "tokenparam": f"{ts},{VER}"},
    )
    with _OPENER.open(req, timeout=timeout) as resp:
        obj = json.loads(resp.read().decode("utf-8"))
    if obj.get("code") != 200:
        raise RuntimeError(f"API {path} -> code={obj.get('code')} {str(obj.get('errorMsg'))[:80]}")
    data = obj.get("data")
    if isinstance(data, str) and data:
        data = json.loads(api_decrypt(data, ts))
    return data


def fetch_image(url: str, timeout: int = 30) -> bytes:
    req = urllib.request.Request(url, headers=HDR)
    with _OPENER.open(req, timeout=timeout) as resp:
        return resp.read()


# ── 接缝梯度判定（与 App 内 ScrambleDetector 同参数） ────────────────────

def block_bounds(h: int, splits: int, block: int):
    return (block * h) // splits, ((block + 1) * h) // splits


def seam_cost(gray: np.ndarray, splits: int, order) -> float:
    h, _w = gray.shape
    if h < splits:
        return float("inf")
    total, n = 0.0, 0
    for k in range(splits - 1):
        a_bottom = block_bounds(h, splits, order[k])[1] - 1
        b_top = block_bounds(h, splits, order[k + 1])[0]
        for off in range(2):
            ra, rb = a_bottom - off, b_top + off
            if ra < 0 or rb >= h:
                continue
            total += float(np.mean(np.abs(gray[ra].astype(int) - gray[rb].astype(int))))
            n += 1
    return float("inf") if n == 0 else total / n


def verdict(pixels: bytes) -> tuple:
    """返回 (cost_identity, cost_reversed, is_scrambled)。只判十等分倒序。"""
    img = Image.open(io.BytesIO(pixels)).convert("L")
    gray = np.asarray(img, dtype=np.int32)
    h = gray.shape[0]
    if h < 10:
        return (float("inf"),) * 2 + (False,)
    ident = seam_cost(gray, 10, list(range(10)))
    rev = seam_cost(gray, 10, list(range(9, -1, -1)))
    return ident, rev, (rev * 2 < ident and ident >= 20)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--photos", default="220981,268000,800000,250000,300000,1474353,1114751",
                    help="逗号分隔的章节 photo id 样本")
    ap.add_argument("--pages", type=int, default=2, help="每章抽查前 N 页")
    ap.add_argument("--host", default=None, help="指定图片 host（默认遍历候选表）")
    args = ap.parse_args()

    photos = [p.strip() for p in args.photos.split(",") if p.strip()]
    print(f"禁漫乱序规则金丝雀  API={API}  样本 {len(photos)} 章 x 前 {args.pages} 页")
    print("=" * 72)

    total, scrambled_n, failed = 0, 0, 0
    for pid in photos:
        try:
            chapter = api(f"/chapter?id={pid}")
        except Exception as e:
            print(f"photo={pid}  章节接口失败: {e}")
            failed += 1
            continue
        images = chapter.get("images") or []
        if not images:
            print(f"photo={pid}  images 为空（章节不存在或已下架）")
            failed += 1
            continue
        hosts = [args.host] if args.host else IMG_HOSTS
        for fn in images[: args.pages]:
            got = None
            for host in hosts:
                try:
                    got = fetch_image(f"https://{host}/media/photos/{pid}/{fn}")
                    break
                except Exception:
                    continue
            if got is None:
                print(f"photo={pid}  {fn}  全部 host 取图失败")
                failed += 1
                continue
            try:
                ci, cr, sc = verdict(got)
            except Exception as e:
                print(f"photo={pid}  {fn}  解码失败: {e}")
                failed += 1
                continue
            total += 1
            scrambled_n += 1 if sc else 0
            mark = "乱序→需还原" if sc else "正常"
            print(f"photo={pid:<9} {fn:<14} 恒等={ci:6.1f}  倒序={cr:6.1f}  -> {mark}")

    print("=" * 72)
    print(f"已判定 {total} 页，乱序 {scrambled_n} 页"
          f"（{0.0 if total == 0 else scrambled_n * 100.0 / total:.1f}%），失败 {failed} 页")
    print("占比为 0 = 服务端已停止打乱（App 内解码逻辑可整体关闭）；占比异常飙升 = 规则漂移，先跑本脚本再改代码。")
    return 0 if total > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
