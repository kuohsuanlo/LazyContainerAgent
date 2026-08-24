#!/usr/bin/env bash
# 跑「刀一(ensure 快取)」摘要邏輯的差分測試 —— 純 JUnit,零 Minecraft server。
#
# 同一份未解碼的 Items ListTag,一邊餵給 LazyContainerTemplate.lazycontainer$computeSummary(摘要),
# 一邊餵給真正的 ContainerHelper.loadAllItems(vanilla 解碼),比對兩邊結論是否一致。
# 摘要「棄答」不算失敗(退回原版路徑本來就安全);只要它開口回答就必須與 vanilla 一致。
#
# 需要 nms-lib/(同 build.sh)與 ~/.m2 內的 junit-platform-console-standalone。
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/home/logocat/.jdks/jdk-25.0.3+9}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -d nms-lib ] || [ -z "$(ls -A nms-lib/*.jar 2>/dev/null)" ]; then
  echo "ERROR: nms-lib/ 缺少 NMS 編譯相依 jar(見 build.sh 說明)。" >&2
  exit 1
fi

JUNIT="$(find "$HOME/.m2" -name 'junit-platform-console-standalone-*.jar' 2>/dev/null | sort | tail -1)"
if [ -z "$JUNIT" ]; then
  echo "== 取得 JUnit console launcher ==" >&2
  mvn -q -B dependency:get -Dartifact=org.junit.platform:junit-platform-console-standalone:1.11.3 >&2
  JUNIT="$(find "$HOME/.m2" -name 'junit-platform-console-standalone-*.jar' | sort | tail -1)"
fi

# nms-lib 內夾帶的舊版 com.mojang.logging 會遮蔽 server jar 內較新的 LogUtils
# (缺 getClassLogger → registry 初始化炸掉),故把 server jar 排前、排除該 lib。
SERVER_JAR="$(ls nms-lib/*mojmap*.jar nms-lib/paper-*.jar 2>/dev/null | head -1)"
REST="$(ls nms-lib/*.jar | grep -vF "$SERVER_JAR" | grep -v 'logging-1' | tr '\n' ':')"
NMSCP="${SERVER_JAR}:${REST}"

OUT=test-out
rm -rf "$OUT" && mkdir -p "$OUT"

echo "== 1. 編譯 template + 測試 =="
javac -proc:none -nowarn -cp "${NMSCP}:${JUNIT}" -d "$OUT" \
  template/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.java \
  tests/io/github/kuohsuanlo/lazycontainer/SummaryDifferentialTest.java \
  tests/io/github/kuohsuanlo/lazycontainer/AttributionClassifyTest.java \
  src/main/java/io/github/kuohsuanlo/lazycontainer/LazyContainerRuntime.java

echo "== 2. 執行差分測試 + 歸因分類測試 =="
java -jar "$JUNIT" execute \
  --class-path "${OUT}:${NMSCP}" \
  --select-class io.github.kuohsuanlo.lazycontainer.SummaryDifferentialTest \
  --select-class io.github.kuohsuanlo.lazycontainer.AttributionClassifyTest \
  --details=tree --disable-banner
