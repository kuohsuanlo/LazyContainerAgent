#!/usr/bin/env bash
# 真實 region 素材的取得/普查/清除。素材是正式站的唯讀副本,用完一定要刪(fetch 只讀、絕不寫回)。
#   bash gates/rig/fixtures.sh fetch    從 $LC_FLEET_HOST 複製 $LC_FIXTURES 列的 region + 產普查 census.json
#   bash gates/rig/fixtures.sh clean    刪掉本機所有素材副本
LC_TIER=fixtures
. "$(cd "$(dirname "$0")/.." && pwd)/lib.sh"
cmd="${1:-fetch}"
if [ "$cmd" = "clean" ]; then
  rm -rf "$LC_FIXTURES_DIR"; echo "素材已刪除:$LC_FIXTURES_DIR"; exit 0
fi
mkdir -p "$LC_FIXTURES_DIR"
: > "$LC_FIXTURES_DIR/fixtures.tsv"
# 自備素材模式:LC_FIXTURES_LOCAL=1 時完全不連艦隊,直接用 $LC_FIXTURES_DIR/<label>/<region>.mca。
# 給沒有艦隊 ssh 存取的機器用——自己從任何一個世界複製容器夠多的 region 進去即可
# (容器越多越有力;跑之前先確認那份世界檔是目標版本的)。
LOCAL="${LC_FIXTURES_LOCAL:-0}"
for f in $(fixtures_each); do
  label=$(fx_label "$f"); dim=$(fx_dim "$f"); reg=$(fx_reg "$f")
  remote=$(echo "$LC_FLEET_WORLD_TMPL" | sed "s/%SHARD%/$label/g; s/%DIM%/$dim/g; s/%REG%/$reg/g")
  mkdir -p "$LC_FIXTURES_DIR/$label"
  dst="$LC_FIXTURES_DIR/$label/$reg.mca"
  if [ ! -f "$dst" ]; then
    if [ "$LOCAL" = "1" ]; then
      echo "自備素材模式:缺 $dst —— 請自己把目標版本的 region 檔放到這個路徑(檔名必須是 r.<x>.<z>.mca)"; exit 1
    fi
    echo "== 取素材 $label $dim $reg"
    # 重試三次:scp 對正在被伺服器改寫的大檔偶爾會吐 "protocol error: filename does not match
    # request"(2026-09-09 實測踩到一次)。一次網路抖動不該把一小時的 gate 整輪弄掉。
    ok=0
    for try in 1 2 3; do
      if scp -q "$LC_FLEET_HOST:$remote" "$dst"; then ok=1; break; fi
      echo "   第 $try 次取檔失敗,5 秒後重試"; rm -f "$dst"; sleep 5
    done
    [ "$ok" = 1 ] || { echo "取不到 $remote(重試三次都失敗)"; exit 1; }
  fi
  printf '%s\t%s\t%s\t%s\t%s\n' "$label" "$dim" "$reg" "$(fx_rx "$f")" "$(fx_rz "$f")" >> "$LC_FIXTURES_DIR/fixtures.tsv"
  ls -l "$dst" | awk '{print "   ", $NF, $5, "bytes"}'
done
newest=$(ls -t "$LC_FIXTURES_DIR"/*/*.mca 2>/dev/null | head -1)
if [ -f "$LC_FIXTURES_DIR/census.json" ] && [ "$LC_FIXTURES_DIR/census.json" -nt "$newest" ]; then
  echo "普查已是最新,略過(要重算就刪 $LC_FIXTURES_DIR/census.json)"
else
  python3 "$LC_LIB_DIR/py/census.py" "$LC_FIXTURES_DIR"
fi
