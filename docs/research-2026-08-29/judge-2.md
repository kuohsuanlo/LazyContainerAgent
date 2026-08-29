# 裁決 2:do-relax-summary-first

## 一、收益優先的算式:凍結秒數全部住在「摘要不肯答」那一格

**這是本次裁決的支點,而且可以從 bytecode 直接證明。**

hook 的組譯在 `/home/logocat/Server/claude-sandbox/workspace/LazyContainerAgent/src/main/java/io/github/kuohsuanlo/lazycontainer/LazyContainerTransformer.java:279-287`:
```
ALOAD 0 → INVOKESTATIC lazycontainer$containerFullState → DUP → IFLT unknown → IRETURN
```
r ∈ {0,1} **兩者都 IRETURN**,原方法一行都不跑。而 `isFullContainer`(`/home/logocat/Server/claude-sandbox/reference-minecraft-nms/4-folia/net/minecraft/world/level/block/entity/HopperBlockEntity.java:477-488`)裡唯一的解碼點是 `:481` 的 `container.getItem(slot)`。

⟹ **s45 那條 16.19 秒的鏈(`isFullContainer → CompoundContainer.getItem → getItems guard → ensure`)只在 r == −1 時可達。** 100% 的該鏈凍結秒數住在 `fullUnknownDirty + fullGaveUp` 這兩個桶裡,一秒都不在 r=0 或 r=1。

這一句同時判了兩件事:
- **放寬摘要**把 r=−1 搬去 r=1 ⟹ `ejectItems` 直接 return false ⟹ **零解碼**。
- **逐格解碼**只能把 r=−1 那一次的「解 27 格」變成「解 1 格」,而且 r=0 完全救不到。

## 二、逐格在推入路徑上的收益是 0,而且 design 鏡頭把行號指錯了一行

`addItem`(`4-folia/HopperBlockEntity.java:555-570`)逐格呼 `tryMoveInItem`,而 `tryMoveInItem` 的**第一個敘述**就是 `:589 ItemStack current = container.getItem(slot);` → `getItems()` guard → 整箱 ensure。

design 鏡頭主張「`:592 container.isEmpty()` ⟹ 今天必定整箱解碼」——**差一行**。`:592` 在 `:589` 之後,箱子那時已經物化,`isEmpty()` 只是掃一份已在記憶體的清單。所以:
- 修 `:592`(或 paper 鏡頭建議的 `(container instanceof HopperBlockEntity) && container.isEmpty()`,由 `:615` 的短路可證等價)**省的是 CPU,不是解碼**。真實但小,別當解碼優化賣。
- 逐格要在推入路徑真的賺到,必須同時做:`:589` 逐格 + `:592` 接 emptyState + 混合存檔。而 `:610 current.grow(count)`、`:285 movedItem.setCount(...)` 都是**不經 setItem 的就地改動**,一碰即髒 ⟹ 混合存檔 ⟹ 現行「pending 原封回寫 / 已物化正常編碼」兩分支的逐位元組鐵則被拆成一棵樹。**在 109 台上這是重寫不是優化。**

逐格唯一乾淨的窄縫是「push 被外掛取消」:`hopperPush:251 callPushMoveEvent` → 取消 → `:253 return false`,`addItem:258` 沒跑,目標從沒被碰。這個比例**現在沒有任何人量過**。

## 三、放寬摘要的收益有生產級證據,逐格沒有

- **s3 商場實掃**:12,754 個全滿箱,**76.3%** 因舊規則無法證明滿(引用點:`template/.../LazyContainerTemplate.java:693`、`tests/.../SummaryDifferentialTest.java:276-277`)。
- **WidenFuzz 20 萬容器**:`fullUnknownDirty` 23,746 → 1,256(−94.7%),與 vanilla 衝突 0。
- 而 s45 的鏈是 `CompoundContainer`(雙箱)——正是商場/倉庫那種「滿箱具名商品」族群。**放寬打的就是造成 s45 的那群箱子。**

逐格能額外賺到的上限 ≈ 放寬後的殘量(fuzz 顯示 5.3%)+ 那個沒量過的「取消 push」窗口。**收益優先的排序沒有懸念。**

## 四、但是——放寬已經寫好了,而且現在是錯的(10 個實測 FALSE-FULL)

我剛剛用 repo 內既有的 `tests/.../ProbeMain.java`(它本身就內建 FALSE-FULL 偵測器)對真實 26.2 codec 跑出來:

```
P1  !minecraft:max_stack_size = Int(1),  count=5   vanilla{max=64 full=false}  summary{tri=1}  FALSE-FULL
P3  !minecraft:max_stack_size = "",      count=5   vanilla{max=64 full=false}  summary{tri=1}  FALSE-FULL
P4  !minecraft:max_stack_size = [],      count=5   vanilla{max=64 full=false}  summary{tri=1}  FALSE-FULL
P5  !minecraft:max_stack_size = Byte(0), count=5   vanilla{max=64 full=false}  summary{tri=1}  FALSE-FULL
P6  max_stack_size(無命名空間) = 99,     count=64  vanilla{max=99 full=false}  summary{tri=1}  FALSE-FULL
P7  :max_stack_size = 99,                count=64  vanilla{max=99 full=false}  summary{tri=1}  FALSE-FULL
P10 mc:max=16 + max=99(重複 key),      count=16  vanilla{max=99 full=false}  summary{tri=1}  FALSE-FULL
P15 !mc:max(Int1) + 壞 lore,            count=5   vanilla{max=64 full=false}  summary{tri=1}  FALSE-FULL
Q6  max(無命名空間)=99 + 壞 lore,       count=64  vanilla{max=99 full=false}  summary{tri=1}  FALSE-FULL
Q8  !mc:max=Int(1),                      count=63  vanilla{max=64 full=false}  summary{tri=1}  FALSE-FULL
```
(另有 P8/P9/P12/Q5 四個 false-not-full,方向安全,只是少省。)

**兩個根因,都在 `LazyContainerTemplate.java:696-700` 這 5 行:**

1. **移除記號判定不看值。** 現行寫 `boolean removal = comps.contains("!minecraft:max_stack_size");` 然後 `max = 1`。但 vanilla 的 `PatchKey.valueCodec()` 對 removed key 是 `Codec.EMPTY.codec()`(`4-folia/net/minecraft/core/component/DataComponentPatch.java:366-368`),**只有值是 CompoundTag 才解得過**——實測 P2 `{}` → max=1 ✔、Q1 `{junk:7}` → max=1 ✔;而 Int/String/List/Byte 一律讓整個 PatchKey entry 報錯,被 `DispatchedMapCodec` 的 partial 丟掉,max 維持物品原廠值。正確規則是:**removal ⟺ 值 instanceof CompoundTag**。

2. **沒有命名空間正規化。** 現行是對 `"minecraft:max_stack_size"` 做字串全等。但 vanilla 走 `Identifier.tryParse(string)`(`DataComponentPatch.java:348`),所以裸 `max_stack_size`(P6)與 `:max_stack_size`(P7)**都是有效 key**。兩種寫法同時存在時(P10、Q5)實測**兩次都是無命名空間那顆勝出**——`DispatchedMapCodec` 是 `putIfAbsent`,勝負由 NbtOps map 迭代序決定,外部不可複現 ⟹ **命中 ≥2 次必須棄權**。

**後果**:假「全滿」⟹ `isFullContainer` 回 true ⟹ `ejectItems` return false ⟹ 那台漏斗**永遠不再往該箱子送東西**;而箱子正因為沒人碰所以永遠 pending、永遠不物化,錯誤自我維持。資料不會掉,但產線靜悄悄卡死,沒有 log、沒有例外。這正是專案「答滿需完整證明」不對稱鐵律要防的形狀,現在被違反了。

**可達性誠實說**:vanilla 自己的編碼器永遠寫正規 `minecraft:` 形式、removal 一律寫空 compound,所以原版產生的資料碰不到。可達來源是 `/data merge block`、schematic/MCA 匯入、以及手工組 NBT 的外掛。

## 五、真正嚇人的不是這個 bug,是閘門

- `./test.sh` 現在 **48/48 全綠**(7 containers、48 tests、0 failed、5836 ms),而 `ProbeMain` / `ProbeFuzz` **不在 `test.sh` 的 `--select-class` 清單裡**(`test.sh:41-59`)。**綠燈是假綠。**
- `SHADOW` 預設 **false**(`LazyContainerRuntime.java:22` 用 `Boolean.getBoolean`),`SUMMARY` 預設 **true**(:47)。唯一能攔下這 10 個假滿的安全網 `lazycontainer$verifyFull`(`template:523-538`,不一致就 `onSummaryMismatch` 並改答 −1)**預設是關的**。不開 shadow 出貨 = 盲射。
- `build.sh:34-42` 只驗 manifest 與 jar 內容,不驗改寫結果。jar 已經在 `target/` 躺著等人鋪。

另外 `logs/` 裡 `EnsureRaceTest` 的紅綠現在**分不出來**:`bad=1943/1950/1974` 那種明顯是負控(刻意壞掉的排序),但 `rounds=2000 raced=13 bad=1` 有 6 筆混在裡面,值班的人無法從 log 判斷哪一筆是負控、哪一筆是真紅。log 行必須帶 build/variant 標記。

## 六、量測幾乎免費(這點修正了幾個鏡頭的悲觀)

`ATTRIBUTION` 預設 **true**(`LazyContainerRuntime.java:143`),`stats()`(:449-480)已經吐 `fullQ=證明滿/證明不滿/佔滿但證不了/整份放棄`,而且吐 **`decodeSpikeMs`**——那就是「region 凍結秒數」本身(≥100ms 桶的總耗時)。它有兩個輸出點:verbose daemon(:499)**以及 `LazyContainerAgentMain.java:46-48` 的 shutdown hook**。艦隊天天被看門貓重啟 ⟹ **可能已經每次重啟就吐一行,不需要改任何 JavaFlag**。但要先在一台上人工驗證拿得到,因為 shutdown hook 裡的 `System.out.println` 會跟 Log4j 關閉競態,可能根本進不了 `latest.log`。

## 七、判決

**do-relax-summary-first。** 理由是收益算式(§1–§3),不是保守。而放寬**不是新工作**,是把已經落地但錯了的 27 行修對、補上它缺的閘門、再走 shadow → 量測 → 放大。

逐格解碼**不是否決,是延後**:等真實 `fullQ` 出來,若「佔滿但證不了 + 整份放棄」在放寬後仍佔 r=−1 查詢的顯著比例,再回頭評估;屆時也必須先量「被取消的 push」比例,因為那是逐格唯一乾淨的窄縫。

**紅線兩條(永久)**:
1. 不得把 lazify 擴到 `HopperBlockEntity`——`getFullState` 直讀 `hopper.items` 欄位(`4-folia/HopperBlockEntity.java:163`),繞過 `getItems()`,會讀到未填清單判成 HOPPER_EMPTY,東西靜默卡死在漏斗裡。
2. 改 `ensure()` 時要重驗 Folia 的 `IGNORE_TILE_UPDATES` ThreadLocal 窗口(`4-folia:601-603`):`setItem` 在該旗標為 true 時呼叫,我們的 guard 就在窗口內,任何新增的 `setChanged()` 會被靜默吞掉。

## 閘門
- G1 假滿零容忍(最高優先):把 tests/io/github/kuohsuanlo/lazycontainer/ProbeMain.java 納入 test.sh 的 --select-class,並改成 JUnit 斷言 assertEquals(0, falseFullCount) —— 目前實測 10 行 FALSE-FULL(P1/P3/P4/P5/P6/P7/P10/P15/Q6/Q8),必須全部歸零才准出貨。false-not-full(P8/P9/P12/Q5)允許存在,只記錄不擋。
- G2 新增 SummaryWidenSafetyTest#removalOnlyWhenValueIsCompound:對 !minecraft:max_stack_size 的值分別為 IntTag/StringTag/ListTag/ByteTag,斷言 lazycontainer$computeSummary 的 tri != 1;並以 ContainerHelper.loadAllItems 實解對照,斷言 vanilla 的 count<max 為真。正確規則:removal 僅當值 instanceof CompoundTag 才成立(P2 {} 與 Q1 {junk:7} 必須仍判 max=1)。
- G3 新增 SummaryWidenSafetyTest#namespaceNormalizedViaIdentifier:key 為 max_stack_size / :max_stack_size / !max_stack_size 三種形式時,斷言「摘要要嘛棄答、要嘛與 vanilla 滿判定一致」,禁止出現 tri==1 而 vanilla full==false。實作必須用 Identifier.tryParse 正規化,不得用字串全等。
- G4 新增 SummaryWidenSafetyTest#duplicateMaxStackSizeKeyBails:minecraft:max_stack_size 與 max_stack_size(或 :max_stack_size)同時存在時,斷言 computeSummary 回 LAZYCONTAINER$SUMMARY_GIVEUP 或 tri != 1。理由:實測 P10/Q5 兩次都是無命名空間那顆勝出,而勝負由 NbtOps map 迭代序 + putIfAbsent 決定,外部不可複現。
- G5 SummaryDifferentialTest 的隨機 component key 產生器擴到四族(無命名空間 / : 前綴 / ! 前綴且值非 compound / 重複 key),輪數 ≥ 200,000,斷言 falseFull == 0,並維持既有斷言「摘要只要開口回答就必須與 vanilla 一致」。
- G6 EnsureRaceTest 全綠:badRounds == 0 且 pendingStaysTrueUntilListIsComplete 綠。同時 [EnsureRaceTest] 這行 log 必須帶 build/variant 識別字串 —— 現行 logs/ 裡 bad=1943(負控)與 bad=1(低核心)混在一起,值班無法判讀。
- G7 build.sh 收尾必須跑 bytecode 政策檢查(靜態分析鏡頭的 LockPolicyCheck,原型在 scratchpad,需搬進 repo 的 tools/):斷言 base splice 6 fields + 18 methods、13 個 leaf 入口 guard 到位、leaf 內無殘留未 redirect 的 ContainerHelper.load/saveAllItems、HopperBlockEntity 兩個 hook 在方法入口。build.sh:34-42 目前只驗 manifest,不驗改寫結果。
- G8 出貨的 jar 必須是跑過 G1–G7 的那一顆:test.sh 尾端印出 target/LazyContainerAgent.jar 的 md5,交付時附同一個 md5。現行 target/LazyContainerAgent.jar(md5 0eaa92b8815c36fa1f0fbcd4c2567542)帶著 10 個假滿,不得鋪。
- G9 生產第一台開 shadow:-Dlazycontainer.shadow=true -Dlazycontainer.verbose=true,跑滿一個完整載入→卸載週期,斷言 summaryMismatch == 0 且 shadowMismatch == 0。注意 shadow 的 verifyFull 會強制解碼,這一台量不到效益,只驗正確性。JavaFlag 屬正式分流,依鐵則須先報服主。
- G10 fullQ 取得性先驗:在一台上人工重啟一次,確認 latest.log 出現 [LazyContainer] shutdown stats: 且含 fullQ= 與 decodeSpikeMs=。拿不到就不要靠它做任何決策(shutdown hook 的 System.out 會與 Log4j 關閉競態)。
- G11 效益驗收在第二台(shadow 關):上線前後各取一次 fullQ 與 decodeSpikeMs,要求 fullQ 第三欄(佔滿但證不了)顯著下降、decodeSpikeMs 每小時值下降,且 summaryMismatch 維持 0。decodeSpikeMs 就是 region 凍結秒數本身,是本案唯一的 KPI。
- G12 逐格解碼的開工前置閘門(現在不做,但先寫死):必須先有一輪真實 fullQ,且放寬之後「佔滿但證不了 + 整份放棄」仍佔 r=−1 查詢的顯著比例;並且必須先量出「被 InventoryMoveItemEvent 取消的 push」比例(hopperPush 取消分支 4-folia/HopperBlockEntity.java:253 加計數)。兩個數字都拿不出來就不開工。
- G13 永久紅線(每次改動都重驗):(a) 不得把 lazify 擴到 HopperBlockEntity —— getFullState 直讀 hopper.items 欄位(4-folia/HopperBlockEntity.java:163)繞過 getItems(),會判成 HOPPER_EMPTY 讓物品靜默卡死;(b) 任何 ensure() 的修改都要重驗 Folia IGNORE_TILE_UPDATES ThreadLocal 窗口(4-folia:601-603),窗口內新增的 setChanged() 會被靜默吞掉。

## 白話摘要
【結論】逐格延遲解碼先不要做。真正該做的是「放寬摘要」——而且它其實已經寫好一半躺在專案裡了,只是那份程式現在是錯的,不能出貨。

【為什麼是這個順序】漏斗要不要把整箱拆開,只看我們摘要那一句話。摘要說「滿」,漏斗掉頭就走,一格都不用拆;說「不滿」或「不知道」,再往下三行就整箱拆開。s45 那 16 秒的凍結,從組譯層可以證明只會發生在「不知道」上。放寬摘要就是把「不知道」變成「滿」——直接歸零。逐格解碼只是把「拆 27 格」改成「拆 1 格」,而且拆完下一行馬上又要整箱拆,還會逼我們把「原封寫回」那條鐵則拆成一棵樹。收益差一個量級,風險差一個量級。生產證據也在放寬這邊:s3 商場掃過的 12,754 個滿箱,有 76.3% 是因為舊規則太嚴才被迫整箱拆的。

【現在最該擔心的事】我今天跑專案測試,48 項全綠;但專案裡另外有一支沒被納入測試的探針,一跑就抓到 10 種「摘要說滿、原版說沒滿」。這種錯不會弄丟東西,但會讓漏斗從此不再往那個箱子送貨——產線靜悄悄停掉,不報錯、不留 log,而且會自己維持下去。更麻煩的是 jar 已經編好躺在 target/ 了,綠燈是假的綠燈。所以下一步很明確:把那支探針納入測試、補掉那 10 個洞(問題出在兩處——「移除記號」沒看值就當真、以及沒把 minecraft: 前綴正規化)、先開 shadow 上一台驗到零不一致、再開第二台看 decodeSpikeMs 這個「凍結秒數」有沒有真的掉。逐格解碼等這輪數字出來再談。