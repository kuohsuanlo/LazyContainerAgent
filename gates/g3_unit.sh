#!/usr/bin/env bash
# G3 差分/併發單元閘門:對「目標版本的真 codec」跑,零 Minecraft server。
# 這一關管的是語意:摘要必須與真解碼一致、物化的跨執行緒視窗、直寫框架與走訪器規則、component partial 語意。
LC_TIER=G3
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
cd "$LC_REPO" || exit 1
echo "== G3 單元/差分 =="
if bash test.sh > "$LC_OUT/test.log" 2>&1; then ok "test.sh 成功"; else fail "test.sh 失敗"; fi
sum=$(grep -E "tests (successful|failed)" "$LC_OUT/test.log" | tr -d '[]' | sed 's/^ *//')
echo "$sum" | sed 's/^/     /'
nsucc=$(grep -E "tests successful" "$LC_OUT/test.log" | grep -oE '[0-9]+' | head -1)
nfail=$(grep -E "tests failed"     "$LC_OUT/test.log" | grep -oE '[0-9]+' | head -1)
[ "${nfail:-1}" = "0" ] && ok "失敗 0 個" || fail "失敗 ${nfail} 個"
[ "${nsucc:-0}" -ge 60 ] && ok "成功 ${nsucc} 個(≥60:確認測試真的有編進去)" || fail "只跑了 ${nsucc} 個測試,少於預期(是不是有測試沒編譯到?)"
for t in SummaryDifferentialTest EnsureRaceTest AttributionClassifyTest ComponentPartialSemanticsTest RawPassthroughFramingTest PassthroughDeficitTest; do
  grep -q "$t" "$LC_OUT/test.log" && ok "測試類別有跑:$t" || fail "測試類別沒跑:$t"
done
tier_verdict
