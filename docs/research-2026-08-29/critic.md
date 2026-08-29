# 完整性批評：有具體依據的缺口

以下每條都自己重跑過（源碼行號取自 `/home/logocat/Server/claude-sandbox/reference-minecraft-nms/4-folia`，數字取自 repo 與 scratchpad 實檔）。

---

## A. 六個鏡頭與三個裁決都沒讀到的一條失效路徑（後果比假全滿嚴重一個數量級）

**A1. `computeSummary` 是載入路徑上唯一沒有 try/catch 的呼叫，而它的失效模式是「整箱內容從磁碟消失」，不是退回 eager。**

`/home/logocat/Server/claude-sandbox/workspace/LazyContainerAgent/template/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.java`：
- `:145-152` `encodeRaw` 有 `catch (Throwable)` → 退回 eager（安全）
- `:152` `this.lazycontainer$raw = encoded;`
- `:157` `lazycontainer$computeSummary(...)` — **沒有任何 try/catch**
- `:166` `pending = true`（最後一步）

⟹ 存在一個「raw 已寫、pending 仍 false」的窗口。此時拋出的三種下場：

| 觸發端 | 結果 |
|---|---|
| chunk 載入 | `BlockEntity.loadStatic` 的 `catch (Throwable t) { LOGGER.error(...); return null; }`（`net/minecraft/world/level/block/entity/BlockEntity.java:207-214`）⟹ **整個 BE 被丟掉**，箱子變空 |
| 活體 BE reload（`/data merge block`、`Chest.getState().update()` → `CraftBlockEntityState.copyData` → `loadWithComponents`） | `ChestBlockEntity.loadAdditional:127` 已把 `items` 換成全新空清單；而 `trySaveRaw` 第一行是 `if (!this.lazycontainer$pending) return false;`（template `:353`）⟹ 下一次 autosave 走 `saveAllItems(空清單)` ⟹ **磁碟上該箱清空，raw 永遠回不去** |
| `block.getState()` | `2-spigot/org/bukkit/craftbukkit/block/CraftBlockEntityState.java:44-50` snapshot=null → 拋 RuntimeException 給外掛 |

**可觸發者（源碼可證）**：`computeSummary` 呼叫 `item.getDefaultMaxStackSize()`（`net/minecraft/world/item/Item.java:160-162`）→ `components()`（`Item.java:156-158`）→ `Holder.Reference.components()` = `Objects.requireNonNull(this.components, "Components not bound yet")`（`net/minecraft/core/Holder.java:264-266`）—— **會拋 NPE**。而 vanilla 對同一情境是優雅降級：`ItemStack.MAP_CODEC` 的 id 欄位走 `Item.CODEC_WITH_BOUND_COMPONENTS`（`ItemStack.java:114` + `Item.java:104-108`），未綁定就是「這個 entry 被丟掉、該格為空」。

修法 3 行（`computeSummary` 包 try/catch → `sumState = 2`）。這條與放寬/逐格的路線選擇無關，但**放寬正好是把更多程式碼（keySet 迭代 + `Identifier.tryParse`）塞進 computeSummary**，曝險面跟著變大。

**A2. 摘要只複製了 `id` 驗證的一半。** template `:670-679` 檢查了「可 tryParse」與「非 air」，沒有複製 `areComponentsBound()`。若真踩到未綁定窗口而沒先拋，vanilla 那格是空的、摘要卻標 clean+atMax ⟹ 假全滿。

---

## B. 沒有源碼／資料支撐的結論

**B1（最嚴重）：「20 萬輪惡意 fuzz 零衝突」驗的不是要出貨的那份程式碼。**

`scratchpad/probe/WidenFuzz.java` 的 V2：
- `:31-39` 用 `Identifier.tryParse` **正規化命名空間**
- `:83` 同一容器內 max-stack key 命中 ≥2 次 ⟹ **棄權**
- `:85` 任何 `!` 移除形式 ⟹ **棄權**（不是設 max=1）
- `:87-90` 非數值／超範圍 ⟹ 棄權

工作樹 `LazyContainerTemplate.java:696-700` 三項全無（字串全等、`removal ⟹ max = 1`、無重複偵測）。**兩個是不同的程式。** 這正好解釋了「fuzz 零衝突」與「探針抓到 9-10 個 FALSE-FULL」的矛盾——沒有人做這個對帳。

延伸：裁決 #1／#2 提的修法（`removal ⟺ 值是 CompoundTag ⟹ max=1`）比 prototype 的「任何 removal 一律棄權」**更寬**，所以那 20 萬輪對新修法同樣不成立，必須重跑。

**B2：`V2 證明全滿 = 1`（20 萬輪）。** `scratchpad/probe/fuzz-result.txt` 全文四行：
```
rounds=200000
V1 證明全滿=0  V1 佔滿但不知道=23746
V2 證明全滿=1  V2 佔滿但不知道=1256
V2 與 vanilla 滿判定衝突=0  假空格=0
```
三個裁決都引「23746 → 1256（−94.7%）」當收益證據，但同一份輸出說：那 22,490 個幾乎全部轉成的是**「證明不滿（tri=0）」，不是「證明全滿」**。這與裁決 #2 的支點論證「摘要答『不滿』在解碼上省零，只有答『滿』才真的省」互斥——兩者不可能同時為真。

而 hook 的組譯（`src/main/java/io/github/kuohsuanlo/lazycontainer/LazyContainerTransformer.java:279-287`）r∈{0,1} 都是 IRETURN，所以答 0 確實省掉 `isFullContainer` 內 `:481 container.getItem(slot)` 那次解碼；只有在漏斗真的往下推到 `tryMoveInItem:589` 才會付回去。**「答 0 但 push 沒真的發生」的比例才是這次放寬的收益大宗，而它是三個裁決唯一沒有列進必量清單的數字。**

**B3：ShopFuzz 只能量 recall。** `scratchpad/probe/shop-result.txt` 第一行 `rounds=20000 vanilla 真的全滿=20000` —— 語料裡每一箱都真的滿，結構上量不到偽陽性率。用它宣稱「0% → 100%」在方法上不成立。

**B4：`logs/` 不是艦隊 log，是本機 JUnit 輸出。** 180 個檔，全部 `slow decode ... @ 0, 0, 0`，執行緒名只有 `lc-ensure-T1`/`main`，內含 `ensure-attr plugin sample: org.junit.platform.commons.util.ReflectionUtils#invokeMethod`。裁決 #1「代表帶儀器的版本根本還沒鋪到 109 台」與 #3「fullQ 是最新 commit 才加的（所以 0 次）」都是從這個語料推的——這個語料本來就不會有 `fullQ=`（測試不印 stats）。可支撐的說法只有：**這個 repo 內沒有任何生產遙測**。

**B5：`ProbeMain.java` 不存在。** `find` 全 repo 0 命中；`git ls-files tests/` 只有 `AttributionClassifyTest / EnsureRaceTest / NmsTestSupport / SummaryDifferentialTest` 四支。裁決 #1、#2 都稱它是「repo 內既有的 `tests/io/github/kuohsuanlo/lazycontainer/ProbeMain.java`」，並把 P1–P18／Q1–Q10 這組 case ID 直接寫進出貨閘門——那個語料現在無法從 repo 重現。工作樹裡的替代品是三個**未追蹤**檔（`AdjNamespaceProbeTest.java`、`AliasProbeTest.java`、`ImpactProbeTest.java`），而 `test.sh:54-57` 只 `--select-class` 了 4 個 class，這三個都沒進去。

**B6：12,754 / 76.3% 只存在於兩行註解**（`LazyContainerTemplate.java:693`、`SummaryDifferentialTest.java:277`），repo 內沒有掃描報告。裁決 #1 標了，#2／#3 仍拿它當收益論證。

---

## C. 閘門擋不住「靜默掉物品」的地方

**C1. 三套閘門全部只針對「假全滿」，A1 那條完全在射程外。** 假全滿的後果是漏斗停搬、raw 逐位元組保留、玩家一開箱就自癒；A1 的後果是磁碟上該箱直接清空。缺的閘門：(a) `computeSummary` 包 try/catch → `sumState=2`；(b) 一支測試餵「會讓 computeSummary 拋」的 raw，斷言 `load` 不拋、`pending==true`、存檔逐位元組等於輸入。

**C2. 「那筆 `bad=1` 必須先查明並結案」在現有語料上不可執行。** `logs/*.log.gz` 的 `bad=` 分佈：`bad=0`×103、`bad=1`×7、以及 `bad=1943/1950/1957/1961/1972/1974/1851/631/440/377/354/44/34`。後面那批只可能是刻意反轉修正的負控，但 `EnsureRaceTest.java:320-321` 只有**一個** println、`:331` 只有一個 `assertEquals(0, badRounds)`，log 行不帶 build/variant；而且 **logs 內沒有任何 JUnit 判決行**（`tests successful|failed` grep 0 命中）。另外 `logs/2026-08-28-112.log.gz` 的 `rounds=10 raced=0 bad=1 samples=[round#2 ... [0:empty, 1:empty, 2:empty]]` 是**三格**容器、raced=0——形狀就不是正式測試。⟹ 語料已污染，唯一可行做法是在乾淨工作樹重跑並把 variant 標進 log 行，不能把「結案舊 log」當出貨前置。

**C3. shadow 金絲雀的覆蓋率被高估，而且它會讓那台失去快路徑。** `lazycontainer$verifyFull`（template `:522-538`）在 `r >= 0` 時對 **0 和 1 兩種答案都**呼 `c.getItem(i)`，那一行必然觸發 ensure；ensure 完成後 `pending=false`，`fullState()`（template `:571-573`）之後**恆回 -1**。所以：
- 「answered full-query 樣本 ≥ 10,000」量到的是「一萬個**不同容器的首次觸碰**」，不是一萬次查詢；閘門文字把兩者混為一談。
- 開 shadow ≈ 把摘要快路徑整個關掉。金絲雀那台的漏斗解碼量會回到接近「沒裝 agent」，**絕不能挑 s45 那種分流跑**。裁決 #2 的 G9、#3 的 G8 有部分覆蓋，#1 的閘門完全沒有這個警告。

**C4. 少了第三種拼法組合的閘門。** 裁決只列了「設定 + 設定」重複。缺的是 `minecraft:max_stack_size`（設定）+ `!max_stack_size`（無命名空間移除）：vanilla 兩把 key 都會被 `Identifier.tryParse` 正規化到同一個 `PatchKey`（`net/minecraft/core/component/DataComponentPatch.java:340-355`），勝負由 `putIfAbsent` 與 NbtOps 迭代序決定；現行 template 會直接採用設定值。`WidenFuzz.java:126-127` 的 key 陣列也沒有這個組合。

---

## D. 六個鏡頭都沒讀到、但確實會碰 slot 的 NMS 路徑

不是熱路徑，但它們讓「已窮舉所有碰 slot 的路徑」這句話目前不成立：

- **`/loot insert <pos>`**：`net/minecraft/server/commands/LootCommand.java:261`（`getContainer`）→ `:283 distributeToContainer` → `:287 container.getItem(slot)`、`:299 current.grow(count)`、`:290/:325 container.setItem`。`:299` 是**就地改動 `getItem` 回傳物件而不呼 setItem**——這是繼 `HopperBlockEntity.java:610`、`ContainerHelper.java:14`、`HopperBlockEntity.java:285` 之後的**第四個**同族點，逐格設計的 INV-6（decoded ⟹ dirty）要把它算進去。
- **`/item replace block` / `/item modify block`**：`net/minecraft/server/commands/ItemCommands.java:375-378 getContainer` → `Container`。

---

## E. 我重驗過、確認沒有漏的（省得再查）

- **兩個 hook 的爆炸半徑確實只有漏斗推／抽**：全 4-folia 樹 `isFullContainer` 只有一個呼叫端 `HopperBlockEntity.java:439`，`tryTakeInItemFromSlot` 只有一個呼叫端 `:500`。
- **沒有繞過 guard 的欄位直讀面**：`items`/`itemStacks` 在整個 4-folia + 2-spigot 只出現在三個 leaf 自己身上（Chest `78/127/129/137/195/200`、Barrel `33/106/113/115/126/131`、Shulker `59/172/253/258/260/266/271`），而三個 leaf **都有** `getContents()` 覆寫（Chest`:77`、Barrel`:32`、Shulker`:58`）⟹ `CraftInventory.java:81` 那條進得了 guard。Shulker`:172` 是 `itemStacks.size()`，只讀大小。
- **`count > max` 不會讓 entry 消失**：`ItemStack.MAP_CODEC`（`ItemStack.java:110-120`）沒有 `.validate(validateStrict)`；`validateStrict` 是 `:160-168` 的獨立方法。components 鏡頭這條正確。
- **`count` 欄位名確為小寫**（`ItemStack.java:115`），template `t.get("count")` 對。
- 三條紅線引用都正確：`ShulkerBoxBlock.java:113 !shulkerBoxBlockEntity.isEmpty()`、`getFullState` 直讀 `hopper.items`（`HopperBlockEntity.java:163`）、`tryMoveInItem` 的 `:589 getItem` 在 `:592 isEmpty` **之前**（裁決 #2 對 design 鏡頭的更正成立）。

---

## 一句話

最大的缺口不是路線選錯，是**證據與程式碼對不上**：出貨候選的放寬規則從來沒有被那 20 萬輪 fuzz 驗過（B1），它被引用的收益數字自己就說「證明全滿只有 1 次」（B2），而唯一能讓箱子在磁碟上整箱消失的路徑（A1）不在任何一套閘門裡。