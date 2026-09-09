# 出貨 gate 共用函式。所有 tier 腳本 source 這支。
set -uo pipefail
LC_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
. "$LC_LIB_DIR/config.env"

: "${LC_OUT:=$LC_GATE_HOME/out/manual}"
mkdir -p "$LC_OUT"
LC_RESULT="$LC_OUT/${LC_TIER:-tier}.result"
: > "$LC_RESULT"

_n_ok=0; _n_fail=0; _n_warn=0
ok()   { _n_ok=$((_n_ok+1));   echo "OK   $*"   | tee -a "$LC_RESULT"; }
fail() { _n_fail=$((_n_fail+1)); echo "FAIL $*" | tee -a "$LC_RESULT"; }
warn() { _n_warn=$((_n_warn+1)); echo "WARN $*" | tee -a "$LC_RESULT"; }
# assert <條件字串為真?> <描述>:用法 chk "$a" -eq "$b" "描述"
chk()  { if [ "$1" = "1" ]; then ok "$2"; else fail "$2"; fi; }
tier_verdict() {
  echo "---- ${LC_TIER:-tier}: OK=$_n_ok FAIL=$_n_fail WARN=$_n_warn" | tee -a "$LC_RESULT"
  [ "$_n_fail" -eq 0 ]
}

# ── rig 控制 ──
rig_kill() {
  for p in $(pgrep -x java 2>/dev/null); do
    [ "$(readlink /proc/$p/cwd 2>/dev/null)" = "$LC_RIG" ] && kill -9 "$p" 2>/dev/null
  done
  for _ in $(seq 1 20); do
    n=0; for p in $(pgrep -x java 2>/dev/null); do [ "$(readlink /proc/$p/cwd 2>/dev/null)" = "$LC_RIG" ] && n=$((n+1)); done
    [ "$n" = 0 ] && break; sleep 1
  done
  tmux kill-session -t "$LC_TMUX" 2>/dev/null
  sleep 1
}
rig_send() { tmux send-keys -t "$LC_TMUX" -l "$1"; sleep 0.4; tmux send-keys -t "$LC_TMUX" C-m; sleep "${2:-0.5}"; }
# rig_boot <console.log> <額外 java 旗標...>
rig_boot() {
  local console="$1"; shift
  # 埠先驗:被佔走時 Paper 只會印一行 "Address already in use" 然後照常走完關機流程,
  # 外面看到的只是「開機失敗」,分不出是 agent 炸了還是埠被別人拿走(2026-09-09 實測:
  # 25599 被另一個專案的測試伺服器佔走,紅綠驗證台整輪報開機失敗)。這裡直接講出是誰佔的。
  local holder
  holder=$(ss -ltnp 2>/dev/null | awk -v p=":$LC_RIG_PORT " 'index($4, p) {print $NF}' | head -1)
  if [ -n "$holder" ]; then
    local hpid; hpid=$(echo "$holder" | grep -oE 'pid=[0-9]+' | cut -d= -f2)
    echo "埠 $LC_RIG_PORT 已被占用:$holder cwd=$(readlink /proc/${hpid:-0}/cwd 2>/dev/null)"
    echo "  ⟹ 換一個埠:LC_RIG_PORT=<空的埠> bash gates/run.sh …(不要去殺那個程序,它可能是別人的)"
    return 1
  fi
  clear_forced_chunks
  rm -rf "$LC_RIG/logs"; mkdir -p "$LC_RIG/logs"
  tmux new-session -d -s "$LC_TMUX" -c "$LC_RIG"
  tmux send-keys -t "$LC_TMUX" "JAVA_HOME=$JAVA_HOME $JAVA_HOME/bin/java -Xmx$LC_RIG_XMX $* -jar server.jar nogui 2>&1 | tee $console" C-m
  local up=0
  for _ in $(seq 1 20); do
    for p in $(pgrep -x java 2>/dev/null); do [ "$(readlink /proc/$p/cwd 2>/dev/null)" = "$LC_RIG" ] && up=1; done
    [ $up = 1 ] && break; sleep 2
  done
  [ $up = 1 ] || { fail "rig 沒起來(見 $console)"; return 1; }
  for _ in $(seq 1 90); do sleep 5; grep -qE 'Done \([0-9.]+s\)' "$LC_RIG/logs/latest.log" 2>/dev/null && return 0; done
  fail "rig 開機沒到 Done(見 $console)"; return 1
}
stats_line() { grep -a "\[LazyContainer\] stash=" "$LC_RIG/logs/latest.log" 2>/dev/null | tail -1 | sed "s/.*LazyContainer\] //"; }
ctr() { echo "$1" | grep -oE "(^| )$2=-?[0-9]+" | tail -1 | cut -d= -f2; }
# forceload 整份 region(注意:/forceload 吃「方塊座標」,單次上限 256 chunk ⟹ 每份拆 4 批)
forceload_region() {
  local dim="$1" rx="$2" rz="$3" bx0=$(( $2 * 512 )) bz0=$(( $3 * 512 ))
  for bx in 0 256; do for bz in 0 256; do
    rig_send "execute in minecraft:$dim run forceload add $((bx0+bx)) $((bz0+bz)) $((bx0+bx+255)) $((bz0+bz+255))" 1.2
  done; done
}
# 送 tick freeze,並回報這個核心到底吃不吃。
# Folia 系(區域執行緒)把全域 tick 拿掉了,`/tick freeze` 不存在——靜默失敗會讓「凍結比對」
# 變成「一邊比一邊被世界改」,而且完全看不出來。所以要偵測並且講出來。
try_freeze() {
  local console="$1" before after
  # 判定用「正面證據」:抓 /tick freeze 成功時核心回的 "The game is frozen"(commands.tick.status.frozen)。
  # 舊版抓的是失敗證據(unknown command 的 `<--[HERE]` 記號),不可靠——伺服器正忙著 forceload 時,
  # 下一行指令會黏在上一行尾巴(實測看到 `…1281 -2817tick freeze<--[HERE]`),於是 Paper 也被誤判成
  # 「不支援 tick freeze」,整關就從凍結比對降級成機械證據判定,而且完全看不出來是誤判。
  # grep -c 零命中時「印 0 而且退出碼 1」⟹ 寫成 `|| echo 0` 會變成兩行 "0\n0",
  # 之後的整數比較直接爆 "integer expression expected"。只能用 || true。
  before=$(grep -aci "is frozen" "$console" 2>/dev/null || true); before=${before:-0}
  rig_send "" 0.6                     # 先送一個空行,把可能黏在上一行的輸入清乾淨
  rig_send "tick freeze" 2
  after=$(grep -aci "is frozen" "$console" 2>/dev/null || true); after=${after:-0}
  if [ "${after:-0}" -le "${before:-0}" ]; then
    LC_FROZEN=0
    warn "這個核心不支援 tick freeze(區域執行緒核心沒有全域 tick)——世界會邊比邊動,改用機械事件證據判定"
    return 1
  fi
  LC_FROZEN=1
  return 0
}

# 等 stash 穩定(連兩次相同且大於門檻)
wait_stash() {
  local min="${1:-1000}" prev=-1 cur
  for _ in $(seq 1 90); do
    sleep 10; cur=$(ctr "$(stats_line)" stash)
    [ "${cur:-0}" = "$prev" ] && [ "${cur:-0}" -gt "$min" ] && return 0
    prev="${cur:-0}"
  done
  return 1
}
# ⚠ forceload 的票會存進 <維度>/data/minecraft/chunk_tickets.dat,**下次開機會自動把那些 chunk 全部載回來**。
# 上一輪若在 `forceload remove all` 之前被中斷,下一次開機就會在「還沒 tick freeze」的狀態下
# 載入整批倉庫 —— 原版模式會當場 GC 死鎖(實測:9GB heap、Preparing spawn area 99% 卡住不動)。
# 所以每次擺素材時一併清掉。
clear_forced_chunks() {
  rm -f "$LC_RIG"/world/dimensions/minecraft/*/data/minecraft/chunk_tickets.dat
}
fixtures_each() { tr ' ' '\n' <<< "$LC_FIXTURES" | grep -v '^$'; }
fx_label() { cut -d: -f1 <<< "$1"; }
fx_dim()   { cut -d: -f2 <<< "$1"; }
fx_reg()   { cut -d: -f3 <<< "$1"; }
fx_rx()    { fx_reg "$1" | cut -d. -f2; }
fx_rz()    { fx_reg "$1" | cut -d. -f3; }
world_dir() { echo "$LC_RIG/world/dimensions/minecraft/$1"; }
