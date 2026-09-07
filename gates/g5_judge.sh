#!/usr/bin/env bash
# G5 逐格裁判:在真伺服器裡(完整 registry + 資料包)把 G4 的輸出跟輸入逐容器、逐格 ItemStack.matches。
# G4 比的是「NBT 結構」;這一關比的是「遊戲看到的物品」——兩者都零差異,才叫資料沒動過。
# 比兩組:輸入 → A(直寫)、B(舊路徑)→ A(直寫)。
LC_TIER=G5
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
E=$LC_OUT/g4; R=$LC_OUT/g5; mkdir -p "$R"
bash "$LC_LIB_DIR/plugins/build.sh" || { fail "裁判外掛編譯失敗"; tier_verdict; exit 1; }
rig_kill
mkdir -p "$LC_RIG/plugins/LcCompare"; rm -f "$LC_RIG/plugins/LcCompare/report-"*.json
: > "$LC_RIG/plugins/LcCompare/jobs.txt"
while read -r label dim reg rx rz; do
  printf 'in-%s\t%s\t%s\n' "$label" "$LC_FIXTURES_DIR/$label/$reg.mca" "$E/A/$label/$reg.mca" >> "$LC_RIG/plugins/LcCompare/jobs.txt"
  printf 'BA-%s\t%s\t%s\n' "$label" "$E/B/$label/$reg.mca" "$E/A/$label/$reg.mca" >> "$LC_RIG/plugins/LcCompare/jobs.txt"
done < "$LC_FIXTURES_DIR/fixtures.tsv"
cp "$LC_LIB_DIR/plugins/lccompare/LcCompare.jar" "$LC_RIG/plugins/"
rig_boot "$R/console.log" -DdisableWatchdog=true || { fail "裁判伺服器沒起來"; tier_verdict; exit 1; }
for _ in $(seq 1 360); do sleep 5; grep -q "LCCOMPARE ALL DONE" "$R/console.log" && break; done
sleep 8; rig_kill; rm -f "$LC_RIG/plugins/LcCompare.jar"
grep -q "LCCOMPARE ALL DONE fails=0" "$R/console.log" && ok "裁判全部跑完、無工作失敗" || fail "裁判沒跑完或有工作炸掉"
cp "$LC_RIG/plugins/LcCompare/report-"*.json "$R/" 2>/dev/null
grep -a "LCCOMPARE" "$R/console.log" | sed 's/.*\] //; s/^/     /'
python3 "$LC_LIB_DIR/py/judge_frozen.py" "$LC_FIXTURES_DIR" "$R" > "$R/verdict.txt" 2>&1
rc=$?; sed 's/^/     /' "$R/verdict.txt"
[ $rc -eq 0 ] && ok "逐格裁判零差異" || fail "逐格裁判有差(見 $R/verdict.txt)"
tier_verdict
