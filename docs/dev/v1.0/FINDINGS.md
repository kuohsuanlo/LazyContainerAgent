# LazyContainer Agent — 反編譯確認事實 + 設計定案 + 測試結果

> 對應 `PROJECT-lazy-container-deser.md`。本檔記錄「反編譯 1.21.11 Paper(mojmap)後確認的事實」、最終
> 注入設計、已建置與驗證的成果,以及上線注意事項。供接手者直接據此繼續。

## 0. 一句話結論
**已實作、已通過 JVM bytecode 驗證、已在真實 Paper 1.21.11 server 端對端驗證(零物品遺失)**的 Java agent,
把箱子/木桶/界伏盒的容器物品「載入時延遲反序列化、卸載未碰時原樣回寫 raw」,精準攻擊 spark 的
~24%(載入 decode)+ ~11%(卸載 encode)主執行緒成本。**尚未上production**(需先跑 shadow + 對抗審查結論)。

---

## 1. 反編譯確認的事實(回答 PROJECT §8)

反編譯來源:`你的 1.21.11 伺服器 jar`(mojmap,7522 個 net.minecraft class),CFR 0.152。

### 1.1 唯一咽喉 = `getItems()`(關鍵發現)
`BaseContainerBlockEntity` 的**所有** Container accessor 都經 `this.getItems()`:
`isEmpty / getItem / removeItem / removeItemNoUpdate / setItem / clearContent / applyImplicitComponents / collectImplicitComponents`。
→ 不必逐一守 10+ 個 accessor(PROJECT §5 最大顧慮),**守一個 `getItems()` 即覆蓋全部 vanilla 讀寫**。

### 1.2 第二讀取路徑 = `getContents()`(CraftBukkit,會繞過咽喉)
每個 leaf(Chest/Barrel/Shulker)都有 `public List<ItemStack> getContents(){ return this.items; }`(Bukkit Inventory 後備),
**直接回欄位、繞過 `getItems()`**。必須一併守。窮舉後,直接碰 items 欄位的方法只有:
`getContents / getItems / setItems / loadAdditional(或 loadFromTag) / saveAdditional`。其餘全走 `getItems()`。

### 1.3 `getContainerSize()` 不需守(結構性、天然安全)
Chest/Barrel 回傳常數 27;Shulker 回 `itemStacks.size()`——清單在載入時 `withSize(27)` 建立、即使未物化也是 27。
size 與內容是否物化無關,**不可守**(否則查 size 也會強制物化)。

### 1.4 載入/存檔的 NBT 抽象可零成本擷取/回寫 raw
- `TagValueInput.input` 是 **`public final CompoundTag`** → 載入時 `((TagValueInput)input).input.get("Items")` 直接拿未解碼 `ListTag` reference(近乎零成本)。
- `TagValueOutput` 的 `output` 雖私有,但有 **`public CompoundTag buildResult()`**(回傳同一個 backing tag)→ `buildResult().put("Items", raw)` 逐位元組回寫。
- `BlockEntity.loadStatic`(行194-211)用 `TagValueInput.create(...)` 建 input → 載入端**恆為 TagValueInput**,instanceof 成立、lazy 必生效。
- `BlockEntity.saveWithoutMetadata`(行154)**只呼 `saveAdditional` + 存 `this.components`**(一般容器為 EMPTY),**不呼 `collectImplicitComponents`** → 例行卸載存檔完全不碰 `getItems()` → pending 維持 → 走 raw 回寫。
- 重建解析用 `ProblemReporter.DISCARDING`(public no-op 單例)+ `TagValueInput.createGlobal`。

### 1.5 loot table 與 lazy 互斥(無交互)
leaf load:`if(!tryLoadLootTable(input)) loadAllItems(...)`。loot 箱 → tryLoadLootTable 回 true → 永不進 lazy 路徑。
一般儲存箱 → 進 lazy。兩者不重疊。`RandomizableContainerBlockEntity` 的 unpackLootTable accessor 守 index==0 是 loot 機制,與 lazy 正交(我的 guard 在 `getItems()`,每個 index 都觸發)。

### 1.6 Shulker 三點不同
欄位名 `itemStacks`(非 items);`saveAllItems(out, itemStacks, false)` **allowEmpty=false 三參數**;load 在 `loadFromTag`(非 loadAdditional)。

---

## 2. 最終注入設計(已實作)

**狀態存放**:splice 進 `BaseContainerBlockEntity` 兩個合成欄位
`lazycontainer$pending:boolean`(預設 false ⟹ 非目標容器/未載入前行為完全不變)、`lazycontainer$raw:net.minecraft.nbt.Tag`。
runtime 最便宜(欄位存取 O(1)、隨 BE GC)。

**邏輯**:用 plain Java `LazyContainerTemplate` 對「真實 NMS」編譯(編譯器驗證簽章),agent 啟動時讀其 bytecode、
把 `lazycontainer$` 欄位/方法 splice 進 BaseContainerBlockEntity(owner remap)。比手寫 ASM 安全得多。

**三個 leaf 的 bytecode 改寫**(ASM,以 leaf 自身為 owner 參照繼承成員 → 免跨類 assignability):
1. `getItems()/getContents()` 入口插:`if(this.lazycontainer$pending) this.lazycontainer$ensure();`
2. `setItems()` 入口插:`this.lazycontainer$pending=false; this.lazycontainer$raw=null;`(整批替換 → 清 lazy 態)
3. load/save 內的 `ContainerHelper.loadAllItems/saveAllItems` 呼叫,用 `aload0; dup_x2; pop` 把 `this` 插到引數底下,
   redirect 成 base 的 `lazycontainer$load/save/saveNoEmpty`。

**行為**:
- `lazycontainer$load`:input 是 TagValueInput → 擷取 "Items" tag 存 raw、pending=true、**跳過 decode**;否則 eager(安全退回)。
- `lazycontainer$ensure`:pending=false(先清,reentrancy 安全);raw!=null → 用 DISCARDING 重建 ValueInput、`loadAllItems(vi, getItems())` 解進清單。
- `lazycontainer$save/saveNoEmpty`→`trySaveRaw`:pending 且 raw!=null 且 output 是 TagValueOutput → `buildResult().put("Items", raw)`、**跳過 encode**;否則 ensure() 後走正常 saveAllItems(逐位元組等同 eager)。
- **shadow 模式**(`-Dlazycontainer.shadow=true`):寫 raw 前先 parse→encode 算 eager 結果逐位元組比對,不一致則寫 eager(安全)+ 計數告警。**亦自動涵蓋 DFU 情境**(見 §4)。

**安全鐵律(已落實)**:base 是 leaf 的 superclass,必先載入並 splice;splice 成功才 `injected=true`;
leaf transform 先檢查 injected,base 沒成功就完全不動 leaf(退回純 vanilla,絕不 NoSuchMethodError);任何例外 → 回傳原 bytes。

---

## 3. 已驗證成果

### 3.1 離線 JVM bytecode 驗證
對真實 NMS bytes 跑 transformer,child-first classloader `Class.forName(...,false,...)` 強制 link/verify:
Base + Chest + Barrel + Shulker **全部 VERIFIED OK**(過程中抓到並修掉一個 owner=BASE 造成的 assignability bug → 改用 leaf owner)。

### 3.2 真實 Paper 1.21.11 server 端對端(`.lctest` 最小測試服)
注入 log:`spliced 2 fields + 6 methods into BaseContainerBlockEntity` + 三 leaf `transformed`,無 VerifyError/IllegalAccessError。

- **Round-trip 正確性(3-boot)**:setblock 帶 3 物品(diamond×42、diamond_sword×1+damage:10、netherite_ingot×7)→ 存 → 重啟(lazy 載入)→ 未碰即存(raw 回寫)→ 重啟 → 讀回 = **與基準逐字相同**(僅 NBT compound 內 key 順序差,語意等同)。`stash=1, rawSave=2, ensure=0, eagerLoad=0`。
- **Ensure 路徑(漏斗觸發解碼+修改+持久化)**:漏斗推 emerald 進箱 → 觸發 `getItems()→ensure` 正確解出原 3 物品 → 加入 emerald → 重載後 4 物品全在、含 component。**證明 decode-on-access 不丟原物品、修改持久。**

→ **五條路徑全部端對端通過**:lazy 載入、raw 回寫、ensure 解碼、修改持久化、data-component 保存。

---

## 4. 風險分析與注意事項

| 項目 | 結論 |
|---|---|
| 回寫 raw 掉資料? | 不會。pending==true ⟹ 清單從未物化、無人能改 → 寫的是載入讀到的同一份 bytes。 |
| 漏攔存取點? | **已證**:getItems() 唯一咽喉 + getContents() 已守;對抗審查窮舉(8 條)確認無第三條直接欄位讀取路徑。 |
| **DFU 跨版本升級** | **複查後基本無虞**:DataFixerUpper 在「chunk NBT 整體載入時、BlockEntity 構造之前」就跑完 → loadAdditional 看到的 tag、我 stash 的 raw **恆為 post-DFU**,回寫 raw == 寫已遷移資料 == vanilla。穩態 parse→encode 亦等冪。保險作法:升級開機那次開 `-Dlazycontainer.shadow=true`(round-trip 比對自動兜底)。 |
| 執行緒安全 | **已證**:載入寫(postLoad)、tick 讀寫、卸載存檔(processUnloads→saveChunk→copyOf)三路徑皆**單一主緒** → pending/raw 不需 volatile。 |
| 雙箱 CompoundContainer | **已證**:CompoundContainer 全走 Container 介面、虛擬分派命中各子箱 getItems() guard,無繞過。 |
| 記憶體 | raw 是未解碼的 ListTag(bytes),比 vanilla 載入後常駐的「已解碼 ItemStack 物件 + component map」**更小**;被存取後即丟 raw 改持有 parsed(同 vanilla)→ heap ≤ vanilla,無洩漏。 |

### 4.1 對抗審查 workflow 結論(8 失效模式 × 對抗驗證,共 ~33 條)
**0 個 critical / 0 個 high / 0 條掉資料路徑。** 7 個群組(missed-accessor、double-chest、loot-table 正交、DFU、block-to-item 破壞掉落/界伏盒不掉空、thread-safety、moonrise-paper 互補)全部確認安全。
僅 2 條 **low**(皆**非掉資料**、皆需「非-vanilla 來源的磁碟 NBT」才觸發、shadow 模式本已對齊):
- **R1**:shulker(allowEmpty=false)若磁碟帶空 `Items:[]`,vanilla 會 discard 該 key,本實作原樣寫回 `Items:[]` → 非 byte-identical。
- **R2**:`Items` 若是非-ListTag(損毀/外部寫入),vanilla 視為空,本實作原樣回寫 → 非 byte-identical(反而保留原 bytes,比 vanilla 更安全)。

**已修(commit 於 template)**:`trySaveRaw` 加 `canWriteRaw = raw instanceof ListTag && !(allowEmpty==false && 空清單)` 守門 —— 非 ListTag / 空清單-shulker 一律退回 ensure+正常 save → **預設(非 shadow)模式對所有輸入逐位元組對齊 vanilla**。並硬化 `ensure()`:物化失敗時還原 pending、保留 raw(try/catch)→ 永不靜默丟失(loadAllItems 依 slot set 冪等,可安全重試)。修正後離線 JVM 驗證 + server 回歸測試皆通過。

**Completeness critic 待補(非阻擋,建議後續)**:crash/kill-9 存檔原子性、`/data merge block` 外部 NBT 寫入互動、structure block、`/clone`、關 shadow 的長期等冪量化。

---

## 5. 建置與部署

```bash
cd LazyContainerAgent
bash build.sh          # → target/LazyContainerAgent.jar(含 splice template + relocated ASM + agent manifest)
```
- 建置相依:`nms-lib/`(Paper 伺服器核心的 NMS 編譯相依 libraries,供 template 對真實 NMS 編譯);JDK 21。
- 啟動:`java -javaagent:LazyContainerAgent.jar -jar <Paper>.jar nogui`
- 旗標:`-Dlazycontainer.shadow=true`(上線前必跑數日,零 mismatch 再關)、`-Dlazycontainer.verbose=true`(+`-Dlazycontainer.verbose.ms=8000`)印計數。
- NMS 版本綁定:現役 1.21.11(major 65/Java 21)。升 26.1.2(Java 25/major 69)需用對應 NMS 重編 template + ASM 升到能讀 major 69(9.10.1 起)。

**建議上線節奏**:單一節點先掛 + shadow 數日(零 mismatch)→ 觀察 spark `loadAllItems`/`processUnloads` 佔比下降 → 逐步擴散。公開發佈前將環境相關路徑/位址參數化。

---

## 6. 與 PROJECT 階段 A/B 的對應
本實作是「統一版」:對**從未物化**的容器,同時達成階段 B(載入跳 decode)與階段 A(卸載跳 encode)。
一旦被存取(ensure 物化)即轉為正常 eager 存檔——保守、安全。未實作「物化後讀但未改也回寫 raw」的進階階段 A
(因清單 reference 可被外部 mutate,難無風險判定 dirty;spark 熱點是大量未碰容器的載卸,本設計已精準命中)。

## 7. 檔案
- `src/main/java/io/github/kuohsuanlo/lazycontainer/`:`LazyContainerAgentMain`(premain/bootstrap)、`LazyContainerRuntime`(bootstrap 純 JDK 狀態+計數)、`LazyContainerTransformer`(splice + leaf 改寫)。
- `template/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.java`:對真實 NMS 編譯的邏輯樣板(splice 來源,執行期僅被讀 bytes)。
- `build.sh`、`pom.xml`、`nms-lib/`。
- 反編譯 NMS 暫存:scratchpad `nms-src/`。
