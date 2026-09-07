#!/usr/bin/env bash
# G1 建置閘門:build.sh(mvn + template 對真 NMS 編譯 + bytecode 政策閘門)。
# 這一關保證「編得出來、注入形狀沒違反鎖政策、template 的 classfile 版本對得上目標核心」。
LC_TIER=G1
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
cd "$LC_REPO" || exit 1
echo "== G1 建置 =="
t0=$(date +%s)
if bash build.sh > "$LC_OUT/build.log" 2>&1; then ok "build.sh 成功"; else fail "build.sh 失敗(見 $LC_OUT/build.log)"; tail -20 "$LC_OUT/build.log"; fi
JAR=target/LazyContainerAgent.jar
if [ -f "$JAR" ] && [ "$(stat -c %Y "$JAR")" -ge "$t0" ]; then ok "jar 是本輪產出"; else fail "jar 不存在或不是本輪產出"; fi
unzip -p "$JAR" META-INF/MANIFEST.MF 2>/dev/null | grep -q 'Premain-Class' && ok "manifest 有 Premain-Class" || fail "manifest 缺 Premain-Class"
ver=$(unzip -p "$JAR" META-INF/MANIFEST.MF 2>/dev/null | grep -i 'Implementation-Version' | tr -d '\r' | awk '{print $2}')
pomver=$(grep -m1 '<version>' pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
[ -n "$ver" ] && [ "$ver" = "$pomver" ] && ok "版本字串一致 jar=$ver pom=$pomver" || fail "版本字串不一致 jar=$ver pom=$pomver"
nasm=$(unzip -l "$JAR" | grep -c 'io/github/kuohsuanlo/lazycontainer/asm/')
[ "${nasm:-0}" -gt 100 ] && ok "ASM 已 relocate 進 jar($nasm 個類別)" || fail "jar 內找不到 relocate 後的 ASM($nasm)"
tmpl=$(mktemp); unzip -p "$JAR" io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.class > "$tmpl" 2>/dev/null
cm=$(od -An -tu1 -j7 -N1 "$tmpl" | tr -d ' '); rm -f "$tmpl"
[ "$cm" = "$LC_CLASSFILE_MAJOR" ] && ok "template classfile major = $cm(與目標核心相同)" || fail "template classfile major = $cm,應為 $LC_CLASSFILE_MAJOR"
grep -q "違規 0 項" tools-out/policy.log 2>/dev/null && ok "bytecode 政策閘門:違規 0 項" || fail "bytecode 政策閘門未通過"
grep -oE "R[0-9]+[^ ]*|檢查 [0-9]+ 項" tools-out/policy.log 2>/dev/null | tail -3 | sed 's/^/     /'
md5sum "$JAR" | tee -a "$LC_RESULT"
tier_verdict
