#!/usr/bin/env bash
# G7 互動對抗(真 bot + 真外掛 API + 真指令 + 真漏斗):
#   輸入 →[玩家開箱/搬格/取物/挖箱、卸載重載、外掛 API、伺服器指令、save]= 中段快照 → 零容忍裁判 + 正控制逐格斷言
#   中段 →[tick step 讓漏斗真的搬東西、save]= 最終快照 → 只允許漏斗可解釋的差異 + 全 region 物品守恆
# 反空洞的核心:bot 自己說做了什麼不算數,要有伺服器端事件(OPEN/CLICK/BREAK/LCOPS/CMDRESULT)對得上。
LC_TIER=G7
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
W=$LC_OUT/g7; OPS=$W/ops; mkdir -p "$W" "$OPS"
RUN_START=$(date +%s)
python3 "$LC_LIB_DIR/py/genops.py" "$LC_FIXTURES_DIR" "$OPS" | sed 's/^/     /' || { fail "產生操作腳本失敗"; tier_verdict; exit 1; }
bash "$LC_LIB_DIR/plugins/build.sh" >/dev/null || { fail "測試外掛編譯失敗"; tier_verdict; exit 1; }
[ -d "$LC_GATE_HOME/node_modules/minecraft-protocol" ] || ( cd "$LC_GATE_HOME" && cp "$LC_LIB_DIR/bot/package.json" . && npm install --silent )

rig_kill
while read -r label dim reg rx rz; do
  w="$(world_dir "$dim")"; mkdir -p "$w/region" "$w/entities" "$w/poi"
  rm -f "$w/region/$reg.mca" "$w/entities/$reg.mca" "$w/poi/$reg.mca"
  cp "$LC_FIXTURES_DIR/$label/$reg.mca" "$w/region/"
  rm -f "$w/data/minecraft/chunk_tickets.dat"
done < "$LC_FIXTURES_DIR/fixtures.tsv"
cp "$LC_REPO/target/LazyContainerAgent.jar" "$LC_RIG/"
[ -f "${LC_CHUNKGUARD_JAR:-/nonexistent}" ] && cp "$LC_CHUNKGUARD_JAR" "$LC_RIG/ChunkGuardAgent.jar"
rm -rf "$LC_RIG/plugins/LcOps" "$LC_RIG/plugins/LcCompare"; rm -f "$LC_RIG/plugins/LcCompare.jar" "$LC_RIG"/lc-badraw-*
mkdir -p "$LC_RIG/plugins/LcOps"
cp "$LC_LIB_DIR/plugins/lcops/LcOps.jar" "$LC_RIG/plugins/"
cp "$OPS/lcops.txt" "$LC_RIG/plugins/LcOps/ops.txt"; cp "$OPS/cmds.txt" "$LC_RIG/plugins/LcOps/cmds.txt"
awk '{print $2, $4, $5}' "$LC_FIXTURES_DIR/fixtures.tsv" > "$LC_RIG/plugins/LcOps/regions.txt"

# bot 要有 op 才能下指令。全新的 rig 沒有 LcProbe 的 usercache,`/op LcProbe` 會失敗 ⟹
# 直接寫 ops.json:離線模式的 UUID 是 UUIDv3(md5) of "OfflinePlayer:<名字>",可離線算出來。
python3 - "$LC_RIG/ops.json" <<'PYEOF'
import hashlib, json, sys
h = bytearray(hashlib.md5(b"OfflinePlayer:LcProbe").digest())
h[6] = (h[6] & 0x0f) | 0x30
h[8] = (h[8] & 0x3f) | 0x80
u = h.hex()
uuid = f"{u[0:8]}-{u[8:12]}-{u[12:16]}-{u[16:20]}-{u[20:32]}"
json.dump([{"uuid": uuid, "name": "LcProbe", "level": 4, "bypassesPlayerLimit": True}], open(sys.argv[1], "w"))
print("     ops.json: LcProbe =", uuid)
PYEOF

CG=""; [ -f "${LC_CHUNKGUARD_JAR:-/nonexistent}" ] && CG="-javaagent:$LC_RIG/ChunkGuardAgent.jar -Dchunkguard.verbose=true"
rig_boot "$W/console.log" -javaagent:"$LC_RIG/LazyContainerAgent.jar" $CG \
  -Dlazycontainer.verbose=true -Dlazycontainer.verbose.ms=15000 || { fail "開機失敗"; tier_verdict; exit 1; }
{
  grep -aE "passthrough armed|spliced|transformed leaf|hooked .* hopper|agent installed|LCOPS watching" "$W/console.log" | sed 's/^ *//'
  echo "transform_failed=$(grep -ac 'transform failed' "$W/console.log") circularity=$(grep -ac ClassCircularityError "$W/console.log")"
} > "$W/boot.txt"
rig_send "tick freeze" 2; rig_send "op LcProbe" 2

echo "     bot 上線做事(開箱/搬格/取物/挖箱/卸載重載)"
# ⚠ Node 找 node_modules 是依「腳本所在目錄」往上找,不是依 cwd。bot.js 在 repo 裡、
# 相依裝在 gate home,所以一定要給 NODE_PATH,否則 MODULE_NOT_FOUND(實際踩過)。
( cd "$LC_GATE_HOME" && NODE_PATH="$LC_GATE_HOME/node_modules" LC_BOT_PROTOCOL="$LC_BOT_PROTOCOL" \
    node "$LC_LIB_DIR/bot/bot.js" 127.0.0.1 "$LC_RIG_PORT" "$OPS/ops.json" "$W/bot-result.json" > "$W/bot.log" 2>&1 )
echo "     bot exit=$?" >> "$W/bot.log"
grep -E "BOT-(DONE|KICK|DISC|FATAL)" "$W/bot.log" | tail -2 | sed 's/^/     /'
echo "after-bot: $(stats_line)" > "$W/counters.txt"

echo "     外掛 API 操作"
touch "$LC_RIG/plugins/LcOps/go-api";  for _ in $(seq 1 24); do sleep 5; [ -f "$LC_RIG/plugins/LcOps/done-api" ] && break; done
echo "     伺服器端指令(含金絲雀)"
touch "$LC_RIG/plugins/LcOps/go-cmds"; for _ in $(seq 1 24); do sleep 5; [ -f "$LC_RIG/plugins/LcOps/done-cmds" ] && break; done
cp "$LC_RIG/plugins/LcOps/result.txt" "$W/lcops-result.txt" 2>/dev/null || : > "$W/lcops-result.txt"
echo "after-ops: $(stats_line)" >> "$W/counters.txt"

date +%s%3N > "$W/mid-epoch.txt"     # 這一刻之前機械碰過的 = bot 段;之後的 = 漏斗段
# 請 LcOps 把每個容器的物化狀態(lazycontainer$pending)倒出來——「未被碰卻結構變了」要驗它真的被物化過
rm -f "$LC_RIG/plugins/LcOps/done-dump"; touch "$LC_RIG/plugins/LcOps/go-dump"; for _ in $(seq 1 24); do sleep 5; [ -f "$LC_RIG/plugins/LcOps/done-dump" ] && break; done
cp "$LC_RIG/plugins/LcOps/pending.tsv" "$W/pending-mid.tsv" 2>/dev/null || : > "$W/pending-mid.tsv"
rig_send "save-all flush" 2; sleep 40
while read -r label dim reg rx rz; do mkdir -p "$W/mid/$label"; cp "$(world_dir "$dim")/region/$reg.mca" "$W/mid/$label/$reg.mca"; done < "$LC_FIXTURES_DIR/fixtures.tsv"
echo "mid: $(stats_line)" >> "$W/counters.txt"

echo "     tick step ${LC_G7_TICKS:-600}(漏斗真的搬東西)"
rig_send "tick step ${LC_G7_TICKS:-600}" 2; sleep 60
rm -f "$LC_RIG/plugins/LcOps/done-dump"; touch "$LC_RIG/plugins/LcOps/go-dump"; for _ in $(seq 1 24); do sleep 5; [ -f "$LC_RIG/plugins/LcOps/done-dump" ] && break; done
cp "$LC_RIG/plugins/LcOps/pending.tsv" "$W/pending-out.tsv" 2>/dev/null || : > "$W/pending-out.tsv"
rig_send "save-all flush" 2; sleep 40
echo "final: $(stats_line)" >> "$W/counters.txt"
rig_send "stop" 2; sleep 50; rig_kill
cp "$LC_RIG/logs/latest.log" "$W/server.log" 2>/dev/null
cp "$LC_RIG/plugins/LcOps/trace.log" "$W/trace.log" 2>/dev/null
cp "$LC_RIG/plugins/LcOps/machinery.tsv" "$W/machinery.tsv" 2>/dev/null || : > "$W/machinery.tsv"
# 機械碰過的位置(有事件證據)拆成兩份:mid 之前(bot 段)與 mid 之後(漏斗段),給裁判當「可解釋」名單
python3 - "$W" "$LC_FIXTURES_DIR/fixtures.tsv" <<'PYEOF'
import sys
W, fx = sys.argv[1], sys.argv[2]
mid = int(open(f'{W}/mid-epoch.txt').read().strip())
rows = [l.rstrip('\n').split('\t') for l in open(f'{W}/machinery.tsv') if l.strip()]
labels = [l.split('\t')[:2] for l in open(fx).read().strip().split('\n')]
for label, dim in labels:
    before = [r for r in rows if r[0] == dim and int(r[2]) <= mid]
    after  = [r for r in rows if r[0] == dim and int(r[3]) >= mid]
    open(f'{W}/mach-mid-{label}.txt', 'w').write('\n'.join(r[1] for r in before) + '\n')
    open(f'{W}/mach-out-{label}.txt', 'w').write('\n'.join(r[1] for r in after) + '\n')
    # 帶種類的版本(pos \t kinds),給裁判判「發射器換了新界伏盒」用
    open(f'{W}/mach-mid-{label}.kinds', 'w').write('\n'.join(r[1] + '\t' + (r[4] if len(r) > 4 else 'M') for r in before) + '\n')
    open(f'{W}/mach-out-{label}.kinds', 'w').write('\n'.join(r[1] + '\t' + (r[4] if len(r) > 4 else 'M') for r in after) + '\n')
    print(f'     機械事件證據 [{label}] bot 段碰過 {len(before)} 個位置,漏斗段碰過 {len(after)} 個位置')
PYEOF
while read -r label dim reg rx rz; do mkdir -p "$W/out/$label"; cp "$(world_dir "$dim")/region/$reg.mca" "$W/out/$label/$reg.mca"; done < "$LC_FIXTURES_DIR/fixtures.tsv"
ls "$LC_RIG"/lc-badraw-* 2>/dev/null | wc -l > "$W/badraw-files.txt"
rm -f "$LC_RIG/plugins/LcOps.jar"
while read -r label dim reg rx rz; do
  for st in mid out; do python3 "$LC_REPO/tools/mca_restore.py" verify "$W/$st/$label/$reg.mca" --deep > "$W/verify-$st-$label.txt" 2>&1; done
done < "$LC_FIXTURES_DIR/fixtures.tsv"
python3 "$LC_LIB_DIR/py/saved_after_open.py" "$LC_FIXTURES_DIR" "$W" > "$W/saved-after-open.txt" 2>&1

echo "     裁判(另起一台伺服器,兩組工作)"
mkdir -p "$LC_RIG/plugins/LcCompare"; rm -f "$LC_RIG/plugins/LcCompare/report-"*.json
: > "$LC_RIG/plugins/LcCompare/jobs.txt"
while read -r label dim reg rx rz; do
  printf 'mid-%s\t%s\t%s\t%s\t%s\n' "$label" "$LC_FIXTURES_DIR/$label/$reg.mca" "$W/mid/$label/$reg.mca" "$OPS/expected-keys-$label.txt" "$W/mach-mid-$label.txt" >> "$LC_RIG/plugins/LcCompare/jobs.txt"
  printf 'out-%s\t%s\t%s\t%s\t%s\n' "$label" "$W/mid/$label/$reg.mca" "$W/out/$label/$reg.mca" "" "$W/mach-out-$label.txt" >> "$LC_RIG/plugins/LcCompare/jobs.txt"
done < "$LC_FIXTURES_DIR/fixtures.tsv"
cp "$LC_LIB_DIR/plugins/lccompare/LcCompare.jar" "$LC_RIG/plugins/"
rig_boot "$W/compare-console.log" -DdisableWatchdog=true || fail "裁判伺服器沒起來"
for _ in $(seq 1 360); do sleep 5; grep -q "LCCOMPARE ALL DONE" "$W/compare-console.log" && break; done
sleep 8; rig_kill; rm -f "$LC_RIG/plugins/LcCompare.jar"
cp "$LC_RIG/plugins/LcCompare/report-"*.json "$W/" 2>/dev/null
grep -a "LCCOMPARE" "$W/compare-console.log" | sed 's/.*\] //; s/^/     /'

python3 "$LC_LIB_DIR/py/judge.py" "$LC_FIXTURES_DIR" "$W" "$OPS" "$RUN_START" > "$W/verdict.txt" 2>&1
rc=$?; cat "$W/verdict.txt" | sed 's/^/     /'
[ $rc -eq 0 ] && ok "互動對抗總判定 PASS" || fail "互動對抗總判定 FAIL(見 $W/verdict.txt)"
tier_verdict
