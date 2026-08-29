# hopper-access

**結論**:不值得做——原版漏斗在 tryMoveInItem:592 硬呼叫 container.isEmpty()（定義上就是全格掃描），而所有能撐過逐格解碼的路徑都是「馬上要寫入、寫完就得整份重編碼」的路徑，所以逐格頂多把成本延後幾微秒、一格也沒省；真正只碰 1 格的 isFullContainer 早已被摘要 hook 用零解碼吃掉，正確的下一步是先撈 fullQ 四個計數器、照它指的方向放寬摘要證明規則，而不是把 1 個 guard 的攻擊面擴成 5 個。

## 硬事實
- 漏斗自己的 5 格與 lazy 完全無關：容量寫死在 HopperBlockEntity.java:33 (HOPPER_CONTAINER_SIZE=5)、:37，礦車版在 MinecartHopper.java:43-45；而 agent 只把 Chest/Barrel/ShulkerBox 三種轉成 lazy（LazyContainerTransformer.java:110），HopperBlockEntity 只被掛兩個靜態檢查 hook（:242、:246）。逐格解碼對「五格」那側收益恆為 0。
- Container.getItem(int) 在我們三種容器上不是逐格介面：BaseContainerBlockEntity.java:105-107 是 return this.getItems().get(slot)，Chest/Shulker 都沒覆寫它。所以任何一次 getItem(0) 都會打到 agent 掛在 getItems() 的 guard、觸發整箱 ensure()——這正是 s45 那條 isFullContainer → CompoundContainer.getItem → getItems guard → ensure 的成因。
- isFullContainer（HopperBlockEntity.java:477-488）典型只碰 1 格：:482-484 遇到第一個 count<maxStackSize 就 return false，而空堆 getCount() 回 0（1-vanilla/ItemStack.java:1053-1055）必然觸發。只有真正全滿的容器才會走完 27/54 格。
- 逐格解碼在推入路徑上收益為 0：tryMoveInItem 的 :592 boolean wasEmpty = container.isEmpty()，而 BaseContainerBlockEntity.isEmpty:94-102 定義上就是掃過每一格；canPlaceItem 對箱子預設恆真（Container.java:56-58），所以第一格就會踩到。更荒謬的是 wasEmpty 只在 :615 目標是 HopperBlockEntity 時才被讀，箱子目標算完就丟（原版同款：1-vanilla:318、:332）。
- 界伏盒的 WorldlyContainer 面向不會減格：ShulkerBoxBlockEntity.java:43 的 SLOTS = IntStream.range(0,27)，:275-277 的 getSlotsForFace 對任何面都回全部 27 格。canPlaceItemThroughFace(:280-282) 只看要塞進來的物品、canTakeItemThroughFace(:285-287) 恆真，兩者都不讀目標容器任何一格。
- 雙箱只會解到「有缺口的那一半」：CompoundContainer.getItem（CompoundContainer.java:78-80）依 slot 分流到 container1/container2，CompoundContainer.isEmpty(:69-71) 也是先問前半、前半空才問後半。所以雙箱的實際解碼單位是 27 不是 54。
- 漏斗礦車只會吸、不會推：MinecartHopper.java 全檔無 ejectItems 呼叫，只有 tryConsumeItems:98-103 → suckInItems:105-120。且 tick():85-89 與 makeStepAlongTrack():92-96 各呼叫一次，consumedItemThisFrame(:86,:100) 只在吸「成功」時擋重複——吸失敗（上面空箱）會每一小段軌道重來。
- 箱子礦車/漏斗礦車本身永遠不是 lazy：AbstractMinecartContainer.java:27 的 itemStacks 是 entity 欄位、:43-45 走 getChestVehicleItem，不是 BaseContainerBlockEntity 的子孫，agent 完全不碰。
- 4-folia HopperBlockEntity.java:393-429 的 allMatch / anyMatch / STACK_SIZE_TEST / IS_EMPTY_TEST 是死碼——全 repo grep 零呼叫點，而且 anyMatch 的 fall-through(:426) 回的是 true。這個 build 裡也不存在 cachedFull 欄位。任何設計都不該建立在這四個上面。
- Folia 相對 Paper 在漏斗上的改動全部是 region threading（skipPull/PushModeEventFire 搬進 RegionizedWorldData.java:390,478、getGameTime→getRedstoneGameTime :143、ignoreBlockEntityUpdates→IGNORE_TILE_UPDATES ThreadLocal :307-309,:601-603），沒有一行改變「碰幾格」。
- Paper 的 optimize-hoppers 對「碰目標幾格」只有兩項實質影響：hopperPush 一次搬 spigotConfig.hopperAmount 個(:245) 而非原版一次 1 個(1-vanilla:158)，以及 hopperPull 用 setItem(i,剩餘)(:308) 取代原版 removeItem+setItem(1-vanilla:252,260)。getFullState(:160-189) 只動漏斗自己 5 格。
- 抽出成功必然物化來源：hopperPull(:279-320) 的 addItem 目標是漏斗（5 格不 lazy），但收尾的 container.setItem(i, ...)(:308) 會走 BaseContainerBlockEntity.setItem:125-129 → getItems() → 解碼。所以 suckInItems 那條路上，摘要（slotProvenEmpty）已經吃掉全部「白掃空格」的成本，剩下的每一次解碼都對應一次真實搬運。
- 原版小毛病：CACHED_SLOTS = new int[54][](:34) 配上 containerSize < CACHED_SLOTS.length(:451) 的判斷式，讓 size 剛好 54 的雙箱永遠 cache miss、每次 getSlots 都 new int[54]。雙箱上下各一台漏斗每 tick 多兩次配置。原版同款(1-vanilla:179)。
- 判斷「該往哪走」的儀器早就埋好了：LazyContainerRuntime.java:167-192 的四個計數器，報表格式 fullQ=證明滿/證明不滿/佔滿但證不了/整份放棄（:477-478）。摘要的 size>32 棄答條件（LazyContainerTemplate.java:621）對 27 格 leaf 永遠不會觸發，所以 fullGaveUp 若偏高只可能是非 ListTag 或非數值 Slot(:649)。
- 摘要目前只容忍唯一一個 component：LazyContainerTemplate.java:696-700 規定 components 必須缺席、或唯一 key 是合法的 minecraft:max_stack_size，其餘一律不得參與「全滿」證明。這是 fullUnknownDirty 的直接來源，也是唯一便宜的放寬方向。

## 風險
- 若真的要做逐格解碼：BaseContainerBlockEntity.getItem:105-107 是 getItems().get(slot)，必須在三個 leaf 額外覆寫 getItem/isEmpty/removeItem/removeItemNoUpdate/setItem，或讓 getItems() 交出自製的 lazy NonNullList。前者把攻擊面從 1 個 guard 擴成 5 個，後者把所有 NMS 與外掛都會拿去用的清單物件換成自製品。在 109 台正式服上這是重寫、不是優化。
- 想繞過 tryMoveInItem:592 的 container.isEmpty() 會踩到戰利品箱：RandomizableContainerBlockEntity.isEmpty:49-52 會順手 unpackLootTable(null)，而 getItem 只在 slot==0 才 unpack(:55-58)。跳過 isEmpty 會改變未開封戰利品箱的開封時機——這是行為變更不是效能優化，而且與 template:561-564 lootPending 已知的那類靜默回歸同一家族。
- 部分物化會把存檔判斷從兩個分支長成一棵樹：目前 trySaveRaw(LazyContainerTemplate.java:341-391) 只有「pending 且 raw!=null ⟹ 原樣回寫」與「已物化 ⟹ 正常編碼」兩種結局。若引入「已動的格重編碼、沒動的格沿用 raw 位元組」，ListTag 的 entry 順序保證會破功，與『逐位元組不變』鐵則正面衝突。
- 第五節建議的『每 tick 解碼預算』不是免費的：hook 點必須從 isFullContainer（回傳 boolean、沒地方延後）往上移到 ejectItems 或 tryMoveItems，那是全新的 hook 面，需要重跑一輪對抗性審查與跨執行緒論證。延後搬運本身雖然玩家看不出來（Paper 自己的 cooldownWhenFull :273-275、:315-317 就是同一招），但新 hook 面的風險是實的。
- 放寬摘要的 component 白名單（LazyContainerTemplate.java:696-700）雖然是最便宜的方向，但每放行一個 component 就是一次『這個 component 絕不會讓 DataComponentPatch 解碼失敗』的斷言。斷言錯了就是錯報「全滿」⟹ 漏斗永不推入 ⟹ 容器永不物化 ⟹ 壞 entry 永久留在 raw 裡（正是 :604-608 註解裡描述的那個災難形狀）。每一個放行都必須配一輪差分測試。
- 外掛可能繞過整套分析：hopperPush/hopperPull 在 skipHopperEvents 為 false 時會 fire PaperInventoryMoveItemEvent(:326、:355)。任何監聽者只要讀 event 的 inventory 內容，就會自己把箱子展開——這條路徑不經過我們任何 hook，也不在漏斗的 slot 統計裡。
- 本次分析用的是 4-folia 反編譯樹，艦隊實跑的是它的 EndRod fork。ejectItems/suckInItems/tryMoveInItem 這幾支若 fork 有動過（transformer 在 LazyContainerTransformer.java:60-61 已經用逐字簽章比對防呆、:255 對不上會退回原版），結論的行號需要重新核對一次。

## 報告
找到答案了：**逐格延遲解碼不值得做**。原因不是「省得不夠多」，而是原版漏斗的程式碼結構讓它在最關鍵的那條路上**根本省不到**。以下每一條都指得到源碼行號。

---

## 〇、先把服主問的「五格」講清楚

「漏斗是不是五個都會要求」——會，但那**五格是漏斗自己的肚子，不是被吸/被推的箱子**，而且**漏斗自己的五格從來就不是 lazy 的**。

- 漏斗容量寫死 5：`HopperBlockEntity.java:33`（`HOPPER_CONTAINER_SIZE = 5`）、`:37`（`items = NonNullList.withSize(5, ...)`）；漏斗礦車一樣是 5（`MinecartHopper.java:43-45`）。
- 會掃這 5 格的有兩處，都是「漏斗自檢」：
  - `getFullState()`（`HopperBlockEntity.java:160-189`）——Paper 加的，一次迴圈同時算出「空/有貨/全滿」，取代原版的 `isEmpty()` + `inventoryFull()` 兩趟（`1-vanilla/HopperBlockEntity.java:115` 與 `:119`）。
  - `hopperPush()` 外圈（`HopperBlockEntity.java:237-238`）——`for i in 0..4: hopper.getItem(i)`，找到第一格有貨的就試著推出去。
- **我們的 agent 只把 Chest / Barrel / ShulkerBox 三種變成 lazy**（`LazyContainerTransformer.java:110`），`HopperBlockEntity` 只被掛了兩個靜態檢查的 hook（`:242`、`:246`），**它的 5 格一直是普通欄位、從來不需要解碼**。

所以：**逐格解碼對「五格」那一側的價值恆為 0**。有價值的只有另一側——目標箱子的 27 格（雙箱 54、界伏盒 27）。

---

## 一、窮舉：每一條會碰到「目標容器」slot 的路徑

先講一個貫穿全文的關鍵事實，它一個人就決定了整個結論：

> **`Container.getItem(int)` 在我們的三種容器上，根本不是「逐格介面」。**
> `BaseContainerBlockEntity.java:105-107`：
> ```java
> public ItemStack getItem(final int slot) {
>     return this.getItems().get(slot);      // ← 先拿整份清單，再取第 slot 格
> }
> ```
> Chest 沒有覆寫它（`ChestBlockEntity.java` 只覆寫 `getItems()` 於 `:194`），ShulkerBox 也沒有。所以**任何一次 `getItem(0)` 都會打到 agent 掛在 `getItems()` 的 guard，觸發整箱 `ensure()`**。
> 這就是 s45 那條 16.19 秒的鏈長成 `isFullContainer → CompoundContainer.getItem → getItems guard → ensure` 的原因。

### 路徑 1：推出前的滿檢查 `isFullContainer`
`HopperBlockEntity.java:432-444`（`ejectItems`）→ `:477-488`（`isFullContainer`）

- 碰哪些格、什麼順序：`getSlots(container, dir)`（`:446-464`）給的順序。木桶/單箱=0..26，雙箱=0..53（`CompoundContainer.getContainerSize` `CompoundContainer.java:64-66`），界伏盒走 `WorldlyContainer.getSlotsForFace` 但回的是**全部 27 格**（`ShulkerBoxBlockEntity.java:43` 的 `SLOTS = IntStream.range(0,27)`、`:275-277`）——**面向不會幫你減格**。
- 提前跳出：`:482-484`，第一格 `count < maxStackSize` 就 `return false`。空格必然滿足（`ItemStack.getCount()` 對空堆回 0，`1-vanilla/ItemStack.java:1053-1055`）。
- **一次 tick 典型碰幾格：1 格。** 只有「真的全滿」時才會走完 27/54 格。
- 介面：`container.getItem(slot)`（`:481`）⟹ 打 `getItems()` ⟹ 整箱解碼。
- 雙箱：`CompoundContainer.getItem`（`CompoundContainer.java:78-80`）會依 slot 分流到對應那半箱，**所以雙箱只會解到「有缺口的那一半」**，不是 54 格。
- agent 現況：**已掛 hook**（`LazyContainerTemplate.java:446-481`）。摘要答「全滿」⟹ 漏斗停手、零解碼；答「不滿」或「不知道」⟹ 落回原路。

### 路徑 2：推出本體 `hopperPush` → `addItem` → `tryMoveInItem`
`HopperBlockEntity.java:233-277` → `:556-572` → `:586-629`

- `hopperPush` 外圈只碰**漏斗自己的 5 格**（`:237-238`），零成本。
- `addItem`（`:556-572`）對目標容器從 slot 0 往上試，`itemStack` 變空就停（`:560`、`:566`）。典型只碰 1~2 格。
- **但 `tryMoveInItem` 裡有一顆地雷**（`:589` 與 `:592`）：
  ```java
  ItemStack current = container.getItem(slot);              // :589  → getItems() → 解碼
  if (canPlaceItemInContainer(container, itemStack, slot, direction)) {
      boolean success = false;
      boolean wasEmpty = container.isEmpty();               // :592  → 全格掃描
  ```
  `BaseContainerBlockEntity.isEmpty()`（`BaseContainerBlockEntity.java:94-102`）是「for 每一格 getItems()」——**定義上就是整份**。而且 `canPlaceItem` 對箱子/木桶預設恆真（`Container.java:56-58`），所以**第一格就會踩到**。
- 更荒謬的是：`wasEmpty` 這個值只在 `:615` 用到，條件是 `container instanceof HopperBlockEntity`。**目標是箱子時，這個全格掃描算完就丟掉。** 原版同樣如此（`1-vanilla/HopperBlockEntity.java:318` 與 `:332`），不是 Paper 的鍋。
- **結論：只要漏斗真的要往箱子塞東西，整箱一定會被展開，逐格解碼在這條路上省不到任何東西。**

### 路徑 3：吸入 `suckInItems` → `tryTakeInItemFromSlot`
`HopperBlockEntity.java:490-520` → `:522-529`

- 順序 0..n-1，**第一次成功搬運就 `return true`**（`:500-502`）。
- `tryTakeInItemFromSlot:523` `container.getItem(slot)`，空格就跳下一格。
- 一次 tick 最多碰幾格：**上面那箱全空 ⟹ 全部 27/54 格都會被問**。這正是「漏斗貼著沒人用的箱子，每 8 tick 敲一次」的日常。
- agent 現況：**已掛 hook**（`slotProvenEmpty`，`LazyContainerTemplate.java:488-520`），空格直接短路、`getItem` 根本不會被呼叫 ⟹ 全空箱子零解碼。這是目前 99.6% 容器沒被解開的主功臣。
- 有貨時：`hopperPull`（`:279-320`）→ `addItem` 目標是**漏斗**（5 格，不 lazy）→ 然後 `container.setItem(i, ...)`（`:308`）→ `BaseContainerBlockEntity.setItem:125-129` → `getItems()` ⟹ 解碼。**抽成功＝箱子必然被展開。**

### 路徑 4：`canPlaceItemInContainer` / `canTakeItemFromContainer`
`:574-577` / `:579-584`。箱子/木桶用預設實作（不看 slot 內容）；界伏盒的 `canPlaceItemThroughFace`（`ShulkerBoxBlockEntity.java:280-282`）只看「要塞進來的東西是不是界伏盒」，`canTakeItemThroughFace`（`:285-287`）恆真。**這兩條完全不碰目標容器的格，零貢獻。**

### 路徑 5：`getSlots`
`:446-464`。不讀任何格。附帶抓到一個原版小毛病：`CACHED_SLOTS = new int[54][]`（`:34`），判斷式是 `containerSize < CACHED_SLOTS.length`（`:451`）——**size 剛好 54 的雙箱永遠 miss，每次呼叫 `new int[54]`**。雙箱上下各一台漏斗，每 tick 就多兩次配置。原版同款（`1-vanilla:179`），不是我們的事，但值得記著。

### 路徑 6：漏斗礦車
`MinecartHopper.java`

- **漏斗礦車只會吸、不會推**：全檔沒有任何 `ejectItems` 呼叫，只有 `tryConsumeItems:98-103` → `suckInItems:105-120` → `HopperBlockEntity.suckInItems`（走路徑 3）。
- 但它比方塊漏斗更兇：`tick():85-89` 呼叫一次，`makeStepAlongTrack():92-96` **每走一段軌道再呼叫一次**。`consumedItemThisFrame`（`:86`、`:100`）只有在**吸成功**時才會擋住重複——**吸失敗（上面是空箱）就每一小段都重來**。
- 反過來，當漏斗把東西推進**箱子礦車 / 漏斗礦車**時：那些容器的 `itemStacks` 是 entity 欄位（`AbstractMinecartContainer.java:27`、`:43-45`），**不是 `BaseContainerBlockEntity` 的子孫，agent 從來不碰**。零關係。

---

## 二、Paper / Folia 相對原版改了什麼（只挑會影響「碰幾格」的）

| Paper/Folia 改動 | 位置 | 對「碰目標容器幾格」的影響 |
|---|---|---|
| `getFullState` 取代 `isEmpty()+inventoryFull()` | `:160-189` vs `1-vanilla:115,119` | 只動漏斗自己 5 格，兩趟變一趟。目標容器：**無影響** |
| `hopperPush` 一次搬 `hopperAmount` 個 | `:245` vs `1-vanilla:158`（一次 1 個） | 同樣吞吐下 `addItem` 呼叫次數變少 ⟹ **目標掃描次數變少** |
| `hopperPull` 用 `setItem(i, 剩餘)` 取代 `removeItem+setItem` | `:308` vs `1-vanilla:252,260` | 每次搬運少一次逐格呼叫 |
| `skipPullModeEventFire` / `skipPushModeEventFire` | `:235`、`:287`、`:497`；Folia 移進 `RegionizedWorldData.java:390,478` | 不碰格。但**若有外掛在監聽 `InventoryMoveItemEvent` 且會讀 inventory，那個外掛自己會把箱子展開**。`MinecraftServer.java:1921` 可見 Paper 的全域版在 Folia 被註解掉、改成 per-region |
| `ignoreOccludingBlocks` | `:704` | 省掉找 entity 容器，不碰格 |
| `allMatch` / `anyMatch` / `STACK_SIZE_TEST` / `IS_EMPTY_TEST` | `:393-429` | **這四個是死碼**：全 repo 掃過，這個 build 裡零呼叫點。而且 `anyMatch:426` 的 fall-through 回的是 `true`（看起來就是錯的）。**不要拿它們當依據** |
| `cachedFull` | — | **這個 build 裡不存在**，沒有這個欄位 |

Folia 相對 Paper 在漏斗上的改動全部是 region threading（靜態欄位搬進 `RegionizedWorldData`、`getGameTime`→`getRedstoneGameTime`、`ignoreBlockEntityUpdates`→`IGNORE_TILE_UPDATES` ThreadLocal），**沒有一行改變「碰幾格」**。

---

## 三、總表

n = 27（單箱/木桶/界伏盒）或 54（雙箱，但實際只解到有缺口的那一半 = 27）。

| 路徑 | 碰誰的格 | 提前跳出 | 典型碰幾格 | 走逐格還是整份 | **逐格解碼相對整箱 27 能省多少** |
|---|---|---|---|---|---|
| `getFullState`（漏斗自檢） | 漏斗自己 5 | 遇不滿即停 | 1–5 | 直接讀欄位 | **0**（不 lazy） |
| `hopperPush` 外圈 | 漏斗自己 5 | 成功即 return | 1–5 | `hopper.getItem` | **0**（不 lazy） |
| `isFullContainer` | 目標 27/54 | 第一格不滿即停 | **1 格** | `getItem`→`getItems` | 理論 **27×**，實際 **0**（見下） |
| `addItem`/`tryMoveInItem` | 目標 27/54 | stack 空即停 | 讀 1–2 格 **＋ `isEmpty()` 全掃 n** | `getItem` + `isEmpty` | **0**（`:592` 註定要全份） |
| `hopperPull` 寫回 | 目標 1 格 | — | 1 | `setItem`→`getItems` | **0**（寫入必須物化） |
| `suckInItems`/`tryTakeInItemFromSlot` | 目標 27/54 | 成功即 return | 空箱 = n、有貨 = k+1 | `getItem` | 空箱那段**摘要已經拿走 100%**；有貨那段隨即寫回 ⟹ **0** |
| `canPlaceItem` / `canTakeItem` | — | — | **0 格** | — | 0 |
| `getSlots` | — | — | **0 格** | — | 0 |
| 漏斗礦車（只吸不推） | 同 `suckInItems` | 同上 | 同上 | 同上 | 同上 |

**「理論 27×、實際 0」怎麼來的**：`isFullContainer` 只碰 1 格，逐格解碼確實能把 27 個 entry 的解碼縮成 1 個。但 `isFullContainer` 回 false 之後的下一步就是 `hopperPush → addItem → tryMoveInItem`，那裡 `:589` 讀格、`:592` 呼叫 `isEmpty()` 全掃——**同一個 tick、往下三層，整箱照樣展開**。省下來的東西在幾微秒後全部吐回去。

---

## 四、為什麼「逐格延遲解碼」不值得做

**(1) 目前根本沒有逐格的切入點。** `BaseContainerBlockEntity.getItem:105-107` 是 `getItems().get(slot)`。要做逐格，必須在三個 leaf 上額外覆寫 `getItem` / `isEmpty` / `removeItem` / `removeItemNoUpdate` / `setItem`，或是讓 `getItems()` 交出一個會自己 lazy 填格的 `NonNullList` 子類。前者是把目前**一個 guard** 的攻擊面擴成**五個**；後者是把一個所有 NMS 與外掛都會拿去亂用的清單物件換成自製品——在 109 台正式服上，這兩條都不是「優化」，是重寫。

**(2) `tryMoveInItem:592` 的 `container.isEmpty()` 把逐格的價值直接歸零。** 這是原版行為（`1-vanilla:318` 一模一樣），而且算出來的 `wasEmpty` 對箱子目標根本用不到（`:615` 只在目標是漏斗時才讀）。想繞過它就得改寫這行的語意——而 `RandomizableContainerBlockEntity.isEmpty:49-52` 會順手 `unpackLootTable`，跳過它會改變戰利品箱的開封時機。**這是行為變更，不是效能優化。**

**(3) 逐格解碼活得下來的路徑，全都是「馬上要寫入」的路徑。** 推入要 `setItem`、抽出成功也要 `setItem`（`:308`）。一旦寫進去，容器就髒了，存檔時 `saveAllItems` 需要完整清單、raw 逐位元組回寫的特權當場失效。你只是把成本從 tick 挪到 save tick，總量一格都沒省。要做到「已動的格重編碼、沒動的格沿用 raw 位元組」才有真省——那要維護兩份互相咬合的狀態機，而且 ListTag 的 entry 順序保證會破功，**與「逐位元組不變」鐵則正面衝突**。

**(4) 真正該省的地方，摘要已經免費省掉了，而且省得更徹底。** `isFullContainer` 只碰 1 格，但那 1 格會炸出整箱——摘要 hook 直接讓那 1 格**連碰都不用碰**（零解碼），比逐格解碼（解 1 個 entry）還省。同理 `slotProvenEmpty` 讓全空箱的 27 次 `getItem` 變成 27 次位元運算。**方向應該是「讓摘要更常敢回答」，不是「解碼解得更細」。**

---

## 五、那 s45 那 16 秒怎麼辦——建議的下一步

**第一步：先看 `fullQ`，不要先寫程式。** 計數器早就埋好了（`LazyContainerRuntime.java:167-192`，報表格式 `fullQ=證明滿/證明不滿/佔滿但證不了/整份放棄`，`:477-478`）。從艦隊撈這四個數字，答案自己會跳出來：

- **`fullUnknownDirty` 大**（每格都有東西、但證不出滿）⟹ 證明規則太嚴。目前只容忍 `minecraft:max_stack_size` 一個 component（`LazyContainerTemplate.java:696-700`）。**這是唯一有明確、便宜、零風險收益的方向**：多放行幾個「絕不會讓 `DataComponentPatch` 解碼失敗」的 component（例如純顯示類），每放行一個就是一批容器從此不用解。
- **`fullGaveUp` 大**（整份放棄）⟹ 去看是哪一種：`size > 32` 那條（`:621`）對我們的 27 格 leaf **永遠不會觸發**，所以只可能是「不是 ListTag」或「Slot 欄位存在但不是數值」（`:649`）。那代表資料本身有問題，該去查資料而不是改 agent。
- **`fullAnsweredNotFull` 大**（證明不滿）⟹ 漏斗接著就是要塞東西進去，**那次解碼是必要工作，逐格也救不了**（見四(2)(3)）。這種情況下唯一能做的是「攤平」而不是「省掉」。

**第二步（只有在 `fullAnsweredNotFull` 佔大宗時才做）：每 tick 解碼預算，用漏斗冷卻付帳。** 觀察到的災情形狀是「一個 tick 累積多次解碼 ⟹ region 凍結 5–16 秒」，不是「總量太大」（穩態每台每小時只有 0.22 次 >100ms）。所以要處理的是**尖峰堆疊**，不是總成本。Paper 自己就有現成先例：`cooldownWhenFull` 在推不動時直接 `setCooldown`（`:273-275`、`:315-317`）。同理，當一個 region 在這個 tick 已經解了 N 個容器，第 N+1 個就給那台漏斗一個 `hopperTransfer` 冷卻、下個 tick 再說。**延後幾個 tick 的搬運玩家看不出來；凍結 16 秒玩家一定看得出來。**
　⚠️ 但這條要誠實標記風險：hook 點得從 `isFullContainer`（回傳 boolean，沒地方「延後」）往上移到 `ejectItems` 或 `tryMoveItems`，是新的 hook 面，需要重新做一輪對抗性審查。**不是免費的。**

**第三步：把「99.6% 從不解碼」這個數字保住。** 目前的架構之所以安全，是因為它只有**一個** guard 點、一條 `ensure()`、一條 raw 存檔。任何讓 lazy 狀態變成「部分物化」的設計，都會讓「存檔時到底該寫 raw 還是重編碼」這個目前只有兩個分支的判斷（`LazyContainerTemplate.java:341-391`）長成一棵樹。在 109 台正式服上，那不是我們該冒的險。