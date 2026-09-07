#!/usr/bin/env bash
# 建立(或補齊)出貨 gate 用的測試伺服器。冪等:已存在就只更新設定與外掛。
#   bash gates/rig/make_rig.sh [--fresh]
# 需要 $LC_PAPER_BUNDLER(目標版本的 Paper bundler jar)與 $LC_VIA_DIR(ViaVersion + ViaBackwards,bot 用)。
LC_TIER=make_rig
. "$(cd "$(dirname "$0")/.." && pwd)/lib.sh"
[ "${1:-}" = "--fresh" ] && rm -rf "$LC_RIG"
mkdir -p "$LC_RIG/plugins"
[ -f "$LC_PAPER_BUNDLER" ] || { echo "缺 Paper bundler jar:$LC_PAPER_BUNDLER"; exit 1; }
cp -n "$LC_PAPER_BUNDLER" "$LC_RIG/server.jar"
# ⚠ cp -n 不覆蓋:沿用舊 rig 會讓 G4–G7 拿**上一版核心**跑而每一關照樣全綠——
# 這正是「靜默失效」。所以一定要驗 rig 裡那支 jar 的版本。
rig_ver=$(unzip -p "$LC_RIG/server.jar" version.json 2>/dev/null | grep -oE '"id"[^,]*' | cut -d'"' -f4)
if [ "$rig_ver" != "$LC_MC_VERSION" ]; then
  echo "rig 內的核心是 $rig_ver,不是 $LC_MC_VERSION —— 換版時必須重建 rig。"
  echo "  bash gates/rig/make_rig.sh --fresh   (會刪掉整個 $LC_RIG 重來)"
  exit 1
fi
echo "eula=true" > "$LC_RIG/eula.txt"
sed "s/%PORT%/$LC_RIG_PORT/" "$LC_LIB_DIR/rig/server.properties.tmpl" > "$LC_RIG/server.properties"
for j in "$LC_VIA_DIR"/ViaVersion*.jar "$LC_VIA_DIR"/ViaBackwards*.jar; do
  [ -f "$j" ] && cp -n "$j" "$LC_RIG/plugins/"
done
# 第一次啟動:解壓 libraries、生成平坦世界,然後停機
if [ ! -d "$LC_RIG/world" ]; then
  echo "== 首次啟動(生成世界)=="
  rig_kill
  rig_boot "$LC_RIG/first-boot.log" || exit 1
  rig_send "stop" 2; sleep 30; rig_kill
fi
mkdir -p "$LC_RIG/world/dimensions/minecraft/overworld/region" \
         "$LC_RIG/world/dimensions/minecraft/the_end/region" \
         "$LC_RIG/world/dimensions/minecraft/the_nether/region"
echo "rig 就緒:$LC_RIG"
ls "$LC_RIG" | head -20
