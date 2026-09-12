#!/usr/bin/env bash
# 離線量測存檔路徑 parse 成本(序列 vs 多核心)。用法:bash tools/decode_bench.sh <r.X.Z.mca> [...]
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_HOME="${JAVA_HOME:-/home/logocat/.jdks/jdk-25.0.3+9}"; export JAVA_HOME; export PATH="$JAVA_HOME/bin:$PATH"
SERVER_JAR="$(ls nms-lib/*mojmap*.jar nms-lib/paper-*.jar 2>/dev/null | head -1)"
REST="$(ls nms-lib/*.jar | grep -vF "$SERVER_JAR" | grep -v 'logging-1' | tr '\n' ':')"
NMSCP="${SERVER_JAR}:${REST}"
OUT=bench-out; rm -rf "$OUT"; mkdir -p "$OUT"
javac -proc:none -nowarn -cp "${NMSCP}" -d "$OUT" \
  template/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.java \
  tests/io/github/kuohsuanlo/lazycontainer/NmsTestSupport.java \
  tools/DecodeBench.java \
  src/main/java/io/github/kuohsuanlo/lazycontainer/LazyContainerRuntime.java
exec java --sun-misc-unsafe-memory-access=allow -Xmx6g -cp "${OUT}:${NMSCP}" io.github.kuohsuanlo.lazycontainer.DecodeBench "$@"
