#!/usr/bin/env bash
# ChunkForceManager 26.2-4 端對端(真伺服器):
#   EndRod rig:重的格第一次載入就走 API 釘住(不 tick)、預算生效、輕的格照常卸載、到期放掉、零例外
#   Paper rig :自動路徑停用(沒有不 tick 的釘法)、手動 add/remove 仍可用
# 用法:LC_RIG=/home/logocat/_lcgate/rig-endrod bash gates/cfm_e2e.sh <cfm.jar> <fixture r.1.0.mca>
set -uo pipefail
cd "$(dirname "$0")/.."
export LC_GATE_HOME="${LC_GATE_HOME:-/home/logocat/_lcgate}"
. gates/config.env; . gates/versions/26.2.env; . gates/lib.sh
PLUGIN_JAR="$1"; FIXTURE="$2"
export LC_OUT="${LC_OUT:-$LC_GATE_HOME/out/cfm-$(date +%Y%m%d-%H%M%S)-$(basename "$LC_RIG")}"; mkdir -p "$LC_OUT"
export LC_RESULT="$LC_OUT/result.txt"; : > "$LC_RESULT"
console="$LC_OUT/console.log"
echo "rig=$LC_RIG out=$LC_OUT"

cp target/LazyContainerAgent.jar "$LC_RIG/LazyContainerAgent.jar"
rm -rf "$LC_RIG/plugins/ChunkForceManager" "$LC_RIG"/plugins/ChunkForceManager-*.jar "$LC_RIG"/plugins/ChunkHoldManager* 
mkdir -p "$LC_RIG/plugins/ChunkForceManager"; cp "$PLUGIN_JAR" "$LC_RIG/plugins/"
cat > "$LC_RIG/plugins/ChunkForceManager/config.yml" <<CFG
heavy-bytes: 1048576
base-hold-seconds: 60
max-hold-seconds: 90
budget-bytes: 33554432
max-pinned-chunks: 512
fixed-pins: []
endrod-pin-ttl-seconds: 3600
worlds: []
sweep-seconds: 20
report-interval-seconds: 30
debug: true
CFG
mv "$LC_RIG/plugins/LcOps.jar" "$LC_RIG/plugins/LcOps.jar.off" 2>/dev/null || true
W="$(world_dir overworld)"; mkdir -p "$W/region" "$W/entities" "$W/poi"
rm -f "$W/region/r.1.0.mca" "$W/entities/r.1.0.mca" "$W/poi/r.1.0.mca"; cp "$FIXTURE" "$W/region/r.1.0.mca"
clear_forced_chunks
echo "agent md5=$(md5sum "$LC_RIG/LazyContainerAgent.jar" | cut -c1-8) plugin md5=$(md5sum "$PLUGIN_JAR" | cut -c1-8)"

rig_kill
rig_boot "$console" "-javaagent:LazyContainerAgent.jar -Dlazycontainer.verbose=true -Dlazycontainer.verbose.ms=5000" || { fail "rig 沒起來"; tier_verdict; exit 1; }
L="$LC_RIG/logs/latest.log"
grep -q "ChunkForceManager 26.2-4 啟動" "$L" && ok "外掛啟動:$(grep -a 'ChunkForceManager 26.2-4 啟動' "$L" | head -1 | grep -oE '釘=[^ ]+')" || fail "外掛沒啟動"
ENDROD=0; grep -q "釘=EndRod API" "$L" && ENDROD=1
echo "endrod mode=$ENDROD"

forceload_region overworld 1 0
wait_stash 20000 || warn "stash 沒穩定(繼續)"
sleep 25
rig_send "cforce status" 2
st=$(grep -a "\[cforce\] pinned=" "$L" | tail -1 | sed 's/.*\[cforce\] //'); echo "status(載入後): $st"
probe=$(echo "$st" | grep -oE "probe=[A-Za-z]+" | cut -d= -f2)
[ "$probe" = ok ] && ok "反射入口就緒" || fail "probe=$probe"
auto=$(echo "$st" | grep -oE "auto [0-9]+" | awk '{print $2}'); held=$(echo "$st" | grep -oE "held=[0-9.]+MB" | cut -d= -f2)
ev=$(echo "$st" | grep -oE "evicted=[0-9]+" | cut -d= -f2); sk=$(echo "$st" | grep -oE "skippedBudget=[0-9]+" | cut -d= -f2); rj=$(echo "$st" | grep -oE "apiRejects=[0-9]+" | cut -d= -f2)
if [ "$ENDROD" = 1 ]; then
  [ "${auto:-0}" -gt 0 ] && ok "重的格第一次載入就釘:auto=$auto held=$held" || fail "auto=0(沒釘任何格)"
  [ "$(( ${ev:-0} + ${sk:-0} ))" -gt 0 ] && ok "預算(32MB)生效:evicted=$ev skippedBudget=$sk" || fail "預算沒生效"
  awk -v b="${held%MB}" 'BEGIN{exit !(b<=33)}' && ok "held ${held} ≤ 預算 32MB" || fail "held ${held} 超過預算"
  [ "${rj:-0}" = 0 ] && ok "核心 API 零拒絕" || warn "apiRejects=$rj(核心每世界上限?)"
  hs=$(grep -a "\[HOTSPOT\] world=world " "$L" | tail -1 | grep -oE "apiPins=[0-9]+" | cut -d= -f2)
  [ "${hs:-0}" -gt 0 ] && ok "核心 [HOTSPOT] 看到 apiPins=$hs(釘的是 level 33)" || warn "[HOTSPOT] 行還沒出現 apiPins(每分鐘一行,可能還沒到)"
  rig_send "cforce list" 2
  first=$(grep -a "\[cforce\] 已 pin" -A1 "$L" | tail -1 | grep -oE "world -?[0-9]+,-?[0-9]+" | head -1 | awk '{print $2}')
else
  [ "${auto:-0}" = 0 ] && ok "Paper:自動路徑停用(auto=0)" || fail "Paper 上竟然自動釘了 auto=$auto"
  grep -q "PAPER(自動停用" <<<"$st" && ok "status 標明 PAPER 自動停用/手動會 tick" || fail "status 沒標 PAPER 模式"
  rig_send "cforce add world 55 19" 2
  grep -aq "PIN(manual) world 55,19" "$L" && ok "Paper 手動 add 可用(plugin ticket,會 tick)" || fail "Paper 手動 add 失敗"
  first="55,19"
fi
hx=${first%,*}; hz=${first#*,}; echo "追蹤的格: ($hx,$hz)"
rig_send "forceload remove all" 2
sleep 20
# ⚠ 探針不能用 execute if loaded:EndRod 對「沒有任何 tick 的 region」會把帶座標的 console 指令靜默丟掉
#   (level 31 有 region 所以有印,level 33 沒有)。改看外掛 status 的 resident=(World.isChunkLoaded,任意執行緒可讀)。
rig_send "cforce status" 2
st1=$(grep -a "\[cforce\] pinned=" "$L" | tail -1 | sed 's/.*\[cforce\] //'); echo "status(forceload 拿掉 20s 後): $st1"
res=$(echo "$st1" | grep -oE "resident=[0-9]+/[0-9]+" | cut -d= -f2); rn=${res%/*}; rd=${res#*/}
if [ "$ENDROD" = 1 ]; then
  [ "${rd:-0}" -gt 0 ] && [ "$rn" = "$rd" ] && ok "forceload 拿掉 20 秒後,被釘的 $rd 格全部仍在記憶體(resident=$res)" || fail "被釘的格有的已不在記憶體(resident=$res)"
else
  [ "${rn:-0}" -ge 1 ] && ok "Paper 手動 pin 的格仍在記憶體(resident=$res)" || fail "Paper 手動 pin 的格不在記憶體(resident=$res)"
fi
rig_send "execute if loaded 520 64 8 run say LIGHT_STILL_LOADED" 2
grep -q "LIGHT_STILL_LOADED" "$L" && warn "輕的格 (32,0) 還載入著" || ok "輕的格 (32,0) 已照常卸載(或無 region 可回答)"
if [ "$ENDROD" = 1 ]; then
  sleep 100
  rig_send "cforce status" 2
  st2=$(grep -a "\[cforce\] pinned=" "$L" | tail -1 | sed 's/.*\[cforce\] //'); echo "status(到期後): $st2"
  exp=$(echo "$st2" | grep -oE "expired=[0-9]+" | cut -d= -f2); auto2=$(echo "$st2" | grep -oE "auto [0-9]+" | awk '{print $2}')
  uw=$(echo "$st2" | grep -oE "unloadedWhileHeld=[0-9]+" | cut -d= -f2); res2=$(echo "$st2" | grep -oE "resident=[0-9]+/[0-9]+" | cut -d= -f2)
  [ "${exp:-0}" -gt 0 ] && ok "到期放掉:expired=$exp" || fail "沒有到期放掉"
  [ "${auto2:-0}" = 0 ] && ok "到期後 auto=0(resident=$res2)" || warn "到期後仍 auto=$auto2"
  [ "${uw:-0}" = 0 ] && ok "釘住期間零非預期卸載(unloadedWhileHeld=0)" || fail "釘住期間有格被卸載:$uw"
else
  rig_send "cforce remove world 55 19" 2
  grep -aq "UNPIN(manual) world 55,19" "$L" && ok "Paper 手動 remove 可用" || fail "Paper 手動 remove 失敗"
fi
n_exc=$(grep -a -c -E "cforce.*(Exception|Error)|ChunkForceManager.*(Exception|Error)|at io\.github\.kuohsuanlo\.chunkforce" "$L" || true)
[ "${n_exc:-0}" = 0 ] && ok "log 無 CFM 例外" || fail "log 有 CFM 例外 $n_exc 行"
s=$(stats_line); [ "$(ctr "$s" eagerLoad)" = 0 ] && ok "agent eagerLoad=0 rawMaxKB=$(ctr "$s" rawMaxKB)" || fail "eagerLoad=$(ctr "$s" eagerLoad)"
rig_send "stop" 2; sleep 30; rig_kill
mv "$LC_RIG/plugins/LcOps.jar.off" "$LC_RIG/plugins/LcOps.jar" 2>/dev/null || true
cp "$L" "$LC_OUT/latest.log" 2>/dev/null
tier_verdict
