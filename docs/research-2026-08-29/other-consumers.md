# other-consumers

**結論**:從「漏斗以外」這個鏡頭看,逐格解碼幾乎賺不到增量收益 —— 比較器(AbstractContainerMenu.java:896-901,無 early exit)、玩家開箱(broadcastChanges:259-266 每 tick 全掃)、破壞掉落(Containers.java:22-26)、Bukkit getContents(CraftInventory.java:80-84)四大戶全部必然整份,逐格只是把同樣的 27 格拆成 27 次做;唯一新賺到的是 26.2 銅傀儡(TransportItemsBetweenContainers,走 Container.ContainerIterator 逐格早退)、Bukkit getItem(index) 與 isEmpty(),都是小戶。而代價是把「半填清單」從競態窗口變成常態,那正是 26.2-2 實測會把滿箱存成 Items:[] 的那條路。結論:值得做,但它的正當理由是漏斗、不是漏斗以外的人,而且紅線必須是「逐格只開在 getItem(slot) 這一層,getItems()/getContents() 補齊才交出清單」。

## 硬事實
- 比較器必然整份掃描:AbstractContainerMenu.java:889-905 的 getRedstoneSignalFromContainer 是 `for (i=0; i<getContainerSize(); i++) getItem(i)` 累加填充率,無任何 early exit —— 少看一格答案就錯。逐格解碼對比較器造成的解碼零收益。
- 單次比較器更新最多做 4 遍 27 格全掃:ComparatorBlock.checkTickOnNeighbor:148-159(calculateOutputSignal→getInputSignal:100-104 一遍;短路不成立時 shouldTurnOn:87-95 再一遍)+ 兩 tick 後 ComparatorBlock.tick:194-196 → refreshOutputState:161-190 再重複一次。
- 比較器更新的觸發源就是漏斗:BlockEntity.setChanged:224-229 每次都呼 level.updateNeighbourForOutputSignal —— 漏斗每搬一次物品,鄰居比較器就重算。
- 玩家開箱必然整份:AbstractContainerMenu.broadcastChanges:259-266 每 tick 走完全部 slot;broadcastFullState:278-283 開啟時全量。Slot.getItem:48-50 雖是逐格,實務上第一 tick 就全解。
- Bukkit 全部 Inventory 讀取 API 都是整份:CraftInventory.getContents:80-84 取得活體清單後由 asCraftMirror:56-66 用 mcItems.get(i) 走完全部(繞過 getItem);contains/all/first/firstEmpty/removeItem(:105-390)一律先呼 getStorageContents。唯一逐格的是 getItem(index):52-55。
- 破壞掉落必然整份:BlockEntity.preRemoveSideEffects:259-262 → Containers.dropContents:22-26 逐格 getItem 走完 size;界伏盒走 ShulkerBoxBlock.playerWillDestroy:110-124 → BaseContainerBlockEntity.collectImplicitComponents:162-170 的 ItemContainerContents.fromItems(getItems())。
- pick-block(帶資料)必然整份:ServerGamePacketListenerImpl.addBlockDataToItem:1083-1093 先 saveCustomOnly(便宜的 raw 路徑)、再 removeComponentsFromTag 把 Items 丟掉、最後 collectComponents() 從 getItems() 全部重收。
- 只有五處會拿到 getItems() 回傳的 NonNullList 本體:CraftInventory.getContents(2-spigot:80-84,方法內)、ChestBlockEntity.swapContents(4-folia:214-219,永久換手,唯一呼叫者是舊世界升級 UpgradeData.java:385)、applyImplicitComponents(BaseContainerBlockEntity.java:154-159)、collectImplicitComponents(:162-170)、ContainerHelper.loadAllItems/saveAllItems(ContainerHelper.java:21-46)。除 swapContents 外沒有任何路徑長期持有引用。
- NonNullList extends AbstractList 且未覆寫 iterator/forEach/stream(NonNullList.java:10-76)—— 覆寫 get(int) 能攔到 enhanced-for,但 §4 那五處會走完整條清單,所以半填狀態不可外洩。
- 26.2 新增銅傀儡是漏斗以外唯一「形狀像漏斗」的容器讀者:TransportItemsBetweenContainers.java(唯一使用者 CopperGolemAi.java)。pickupItemFromContainer:544-556 第一個非空格就 return;addItemsToContainer:559-583 第一個空格就 return;hasItemMatchingHandItem:514-523 早退。全部走 Container.ContainerIterator(Container.java:118-141)= 逐格 getItem,不持有清單。
- 銅傀儡找箱子的階段完全不解碼:getTransportTarget:277-305 掃遍周圍 chunk 的所有 ChestBlockEntity,只讀方塊狀態與 isContainerLocked:339-341(→ BaseContainerBlockEntity.isLocked:85-87 讀 lockKey 欄位)。只有走到箱子前 doReachedTargetInteraction:264-274 才呼 container.isEmpty()。
- getState() 快照全程 lazy:CraftBlockEntityState 建構子:44-50 → createSnapshot:78-84 用 saveWithFullMetadata(走 raw 回寫)+ BlockEntity.loadStatic:193-215 → loadWithComponents:118-121 → loadAdditional(重新變 pending)。loadWithComponents 不呼 applyImplicitComponents。
- 漏斗事件的 Bukkit Inventory 是免費的:HopperBlockEntity.getInventory:372-386 用 blockEntity.getOwner(false)(BlockEntity.java:377-382 → CraftBlockStates.java:193-209 設 DISABLE_SNAPSHOT=true)= 活體包裝,不建快照、不碰任何格。只有外掛真的呼 getContents() 才解碼。對比:DropperBlock.java:76 與 CrafterBlock.java:216 用的是 getOwner()(有快照),但快照本身也走 raw→raw。
- /clone 兩端都 lazy:來源 CloneCommands.java:222-226 用 saveCustomOnly、目的地 :277-279 用 loadCustomOnly(TagValueInput)→ 重新變 pending。structure block 存檔 StructureTemplate.java:120-124 用 saveWithId 同樣走 raw。
- 箱子的 Items 從不走網路:BlockEntity.getUpdateTag:243-245 回傳空 CompoundTag,Chest/Barrel/Shulker 都沒覆寫。客戶端同步零成本。
- 漏斗推入路徑每格呼一次 isEmpty():HopperBlockEntity.tryMoveInItem:591 的 `boolean wasEmpty = container.isEmpty();`,而 tryMoveInItem 被 addItem:556-571 逐格呼叫。isEmpty()(BaseContainerBlockEntity.java:93-101)早退,所以幾乎空的箱子會被走到底。
- 漏斗三個入口都早退,這是逐格的真收益來源:isFullContainer:477-488(第一個沒滿的格就 return false)、suckInItems:490-520(第一個抽得動的格就 return true)、tryTakeInItemFromSlot:522-529(只碰指定 slot)。
- loot 展開只在 slot 0 觸發:RandomizableContainerBlockEntity.getItem:55-57 是 `if (slot == 0) this.unpackLootTable(null);`。逐格設計若讓漏斗跳過 slot 0,未開封戰利品箱將永遠抽不出來。
- HopperBlockEntity.allMatch/anyMatch(:393-430)在 26.2 這份源碼裡定義了但零呼叫點(死碼);anyMatch 迴圈跑完 return true(:425)是上游筆誤,因無人呼叫故無影響。
- ShulkerBoxBlockEntity 是 WorldlyContainer(:35),漏斗對它走 getSlotsForFace:275-277 回傳 SLOTS(全 27 格);Chest/Barrel 不是,走 HopperBlockEntity.getSlots:446-464 的 CACHED_SLOTS 平坦陣列。三者容量都是 27(ChestBlockEntity.java:31、BarrelBlockEntity.java:62、ShulkerBoxBlockEntity.java:45)。
- 現行 runtime 已有分桶計數器可直接回答「漏斗以外是誰」:LazyContainerRuntime.java:281-301 的 hopper/comparator/quickshop/save/drop/plugin/player/vanilla 八桶,:463-469 會印出來,:329 會印 vanilla/plugin 桶前 30 個未見過的觸發點。

## 風險
- 半填清單外洩會直接掉玩家東西,而且逐格會把它從競態窗口變成常態。26.2-2 已實測:2000 輪中 1943 輪讀到半填,最壞把 27 格滿箱存成 Items:[]。逐格版本的唯一不變式必須是「getItems()/getContents() 補齊才交出」。
- 最致命的單點是 ShulkerBoxBlock.java:113 的 !shulkerBoxBlockEntity.isEmpty() —— 半填清單被判成空,界伏盒會掉成空盒,整箱東西蒸發。isEmpty() 必須接摘要或強制補齊。
- ContainerHelper.saveAllItems(ContainerHelper.java:25-38)拿到半填清單會寫出殘缺磁碟資料,而 autosave 對「純讀不弄髒」的 chunk 之後不再重寫 ⟹ 永久殘缺,事後無法察覺。
- CraftInventory.getContents()(2-spigot:80-84 → asCraftMirror:56-66)拿到活體清單後用 mcItems.get(i) 直接索引,完全繞過 getItem 這一層 —— 任何「只在 getItem 攔截」的逐格設計都攔不到它。
- 漏斗推入路徑的 container.isEmpty()(HopperBlockEntity.java:591)在幾乎空的箱子上會走到底,而且被 addItem:556-571 逐格重複呼叫最多 27 次。逐格設計若不先處理 isEmpty(),推入路徑會變成新的解碼熱點,比現在更糟。
- RandomizableContainerBlockEntity.getItem:55-57 只在 slot == 0 展開 loot table。逐格 + 摘要跳格若跳掉 slot 0,未開封戰利品箱會永遠抽不出來 —— 靜默行為回歸,不會有任何錯誤訊息。
- 逐格解碼要求「解出來的 ItemStack 必須寫回清單」,因為 hopperPull:279-320、tryMoveInItem:586-628、ContainerHelper.removeItem:13-15 都就地改動回傳的物件(setCount/split/grow)。回傳一份不在清單裡的複本 = 掉物或複製。
- 比較器投入產出比是負的:必然整份(AbstractContainerMenu.java:896-901)、單次更新最多 4 遍全掃、觸發源是漏斗流量本身。把工程力氣放在逐格而不是比較器摘要,可能整個押錯方向 —— 決策前必須先看線上 attrComparator 的實際數字。
- 銅傀儡的收益是假設性的:TransportItemsBetweenContainers 存在於 26.2 源碼不代表艦隊上有人在養銅傀儡。動手前必須從 attrVanilla 桶的 sample 印出確認,否則整個 §2 的非漏斗收益只剩 getItem(index) 和 isEmpty()。
- CraftBlockEntityState.DISABLE_SNAPSHOT 是 static 非 volatile 欄位,CraftBlockStates.java:200-207 在呼叫前後修改它。Folia/EndRod 多執行緒下這是既有資料競態(非本專案造成),但若逐格設計依賴「getState 一定走快照」的假設就會踩到。
- ChestBlockEntity.swapContents:214-219 把整條 NonNullList 永久換手給另一個 BE,是唯一引用逃逸出方法邊界的路徑。生產上只有舊世界升級 UpgradeData.java:385 會呼(等於死碼),但逐格版本的 bitmask 必須跟著清單一起搬,否則升級舊世界時會出現「bitmask 說已解、清單其實是別人的」的錯配。
- 本輪只查了 NMS/CraftBukkit 層,沒有查艦隊 109 台上的自有外掛。事件物件本身免費已從源碼證明,但有多少外掛在 InventoryMoveItemEvent 裡呼 getSource().getContents() 是純外掛行為問題,需要 grep 自有 repo 才能回答。

## 報告
# 鏡頭:漏斗以外,誰會碰容器 slot?逐格解碼對他們有沒有用?

> 全部結論都指到 `/home/logocat/Server/claude-sandbox/reference-minecraft-nms/4-folia`(EndRod 跑的那層)與 `2-spigot`(CraftBukkit 層)的真實源碼行號。沒有一條是憑印象。

---

## 0. 一句話

**逐格解碼對「漏斗以外」幾乎賺不到東西**:比較器、玩家開箱、破壞掉落、Bukkit `getContents()` 這四大戶**全部是必然整份掃描**,逐格只是把同樣的 27 格拆成 27 次來做,總工作量不變、還多付每格一次的簿記成本。真正因為逐格而受益的非漏斗路徑只有三個小戶:**26.2 新增的銅傀儡**、**Bukkit 的 `getItem(index)`**、**`isEmpty()`**。

而代價很硬:逐格解碼會讓「清單只解了一半」從**競態窗口**變成**常態**,而半填清單外洩正是 26.2-2 已經實測會把 27 格滿箱寫成 `Items: []` 的那條路(2000 輪中 1943 輪讀到半填)。所以如果要做,紅線是:**逐格只能開在 `getItem(slot)` 這一層,`getItems()`/`getContents()` 這兩個咽喉必須繼續「補齊全部才交出清單」**。

---

## 1. 分類 A:必然整份 —— 逐格解碼收益 = 0

這些路徑不管你怎麼設計,最後都會把每一格都要到手。

| 路徑 | 檔名:行號 | 碰哪些格 | 必然整份? |
|---|---|---|---|
| **比較器讀容器** | `net/minecraft/world/inventory/AbstractContainerMenu.java:889-905` | `for (i=0; i<getContainerSize(); i++) getItem(i)`,**無 early exit** | ✅ **必然** |
| 玩家開箱每 tick 同步 | `AbstractContainerMenu.java:259-266` (`broadcastChanges`) | 全部 slot,**每 tick 一輪** | ✅ |
| 玩家開箱第一次全量推送 | `AbstractContainerMenu.java:278-283` (`broadcastFullState`) | 全部 slot | ✅ |
| Bukkit `getContents()`/`getStorageContents()` | `2-spigot/.../inventory/CraftInventory.java:80-84` → `asCraftMirror` `:56-66` | **拿到活體清單後直接 `mcItems.get(i)` 走完全部**,繞過 `getItem` | ✅ |
| Bukkit `contains/all/first/firstEmpty/removeItem` | `CraftInventory.java:105-279, 358-390` | 一律先呼 `getStorageContents()` | ✅ |
| 雙箱 `getContents()` | `net/minecraft/world/CompoundContainer.java:15-21` | 逐格 `getItem(i)` 但走滿 54 格、兩半都解 | ✅ |
| 破壞掉落(箱/桶) | `BlockEntity.java:259-262` → `Containers.java:22-26` | `for i in 0..size: dropItemStack(getItem(i))` | ✅ |
| 破壞界伏盒(收成物品) | `ShulkerBoxBlock.java:110-124` → `BaseContainerBlockEntity.java:162-170` (`ItemContainerContents.fromItems`) | 全部 | ✅ |
| 界伏盒 loot 動態掉落 | `ShulkerBoxBlock.java:139-143` | `for i in 0..size: output.accept(getItem(i))` | ✅ |
| loot table `copy_components` | `CopyComponentsFunction.java:110` → `collectComponents` | 全部 | ✅ |
| **pick-block(創造中鍵帶資料)** | `ServerGamePacketListenerImpl.java:1083-1093` | `saveCustomOnly`(便宜)→ `removeComponentsFromTag` 把 Items 丟掉 → `collectComponents()` 全部重收 | ✅ |
| 元件寫回容器(放置帶 CONTAINER 的方塊) | `BaseContainerBlockEntity.java:154-159`(`copyInto(getItems())`) | 全格**寫入** | ✅ |
| 舊世界升級併箱 | `ChestBlockEntity.java:214-219` (`swapContents`) ← 只有 `UpgradeData.java:385` 呼叫 | 整條清單換手 | ✅(且是引用逃逸,見 §3) |
| `clearContent()` | `BaseContainerBlockEntity.java:137-139` | `getItems().clear()` | ✅ |

### 1.1 比較器專章:是的,必然整份,而且一次不只掃一遍

`AbstractContainerMenu.getRedstoneSignalFromContainer` (`AbstractContainerMenu.java:889-905`) 的演算法本身就要求全掃:

```java
for (int i = 0; i < container.getContainerSize(); i++) {
    ItemStack itemStack = container.getItem(i);
    if (!itemStack.isEmpty()) {
        totalPercent += (float)itemStack.getCount() / container.getMaxStackSize(itemStack);
    }
}
totalPercent /= container.getContainerSize();
```

它要算的是「平均填充率」,少看一格答案就錯。**沒有任何 early exit 的可能**,所以逐格解碼對比較器造成的解碼**零收益**——單箱 27 格、雙箱 54 格(兩半都解),一格都省不掉。

更糟的是「一次比較器更新」不只掃一遍:

- `ComparatorBlock.checkTickOnNeighbor:148-159` → `calculateOutputSignal` → `getInputSignal:100-104` → **第 1 遍全掃**;若訊號沒變,`||` 右側的 `shouldTurnOn:87-95` 會再呼一次 `getInputSignal` → **第 2 遍**。
- 兩 tick 後排程 tick 觸發 `ComparatorBlock.tick:194-196` → `refreshOutputState:161-190` → `calculateOutputSignal` → **第 3 遍**;條件成立時 `shouldTurnOn` → **第 4 遍**。

也就是**單次比較器更新最多 4 次 27 格全掃**。

而觸發頻率是關鍵:`BlockEntity.setChanged` (`BlockEntity.java:224-229`) 每次都呼 `level.updateNeighbourForOutputSignal(...)`——也就是**漏斗每搬一次物品,旁邊的比較器就重算一次**。所以「比較器」這個桶的解碼量,實際上是漏斗流量的附帶產物。

**給服主的可操作結論**:比較器要省,只能走「摘要」路線(從 raw 直接算出 0–15 的訊號值),不能走逐格路線。而且摘要要算對訊號,需要每格的 `count` 與 `max_stack_size`,判定條件會比現在的「滿/不滿」更嚴 —— 這是另一把刀,不是這一把。

現行版本已經有 `attrComparator` 計數器(`LazyContainerRuntime.java:283, 463`),服主可以直接看線上數字判斷比較器到底佔多少:

```
grep "LazyContainer" latest.log | tail -1      # 看 attrHopper= / attrComparator= / attrVanilla= …
```

---

## 2. 分類 B:逐格 + 早退 —— 逐格解碼真的有用

| 路徑 | 檔名:行號 | 碰哪些格 | 必然整份? |
|---|---|---|---|
| 漏斗 `isFullContainer` | `HopperBlockEntity.java:477-488` | 遇到第一個「沒滿」的格就 `return false` | ❌ 最好情況 **1 格** |
| 漏斗 `suckInItems` | `HopperBlockEntity.java:490-520` | 第一個抽得動的格就 `return true` | ❌ |
| 漏斗 `tryTakeInItemFromSlot` | `HopperBlockEntity.java:522-529` | 只碰指定 slot | ❌ **1 格** |
| 漏斗/漏斗礦車/投擲器/合成器 推入 | `HopperBlockEntity.java:556-571` → `tryMoveInItem:586-628` | 逐格 `getItem(slot)` 直到物品用完 —— **但每格都會呼 `container.isEmpty()`**(`:591` `boolean wasEmpty = container.isEmpty();`) | 半 |
| **銅傀儡拿東西** | `TransportItemsBetweenContainers.java:544-556` | `for (ItemStack : container)` 第一個非空格就 `return` | ❌ |
| **銅傀儡放東西** | `TransportItemsBetweenContainers.java:559-583` | 第一個空格就 `return` | ❌ |
| **銅傀儡「這箱有沒有我手上的東西」** | `TransportItemsBetweenContainers.java:514-523` | 第一個同物品就 `return true` | ❌ |
| `Container` 預設迭代器 | `net/minecraft/world/Container.java:115-141` | `ContainerIterator.next()` = `container.getItem(index++)` —— **逐格,不持有清單** | ❌ |
| `countItem` / `hasAnyMatching` / `hasAnyOf` | `Container.java:62-83` | `hasAnyMatching` 早退;`countItem` 全掃 | 半 |
| `isEmpty()` | `BaseContainerBlockEntity.java:93-101` | 第一個非空格就 `return false` | ❌ 非空箱 **1 格**;空箱 27 格 |
| Bukkit `inv.getItem(index)` | `CraftInventory.java:52-55` | **1 格** | ❌ |
| `/item` 指令、`SlotAccess` | `Container.java:98-111` | **1 格** | ❌ |
| 開箱 GUI 的單格讀 | `Slot.java:48-50` | 1 格 —— **但 `broadcastChanges` 每 tick 全掃,實務上等於整份** | ✅(實務上) |

### 2.1 26.2 的新變數:銅傀儡

`TransportItemsBetweenContainers`(`net/minecraft/world/entity/ai/behavior/`)是 26.2 才有的,唯一使用者是 `CopperGolemAi.java`。它是**漏斗以外唯一一個「行為形狀跟漏斗一樣」的新來源**:逐格、早退、反覆碰同一批箱子。

三個對我們有利的事實:

1. **找箱子的階段完全不解碼**。`getTransportTarget:277-305` 掃遍周圍 chunk 裡所有 `ChestBlockEntity`,但只看方塊狀態、`isContainerLocked`(`:339-341` → `BaseContainerBlockEntity.isLocked:85-87`,只讀 `lockKey` 欄位)、以及已訪問清單 —— **一格都沒碰**。
2. `for (ItemStack itemStack : container)` 走的是 `Container.ContainerIterator`(`Container.java:118-141`),**逐格 `getItem(i)`,不會拿到活體清單**。這對逐格設計是好消息。
3. 頻率遠低於漏斗:`TARGET_INTERACTION_TIME = 60`、`IDLE_COOLDOWN = 140`(`TransportItemsBetweenContainers.java:41,47`),一次互動間隔數秒;漏斗是 8 tick。

**行動建議**:在改任何東西之前,先去線上撈 `attrVanilla` 桶的 sample 印出來(`LazyContainerRuntime.java:329` 會印前 30 個未見過的觸發點),看看有沒有 `TransportItemsBetweenContainers` 或 `CopperGolemAi`。如果沒有(艦隊上沒人養銅傀儡),那 §2 這一整欄的非漏斗收益就只剩 `getItem(index)` 和 `isEmpty()`,幾乎為零。

---

## 3. 分類 C:根本不碰格 —— 現行 raw 路徑已經零成本

這批路徑常被誤以為會「打開箱子」,實際上現行設計已經全部走 raw 進 raw 出,逐格與否完全無關。

| 路徑 | 檔名:行號 | 為什麼零成本 |
|---|---|---|
| chunk 例行存檔 | `LevelChunk.java:565-570` → `BlockEntity.saveWithoutMetadata:161-164` → `ChestBlockEntity.saveAdditional:133-139` | 走 `lazycontainer$save`,原樣回寫 raw |
| **`getState()` 快照(外掛最常見)** | `2-spigot/.../block/CraftBlockEntityState.java:44-50, 78-84` | `saveWithFullMetadata`(raw)→ `BlockEntity.loadStatic:193-215` → `loadWithComponents:118-121` → `loadAdditional` → **快照 BE 也是 pending**。頭尾都 lazy,`loadWithComponents` 不呼 `applyImplicitComponents` |
| 漏斗事件的 Inventory 物件 | `HopperBlockEntity.java:372-386` → `BlockEntity.getOwner(false):377-382` → `CraftBlockStates.java:193-209`(`DISABLE_SNAPSHOT=true`) | **活體包裝,不建快照、不碰任何格**。只有外掛真的呼 `event.getSource().getContents()` 才解碼 |
| 投擲器/合成器推入時的 Inventory | `DropperBlock.java:76`、`CrafterBlock.java:216`(`into.getOwner()` = **有**快照) | 快照走 raw→raw,仍零解碼 |
| `/clone` | `CloneCommands.java:222-226`(`saveCustomOnly`)、`:277-279`(`loadCustomOnly`) | 兩端都 lazy,目的地重新變 pending |
| structure block **存** | `StructureTemplate.java:120-124`(`saveWithId`) | raw 回寫 |
| structure block **放**/落砂方塊/`BLOCK_ENTITY_DATA` 物品放置 | `StructureTemplate.java:337`、`FallingBlockEntity.java:233-236`、`TypedEntityData.java:171` | `loadCustomOnly`/`loadWithComponents` → 重回 pending |
| `/data get block`、`/execute if block … {nbt}` | `BlockDataAccessor.java:72`、`BlockInput.java:60`、`BlockPredicate.java:83` | `saveWithFullMetadata`,lazy |
| `/data merge block` | `BlockDataAccessor.java:64` | `loadWithComponents` → 重回 pending(對**活體** BE,所以 template 的 monitor 是必要的) |
| 客戶端方塊實體同步封包 | `BlockEntity.getUpdateTag:243-245` 回空 `CompoundTag` | 箱子的 Items **從不走網路**,零成本 |
| `getContainerSize()` | `ShulkerBoxBlockEntity.java:171-173` / Chest 常數 27 | size 與物化無關(FINDINGS §1.3) |

一個小發現:`HopperBlockEntity.allMatch/anyMatch`(`:393-430`)在 26.2 的這份源碼裡**定義了但沒有任何呼叫點**(死碼),不用管它。另外 `anyMatch` 迴圈跑完 `return true`(`:425`)看起來是 Paper 上游的筆誤,但因為沒人呼叫,不影響任何事。

---

## 4. 核心問題:誰會「留著 `getItems()` 回傳的清單引用」?

這決定「已解格 / 未解格混合狀態」能不能安全暴露。窮舉整棵樹之後,**取得 `NonNullList` 本體的只有五處**:

| # | 誰 | 檔名:行號 | 引用活多久 | 怎麼讀 |
|---|---|---|---|---|
| 1 | `CraftInventory.getContents()` | `2-spigot/.../CraftInventory.java:80-84` → `asCraftMirror:56-66` | **只活在方法內** | `mcItems.get(i)` 走完全部 —— **繞過 `getItem`** |
| 2 | `ChestBlockEntity.swapContents` | `4-folia/.../ChestBlockEntity.java:214-219` | **永久換手到另一個 BE** | `one.setItems(two.getItems())` |
| 3 | `applyImplicitComponents` | `BaseContainerBlockEntity.java:154-159` | 方法內 | `ItemContainerContents.copyInto(getItems())` 全格**寫** |
| 4 | `collectImplicitComponents` | `BaseContainerBlockEntity.java:162-170` | 方法內 | `ItemContainerContents.fromItems(getItems())` 全格**讀** |
| 5 | `ContainerHelper.loadAllItems/saveAllItems` | `ContainerHelper.java:21-46` | 方法內 | save 全格讀、load 依 slot 寫(這條是我們自己的 redirect 目標) |

另外 `BaseContainerBlockEntity.isEmpty():93-101` 用 enhanced-for 走清單,實際上經 `AbstractList.Itr` → `NonNullList.get(i)`(`NonNullList.java:39-42`),逐格且早退。

**好消息**:除了 `swapContents`(只有 1.12→1.13 舊世界升級 `UpgradeData.java:385` 會呼,生產上等於死碼),**沒有任何路徑把清單存進欄位長期持有**。理論上「混合狀態」是可以存在的。

**壞消息**:`NonNullList extends AbstractList` 而且**沒有覆寫 `iterator()`/`forEach()`/`stream()`**(`NonNullList.java:10-76`),所以任何拿到清單的人,只要不是我們攔得到的 `get(int)`,就會直接看到未解的空格。而 §4 第 1、3、4、5 條全都會走完整條清單。

---

## 5. 紅線:半填清單一旦外洩,會發生什麼

這不是理論。26.2-2 已經實測過:**2000 輪中 1943 輪讀到半填,最壞情況把 27 格滿箱存成 `Items: []`**(`RELEASE-NOTE-26.2-2.md` / `tests/.../EnsureRaceTest.java`)。當時那是個**競態窗口**;逐格解碼會把它變成**常態**。

半填清單外洩到這五個地方,分別會怎樣:

| 外洩點 | 檔名:行號 | 後果 |
|---|---|---|
| `ShulkerBoxBlock.playerWillDestroy` 用 `!isEmpty()` 決定界伏盒帶不帶內容 | `ShulkerBoxBlock.java:113` | 判成空 ⟹ **界伏盒掉成空盒,整箱東西蒸發** |
| `collectImplicitComponents` → `fromItems` | `BaseContainerBlockEntity.java:169` | 界伏盒/pick-block **只帶到一半的物品** |
| `ContainerHelper.saveAllItems` | `ContainerHelper.java:25-38` | **磁碟永久殘缺**(autosave 之後不再重寫這個 chunk) |
| `CraftInventory.getContents()` | `CraftInventory.java:81` | 外掛看到空箱 ⟹ 商店/保護/統計類外掛全面誤判 |
| `Containers.dropContents` | `Containers.java:22-26` | 破壞箱子**只掉一部分** |
| 銅傀儡 `matchesGettingItemsRequirement` | `TransportItemsBetweenContainers.java:506-508` | 判成空箱,行為異常(無資料損失,但是靜默行為回歸) |

**所以逐格設計的不變式只能是這一條**:

> `getItems()` 與 `getContents()` 這兩個咽喉,**必須繼續把剩下未解的格補齊之後才交出清單**。逐格只能開在 `getItem(slot)` / `removeItem(slot)` / `removeItemNoUpdate(slot)` / `setItem(slot)` / `isEmpty()` 這一層。

好消息是這條紅線**跟現行架構完全相容**:`BaseContainerBlockEntity` 的 `getItem/removeItem/removeItemNoUpdate/setItem` 現在都是 `this.getItems().get(slot)` 之類(`:105-130`),把它們改成「只補這一格」是純加法;而 `RandomizableContainerBlockEntity` 這一層(Chest/Barrel/Shulker 都繼承它)只是先呼 `unpackLootTable` 再 `super.*`(`:49-77`),完全透明。

**注意一個既有陷阱**:`RandomizableContainerBlockEntity.getItem:55-57` 是 `if (slot == 0) this.unpackLootTable(null);` —— Paper 的漏斗優化只在 slot 0 觸發 loot 展開。逐格設計如果讓漏斗跳過 slot 0(因為摘要說它空),就會漏掉 loot 展開,未開封的戰利品箱永遠抽不出來。這正是 FINDINGS §1.5 記載的坑,逐格版本要沿用 `lazycontainer$lootPending()` 那套守門。

---

## 6. 另一個必須順手處理的東西:`isEmpty()` 在推入路徑上是每格一次

`HopperBlockEntity.tryMoveInItem:591` 有一行 `boolean wasEmpty = container.isEmpty();`,而 `tryMoveInItem` 是被 `addItem:556-571` **逐格呼叫**的。也就是說漏斗/投擲器/合成器往箱子推東西時,`isEmpty()` 會被呼叫最多 27 次。

`isEmpty()`(`BaseContainerBlockEntity.java:93-101`)早退,所以:
- 箱子第 0 格有東西 ⟹ 每次 1 格,超便宜。
- 箱子**幾乎空**(只有最後幾格有東西)⟹ 每次都要走到底 ⟹ 逐格解碼下這會**把整箱都解出來**,而且是 27 次全掃。

如果做逐格,`isEmpty()` 一定要接上摘要(`lazycontainer$sumBits != 0` ⟹ 證明非空,直接回 false),否則推入路徑會變成逐格設計的天敵。

---

## 7. 結論與建議節奏

1. **不要為了比較器做逐格**。比較器必然整份(`AbstractContainerMenu.java:896-901`),而且單次更新最多 4 遍全掃。要省它只能做「從 raw 直接算訊號」的摘要,而且判定條件比現在的滿/空更嚴。
2. **逐格的正當理由只有漏斗**(`isFullContainer:477-488`、`suckInItems:490-520`、`tryTakeInItemFromSlot:522-529` 三個都早退),外加 26.2 的銅傀儡。生產已測:71–99% 的解碼由漏斗觸發 —— 這個比例本身就說明「漏斗以外」這個鏡頭賺不到什麼。
3. **先量再改**。開 `-Dlazycontainer.verbose=true` 撈一輪 `attrComparator` / `attrVanilla` / `attrPlayer` / `attrPlugin` 的實際比例,以及 `attrVanilla` 的 sample 裡有沒有 `TransportItemsBetweenContainers`。如果 comparator 佔比顯著,那該做的是摘要延伸而不是逐格。
4. **如果要做逐格,唯一安全的形狀**是:新增一個「已解格 bitmask」,`getItem(slot)` 只補該格並寫回清單(因為 `hopperPull:279-320`、`tryMoveInItem:586-628`、`ContainerHelper.removeItem:13-15` 都**就地改動**回傳的 ItemStack,不寫回清單就是掉物/複製);`getItems()`/`getContents()` 補齊全部才交出;`isEmpty()` 接摘要。
5. **上線前必跑 shadow**,而且這次的 shadow 要多驗一件事:每次 `getItems()` 交出清單時,bitmask 必須是全 1。這是逐格版本最重要的單一不變式。

---

## 8. 這輪沒查、但下一輪應該查的

- **外掛層**:艦隊 109 台上到底有多少外掛在 `InventoryMoveItemEvent` 裡呼 `getSource().getContents()`。源碼證明事件物件本身是免費的(`HopperBlockEntity.java:372-386` + `getOwner(false)`),所以這是純外掛行為問題,要 grep 自有 repo。
- **`CraftBlockEntityState.DISABLE_SNAPSHOT` 是 static 非 volatile**(`CraftBlockStates.java:200-207` 前後改它),在 Folia/EndRod 多執行緒下是資料競態。這不是我們的 bug,但如果逐格設計依賴「getState 一定走快照」這個假設,就會踩到。