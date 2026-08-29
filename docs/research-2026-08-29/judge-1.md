# 裁決 1:do-relax-summary-first

角度=風險優先:先假設會掉玩家東西,問「哪個方案最不可能靜默掉物品」。

■ 一、兩個方案的失效方向不對稱,差一個數量級

逐格解碼會第一次在 109 台正式服上引入「存檔時刪 entry」這個動作,並且同時新增至少四條各自獨立的靜默損失路徑:
 (a) 混合存檔第一半的 continue 是唯一會刪東西的動作,slot 判讀錯一次 = 該格永久空白;
 (b) ensureAll 在部分已解之後若照舊 loadAllItems 整份寫回,會用舊 raw 蓋掉漏斗剛抽空的格 = 物品復活 = 複製(與 LazyContainerTransformer.java:317-324 記載「無 guard 時 26/26 格復活」同一種病);
 (c) decoded 從 boolean 變 bitmask,|= 是 read-modify-write,鎖外做就 lost update(靜態分析鏡頭的模型 B9 實證),現行 pending 是純寫、結構上不可能有這類競態;
 (d) INV-7 破功(clear 忘了把 entryOff/slotEntryIdx 一起設 null)會從錯誤的位元組位置解出一個「看起來完全合法」的 entry —— 無例外、無 log、玩家只會說「我的東西變成別的了」。
更關鍵的是它會退役目前唯一在真實玩家資料上持續對帳的證據:byte-identical shadow。部分物化之後,存檔輸出既不是原 raw 也不是純 vanilla 編碼,判準只能從「逐位元組相等」退化成「多重集合相等」。

放寬摘要的失效方向則是「錯報全滿」。我實地追到後果的終點:ejectItems 在 4-folia/HopperBlockEntity.java:439-441 `if (isFullContainer(container, direction)) return false;` —— 錯報全滿只會讓漏斗不推,容器從頭到尾沒被物化、raw 逐位元組原封保留,玩家一開箱(ensure ⟹ pending=false)就自己好。是「農場默默停擺」,不是「東西不見」。風險等級差一個數量級。

■ 二、逐格能省的地方,漏斗兩行之後就吐回去(所以它的正當理由本來就很薄)

我核過 4-folia 源碼:BaseContainerBlockEntity.java:104-107 `getItem(slot)` 就是 `this.getItems().get(slot)`,Chest/Barrel/Shulker 都沒覆寫 ⟹ 任何一次 getItem 都打到 getItems() 的 guard。而 isFullContainer 答「不滿」之後必然進 hopperPush → addItem → tryMoveInItem,那裡 HopperBlockEntity.java:589 `container.getItem(slot)` 與 :592 `boolean wasEmpty = container.isEmpty();` 兩行內整箱物化(而且 wasEmpty 只在 :615 目標是 HopperBlockEntity 時才被讀,對箱子純浪費);抽取成功也必然走 :308 的 setItem → BaseContainerBlockEntity.java:125-129 → getItems()。所以只有「證明滿」這個答案才真的買到零解碼 —— 那正是放寬摘要打的那一格。兩個獨立鏡頭(hopper-access、paper-optimizations)各自得到同一結論,而 paper-optimizations 還多一刀:摘要棄答的那群本來就是「每格都有 entry」,isFullContainer 對它們平均要掃到最接近全部格,逐格在最需要它的族群上最不划算。

■ 三、但方向對不等於程式碼可出——我實測到現況已經破了

git status 顯示 template 的 components 規則已經被另一支線放寬(未 commit,line 686-712)。我把它與 tests/io/github/kuohsuanlo/lazycontainer/ProbeMain.java 一起編進真 NMS 跑(paper-26.2-mojmap),28 個案例裡 **9 個是 FALSE-FULL**(摘要答「滿」、vanilla 說「不滿」):P1/P3/P4/P5/P15/Q8/P6/P7/P10/Q6。兩個 bug 家族:

 1. 移除記號沒驗值:template:697 用 `comps.contains("!minecraft:max_stack_size")` 就把 max 設成 1。但 vanilla 的 PatchKey.valueCodec()(4-folia/DataComponentPatch.java:368-370)對 removal 是 `Codec.EMPTY.codec()` —— 只有 compound 解得出來。值是 Int(1)/""/[]/Byte(0) 時整個 patch entry 被丟掉、max 保持 64,vanilla 判「不滿」,我們卻答「滿」。(值是 {} 或 {junk:7} 才真的 max=1,見 P2/Q1。)
 2. 命名空間沒正規化:vanilla 把 key 丟進 `Identifier.tryParse`(DataComponentPatch.java:348),所以 `max_stack_size`、`:max_stack_size` 與 `minecraft:max_stack_size` 是同一顆;template:696 是字串全等比對 ⟹ 略過 ⟹ 用物品原廠 max。當真 max 比原廠**高**(P6:max=99、count=64)就會錯報全滿。

 反向的 P8/P9/P12/Q5 是安全方向(false-not-full,只是多解一次),不算事故。
 好消息:lazycontainer$verifyFull(template:522-538)是主動呼 c.getItem(i) 強制物化來覆核的,所以 shadow 金絲雀**真的抓得到** false-full,不會被自己的答案矇蔽 —— 這讓「單台 shadow 跑滿」成為有效閘門而非儀式。

■ 四、為什麼不是 do-neither-yet、不是 do-both

do-neither-yet 的理由是「先等 fullQ」。但 fullQ 計數器早在 f4aabad 就 commit 了,而我 zgrep 過 logs/*.log.gz(含今日 2026-08-29 全部檔案)—— **`fullQ=` 出現 0 次**,代表帶儀器的版本根本還沒鋪到 109 台。等一份沒人在收的資料不是等待,是空轉。正確做法是把「修好的放寬」與「fullQ 儀器」同一顆 jar 出,一邊收數一邊收割 76.3% 那塊(註:12,754 箱/76.3% 這個數字目前只存在於 template:693 與 SummaryDifferentialTest:277 的註解裡,repo 內沒有任何掃描報告佐證,列為出貨前必須重新導出的項目)。

do-both 錯在時序:逐格的收益上限恰好等於放寬之後「佔滿但證明不了」的殘量,而那個殘量現在量不出來。在殘量未知的情況下同時引入「存檔會刪 entry」的第三態,是拿事故換一個沒被量化的收益。

■ 五、附帶的紅線(不納入本次交付,但必須寫進禁做清單)

 1. BCE.isEmpty() 若要接摘要,**只准服務「證明非空 ⟹ 回 false」那一半**。答「空」會走到 4-folia/ShulkerBoxBlock.java:113 的 `!shulkerBoxBlockEntity.isEmpty()` —— 錯報空 ⟹ 界伏盒掉成空盒 ⟹ 整箱蒸發,這是本專案風險排序上唯一的「立即、不可逆、無 log」路徑。而且昂貴的那一半(幾乎空的大箱掃到底)剛好就是可證明非空的那一半,安全半邊已經吃掉全部收益。
 2. 永不把 lazify 擴到 HopperBlockEntity:Paper 的 getFullState 直讀欄位 hopper.items 而非 getItems(),會讀到未填清單判成 HOPPER_EMPTY ⟹ 漏斗永不外推 ⟹ 物品靜默卡死。
 3. 改 ensure() 時要重驗 Folia 的 IGNORE_TILE_UPDATES ThreadLocal 窗口(4-folia/HopperBlockEntity.java:601-603 涵蓋我們的 guard):任何在 ensure 內新增的 setChanged() 會被靜默吞掉。

## 閘門
- ProbeMain 升格為 JUnit(ComponentFullClaimDifferentialTest#noFalseFull)並進 test.sh:對 P1–P18/Q1–Q10 全部案例斷言 `!(summaryTri==1 && vanillaFull==false)`。現況實測 9 紅(P1/P3/P4/P5/P15/Q8/P6/P7/P10/Q6),必須 0 紅才可出貨;此測試不得只由人手跑。
- 移除記號修正的定點斷言(#removalMarkerRequiresCompound):`!minecraft:max_stack_size` 的值為 Int/String/List/Byte 時,摘要不得把 max 當成 1(vanilla PatchKey.valueCodec()=Codec.EMPTY.codec(),4-folia/DataComponentPatch.java:368-370);值為 CompoundTag(含非空 {junk:7})時才允許 max=1。
- 命名空間正規化斷言(#namespaceNormalizedLikeIdentifierTryParse):`max_stack_size`、`:max_stack_size`、`!max_stack_size`、`!:max_stack_size` 必須與 `minecraft:` 前綴版同義(對齊 DataComponentPatch.java:348 的 Identifier.tryParse);同時斷言 ' minecraft:max_stack_size'(前導空白)、大寫等 tryParse 失敗形式視同無關 component。
- 重複拼法一律棄答(#dupSpellingGivesUp):同一個 components 內出現 ≥2 種可正規化成 minecraft:max_stack_size 的 key(含 ! 形式)時,computeSummary 必須回 GIVEUP/-1 —— 勝者由 NbtOps map 迭代序 + DispatchedMapCodec 的 putIfAbsent 決定,外部無法複現。現況 P10/Q5/Q7 會開口回答,必須改成棄答。
- SummaryDifferentialTest 放寬版惡意 fuzz(≥200,000 容器)三條斷言全綠:(a) false-full 次數 == 0;(b) 假「證明為空」的格 == 0;(c) 棄答允許、答錯不允許。語料必須含壞 id、air、缺 Slot、非數值 Slot、! 記號各種值型別、無命名空間/冒號開頭 key、max 超範圍。
- ComponentPartialSemanticsTest 綠燈並列入版本升級檢查表:釘住「非 max_stack_size 的壞 component 不會讓格子變空、壞 id 才會」這條 DFU partial 前提。此測試一旦在升版後轉紅,放寬規則必須整條退回。
- 改動面積閘門:`git diff` 對 LazyContainerTemplate.java 的 hunk 不得落在 computeSummary 的 components 區塊(約 686–712)以外;load/ensure/clear/save 與 raw 逐位元組回寫路徑必須 0 行變更。既有 raw round-trip byte-identity 測試保持綠燈。
- EnsureRaceTest 必須證明它真的撞進視窗:斷言 `raced >= 100 && bad == 0`(現況 logs 出現過 rounds=10 raced=0 的空測,以及 2026-08-28-116.log.gz 的 rounds=2000 raced=12 bad=1)。那筆 bad=1 必須先查明並結案,否則不得出貨。
- build.sh 加 bytecode 政策檢查(補 build.sh:34-42 只驗 manifest 的缺口):斷言 base 被 splice(6 欄位 + 18 方法)、三個 leaf 的 13 個入口 guard 齊全、leaf 內無殘留未 redirect 的 ContainerHelper.load/saveAllItems、兩個 hopper hook 在方法入口。缺任一項即 build 失敗(可防 r149 那類靜默失效)。
- 單台 shadow 金絲雀 ≥72 小時:`-Dlazycontainer.shadow=true`,同時斷言 `summaryMismatch == 0` 且 answered full-query 樣本 >= 10,000。mismatch=0 但樣本=0 視為未通過(verifyFull 會強制物化覆核,所以這是 false-full 唯一的生產級偵測手段)。
- fullQ 儀器必須隨同一顆 jar 出貨並在正式服產生真實 `fullQ=` 行(今日 zgrep logs/*.log.gz 為 0 次)。逐格解碼的決策必須等到這份分佈回收之後再開,不得先動存檔規則。
- 數字溯源:template:693 與 SummaryDifferentialTest:277 引用的「s3 商場 12,754 個全滿箱、76.3%」在 repo 內沒有任何掃描報告佐證。交付給服主前必須用離線 region 掃描或 fullAnsweredFull/fullUnknownDirty 重新導出,否則不得作為收益宣稱。

## 白話摘要
一、決定:先做「放寬摘要」,逐格解碼暫時不做。理由很直接——逐格想省的那 26 格,漏斗在兩行之後就全部討回去了:isFullContainer 答「還沒滿」之後,推入路徑的 HopperBlockEntity.java:589 讀一格、:592 又整箱掃一次(而那個掃描結果對箱子根本用不到,只有目標是漏斗時才會被讀),抽取成功也一定要 setItem 寫回去。真正能買到「完全不用解」的,只有摘要答得出「滿」那一格,而那正是放寬要打的地方。

二、但我實測發現:目前工作區裡那份「已經放寬」的程式碼還不能出。我把它跟現成的探針一起編進真的 26.2 伺服器類別庫跑,28 個案例裡有 9 個會把「其實沒滿」的箱子講成「滿」。兩個原因:(1) 移除記號 `!minecraft:max_stack_size` 只要 key 存在就當數,但原版規定值必須是一個 {} 才算數,寫成數字或字串時原版會整顆丟掉;(2) 命名空間沒正規化,原版把 `max_stack_size`(沒有 minecraft: 前綴)當成同一顆,我們的字串比對會漏掉,漏掉之後用錯上限就有機會錯報「滿」。檔案在 /home/logocat/Server/claude-sandbox/workspace/LazyContainerAgent/template/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.java 的 696、697 兩行,探針在同專案的 tests/io/github/kuohsuanlo/lazycontainer/ProbeMain.java。好消息是這種錯不會掉東西:錯報「滿」只會讓漏斗不往那個箱子推,箱子資料一個位元組都沒動,玩家一開箱就自己恢復正常。所以是方向對、程式碼還沒好,不是方向錯。

三、為什麼不現在做逐格:它會第一次讓「存檔的時候要刪掉某幾筆資料」這件事出現在 109 台正式服上,而且會讓 shadow(目前唯一在真實玩家資料上逐位元組對帳的證據)失效——部分解碼之後,存檔輸出既不是原本那份、也不是原版會寫的那份,對帳標準只能退化成「東西數量對就好」。至於「該不該做」的判斷資料,其實我們還沒在收:fullQ 這組計數器兩個 commit 前就寫好了,但我掃過全部 109 台的 log,一次都沒出現過,代表帶儀器的版本根本沒鋪。所以正確順序是:修好放寬 → 跟 fullQ 儀器同一顆 jar 出 → 單台開 shadow 跑三天確認零誤判 → 收一輪數字,再回頭談逐格值不值得。另外提醒一條紅線:如果之後有人想把摘要接到 isEmpty(),只准回答「這箱不是空的」,絕對不准回答「這箱是空的」——後者會走到界伏盒破壞判定,錯一次就是整箱蒸發。