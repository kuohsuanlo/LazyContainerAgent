#!/usr/bin/env bash
# 紅綠驗證台:故意把資料弄壞,證明警報真的會亮 —— 而且證明關掉警報就真的會靜默掉東西。
#
# 「沒有紅就不准判綠」:一個從來沒有真的亮過紅燈的警報系統,和沒有警報系統是一樣的。
#
# 五個回合,全部跑在真實的正式站 region 素材上(唯讀複本,不碰線上):
#   green            不注入故障               ⟹ 不得有任何警報,磁碟零流失
#   wipeKeepRaw      清單被清空,但 raw 還在   ⟹ 存檔本來就原樣寫回,磁碟零流失(這是主要防線)
#   wipe             物化過、沒人碰過就被清空   ⟹ 必須亮 SILENT WIPE + SAFE MODE,而且用留著的 raw 寫回 ⟹ 磁碟零流失
#   wipe-noguard     同上但關掉守門(對照組)   ⟹ 必須「沒有警報」而且磁碟真的掉東西
#   wipeAccessed     有人碰過之後才被清空       ⟹ 不得自動寫回(那可能是玩家拿光的),整個 chunk 大面積歸零時
#                                                 必須 MASS EMPTY 落檔 + 報警 + SAFE MODE;磁碟會掉,但救援檔裡一個都不少
#   corruptRaw       raw 被改壞一個 byte       ⟹ 核心自己的 NbtIo 解爆,必須亮 BAD RAW + SAFE MODE
#   dropSideCar      直寫的側車被吞掉           ⟹ rawEmit 追不上 rawPassthrough,必須亮 BAD PASSTHROUGH;
#                                                 而且磁碟上的指紋必須是「缺 Items 鍵」而不是「空清單」
#
# 判準寫在最後的 verdict:紅回合亮紅 + 綠回合全綠 + 對照組真的掉東西,三者同時成立才算通過。
LC_TIER=RED
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
E=$LC_OUT/red; mkdir -p "$E"
ROUNDS="${LC_RED_ROUNDS:-green wipeKeepRaw wipe wipe-noguard wipeAccessed corruptRaw dropSideCar}"
EVERY="${LC_RED_EVERY:-50}"          # 每 N 個容器注入一次

flags_of() {
  local a="-javaagent:$LC_RIG/LazyContainerAgent.jar -Dlazycontainer.verbose=true -Dlazycontainer.verbose.ms=15000"
  case "$1" in
    green)        echo "$a" ;;
    wipeKeepRaw)  echo "$a -Dlazycontainer.fault=wipeKeepRaw -Dlazycontainer.fault.every=$EVERY" ;;
    wipe)         echo "$a -Dlazycontainer.fault=wipe -Dlazycontainer.fault.every=$EVERY" ;;
    wipe-noguard) echo "$a -Dlazycontainer.fault=wipe -Dlazycontainer.fault.every=$EVERY -Dlazycontainer.guard=false" ;;
    wipeAccessed) echo "$a -Dlazycontainer.fault=wipeAccessed -Dlazycontainer.fault.every=$EVERY" ;;
    corruptRaw)   echo "$a -Dlazycontainer.fault=corruptRaw -Dlazycontainer.fault.every=$EVERY" ;;
    dropSideCar)  echo "$a -Dlazycontainer.fault=dropSideCar -Dlazycontainer.fault.every=$EVERY" ;;
  esac
}

cp "$LC_REPO/target/LazyContainerAgent.jar" "$LC_RIG/" || exit 1
# 只用一份素材:紅綠驗證要的是「訊號有沒有出現」,不是覆蓋率(覆蓋率是 G4 的事)。
# 沒有素材就不要跑:空跑會把每一條斷言都判成紅,看起來像「警報壞了」,其實是「什麼都沒跑」。
[ -s "$LC_FIXTURES_DIR/fixtures.tsv" ] || { fail "沒有素材($LC_FIXTURES_DIR/fixtures.tsv 是空的)—— 這一關不算數"; tier_verdict; exit 1; }
FIX=$(head -1 "$LC_FIXTURES_DIR/fixtures.tsv")
read -r label dim reg rx rz <<<"$FIX"
[ -n "$reg" ] && [ -f "$LC_FIXTURES_DIR/$label/$reg.mca" ] \
  || { fail "素材檔不在($LC_FIXTURES_DIR/$label/$reg.mca)—— 這一關不算數"; tier_verdict; exit 1; }
echo "素材:$label $dim $reg"

for r in $ROUNDS; do
  echo "== 紅綠回合 $r =="
  rig_kill
  rm -f "$LC_RIG"/lc-badraw-* "$LC_RIG"/lc-massempty-*
  w="$(world_dir "$dim")"; mkdir -p "$w/region" "$w/entities" "$w/poi"
  rm -f "$w/region/$reg.mca" "$w/entities/$reg.mca" "$w/poi/$reg.mca" "$w/data/minecraft/chunk_tickets.dat"
  cp "$LC_FIXTURES_DIR/$label/$reg.mca" "$w/region/"
  rig_boot "$E/$r.console" $(flags_of "$r") || { fail "[$r] 開機失敗"; continue; }
  try_freeze "$E/$r.console" || true
  forceload_region "$dim" "$rx" "$rz"
  wait_stash 1000 || warn "[$r] stash 沒有穩定下來"
  rig_send "save-all flush" 2; sleep 90   # 留夠時間讓統計執行緒吐出足夠的取樣點給對帳判準
  stats_line > "$E/$r.counters"
  rig_send "forceload remove all" 2; rig_send "stop" 2; sleep 45; rig_kill
  cp "$LC_RIG/logs/latest.log" "$E/$r.log" 2>/dev/null
  mkdir -p "$E/$r"; cp "$w/region/$reg.mca" "$E/$r/$reg.mca"
  echo "$(ls "$LC_RIG"/lc-badraw-* 2>/dev/null | wc -l)" > "$E/$r.dumps"
  mkdir -p "$E/$r.massempty"; cp "$LC_RIG"/lc-massempty-* "$E/$r.massempty/" 2>/dev/null || true
  python3 "$LC_REPO/tools/container_loss_watch.py" "$LC_FIXTURES_DIR/$label/$reg.mca" "$E/$r/$reg.mca" --all \
    > "$E/$r.loss" 2>&1 || true
  echo "     $(head -1 "$E/$r.loss")"
done

# ── 判定 ────────────────────────────────────────────────────────────────────────
n() { local v; v=$(grep -ac "$2" "$E/$1.log" 2>/dev/null || true); echo "${v:-0}"; }
ctrv() { local v; v=$(ctr "$(cat "$E/$1.counters" 2>/dev/null)" "$2"); echo "${v:-0}"; }
emptied() { grep -oE "歸零容器 [0-9]+ 個" "$E/$1.loss" 2>/dev/null | grep -oE "[0-9]+" | awk '{s+=$1} END{print s+0}'; }

for r in $ROUNDS; do
  [ -f "$E/$r.log" ] || continue
  printf "     %-13s FAULT=%-4s SILENT_WIPE=%-4s MASS_EMPTY=%-3s BAD_RAW=%-4s SAFE_MODE=%-3s silentWipe=%-8s badRaw=%-4s 歸零=%s\n" \
    "$r" "$(n "$r" 'FAULT ')" "$(n "$r" 'SILENT WIPE')" "$(n "$r" 'MASS EMPTY')" "$(n "$r" 'BAD RAW')" "$(n "$r" 'SAFE MODE')" \
    "$(grep -oE 'silentWipe=[0-9]+/[0-9]+' "$E/$r.counters" | head -1 | cut -d= -f2)" "$(ctrv "$r" badRaw)" "$(emptied "$r")"
done

case " $ROUNDS " in *" green "*)
  [ "$(n green 'SILENT WIPE')" = 0 ] && [ "$(n green 'BAD RAW')" = 0 ] && [ "$(n green 'SAFE MODE')" = 0 ] \
    && ok "[green] 不注入故障時完全安靜(沒有假紅)" || fail "[green] 沒注入故障卻有警報 —— 假紅"
  [ "$(emptied green)" = 0 ] && ok "[green] 磁碟零流失" || fail "[green] 磁碟流失 $(emptied green) 個容器"
;; esac
case " $ROUNDS " in *" wipeKeepRaw "*)
  [ "$(n wipeKeepRaw 'FAULT ')" -gt 0 ] && ok "[wipeKeepRaw] 故障真的注入了($(n wipeKeepRaw 'FAULT ') 次)" || fail "[wipeKeepRaw] 故障沒注入,這一回合不算數"
  [ "$(emptied wipeKeepRaw)" = 0 ] \
    && ok "[wipeKeepRaw] 清單被清空但 raw 還在 ⟹ 存檔原樣寫回,磁碟零流失(主要防線成立)" \
    || fail "[wipeKeepRaw] 磁碟流失 $(emptied wipeKeepRaw) 個容器 —— 原樣寫回沒守住"
;; esac
case " $ROUNDS " in *" wipe "*)
  [ "$(n wipe 'FAULT ')" -gt 0 ] && ok "[wipe] 故障真的注入了($(n wipe 'FAULT ') 次)" || fail "[wipe] 故障沒注入,這一回合不算數"
  [ "$(n wipe 'SILENT WIPE')" -gt 0 ] && ok "[wipe] 亮紅:SILENT WIPE $(n wipe 'SILENT WIPE') 行" || fail "[wipe] 沒亮紅 —— 警報是死的"
  swv=$(grep -oE "silentWipe=[0-9]+/[0-9]+" "$E/wipe.counters" | head -1); swn=${swv#silentWipe=}; swd=${swn%%/*}; swh=${swn##*/}
  [ "${swd:-0}" -gt 0 ] && ok "[wipe] 計數器 silentWipe=$swn(偵測/自救)" || fail "[wipe] 計數器沒動"
  [ "${swh:-0}" = "${swd:-1}" ] && ok "[wipe] 自救 $swh/$swd —— 留著的 raw 全部寫回去了" \
    || fail "[wipe] 自救只有 $swh/$swd —— 留著的 raw 沒有全部寫回"
  [ "$(emptied wipe)" = 0 ] && ok "[wipe] 磁碟零流失(偵測到 + 用原始資料重寫 = 你要的那件事)" \
    || fail "[wipe] 磁碟流失 $(emptied wipe) 個容器 —— 寫回沒有落到磁碟"
  [ "$(n wipe 'SAFE MODE')" -gt 0 ] && ok "[wipe] 自動降級 SAFE MODE 觸發" || fail "[wipe] 沒有降級"
;; esac
case " $ROUNDS " in *" wipeAccessed "*)
  [ "$(n wipeAccessed 'FAULT ')" -gt 0 ] && ok "[wipeAccessed] 故障真的注入了($(n wipeAccessed 'FAULT ') 次)" || fail "[wipeAccessed] 故障沒注入"
  [ "$(n wipeAccessed 'SILENT WIPE')" = 0 ] && ok "[wipeAccessed] 碰過的容器不走靜默清空(不得自動寫回)" || fail "[wipeAccessed] 碰過的容器被當成靜默清空寫回了 —— 那會複製"
  [ "$(n wipeAccessed 'MASS EMPTY')" -gt 0 ] && ok "[wipeAccessed] 亮紅:MASS EMPTY $(n wipeAccessed 'MASS EMPTY') 個 chunk" || fail "[wipeAccessed] 整個 chunk 大面積歸零卻沒有 MASS EMPTY"
  nd=$(ls "$E/wipeAccessed.massempty" 2>/dev/null | wc -l)
  [ "${nd:-0}" -gt 0 ] && ok "[wipeAccessed] 救援檔落了 $nd 個(lc-massempty-*.nbt)" || fail "[wipeAccessed] 沒有救援檔 —— 資料真的丟了"
  # 救援檔裡的容器數要 ≥ 磁碟上掉的數(一個都不能少);用 nbtscan 的讀取器解那個檔
  saved=$(python3 - "$E/wipeAccessed.massempty" "$LC_LIB_DIR" <<'PY2'
import sys, os, io, glob
sys.path.insert(0, os.path.join(sys.argv[2], 'py')); import nbtscan
n=0
for f in glob.glob(os.path.join(sys.argv[1], '*.nbt')):
    b=io.BytesIO(open(f,'rb').read()); b.read(1); b.read(int.from_bytes(b.read(2),'big'))
    root=nbtscan.rd(b,10)[1]; ents=root.get('entries')
    if ents: n+=len(ents[1][2])
print(n)
PY2
)
  [ "${saved:-0}" -ge "$(emptied wipeAccessed)" ] && [ "${saved:-0}" -gt 0 ] \
    && ok "[wipeAccessed] 救援檔裡 $saved 個容器 ≥ 磁碟掉的 $(emptied wipeAccessed) 個 —— 一個都不少" \
    || fail "[wipeAccessed] 救援檔裡只有 ${saved:-0} 個,磁碟掉了 $(emptied wipeAccessed) 個"
  [ "$(n wipeAccessed 'SAFE MODE')" -gt 0 ] && ok "[wipeAccessed] 自動降級 SAFE MODE 觸發" || fail "[wipeAccessed] 沒有降級"
;; esac
case " $ROUNDS " in *" wipe-noguard "*)
  [ "$(n wipe-noguard 'FAULT ')" -gt 0 ] && ok "[wipe-noguard] 故障真的注入了" || fail "[wipe-noguard] 故障沒注入"
  [ "$(n wipe-noguard 'SILENT WIPE')" = 0 ] && ok "[wipe-noguard] 關掉守門就完全沒有警報(證明訊號來自守門本身)" || fail "[wipe-noguard] 守門關了還有警報?"
  [ "$(emptied wipe-noguard)" -gt 0 ] \
    && ok "[wipe-noguard] 對照組磁碟真的掉了 $(emptied wipe-noguard) 個容器 —— 這就是沒有守門的下場" \
    || fail "[wipe-noguard] 對照組沒掉東西 ⟹ 這個故障根本沒破壞力,前面的紅不算數"
;; esac
case " $ROUNDS " in *" corruptRaw "*)
  [ "$(n corruptRaw 'FAULT ')" -gt 0 ] && ok "[corruptRaw] 故障真的注入了($(n corruptRaw 'FAULT ') 次)" || fail "[corruptRaw] 故障沒注入"
  if [ "$(n corruptRaw 'BAD RAW')" -gt 0 ] || [ "$(ctrv corruptRaw badRaw)" -gt 0 ]; then
    ok "[corruptRaw] 亮紅:BAD RAW $(n corruptRaw 'BAD RAW') 行、計數器 badRaw=$(ctrv corruptRaw badRaw)"
  else
    fail "[corruptRaw] 改壞了 byte 卻沒有任何 BAD RAW —— 壞資料被靜默吞掉"
  fi
  [ "$(cat "$E/corruptRaw.dumps" 2>/dev/null || echo 0)" -gt 0 ] && ok "[corruptRaw] 壞 bytes 有落檔可救" || warn "[corruptRaw] 沒有 lc-badraw 落檔"
  [ "$(n corruptRaw 'SAFE MODE')" -gt 0 ] && ok "[corruptRaw] 自動降級 SAFE MODE 觸發" || fail "[corruptRaw] 沒有降級"
;; esac

case " $ROUNDS " in *" dropSideCar "*)
  pt=$(ctrv dropSideCar rawPassthrough); em=$(ctrv dropSideCar rawEmit)
  [ "${pt:-0}" -gt 0 ] && ok "[dropSideCar] 直寫真的有跑(rawPassthrough=$pt)" || fail "[dropSideCar] 直寫沒跑,這一回合不算數"
  [ "${em:-0}" -lt "${pt:-1}" ] && ok "[dropSideCar] rawEmit=$em < rawPassthrough=$pt(側車真的被吞了)" || fail "[dropSideCar] 側車沒被吞,故障沒生效"
  [ "$(n dropSideCar 'BAD PASSTHROUGH')" -gt 0 ] \
    && ok "[dropSideCar] 亮紅:BAD PASSTHROUGH $(n dropSideCar 'BAD PASSTHROUGH') 行" \
    || fail "[dropSideCar] 側車被吞卻沒有 BAD PASSTHROUGH —— #261 唯一不會自己爆的失效模式沒被抓到"
  # 指紋:側車被吞 ⟹ 容器在磁碟上「完全沒有 Items 這個鍵」,而不是「空清單」。
  # 這條是 2026-09-08 s3 事故判讀的依據,要用一次可控實驗釘住。
  python3 "$LC_LIB_DIR/py/itemskey.py" "$E/dropSideCar/$reg.mca" > "$E/dropSideCar.keys" 2>&1 || true
  python3 "$LC_LIB_DIR/py/itemskey.py" "$LC_FIXTURES_DIR/$label/$reg.mca" > "$E/input.keys" 2>&1 || true
  nk=$(grep -oE "no_items_key +[0-9]+" "$E/dropSideCar.keys" | grep -oE "[0-9]+$")
  ik=$(grep -oE "no_items_key +[0-9]+" "$E/input.keys" | grep -oE "[0-9]+$")
  [ "${nk:-0}" -gt "${ik:-0}" ] \
    && ok "[dropSideCar] 磁碟指紋 = 缺 Items 鍵(輸入 ${ik:-0} → 輸出 ${nk:-0}),與『空清單』是不同機制" \
    || fail "[dropSideCar] 缺鍵數沒有增加(輸入 ${ik:-0} → 輸出 ${nk:-0})—— 指紋假設不成立"
;; esac

tier_verdict
