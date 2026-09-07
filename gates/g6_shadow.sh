#!/usr/bin/env bash
# G6 摘要/影子對抗:開 -Dlazycontainer.shadow=true、讓世界真的 tick(漏斗、比較器會問摘要),
# 用「真解碼」當即時神諭校驗每一次快答與每一次原樣寫回。
#   shadowMismatch=0  → 寫回磁碟的與原版重新編碼結構相同
#   summaryMismatch=0 → 漏斗摘要每一次開口回答都與真解碼一致
# 這是唯一能在「執行中」驗摘要語意的一關(G3 是離線差分,這裡是真世界資料 + 真呼叫者)。
LC_TIER=G6
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
S=$LC_OUT/g6; mkdir -p "$S"
rig_kill
while read -r label dim reg rx rz; do
  w="$(world_dir "$dim")"; mkdir -p "$w/region" "$w/entities" "$w/poi"
  rm -f "$w/region/$reg.mca" "$w/entities/$reg.mca" "$w/poi/$reg.mca"
  cp "$LC_FIXTURES_DIR/$label/$reg.mca" "$w/region/"
  rm -f "$w/data/minecraft/chunk_tickets.dat"
done < "$LC_FIXTURES_DIR/fixtures.tsv"
cp "$LC_REPO/target/LazyContainerAgent.jar" "$LC_RIG/"
rig_boot "$S/console.log" -javaagent:"$LC_RIG/LazyContainerAgent.jar" -Dlazycontainer.shadow=true \
  -Dlazycontainer.verbose=true -Dlazycontainer.verbose.ms=15000 || { fail "開機失敗"; tier_verdict; exit 1; }
grep -q 'SHADOW mode' "$S/console.log" && ok "以 SHADOW 模式啟動" || fail "沒看到 SHADOW mode 開機行"
while read -r label dim reg rx rz; do forceload_region "$dim" "$rx" "$rz"; done < "$LC_FIXTURES_DIR/fixtures.tsv"
wait_stash 1000 || warn "stash 沒穩定"
echo "     世界持續 tick ${LC_G6_TICK_SECONDS:-240}s(讓漏斗/比較器真的去問摘要)"
sleep "${LC_G6_TICK_SECONDS:-240}"
rig_send "save-all flush" 2; sleep 40
stats_line > "$S/counters"; ctrs=$(cat "$S/counters"); echo "     counters: $ctrs"
rig_send "stop" 2; sleep 45; rig_kill
cp "$LC_RIG/logs/latest.log" "$S/server.log" 2>/dev/null
sm=$(ctr "$ctrs" shadowMismatch); mm=$(ctr "$ctrs" summaryMismatch); sb=$(ctr "$ctrs" summaryBuild)
sf=$(ctr "$ctrs" summaryFull); sk=$(ctr "$ctrs" summarySkip); en=$(ctr "$ctrs" ensure); br=$(ctr "$ctrs" benignReorder)
[ "${sm:-1}" = 0 ] && ok "shadowMismatch=0(原樣寫回與原版重編碼結構相同)" || fail "shadowMismatch=$sm"
[ "${mm:-1}" = 0 ] && ok "summaryMismatch=0(摘要快答與真解碼一致)" || fail "summaryMismatch=$mm"
[ "${sb:-0}" -gt 1000 ] && ok "summaryBuild=$sb(摘要有在建)" || fail "summaryBuild=$sb 太少"
[ $(( ${sf:-0} + ${sk:-0} )) -gt 0 ] && ok "摘要真的被問了:summaryFull=$sf summarySkip=$sk" || fail "整輪沒有任何摘要快答($sf/$sk)——這一關等於沒測到"
[ "${en:-0}" -gt 0 ] && ok "ensure=$en(有容器被真的物化)" || warn "ensure=$en"
echo "     benignReorder=$br(外掛寫法造成的無害順序差,不是問題)"
ve=$(grep -acE 'VerifyError|NoSuchMethodError' "$S/server.log"); [ "$ve" = 0 ] && ok "無 VerifyError/NoSuchMethod" || fail "VerifyError $ve 行"
tier_verdict
