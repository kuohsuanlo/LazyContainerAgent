#!/usr/bin/env bash
# 跑 template 的 JUnit 測試 —— 零 Minecraft server(headless NMS,見 tests/NmsTestSupport.java)。
#
#  - SummaryDifferentialTest:同一份未解碼的 Items ListTag,一邊餵給 lazycontainer$computeSummary(摘要),
#    一邊餵給真正的 ContainerHelper.loadAllItems(vanilla 解碼),比對兩邊結論。摘要「棄答」不算失敗;
#    只要它開口回答就必須與 vanilla 一致。含 26.2-2 的 A2(非數值 Slot)雙向案例。
#  - EnsureRaceTest(26.2-2 / A1):繼承 template 的可實例化子類,兩條執行緒搶 ensure(),斷言
#    「pending=false ⟹ 清單完整」與「ensure 中途存檔絕非子集」。-Dlazycontainer.test.rounds=N 可調輪數。
#  - AttributionClassifyTest:ensure 觸發者歸因分類器(純 JDK)。
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
  tests/io/github/kuohsuanlo/lazycontainer/NmsTestSupport.java \
  tests/io/github/kuohsuanlo/lazycontainer/SummaryDifferentialTest.java \
  tests/io/github/kuohsuanlo/lazycontainer/EnsureRaceTest.java \
  tests/io/github/kuohsuanlo/lazycontainer/AttributionClassifyTest.java \
  tests/io/github/kuohsuanlo/lazycontainer/ComponentPartialSemanticsTest.java \
  tests/io/github/kuohsuanlo/lazycontainer/RawPassthroughFramingTest.java \
  src/main/java/io/github/kuohsuanlo/lazycontainer/LazyContainerRuntime.java

echo "== 2. 執行差分測試 + 併發測試 + 歸因分類測試 =="
# --sun-misc-unsafe-memory-access=allow:NmsTestSupport 用 Unsafe.allocateInstance 配假 server(JDK 25 會印警告)
java --sun-misc-unsafe-memory-access=allow -jar "$JUNIT" execute \
  --class-path "${OUT}:${NMSCP}" \
  --select-class io.github.kuohsuanlo.lazycontainer.SummaryDifferentialTest \
  --select-class io.github.kuohsuanlo.lazycontainer.EnsureRaceTest \
  --select-class io.github.kuohsuanlo.lazycontainer.AttributionClassifyTest \
  --select-class io.github.kuohsuanlo.lazycontainer.ComponentPartialSemanticsTest \
  --select-class io.github.kuohsuanlo.lazycontainer.RawPassthroughFramingTest \
  --details=tree --disable-banner
