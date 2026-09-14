#!/usr/bin/env bash
# ChunkHoldManager 端對端(真伺服器):重的格被留住、輕的格照常卸載、到期放掉、預算生效、零例外。
# 用法:LC_RIG=/home/logocat/_lcgate/rig-endrod bash gates/chunkhold_e2e.sh <plugin.jar> <fixture r.1.0.mca>
set -uo pipefail
cd "$(dirname "$0")/.."
export LC_GATE_HOME="${LC_GATE_HOME:-/home/logocat/_lcgate}"
. gates/config.env; . gates/versions/26.2.env; . gates/lib.sh
PLUGIN_JAR="$1"; FIXTURE="$2"
export LC_OUT="${LC_OUT:-$LC_GATE_HOME/out/chunkhold-$(date +%Y%m%d-%H%M%S)-$(basename "$LC_RIG")}"; mkdir -p "$LC_OUT"
export LC_RESULT="$LC_OUT/result.txt"; : > "$LC_RESULT"
console="$LC_OUT/console.log"
echo "rig=$LC_RIG out=$LC_OUT"

# ── 安裝:新 agent、外掛、測試用短時效設定、素材 ──
cp target/LazyContainerAgent.jar "$LC_RIG/LazyContainerAgent.jar"
mkdir -p "$LC_RIG/plugins/ChunkHoldManager"
rm -f "$LC_RIG"/plugins/ChunkHoldManager-*.jar; cp "$PLUGIN_JAR" "$LC_RIG/plugins/"
cat > "$LC_RIG/plugins/ChunkHoldManager/config.yml" <<CFG
enabled: true
heavy-bytes: 1048576
base-hold-seconds: 30
max-hold-seconds: 60
budget-bytes: 33554432
worlds: []
sweep-seconds: 5
report-minutes: 1
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
grep -q "\[ChunkHold\] 啟動" "$L" && ok "外掛啟動" || fail "外掛沒啟動(看 $console)"
grep -aq "LazyContainerAgent 26.2-10" "$console" && ok "agent 26.2-10 banner(premain 印在 console)" || warn "banner 沒看到 26.2-10:$(grep -a 'LazyContainerAgent ' "$console" | head -1)"

# ── 載入整份 region(1024 格,含 114 格 >2MB)──
forceload_region overworld 1 0
wait_stash 20000 || warn "stash 沒穩定(繼續)"
sleep 8
echo "stats: $(stats_line)"
grep -q "\[ChunkHold\] 反射入口就緒" "$L" && ok "反射入口就緒:$(grep -a '反射入口就緒' "$L" | head -1 | sed 's/.*就緒://')" || fail "反射入口沒解析(功能停用?)"
rig_send "chunkhold status" 2
st=$(grep -a "\[ChunkHold\] held=" "$L" | tail -1); echo "status(載入後): $st"
held=$(echo "$st" | grep -oE "held=[0-9]+" | cut -d= -f2); bytes=$(echo "$st" | grep -oE "bytes=[0-9.]+MB" | cut -d= -f2)
[ "${held:-0}" -gt 0 ] && ok "載入後有留住重的格:held=$held bytes=$bytes" || fail "載入後 held=0"
ev=$(echo "$st" | grep -oE "evicted=[0-9]+" | cut -d= -f2); sk=$(echo "$st" | grep -oE "skippedBudget=[0-9]+" | cut -d= -f2)
[ "$(( ${ev:-0} + ${sk:-0} ))" -gt 0 ] && ok "預算(32MB)生效:evicted=$ev skippedBudget=$sk" || fail "預算沒生效(114 格 >2MB 卻沒被踢/跳過)"
b=$(echo "$bytes" | sed 's/MB//'); awk -v b="$b" 'BEGIN{exit !(b<=33)}' && ok "留住總量 ${bytes} ≤ 預算 32MB" || fail "留住總量 ${bytes} 超過預算"
rig_send "chunkhold list" 2
top=$(grep -a "\[ChunkHold\] 留住中" "$L" | tail -1); echo "$top"
# 挑一格被留住的,記下座標(list 第一行)
first=$(grep -a "\[ChunkHold\] 留住中" -A1 "$L" | tail -1 | grep -oE "\(-?[0-9]+,-?[0-9]+\)" | head -1 | tr -d '()')
hx=${first%,*}; hz=${first#*,}
echo "追蹤的格: ($hx,$hz)"

# ── 拿掉 forceload:輕的格 3 秒後卸載,重的格要還在 ──
rig_send "forceload remove all" 2
sleep 15
rig_send "execute if loaded $((hx*16+8)) 64 $((hz*16+8)) run say HELD_STILL_LOADED" 2
grep -q "HELD_STILL_LOADED" "$L" && ok "forceload 拿掉 15 秒後,被留住的格 ($hx,$hz) 仍載入" || fail "被留住的格 ($hx,$hz) 已卸載(票沒生效?)"
# 找一格「沒被留住」的(輕的):r.1.0 內任一 chunk 只要不在 list 裡;用 (32,0) 角落
rig_send "execute if loaded 520 64 8 run say LIGHT_STILL_LOADED" 2
grep -q "LIGHT_STILL_LOADED" "$L" && warn "輕的格 (32,0) 15 秒後還載入著(可能是 delay 或還在卸載佇列)" || ok "輕的格 (32,0) 已照常卸載"
uw=$(grep -a "\[ChunkHold\] held=" "$L" | tail -1 | grep -oE "unloadedWhileHeld=[0-9]+" | cut -d= -f2)

# ── 等到期(最長 60s + sweep 5s)──
sleep 75
rig_send "chunkhold status" 2
st2=$(grep -a "\[ChunkHold\] held=" "$L" | tail -1); echo "status(到期後): $st2"
held2=$(echo "$st2" | grep -oE "held=[0-9]+" | cut -d= -f2); exp=$(echo "$st2" | grep -oE "expired=[0-9]+" | cut -d= -f2)
[ "${exp:-0}" -gt 0 ] && ok "到期放掉:expired=$exp" || fail "沒有任何到期放掉(expired=$exp)"
[ "${held2:-0}" = 0 ] && ok "到期後 held=0" || warn "到期後仍 held=$held2(可能有格被重新載入)"
uw2=$(echo "$st2" | grep -oE "unloadedWhileHeld=[0-9]+" | cut -d= -f2)
[ "${uw2:-0}" = 0 ] && ok "留住期間沒有任何格被卸載(unloadedWhileHeld=0)" || fail "留住期間有格被卸載:unloadedWhileHeld=$uw2"
sleep 10
rig_send "execute if loaded $((hx*16+8)) 64 $((hz*16+8)) run say HELD_AFTER_EXPIRE" 2
grep -q "HELD_AFTER_EXPIRE" "$L" && warn "到期後 ($hx,$hz) 仍載入(可能被別的票留住)" || ok "到期後 ($hx,$hz) 已卸載"

# ── 零例外、agent 統計 ──
n_exc=$(grep -a -c -E "ChunkHold.*(Exception|Error)|chunkhold.*at io\.github" "$L" || true)
[ "${n_exc:-0}" = 0 ] && ok "log 無 ChunkHold 例外" || fail "log 有 ChunkHold 例外 $n_exc 行"
s=$(stats_line); echo "final stats: $s"
[ -n "$(ctr "$s" rawMaxKB)" ] && ok "統計行有 rawMaxKB=$(ctr "$s" rawMaxKB) rawBig=$(ctr "$s" rawBig)" || fail "統計行沒有 rawMaxKB(agent 不是 26.2-10?)"
[ "$(ctr "$s" eagerLoad)" = 0 ] && ok "eagerLoad=0" || fail "eagerLoad=$(ctr "$s" eagerLoad)"

rig_send "stop" 2; sleep 30; rig_kill
mv "$LC_RIG/plugins/LcOps.jar.off" "$LC_RIG/plugins/LcOps.jar" 2>/dev/null || true
cp "$L" "$LC_OUT/latest.log" 2>/dev/null
tier_verdict
