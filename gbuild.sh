#!/usr/bin/env bash
# JM Reader 构建脚本。
#
# 为什么不用 ./gradlew：本沙箱环境下 ~/.gradle 下的 lock 文件偶尔无法重新打开，
# wrapper 会卡在 "Waiting to acquire shared lock"；这里绕过 wrapper，
# 直接用解压好的 Gradle 发行包 + 显式 JVM 参数跑一次性的 GradleMain。
# 注意：必须在**关闭沙箱隔离**的情况下运行，否则 rm 会被静默拒绝。
#
# 用法：
#   ./gbuild.sh                              # 默认：单测 + debug APK
#   ./gbuild.sh :app:assembleRelease         # release APK（minify + shrink）
#   ./gbuild.sh :app:testDebugUnitTest       # 只跑单测
#   ./gbuild.sh release                      # 组合：单测 + release + 按版本号重命名 APK
set -u

JAVA_BIN="/c/Program Files/Android/Android Studio/jbr/bin/java.exe"
LOG="/c/Users/${USER:-wacil}/AppData/Local/Temp/jm_build.log"

# AGP 8.9.0 要求 Gradle >= 8.11.1；版本号从 wrapper 配置里取，避免写死
GV=$(grep -oP 'gradle-\K[0-9.]+(?=-bin\.zip)' gradle/wrapper/gradle-wrapper.properties | head -1)
REAL=$(ls -d "/c/Users/${USER:-wacil}/.gradle/wrapper/dists/gradle-$GV-bin"/*/"gradle-$GV" 2>/dev/null | head -1)
if [ -z "$REAL" ]; then
  echo "未找到已解压的 Gradle $GV，请先执行一次 ./gradlew --version 触发下载" >&2
  exit 1
fi
GRADLE_HOME=$(cygpath -m "$REAL")

rm -rf "/c/Users/${USER:-wacil}/.gradle/native" "/c/Users/${USER:-wacil}/.gradle/daemon" 2>/dev/null
if [ -e "/c/Users/${USER:-wacil}/.gradle/native" ]; then
  echo "!! ~/.gradle/native 未能删除（沙箱？），构建大概率会失败" >&2
fi

# 与 gradle.properties 的 org.gradle.jvmargs 保持一致，避免 fork 单次 daemon
JVM_ARGS=(
  -Dfile.encoding=UTF-8
  -XX:+UseG1GC
  -XX:SoftRefLRUPolicyMSPerMB=1
  -XX:ReservedCodeCacheSize=512m
  -XX:MaxMetaspaceSize=1024m
  -XX:+HeapDumpOnOutOfMemoryError
  -Xms2g
  -Xmx4g
)
OPENS=(
  --add-opens=java.base/java.lang=ALL-UNNAMED
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
  --add-opens=java.base/java.util=ALL-UNNAMED
  --add-opens=java.prefs/java.util.prefs=ALL-UNNAMED
  --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
  --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
  --add-opens=java.base/java.nio.charset=ALL-UNNAMED
  --add-opens=java.base/java.net=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
  --add-opens=java.xml/javax.xml.namespace=ALL-UNNAMED
  --add-opens=java.base/java.time=ALL-UNNAMED
)

# 默认任务；`release` 是组合任务的语法糖
TASKS=("$@")
if [ $# -eq 0 ]; then
  TASKS=(:app:testDebugUnitTest :app:assembleDebug)
elif [ "$1" = "release" ]; then
  TASKS=(:app:testDebugUnitTest :app:assembleRelease)
fi

"$JAVA_BIN" "${JVM_ARGS[@]}" "${OPENS[@]}" \
  -Dorg.gradle.appname=gradle \
  -classpath "$GRADLE_HOME/lib/gradle-launcher-$GV.jar" \
  org.gradle.launcher.GradleMain \
  --no-daemon --console=plain "${TASKS[@]}" > "$LOG" 2>&1
EXIT=$?

echo "EXIT=$EXIT"
grep -E "^e: |FAILURE|BUILD SUCCESSFUL|BUILD FAILED|error:" "$LOG" | head -40

# release 成功后按 jm-reader-v{版本号}.apk 重命名，方便直接丢进更新仓库
if [ $EXIT -eq 0 ] && [ "${TASKS[*]}" != "${TASKS[*]/assembleRelease/}" ]; then
  VER=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)
  SRC="app/build/outputs/apk/release/app-release.apk"
  DST="jm-reader-v${VER}.apk"
  if [ -f "$SRC" ]; then
    cp -f "$SRC" "$DST"
    echo "已产出 $DST  sha256=$(sha256sum "$DST" | cut -d' ' -f1)"
    echo "下一步：把 $DST 提交到更新仓库，并用上面的 sha256 更新 update.json"
  else
    echo "!! 未找到 $SRC" >&2
  fi
fi

echo "(完整日志: $LOG)"
exit $EXIT
