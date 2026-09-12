#!/usr/bin/env bash
# G4 存檔四模式端對端(真實 region、凍結、不玩):
#   V 原版(完全不掛 agent)  A 直寫  B 關直寫(=26.2-2 舊路徑)  C 直寫觀測模式
# 四份輸出與輸入必須「結構相等」(compound key 順序不算差異——那是 Paper 雜湊表的迭代序)。
# V 是對照組:證明「載入→存檔」這條路本身就忠實,把「原版也會這樣」與「agent 弄的」分開。
# 反空洞:輸出檔的 chunk 時間戳必須真的變(沒變 = 根本沒載入,差異當然是 0)。
LC_TIER=G4
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
E=$LC_OUT/g4; mkdir -p "$E"
MODES="${LC_G4_MODES:-V A B C}"
flags_of() {
  local agent="-javaagent:$LC_RIG/LazyContainerAgent.jar -Dlazycontainer.verbose=true -Dlazycontainer.verbose.ms=15000"
  [ -f "${LC_CHUNKGUARD_JAR:-/nonexistent}" ] && agent="$agent -javaagent:$LC_RIG/ChunkGuardAgent.jar -Dchunkguard.verbose=true"
  case "$1" in
    V) echo "" ;;
    A) echo "$agent" ;;
    B) echo "$agent -Dlazycontainer.passthrough=false" ;;
    C) echo "$agent -Dlazycontainer.passthrough.shadow=true" ;;
  esac
}
cp "$LC_REPO/target/LazyContainerAgent.jar" "$LC_RIG/" || exit 1
[ -f "${LC_CHUNKGUARD_JAR:-/nonexistent}" ] && cp "$LC_CHUNKGUARD_JAR" "$LC_RIG/ChunkGuardAgent.jar"
# 收機械事件證據:核心不支援 tick freeze 時,世界會邊比邊動,要能分辨「誰動的」。
# 支援 freeze 的核心上這支外掛不會記到任何東西,無害。
bash "$LC_LIB_DIR/plugins/build.sh" >/dev/null 2>&1 || warn "測試外掛編譯失敗,將沒有機械事件證據"
rm -rf "$LC_RIG/plugins/LcOps"; mkdir -p "$LC_RIG/plugins/LcOps"
cp "$LC_LIB_DIR/plugins/lcops/LcOps.jar" "$LC_RIG/plugins/" 2>/dev/null
awk '{print $2, $4, $5}' "$LC_FIXTURES_DIR/fixtures.tsv" > "$LC_RIG/plugins/LcOps/regions.txt"
for m in $MODES; do
  echo "== G4 模式 $m =="
  rig_kill
  rm -f "$LC_RIG"/lc-badraw-* "$LC_RIG/plugins/LcOps/machinery.tsv"
  while read -r label dim reg rx rz; do
    w="$(world_dir "$dim")"; mkdir -p "$w/region" "$w/entities" "$w/poi"
    rm -f "$w/region/$reg.mca" "$w/entities/$reg.mca" "$w/poi/$reg.mca"
    cp "$LC_FIXTURES_DIR/$label/$reg.mca" "$w/region/"
    rm -f "$w/data/minecraft/chunk_tickets.dat"
  done < "$LC_FIXTURES_DIR/fixtures.tsv"
  rig_boot "$E/$m.console" $(flags_of "$m") || { fail "[$m] 開機失敗"; continue; }
  {
    grep -aE "passthrough armed|spliced|transformed leaf|hooked .* hopper|agent installed|ChunkGuard\] armed" "$E/$m.console" | sed 's/^ *//'
    echo "transform_failed=$(grep -ac 'transform failed' "$E/$m.console") circularity=$(grep -ac ClassCircularityError "$E/$m.console")"
  } > "$E/$m.boot"
  try_freeze "$E/$m.console" || true
  while read -r label dim reg rx rz; do forceload_region "$dim" "$rx" "$rz"; done < "$LC_FIXTURES_DIR/fixtures.tsv"
  if [ "$m" = "V" ]; then sleep 240; else wait_stash 1000 || warn "[$m] stash 沒有穩定下來"; fi
  rig_send "save-all flush" 2; sleep 40
  stats_line > "$E/$m.counters"
  rig_send "forceload remove all" 2; rig_send "stop" 2; sleep 45; rig_kill
  cp "$LC_RIG/logs/latest.log" "$E/$m.log" 2>/dev/null
  cp "$LC_RIG/plugins/LcOps/machinery.tsv" "$E/$m.machinery.tsv" 2>/dev/null || : > "$E/$m.machinery.tsv"
  while read -r label dim reg rx rz; do mkdir -p "$E/$m/$label"; cp "$(world_dir "$dim")/region/$reg.mca" "$E/$m/$label/$reg.mca"; done < "$LC_FIXTURES_DIR/fixtures.tsv"
  # ── 每個模式的閘門 ──
  ctrs=$(cat "$E/$m.counters"); echo "     counters: ${ctrs:-(原版無計數)}"
  ve=$(grep -acE 'VerifyError|NoSuchMethodError|IllegalAccessError' "$E/$m.log"); [ "$ve" = 0 ] && ok "[$m] 無 VerifyError/NoSuchMethod" || fail "[$m] VerifyError/NoSuchMethod $ve 行"
  ex=$(grep -ac 'Exception' "$E/$m.log"); [ "$ex" = 0 ] && ok "[$m] log 無 Exception" || warn "[$m] log 有 $ex 行 Exception"
  nbad=$(ls "$LC_RIG"/lc-badraw-* 2>/dev/null | wc -l); [ "$nbad" = 0 ] && ok "[$m] 無 lc-badraw 落檔" || fail "[$m] lc-badraw 落檔 $nbad 個"
  if [ "$m" != "V" ]; then
    grep -q 'transform_failed=0 circularity=0' "$E/$m.boot" && ok "[$m] transform 全成功、無 ClassCircularityError" || fail "[$m] transform 有失敗或 ClassCircularityError"
    st=$(ctr "$ctrs" stash); rs=$(ctr "$ctrs" rawSave); br=$(ctr "$ctrs" badRaw); el=$(ctr "$ctrs" eagerLoad)
    sm=$(ctr "$ctrs" shadowMismatch); mm=$(ctr "$ctrs" summaryMismatch)
    [ "${st:-0}" -gt 1000 ] && ok "[$m] stash=$st(延遲載入生效)" || fail "[$m] stash=$st 太少"
    [ "${rs:-0}" -gt 1000 ] && ok "[$m] rawSave=$rs(原樣寫回生效)" || fail "[$m] rawSave=$rs 太少"
    [ "${br:-1}" = 0 ] && ok "[$m] badRaw=0" || fail "[$m] badRaw=$br"
    [ "${el:-1}" = 0 ] && ok "[$m] eagerLoad=0" || fail "[$m] eagerLoad=$el"
    [ "${sm:-1}" = 0 ] && ok "[$m] shadowMismatch=0" || fail "[$m] shadowMismatch=$sm"
    [ "${mm:-1}" = 0 ] && ok "[$m] summaryMismatch=0" || fail "[$m] summaryMismatch=$mm"
    # 存檔守門:載入時有東西、沒人碰過、卻要寫出空的。正常運作下恆為 0;非 0 就是資料事故。
    # 26.2-2 沒有守門/自動降級/故障注入這些計數器與 log 行
    if [ "${LC_HAS_PASSTHROUGH:-0}" = 1 ]; then
    sw=$(ctr "$ctrs" silentWipe); sw=${sw%%/*}
    [ "${sw:-1}" = 0 ] && ok "[$m] silentWipe=0(無靜默清空)" || fail "[$m] silentWipe=$sw —— 有容器在沒人碰的情況下被清空"
    nsm=$(grep -ac 'SAFE MODE' "$E/$m.log" || true)
    [ "${nsm:-1}" = 0 ] && ok "[$m] 未觸發自動降級" || fail "[$m] 觸發了 SAFE MODE($nsm 行)"
    # 紅綠驗證台的故障注入旗標絕對不能出現在出貨驗證裡(那會故意弄壞資料)
    nfi=$(grep -ac 'FAULT INJECTION ACTIVE' "$E/$m.log" || true)
    [ "${nfi:-1}" = 0 ] && ok "[$m] 沒有帶到故障注入旗標" || fail "[$m] 帶了故障注入旗標($nfi 行)—— 這一輪的紅綠不算數"
    fi   # LC_HAS_PASSTHROUGH
  fi
  # 2026-09-12 revert 到 26.2-2:沒有 #261 直寫,模式 A/B/C 的直寫斷言全部跳過(LC_G4_MODES 也只剩 V A)
  if [ "${LC_HAS_PASSTHROUGH:-0}" = 1 ]; then
  case "$m" in
    A)
      pt=$(ctr "$ctrs" rawPassthrough); em=$(ctr "$ctrs" rawEmit); rs=$(ctr "$ctrs" rawSave)
      [ "${pt:-0}" -gt 1000 ] && ok "[A] rawPassthrough=$pt" || fail "[A] rawPassthrough=$pt 太少"
      awk -v a="${pt:-0}" -v b="${rs:-1}" 'BEGIN{exit !(a>=0.9*b)}' && ok "[A] rawPassthrough ≥ 0.9×rawSave($pt/$rs)" || fail "[A] rawPassthrough 只有 $pt / rawSave $rs"
      awk -v e="${em:-0}" -v p="${pt:-1}" 'BEGIN{exit !(e>=0.99*p)}' && ok "[A] rawEmit=$em ≥ 0.99×rawPassthrough=$pt(側車真的被寫進串流)" \
        || fail "[A] rawEmit=$em < rawPassthrough=$pt —— 核心把直寫的側車丟掉了(換版最危險的靜默失效)"
      # ⚠ set -o pipefail 之下不能寫成 `grep -c … | grep -q`:grep -c 零命中時退出碼是 1,
      # 整條管線就被判失敗(這條假紅實際發生過)。命令替換 + || true 才對。
      nbp=$(grep -ac 'BAD PASSTHROUGH' "$E/$m.log" || true)
      [ "${nbp:-1}" = 0 ] && ok "[A] 無 BAD PASSTHROUGH 告警" || fail "[A] 出現 BAD PASSTHROUGH($nbp 行)"
      ;;
    B)
      pt=$(ctr "$ctrs" rawPassthrough); [ "${pt:-1}" = 0 ] && ok "[B] rawPassthrough=0(直寫確實關掉了)" || fail "[B] 關了直寫卻還有 rawPassthrough=$pt" ;;
    C)
      okp=$(ctr "$ctrs" ptShadowOk); mis=$(ctr "$ctrs" ptShadowMismatch); pt=$(ctr "$ctrs" rawPassthrough)
      [ "${okp:-0}" -gt 1000 ] && ok "[C] ptShadowOk=$okp(觀測探針真的跑了)" || fail "[C] ptShadowOk=$okp 太少"
      [ "${mis:-1}" = 0 ] && ok "[C] ptShadowMismatch=0" || fail "[C] ptShadowMismatch=$mis"
      [ "${pt:-1}" = 0 ] && ok "[C] 觀測模式不寫直寫(rawPassthrough=0)" || fail "[C] 觀測模式竟然走了直寫 $pt" ;;
  esac
  fi   # LC_HAS_PASSTHROUGH
done
echo "== G4 結構比對 =="
cat "$E"/*.machinery.tsv 2>/dev/null | awk -F'\t' '{print $2}' | sort -u > "$E/machinery-all.txt"
python3 "$LC_LIB_DIR/py/structcmp.py" "$LC_FIXTURES_DIR" "$E" "$(echo $MODES | tr ' ' ',')" "$E/machinery-all.txt" > "$E/structcmp.txt" 2>&1
rc=$?; sed 's/^/     /' "$E/structcmp.txt"
[ $rc -eq 0 ] && ok "四模式與輸入結構完全相等" || fail "結構比對有差(見 $E/structcmp.txt)"
tier_verdict
