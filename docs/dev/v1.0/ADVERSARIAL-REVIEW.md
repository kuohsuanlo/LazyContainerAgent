# LazyContainer 對抗審查報告

> 8 失效模式 finder × 對抗驗證,共 12 agents。totalFindings=39, confirmedReal=2(皆 low、非掉資料,已修)。

The fix region is confirmed. The shadow-mode handling and the suggested fix point both match the findings' claims. I have everything I need to produce the report.

# LazyContainer Agent 對抗審查報告(合併版)

延遲容器解碼(lazy `Items` decode/encode)優化的失效模式審查。共 33 條原始發現,去重後彙整如下。

## 一、去重合併結果

原始 33 條中,有大量同主題(同 `mode`)的「已驗無虞」確認,合併為 9 個失效模式群組:

| 群組(mode) | 條目數 | 結論 | 真 bug 數 |
|---|---|---|---|
| missed-accessor(漏掉的欄位存取咽喉) | 8 | getItems()/getContents() 為唯一咽喉,private 欄位 + 精確 descriptor guard | 0 |
| double-chest(雙箱委派鏈) | 6 | CompoundContainer 全走 Container 介面,虛擬分派命中子箱 guard | 0 |
| loot-table(戰利品箱 + lazy 正交性) | 7 | LootTable 分支不被 redirect,Items 由 raw 保存,兩者 NBT 正交 | 0 |
| dfu-version(跨版本升級) | 3 | DFU 在 chunk-tag 層、BE 構造前完成,raw 恆為 post-DFU | 0 |
| block-to-item(破壞掉落/pick-block) | 6 | collectComponents/getDrops 必經 getItems() guard,界伏盒不掉空 | 0 |
| thread-safety(可見性/競態) | 1 | 三路徑(載入寫/tick 讀寫/存檔讀)皆單一主緒,無需 volatile | 0 |
| moonrise-paper-conflict(底層 chunk 機制衝突) | 5 | Moonrise 延遲 BE 物件建立,本優化延遲 items decode,兩層互補 | 0 |
| **raw-aliasing-empty(raw 別名/空容器/型別)** | **3** | **2 真 1 假** | **2** |

## 二、依嚴重度排序(僅列 real=true 及需注意者)

| # | 嚴重度 | real | 標題 | 是否掉東西/毀資料 |
|---|---|---|---|---|
| R1 | low | ✅ true | Shulker(allowEmpty=false)空 `Items:[]` 非 byte-identical:vanilla discard,本實作寫回 `Items:[]` | ❌ 不掉物(空→空) |
| R2 | low | ✅ true | trySaveRaw 對「型別錯誤的 Items」(非 ListTag)原樣回寫,vanilla 視為空 | ❌ 不掉物(反而保留原 bytes,比 vanilla 更安全) |
| R3 | low | ❌ false(原報 0.55→裁定假警報 0.82) | ensure() 內例外留下空容器(silent loss) | ❌ 路徑不可達,非真 bug |

## 三、真正會掉東西/毀資料的 critical/high

**結論:沒有任何 critical 或 high。沒有任何一條會掉物品或毀資料。**

- R1、R2 是 **byte-identity 違反(NBT 美觀差異)**,非資料風險。兩者皆:(a) 不掉物品,空/壞輸入再載入仍為空;(b) 需要**非-vanilla 來源的磁碟 NBT**(其他外掛/外部工具/舊格式/損毀)才會觸發,純 vanilla 資料不可達;(c) **shadow 模式已正確中和**(eagerItems 比對不符 → 改寫 eager → 對齊 vanilla)。差異只在預設(非 shadow)生產模式才浮現。
- R3 經第二輪裁定為**假警報**:pending 狀態只可能在 chunk 載入(server 已就緒)時產生,`getServer()==null` 不可達;`loadAllItems` 對損毀 Items 用 `ProblemReporter.DISCARDING` 吞錯不拋例外。set-flag-first 的順序隱患存在但無觸發點。

最該擔心的「界伏盒破壞掉空」情境(block-to-item 群組)已被多條獨立確認:破壞掉落必經 `getItems()` 咽喉物化,pending 界伏盒破壞時內容完整寫入掉落物。

## 四、Completeness Critic — 未覆蓋的失效模式

審查覆蓋面很廣,但以下失效模式**未見於發現清單**,建議補測:

1. **Crash / kill -9 中途存檔的原子性**:autosave 寫到一半當機,raw 路徑與 vanilla encode 路徑對 region 檔的寫入原子性是否一致?(已覆蓋正常 unload,未覆蓋非正常終止。)
2. **`/data` 指令、`BLOCK_ENTITY_DATA` 直接寫入 NBT 的反向路徑**:op 用 `/data merge block` 改 pending 容器的 Items,或 `setBlockEntityData`。pick-block 讀路徑有覆蓋,但**外部直接 NBT 寫入 + 隨後 raw 回寫**的互動未見分析。
3. **structure block / 結構方塊存讀容器**:structure save/load 走 `saveAdditional`/`loadAdditional` 還是另一條路徑?未覆蓋。
4. **`/clone` 方塊指令搬移 pending 容器**:clone 是否複製 raw/pending 合成欄位,或重走 save→load?未見。
5. **記憶體洩漏 / raw 滯留**:長期 pending 的容器持有 raw `Tag` 參照(MEMORY 中多次提及自家外掛 per-chunk heap 洩漏)。大量未開啟箱子常駐時,raw `CompoundTag` 累積的 heap 佔用 vs vanilla 立即釋放 NBT,**未做記憶體面評估**。
6. **shadow 模式關閉後的長期等冪保證**:多條發現把正規化等冪性「外包」給 shadow 模式,但**沒有一條給出關掉 shadow 的長期 parse→encode 等冪證明**(發現自己也承認「應由那條失效模式的審查給出,非本回合」)。這是覆蓋面上最大的懸空依賴。
7. **跨 leaf 型別的 `Items` 以外 NBT 欄位**(如界伏盒的 `Lock`/`LootTable`/custom name + components):components 走 `this.components`(EMPTY)已提及,但 raw 往返只保 `Items`,**其餘 BE 欄位是否全由 vanilla saveAdditional 正常處理、與 raw 寫入無排序衝突**,僅 pick-block 一條間接觸及。

---

## 上線前必修

**(無)** — 沒有任何 critical/high,沒有掉物/毀資料路徑。嚴格說可 0 修正上線。

## 建議修(低風險、提升嚴謹度,非阻擋上線)

1. **R1 + R2 合併一個守門**:在 `lazycontainer$trySaveRaw`(`LazyContainerTemplate.java:117-141`)寫 raw 前加 `raw instanceof ListTag` 檢查;且當 `allowEmpty==false && ((ListTag)raw).isEmpty()` 時改 `out.remove("Items")`。非 ListTag 或空-list-shulker 一律退回 `ensure()` + 正常 save,逐位元組對齊 vanilla。代價:該次少省一次 encode。一個守門同時消除兩條 byte-identity 分歧。
2. **R3 防禦性硬化**:`ensure()`(`L77-91`)把 `raw=null` 延到 `loadAllItems` 成功之後(try/finally 或成功後才清),並在開頭加 `TickThread.ensureTickThread` 斷言。守的是目前不可達的路徑,但成本近零、把不變式變成執行期可驗證。
3. **補 completeness 第 5、6 項**:加一支記憶體 metric(滯留 raw 數量/位元組),以及一輪「關 shadow 跑 parse→encode 等冪」的長期驗證,作為正式關 shadow 的前置條件。

## 已覆蓋無虞

- 咽喉唯一性(getItems/getContents 雙守 + private 欄位)、雙箱委派、戰利品箱與 lazy 正交、DFU 跨版本、破壞掉落/界伏盒不掉空、pick-block(含 op NBT copy)、執行緒安全(單主緒)、Moonrise/Paper 底層相容 —— 共 7 個失效模式群組、約 30 條,證據鏈完整,**確認安全**。
- R1/R2 在 **shadow 模式下已自動對齊 vanilla**,預設模式的差異不掉物。

關鍵檔案:`LazyContainerAgent/template/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.java`(`trySaveRaw` L117-141、`ensure` L77-91、`eagerItems` L148-157)。