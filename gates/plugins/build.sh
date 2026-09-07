#!/usr/bin/env bash
# 編譯兩支測試輔助外掛(對目標版本的 NMS 編譯,mojang mappings namespace)。
#   LcCompare —— 裁判:在真伺服器裡把兩份 mca 逐容器逐格 ItemStack.matches
#   LcOps     —— 痕跡:伺服器端事件記錄 + 外掛 API 操作 + 指令執行(回饋可驗)
LC_TIER=plugins
. "$(cd "$(dirname "$0")/.." && pwd)/lib.sh"
NMSCP="$(ls "$LC_REPO"/nms-lib/*.jar | tr '\n' ':')"
for p in lccompare lcops; do
  d="$LC_LIB_DIR/plugins/$p"
  rm -rf "$d/out"; mkdir -p "$d/out"
  javac -proc:none -nowarn -cp "$NMSCP" -d "$d/out" $(find "$d/src" -name '*.java') || { echo "編譯失敗 $p"; exit 1; }
  cp "$d/res/plugin.yml" "$d/out/"
  jarname=$(grep '^name:' "$d/res/plugin.yml" | awk '{print $2}')
  ( cd "$d/out" && jar cf "$d/$jarname.jar" . )
  echo "  $d/$jarname.jar  $(md5sum "$d/$jarname.jar" | cut -c1-8)"
done
