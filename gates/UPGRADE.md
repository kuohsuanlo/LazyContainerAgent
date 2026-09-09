# 換版 runbook(26.2 → 26.3 → …)

給下一個接手的人或 agent。照著做,不要跳步。**每一步都有自己的閘門,前一關沒綠不要進下一關。**

## 心智模型:什麼會失效、什麼不會

| | 會被版本改壞 | 為什麼 |
|---|---|---|
| **agent 程式碼** | ✅ 會 | 對真實 NMS 編譯 + bytecode 織入指定方法。Mojang 改名/改簽章 ⟹ 編不過或找不到注入點 |
| **codec 語意假設** | ✅ 會 | 摘要押在「逐 component partial」「缺 `Slot` 回退 0」「`max_stack_size` 1..99」這些 26.2 實測釘住的行為 |
| **測試工具本身** | ❌ 不會 | bot、裁判外掛、痕跡外掛、單一總判定、凍結對照、形狀指紋、還原工具都是版本無關的基礎建設 |
| **NBT 二進位格式** | ❌ 十年沒變 | 直寫的 named-tag 框架、走訪器規則、`mca_restore.py` 幾乎不用動 |

**所以換版是「重跑」不是「重寫」**:建這套東西花了幾天,重跑一輪約兩小時。

而且它壞的方式是**大聲的**:版本不符會在開機或第一次載箱子時丟 `VerifyError`/`NoSuchMethodError`,
那台起不來,而不是靜默改壞資料。這是刻意換來的安全邊界。

## 危險度排序(哪一項最可能被新版改壞)

1. **存檔直寫**(唯一會「靜默」失效的):三個 hook 全 armed、`attachRaw` 成功、`rawPassthrough` 照加,
   但新版若在存檔鏈中間多一段「用 entrySet 重建 tag」的程式,側車會被丟掉 ⟹ 箱子存成空。
   **G2 的 `NBTUSE` 行 + G4 的 `rawEmit` 對帳就是為了這一格。** shadow 抓不到(探針走自己的 `NbtIo.write`)。
2. **延遲載入核心**:leaf 若新增一個直接摸 `items` 欄位、繞過 `getItems()` 咽喉的方法,會看到空的佔位清單。
   G2 的 `ITEMSREF` 行會顯形。
3. **漏斗摘要**:簽章變 ⟹ 沒 hook(只是變慢,會在開機行少一句);codec 語意漂移 ⟹ G3/G6 會紅。
4. 其餘(走訪器、還原工具、面板整合):格式沒變就不會動。

---

## 步驟

### 0. 準備素材(一次)

```bash
V=26.3                                   # 目標版本
cp gates/versions/26.2.env gates/versions/$V.env
$EDITOR gates/versions/$V.env             # 逐行改:JDK 路徑、classfile major、NMS jar、Paper bundler、
                                          # bot 協定版本、shape 基準檔名、素材清單
                                          # ⚠ LC_SHAPE_BASELINE 這一步先**指回 26.2 的基準**(見第 2 步)

# nms-lib:讓 Paper bundler 解開自己,再把 server jar 與 libraries 攤平成一層
java -jar paper-$V.jar --version
mkdir -p nms-lib
cp versions/$V/paper-$V.jar nms-lib/paper-$V-mojmap.jar          # 檔名要含 mojmap 或 paper-
i=0; find libraries -name '*.jar' | while read j; do cp "$j" "nms-lib/lib-$i-$(basename $j)"; i=$((i+1)); done

# 測試伺服器一定要重建(沿用舊 rig = 拿舊核心跑新版測試,而且每一關照樣全綠)
bash gates/rig/make_rig.sh --fresh
```

**ASM**:`pom.xml` 的 ASM 版本要能解析目標版的 classfile。Java 25 = classfile 69 需要 ASM ≥ 9.7;
再往上每升一個 Java 大版就要再確認一次。版本不夠時 G1 會在 template splice 時炸(`Unsupported class file major version`)。

- `LC_JAVA_MAJOR` / `LC_CLASSFILE_MAJOR`:看核心 jar 內 `version.json` 的 `java_version`(Java 25 = classfile 69,26 = 70…)。
- `LC_BOT_PROTOCOL`:`minecraft-protocol` 支援到的最高版本(通常落後正式版一兩個小版);rig 掛 ViaVersion + **ViaBackwards** 翻譯。
  兩個都要,只裝 ViaVersion 連不上。
- `LC_FIXTURES`:艦隊上真的存在的大倉庫 region。想換素材,用 `tools/scan_containers.py` 找容器最密的 region。

```bash
bash gates/run.sh --version $V --tiers G0     # 只驗環境。這關紅就不要往下走
```

### 1. template 重新編譯 —— 編譯器就是第一道 diff

```bash
bash build.sh
```

編不過的每一行都是「Mojang 改了什麼」的清單。常見型態與對策:

| 症狀 | 意思 | 怎麼修 |
|---|---|---|
| `cannot find symbol: class ResourceLocation` | 型別改名(26.x 把它改成 `Identifier`) | 跟著改;`FINDINGS.md` 有慣例 |
| `method loadAllItems ... cannot be applied` | `ValueInput`/`ValueOutput` 家族換形狀 | 對照新簽章改 template,並同步 `LazyContainerTransformer` 的 descriptor 常數 |
| `LazyContainerTemplate.class` major 不對 | 用錯 JDK | `LC_JAVA_HOME` / `build.sh` 的 `JAVA_HOME` |
| 政策閘門「違規 N 項」 | 改 template 時碰壞了鎖政策 | 讀 `tools-out/policy.log`;規則見 README「跨執行緒鐵律」 |

改完 template 之後,**splice 數字會變**(transformer 是按 `lazycontainer$` 前綴掃成員,不是寫死清單)。
數字用這行取得,更新到 `gates/versions/$V.env` 的 `LC_SPLICE_FIELDS/METHODS`,以及 TESTING.md 裡引用的那行:

```bash
javap -p -cp template-out io.github.kuohsuanlo.lazycontainer.LazyContainerTemplate \
  | grep -c 'lazycontainer[$][a-zA-Z]*;'      # 欄位數
javap -p -cp template-out io.github.kuohsuanlo.lazycontainer.LazyContainerTemplate \
  | grep -c 'lazycontainer[$][a-zA-Z]*('      # 方法數
```

**兩支測試外掛也是對 NMS 編的**(`gates/plugins/lccompare` 用 NMS 的 codec 當裁判、`lcops` 讀 spliced 欄位),
所以它們也可能因為改名編不過。先單獨編一次,不要等到 G5/G7 才發現:

```bash
bash gates/plugins/build.sh
```

**版本號**:`pom.xml` 的 `<version>` 與 README 裡的版本敘述要一起更新,否則 G8 會紅。

### 2. 注入形狀:先產指紋,逐行審查,再收進基準

**順序很重要:先拿新版 NMS 對「上一版的基準」比,看 diff;確認每一行都安全之後才收成新基準。**
直接把新版指紋存成新基準再跑,是拿自己比自己,必然 PASS——等於把 G2 整關作廢。

```bash
# 1. 先用上一版的基準比(LC_SHAPE_BASELINE 這時仍指向 26.2 的檔)
bash gates/run.sh --version $V --tiers G2      # 會紅,而且會印出 +/- 的每一行

# 2. 逐行看過(照下面的審查重點),確認安全後才產生新基準
java -cp tools-out:$(cat tools-out/asm.cp) InjectionShapeCheck nms-lib/<新 NMS>.jar > gates/shape/$V.txt

# 3. 把 gates/versions/$V.env 的 LC_SHAPE_BASELINE 改指向 $V.txt,再跑一次確認綠
bash gates/run.sh --version $V --tiers G2
```

**審查重點(照這個順序看):**

1. `MISSING-METHOD` / `MISSING-CLASS`:注入目標不見了。先回第 1 步把 template 與 transformer 對齊。
2. `NBTUSE <存檔視窗呼叫者> …`:26.2 的兩個呼叫者都是 `(none)` —— 它們完全不碰 NBT 方法,
   所以直寫掛上去的側車一路活到 `write()`。**如果新版這裡冒出 `CompoundTag.putAll` / `entrySet` / `merge` 之類,
   直接視為「直寫不能開」**,先用 `-Dlazycontainer.passthrough=false` 出貨,再研究。
3. `CALLER-BE-NBT`:存檔視窗多了新呼叫者 ⟹ 那個呼叫者會在視窗內拿到「沒有 Items 樹」的 compound。要確認它不讀 Items。
4. `ITEMSREF`:leaf 新增了直接摸 `items` 欄位的方法 ⟹ 繞過 `getItems()` 咽喉。要在 transformer 補 guard。
5. `HOPPERCALL` / `CHCALL`:漏斗 hook 與 `ContainerHelper` 呼叫點是否還是同一組。
6. `FIELD ... TagValueInput#input`:template 直接讀這個欄位,名稱/型別/存取權變了就要跟著改。

確認每一行都安全之後才把 `gates/shape/$V.txt` 收進 git,並在 `gates/versions/$V.env` 指過去。

### 3. 語意:對新版真 codec 跑差分

```bash
bash gates/run.sh --version $V --tiers G3
```

`SummaryDifferentialTest` / `ComponentPartialSemanticsTest` 紅 = **新版 codec 的語意變了**,不是測試壞了。
處理原則:**摘要可以棄答,不可以答錯**。看不懂的新語意一律讓摘要 `GIVEUP`(代價只是那類容器少省一次)。

### 4. 磁碟輸出:四模式 + 逐格裁判

```bash
bash gates/run.sh --version $V --tiers G4,G5 --keep-fixtures
```

這兩關是**直寫能不能開**的裁決點:

- **`rawEmit` 追不上 `rawPassthrough`** ⟹ 側車被丟了。**立刻停,不要出貨直寫**;
  用 `-Dlazycontainer.passthrough=false` 先出一版(延遲載入與摘要的好處照拿,只放掉存檔那一段)。
- **A vs B 結構有差** ⟹ 直寫寫出來的東西跟舊路徑不同。停。
- **V(原版)vs 輸入有差** ⟹ 是新版核心自己會正規化某些欄位,不是 agent 的問題;先釐清再談其他模式。

### 5. 執行中的語意與互動

```bash
bash gates/run.sh --version $V --tiers G6,G7 --keep-fixtures
```

G6 沒有任何摘要被問到(`summaryFull+summarySkip=0`)= 這關等於沒測到,要查為什麼漏斗沒跑。
G7 的 FINAL VERDICT 會把每一條不成立的斷言列出來,照著看即可。

### 6. 出貨與上線順序

```bash
bash gates/run.sh --version $V            # 全綠 = 可以出貨
bash gates/rig/fixtures.sh clean          # 素材是正式站的副本,一定要刪
```

上線順序(**鐵則:jar 不代鋪**,交付路徑 + md5 + commit 給服主自己鋪):

1. **一台金絲雀,開 `-Dlazycontainer.passthrough.shadow=true`,跑一天。** 看 `ptShadowMismatch` 必須是 0。
2. 同一台拿掉觀測旗標,跑一天。看 `badRaw=0`、`rawEmit` 跟得上 `rawPassthrough`、沒有 `BAD PASSTHROUGH`。
3. 再依「存檔尖峰最痛」的排序分批鋪(從 `.106` 統一操作;每台先備份原 jar)。
4. 全程盯面板的區塊救援偵測器(`BAD RAW` 會自動建案)。

**回滾**:把 `-javaagent:` 與那幾個 `-D` 拿掉重啟,立刻回 100% 原版,**不需要任何資料遷移**——磁碟格式從頭到尾沒被改過。

### 正式站跑的是 fork 的話

**原廠綠不等於 fork 綠。** 注入是織進核心內部類別的,fork 動過那些類別就會壞。
換版時要對 fork 再跑一次 G2 與 G4–G7(做法見 `README.md` 的「對 fork 核心跑」)。

最省力的判斷法:**拿 fork 的 NMS 對原廠的形狀基準跑 G2**。
0 差異就代表 fork 沒碰任何注入假設,原廠那輪的結論可以延伸過來;有差異的每一行就是要重新驗的地方。

---

## 紅燈怎麼分:哪些必須停、哪些可以繼續

| 紅在哪 | 意思 | 怎麼辦 |
|---|---|---|
| G0 | 環境不對 | **停**。後面每一關的紅綠都不能信 |
| G1 template 編不過 | Mojang 改名/改簽章 | 照編譯器的清單改,這是必經之路 |
| G1 政策閘門 | 改 template 時碰壞了鎖紀律 | **停**,讀 `tools-out/policy.log` |
| G2 有 `MISSING-METHOD` | 注入目標不見了 | 回第 1 步對齊 |
| G2 `NBTUSE` 冒出 NBT 方法 | 存檔鏈會重建 tag | **直寫不能開**,用 `-Dlazycontainer.passthrough=false` 出貨 |
| G2 `ITEMSREF` 新增 | leaf 多了繞過咽喉的方法 | **停**,要在 transformer 補 guard |
| G3 差分測試 | 新版 codec 語意變了 | 改摘要,原則是「可以棄答不可以答錯」 |
| G4 `rawEmit` 追不上 | 側車被丟掉 | **停**,直寫不能開 |
| G4 A vs B 結構有差 | 直寫與舊路徑輸出不同 | **停** |
| G4 V(原版)vs 輸入有差 | 新版核心自己會正規化 | 不是 agent 的問題,記錄下來即可(V 的比較本來就只報告不判紅) |
| G3/G4 `silentWipe` > 0 或 log 有 `SILENT WIPE` | 有容器在沒人碰的情況下被寫成空的 | **停,而且是全站等級的停**。這是資料事故不是效能問題;先看警報行的座標,拿當日備份跑 `tools/container_loss_watch.py` 確認範圍 |
| log 有 `MASS EMPTY` | 同一次存檔裡整個 chunk 大面積「碰過之後歸零」;原始內容已落成 `lc-massempty-*.nbt` | **停**。先把那個檔備份走(它是唯一的原始內容),再看是不是玩家真的在搬家(對照 CoreProtect / 機械事件);確定是事故就用檔案還原,不要去翻備份 |
| log 有 `SAFE MODE` | agent 自己偵測到異常,已經就地關掉直寫 | 伺服器不用重啟就已經在安全路徑上跑,但**這一版不得繼續出貨**;把該行回報並回到 G2/G4 找原因 |
| G5 逐格有差 | 遊戲看到的物品變了 | **停**,這是最嚴重的一種 |
| G6 `shadowMismatch` > 0 | 寫回的東西與原版重編碼結構不同**且解碼後也不同** | **停**;若只是寫法不同會被歸到 `benignEncoding` 不會紅 |
| G7 某條斷言 | 多半是判定範圍問題,不是 agent | 先看是不是「世界自己的機械在動」——但**要有事件證據**才算解釋,不能用猜的 |

## 只想先上線、不想等全綠時

可接受的最小組合是 **G0+G1+G2+G3+G4+G5**,並且**關掉直寫**出貨:

```bash
bash gates/run.sh --version $V --tiers G0,G1,G2,G3,G4,G5 --keep-fixtures
```

注意 G4 預設仍會跑 A(直寫)模式——**那是刻意的**:就算你打算關掉直寫出貨,也要先知道直寫在新版上是好是壞。
真的只想跑部分模式時用 `LC_G4_MODES="V B"`,但這樣 G5 會找不到 A 的輸出而整關失敗(G5 比的就是 A)。

出貨旗標:

```
-javaagent:LazyContainerAgent.jar -Dlazycontainer.passthrough=false -Dlazycontainer.shadow=true
```

這樣拿得到延遲載入(載入尖峰,最大的一塊)與漏斗摘要,放掉存檔直寫那一段;
G6/G7 補跑完、確認 `rawEmit` 對得上之後再開直寫。
