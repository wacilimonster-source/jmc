#!/usr/bin/env bash
# JM Reader 性能基线采集（adb 级别；Macrobenchmark 模块建立前的可运行替代）。
#
# 用法：
#   tools/perf/collect-baseline.sh --serial <serial> --runs 10 \
#     --out reports/performance/baseline-2026-11-03
#
# 产出：raw/ttid-<n>.txt、raw/gfxinfo-<n>.txt、raw/perfetto-<n>.perfetto-trace、summary.csv
#
# 注意：
#  - 需要已连接真机（release 或 profileable 构建）；没有设备时脚本直接退出，
#    绝不生成占位数字。
#  - 滚动流畅度需要固定手势；本脚本只采集启动与静态首屏，滚动请配合 UI Automator
#    或人工固定手势后另跑 gfxinfo。脚本会在 summary.csv 标记 scroll=not-captured。
set -u

SERIAL=""
RUNS=10
OUT="reports/performance/baseline-$(date +%Y-%m-%d)"
PACKAGE="com.jmread"
ACTIVITY="com.jmread/.MainActivity"
CONFIG="tools/perf/perfetto-config.pbtxt"
DEVICE_TRACE="/data/misc/perfetto-traces/jm-baseline.pftrace"

while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --runs) RUNS="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --package) PACKAGE="$2"; shift 2 ;;
    --activity) ACTIVITY="$2"; shift 2 ;;
    -h|--help) sed -n '1,20p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "未找到 adb。请安装 Android platform-tools 并加入 PATH。" >&2
  exit 2
fi

ADB=(adb)
if [ -n "$SERIAL" ]; then
  ADB=(adb -s "$SERIAL")
fi

DEVICES=$("${ADB[@]}" devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$DEVICES" ]; then
  echo "没有可用的 Android 设备/模拟器。基线未采集（不要填写占位数字）。" >&2
  exit 3
fi
echo "使用设备：$(echo "$DEVICES" | head -1)"

if [ ! -f "$CONFIG" ]; then
  echo "缺少 Perfetto 配置：$CONFIG" >&2
  exit 4
fi

mkdir -p "$OUT/raw"
SUMMARY="$OUT/summary.csv"
echo "run,total_time_ms,wait_time_ms,scroll" > "$SUMMARY"

for i in $(seq 1 "$RUNS"); do
  echo "── run $i/$RUNS ─────────────────────────────────────"
  "${ADB[@]}" shell am force-stop "$PACKAGE"

  # Perfetto 在后台采集，覆盖冷启动到首页稳定
  "${ADB[@]}" shell perfetto -c - --txt -o "$DEVICE_TRACE" < "$CONFIG" &
  PERFETTO_PID=$!
  sleep 2

  TTID_FILE="$OUT/raw/ttid-$i.txt"
  "${ADB[@]}" shell am start -W -n "$ACTIVITY" > "$TTID_FILE" 2>&1
  sleep 12

  wait "$PERFETTO_PID" 2>/dev/null || true
  "${ADB[@]}" pull "$DEVICE_TRACE" "$OUT/raw/perfetto-$i.perfetto-trace" >/dev/null 2>&1 || \
    echo "  !! perfetto trace 拉取失败（Android 10+ 才支持）" >&2
  "${ADB[@]}" shell rm -f "$DEVICE_TRACE" >/dev/null 2>&1 || true

  "${ADB[@]}" shell dumpsys gfxinfo "$PACKAGE" framestats > "$OUT/raw/gfxinfo-$i.txt" 2>&1 || true

  TOTAL=$(grep -m1 'TotalTime:' "$TTID_FILE" | awk '{print $2}')
  WAIT=$(grep -m1 'WaitTime:' "$TTID_FILE" | awk '{print $2}')
  echo "$i,${TOTAL:-},${WAIT:-},not-captured" >> "$SUMMARY"
  echo "  TotalTime=${TOTAL:-?}ms WaitTime=${WAIT:-?}ms"
done

echo
echo "完成。结果：$OUT"
echo "下一步：填写 $OUT/README.md 的环境表与指标对比；滚动/内存场景需另跑固定手势与 PSS 采样。"
