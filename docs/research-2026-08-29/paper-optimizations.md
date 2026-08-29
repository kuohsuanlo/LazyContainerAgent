# paper-optimizations

**結論**:從 Paper/Folia 源碼看,逐格延遲解碼在漏斗路徑上不值得做——因為 isFullContainer 回「不滿」之後兩行(3-paper:576/579)就會把整箱物化,省下的格數會被立刻討回去,而摘要棄答的那群容器(滿格佔用、帶自訂 component)平均本來就要掃到接近全部格;真正該做的是拆掉 3-paper:579 這條可證明等價的多餘 isEmpty()、先量「被外掛取消的 push」比例,並把力氣放在減少「同一 tick 內的首觸數量」而非「單次解碼成本」。

## 硬事實
- Paper 在 ejectItems 之前確實會跳過空漏斗:3-paper/HopperBlockEntity.java:192 `if (fullState != HOPPER_EMPTY)`;但這不是 Paper 新增的保護,原版 1-vanilla/HopperBlockEntity.java:115 `if (!entity.isEmpty())` 已同效。Paper 的貢獻只是把 isEmpty()+inventoryFull() 兩次掃描併成 getFullState 一次(3-paper:150-179)。
- isFullContainer 在 Paper 與 Folia 都沒有被短路也沒有被快取:3-paper:465-476 與 1-vanilla:205-216 逐字相同(diff 驗證 IDENTICAL),paper↔folia diff 也未觸及該段。這塊 100% 是 LazyContainerAgent 的責任。
- isFullContainer 只在漏斗非空時才會被呼叫(由 3-paper:192 保證),而它回「不滿」之後必然進入 hopperPush → addItem → tryMoveInItem,在 3-paper:576 `container.getItem(slot)` 與 3-paper:579 `container.isEmpty()` 兩行內把整箱物化。⟹ 摘要答「不滿」在解碼上省零,只有答「滿」(3-paper:427-429 提前 return)才真的省。
- 3-paper:579 的 `boolean wasEmpty = container.isEmpty();` 其唯一消費點在 3-paper:602 且被 `container instanceof HopperBlockEntity` 守著。因 Java && 短路,改成 `(container instanceof HopperBlockEntity) && container.isEmpty()` 逐字等價;可消掉目標為箱子時的整箱掃描與 addItem 迴圈造成的 O(n²)(雙箱最壞 54×54=2916 次 ItemStack.isEmpty)。原版 1-vanilla:318 與 Folia 同形。
- 空格必然讓 isFullContainer 回 false:ItemStack.EMPTY 的 getCount()=0,getMaxStackSize() 走 ItemInstance.java:14-16 的 getOrDefault(MAX_STACK_SIZE, 1),而空 stack 的 getComponents() 回 DataComponentMap.EMPTY(ItemStack.java:232-235)⟹ 得 1 ⟹ 0<1。所以典型情況只需 slot 0 一格,卻付整箱解碼——s45 鏈的形狀。
- Folia 對 CompoundContainer 與 ChestBlock 完全沒有 patch(diff 3-paper vs 4-folia 兩檔皆 IDENTICAL)。雙箱的執行緒安全純靠幾何保證:ServerLevel.java:711-719 建 regioniser 時 regionSectionMergeRadius=1,相鄰 chunk 必在同一 region ⟹ 雙箱兩半永遠同執行緒。s45 的 16.19 秒因此是純工作量、不是鎖競爭。
- CompoundContainer.isEmpty()(4-folia:69-71)= container1.isEmpty() && container2.isEmpty(),兩半各走 BaseContainerBlockEntity.isEmpty()(4-folia:94-99)迭代 getItems() ⟹ 一次呼叫可同時物化雙箱兩半。
- Paper 的 CACHED_SLOTS 對雙箱失效:宣告是 new int[54][](3-paper:34),判斷是 `containerSize < CACHED_SLOTS.length` 即 54<54=false(3-paper:439)⟹ 雙箱每次 getSlots 都 createFlatSlots(54) 現配一個 int[54]。單箱/木桶(27)有快取。原版同 bug(1-vanilla:179)。
- Shulker 的 getSlotsForFace 在 Paper/Folia 完全沒改:4-folia/ShulkerBoxBlockEntity.java:275-277 無條件回靜態 SLOTS=IntStream.range(0,27)(:43),不看方向也不看開蓋狀態。走 WorldlyContainer 分支(3-paper:435-436)所以不配置陣列,但 isFullContainer 對它一樣是碰第一格就觸發 ensure,沒有可借的短路。canPlaceItemThroughFace 的 shulker-in-shulker 禁令(4-folia:280-282)發生在 3-paper:576 getItem 之後,拒絕也已經物化。
- Paper 的 getFullState 讀的是欄位 hopper.items(3-paper:153)而非 getItems()。若日後把 lazify 擴到 HopperBlockEntity,這行會讀到未填的空 NonNullList 判成 HOPPER_EMPTY ⟹ 漏斗永不外推 ⟹ 物品靜默卡死。目前 LazyContainerTransformer.java:110-116 只動 Chest/Barrel/Shulker,安全。
- skipHopperEvents 在 Paper 是全域靜態(MinecraftServer.java:1819),在 Folia 那行被註解掉(4-folia/MinecraftServer.java:1921)改成 per-region 狀態 RegionizedWorldData.java:390,每 tick 在 :478 updateTickData() 重算。我們沒碰,無衝突。
- 漏斗事件路徑本身不會觸發解碼:getInventory()(3-paper:360-373)對雙箱是 new CraftInventoryDoubleChest 包裝,對單箱是 getOwner(false).getInventory(),而 getOwner(false) 走 block.getState(false)(BlockEntity.java:377-382)= 不做快照。風險只在外掛監聽器自己呼叫 getContents()/getItem()。
- Paper 的節流已經在幫忙但只對「重複探測」:cooldown-when-full 預設 true(3-paper/WorldConfiguration.java:492)在 3-paper:264-266 / 305-307 給 8 tick cooldown。spigot ticks-per.hopper-check 預設 1(2-spigot/SpigotWorldConfig.java:285),艦隊實際也是 1(EndRod-paper-folia/tmp-r178h2-smoke/spigot.yml:126)⟹ 這個閥門目前是關的。
- 摘要棄答的容器必然是「每格都有 entry」(LazyContainerTemplate.java:729-739 的 tri 計算),而造成棄答的族群正是帶自訂 component 的物品(附魔裝/命名物/shulker),其 max_stack_size 多為 1、count 也為 1 ⟹ 每格都 count>=max ⟹ isFullContainer 必須掃完全部 27 格。逐格解碼在最需要它的族群上,平均要解的格數最接近全部。
- Paper 樹裡 3-paper:381-417 的 allMatch/anyMatch/STACK_SIZE_TEST/IS_EMPTY_TEST 是死碼(該檔內無任何呼叫點),且 anyMatch 在無命中時落到 3-paper:414 的 `return true;` 是 bug。不可拿來當「Paper 已做過短路」的依據。
- Folia 把 Paper 的 ignoreBlockEntityUpdates 靜態欄位改成 IGNORE_TILE_UPDATES ThreadLocal(4-folia:307-309、601-603),tryMoveInItem 的 container.setItem 在該旗標為 true 的窗口內執行,而 setItem 走 BaseContainerBlockEntity:126 的 getItems() ⟹ ensure guard 就在這個窗口內。目前 ensure 不發方塊更新所以安全,但任何在 ensure 內新增的 setChanged() 會被靜默吞掉。

## 風險
- 【方向風險,最重要】逐格延遲解碼在漏斗推入路徑上省不到工作量:isFullContainer 之後兩個呼叫(3-paper:576/579)就整箱物化。若照現行計畫投入,很可能做完之後尖峰次數沒變,因為總解碼量守恆、只是換了順序。建議先量「被 InventoryMoveItemEvent 取消的 push」比例再決定。
- 【鐵則衝突】逐格解碼會產生「部分物化」第三態,存檔時必須把 raw entry 與重新 encode 的 entry 合併,逐位元組不變鐵則會被這條合併路徑威脅。唯一安全形狀是「逐格只服務唯讀探測,任何寫入先升級成整箱 ensure」——但推入路徑第一件事就是寫入,等於在漏斗路徑上沒有立足點。
- 【永不可做】不能把 lazify 擴到 HopperBlockEntity:Paper 的 getFullState 直讀 hopper.items 欄位(3-paper:153)繞過 getItems(),會讀到未填清單判成 HOPPER_EMPTY,漏斗永不外推 ⟹ 物品靜默卡死在漏斗裡。這是無聲的資產損失,不會有 log。
- 【未來回歸】Folia 的 IGNORE_TILE_UPDATES ThreadLocal 窗口(4-folia:601-603)涵蓋我們的 ensure guard。現行 ensure 不發方塊更新所以安全,但日後任何在 ensure 內加 setChanged() 的修改都會被靜默吞掉,不會報錯。改 ensure 時必須重驗這一點。
- 【外掛污染】任何監聽 InventoryMoveItemEvent 的外掛只要在 handler 內呼叫 getSource().getContents() 或 event.getItem(),就會當場物化容器,而且讓 Paper 的 skipPushModeEventFire/skipPullModeEventFire(3-paper:322/345)無法設起來,事件每次都 fire。這會同時放大解碼次數與事件成本,且 attribution 會把它記在 hopper 桶(LazyContainerRuntime.java:365-372 只認 net.minecraft.*Hopper* frame,外掛在更外層時看不見)。建議掃一次艦隊有哪些外掛監聽這個事件。
- 【config 屬正式分流,需先報】把 ticks-per.hopper-check 從 1 調到 4/8 是壓「同一 tick 首觸潮」最便宜的手段,但它會讓閒置漏斗最多晚 0.4 秒反應,是遊戲手感決定。依鐵則(寫入正式分流前先問)必須先報服主,不可自行改。
- 【不在本鏡頭但觀察到】/home/logocat/Server/claude-sandbox/workspace/LazyContainerAgent/logs/2026-08-28-116.log.gz 內的 EnsureRaceTest 出現 `rounds=2000 raced=12 bad=1`,樣本訊息是「round#7 pending=false 但清單不完整」。同日其他輪次是 bad=0。這是競態測試的失敗案例,若尚未有人追,建議另開一條線查——它直指「ensure 填完才翻旗標」的不變式。

## 報告
＃ 鏡頭:Paper / Folia 對漏斗與容器的既有優化 — 我們有沒有打架、有沒有重做

讀的檔案(全文比對過):
- `/home/logocat/Server/claude-sandbox/reference-minecraft-nms/1-vanilla/net/minecraft/world/level/block/entity/HopperBlockEntity.java`(456 行)
- `/home/logocat/Server/claude-sandbox/reference-minecraft-nms/3-paper/net/minecraft/world/level/block/entity/HopperBlockEntity.java`(779 行)
- `/home/logocat/Server/claude-sandbox/reference-minecraft-nms/4-folia/net/minecraft/world/level/block/entity/HopperBlockEntity.java`(792 行)
- 以及 `CompoundContainer.java`、`ChestBlock.java`、`BaseContainerBlockEntity.java`、`ShulkerBoxBlockEntity.java`、`ItemStack.java`、`ItemInstance.java`、`RegionizedWorldData.java`、`ServerLevel.java`、`SpigotWorldConfig.java`、`WorldConfiguration.java`

---

## 一、先講結論(一句話)

**Paper 的「Optimize Hoppers」跟我們完全不重疊、也不衝突;但它同時證明了一件對「逐格延遲解碼」很不利的事:漏斗只要真的要往容器推東西,Paper 在 `isFullContainer` 之後兩個呼叫內就會把整箱物化(`3-paper:576` 的 `container.getItem(slot)` 和 `3-paper:579` 的 `container.isEmpty()`)。所以「少解碼幾格」在推入路徑上省不到東西,只是把同一份工作往後挪兩行。**

---

## 二、Paper 各項優化各自省了什麼(逐條對源碼)

### 1. `getFullState`(`3-paper:150-179`)——省的是「漏斗自己」的兩次掃描,不是我們的容器

原版 `tryMoveItems` 掃兩次漏斗自己的 5 格:`entity.isEmpty()`(`1-vanilla:115`)與 `entity.inventoryFull()`(`1-vanilla:119`)。Paper 把它合併成一次掃描回傳三態(`3-paper:191`),然後:

```java
191  final int fullState = getFullState(entity);
192  if (fullState != HOPPER_EMPTY) {
193      changed = ejectItems(level, pos, entity);
194  }
```

**這回答了你的問題 (1):是的,漏斗自己空的時候完全不會碰目標容器。**但這不是 Paper 新加的保護——原版 `1-vanilla:115` 的 `if (!entity.isEmpty())` 已經是同樣效果。Paper 只是把兩次掃描併成一次,省的是 5 格 ItemStack 的迭代,**與我們無關**(漏斗本身沒有被我們 lazify,見第四節)。

要注意的是 `getFullState` 讀的是**欄位** `hopper.items`(`3-paper:153`),不是 `getItems()`。這一點對我們是紅線:**如果哪天把 lazify 擴到 HopperBlockEntity,Paper 這行會讀到那個還沒填的空 NonNullList,判成 HOPPER_EMPTY ⟹ 漏斗永遠不往外推 ⟹ 東西靜默卡死在漏斗裡。**目前 transformer 只動 Chest/Barrel/Shulker(`LazyContainerTransformer.java:110-116`),沒有這個問題,但這是「以後不准做」的清單第一條。

### 2. `skipPullModeEventFire` / `skipPushModeEventFire`(`3-paper:221-223`、`241`、`277`)——省的是 Bukkit 事件,不是解碼

Paper 的邏輯是:`InventoryMoveItemEvent` 打完一次之後,如果監聽器既沒呼叫 `getItem()` 也沒呼叫 `setItem()`(`3-paper:322`、`345`),就把旗標設起來,同一批之後都不再 fire。開關來源:

- Paper:`MinecraftServer.java:1819`,`skipHopperEvents = disableMoveEvent || 沒有任何監聽器`
- Folia:上面那行被註解掉(`4-folia/MinecraftServer.java:1921`),改成每個 region 每 tick 算一次,存在 `RegionizedWorldData.skipHopperEvents`(`RegionizedWorldData.java:390`、賦值在 `:478` 的 `updateTickData()`)

**與我們沒有任何重疊**——它省的是事件物件與 Bukkit 包裝,不是 NBT 解碼。而且事件路徑本身**不會**觸發我們的解碼:`getInventory()`(`3-paper:360-373`)對雙箱是 `new CraftInventoryDoubleChest(compoundContainer)`,對單箱是 `blockEntity.getOwner(false).getInventory()`,`getOwner(false)` 走 `block.getState(false)`(`BlockEntity.java:377-382`)= **不做快照**,不碰 `getItems()`。

⚠ **唯一風險在外掛側**:任何監聽 `InventoryMoveItemEvent` 的外掛只要呼叫 `event.getSource().getContents()` 或 `getItem()`,就會當場把容器物化,而且會關掉 skip 旗標讓事件每次都 fire。這不是我們的責任,但它會污染 attribution(`LazyContainerRuntime.java:365-372` 把任何 `net.minecraft.*Hopper*` frame 記成 hopper 桶,外掛在更外層時仍歸 hopper)。

### 3. `hopperPush` 裡 `origItemStack.setCount()` 的技巧(`3-paper:232-261`)——省的是 ItemStack 複製

原版每次嘗試推一格都 `self.removeItem(slot, 1)` 再視情況 `setCount` 復原(`1-vanilla:158-167`)。Paper 改成先把原 stack 的 count 暫時改成 `hopper-amount`,推成功才 `copy(true)` 一份寫回(`3-paper:252-257`),失敗就把 count 復原(`3-paper:261`)。**省的是每 tick 每格一次 ItemStack 配置**,與 NBT 解碼無關。

順帶:`canMergeItems` Paper 把原版的 `<=` 改成 `<`(`3-paper:722`,註解直說 "used to return true for full itemstacks?!")。我們摘要的滿判準是 `atMax = count >= max`(`LazyContainerTemplate.java:714`),對齊的是 `isFullContainer` 的 `count < max`(`3-paper:470`),**不是** `canMergeItems`。兩邊各自對齊各自的呼叫點,沒有錯配。

### 4. `getSlots` 的 `CACHED_SLOTS`(`3-paper:434-452`)——**Paper 沒改,而且對雙箱是失效的**

這段 Paper 與原版逐字相同(我用 `diff 1-vanilla:174-216 vs 3-paper:434-476` 驗過,輸出 IDENTICAL)。快取陣列宣告是 `private static final int[][] CACHED_SLOTS = new int[54][]`(`3-paper:34`),而判斷是:

```java
438  int containerSize = container.getContainerSize();
439  if (containerSize < CACHED_SLOTS.length) {   // 54 < 54 → false
...
449      return createFlatSlots(containerSize);   // 雙箱每次都 new int[54]
```

**雙箱容器大小正好是 54,`54 < 54` 為 false ⟹ 每一次 `isFullContainer`、每一次 `suckInItems` 都現配一個 `int[54]`。**這是上游一個 off-by-one,單箱/木桶(27)有快取,雙箱沒有。跟我們沒關係(是配置不是解碼),但如果哪天要送 upstream patch,這是最好摘的一顆。

### 5. cooldown 三條(`3-paper:138-140`、`264-266`、`305-307`)——這是**唯一**已經在幫我們壓解碼次數的東西

- **spigot `ticks-per.hopper-check`**(`3-paper:138-140`):`tryMoveItems` 回 false(這 tick 什麼都沒搬)且 `hopperCheck > 1` 時,直接把 cooldown 設成 hopperCheck。預設值 1(`2-spigot/SpigotWorldConfig.java:285`),**艦隊實際設定也是 1**(`EndRod-paper-folia/tmp-r178h2-smoke/spigot.yml:126`)。
- **paper `hopper.cooldown-when-full`**(預設 true,`3-paper/WorldConfiguration.java:492`):推不進去(`3-paper:264-266`)或拉不出來(`3-paper:305-307`)時給 8 tick cooldown。

意義:**目標容器塞滿時,漏斗的重複探測已經被 Paper 壓到 1/8。**所以「同一個容器被反覆問滿不滿」在生產上不是問題;真正的成本是**第一次觸碰**——解一次之後 `pending=false`,之後全部走原版速度。這跟實測「99.6% 從頭到尾沒被解」「穩態每台每小時 0.22 次尖峰」「偶發一個 tick 累積多次解碼造成 5-16 秒凍結」完全吻合:**這是 chunk 載入波造成的首觸潮,不是穩態負載。**

### 6. `disable-move-event` / `ignore-occluding-blocks`(`3-paper:493-494`)

- `disableMoveEvent` 預設 false;它只是強制把上面第 2 條的 skip 旗標打開。
- `ignoreOccludingBlocks` 預設 false;在 `3-paper:691` 生效:目標位置是實心遮蔽方塊時,跳過 `getEntityContainer` 的 AABB 實體搜尋。**省的是實體查詢,不是解碼。**

另外兩個同族的「先數監聽器再做事」:`InventoryPickupItemEvent`(`3-paper:521-529`)、`HopperInventorySearchEvent`(`3-paper:640`、`656`)。都與我們無關。

### 7. Paper 樹裡有一段**沒接上的死碼**,不要拿來當基礎

`3-paper:381-417` 的 `allMatch` / `anyMatch` / `STACK_SIZE_TEST` / `IS_EMPTY_TEST` 在整個檔案裡**沒有任何呼叫點**(我 grep 過該檔,只有宣告行命中)。這看得出來 Paper 本來打算用它們取代 `isFullContainer`/`isEmpty`,做到一半沒接。而且 `anyMatch` 有 bug——迴圈跑完落到 `3-paper:414` 的 `return true;`,**沒有任何一格通過測試時也回 true**。目前是死碼所以無害,**但我們絕對不能拿這兩個 helper 當「Paper 已經幫我們做了短路」的依據。**

---

## 三、你點名的四個問題,逐題回答

### (1) Paper 有沒有在 `ejectItems` 之前先檢查漏斗自己是空的?

**有,而且原版就有。** `3-paper:192` 的 `fullState != HOPPER_EMPTY` 對應 `1-vanilla:115` 的 `!entity.isEmpty()`。漏斗空 ⟹ 完全不碰目標容器 ⟹ 零解碼。這條路徑不是我們的責任,也不需要我們做任何事。

**反過來的推論才是重點:`isFullContainer` 被呼叫時,漏斗保證非空、保證接下來會嘗試推入。**

### (2) `isFullContainer` 在 Paper 有沒有被短路或快取?

**完全沒有。** `3-paper:465-476` 與 `1-vanilla:205-216` 逐字相同(diff 驗證),Folia 也沒改(paper↔folia diff 沒有碰到這段)。沒有快取、沒有 dirty flag、沒有短路。**這整塊仍然 100% 是我們的責任。**

它的行為細節值得記下來:

```java
468  for (int slot : slots) {
469      ItemStack itemStack = container.getItem(slot);
470      if (itemStack.getCount() < itemStack.getMaxStackSize()) {
471          return false;
```

空格一定會讓它回 false:`ItemStack.EMPTY` 的 `getCount()` 是 0,`getMaxStackSize()` 走 `ItemInstance.java:14-16` 的 `getOrDefault(MAX_STACK_SIZE, 1)`,而空 stack 的 `getComponents()` 回 `DataComponentMap.EMPTY`(`ItemStack.java:232-235`)⟹ 得 1 ⟹ `0 < 1` ⟹ 回 false。**所以只要有一格是空的,`isFullContainer` 在碰到那一格就結束。**典型情況只需要 slot 0 一格,卻要付整箱 27 條 entry 的解碼——這正是 s45 那條鏈的形狀。

### (3) Folia 的 region threading 對 `CompoundContainer`(雙箱)怎麼處理?

**什麼都沒做。** `diff 3-paper/CompoundContainer.java 4-folia/CompoundContainer.java` → **完全相同**;`ChestBlock.java` 也**完全相同**。Folia 對雙箱沒有加任何鎖、任何 thread check、任何 region ownership 斷言。

安全性完全靠 regioniser 的幾何保證:`ServerLevel.java:711-719` 建 regioniser 時 `regionSectionMergeRadius = 1`(第四個參數),一個 section 是 `1 << getRegionChunkShift()` 個 chunk。**相鄰 chunk 必定在同一個 region ⟹ 雙箱兩半永遠在同一條執行緒上 tick。**

對我們的意義有三:

1. **`CompoundContainer.getItem`(`4-folia:78-80`)只是把 slot 映射到某一半,沒有任何同步。**我們在 `lazycontainer$containerFullState`(`LazyContainerTemplate.java:456-468`)自己拆 `container1`/`container2` 是對的,而且是**唯一**能做的事——Folia 沒給我們任何可以借力的東西。
2. **s45 那 16.19 秒不是鎖競爭,是純工作量。**單一 region 執行緒上一個 tick 內堆了 N 次首觸解碼,沒有人在等鎖。所以「加鎖優化」「拆執行緒」這類方向對這個症狀無效——**要減的是那一 tick 內的解碼「次數」,不是單次成本。**
3. `CompoundContainer.isEmpty()`(`4-folia:69-71`)= `container1.isEmpty() && container2.isEmpty()`,兩半各自走 `BaseContainerBlockEntity.isEmpty()`(`4-folia/BaseContainerBlockEntity.java:94-99`)迭代 `getItems()` ⟹ **一次呼叫可以同時物化兩半。**這是雙箱比單箱貴一倍的地方。

另外 Folia 對漏斗只做了 region 化改寫,沒有新優化:`tickedGameTime` 改成 `Long.MIN_VALUE` 起始並加 `updateTicks` 位移(`4-folia:36-40`、`76-83`)、`level.getGameTime()` → `getRedstoneGameTime()`(`4-folia:143`)、`ignoreBlockEntityUpdates` 靜態欄位 → `IGNORE_TILE_UPDATES` ThreadLocal(`4-folia:307-309`、`601-603`)。

⚠ **`IGNORE_TILE_UPDATES` 是一個我們要小心的窗口**:`tryMoveInItem` 在 `set(TRUE)` 與 `set(FALSE)` 之間呼叫 `container.setItem(slot, itemStack)`,而 `setItem` 走 `BaseContainerBlockEntity.setItem`(`:126`)→ `getItems()` → **我們的 ensure guard 就在這個 ThreadLocal 為 true 的窗口內執行**。只要 `ensure()` 內部不去呼叫 `setChanged()` / 不去發方塊更新,就沒事(現行實作是填清單+翻旗標,符合)。**但這是「以後改 ensure() 時要重新驗」的一條**:任何在 ensure 裡新增的 `setChanged()` 都會被這個 ThreadLocal 靜默吞掉。

### (4) Paper 的 hopper 對「目標是 shulker」的 `getSlotsForFace` 行為

Paper/Folia **完全沒改**。`ShulkerBoxBlockEntity` 是 `WorldlyContainer`(`4-folia:35`),`getSlotsForFace` 無條件回傳靜態 `SLOTS = IntStream.range(0, 27).toArray()`(`4-folia:43`、`275-277`)——不看方向、不看開蓋動畫、不看 `openCount`。

實務影響:

- 走 `getSlots` 的 WorldlyContainer 分支(`3-paper:435-436`)⟹ **回傳共用靜態陣列,沒有配置**(比雙箱好,雙箱每次 new int[54])。
- `isFullContainer` 對 shulker 一樣是「碰第一格就觸發我們的 ensure」,行為與木桶完全相同,**沒有任何 Paper 短路可以借。**
- 唯一的 shulker 特例是 `canPlaceItemThroughFace`(`4-folia:280-282`):禁止把 shulker 放進 shulker。這發生在 `tryMoveInItem` 的 `canPlaceItemInContainer`(`3-paper:577`)**之後** `container.getItem(slot)`(`3-paper:576`)已經跑完——**所以就算最後被拒絕,容器還是已經被物化了。**

---

## 四、哪些路徑已經被既有優化壓掉了 / 哪些仍是我們的責任

| 路徑 | 誰在管 | 現況 |
|---|---|---|
| 漏斗自己是空的 ⟹ 不碰目標 | 原版 `1-vanilla:115` / Paper `3-paper:192` | **已壓掉,零解碼,不是我們的事** |
| 目標塞滿 ⟹ 8 tick 才重試 | Paper `cooldown-when-full`(`3-paper:264-266`) | **已壓掉重複探測**;但首觸那次照樣付錢 |
| 閒置漏斗探測頻率 | spigot `ticks-per.hopper-check`(`3-paper:138-140`) | 艦隊設 1 = **沒開這個閥門**(見第六節) |
| 目標是遮蔽方塊 ⟹ 跳過實體搜尋 | Paper `ignore-occluding-blocks`(`3-paper:691`) | 預設關;省的是實體查詢不是解碼 |
| Bukkit 事件開銷 | Paper skip 旗標(`3-paper:221-223`) | 已壓掉;**但外掛監聽器仍可自己物化容器** |
| **`isFullContainer` 掃目標容器** | **沒人管**(`3-paper:465-476` = 原版逐字) | **100% 我們的責任(摘要快查)** |
| **`suckInItems` 逐格掃來源** | **沒人管** | **我們的責任;已被 `slotProvenEmpty` 完整收割** |
| **`tryMoveInItem` 的 `getItem` + `isEmpty`** | **沒人管** | **我們的責任,而且是目前最大的漏網之魚(見下)** |
| 比較器讀取(`ChestBlock.java:380`) | 沒人管 | 本來就要讀全部格,逐格解碼幫不上 |

---

## 五、對「逐格延遲解碼」的判斷(這是本鏡頭的重點)

### 5.1 推入路徑上,逐格解碼**省不到東西**——這是結構性的

把 Paper 的呼叫鏈攤開:

```
ejectItems              3-paper:420
 └ isFullContainer      3-paper:427   ← 我們的摘要掛在這
    · 回 true  ⟹ return false at :429  ← 唯一真正省下解碼的答案
    · 回 false ⟹ 往下
 └ hopperPush           3-paper:431
    └ hopper.getItem(i) 3-paper:229   (漏斗自己,不是我們的)
    └ callPushMoveEvent 3-paper:242   (取消 ⟹ return false at :245,目標未被碰)
    └ addItem           3-paper:249 → :543
       └ tryMoveInItem  3-paper:573
          ├ container.getItem(slot)   3-paper:576  ← 物化
          └ container.isEmpty()       3-paper:579  ← 物化(整箱掃描)
```

因為 `isFullContainer` 只在漏斗**非空**時才會被呼叫(`3-paper:192` 保證),**每一次「不滿」的回答後面必然接一次推入嘗試,而推入嘗試在 `3-paper:576` 就把整箱物化了。**

所以:

> **摘要答「不滿」在解碼上省零。只有答「滿」才真的省。**

這件事 `LazyContainerRuntime.java:49-53` 的註解其實已經寫對了(「答『不滿』只是省掉一圈掃描,後續 hopperPush 仍可能觸發物化」),我這邊是從源碼把「可能」升級成「必然」。

推論到逐格解碼:**如果逐格解碼的目的是讓 `isFullContainer` 只解 1 格而不是 27 格,那省下的 26 格會在兩行之後被 `3-paper:576/579` 討回去。總工作量不變,只是換了順序。**

### 5.2 `3-paper:579` 是一顆可以免費拆掉的地雷(這比逐格解碼值錢)

```java
577  if (canPlaceItemInContainer(container, itemStack, slot, direction)) {
578      boolean success = false;
579      boolean wasEmpty = container.isEmpty();          // ← 整箱掃描,每格都跑一次
...
602      if (wasEmpty && container instanceof HopperBlockEntity hopperBlockEntity && ...) {
```

`wasEmpty` 唯一的消費點是 `3-paper:602`,而且被 `container instanceof HopperBlockEntity` 守著。Java 的 `&&` 由左而右短路,所以把 `579` 換成等價的

```java
boolean wasEmpty = (container instanceof HopperBlockEntity) && container.isEmpty();
```

**在語意上逐字等價**(可從 `602` 直接證明),而且:

- 目標是箱子/木桶/shulker 時,`isEmpty()` 根本不會被呼叫 ⟹ **少掉一整條物化觸發路徑**
- 順帶消掉原版的 O(n²):`addItem` 迴圈最多掃 54 格,每格再 `isEmpty()` 掃 54 格 = 2916 次 `ItemStack.isEmpty()`(雙箱、每次推入嘗試)

這是一個 **INVOKEINTERFACE 單點 redirect**,跟我們已經在做的 `ContainerHelper.loadAllItems/saveAllItems` redirect 是同一種手法(`LazyContainerTransformer.java:55-57`)。**原版與 Paper 逐字相同(`1-vanilla:318` = `3-paper:579`),Folia 也相同**,所以簽章比對風險低。

⚠ 但要誠實:拆掉 `579` **不會**消掉 `576` 的 `container.getItem(slot)`。所以推入路徑仍然會物化。這顆的價值是「少一條觸發路徑 + 拿掉 O(n²)」,不是「讓推入路徑零解碼」。

### 5.3 逐格解碼真正有價值的窄縫:被外掛取消的推入

`callPushMoveEvent` 被取消時,`3-paper:243-246` 直接 return,**目標容器完全沒被碰過**。這種情況下 `isFullContainer` 是**唯一**的觸碰者,逐格解碼(只解 slot 0)就是純賺。

艦隊上這種情況存在嗎?領地保護類外掛(GriefPrevention / 容器鎖)確實會取消打進受保護容器的 `InventoryMoveItemEvent`。**這是唯一值得先量的數字**:「isFullContainer 觸發的解碼裡,有多少比例後面那次 push 被事件取消了」。量法:在 `hopperPush` 的取消分支(`3-paper:245`)加一個計數,跟 `fullUnknownDirty` 對照。如果這個比例低(<10%),逐格解碼在漏斗路徑上就沒有商業理由。

### 5.4 逐格解碼會撞上「逐位元組不變」鐵則

現行存檔規則是二選一:`pending && raw != null` ⟹ 原樣回寫 raw;否則正常 encode。逐格解碼會產生第三種狀態「部分物化」——存檔時就得把「沒動過的格用 raw entry、動過的格重新 encode」合併起來,**那條合併路徑一旦有一個 entry 順序或欄位順序不同,逐位元組不變就破了**,而這是已經在 109 台上跑的東西,回歸=事故。

**唯一安全的形狀是:逐格解碼只服務「唯讀探測」,任何寫入一律先升級成整箱 `ensure()`。**這樣存檔規則不變(要嘛全 raw、要嘛全 encode)。但這個限制正好把 5.1 的結論再確認一次——推入路徑第一件事就是寫入,所以逐格在那裡沒有立足點。

### 5.5 摘要棄答那一群,逐格解碼也救不了

摘要棄答的條件很窄(`LazyContainerTemplate.java:729-739`):**每一格都有 entry**,而且沒有任何一格是「乾淨且 count<max」。也就是說,棄答的容器**一定是滿格佔用的**。那麼 `isFullContainer` 就不會在 slot 0 早退——它得一路掃到第一個 `count < max` 的格。

而造成棄答的族群正是「帶自訂 component 的東西」(附魔裝、命名物、shulker),這些的 `max_stack_size` 多半是 1、count 也是 1 ⟹ `count >= max` ⟹ **每一格都通過,`isFullContainer` 掃完全部 27 格才回 true。**

> **逐格解碼在「最需要它的那一群容器」上,平均要解的格數最接近全部。**

這是我這個鏡頭最反直覺、也最該讓服主知道的一句。

---

## 六、我建議的優先順序(從這個鏡頭看)

1. **免費、零程式碼:把 `ticks-per.hopper-check` 從 1 調到 4 或 8**(`spigot.yml`,`3-paper:138-140` 生效)。它只在「這 tick 什麼都沒搬」時才給 cooldown,所以**只節流閒置漏斗**,不影響運作中的產線;代價是閒置漏斗最多晚 0.4 秒才反應。它把「同一 tick 內落地的首觸數量」直接除以 8,**正對 s45 那種 chunk 載入波的凍結**。這是服主的遊戲手感決定,不是我們的技術決定——**建議先問過再改,而且屬於正式分流 config,依鐵則要先報。**
2. **低風險、可證明等價:redirect `3-paper:579` 的 `container.isEmpty()`**(第 5.2 節)。少一條物化觸發路徑 + 拿掉 O(n²)。
3. **先量再決定:數「被取消的 push」比例**(第 5.3 節)。這個數字決定逐格解碼在漏斗路徑上到底有沒有商業理由。
4. **逐格延遲解碼:在漏斗路徑上,依現有證據不建議做。**理由是 5.1(推入必然物化)+ 5.5(棄答族群平均要解全部格)+ 5.4(撞逐位元組鐵則)。若還是要做,唯一安全形狀是「只服務唯讀探測、任何寫入先整箱 ensure」。
5. **真正該打的是「一個 tick 內的首觸數量」,不是「單次解碼成本」。**Folia 的 region 保證告訴我們那 16 秒是純工作量、沒有鎖(第三節 (3))。方向應該是把首觸攤到多個 tick(例如一個 region 每 tick 只准 N 次 ensure,超過的讓 `isFullContainer` 回「不知道」走原版路徑——**注意這條要小心,「走原版路徑」就是當場解碼,不是延後**;真要延後得讓漏斗這 tick 放棄,那就是行為改變,需要另外設計)。

---

## 七、與既有優化的衝突檢查(結論:目前沒有衝突)

| 檢查項 | 結論 |
|---|---|
| `getFullState` 直讀 `hopper.items` 欄位(`3-paper:153`) | 安全:漏斗沒被 lazify(`LazyContainerTransformer.java:110-116` 只動 Chest/Barrel/Shulker)。**列入「永不 lazify 漏斗」紅線。** |
| Chest/Barrel/Shulker 是否有繞過 `getItems()` 的直讀欄位 | 已逐檔 grep:`ChestBlockEntity:78/195`、`BarrelBlockEntity:33/126`、`ShulkerBoxBlockEntity:59/266` 都是 `getContents()`/`getItems()` 本體,已被 guard 覆蓋。唯一例外 `ShulkerBoxBlockEntity:172` 的 `this.itemStacks.size()`(= `getContainerSize()`),只讀大小、不需解碼,安全。 |
| Folia `IGNORE_TILE_UPDATES` ThreadLocal 窗口 | 目前安全(ensure 不發方塊更新)。**列入「改 ensure 時要重驗」清單。** |
| Folia 對 CompoundContainer 的執行緒保證 | 相鄰 chunk 必同 region(`ServerLevel.java:711-719`,mergeRadius=1),雙箱兩半同執行緒。我們自己拆 container1/container2 的做法正確且無替代。 |
| Paper skip 事件旗標 | 我們沒碰;Folia 版在 `RegionizedWorldData` 是 per-region 狀態,我們也沒碰。 |
| `canMergeItems` 的 `<` vs `<=` | 我們的摘要對齊的是 `isFullContainer` 的判準,不是 `canMergeItems`,沒有錯配。 |
| Paper 死碼 `allMatch`/`anyMatch` | **不可依賴**;`anyMatch`(`3-paper:399-415`)無命中時回 `true`,是 bug。 |
