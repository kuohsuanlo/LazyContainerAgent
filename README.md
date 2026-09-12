# LazyContainerAgent

**中文** ｜ [English](README.en.md)

> **箱子物品「延遲反序列化(不急著把資料拆解成遊戲內物件,拖到真的要用才拆)+ 沒碰過就原樣寫回」的 Java agent。**
> 針對 Paper 26.2,把 chunk(遊戲世界切成一塊一塊的地圖區域,伺服器以此為單位載入/卸載)載入時「立刻把每個箱子的物品從 NBT(Minecraft 儲存物品/方塊資料的二進位格式)解包」與卸載時「重新打包」這兩筆白工砍掉。

🧠 **經 Claude Fable 5 對抗審計**(49 個獨立 agent 分工找碴+交叉反駁,詳見 [`FABLE5-AUDIT.md`](FABLE5-AUDIT.md)):14 條發現全數逐一核實、0 條被推翻;確認**記憶體有界不洩漏、存檔淨省不做白工、正常玩家操作零掉物風險**;找到並已修復一個需要管理員指令才會踩到的邊角漏洞(容器複製/直接改存檔資料時可能共用到同一份資料)。

⚠️ **這不是外掛(plugin),是 Java agent** —— 用 `-javaagent:` 掛在 JVM 上,**不要丟 `plugins/`**(丟了沒用)。

> 🔒 **版本敏感(務必先讀)**
> 本 agent 以 bytecode **直接織入 Paper 26.2 / Java 25** 的內部類別(template classfile major 69),屬**版本綁死**的工具。
> - **僅適用於 Paper 26.2 + Java 25。** 任何其他 Minecraft 版本或 Java 版本,**一律不要直接套用**。
> - 換版必須:① 以對應版本的 NMS 重新編譯 `template/`、② 將 ASM 升級到能解析目標 classfile 版本、③ 重新以 shadow 模式驗證。
> - 版本不符時會在**開機或首次載入箱子時直接拋出例外**(`VerifyError` / `NoSuchMethodError`)。這是**刻意的「安全停機」設計——絕不會靜默改壞或弄丟資料**,但該節點會無法啟動,因此**務必先在測試環境驗證**再上線。
> - 測試素材(region / 物品 dump)為目標版格式,請勿在其他版本載入。
> - 26.2 實機測試報告:[`docs/test-reports/26.2.md`](docs/test-reports/26.2.md)。

---

## 快速上手

> 前提:**Paper 26.2 + Java 25**(其他版本請先看上面的「版本敏感」)。

**1. 放 jar** —— 把 `LazyContainerAgent.jar` 放到節點看得到的位置(跟你的伺服器 jar 放同一層最省事)。**不要丟進 `plugins/`**:它是 Java agent、不是外掛,丟了沒作用。

**2. 改啟動參數** —— 在 `java` 那行、`-jar` 的**前面**,加上以下幾段(第一次**請先用 shadow 驗證模式**):

```bash
java ... \
  -javaagent:LazyContainerAgent.jar \
  -Dlazycontainer.shadow=true \
  -Dlazycontainer.verbose=true \
  -jar <你的 Paper>.jar nogui
```

**3. 先驗證,別急著上真效能** —— 開著 `shadow=true` 跑個幾天。它會把優化後的輸出跟原版做法**逐位元組對照**:只要 `shadowMismatch` 一直是 **0**,就代表輸出跟原版完全一致、**資料零風險**。代價是這階段兩套都做、暫時不會變快。
> - 開機 log 應出現 `[LazyContainer] agent installed … [SHADOW mode]`。
> - verbose 每隔一段印一行 `stash=… rawSave=… shadowMismatch=0 …`;`stash` 持續往上爬 = 正在運作。

**4. 確認沒問題,再換真效能** —— 跑數天 `shadowMismatch=0`、也沒玩家回報少東西,就把 `-Dlazycontainer.shadow=true` 拿掉、重啟。這時「沒人碰過的箱子」會直接原樣寫回(跳過打包),效能才真正省下來。

**回滾** —— 把那幾段 `-D` 與 `-javaagent` 拿掉、重啟,立刻回 100% 原版,**不需要任何資料遷移**(硬碟格式從頭到尾沒被改過)。

---

## 這東西在解決什麼

開伺服器開久了,你大概都會撞上一種很微妙的卡頓:

明明沒什麼人在線上,主執行緒卻莫名其妙地忙。

抓 spark 一看,真兇往往不是怪、不是紅石——是**箱子**。

更精確一點,是「箱子裡的東西」。Minecraft 把物品存在硬碟上時是壓縮打包的;每當一塊地圖(chunk)被載進記憶體,伺服器就把那一區**每一個箱子、每一格物品,從 NBT 完整拆包一遍**;這塊地圖要卸載時,又**整批重新打包**寫回去。

問題是——那些箱子,絕大多數從載入到卸載,**根本沒人去開**。

拆了、又包回去,中間沒人看一眼。純白工。

而且 1.21 之後物品帶了 data components(附魔、lore、自訂名稱、容器內容…),拆包打包更貴。一座放滿地圖畫的倉庫、一條塞滿界伏盒的儲存線,光是「被載入」這件事,就能把主執行緒吃掉好幾成——在廢土(約 110 個 Paper 節點)的正式環境裡,負載最重的節點一度有 **45%** 的主執行緒,就卡在這一條鏈上:

```
ChunkFullTask.run → … → ChestBlockEntity.loadAdditional
  → ContainerHelper.loadAllItems     ← 拆包箱子物品 ≈ 45%
```

面對這種卡頓,最輕鬆的解法是**禁止**——限制每個箱子能放幾張地圖、叫大家別蓋大倉庫。但這就像為了省電把冰箱拔掉:LAG 是不見了,玩家的東西也跟著不見了。我一向不信這套——**能用技術克服的,就不該用規則去閹割玩法。**

所以這個 agent 做的事,白話講就一句:

**沒人要看的箱子,別急著拆;沒人動過的箱子,就原封不動地放回去。**

載入時,先把箱子的原始資料**收著**、先不拆;真的有人去開、漏斗去抽、比較器去讀,才當場拆**那一個**。從載入到卸載都沒人碰的,就把當初收著的那包原始 bytes **逐位元組原樣寫回**——完全跳過重新打包。

對玩家來說,箱子裡裝什麼、擺在第幾格,**一模一樣**,你驗證不出任何差別。差別只在伺服器:那一大批「拆了又包、卻根本沒人看」的白工,消失了。

---

## 效能實證(206 層波動拳 → 0%)

vanilla 載入一個放滿地圖畫/唱片的箱子,光把物品從 NBT 解出來,呼叫堆疊就深到 **~206 層**——因為資料是真的巢狀(箱子 → 界伏盒 → 地圖畫 → lore → 不同顏色文字)再乘上 Mojang codec 框架每層疊 15-20 個 frame。最底層那一行只是在 `TextColor.parseColor`(解析 lore 顏色)/ `String.equals`(比對欄位名)。

<p align="center">
<img src="docs/img/callchain-206-before.png" width="360" alt="206 層呼叫鏈(波動拳)">
&nbsp;&nbsp;
<img src="docs/img/improved-after.png" width="440" alt="改善後">
</p>

`.lctest` 同一塊密集容器 chunk、`forceload` churn、跑兩輪:

| Run | 模式 | spark | 容器解碼佔主執行緒 | profile 節點數 | 最深呼叫 |
|---|---|---|---|---|---|
| 1 | vanilla | `WOVkupfiJx` | **62.17%** | 3378 | 200 層 |
| 1 | **agent** | `wGDbUTbZKN` | **0.00%** | 482 | 9 層 |
| 2 | vanilla | `AjXLAdXzTd` | **65.65%** | 4305 | 200 層 |
| 2 | **agent** | `caXFofKSVQ` | **0.00%** | 763 | 36 層 |

整座解碼塔在 agent profile **直接消失**。4 份 spark 原始檔(已靜態存檔避免連結失效)+ 完整 206 行鏈 + 說明:[`docs/spark/`](docs/spark/SUMMARY.md)。

> ⚠️ 62~66% 是「容器解碼**單獨隔離**」的壓力測;真實混合負載下佔比為載入 **~24%** + 卸載 **~11%**(負載最重的節點可達 **~45%**)。省的是解/打包 CPU,不省 I/O / GC。

---

## 一個箱子的一生:從讀取到存檔的時間序列

以下按**真實發生順序**走一遍。括號裡是程式碼位置,想自己查證用。
現行版本是 **26.2-2**(`26.2-9` 是同一份程式、只換版本字串)。

### ① chunk 從硬碟讀進來

硬碟上的區塊檔先被核心解壓、解析成一棵 NBT 樹。這一步全是核心做的,跟本 agent 無關。
接著核心替樹上每個方塊實體(附在方塊上、替它存額外資料的物件,箱子就是一種)建出 Java 物件,
並呼叫它的載入方法把資料填進去。

### ② 箱子載入:只抄走一段 bytes,不拆物品

原版在這裡會把「Items 清單」整份解碼:每一格都要查物品登錄表、解出附魔耐久等屬性、建出物品物件。
一格倉庫幾百個箱子,這筆錢在 chunk 載入當下一次付清。

本 agent 把這個呼叫改掉了(`lazycontainer$load`,template:141)。它做三件事,順序是固定的:

1. 把「Items」那棵子樹原封序列化成一段 bytes 存起來。整棵 chunk 樹隨後可以被回收,記憶體只留這段 bytes。
2. 趁樹還在手上,順便算一張**摘要**(下面第 ④ 步會用)。
3. 最後才把「尚未解碼」的旗標打開。

**省下的**:整格箱子的物品解碼,一個都沒做。
**沒省的**:硬碟 I/O、解壓、NBT 解析,那些是核心的工作。
**退路**:輸入不是預期的型別、或序列化出了任何意外,整個退回原版的立即解碼(計在 `eagerLoad`,正常恆為 0)。

### ③ 沒人碰的時候:什麼都不做

箱子就這樣躺著。記憶體裡是一段 bytes 加一張摘要,沒有物品物件。
這是整個 agent 的全部收益來源:**倉庫區大部分箱子,從載入到卸載沒有任何人真的需要看它的內容**。

### ④ 漏斗每 tick 來問:滿了嗎?這格空嗎?

漏斗運作時會不斷問旁邊的容器兩件事:你滿了嗎(滿了我就不推)、這一格有東西可以拿嗎。
原版回答這兩個問題要把整箱物品解出來看。如果為了回答就解碼,懶載入等於白做。

所以漏斗的這兩個檢查點也被攔了(`isFullContainer` 與 `tryTakeInItemFromSlot`,transformer:242/246),
改成先問那張摘要(`lazycontainer$fullState` / `slotEmpty`,template:582/595)。摘要在載入時就算好了,查詢只是讀幾個欄位:

- 回答**「證明全滿」**或**「證明不滿」** → 漏斗直接用,零解碼。
- 回答**「不知道」** → 退回原版做法,該解就解。

什麼時候會說不知道:物品堆疊上限要看物品屬性才知道、遇到本工具不敢斷言的資料、或還沒開封的戰利品箱(刻意不答)。
摘要**只會少答,不會答錯**。這件事有專門的差分測試守著:同一份資料餵給摘要和真正的解碼器,
只要摘要開口,結論就必須跟真解碼一致(`tests/SummaryDifferentialTest.java`)。
雙箱兩半各查,任一半證明不滿就是不滿、兩半都滿才算滿(template:457)。

log 裡的 `fullQ=` 四個數字就是「證明滿 / 證明不滿 / 答不出來 / 整份放棄」的分佈。

### ⑤ 有人真的要拿東西:解碼一次,之後跟原版一樣

玩家開箱、漏斗真的要搬、外掛讀內容、比較器要算訊號、指令改資料 —— 這些最後都會走到取物品清單那個方法。
那個入口被插了一行檢查:還沒解碼就先解(`getItems` / `getContents` 的 guard,transformer:307)。

解碼本身(`lazycontainer$ensure`,template:239)在鎖裡面做:bytes 解回 NBT 樹 → 跑原版物品解碼 → 逐格填進清單
→ 丟掉 bytes → **最後**才把旗標關掉。

**每個箱子每次載入最多解一次。** 解完之後,這個箱子的一切行為跟沒裝 agent 完全相同。
已解碼箱子的取用成本是「一個 volatile 讀」,不進鎖。

順便記帳:解一次要多久、是誰害的(漏斗 / 比較器 / 玩家 / 商店外掛 / 存檔 / 破壞 / 原版 / 其他外掛),
超過 100 毫秒的還會印座標。這些是 log 裡的 `decodeMs` `decodeMaxMs` `decodeHist` 與 `attr*` 各桶。

### ⑥ 整批換內容:直接放棄暫存

有些路徑會把整個物品清單換掉(例如指令改資料、外掛重載方塊狀態)。
那些入口會把旗標和 bytes 一起清掉(`lazycontainer$clear`,template:312),之後就是純原版行為。
這條路是 26.2-2 修掉「物品原地復活」那個複製漏洞的地方,不要動。

### ⑦ 存檔:核心先在遊戲執行緒做副本,再由寫盤執行緒寫硬碟

存檔分兩步,這件事值得講清楚:

- **第一步(遊戲執行緒)**:核心把這個 chunk 的現況整理成一份「不會再變的副本」。
  它會逐一向每個方塊實體要一份 NBT。因為遊戲還在跑,寫硬碟的人必須拿到一份定格的資料。
- **第二步(寫盤執行緒)**:副本被序列化、壓縮、寫進區塊檔。**這一步早就不在遊戲執行緒上**,硬碟快慢影響不到遊戲。

本 agent 只出現在第一步,也就是「核心來要 NBT」的那一刻(`lazycontainer$save`,template:327):

- **從頭到尾沒人碰過的箱子** → 把那段 bytes 解析成 NBT 樹交出去(`lazycontainer$trySaveRaw`,template:350)。
  這裡**只做 NBT 解析,不跑物品解碼**:不查物品登錄表、不建物品物件、不重新打包屬性。
  每次存檔都解一棵全新的私有樹,所以存檔輸出和記憶體裡的狀態不會共用同一份資料。計在 `rawSave`。
- **已經被碰過的箱子** → 走原版的打包流程,一步都不省。
- 界伏盒的空清單有特別規則(原版會把空的 Items 整個丟掉),遇到就退回原版處理。

整段讀旗標、讀 bytes、寫出去都在同一把鎖裡,跟進行中的解碼互斥。
這是 26.2-2 修掉的另一個真實 bug:舊版在鎖外讀旗標,解碼解到一半時存檔,會把半填的清單寫進硬碟。

**寫進硬碟的內容和原版差在哪**:結構完全相同,唯一可能不同的是 NBT compound 內部的 key 排列順序,
那個順序本來就由 Paper 的雜湊表決定,不是資料。

### ⑧ chunk 卸載

存完之後 chunk 物件被丟掉,那段 bytes 跟著一起被回收。沒有跨 chunk 的快取,沒有東西留在記憶體裡。

---

### 跨執行緒為什麼安全

多執行緒核心(Folia / EndRod 這類把世界切成多個 region 各自跑的核心)上,插件執行緒可能直接讀到活著的箱子物件。
所以順序不是風格問題,是正確性:

1. **旗標是 volatile,而且「填完才翻」**。解碼的寫入順序固定是「逐格填清單 → 丟掉 bytes → 最後關旗標」。
   任何執行緒只要讀到旗標是關的,依 Java 記憶體模型保證清單必然已經完整。反過來寫就是舊版那個 bug:
   實測 2000 輪裡有 1943 輪被另一條執行緒讀到半填的清單,最壞把 27 格滿箱存成空的。
2. **會碰旗標和 bytes 的四條路徑全部進同一把鎖**:載入、解碼、存檔、整批替換。彼此排隊,不會交錯。
3. **不進鎖的讀者只准讀那個 volatile 旗標**,正確性完全靠第 1 條。漏斗查摘要就屬於這一類。
4. **載入的寫入順序是 bytes → 摘要 → 最後開旗標**,所以讀到旗標開著的人,一定看得到完整的 bytes 和摘要。
5. **解碼中途會呼叫自己**(填清單時要先拿到清單物件,而 guard 看到旗標還開著會再進解碼),
   所以有一個欄位專門記「現在是誰在解」,認出是自己就直接放行。這個欄位不能為了省事拿掉。

回歸測試:`tests/EnsureRaceTest.java`,裡面有一條斷言守著「真的搶到視窗」,避免測試變成假綠。

---

### 現在還在的優化一覽

| 名稱 | 一句話 | 生效的時刻 | log 欄位 |
|---|---|---|---|
| 延遲載入 | 載入時只抄 bytes,不解物品 | chunk 載入 | `stash` |
| 存取時才解碼 | 真的有人要看才解,每箱每次載入至多一次 | 第一次被碰 | `ensure` |
| 摘要快答 | 漏斗問滿不滿 / 這格空不空,讀欄位就能答,零解碼 | 漏斗每 tick | `summaryFull` `summarySkip` `fullQ` |
| 存檔只做 NBT 解析 | 沒被碰過的箱子存檔時不跑物品打包 | 存檔做副本時 | `rawSave` |
| 解碼歸因與計時 | 記錄是誰、花多久、慢的印座標 | 每次解碼 | `attr*` `decodeMs` `decodeHist` |
| 安全退路 | 任何意外一律退回原版行為 | 全路徑 | `eagerLoad` |
| shadow 驗證 | 上線前把兩種做法都算一遍逐位元組對照 | 開旗標時 | `shadowMismatch` |

### 已經拔掉、不在線上的東西

2026-09-12 起,**26.2-3 到 26.2-8 的所有新增功能都已從程式碼移除**,runtime 退回 26.2-2:

- 存檔直寫(把 bytes 掛在核心的資料物件上、跳過 NBT 解析直接寫盤)
- 暫留原始資料 10 秒、寫入保真檢查、沒人碰過卻要寫空就補回、大面積清空警報、自動降級、故障注入

原因:這三個版本每次上線都發生大面積容器清空(09-08 s3;09-12 s3、s37、s69),26.2-2 則跑了數週無事。
根因至今未破,所以整條路封掉。出貨 gate 全綠過、紅綠台驗證過,一樣出事 —— 這件事本身也記在 `gates/README.md`。

現行程式碼裡已經找不到這些功能的任何殘留(對 `src/` `template/` 搜尋直寫相關符號為 0 命中)。
替代方案的評估寫在 [`docs/DESIGN-26.2-10-safe-perf.md`](docs/DESIGN-26.2-10-safe-perf.md),**目前未實作、未上線**。

### 怎麼確認它在跑

開機 log 應該有這三行:

```
[LazyContainer] spliced 6 fields + 18 methods into BaseContainerBlockEntity
[LazyContainer] transformed leaf .../ChestBlockEntity
[LazyContainer] agent installed (transformer registered)
```

開 `-Dlazycontainer.verbose=true` 之後,每 30 秒印一行統計。主要看:

- `stash` 持續往上爬 = 懶載入正在運作。
- `ensure` 相對 `stash` 越小越好,代表大多數箱子從沒被碰過。
- `rawSave` = 這段時間有多少次「沒被碰過的箱子」走了省事的存檔路徑。
- `eagerLoad` `shadowMismatch` `summaryMismatch` **正常恆為 0**,不是 0 就要查。
- 統計行裡如果出現 `rawPassthrough` `keptRaw` `badWrite` 這類字,代表跑的是**已拔除的舊版本**,該台要換回 26.2-2。

### 求證表

| 步驟 | 程式碼 |
|---|---|
| ② 載入只抄 bytes | `template/…/LazyContainerTemplate.java:141` `lazycontainer$load` |
| ② 摘要建置 | 同檔 `:156`、演算法本體 `lazycontainer$computeSummary`(同檔案內搜尋方法名) |
| ④ 漏斗攔截點 | `src/…/LazyContainerTransformer.java:242` `:246` `transformHopper` |
| ④ 摘要查詢 | `template:582` `lazycontainer$fullState`、`:595` `lazycontainer$slotEmpty`、`:457` 雙箱 |
| ⑤ 存取 guard | `src/…/LazyContainerTransformer.java:307` `guardKind` |
| ⑤ 解碼 | `template:239` `lazycontainer$ensure` |
| ⑥ 整批換內容 | `template:312` `lazycontainer$clear` |
| ⑦ 存檔入口 | `template:327` `lazycontainer$save`、`:335` `saveNoEmpty` |
| ⑦ 存檔只做 NBT 解析 | `template:350` `lazycontainer$trySaveRaw`、`:199` `lazycontainer$decodeRaw` |
| 統計行欄位 | `src/…/LazyContainerRuntime.java:449` `stats()` |

---

## 怎麼運作(對照表版)

> 時間順序的完整說明見上面「[一個箱子的一生](#一個箱子的一生從讀取到存檔的時間序列)」;這一節是給趕時間的人的速查。

### 白話比喻
像搬家公司本來**每個經過倉庫的箱子都拆開檢查再封回**(連沒人問的也拆)。改成:**沒人要看的別拆;沒動過的原封出貨。**

### 技術機制
注入 NMS 容器類別,加入 6 個合成欄位(`pending` / `ensuring` / `raw` + 3 個摘要欄位)+ 改寫存取點:

| 動作 | 計數器 | 說明 |
|---|---|---|
| **延遲載入** | `stash` | `loadAdditional` 不呼 `ContainerHelper.loadAllItems`,改抓未解碼的原始 `Items` ListTag 暫存、標記 `pending`。**跳過解包。** |
| **存取時物化** | `ensure` | 首次有人呼 `getItems()/getContents()` → 才把暫存的 raw 解進清單(只解這一個)。 |
| **存檔省打包** | `rawSave` | 存檔時若該容器全程沒被碰(`pending`)→ 把暫存 bytes 解析成 NBT 樹交給核心。**只做 NBT 解析,不跑物品打包。** |
| **退回 eager** | `eagerLoad` | input 不是 `TagValueInput`(理論上不會)→ 安全退回原本 vanilla 行為。 |

### 跨執行緒鐵律(26.2-2,**改前必讀**)

> 上面「一個箱子的一生」已用白話講過同一件事;這一節是**要動 `template/` 的人**該看的精確版本。

這幾條不是風格偏好,是 26.2-2 修掉一個真實掉物/複製/存檔殘缺 bug 之後留下的不變式。**動 template 之前先讀完。**

1. **`lazycontainer$pending` 是 `volatile`,而且「填完才翻」。**
   `ensure()` 的寫入序固定為「逐格填清單 → `raw=null` → **最後** `pending=false`」。任何執行緒只要讀到 `pending==false`,
   依 happens-before **清單就必然已經完整**。反過來寫(先清旗標再填)就是 26.2-1 的 bug:實測 2000 輪中 1943 輪
   被另一條執行緒讀到半填清單,最壞把 27 格滿箱存成 `Items: []`。
2. **碰 `pending` / `raw` 的路徑一律進 `this` monitor。** 載入(`load`)、物化(`ensure`)、存檔(`save`/`saveNoEmpty`)、
   整批替換(`clear`)四條全部 `synchronized`,彼此序列化。**transformer 的 `GUARD_CLEAR` 必須呼叫 `lazycontainer$clear()`,
   不可就地 `PUTFIELD`**——就地寫就是鎖外改狀態,等於沒修。
3. **未持鎖的讀者只准讀 volatile 旗標。** leaf guard(`if (pending) ensure();`)與漏斗摘要查詢不進鎖;
   它們的正確性完全靠第 1 條。
4. **`load` 的寫入序是 raw → 摘要 → 最後 `pending=true`。** 讀到 `pending==true` 的查詢端必看得到 raw 與完整摘要。
5. **`lazycontainer$ensuring`(`Thread`)只做重入偵測。** `ensure()` 在 monitor 內呼叫 `getItems()`,而 guard 看到
   `pending` 仍是 true 會再進 `ensure()`;monitor 可重入,不擋就是無限遞迴。**不要為了「省一個欄位」把它拿掉。**
6. **為什麼 Paper 上看不到問題、EndRod 上會**:EndRod 的 PIW(R39)允許非擁有 region 的插件執行緒讀活體容器,
   而 paper-server 的 `CraftInventory.getItem/getContents` 是**先呼叫 NMS** 才做跨區快照 → guard 與整段解碼都跑在插件執行緒上。
   舊文件寫的「三路徑皆單一主緒」只描述純 Paper。

回歸測試:`tests/io/github/kuohsuanlo/lazycontainer/EnsureRaceTest.java`(`./test.sh` 會跑)。
它有 `assertTrue(raced > 0)` 守著「真的有搶到視窗」,不會變成假綠。細節見 [`RELEASE-NOTE-26.2-2.md`](RELEASE-NOTE-26.2-2.md)。

### 熱路徑成本

| 路徑 | 26.2-2 的額外成本 |
|---|---|
| 已物化容器的 `getItems()`(穩態的絕大多數) | **一個 volatile 讀**。x86 上就是普通 `mov`(零額外指令,只擋編譯器重排),ARM 為 `ldar`。不進 monitor。 |
| `ensure()` | 每容器每次載入**至多一次**,不在 tick 熱路徑。 |
| `load` / `save` / `clear` | 各多一次**無競爭** thin-lock CAS。三者都不是每 tick 路徑(存檔本來就是週期性動作)。 |
| 漏斗摘要查詢(滿/空檢查) | **零**。只讀欄位,不進鎖。 |

**沒有拿效能換正確性的取捨**;上方效能實證的數字在 26.2-2 之後不變。

---

涵蓋型別:`ChestBlockEntity`、`BarrelBlockEntity`、`ShulkerBoxBlockEntity`。
**唯一咽喉 = `getItems()`**:NMS `BaseContainerBlockEntity` 所有容器讀寫(isEmpty/getItem/removeItem/setItem/clearContent/掉落/比較器…)都經它,守一個即覆蓋全部;CraftBukkit 的 `getContents()` 會繞過,額外守。`getContainerSize()` 不經內容(結構性),不守。

---

## 架構(怎麼注入的)

純外掛(plugin)無法覆寫 NMS(Minecraft 伺服器內部程式碼)裡標記 `final`(禁止被子類別覆寫)的方法,所以用 **Java agent + ASM(操作 Java bytecode、能在類別載入當下動態改寫它的工具)注入**:

1. **`LazyContainerAgentMain`**(premain,JVM 啟動時最先跑的進入點):把整個 jar 用 `appendToBootstrapClassLoaderSearch` 掛上 bootstrap classloader(JVM 最底層、所有類別載入器共同的祖先,這樣才能繞過 Paper 把 Minecraft 內部程式碼隔離起來的機制),再註冊 transformer(下面第 2 點的類別改寫器)。
2. **`LazyContainerTransformer`**(執行實際改寫的 ASM 邏輯):
   - 把 **`LazyContainerTemplate`**(用一般 Java 語法、對著「真實的 Minecraft 伺服器內部程式碼」編譯出來的邏輯,而不是手刻 bytecode)splice(接枝:把外來的欄位/方法插進既有類別)進 `BaseContainerBlockEntity`(所有容器方塊共同的父類別)。→ **編譯器會幫忙驗證方法簽章對不對,比手寫 bytecode 安全得多。**
   - 在箱子/木桶/界伏盒這三個實際子類別(繼承鏈最底層的類別,術語叫 leaf)的 `getItems/getContents/setItems` 入口插「守門檢查」(guard,判斷這容器還沒被解碼、要不要先補解碼)、把 load/save 裡呼叫 `ContainerHelper` 的地方改成呼叫延遲版邏輯。
3. **`LazyContainerRuntime`**(掛在 bootstrap classloader、純 JDK 沒有依賴任何 Minecraft 類別):shadow(驗證模式)開關 + 計數器。
4. **安全鐵律**:父類別(base/superclass)沒改寫成功,就**完全不動子類別(leaf)** → 整個退回純原版行為,絕不會產生「方法不存在」這類崩潰性錯誤(`NoSuchMethodError`);過程中任何例外 → 回傳原本沒改過的 bytecode。

---

## 為什麼不會掉資料 / 改到區塊資料

**它改的是「什麼時候解包」,不是「箱子存什麼」。硬碟格式從頭到尾沒變。**

- **沒碰的箱子** → 寫回的是載入時讀到的那份資料本身(只做 NBT 解析,不經物品解碼/重新打包),物品內容不可能被改寫。
- **被碰的箱子** → 跟 vanilla 一模一樣地解碼、再一模一樣地存回。
- 只動箱子的 `Items`(容器裡的物品清單),不碰地形 / 方塊 / 實體 / 光照 / 其他 BE(BlockEntity,附在方塊上、替它存額外資料的物件,例如告示牌的文字、箱子的內容物)。

已驗證:
- **離線 JVM bytecode 驗證**:注入的 4 個類別全通過 link/verify(JVM 載入類別時檢查 bytecode 合不合法的機制)。
- **真實 Paper 26.2 端對端**:放物品(diamond×42 / sword{damage:10} / netherite×7)→重啟→重載,**逐字相同**(含 data-component,1.20.5 之後 Minecraft 用來描述物品屬性——附魔、耐久、自訂名稱等——的資料格式);shadow 真實世界 56 容器 `shadowMismatch=0`。詳見 [`docs/test-reports/26.2.md`](docs/test-reports/26.2.md)。
- **對抗審查(8 種失效模式 × 對抗驗證)**:**0 個會改/掉資料的路徑**;查到 2 個無關痛癢的 byte-identity(逐位元組完全相同)小差異,已修。
- **Fable 5 二輪對抗審計**(49 agent,更大規模、針對現行程式碼):同樣 **0 個會掉物的路徑**,額外找到並修復 1 個管理員指令才會踩到的邊角漏洞。詳見 [`FABLE5-AUDIT.md`](FABLE5-AUDIT.md)。
- **DFU 跨版本**:暫存的原始資料本來就是「DFU(DataFixerUpper,Minecraft 用來把舊版存檔資料自動升級成新版格式的機制)升級後」的版本(DFU 在區塊資料被讀出來的最早期、BE 物件都還沒建立前就跑完了),回寫的自然也是升級後的版本,不會有跨版本相容性問題。

詳見 [`FINDINGS.md`](FINDINGS.md)、[`ADVERSARIAL-REVIEW.md`](ADVERSARIAL-REVIEW.md)、[`FABLE5-AUDIT.md`](FABLE5-AUDIT.md)。

---

## shadow 模式(上線必開的驗證)

`-Dlazycontainer.shadow=true`:每次要寫 raw **之前**,額外把 vanilla 的做法(解開→重打包)算一遍**逐位元組比對**:
- **一樣** → 寫 raw(並得到一筆「快路徑正確」的證據)。
- **不一樣** → 改寫 **vanilla 那份**(安全的),`shadowMismatch++` 並印座標。

→ **開著 shadow,硬碟輸出在數學上不可能跟 vanilla 不同**(零風險驗證)。代價:兩套都做了,**暫時沒加速**。
跑數天 `shadowMismatch=0` + 無玩家回報少東西 → 才關 shadow 換真效能。

### shadowMismatch vs benignReorder(語意感知比對)

純逐位元組比對對「**同一組物品、只是 Items 清單順序不同**」會誤判(常見於外掛產的容器——每個 entry 自帶 `Slot`,清單順序不影響槽位)。所以 v2 起把差異分兩類:

- **`benignReorder`** — raw 與 eager 是**同一組物品+槽位、只差清單順序**(以 multiset 比對確認)→ **安全寫 raw、不算問題**。但仍**偵測並回報**:印 `benign reorder @ <pos> — … NO IMPACT (raw kept)`(前 30 次,之後僅累加避免洗版)。
- **`shadowMismatch`** — 真正的結構差異(物品數量/內容變了,例如槽位越界被丟棄)→ 寫 eager(對齊 vanilla)+ 印座標。

→ 盯 **`shadowMismatch=0`** 即可;`benignReorder` 只是「外掛寫法不同」的無害提示,**不是要修的東西**。`-Dlazycontainer.dump=true` 時兩類都會把 raw/eager 各存一份(`lc-mismatch-N` / `lc-benign-N`)供離線 diff。

---

## 出貨 gate(換版與每次改動的驗收)

每一項優化都有自己的 use case、測試素材與通過條件,收在 [`gates/`](gates/README.md):

```bash
bash gates/run.sh                       # 全部 G0…G8,約 90–120 分鐘
bash gates/run.sh --tiers G0,G1,G2,G3   # 只跑離線關卡,約 5 分鐘
bash gates/run.sh --version 26.3        # 換版
```

| 關卡 | 守什麼 |
|---|---|
| G0 環境/版本 | JDK、核心 jar、classfile major、工具、素材來源 |
| G1 建置 + 政策閘門 | 編得出來、鎖政策沒被破壞、版本字串一致 |
| G2 注入形狀 diff | 目標版 NMS 的注入假設指紋與基準逐行比(**換版最重要的一關**) |
| G3 差分/併發單元 | 摘要 vs 真 codec、物化競態 |
| G4 存檔 E2E | 原版 vs 本 agent,真倉庫逐容器輸出結構相等(直寫拔除後 `LC_HAS_PASSTHROUGH=0`,只跑這兩種) |
| G5 逐格裁判 | 真伺服器裡逐容器逐格 `ItemStack.matches` |
| G6 摘要/影子對抗 | 執行中用真解碼當神諭校驗每次快答 |
| G7 互動對抗 | 真 bot + 外掛 API + 指令 + 漏斗,單一 FINAL VERDICT |
| G8 出貨物件 | 版本字串、文件、交付夾 |

換版(26.2 → 26.3 → …)照 [`gates/UPGRADE.md`](gates/UPGRADE.md) 走:**測試資產是版本無關的基礎建設,換版是「重跑」不是「重寫」**。

---

## 建置

```bash
bash build.sh        # 需要 JDK 25(見下);產出 target/LazyContainerAgent.jar
```
需要 `nms-lib/`(你的伺服器核心 Paper 的 NMS 編譯相依 libraries,供 `template/` 對真實 NMS 編譯;NMS = Minecraft 伺服器內部程式碼)。
此目錄不入 git(太大、含 Mojang/Paper 產物),建置前自行放好。

流程:① `mvn package`(編出 agent 本體類別 + 把 ASM 這個依賴打包重定位進 jar + 產生 manifest)→ ② `javac` 把 template 對真實 NMS 原始碼編譯(這步**必須用 JDK 25**,因為要編出跟 26.2 伺服器相符的 bytecode 版本;agent 本體類別編譯目標是相容性較廣的 JDK 21 格式,但整個編譯流程統一用 JDK 25 跑,`build.sh` 已內建這個設定)→ ③ `jar uf` 把 template 編出來的 `.class` 檔當成「純資料」塞進 jar(執行期只會被讀取原始 bytes,不會真的被當一個類別載入)。

---

## 部署

把 jar 放到節點看得到的位置,在 `java` 那行 **`-jar` 前面**插旗標:

```bash
java -Xms8000M -Xmx8000M \
  -javaagent:LazyContainerAgent.jar \
  -Dlazycontainer.shadow=true \
  -Dlazycontainer.verbose=true \
  ... 原本的 -XX 旗標 ... \
  -jar <你的 Paper>.jar nogui
```

開機 log 應出現:
```
[LazyContainer] LazyContainerAgent —— crafted by 廢土貓大 LogoCat · 廢土 · mcfallout.net
[LazyContainer] spliced 6 fields + 18 methods into BaseContainerBlockEntity
[LazyContainer] transformed leaf .../ChestBlockEntity
[LazyContainer] agent installed (transformer registered) [SHADOW mode]
```

| 旗標 | 作用 |
|---|---|
| `-Dlazycontainer.shadow=true` | **上線必開**。輸出保證等同 vanilla;暫無加速。 |
| `-Dlazycontainer.verbose=true` | 背景 daemon 定期印計數。 |
| `-Dlazycontainer.verbose.ms=8000` | verbose 列印間隔(ms,預設 30000)。 |
| `-Dlazycontainer.dump=true` | mismatch / benign reorder 時把 raw/eager SNBT 各落一檔(`lc-mismatch-N` / `lc-benign-N`,各前 30 次),供離線 diff。 |
| `-Dlazycontainer.dump.dir=<路徑>` | dump 落檔目錄(預設 `.` = 伺服器工作目錄)。 |
| `-Dlazycontainer.summary=false` | 關掉「漏斗問滿不滿/這格空不空」的摘要快答(保留延遲解碼本身)。 |
| `-Dlazycontainer.attribution=false` | 關掉解碼觸發者歸因(stats 行會印 `attribution=off`)。 |

### 存檔直寫(raw passthrough)—— ⛔ 已於 2026-09-12 移除

26.2-3 ~ 26.2-8 曾經把暫存的 bytes 掛在核心存檔用的資料物件上、跳過 NBT 解析直接寫盤,
後續版本又在上面加了暫留原始資料、寫入保真檢查、空寫守門、大面積清空警報與自動降級。
**這一整條路已經從程式碼移除,runtime 退回 26.2-2。**

原因:那三個版本每次上線都發生大面積容器清空(09-08 s3;09-12 s3、s37、s69),而 26.2-2 跑了數週無事。
根因未破。出貨 gate 全綠、紅綠台也驗過,一樣出事 —— 所以「gate 全綠」不等於生產安全,這點記在 [`gates/README.md`](gates/README.md)。

替代方案(存檔預解析、逐格平行物化、調 autosave 節奏)的評估寫在
[`docs/DESIGN-26.2-10-safe-perf.md`](docs/DESIGN-26.2-10-safe-perf.md),**未實作、未上線**。
歷史設計文件保留在 [`docs/RAW-PASSTHROUGH-261.md`](docs/RAW-PASSTHROUGH-261.md) 供考古,不代表現況。

### 出事了怎麼救:`tools/mca_restore.py`

純 Python、不需要伺服器。`verify --deep` 逐格解壓並走完整 NBT,列出壞掉的區塊;`list` 列容器與其 Items 大小;`restore-chunk` 從備份把整格貼回;`restore-items` 只把單一容器的 Items 貼回。

還原一律是**位元組拼接**,不重新編碼:Java 的 NBT 字串是 modified-UTF-8(`U+0000` 與增補字元的寫法和一般 UTF-8 不同),浮點數的 NaN 位元樣式、compound 的 key 順序也都會在重寫時改變——整份重寫等於在每個欄位上重新賭一次。所有寫入前會確認世界的 `session.lock` 沒被鎖住、沒有程序開著那個檔(伺服器在跑時,它記憶體裡的區域檔檔頭會把外部修改整份蓋掉),並先備份目標檔。

**回滾**:刪掉那幾段旗標重啟 → 回 100% vanilla,**不需任何資料遷移**(硬碟格式沒被改過)。

---

## 實測(正式環境,shadow 模式)

在正式環境的 Paper 節點實掛 shadow 模式,觀察到的行為:

- **`shadowMismatch=0`(持續)** → 輸出與 vanilla 逐位元組一致,資料零風險。
- `stash` 持續累積 → 載入時「立刻解包」這件白工確實被攔下(也就是那 45% 的源頭)。
- `ensure` 的高低取決於該節點漏斗/比較器的活躍度:被碰到的箱子會即時物化(分散到各 tick),「完全省掉」的是「從載入到卸載都沒被碰」的那一批(`rawSave`)。

因此最大效益落在「閒置或 churn 中的容器」;最終加速幅度待關閉 shadow 後重抓 spark 對照(見上方「效能實證」)。

---

## 限制與注意

- **益處依賴「箱子沒被碰」**:churn / 閒置儲存(載入→沒人碰→卸載)大勝;**活躍的漏斗/比較器分類倉**會把箱子 ensure 掉,純省比例變小(主要益處變成「把載入尖峰打散」)。姊妹專案 **ChunkForceManager** 從「別讓 chunk 反覆載卸」那端互補。
- **版本綁定**:Paper **26.2 / Java 25**(template major 69)。換版需用對應 NMS 重編 `template/`,並把 ASM 升到能讀目標 classfile 版本。版本不符會在開機/首次載箱子時**大聲報錯**(VerifyError/NoSuchMethod),不會靜默毀資料。詳見下方「版本敏感(務必先讀)」。
- 不影響:loot table 箱子(走另一條路徑,正交)、雙箱 CompoundContainer(委派到子箱 getItems,已守)。
- **多執行緒核心(EndRod / Folia 系)**:26.2-1 以前假設「載入/tick/卸載皆主執行緒」,那個假設**只在純 Paper 成立**——EndRod 的 PIW(R39)允許插件執行緒讀活體容器,舊版因此有真實的半填視窗。26.2-2 起 `pending` 為 volatile、`ensure()` 填完才翻旗標、四條狀態路徑全進 monitor,**在單主緒與多執行緒核心上都正確**。見上方「跨執行緒鐵律」。

---

## 檔案地圖

```
src/main/java/io/github/kuohsuanlo/lazycontainer/
  LazyContainerAgentMain.java   premain / bootstrap 掛載
  LazyContainerRuntime.java     bootstrap 純 JDK:shadow 開關 + 計數器
  LazyContainerTransformer.java ASM:splice base + 改寫 leaf
template/.../LazyContainerTemplate.java   對真實 NMS 編譯的延遲邏輯(splice 來源)
tools/scan_containers.py        掃 region 檔找箱子最密的 chunk(找「載入最貴」的地點)
tools/mca_restore.py            離線修/還原區塊檔(verify/list/restore-chunk/restore-items)
tools/mca_merge3.py             整格三方合併還原(避免整格貼舊備份把新建築倒掉)
tools/decode_bench.sh           離線量存檔路徑 NBT 解析與物品解碼的成本(序列 vs 多核心)
tests/.../SummaryDifferentialTest.java  摘要 vs 真 codec 差分(含 A2 案例)
tests/.../EnsureRaceTest.java          跨執行緒物化視窗回歸(26.2-2 / A1)
tests/.../NmsTestSupport.java          零 Minecraft server 的 headless NMS 啟動
test.sh   跑上面三支測試(需 nms-lib/)
build.sh  pom.xml  nms-lib/(不入 git)
FINDINGS.md           反編譯確認的事實 + 設計定案 + 風險分析
ADVERSARIAL-REVIEW.md 對抗審查報告(8 失效模式,12 agent)
FABLE5-AUDIT.md        Fable 5 二輪對抗審計(49 agent,含記憶體/掉物三問結論)
RELEASE-NOTE-26.2-2.md 跨執行緒視窗(A1)+ 摘要非數值 Slot(A2)的根因、JMM 論證、紅/綠證據
TESTING.md            怎麼自己測(自動 round-trip / 手動玩測 / 真實世界副本驗 shadow)
```

延伸閱讀:[`FINDINGS.md`](FINDINGS.md)(技術全貌)· [`TESTING.md`](TESTING.md)(自測)· [`ADVERSARIAL-REVIEW.md`](ADVERSARIAL-REVIEW.md)(審查)· [`FABLE5-AUDIT.md`](FABLE5-AUDIT.md)(Fable 5 審計)。
