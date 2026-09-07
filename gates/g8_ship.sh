#!/usr/bin/env bash
# G8 出貨物件:版本字串、文件、交付夾。鐵則是 jar 不代鋪——這一關只把「服主自己鋪要用的東西」備齊。
LC_TIER=G8
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
cd "$LC_REPO" || exit 1
ver=$(grep -m1 '<version>' pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
JAR=target/LazyContainerAgent.jar
md5=$(md5sum "$JAR" | cut -d' ' -f1)
echo "== G8 出貨 =="
[ -n "$ver" ] && ok "版本 $ver" || fail "pom 讀不到版本"
git -C "$LC_REPO" diff --quiet && git -C "$LC_REPO" diff --cached --quiet && ok "工作區乾淨(出貨的就是 commit 的)" || warn "工作區有未 commit 的改動"
commit=$(git -C "$LC_REPO" rev-parse --short HEAD 2>/dev/null); ok "commit $commit"
grep -q "$LC_MC_VERSION" README.md && ok "README 標的版本 $LC_MC_VERSION" || fail "README 沒提到目標版本 $LC_MC_VERSION"
# splice 數字:transformer 是按 lazycontainer$ 前綴掃 template 成員、不是寫死清單,
# 所以「改了 template 卻沒看到數字變」= template 沒被重新編進 jar(build.sh 第 2、3 步)。
sp=$(grep -ho "spliced [0-9]* fields + [0-9]* methods" "$LC_OUT"/g4/*.boot "$LC_OUT"/g7/boot.txt 2>/dev/null | sort -u | head -1)
if [ -n "$sp" ]; then
  want="spliced $LC_SPLICE_FIELDS fields + $LC_SPLICE_METHODS methods"
  [ "$sp" = "$want" ] && ok "splice 數字 = 設定值($sp)" || fail "splice 數字是「$sp」,設定寫的是「$want」(改了 template 就要一起更新 gates/versions/$LC_MC_VERSION.env 與文件)"
else
  warn "本輪沒有跑到會開機的關卡,無法核對 splice 數字"
fi
[ -f "docs/test-reports/$LC_MC_VERSION.md" ] && ok "有 $LC_MC_VERSION 測試報告" || warn "缺 docs/test-reports/$LC_MC_VERSION.md"
D="$LC_GATE_HOME/dist-$(date +%Y%m%d)-lazycontainer-$ver"
mkdir -p "$D"; cp "$JAR" "$D/"; cp tools/mca_restore.py "$D/"
{
  echo "LazyContainerAgent $ver(Minecraft $LC_MC_VERSION)"
  echo "commit  : $commit"
  echo "jar md5 : $md5"
  echo "gate    : $LC_OUT"
  echo
  echo "檔案:"
  echo "  LazyContainerAgent.jar   放在節點看得到的位置,-javaagent: 指過去(不要丟 plugins/)"
  echo "  mca_restore.py           出事時的離線還原工具(verify --deep / restore-chunk / restore-items)"
  echo
  echo "第一次上線建議:先 -Dlazycontainer.passthrough.shadow=true 跑一天,ptShadowMismatch 必須是 0,再拿掉。"
} > "$D/README.txt"
ok "交付夾 $D"
ls -l "$D" | sed 's/^/     /'
echo "jar md5 $md5" | tee -a "$LC_RESULT"
tier_verdict
