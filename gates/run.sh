#!/usr/bin/env bash
# LazyContainerAgent 出貨 gate —— 一鍵跑完所有關卡並產出報告。
#
#   bash gates/run.sh                      跑全部(G0…G8)
#   bash gates/run.sh --tiers G0,G1,G2,G3  只跑指定關卡
#   bash gates/run.sh --tiers RED          紅綠驗證台:故意注入故障,證明警報真的會亮
#   bash gates/run.sh --version 26.3       換版本設定檔(gates/versions/26.3.env)
#   bash gates/run.sh --keep-fixtures      跑完保留素材副本(預設會刪:那是正式站的資料)
#
# 關卡與它們各自守什麼,見 gates/README.md;換版流程見 gates/UPGRADE.md。
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TIERS="G0,G1,G2,G3,G4,G5,G6,G7,G8"
while [ $# -gt 0 ]; do
  case "$1" in
    --tiers) TIERS="$2"; shift 2;;
    --version) export LC_MC_VERSION="$2"; shift 2;;
    --keep-fixtures) export LC_KEEP_FIXTURES=1; shift;;
    *) echo "不認得的參數 $1"; exit 2;;
  esac
done
# 被 Ctrl-C / kill 打斷時,把子關卡與測試伺服器一起收掉。
# 不收會留下孤兒:tier 腳本繼續跑、rig 伺服器繼續開著,下一輪就會跟它搶同一個 rig
# (實測踩過:孤兒 G4 把新一輪的世界換掉,還留下 forceload 票)。
cleanup() {
  trap - INT TERM
  for p in $(pgrep -f "gates/g[0-9]_" 2>/dev/null); do kill -9 "$p" 2>/dev/null; done
  for p in $(pgrep -x java 2>/dev/null); do
    [ "$(readlink /proc/$p/cwd 2>/dev/null)" = "${LC_RIG:-}" ] && kill -9 "$p" 2>/dev/null
  done
  tmux kill-session -t "${LC_TMUX:-lcgate}" 2>/dev/null
  echo "(已中斷:子關卡與測試伺服器都收掉了)"
  exit 130
}
trap cleanup INT TERM

export LC_RUN_ID="${LC_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LC_TIER=run . "$HERE/lib.sh"
export LC_OUT="$LC_GATE_HOME/out/$LC_RUN_ID"
mkdir -p "$LC_OUT"
REPORT="$LC_OUT/REPORT.md"
echo "出貨 gate:MC $LC_MC_VERSION,產物 $LC_OUT"
{
  echo "# LazyContainerAgent 出貨 gate 報告"
  echo
  echo "- 執行 : \`$LC_RUN_ID\`  目標版本 \`$LC_MC_VERSION\`"
  echo "- 主機 : $(hostname)  JDK $("$LC_JAVA_HOME/bin/java" -version 2>&1 | head -1)"
  echo "- commit: $(git -C "$LC_REPO" rev-parse --short HEAD 2>/dev/null)"
  echo
  echo "| 關卡 | 內容 | 結果 | OK | FAIL |"
  echo "|---|---|---|---|---|"
} > "$REPORT"
declare -A NAME=( [G0]="環境/版本" [G1]="建置+政策閘門" [G2]="注入形狀 diff" [G3]="差分/併發單元"
                  [G4]="存檔四模式 E2E" [G5]="逐格裁判" [G6]="摘要/影子對抗" [G7]="互動對抗總判定" [G8]="出貨物件" [RED]="紅綠驗證台(故意弄壞)" )
declare -A SCRIPT=( [G0]=g0_preflight.sh [G1]=g1_build.sh [G2]=g2_shape.sh [G3]=g3_unit.sh
                    [G4]=g4_e2e.sh [G5]=g5_judge.sh [G6]=g6_shadow.sh [G7]=g7_interactive.sh [G8]=g8_ship.sh [RED]=red.sh )
need_fixtures=0
for t in $(echo "$TIERS" | tr ',' ' '); do case "$t" in G4|G5|G6|G7|RED) need_fixtures=1;; esac; done
if [ "$need_fixtures" = 1 ]; then
  echo "== 準備 rig 與素材 =="
  bash "$HERE/rig/make_rig.sh" 2>&1 | tail -3
  # 素材抓不到就整輪中止。不中止的話後面每一關都會在「沒有素材」的情況下空跑,
  # 而空跑產出的是一整排看起來像真的的 FAIL(2026-09-09 實測:紅綠驗證台 16 個假紅)——
  # 那比空跑報綠更糟,因為它會讓人以為警報系統壞了。
  if ! bash "$HERE/rig/fixtures.sh" fetch 2>&1 | tail -8; then
    echo "素材準備失敗 —— 整輪中止(不讓後面的關卡空跑)"; exit 3
  fi
  if [ ! -s "${LC_FIXTURES_DIR:-$LC_GATE_HOME/fixtures}/fixtures.tsv" ]; then
    echo "素材清單是空的 —— 整輪中止(不讓後面的關卡空跑)"; exit 3
  fi
fi
total_fail=0
for t in $(echo "$TIERS" | tr ',' ' '); do
  s="${SCRIPT[$t]:-}"; [ -n "$s" ] || { echo "沒有這個關卡:$t"; continue; }
  echo; echo "######## $t ${NAME[$t]} ########"
  st=$(date +%s)
  bash "$HERE/$s" 2>&1 | tee "$LC_OUT/$t.log"
  rc=${PIPESTATUS[0]}
  el=$(( $(date +%s) - st ))
  # grep -c 零命中會回傳 1;寫成 `|| echo 0` 會變成印出兩行,表格就爛掉。用 || true。
  nok=$(grep -c '^OK ' "$LC_OUT/$t.result" 2>/dev/null || true); nok=${nok:-0}
  nfa=$(grep -c '^FAIL ' "$LC_OUT/$t.result" 2>/dev/null || true); nfa=${nfa:-0}
  [ "$rc" -ne 0 ] && total_fail=$((total_fail+1))
  printf '| %s | %s | %s(%ds) | %s | %s |\n' "$t" "${NAME[$t]}" \
    "$([ "$rc" -eq 0 ] && echo PASS || echo FAIL)" "$el" "$nok" "$nfa" >> "$REPORT"
done
{
  echo
  if [ "$total_fail" -eq 0 ]; then echo "## 總判定:PASS —— 可以出貨"; else echo "## 總判定:FAIL($total_fail 關) —— 不得出貨"; fi
  echo
  echo "各關卡明細見同目錄的 \`<關卡>.log\` 與 \`<關卡>.result\`。"
} >> "$REPORT"
# 自備素材(LC_FIXTURES_LOCAL=1)不刪——那是使用者自己的檔案,不是我們抓來的正式站副本
if [ "${LC_KEEP_FIXTURES:-0}" != "1" ] && [ "${LC_FIXTURES_LOCAL:-0}" != "1" ] && [ "$need_fixtures" = 1 ]; then
  bash "$HERE/rig/fixtures.sh" clean
fi
echo; cat "$REPORT"
exit $([ "$total_fail" -eq 0 ] && echo 0 || echo 1)
