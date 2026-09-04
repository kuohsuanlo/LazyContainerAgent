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

## 怎麼運作

### 白話比喻
像搬家公司本來**每個經過倉庫的箱子都拆開檢查再封回**(連沒人問的也拆)。改成:**沒人要看的別拆;沒動過的原封出貨。**

### 技術機制
注入 NMS 容器類別,加入 6 個合成欄位(`pending` / `ensuring` / `raw` + 3 個摘要欄位)+ 改寫存取點:

| 動作 | 計數器 | 說明 |
|---|---|---|
| **延遲載入** | `stash` | `loadAdditional` 不呼 `ContainerHelper.loadAllItems`,改抓未解碼的原始 `Items` ListTag 暫存、標記 `pending`。**跳過解包。** |
| **存取時物化** | `ensure` | 首次有人呼 `getItems()/getContents()` → 才把暫存的 raw 解進清單(只解這一個)。 |
| **原樣寫回** | `rawSave` | 卸載存檔時若該容器全程沒被碰(`pending`)→ 把原始 bytes 逐位元組寫回。**跳過打包。** |
| **退回 eager** | `eagerLoad` | input 不是 `TagValueInput`(理論上不會)→ 安全退回原本 vanilla 行為。 |

### 跨執行緒鐵律(26.2-2,**改前必讀**)

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

- **沒碰的箱子** → 寫回的是載入時讀到的同一份 bytes(逐位元組相同),沒經轉換。
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
| `-Dlazycontainer.passthrough=false` | 關掉**存檔直寫**(見下節):未物化的箱子存檔時退回「解成 NBT 樹再交給核心重編碼」的舊路徑。 |
| `-Dlazycontainer.passthrough.shadow=true` | 直寫的**觀測模式**:磁碟照舊寫解析出的樹(等同關掉直寫),另外把直寫真的做一遍並讀回對帳。上線前跑一天用。 |

### 存檔直寫(raw passthrough)

沒被碰過的箱子,存檔時不再把暫存的原始 bytes 解成 NBT 樹、再由核心逐節點重新序列化(#261 點名這段是艦隊 5 秒級卡頓的來源之一),而是把 bytes 直接接到核心寫區塊的輸出流:`CompoundTag` 多了一組「原始 Items」欄位,核心呼叫 `write()` 時先把它以標準 named-tag 框架(`[typeId][名稱][payload]`)吐出,其餘欄位照常;`copy()` 會一併帶走。只在核心真正為存檔收集方塊實體 NBT 的視窗(`LevelChunk.getBlockEntityNbtForSaving`)內、非 shadow、且 raw 確實是 ListTag 時啟用,其餘情況一律退回舊路徑;stats 行的 `rawPassthrough=` 計次。輸出與舊路徑**結構相等**(離線用真實 region 檔 A/B 比對過;compound 內 key 順序本來就由 Paper 的雜湊表決定)。

**寫入前自檢**:掛上 bytes 之前先做一次零配置的 NBT 走訪,確認它剛好是一個完整合法的清單、長度分毫不差、字串是合法的 modified-UTF-8。規則逐條對齊伺服器讀取端或更嚴(含 `byte[]`/`int[]` 的 2^24 上限)——比讀取端嚴只會多退回舊路徑,比它寬鬆才會寫出讀不回的區塊。判定快取在方塊實體上(`rawOk`),每份 bytes 只走訪一次,自動存檔不重掃;耗時計在 `rawWalk=` / `rawWalkMaxMs=`。

**萬一 bytes 真的壞了**:走訪拒收、或解析時丟例外,都不會把它寫進區塊。該容器改走原版編碼,原始 bytes 落檔成 `lc-badraw-<座標>-N.bin` 供 `tools/mca_restore.py` 使用,座標印在 log,計在 `badRaw=`(正常恆為 0)。這條很重要:26.2 的 NBT 讀取端對壞資料丟的是 RuntimeException 而不是 IOException,若讓它穿出去,核心會把**整個區塊這輪的存檔丟掉**(同區塊其他容器的變更一起沒寫),而且每次自動存檔重演。

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
