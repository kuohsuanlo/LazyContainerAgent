#!/usr/bin/env bash
# G0 環境閘門:版本、工具、素材來源。這一關擋的是「拿錯 JDK / 拿錯核心 jar / 少裝東西」——
# 這些如果沒在最前面擋下,後面每一關的紅綠都不能信。
LC_TIER=G0
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
echo "== G0 環境 =="
jv=$("$LC_JAVA_HOME/bin/java" -version 2>&1 | head -1)
maj=$("$LC_JAVA_HOME/bin/java" -version 2>&1 | grep -oE '"[0-9]+' | tr -d '"' | head -1)
[ "$maj" = "$LC_JAVA_MAJOR" ] && ok "JDK $maj = 目標版要求($jv)" || fail "JDK $maj ≠ 目標版要求 $LC_JAVA_MAJOR($jv)"
[ -f "$LC_NMS_JAR" ] && ok "NMS jar 存在 $(basename "$LC_NMS_JAR")" || fail "缺 NMS jar $LC_NMS_JAR"
if [ -f "$LC_PAPER_BUNDLER" ]; then
  vj=$(unzip -p "$LC_PAPER_BUNDLER" version.json 2>/dev/null)
  id=$(echo "$vj" | grep -oE '"id"[^,]*' | cut -d'"' -f4)
  jr=$(echo "$vj" | grep -oE '"java_version": *[0-9]+' | grep -oE '[0-9]+')
  [ "$id" = "$LC_MC_VERSION" ] && ok "Paper bundler = $id" || fail "Paper bundler 是 $id,不是 $LC_MC_VERSION"
  [ "$jr" = "$LC_JAVA_MAJOR" ] && ok "核心要求 Java $jr" || fail "核心要求 Java $jr,設定寫 $LC_JAVA_MAJOR"
else
  fail "缺 Paper bundler jar $LC_PAPER_BUNDLER"
fi
# NMS 類別的 classfile major(template 必須編得出同版)
cm=$(unzip -p "$LC_NMS_JAR" net/minecraft/nbt/CompoundTag.class 2>/dev/null | od -An -tu1 -j7 -N1 | tr -d ' ')
[ "$cm" = "$LC_CLASSFILE_MAJOR" ] && ok "NMS classfile major = $cm" || fail "NMS classfile major = $cm,設定寫 $LC_CLASSFILE_MAJOR(ASM 與 JDK 都要跟上)"
[ -x "$LC_JAVA_HOME/bin/java" ] && ok "JAVA_HOME 存在 $LC_JAVA_HOME" || fail "LC_JAVA_HOME 指的路徑沒有 java:$LC_JAVA_HOME"
for t in tmux python3 node npm javac jar mvn unzip; do
  command -v "$t" >/dev/null && ok "工具 $t" || fail "缺工具 $t"
done
if [ "${LC_FIXTURES_LOCAL:-0}" = "1" ]; then ok "自備素材模式(不連艦隊)"; else
  command -v scp >/dev/null && ok "工具 scp" || fail "缺工具 scp(取素材要用;或設 LC_FIXTURES_LOCAL=1 自備)"
fi
if [ -f "${LC_CHUNKGUARD_JAR:-/nonexistent}" ]; then ok "選配 ChunkGuardAgent 會一起掛(驗兩支 agent 共存)"
else warn "沒有 ChunkGuardAgent(選配),本輪只掛 LazyContainerAgent"; fi
JUNIT=$(find "$HOME/.m2" -name 'junit-platform-console-standalone-*.jar' 2>/dev/null | head -1)
[ -n "$JUNIT" ] && ok "JUnit console standalone 已在 ~/.m2" || warn "~/.m2 沒有 JUnit console standalone(test.sh 會自己 mvn dependency:get,需要外網)"
n_nms=$(ls "$LC_REPO"/nms-lib/*.jar 2>/dev/null | wc -l)
[ "${n_nms:-0}" -ge 50 ] && ok "nms-lib 有 $n_nms 個 jar" || fail "nms-lib 只有 ${n_nms:-0} 個 jar(需要目標版 mojmap server jar + 它的 libraries;見 gates/README.md 前置需求)"
[ -d "$LC_GATE_HOME/node_modules/minecraft-protocol" ] && ok "bot 相依已安裝" || warn "缺 $LC_GATE_HOME/node_modules(G7 前會自動 npm install)"
n=0; for j in "$LC_VIA_DIR"/ViaVersion*.jar "$LC_VIA_DIR"/ViaBackwards*.jar; do [ -f "$j" ] && n=$((n+1)); done
[ "$n" -ge 2 ] && ok "ViaVersion + ViaBackwards 就位($n 個 jar)" || fail "缺 Via 外掛(bot 用舊協定連線需要);找不到於 $LC_VIA_DIR"
free_g=$(df -BG --output=avail "$LC_GATE_HOME" 2>/dev/null | tail -1 | tr -dc '0-9')
[ "${free_g:-0}" -ge 20 ] && ok "磁碟可用 ${free_g}G" || fail "磁碟只剩 ${free_g}G(素材+三模式輸出約需 20G)"
if [ "${LC_FIXTURES_LOCAL:-0}" = "1" ]; then
  miss=0
  for f in $(fixtures_each); do
    [ -f "$LC_FIXTURES_DIR/$(fx_label "$f")/$(fx_reg "$f").mca" ] || miss=$((miss+1))
  done
  [ "$miss" = 0 ] && ok "自備素材齊全" || fail "自備素材缺 $miss 份(放到 $LC_FIXTURES_DIR/<label>/<region>.mca)"
elif timeout 20 ssh -o BatchMode=yes -o ConnectTimeout=8 "$LC_FLEET_HOST" true 2>/dev/null; then
  ok "素材來源 $LC_FLEET_HOST 可連(唯讀取 region)"
else
  fail "連不上素材來源 $LC_FLEET_HOST;沒有艦隊存取就設 LC_FIXTURES_LOCAL=1 自備素材(見 gates/README.md)"
fi
tier_verdict
