#!/usr/bin/env bash
# 建置 LazyContainerAgent:
#  1) mvn package      → agent 類別(Runtime/AgentMain/Transformer)+ shaded/relocated ASM + agent manifest
#  2) javac template   → 對「真實 26.2 mojmap NMS」編譯 LazyContainerTemplate(產生正確的 NMS 符號 bytecode)
#  3) jar uf           → 把 template .class 當 passive resource 注入 shaded jar(執行期只被讀 bytes、不被載入為類別)
# 注意:26.2 NMS classfile = major69,template 必須用 JDK 25 編譯;nms-lib/ 放 26.2 mojmap server jar + 其 libraries。
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/home/logocat/.jdks/jdk-25.0.3+9}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -d nms-lib ] || [ -z "$(ls -A nms-lib/*.jar 2>/dev/null)" ]; then
  echo "ERROR: nms-lib/ 缺少 NMS 編譯相依 jar(你的 Paper 伺服器核心的 NMS libraries)。" >&2
  exit 1
fi
NMSCP="$(ls nms-lib/*.jar | tr '\n' ':')"

echo "== 1. mvn package =="
mvn -q -B clean package

JAR="target/LazyContainerAgent.jar"
[ -f "$JAR" ] || { echo "ERROR: $JAR 未產生" >&2; exit 1; }

echo "== 2. compile template against real NMS (+ agent classes for LazyContainerRuntime) =="
rm -rf template-out && mkdir -p template-out
javac -proc:none -nowarn -cp "${NMSCP}:target/classes" -d template-out \
  template/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.java

echo "== 3. inject template .class into shaded jar =="
( cd template-out && jar uf "../$JAR" io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.class )

echo "== 4. verify =="
echo "-- manifest --"
unzip -p "$JAR" META-INF/MANIFEST.MF | grep -E 'Premain|Agent-Class|Retransform|Redefine|Implementation-Version' || true
echo "-- key entries --"
# head 會提早關管線讓 unzip 吃 SIGPIPE,配 pipefail 會把整個 build 判死 → 用 awk 截斷代替 head
unzip -l "$JAR" | awk '/lazycontainer\/(LazyContainer(Agent|Runtime|Transformer|Template)|asm\/)/ && ++n<=20'
echo "-- relocated ASM present? --"
unzip -l "$JAR" | grep -c 'io/github/kuohsuanlo/lazycontainer/asm/' || true
echo "== 5. bytecode 政策閘門(tools/LockPolicyCheck.java)=="
# 對「改寫後的真實 NMS 類別」機械驗證:碰 raw/摘要/ensuring 的方法都在 monitor 內、未持鎖讀者只讀
# volatile pending、三個 leaf 的 13 個入口 guard 到位、無殘留 ContainerHelper.load/saveAllItems、
# hopper 兩個摘要 hook 在方法入口。0.2 秒;任一違規 ⟹ build 失敗(這是出貨閘門,不是建議)。
# ASM classpath 用 maven 解析出的那一組(與 transformer 編譯時相同版本,避免 ~/.m2 內多版本混用)。
mkdir -p tools-out
mvn -q -B dependency:build-classpath -DincludeGroupIds=org.ow2.asm -Dmdep.outputFile=tools-out/asm.cp >/dev/null
ASMCP="$(cat tools-out/asm.cp):"
javac -proc:none -nowarn -cp "${ASMCP}target/classes" -d tools-out tools/LockPolicyCheck.java
java -cp "tools-out:${ASMCP}target/classes:template-out" LockPolicyCheck \
  "$(ls nms-lib/*mojmap*.jar nms-lib/paper-*.jar 2>/dev/null | head -1)" > tools-out/policy.log 2>&1 || true
tail -1 tools-out/policy.log
if ! grep -q "違規 0 項" tools-out/policy.log; then
  cat tools-out/policy.log >&2
  echo "ERROR: bytecode 政策閘門未通過,jar 不得出貨" >&2
  exit 1
fi
echo "DONE: $(readlink -f "$JAR")"
