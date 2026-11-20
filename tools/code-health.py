#!/usr/bin/env python3
"""PiKA 代码健康度快照 / 回归门禁。

用法：
    python tools/code-health.py            # 打印指标
    python tools/code-health.py --check    # 与 tools/code-health-baseline.json 比较，变差则退出 1
    python tools/code-health.py --write-baseline  # 刷新基线（只应在确认改善后执行）

设计原则：只统计可机械判定、且历史上真实导致维护成本上升的指标。
不做「代码风格警察」——格式交给 IDE/编译器，这里只盯结构性退化。
"""
from __future__ import annotations

import json
import os
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app" / "src" / "main" / "java"
TEST = ROOT / "app" / "src" / "test" / "java"
BASELINE = ROOT / "tools" / "code-health-baseline.json"

FQN = re.compile(r'(?<![A-Za-z0-9_.])(?:com\.pika|androidx|android)\.[A-Za-z_]')
DECL = re.compile(
    r'^\s*(?:(?:private|internal|public|inline|suspend|override|operator|tailrec|@Composable)\s*)*'
    r'fun\s+([A-Za-z_][A-Za-z0-9_]*)'
)
BS = chr(92)


def kotlin_files() -> list[Path]:
    return sorted(SRC.rglob("*.kt"))


def code_only(text: str) -> str:
    """把注释/字符串替换为空格，保留换行，避免误报。"""
    out = list(text)
    n = len(text)
    i = 0

    def blank(a: int, b: int) -> None:
        for k in range(a, min(b, n)):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
            continue
        if text.startswith("/*", i):
            depth = 0
            j = i
            while j < n:
                if text.startswith("/*", j):
                    depth += 1
                    j += 2
                    continue
                if text.startswith("*/", j):
                    depth -= 1
                    j += 2
                    if depth == 0:
                        break
                    continue
                j += 1
            blank(i, j)
            i = j
            continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            blank(i, j)
            i = j
            continue
        if text[i] == '"':
            j = i + 1
            while j < n:
                if text[j] == BS:
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            blank(i, j)
            i = j
            continue
        i += 1
    return "".join(out)


def metrics() -> dict:
    files = kotlin_files()
    fqn_lines = 0
    todo = 0
    bang = 0
    large_files = 0
    long_functions = 0
    run_catching_suspend = 0
    windows: Counter = Counter()

    for path in files:
        raw = path.read_text(encoding="utf-8", errors="replace")
        lines = raw.splitlines()
        if len(lines) > 800:
            large_files += 1
        clean = code_only(raw).splitlines()
        for line in clean:
            stripped = line.strip()
            if stripped.startswith("import ") or stripped.startswith("package "):
                continue
            if FQN.search(line):
                fqn_lines += 1
            todo += len(re.findall(r"\b(?:TODO|FIXME|HACK|XXX)\b", line))
            bang += line.count("!!")

        # 近似函数长度：从声明行开始做花括号配平
        depth = 0
        pending: tuple[str, int] | None = None
        for i, line in enumerate(clean):
            if pending is None:
                m = DECL.match(line)
                if m and "{" in line:
                    pending = (m.group(1), i)
                    depth = 0
            if pending is not None:
                depth += line.count("{") - line.count("}")
                if depth <= 0 and i > pending[1]:
                    if i - pending[1] + 1 >= 150:
                        long_functions += 1
                    pending = None

        # 协程内 runCatching（近似：suspend fun 或 launch/async 块内）
        stack: list[tuple[str, int]] = []
        depth = 0
        for i, line in enumerate(clean):
            kind = None
            if re.search(r"\bsuspend\s+fun\b", line):
                kind = "suspend"
            elif re.search(r"\b(?:launch|async)\s*[\({]", line):
                kind = "launch"
            if kind and "{" in line:
                stack.append((kind, depth + 1))
            depth += line.count("{") - line.count("}")
            while stack and depth < stack[-1][1]:
                stack.pop()
            if "runCatching" in line and stack:
                run_catching_suspend += 1

        # 重复 10 行窗口（忽略 import / 纯括号 / 空行）
        norm = [re.sub(r"\s+", " ", l).strip() for l in lines]
        for i in range(len(norm) - 9):
            w = tuple(norm[i:i + 10])
            if sum(len(x) for x in w) < 250:
                continue
            if any(x.startswith("import ") for x in w):
                continue
            if all(not x or x in ("{", "}", "},", "),", ")", "},") for x in w):
                continue
            windows[w] += 1

    duplicate_windows = sum(1 for c in windows.values() if c >= 3)
    test_files = len(list(TEST.rglob("*.kt")))
    return {
        "files": len(files),
        "lines": sum(len(p.read_text(encoding="utf-8", errors="replace").splitlines()) for p in files),
        "fqn_lines": fqn_lines,
        "large_files": large_files,
        "long_functions": long_functions,
        "run_catching_in_coroutines": run_catching_suspend,
        "todo_markers": todo,
        "bang_bang": bang,
        "duplicate_windows": duplicate_windows,
        "test_files": test_files,
    }


def main() -> int:
    m = metrics()
    print(json.dumps(m, ensure_ascii=False, indent=2))
    if "--write-baseline" in sys.argv:
        BASELINE.write_text(json.dumps(m, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"baseline written: {BASELINE}")
        return 0
    if "--check" in sys.argv:
        if not BASELINE.exists():
            print("baseline missing; run --write-baseline first", file=sys.stderr)
            return 2
        base = json.loads(BASELINE.read_text(encoding="utf-8"))
        # 只对「越低越好」的结构指标做门禁；files/lines 是规模信息，正常开发会增长。
        worse_if_up = {
            "fqn_lines",
            "large_files",
            "long_functions",
            "run_catching_in_coroutines",
            "todo_markers",
            "bang_bang",
            "duplicate_windows",
        }
        worse = {
            k: (base.get(k), v)
            for k, v in m.items()
            if k in base and k in worse_if_up and v > base[k]
        }
        if "test_files" in base and m["test_files"] < base["test_files"]:
            worse["test_files"] = (base["test_files"], m["test_files"])
        if worse:
            print("\nCODE HEALTH REGRESSION:", file=sys.stderr)
            for k, (old, new) in worse.items():
                print(f"  {k}: {old} -> {new}", file=sys.stderr)
            return 1
        print("\ncode health OK (structural metrics not worse; tests not reduced)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
