#!/usr/bin/env bash
# v2 建置腳本 — 比 v1.0 簡化。
#
# v1.0 三步驟:
#   1) mvn package → agent 類別 + shaded ASM + manifest
#   2) javac template → 對真實 NMS 編譯 LazyContainerTemplate
#   3) jar uf → 注入 template .class 當 resource
#
# v2 簡化:
#   1) mvn package → agent 類別 + shaded ASM + manifest (同 v1.0)
#   (不再需要 template 編譯步驟)
#
# REVIEW(D3): template 目錄保留未刪除,但不再編譯。
#   template/LazyContainerTemplate.java 作為「v1.0 splice 架構」的參考,
#   以及未來 D3 option 3 (template 編譯策略) 的起點。
#   若要啟用 template 編譯: bash build.sh --with-template
set -euo pipefail
cd "$(dirname "$0")"

# Try SDKMAN if available (handles JAVA_HOME + PATH for Java/Maven)
if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
    set +u; source "$HOME/.sdkman/bin/sdkman-init.sh"; set -u
fi
# If SDKMAN is not available, set JAVA_HOME manually:
# JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ; export JAVA_HOME ; export PATH="$JAVA_HOME/bin:$PATH"
# 注意:對 26.2 mojmap NMS 編譯 template 時需 JDK 25 (major 69),nms-lib/ 放 26.2 server jar。

WITH_TEMPLATE=false
if [[ "${1:-}" == "--with-template" ]]; then
    WITH_TEMPLATE=true
    if [ ! -d nms-lib ] || [ -z "$(ls -A nms-lib/*.jar 2>/dev/null)" ]; then
        echo "ERROR: --with-template 需要 nms-lib/ (NMS 編譯相依 jar)" >&2
        exit 1
    fi
    NMSCP="$(ls nms-lib/*.jar | tr '\n' ':')"
fi

echo "== 1. mvn package =="
mvn -q -B clean package

JAR="target/LazyContainerAgent.jar"
[ -f "$JAR" ] || { echo "ERROR: $JAR 未產生" >&2; exit 1; }

if $WITH_TEMPLATE; then
    echo "== 2. (optional) compile template against real NMS =="
    rm -rf template-out && mkdir -p template-out
    javac -proc:none -nowarn -cp "${NMSCP}:target/classes" -d template-out \
        template/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.java
    echo "== 3. inject template .class into jar =="
    ( cd template-out && jar uf "../$JAR" io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.class )
fi

echo "== verify =="
echo "-- manifest --"
unzip -p "$JAR" META-INF/MANIFEST.MF | grep -E 'Premain|Agent-Class|Retransform|Redefine' || true
echo "-- agent classes --"
# Avoid broken pipe under 'set -o pipefail' by limiting rows inside awk.
unzip -l "$JAR" | awk '/lazycontainer\/(Lazy|Container|Detach|Version|Nms|asm\/)/ && $0 !~ /\/$/ { print; if (++n >= 30) exit }'
echo "-- relocated ASM present? --"
unzip -l "$JAR" | grep -c 'io/github/kuohsuanlo/lazycontainer/asm/' || true
echo ""
echo "== 5. run self-test =="
java -cp "$JAR" io.github.kuohsuanlo.lazycontainer.DemoRunner && echo "self-test: PASS" || echo "self-test: FAIL"
echo ""
echo "DONE: $(readlink -f "$JAR")"
