# LazyContainer Agent 對抗審查報告(合併版)

> 針對 8 種失效模式(容器物品可能出錯的情境分類),各指派一個 finder(專門負責找出某一類 bug 的 AI 審查角色)去挖問題,再用對抗驗證(多個 AI 角色互相踢館、覆核彼此發現是否真的成立的審查方式)交叉確認,前後共動用 12 個 agent(AI 審查角色)。totalFindings(原始發現總數)=39,confirmedReal(最終確認為真的問題數)=2(嚴重度皆為 low〔低〕,且都不會掉玩家物品,已修復)。
>
> 更大規模、針對現行程式碼(含 [`FABLE5-AUDIT.md`](FABLE5-AUDIT.md) 記錄的修復)的二輪審計,見 [`FABLE5-AUDIT.md`](FABLE5-AUDIT.md)(49 agent)。

延遲容器解碼(lazy `Items` decode/encode,意思是容器裡的物品清單〔`Items`,NBT〔Minecraft 存檔用的資料格式,Named Binary Tag〕欄位之一〕不會一載入就急著解析成 Java 物件,而是先以最原始的位元組資料〔以下簡稱 raw〕擱著,等真的被玩家打開、或程式需要用到時才解析;完全沒被碰過的容器則直接把 raw 原封寫回存檔,省下重複解析/編碼的效能開銷)優化的失效模式審查(針對這項效能優化,系統性檢查所有可能出錯、導致資料異常的情境)。共 33 條原始發現,去重合併後彙整如下。

## 一、去重合併結果

原始 33 條發現中,有大量屬於同一分類(表格中的 `mode` 欄位,即每種失效模式的代號)且「已驗證無虞」的確認,合併為 9 個失效模式群組:

| 群組(mode) | 條目數 | 結論 | 真 bug 數 |
|---|---|---|---|
| missed-accessor(漏掉的欄位存取咽喉) | 8 | getItems()/getContents() 為唯一咽喉,private 欄位 + 精確 descriptor guard | 0 |
| double-chest(雙箱委派鏈) | 6 | CompoundContainer 全走 Container 介面,虛擬分派命中子箱 guard | 0 |
| loot-table(戰利品箱 + lazy 正交性) | 7 | LootTable 分支不被 redirect,Items 由 raw 保存,兩者 NBT 正交 | 0 |
| dfu-version(跨版本升級) | 3 | DFU 在 chunk-tag 層、BE 構造前完成,raw 恆為 post-DFU | 0 |
| block-to-item(破壞掉落/pick-block) | 6 | collectComponents/getDrops 必經 getItems() guard,界伏盒不掉空 | 0 |
| thread-safety(可見性/競態) | 1 | ~~三路徑(載入寫/tick 讀寫/存檔讀)皆單一主緒,無需 volatile~~ **⚠️ 26.2-2 已修正**:前提只在純 Paper 成立,EndRod(PIW R39)上插件執行緒會讀活體容器 → 真的有半填視窗,見文末「26.2-2 更正」 | ~~0~~ **1** |
| moonrise-paper-conflict(底層 chunk 機制衝突) | 5 | Moonrise 延遲 BE 物件建立,本優化延遲 items decode,兩層互補 | 0 |
| **raw-aliasing-empty(raw 別名/空容器/型別)** | **3** | **2 真 1 假** | **2** |

*(表格內幾個底層用詞白話對照:「咽喉」指所有存取路徑都必須經過的唯一入口/檢查點,繞不過去就不會漏掉保護;「guard」指程式碼裡的防呆檢查;「虛擬分派」是 Java 物件導向的機制,呼叫方法時系統會自動依物件實際型別找到對應版本,不會因為漏寫某個子類而跳過檢查;「正交」是數學/工程用語,意思是兩塊資料互不影響、各自獨立變化,改一個不會弄亂另一個;DFU(Data Fixer Upper)是 Minecraft 官方的「舊版存檔格式自動升級」機制;BE(BlockEntity,方塊實體)指箱子等「有額外資料」的方塊背後對應的 Java 物件;「chunk-tag 層」指一大塊區塊(chunk)資料剛讀出來、還沒拆成個別方塊實體之前的階段;Moonrise 是 Paper/Folia 底層另一套加速區塊載入的第三方最佳化,和本專案的延遲解碼是兩層互補、互不衝突的優化。)*

## 二、依嚴重度排序(僅列 real=true〔對抗審查最終判定為真實存在的問題〕及需注意者)

| # | 嚴重度 | real | 標題 | 是否掉東西/毀資料 |
|---|---|---|---|---|
| R1 | low | ✅ true | Shulker(allowEmpty=false)空 `Items:[]` 非 byte-identical:vanilla discard,本實作寫回 `Items:[]` | ❌ 不掉物(空→空) |
| R2 | low | ✅ true | trySaveRaw 對「型別錯誤的 Items」(非 ListTag)原樣回寫,vanilla 視為空 | ❌ 不掉物(反而保留原 bytes,比 vanilla 更安全) |
| R3 | low | ❌ false(原報 0.55→裁定假警報 0.82) | ensure() 內例外留下空容器(silent loss) | ❌ 路徑不可達,非真 bug |

*(表格內幾個用詞白話對照:`allowEmpty` 是程式參數,決定「空容器可不可以被允許」;`ListTag` 是 NBT 資料格式裡的「清單」型別,容器的 Items 欄位正常應該是這個型別;byte-identical/byte-identity 指寫回磁碟的位元組跟 vanilla〔原版〕存檔逐位元組完全一致;discard(丟棄)指 vanilla 遇到異常資料時直接不存;silent loss 指程式沒有報錯,但資料卻悄悄不見了;`real` 欄位的 true/false 是對抗審查(多個 AI 角色互相踢館、驗證彼此發現是否成立的審查方式)最終判定「這問題是不是真的存在」;像 R3 那樣的 0.55→0.82 是信心值,是 AI 審查角色對「這是不是真問題」給的把握分數(0~1,愈高愈確定),這裡是覆核後從 0.55 上修到 0.82,但最終裁定(多位 AI 角色投票覆核後的結論)是假警報(表面看起來像問題,深入查證後證實不成立)。)*

## 三、真正會掉東西/毀資料的 critical(最嚴重級)/high(次嚴重級)

**結論:沒有任何 critical 或 high。沒有任何一條會掉物品或毀資料。**

- R1、R2 是 **byte-identity 違反(寫回磁碟的位元組沒有跟 vanilla〔原版〕存檔逐位元組完全一致,但遊戲內資料/功能其實沒有差別,純屬 NBT 格式上的細節差異)**,非資料風險。兩者皆:(a) 不掉物品,空/壞輸入再載入仍為空;(b) 需要**非-vanilla 來源的磁碟 NBT**(其他外掛/外部工具/舊格式/損毀寫出、不是原版遊戲自己存的檔案)才會觸發,純 vanilla 資料不可達;(c) **shadow 模式(影子模式:新舊兩套邏輯同時跑,新邏輯先在背景比對、不會真的生效,等確認結果一致才正式切換上場,是一種安全上線的驗證方式)已正確中和**(eagerItems〔沒有延遲、立刻解析好的物品資料,用來跟 lazy 版本比對是否一致〕比對不符 → 改寫 eager → 對齊 vanilla)。差異只在預設(非 shadow)生產模式才浮現。
- R3 經第二輪裁定為**假警報**:pending(容器已經生成,但裡面的物品清單還沒真的解析成 Java 物件,仍以最原始的 raw 資料型式擱置)狀態只可能在 chunk(區塊)載入(server 已就緒)時產生,`getServer()==null`(取得伺服器物件卻拿到空值)這個分支實際上不可達;`loadAllItems` 對損毀 Items 用 `ProblemReporter.DISCARDING`(Minecraft 內建「回報但丟棄有問題資料、不中斷程式」的機制,不會讓伺服器當機,但問題資料會默默消失)吞錯不拋例外。set-flag-first(先設定狀態旗標、後面才做實際處理的寫法)的順序隱患(理論上可能因為順序而出包的風險)存在但無觸發點(實際情況下走不到)。

最該擔心的「界伏盒破壞掉空」情境(block-to-item 群組)已被多條獨立確認:破壞掉落必經 `getItems()` 咽喉物化(從還沒解析的原始 raw 資料,真正轉換成程式可以操作的 Java 物件),pending 界伏盒破壞時內容完整寫入掉落物。

## 四、Completeness Critic(專門檢查「這次審查還漏查了什麼」的 AI 角色)— 未覆蓋的失效模式

審查覆蓋面很廣,但以下失效模式**未見於發現清單**,建議補測:

1. **Crash(當機)/ kill -9(強制終止程序)中途存檔的原子性(要嘛整份資料完整寫成功、要嘛完全沒寫,不會留下寫一半、壞掉的檔案)**:autosave(自動存檔)寫到一半當機,raw 路徑與 vanilla encode 路徑對 region 檔(Minecraft 存檔用的 .mca 檔案,一份裝著一大片區域的所有方塊/區塊資料)的寫入原子性是否一致?(已覆蓋正常 unload〔區塊正常卸載〕,未覆蓋非正常終止。)
2. **`/data` 指令、`BLOCK_ENTITY_DATA` 直接寫入 NBT 的反向路徑**:op(有管理員權限的玩家)用 `/data merge block` 改 pending 容器的 Items,或 `setBlockEntityData`。pick-block(創造模式中用滑鼠中鍵「吸取方塊」到快捷欄的操作)讀路徑有覆蓋,但**外部直接 NBT 寫入 + 隨後 raw 回寫**的互動未見分析。
3. **structure block / 結構方塊存讀容器**:structure(結構)save/load 走 `saveAdditional`/`loadAdditional`(方塊實體標準的存檔/讀檔方法)還是另一條路徑?未覆蓋。
4. **`/clone` 方塊指令搬移 pending 容器**:clone 是否複製 raw/pending 合成欄位,或重走 save→load?未見。
5. **記憶體洩漏 / raw 滯留**:長期 pending 的容器持有 raw `Tag`(NBT 資料的基礎型別)參照(MEMORY 中多次提及自家外掛 per-chunk heap 洩漏)。大量未開啟箱子常駐時,raw `CompoundTag`(NBT 格式裡的「複合物件」型別,可以裝很多欄位,例如一個方塊實體的完整資料)累積的 heap(Java 用來放物件的記憶體區塊)佔用 vs vanilla 立即釋放 NBT,**未做記憶體面評估**。
6. **shadow 模式關閉後的長期等冪保證**:多條發現把正規化等冪性(idempotent,指同一份資料不管重複做幾次「解析再寫回」,結果都要跟原本一模一樣、不會愈跑愈走鐘)「外包」給 shadow 模式,但**沒有一條給出關掉 shadow 的長期 parse(解析)→encode(重新寫回)等冪證明**(發現自己也承認「應由那條失效模式的審查給出,非本回合」)。這是覆蓋面上最大的懸空依賴(結論建立在別人還沒證明的前提上)。
7. **跨 leaf 型別(class 繼承樹最末端的具體子類別,例如界伏盒、木箱各自的實作類別)的 `Items` 以外 NBT 欄位**(如界伏盒的 `Lock`〔上鎖用的鑰匙名稱設定〕/`LootTable`〔戰利品表,決定容器第一次打開時隨機生成什麼物品的設定〕/custom name〔自訂名稱〕+ components〔Minecraft 新版資料元件系統,用來存物品/方塊的額外屬性資料〕):components 走 `this.components`(EMPTY)已提及,但 raw 往返只保 `Items`,**其餘 BE(方塊實體)欄位是否全由 vanilla saveAdditional 正常處理、與 raw 寫入無排序衝突**,僅 pick-block 一條間接觸及。

---

## 上線前必修

**(無)** — 沒有任何 critical/high,沒有掉物/毀資料路徑。嚴格說可 0 修正上線。

## 建議修(低風險、提升嚴謹度,非阻擋上線)

1. **R1 + R2 合併一個守門**:在 `lazycontainer$trySaveRaw`(`LazyContainerTemplate.java:117-141`)寫 raw 前加 `raw instanceof ListTag` 檢查;且當 `allowEmpty==false && ((ListTag)raw).isEmpty()` 時改 `out.remove("Items")`。非 ListTag 或空-list-shulker 一律退回 `ensure()` + 正常 save,逐位元組對齊 vanilla。代價:該次少省一次 encode。一個守門同時消除兩條 byte-identity 分歧。
2. **R3 防禦性硬化**:`ensure()`(`L77-91`)把 `raw=null` 延到 `loadAllItems` 成功之後(try/finally 或成功後才清),並在開頭加 `TickThread.ensureTickThread`(檢查「現在是不是在遊戲主執行緒上執行」,不是的話直接報錯,防止未來不小心從錯誤的執行緒呼叫到)斷言。守的是目前不可達的路徑,但成本近零、把不變式(程式設計中「這件事任何時候都必須成立」的假設,這裡指「raw 必須等資料讀完才能清空」)變成執行期可驗證。
3. **補 completeness(見上方「未覆蓋的失效模式」)第 5、6 項**:加一支記憶體 metric(可觀測、量化追蹤的統計指標,例如滯留 raw 數量/位元組),以及一輪「關 shadow 跑 parse→encode 等冪」的長期驗證,作為正式關 shadow 的前置條件。

## 已覆蓋無虞

- 咽喉唯一性(getItems/getContents 雙守 + private 欄位)、雙箱委派、戰利品箱與 lazy 正交、DFU 跨版本、破壞掉落/界伏盒不掉空、pick-block(含 op NBT copy)、~~執行緒安全(單主緒)~~(**⚠️ 26.2-2 已修正**,見文末)、Moonrise/Paper 底層相容 —— 共 7 個失效模式群組、約 30 條,證據鏈完整,**確認安全**。
- R1/R2 在 **shadow 模式下已自動對齊 vanilla**,預設模式的差異不掉物。

關鍵檔案:`LazyContainerAgent/template/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.java`(`trySaveRaw` L117-141、`ensure` L77-91、`eagerItems` L148-157)。

---

## 26.2-2 更正:thread-safety 那條結論在 EndRod 上不成立

> 本節是**後補的更正**,上方原文一字未刪——結論會過期,證據鏈不會,留著才知道當初漏看了什麼。

本報告(以及 `FINDINGS.md` §4)把 thread-safety 判為 0 真 bug,理由是「載入寫 / tick 讀寫 / 卸載存檔三路徑皆單一主緒」,
並把「未持鎖讀者可能讀到旗標已清、清單未填完」記成**既有設計邊界**。**推論沒錯,前提錯了**:那個前提只描述純 Paper。

在 EndRod 上:

1. **PIW(Plugin-Intent-Wins,R39)明文允許非擁有 region 的插件執行緒讀活體容器。**
2. paper-server 的 `CraftInventory.getItem` / `getContents` 是**先呼叫 NMS 的 `getItem`/`getContents`**、
   拿到結果之後才做跨區快照 → **leaf guard 與整段 `ensure()` 解碼都跑在插件執行緒上**,快照擋不住視窗。
3. `Level.getBlockEntity` 對 off-region 執行緒回傳的是**活體 BE**,不是副本。

因此舊版 `ensure()` 的「先清旗標、再逐格填」是**真 bug**,不是設計邊界:另一條執行緒的 guard 讀到 `pending==false`
就跳過物化、直接使用半填清單 → 漏斗推入的物品被覆蓋(掉物)、漏斗抽出的量被原量覆蓋(複製)、破壞少掉落、
`getState()` 快照缺物、**autosave 把半填清單編碼寫盤且之後不再重寫(磁碟永久殘缺)**。

`tests/io/github/kuohsuanlo/lazycontainer/EnsureRaceTest.java` 對舊順序實跑:**2000 輪中 1943 輪**讀到半填清單
(樣本為 27 格全空),存檔交錯測試把 27 格滿箱寫成 `Items: []`。修正後同一份測試 `bad=0`、`raced=1985`。
完整根因、JMM 論證、五條後果與紅/綠原始輸出見 [`RELEASE-NOTE-26.2-2.md`](RELEASE-NOTE-26.2-2.md)。

同一版另修 A2:摘要對「`Slot` 欄位存在但非數值」的 entry 舊版仍可標成乾淨滿堆 → 26 格滿 + 一個壞 `Slot` 會被證成
「全滿」讓漏斗永不推入;現改為整份棄答。

**留給下一輪的教訓**:本報告的每一條「已證安全」都應該連同**它假設的執行模型**一起寫下來。
「單一主緒」在這個專案裡不是事實,是一個**會隨部署核心改變的前提**。
