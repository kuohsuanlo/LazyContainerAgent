# 「針對某一格解碼」要不要做?—— 研究結論與出貨內容(2026-08-29)

> 給服主的白話技術文。每個結論都指得到源碼行號或實測數字;源碼路徑以
> `reference-minecraft-nms/4-folia/`(艦隊跑的 EndRod 基底)為準。
> 研究方法:6 個獨立鏡頭(漏斗存取/其他消費者/設計規格/靜態分析可行性/component 安全/Paper 優化)
> → 3 個裁決者(風險/收益/工程三角度)→ 完整性批評者;共 10 agent、52 分鐘。
> 全文在 `docs/research-2026-08-29/`。

## 一句話

**逐格解碼不做。改做「放寬摘要」——零解碼、只改一個純函式、已紅綠出貨。**
三個裁決者從三個不同角度算出同一個答案。

---

## 1. 為什麼逐格解碼不值得:漏斗兩行之後就把整箱討回去

漏斗往箱子推東西的路徑(`HopperBlockEntity.java`):

```
ejectItems:439   if (isFullContainer(container, dir)) return false;   ← 我們的摘要 hook 掛在這裡
                 ↓ 摘要答「不滿」或「不知道」
hopperPush:233 → addItem:556 → tryMoveInItem:586
tryMoveInItem:589   ItemStack current = container.getItem(slot);       ← getItem = getItems().get(slot)(BaseContainerBlockEntity:105)
tryMoveInItem:592   boolean wasEmpty = container.isEmpty();            ← isEmpty = for 每一格 getItems()(BaseContainerBlockEntity:94)
```

`:592` 那個 `isEmpty()` 是**全格掃描**,而且算出來的 `wasEmpty` 只在目標是漏斗時才用到(`:615`)——目標是箱子時算完就丟。這是原版行為(`1-vanilla` 同款),不是 Paper 的鍋。

所以:逐格解碼能把 `isFullContainer` 的解碼從 27 格縮成 1 格,但**同一個 tick 往下三層,整箱照樣展開**。省下的東西幾微秒後全部吐回去。

其他路徑也一樣:
- 抽取成功 ⟹ `hopperPull:308` `container.setItem(...)` ⟹ `getItems()` ⟹ 整箱展開(寫入必須物化)
- 比較器 `AbstractContainerMenu.java:896-901` 無提前跳出,必然整份
- 玩家開箱 `broadcastChanges:259-266` 每 tick 全掃
- Bukkit `CraftInventory.getContents:80-84` 必然整份

**真正只碰 1 格、而且答對就零解碼的,只有 `isFullContainer` 那一格——那正是摘要 hook 已經在做的事。** 逐格解碼救的是摘要「不肯答」的殘量;讓摘要肯答,殘量就沒了。

### 服主問的「漏斗是不是五個都會要求」
會,但那**五格是漏斗自己的肚子**(`HopperBlockEntity:33` `HOPPER_CONTAINER_SIZE = 5`),掃它們的是 `getFullState:160-189`(Paper)與 `hopperPush:237-238` 外圈。**漏斗自己的五格從來不是 lazy 的**(agent 只把 Chest/Barrel/ShulkerBox 變 lazy),永遠不需要解碼。有價值的只有另一側——目標箱子的 27 格。

### 逐格解碼的代價(如果硬做)
- 新增 `entryOff/slotEntryIdx/decodedMask` 三個欄位;`pending` 的「純 volatile 寫」變成 bitmask 的 read-modify-write(一整類現行設計結構上不可能有的新競態)
- 存檔要輸出「已解格重編碼 + 未解格原始 entry」的混合體——**第一次在 109 台正式服上引入「存檔時刪 entry」這個動作**,錯一次就是該格永久空白、無例外、無 log
- 會讓 shadow(目前唯一在真實玩家資料上逐位元組對帳的證據)失效
- 這是重寫,不是優化

---

## 2. 放寬摘要:做了什麼、為什麼安全

### 問題
摘要要證明「這箱全滿」才能讓漏斗零解碼掉頭。舊規則:entry 帶**任何**非 `max_stack_size` 的 component 就不敢證明滿(怕 component 解碼失敗讓那格實為空)。
s3 商場磁碟掃描(4 個 region,唯讀副本已刪):**12,754 個全滿箱,76.3% 因此無法證明滿**——commit 排行 `custom_data` 27.7 萬、`container`(箱中界伏盒)24.5 萬、enchantments、repair_cost、lore/custom_name。廢土的商店箱幾乎全中。

### 真 codec 說了什麼(`ComponentPartialSemanticsTest`,26.2 + DFU 10.0.21,16 種形狀全部釘住)
| 形狀 | vanilla 解出 |
|---|---|
| lore 給字串 / enchantments 給字串 / 不存在的 key | **有物品**,壞 component 被丟 |
| max_stack_size 給字串 / 200 / 0 | 有物品,max 退回物品預設 |
| 合法 max=16 + 壞 lore | 有物品,**max 仍是 16**(好的留著) |
| components 整個不是 compound | 有物品,整包被丟 |
| 裸 `max_stack_size` / `:max_stack_size` | **正規化成 minecraft:,生效** |
| `!minecraft:max_stack_size` 值=Int | 被丟,不生效 |
| `!minecraft:max_stack_size` 值=`{}` | 生效,max=1 |
| 同 entry 重複拼法(ns + 裸) | 勝者由 fastutil map 迭代序決定,兩種插入序都取同一個 |

源碼鏈:`ItemStack.MAP_CODEC`(ItemStack.java:110-120)→ `DataComponentPatch.CODEC = dispatchedMap`(DataComponentPatch.java:23)→ `optionalFieldOf` 在 DFU 10.0.21 是嚴格的(`Codec.optionalFieldOf` 推 `iconst_0`)→ 但 `TagValueInput.TypedListWrapper` 保留 partial;max 只由 `ItemInstance.getMaxStackSize()=getOrDefault(MAX_STACK_SIZE,1)`(ItemInstance.java:14)決定;key 解析走 `PatchKey.CODEC` 先剝 `!` 再 `Identifier.tryParse`(DataComponentPatch.java:340-360)。

**結論:壞 component 永遠不會讓格子空掉。摘要只需要看 `minecraft:max_stack_size`。**

### 新規則(`LazyContainerTemplate.lazycontainer$computeSummary`,components 區塊)
- 用 `Identifier.tryParse` 正規化 key(跟 vanilla 同一支),剝 `!` 前綴
- 命中 `minecraft:max_stack_size` ≥2 次(重複拼法)或出現移除記號 ⟹ **不證明**(語意由 map 迭代序/值型別決定,外部不可複現)
- 合法 1..99 ⟹ 採用;非數值/超範圍 ⟹ 物品預設
- 其他任何 component 一律不影響「格子有無物品」與 max
- `computeSummary` 整段包 try/catch(批評者抓到:沒包的話任何例外會讓 chunk 載入丟掉整個 BE、活體 reload 後存空清單 ⟹ **整箱從磁碟消失**;現在一律當「整份放棄」)

### 不對稱鐵律下的安全性
放寬只會讓摘要更常答「滿」。答錯「滿」的後果:`ejectItems:439` return false ⟹ 漏斗不推、箱子沒被物化、raw 原封保留、玩家一開箱就恢復。是「農場停擺」,**不是掉東西**。而三支放寬測試 + 12 種拼法測試 + 28,000 份 fuzz(產生器灌入 13 種 component 壞法 + 5 種拼法陷阱)全部以真 codec 為 oracle,摘要開口就必須雙向一致。

### 實測
rig(Paper 26.2,漏斗在箱上往下推):放寬前 `fullQ=437/1/1/0`——全滿但每格帶 custom_name 的箱被迫整箱解碼;放寬後 `fullQ=708/1/0/0`——證明滿、零解碼;半空箱照常推入。

---

## 3. 「有沒有辦法嚴格靜態分析複製或遺失?」——誠實的答案

**做得到的是「在寫明的假設下,每次 build 都由機器重新驗一遍」;做不到的是「數學上證明線上永遠不會掉東西」。**

| 路線 | 可行性 | 能證明到什麼 | 現況 |
|---|---|---|---|
| (a) 窮舉狀態機模型(純 Java) | ✅ 27 格 1,197 狀態 3ms;任兩執行緒各 3 操作全交錯 3,536 萬狀態 26 秒 | 交錯層:不掉物/不複製 | 原型 `scratchpad/LazyModelCheck3.java`;**確定性重現 A1/A2 兩個真 bug(1ms)** |
| (b) 差分模糊 vs vanilla codec | ✅ 已在用 | 語意層:摘要/解碼與原版對同一份資料一致 | `SummaryDifferentialTest` 28k+ |
| (c) JMM happens-before | ⚠️ 只能人工 | 弱記憶體重排 | A1 的證明是這型 |
| (d) **bytecode 機械檢查** | ✅ 0.17 秒 | 織入後的碼符合鎖紀律、13 個 guard 到位、redirect 無殘留、hook 在入口 | **本次接進 build.sh 當閘門**(`tools/LockPolicyCheck.java`,18 項/0 違規,違規 ⟹ build 失敗) |
| (e) shadow 資料層不變式 | ✅ 生產中 | 真實資料上寫回的 == 原版會寫的 | 維持 |

關鍵洞見:**「可窮舉」不是這個問題天生的性質,是現行鎖紀律買來的。** 同樣的逐格解碼把鎖拿掉,狀態數每多一格 ×2.7,27 格 ≈ 10¹⁴,跑不完——不只正確性變難,連驗證能力都一起失去。

也要老實講:窮舉模型的作者一個下午就在模型裡犯了兩次錯(一次讓真 bug 漏掉、一次生出 3,202 個假警報)。**模型證明的永遠是模型,不是那份 jar。** 所以 (d) 那個對真實 bytecode 的檢查器最值錢——它驗的就是要出貨的東西。

### 這次出貨的閘門(全部自動,test.sh + build.sh)
1. 摘要開口必與 vanilla 雙向一致(含 12 種拼法、13 種 component 壞法、28k fuzz)
2. `ComponentPartialSemanticsTest` 釘住 partial 語意——**版本升級時第一個該紅的哨兵**;它一紅,放寬規則整條退回
3. `EnsureRaceTest` 五支(含確定性、單核不假綠)
4. bytecode 政策閘門 18 項 0 違規
5. `rawBytesRoundTrip`:decode 同一份 bytes 完全確定性 + 結構相等(見附註)

---

## 4. 交付
- jar:`target/LazyContainerAgent.jar` md5 `64f1686b59f6ade7f43f40c480e0c586`(鐵則:jar 不代鋪)
- 測試:`test.sh` 50/50(含全佔用 fuzz:28,777 份開口答滿、0 錯答;12 種拼法;16 種 partial 語意釘樁)
- 建置閘門:`build.sh` 第 5 步 bytecode 政策檢查 18 項 0 違規

## 5. 部署建議
- 先鋪一台非主力分流,加 `-Dlazycontainer.shadow=true` 跑 72 小時,確認 `summaryMismatch=0` 且 log 出現 `fullQ=` 行;再擴散。
- 觀察 `decodeSpikeMs`(尖峰秒數)與 `fullQ` 第三桶(佔滿但證明不了)是否歸零。
- 同一批**不要**夾帶其他改動——特別是有人提議拆掉 `tryMoveInItem:592` 那行 `isEmpty()`:那會改到未開封戰利品箱的開封時機(`RandomizableContainerBlockEntity.isEmpty:49-52` 會 `unpackLootTable`),不是純優化,要另外一批。

## 附註:Paper 的 CompoundTag key 順序
Paper 把 `CompoundTag` 換成 fastutil `Object2ObjectOpenHashMap(8, 0.8f)`(`CompoundTag.java:54/168`)。decode→encode 之後 compound 內 key 順序可能翻轉(實測週期 2:b≠b2、b==b3),但 **decode 同一份 bytes 完全確定性**。生產存檔每次都從同一份 raw 出發 ⟹ 每次寫出相同位元組;raw 內 key 順序可能與磁碟原檔不同——vanilla 自己重存也會改順序,**不是資料變更**。往返測試已改成斷言「決定性 + 結構相等」。
