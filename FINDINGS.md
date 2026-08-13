# LazyContainer Agent — 反編譯確認事實 + 設計定案 + 測試結果

> 對應 `PROJECT-lazy-container-deser.md`。本檔記錄「反編譯 1.21.11 Paper(mojmap,Mojang 官方公布的方法/欄位命名對照表,套用後反編譯出的程式碼會用真實方法名而非亂碼代號,較好讀)後確認的事實」、最終
> 注入設計、已建置與驗證的成果,以及上線注意事項。供接手者直接據此繼續。

## 0. 一句話結論
**已實作、已通過 JVM bytecode(Java 編譯後、由虛擬機實際執行的底層指令)驗證、已在真實 Paper 1.21.11 server 端對端驗證(零物品遺失)**的 Java agent,
把箱子/木桶/界伏盒的容器物品「載入時延遲反序列化(先不解析成遊戲內的物品清單,真的被存取時才解析)、卸載未碰時原樣回寫 raw(未解析過的原始資料,不重新編碼)」,精準攻擊 spark(Minecraft 常見的效能剖析外掛,能量出哪個方法吃掉多少主執行緒時間)量出的
~24%(載入時 decode,把存檔資料解析成物品物件的成本)+ ~11%(卸載時 encode,把物品物件轉回存檔格式的成本)主執行緒成本。**尚未上production**(需先跑 shadow 模式(讓新舊寫法同時跑、比對結果是否一致,不一致就自動退回安全寫法,細節見下)+ 對抗審查結論)。

---

## 1. 反編譯確認的事實(回答 PROJECT §8)

反編譯來源:`你的 1.21.11 伺服器 jar`(mojmap,7522 個 net.minecraft class),CFR 0.152(把 .class 編譯檔還原成可讀 Java 原始碼的反編譯工具,此處用的版本)。

### 1.1 唯一咽喉 = `getItems()`(關鍵發現)
`BaseContainerBlockEntity` 的**所有** Container accessor(讀寫容器內容的方法,不管是取物品還是放物品)都經 `this.getItems()`:
`isEmpty / getItem / removeItem / removeItemNoUpdate / setItem / clearContent / applyImplicitComponents / collectImplicitComponents`。
→ 不必逐一守 10+ 個 accessor(PROJECT §5 最大顧慮),**守一個 `getItems()` 即覆蓋全部 vanilla 讀寫**。

### 1.2 第二讀取路徑 = `getContents()`(CraftBukkit,會繞過咽喉)
每個 leaf(繼承自 BaseContainerBlockEntity 的具體容器類別,例如 Chest/Barrel/Shulker)都有 `public List<ItemStack> getContents(){ return this.items; }`(Bukkit Inventory 後備),
**直接回欄位、繞過 `getItems()`**。必須一併守。窮舉後,直接碰 items 欄位的方法只有:
`getContents / getItems / setItems / loadAdditional(或 loadFromTag) / saveAdditional`。其餘全走 `getItems()`。

### 1.3 `getContainerSize()` 不需守(結構性、天然安全)
Chest/Barrel 回傳常數 27;Shulker 回 `itemStacks.size()`——清單在載入時 `withSize(27)` 建立、即使未物化(尚未把原始存檔資料解析成真正的 ItemStack 物品物件)也是 27。
size 與內容是否物化無關,**不可守**(否則查 size 也會強制物化)。

### 1.4 載入/存檔的 NBT(Minecraft 存檔用的二進位資料格式,箱子/方塊/物品的所有資訊都存在裡面,概念上類似遊戲界的 JSON)抽象可零成本擷取/回寫 raw
- `TagValueInput`(遊戲載入資料時實際傳進來的「讀取器」物件,底層包著一份 NBT 資料)的欄位 `.input` 是 **`public final CompoundTag`**(NBT 的巢狀 key-value 結構,概念上像一個 Java Map)→ 載入時 `((TagValueInput)input).input.get("Items")` 直接拿未解碼(還沒轉成物品物件)的 `ListTag`(NBT 的陣列型別)reference(參照,不是複製一份)(近乎零成本)。
- `TagValueOutput`(存檔時實際接收資料的「寫入器」物件,對應 TagValueInput 的存檔版)的 `output` 欄位雖私有,但有 **`public CompoundTag buildResult()`**(回傳同一份底層 NBT 資料,不是複製品)→ `buildResult().put("Items", raw)` 逐位元組回寫。
- `BlockEntity.loadStatic`(行194-211)用 `TagValueInput.create(...)` 建 input → 載入端**恆為 TagValueInput**,instanceof 成立、lazy(延遲解析:先不解碼,等真的被存取才解)必生效。
- `BlockEntity.saveWithoutMetadata`(行154)**只呼 `saveAdditional` + 存 `this.components`**(一般容器為 EMPTY),**不呼 `collectImplicitComponents`** → 例行卸載存檔完全不碰 `getItems()` → pending 維持 → 走 raw 回寫。
- 重建解析用 `ProblemReporter.DISCARDING`(public no-op〔呼叫了但什麼都不做〕單例)+ `TagValueInput.createGlobal`。

### 1.5 loot table(戰利品表,例如地牢箱針對玩家生成隨機戰利品的機制)與 lazy 的關係

> ⚠️ **本節原本的結論「loot 箱永不進 lazy 路徑、兩者互斥」在 Paper 上是錯的**,已於 2026-08-14 由
> Fable 5 二輪對抗審計推翻並實機證實。錯誤來源:當時只讀了 vanilla 語意,沒有讀 Paper 的 LootTable API patch。

**原版(vanilla)語意**:leaf load 是 `if(!tryLoadLootTable(input)) loadAllItems(...)`,loot 箱的
`tryLoadLootTable` 回 true → 跳過 `loadAllItems` → 不進 lazy 路徑。

**Paper 實際語意(權威,26.2 已核對)**:Paper 的 LootTable API 把 `RandomizableContainer.tryLoadLootTable`
改成回傳 `lootTable != null && this.lootableData() == null`,而 `RandomizableContainerBlockEntity.lootableData`
是欄位初始化的 `new PaperLootableInventoryData()`、**恆非 null** ⟹ **`tryLoadLootTable` 在 Paper 上恆回 false**
⟹ **loot 箱同樣會走 `loadAllItems`(被 redirect)、同樣變成 pending**,其 raw 是一份「空的 Items 清單」
(存檔時 `trySaveLootTable` 同理回 false,於是 `LootTable` 與 `Items: []` 會同時寫進 NBT——實機 dump 已證實)。

**因此的實務後果**:任何「以 raw 判斷容器是否為空」的優化,都必須額外排除「未開封的 loot 容器」,
否則會把它誤判成空容器,壓制掉 `RandomizableContainerBlockEntity.getItem(0)` 內的 `unpackLootTable`
——漏斗將永遠抽不出未開封的戰利品箱(靜默行為回歸)。刀一(ensure 快取)即以
`lazycontainer$lootPending()`(`getLootTable() != null` 就棄答)守住這點,並有差分實機驗證。

### 1.6 Shulker 三點不同
欄位名 `itemStacks`(非 items);`saveAllItems(out, itemStacks, false)` **allowEmpty=false 三參數**;load 在 `loadFromTag`(非 loadAdditional)。

---

## 2. 最終注入設計(已實作)

**狀態存放**:splice(agent 啟動時把新欄位/方法動態「嫁接」進既有 class 的 bytecode,不需改原始碼)進 `BaseContainerBlockEntity` 兩個合成欄位
`lazycontainer$pending:boolean`(預設 false ⟹ 非目標容器/未載入前行為完全不變)、`lazycontainer$raw:net.minecraft.nbt.Tag`。
runtime 最便宜(欄位存取 O(1)、隨 BE GC)。

**邏輯**:用 plain Java `LazyContainerTemplate` 對「真實 NMS」(net.minecraft.server,Minecraft 伺服器內部真正的遊戲邏輯程式碼,跟外掛開發者常用的 Bukkit/Paper API 是不同層)編譯(編譯器驗證簽章),agent 啟動時讀其 bytecode、
把 `lazycontainer$` 欄位/方法 splice 進 BaseContainerBlockEntity(owner remap,把方法內部「這個方法屬於哪個類別」的標記改指到新位置)。比手寫 ASM(直接讀寫 Java bytecode 的函式庫,常用來做這種動態插入邏輯的工具)安全得多。

**三個 leaf 的 bytecode 改寫**(用 ASM,以 leaf 自身當 owner 去參照繼承來的成員 → 避免跨類別的 assignability(型別可指派性;JVM 驗證 bytecode 時檢查「這個型別能不能安全當成另一個型別使用」的規則)問題):
1. `getItems()/getContents()` 入口插:`if(this.lazycontainer$pending) this.lazycontainer$ensure();`
2. `setItems()` 入口插:`this.lazycontainer$pending=false; this.lazycontainer$raw=null;`(整批替換 → 清 lazy 態)
3. load/save 內的 `ContainerHelper.loadAllItems/saveAllItems` 呼叫,用 `aload0; dup_x2; pop`(這三個是 JVM bytecode 底層指令,效果是把 `this` 塞進呼叫參數最底下)把 `this` 插到引數底下,
   redirect 成 base 的 `lazycontainer$load/save/saveNoEmpty`。

**行為**:
- `lazycontainer$load`:input 是 TagValueInput → 擷取 "Items" tag 存 raw、pending=true、**跳過 decode**;否則 eager(正常路徑,照舊立刻解析、不省略步驟,安全退回)。
- `lazycontainer$ensure`:pending=false(先清,reentrancy〔同一個方法在還沒執行完時被重複呼叫〕安全);raw!=null → 用 DISCARDING 重建 ValueInput(NBT 讀取器介面,TagValueInput 就是它其中一種實作)、`loadAllItems(vi, getItems())` 解進清單。
- `lazycontainer$save/saveNoEmpty`→`trySaveRaw`:pending 且 raw!=null 且 output 是 TagValueOutput → `buildResult().put("Items", raw)`、**跳過 encode**;否則 ensure() 後走正常 saveAllItems(逐位元組等同 eager)。
- **shadow 模式**(`-Dlazycontainer.shadow=true`):寫 raw 前先 parse(解析)→encode(再編碼回去)算出 eager 結果逐位元組比對,不一致則寫 eager(安全)+ 計數告警。**亦自動涵蓋 DFU(DataFixerUpper,Mojang 用來把舊版本存檔資料結構自動升級成新版本格式的機制)情境**(見 §4)。

**安全鐵律(已落實)**:base 是 leaf 的 superclass,必先載入並 splice;splice 成功才 `injected=true`;
leaf transform(改寫 leaf 的 bytecode 這個步驟)先檢查 injected,base 沒成功就完全不動 leaf(退回純 vanilla,絕不 NoSuchMethodError);任何例外 → 回傳原 bytes。

---

## 3. 已驗證成果

### 3.1 離線 JVM bytecode 驗證
對真實 NMS bytes 跑 transformer(前面提到的改寫步驟),用 child-first(自己這層的 classloader 優先找 class,找不到才問上層,避免撞到 server 本身已載入的舊版本)的 classloader(JVM 負責把 .class 檔案載入成可執行類別的機制)搭配 `Class.forName(...,false,...)` 強制 link/verify(連結並驗證改寫後的 bytecode 合法):
Base + Chest + Barrel + Shulker **全部 VERIFIED OK**(過程中抓到並修掉一個 owner=BASE 造成的 assignability bug → 改用 leaf owner)。

### 3.2 真實 Paper 1.21.11 server 端對端(`.lctest` 最小測試服)
注入 log:`spliced 2 fields + 6 methods into BaseContainerBlockEntity` + 三 leaf `transformed`,無 VerifyError/IllegalAccessError。

- **Round-trip(資料寫出去、之後再讀回來比對,確認前後一致)正確性(3-boot)**:setblock 帶 3 物品(diamond×42、diamond_sword×1+damage:10、netherite_ingot×7)→ 存 → 重啟(lazy 載入)→ 未碰即存(raw 回寫)→ 重啟 → 讀回 = **與基準逐字相同**(僅 NBT compound 內 key 順序差,語意等同)。`stash=1, rawSave=2, ensure=0, eagerLoad=0`。
- **Ensure 路徑(漏斗觸發解碼+修改+持久化)**:漏斗推 emerald 進箱 → 觸發 `getItems()→ensure` 正確解出原 3 物品 → 加入 emerald → 重載後 4 物品全在、含 component。**證明 decode-on-access 不丟原物品、修改持久。**

→ **五條路徑全部端對端通過**:lazy 載入、raw 回寫、ensure 解碼、修改持久化、data-component 保存。

---

## 4. 風險分析與注意事項

| 項目 | 結論 |
|---|---|
| 回寫 raw 掉資料? | 不會。pending==true ⟹ 清單從未物化、無人能改 → 寫的是載入讀到的同一份 bytes。 |
| 漏攔存取點? | **已證**:getItems() 唯一咽喉 + getContents() 已守;對抗審查窮舉(8 條)確認無第三條直接欄位讀取路徑。 |
| **DFU 跨版本升級** | **複查後基本無虞**:DataFixerUpper 在「chunk NBT 整體載入時、BlockEntity 構造之前」就跑完 → loadAdditional 看到的 tag、我 stash(暫存)的 raw **恆為 post-DFU(已經跑過 DFU 升級之後的版本)**,回寫 raw == 寫已遷移資料 == vanilla。穩態 parse→encode 亦等冪(重複執行結果不變)。保險作法:升級開機那次開 `-Dlazycontainer.shadow=true`(round-trip 比對自動兜底)。 |
| 執行緒安全 | **已證**:載入寫(postLoad)、tick 讀寫、卸載存檔(processUnloads→saveChunk→copyOf)三路徑皆**單一主緒** → pending/raw 不需 volatile。 |
| 雙箱 CompoundContainer(兩格箱子併成一個大箱子時,Minecraft 用來包成單一容器操作的類別) | **已證**:CompoundContainer 全走 Container 介面、虛擬分派(呼叫方法時依物件實際型別決定要跑哪個實作,不是編譯期就決定死)命中各子箱 getItems() guard,無繞過。 |
| 記憶體 | raw 是未解碼的 ListTag(bytes),比 vanilla 載入後常駐的「已解碼 ItemStack 物件 + component map」**更小**;被存取後即丟 raw 改持有 parsed(同 vanilla)→ heap ≤ vanilla,無洩漏。 |

### 4.1 對抗審查 workflow 結論(8 失效模式 × 對抗驗證,共 ~33 條)
**0 個 critical / 0 個 high / 0 條掉資料路徑。** 7 個群組(missed-accessor、double-chest、loot-table 正交、DFU、block-to-item 破壞掉落/界伏盒不掉空、thread-safety、moonrise-paper〔Moonrise,一個改寫 Paper 底層多執行緒/區塊邏輯的效能 fork/patch 專案〕互補)全部確認安全。
僅 2 條 **low**(皆**非掉資料**、皆需「非-vanilla 來源的磁碟 NBT」才觸發、shadow 模式本已對齊):
- **R1**:shulker(allowEmpty=false)若磁碟帶空 `Items:[]`,vanilla 會 discard 該 key,本實作原樣寫回 `Items:[]` → 非 byte-identical。
- **R2**:`Items` 若是非-ListTag(損毀/外部寫入),vanilla 視為空,本實作原樣回寫 → 非 byte-identical(反而保留原 bytes,比 vanilla 更安全)。

**已修(commit 於 template)**:`trySaveRaw` 加 `canWriteRaw = raw instanceof ListTag && !(allowEmpty==false && 空清單)` 守門 —— 非 ListTag / 空清單-shulker 一律退回 ensure+正常 save → **預設(非 shadow)模式對所有輸入逐位元組對齊 vanilla**。並硬化 `ensure()`:物化失敗時還原 pending、保留 raw(try/catch)→ 永不靜默丟失(loadAllItems 是依 slot〔格子〕寫入,冪等〔重複執行結果不變〕,可安全重試)。修正後離線 JVM 驗證 + server 回歸測試皆通過。

**Completeness critic(對抗審查流程裡專門負責挑「還有什麼沒覆蓋到」的檢查角色)待補(非阻擋,建議後續)**:crash/kill-9(異常強制關閉,例如斷電或直接砍 process)存檔原子性、`/data merge block` 外部 NBT 寫入互動、structure block、`/clone`、關 shadow 的長期等冪量化。

---

## 5. 建置與部署

```bash
cd LazyContainerAgent
bash build.sh          # → target/LazyContainerAgent.jar(含 splice template + relocated ASM + agent manifest)
```
- 建置相依:`nms-lib/`(Paper 伺服器核心的 NMS 編譯相依 libraries,供 template 對真實 NMS 編譯);JDK 21。
- 啟動:`java -javaagent:LazyContainerAgent.jar -jar <Paper>.jar nogui`
- 旗標:`-Dlazycontainer.shadow=true`(上線前必跑數日,零 mismatch 再關)、`-Dlazycontainer.verbose=true`(+`-Dlazycontainer.verbose.ms=8000`)印計數。
- NMS 版本綁定:現役 1.21.11(major 65,即 class 檔案格式版本號,對應 Java 21)。升 26.1.2(Java 25/major 69)需用對應 NMS 重編 template + ASM 升到能讀 major 69 的版本(9.10.1 起)。

**建議上線節奏**:單一節點先掛 + shadow 數日(零 mismatch)→ 觀察 spark `loadAllItems`/`processUnloads` 佔比下降 → 逐步擴散。公開發佈前將環境相關路徑/位址參數化。

---

## 6. 與 PROJECT 階段 A/B 的對應
本實作是「統一版」:對**從未物化**的容器,同時達成階段 B(載入跳 decode)與階段 A(卸載跳 encode)。
一旦被存取(ensure 物化)即轉為正常 eager 存檔——保守、安全。未實作「物化後讀但未改也回寫 raw」的進階階段 A
(因清單 reference 可被外部 mutate〔修改〕,難無風險判定 dirty〔內容是否曾被改過的標記〕;spark 熱點是大量未碰容器的載卸,本設計已精準命中)。

## 7. 檔案
- `src/main/java/io/github/kuohsuanlo/lazycontainer/`:`LazyContainerAgentMain`(premain,Java agent 的進入點方法,在遊戲主程式 main() 之前、JVM 啟動階段就先被呼叫/bootstrap,即這段啟動初始化過程)、`LazyContainerRuntime`(bootstrap 純 JDK 狀態+計數)、`LazyContainerTransformer`(splice + leaf 改寫)。
- `template/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.java`:對真實 NMS 編譯的邏輯樣板(splice 來源,執行期僅被讀 bytes)。
- `build.sh`、`pom.xml`、`nms-lib/`。
- 反編譯 NMS 暫存:scratchpad `nms-src/`。
