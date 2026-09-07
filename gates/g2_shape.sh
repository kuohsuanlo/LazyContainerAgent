#!/usr/bin/env bash
# G2 注入形狀閘門:對目標版 NMS jar 掃出「注入假設指紋」,與基準逐行 diff。
# 抓的是「新增的東西」——例如存檔鏈中間多了重建 CompoundTag 的步驟(直寫側車會被靜默丟掉),
# 或 leaf 新增直接讀 items 欄位、繞過 getItems() 咽喉的方法。這兩種靠「檢查我想得到的還在不在」抓不到。
LC_TIER=G2
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
cd "$LC_REPO" || exit 1
echo "== G2 注入形狀 =="
mkdir -p tools-out
mvn -q -B dependency:build-classpath -DincludeGroupIds=org.ow2.asm -Dmdep.outputFile=tools-out/asm.cp >/dev/null 2>&1
ASMCP="$(cat tools-out/asm.cp)"
javac -proc:none -nowarn -cp "$ASMCP" -d tools-out tools/InjectionShapeCheck.java || { fail "InjectionShapeCheck 編不過"; tier_verdict; exit 1; }
java -cp "tools-out:$ASMCP" InjectionShapeCheck "$LC_NMS_JAR" --baseline "$LC_SHAPE_BASELINE" > "$LC_OUT/shape.txt" 2>&1
rc=$?
sed 's/^/     /' "$LC_OUT/shape.txt" | tail -40
if [ $rc -eq 0 ]; then ok "注入形狀與基準 $(basename "$LC_SHAPE_BASELINE") 完全一致"
else fail "注入形狀與基準不同(見 $LC_OUT/shape.txt;每一行都要人看過,確認安全才更新基準)"; fi
tier_verdict
