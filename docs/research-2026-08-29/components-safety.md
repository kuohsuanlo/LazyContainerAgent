# components-safety

**結論**:放寬摘要應該優先於逐格解碼:現行「有 component 就不能證明滿」建立在一個 26.2 不成立的前提(壞 component 會讓格子變空)上,真實 codec 是 partial 保留;只改 template 686-712 這 27 行、把「唯一要看的 key」收斂成 minecraft:max_stack_size(含 ! 移除形式、命名空間正規化、命中兩次就棄權),廢土商店那種滿箱具名商品就從 0% 可證明滿變 100%,20 萬輪惡意 fuzz 零衝突——而漏斗一旦答得出「滿」就是零解碼,逐格解碼救的只是剩下的殘量,該排在量完 fullQ 之後。

## 硬事實
- 一顆 component 解碼失敗不會讓格子變空:DispatchedMapCodec.java:49 `.setPartial(pair)` 把「解成功的那些 component」當 partial 傳出,OptionalFieldCodec.java:31 `parsed.map(Optional::of).setPartial(parsed.resultOrPartial())` 讓嚴格 optionalFieldOf 也傳 partial,TagValueInput.java:426-430 的 iterator 直接把 partial 當成功值塞進該 slot(DFU 10.0.21 反編譯,jar=/home/logocat/Server/claude-sandbox/workspace/sv-papertest/libraries/com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar)
- 實測 headless NMS(probe/ComponentProbe.java):custom_name=int7、lore=字串、damage=字串、damage=-5、custom_data=字串、enchantments=字串、trim=字串、attribute_modifiers=字串、未知 key、外來命名空間 key、無法 parse 的 key、components 整個是字串/清單 —— 全部 empty=false count=64 max=64,壞的那顆被丟掉
- 唯一會讓格子真的變空的是 id:Item.java:100-108 的 CODEC/CODEC_WITH_BOUND_COMPONENTS 走 fieldOf,失敗不帶 partial。實測 id=不存在物品 / minecraft:air ⟹ empty=true。現行摘要已經在檢查 id
- isFullContainer 的判準只依賴 count 欄位與 MAX_STACK_SIZE:HopperBlockEntity.java:477-488 用 `count < getMaxStackSize()`;ItemStack 沒覆寫 getMaxStackSize,吃 ItemInstance.java:14-16 的 `getOrDefault(DataComponents.MAX_STACK_SIZE, 1)`;全樹只有這一處取值。除了 minecraft:max_stack_size 沒有第二顆 component 影響 max
- ItemStack 建構子 ItemStack.java:269-277 純賦值,沒有驗證也沒有 clamp;validateStrict(ItemStack.java:160-168)不在容器載入路徑上(呼叫者只有 ItemInput.java:27、ItemStackTemplate.java:83、ItemStack.java:995)
- 移除記號會把 max 打成 1:DataComponentPatch.java:339-369 的 PatchKey.CODEC,REMOVED_PREFIX="!";實測 `!minecraft:max_stack_size` ⟹ count=1 max=1,vanilla 判滿
- 命名空間可省略:PatchKey.CODEC 走 Identifier.tryParse,實測 key 寫成 `max_stack_size`(無 minecraft:)照樣生效 ⟹ max=16。現行 template:697 的字串全等比對會漏掉這種寫法(目前因『漏掉就棄權』而安全,放寬後必須正規化)
- 兩種寫法同時出現時誰贏無法便宜複現:DispatchedMapCodec.java:63 是 putIfAbsent(先到先贏),而先後由 NbtOps map 迭代序決定。實測 DupKeyProbe:keySet 顯示 minecraft:max_stack_size 在前,生效的卻是無命名空間那顆(max=4)⟹ 命中 ≥2 次必須棄權
- count 出界不會丟 entry 而是退回 1:ExtraCodecs.java:682-688 + OptionalFieldCodec.java:31,實測 count=200 與 count=0 都變成 count=1(現行摘要對此棄權,方向安全)
- max_stack_size 自己不合法(200 / 0 / 字串)不會丟 entry,只是那顆被丟掉、退回物品原廠上限(DataComponents.java:110-112 用 ExtraCodecs.intRange(1,99))
- 廢土商店情境實測(probe/ShopFuzz.java,20000 個 27 格滿堆、每件帶 custom_name+lore+custom_data、1/40 格塞壞 component):V1 證明全滿=0,V2 證明全滿=20000,與 vanilla 衝突=0
- 惡意隨機差分實測(probe/WidenFuzz.java,20 萬容器,混入壞 id/air/缺 Slot/!minecraft:max_stack_size/無命名空間 max_stack_size/無法 parse 的 key/各式壞值):V1『佔滿但答不出來』23746 → V2 1256(−94.7%),V2 與 vanilla 滿判定衝突=0、假『證明為空』的格=0
- 要改的只有 LazyContainerTemplate.java:686-712 這 27 行(components 那段),Slot/id/count/後寫者勝/三態打包全部不動
- 現成的 shadow 覆核可直接當滾動機制:template 522-538 行的 lazycontainer$verifyFull 會拿真 items 重算 vanilla 判定、不符就 onSummaryMismatch 並改答不知道
- 109 台正式服的 log(LazyContainerAgent/logs/2026-08-28-*.log.gz)裡沒有任何 `fullQ=` 行,只有一行 ensure-attr 樣本 ⟹ 『放寬能救多少 %』在生產上目前是估的,只有離線 fuzz 數字

## 風險
- 白名單 vs deny-only 的取捨:deny-only(只擋 max_stack_size)在今天可證明正確,但未來 MC 版本若新增一顆會餵進 getMaxStackSize 的 component,deny-only 會靜默漏掉,白名單則會自動棄權。緩解=升版檢查表加一條『grep getOrDefault(DataComponents.MAX_STACK_SIZE 的取值點是否仍只有 ItemInstance 一處』
- 命名空間正規化是放寬後唯一的新增出錯面:現行字串全等比對之所以安全,是因為『比不到就棄權』;放寬後若用『不在黑名單就略過』的寫法,無命名空間的 max_stack_size 會被當成無關 component 略過 ⟹ 用錯 max ⟹ 有機會產生假『全滿』⟹ 漏斗永久停搬。必須用 Identifier.tryParse 正規化,且 ≥2 次命中就棄權
- 新引入的邊緣情境:放寬後,一箱『每格都乾淨、但其中一顆 component 的 codec 會拋例外(非 DataResult error)』的容器會被證成全滿、漏斗永不觸碰、永遠不物化、存檔逐位元組原樣寫回。資料不會掉(比 vanilla 更保守),但該容器等同凍結。現行規則因為要求零 component 而碰不到這一類
- partial 語意本身是 DFU 行為,不是 Mojang 明文契約:26.2-2 當初對非數值 Slot 選擇棄答,理由正是『兩家稽核對 DFU partial 語意結論相反』。本次結論同樣建立在 partial 上,差別是我有本機真 codec 的實測。緩解=保留 shadow 覆核先跑一台,並在升版時重跑 ComponentProbe
- Item 元件綁定時機:摘要用 item.getDefaultMaxStackSize() 讀 holder 的 bound 元件表;若有任何窗口在資料包綁定完成前就載入 chunk,讀到的會是未綁定狀態(Holder.java:230-232 areComponentsBound)。這個風險現行版本同樣存在、未觀察到,但放寬後被證成滿的容器變多,曝險面跟著變大
- 缺生產基線:上線前沒有真實 fullQ 分佈,無法承諾『解碼會少幾成』。建議先開 attribution 收一輪 fullQ 再談收益,否則交付時只能引用離線 fuzz
- 另一個 agent 已在 test.sh 加了 ComponentPartialSemanticsTest(第 47、57 行);我沒有動它,但併線時要確認兩邊的 partial 語意測試沒有重複或互相矛盾

## 報告
## 一句話

**現行「entry 帶任何非 `max_stack_size` 的 component 就不可證明滿」這條規則,建立在一個 26.2 實際上不成立的前提上。** 前提是「component 解碼失敗會讓該格實為空」——真實 codec 的行為是 **partial 保留:壞掉的那顆 component 被丟掉,ItemStack 照樣落格**。放寬之後,一箱「27 格滿堆的具名商品」從 **0% 可證明滿** 變成 **100% 可證明滿**(2 萬輪實測,零衝突)。這比逐格解碼便宜、風險小、收益大,應該先做。

---

## (1) 一顆 component 解碼失敗時,整格會怎樣?

### 源碼證明鏈(逐層)

路徑是 `ContainerHelper.loadAllItems` → `input.listOrEmpty("Items", ItemStackWithSlot.CODEC)` → `TagValueInput.TypedListWrapper.iterator()`。

| 層 | 檔案:行號 | 行為 |
|---|---|---|
| 容器 | `net/minecraft/world/ContainerHelper.java:39-45` | 逐 entry `itemStacks.set(item.slot(), item.stack())`,只過濾 `isValidInContainer` |
| entry | `net/minecraft/world/ItemStackWithSlot.java:8-16` | `RecordCodecBuilder`:`Slot`(optional,預設 0)+ `ItemStack.MAP_CODEC` |
| stack | `net/minecraft/world/item/ItemStack.java:110-120` | `id` = `fieldOf`(嚴格)、`count` = `optionalAlwaysPresentFieldOf(intRange(1,99),1)`、`components` = **`DataComponentPatch.CODEC.optionalFieldOf("components", EMPTY)`** |
| patch | `net/minecraft/core/component/DataComponentPatch.java:23-57` | `Codec.dispatchedMap(PatchKey.CODEC, PatchKey::valueCodec)` |

關鍵四行(DFU 10.0.21,jar 在 `/home/logocat/Server/claude-sandbox/workspace/sv-papertest/libraries/com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar`,我反編到 `/tmp/claude-1000/-home-logocat-Server-claude-sandbox/a0b371fe-72c5-491e-ab05-2164cd8dad7c/scratchpad/dfu/out/`):

1. **`DispatchedMapCodec.java:35-52`** —— 逐 entry 解;某顆失敗只是 `failed.add(input)`,最後
   `return finalResult.map(...).setPartial(pair)`(**第 49 行**),`pair` = **所有解成功的 component**。
   → 一顆壞 component ⟹ patch 是「Error 但帶著 partial(= 少那顆的 map)」。
2. **`OptionalFieldCodec.java:25-33`** —— 非 lenient 版本第 31 行:
   `parsed.map(Optional::of).setPartial(parsed.resultOrPartial())`。
   → **即使是嚴格 `optionalFieldOf`,partial 也會往上傳**;而且 `components` 整個不是 compound 時,partial = `Optional.empty()` ⟹ xmap 拿預設值 `DataComponentPatch.EMPTY`。
3. **`DataResult.java:215-228`(`Error.ap`)** —— record 的 applicative 組裝:只要每個欄位都有 result 或 partial,結果就是「Error 但帶著組好的 partial 物件」。`DataResult.java:169-174`(`Error.map`)、`MapCodec.java:389-393`(`MapCodecCodec.decode`)同樣保留 partial。
4. **`net/minecraft/world/level/storage/TagValueInput.java:413-437`** —— `TypedListWrapper.iterator()`:
   ```java
   case Error<T> error:
       reportIndexUnwrapProblem(index, value, error);
       if (!error.partialValue().isPresent()) break;   // 沒 partial 才丟掉
       return error.partialValue().get();              // 有 partial 就當成功值用
   ```

### 結論

> **「只丟掉那個 component」。整個 ItemStack 用 partial 保留,格子照樣有東西。**

只有 **沒有 partial 的失敗** 才會讓整格消失,而那只有一種來源:`id`(`Item.java:100-108` 的 `CODEC` / `CODEC_WITH_BOUND_COMPONENTS`,`fieldOf` 嚴格且失敗不帶 partial)。**現行摘要已經在檢查 id**。

### 實測(headless NMS,零 server)

`/tmp/claude-1000/-home-logocat-Server-claude-sandbox/a0b371fe-72c5-491e-ab05-2164cd8dad7c/scratchpad/probe/ComponentProbe.java`,結果在同目錄 `log.txt`:

```
custom_name = int 7 (invalid)          -> empty=false count=64 max=64 comps={}
lore = string                          -> empty=false count=64 max=64 comps={}
damage = string / damage = -5          -> empty=false count=64 max=64 comps={}
custom_data = string                   -> empty=false count=64 max=64 comps={}
enchantments = string                  -> empty=false count=64 max=64 comps={}
trim / attribute_modifiers = string    -> empty=false count=64 max=64 comps={}
兩顆同時壞                              -> empty=false count=64 max=64 comps={}
壞 custom_name + 合法 max_stack_size=16 -> empty=false count=64 max=16 ★ 壞的丟掉、好的留著
unknown / 外來命名空間 / 無法 parse 的 key -> empty=false count=64 max=64 comps={}
components 整個是字串 / 是清單            -> empty=false count=64 max=64 comps={}
--- 對照組(真的會讓格子變空的)---
id = 不存在的物品                        -> empty=true
id = minecraft:air                     -> empty=true
```

順帶挖到兩件目前沒用上、但值得記錄的事實:
- `count = 200` 或 `count = 0` **不會讓 entry 消失**,而是 partial 退回預設 **1**(`ExtraCodecs.java:682-688` + `OptionalFieldCodec.java:31`)。現行摘要對此棄權——方向安全,不必改。
- `max_stack_size = 200 / 0 / "16"`(自己不合法)**不會讓 entry 消失**,而是那顆被丟掉、退回物品原廠上限。

---

## (2) 誰會影響 maxStackSize?只有一個

`isFullContainer` 的判準是 `itemStack.getCount() < itemStack.getMaxStackSize()`(`net/minecraft/world/level/block/entity/HopperBlockEntity.java:477-488`)。

- `getCount()` 只來自 `count` 欄位(`ItemStack.java:1297`;建構子 `ItemStack.java:269-277` 純賦值、**沒有任何驗證或 clamp**)。
- `getMaxStackSize()` **ItemStack 自己沒有覆寫**,吃 `net/minecraft/world/item/ItemInstance.java:14-16`:
  ```java
  default int getMaxStackSize() { return this.getOrDefault(DataComponents.MAX_STACK_SIZE, 1); }
  ```
- 全樹 grep `MAX_STACK_SIZE` 的取值點只有這一處 + `Item.java:160-162` 的 `getDefaultMaxStackSize()`(= 原廠 prototype),而 `Item.components()`(`Item.java:156-158`)回傳的正是 codec 建 `PatchedDataComponentMap` 時用的同一份 holder 元件表 ⟹ template 用 `item.getDefaultMaxStackSize()` 當底值是對的。
- `ItemStack.validateStrict`(`ItemStack.java:160-168`,含「count 超過 max 就拒絕」)**不在載入路徑上**——呼叫者只有 `ItemInput.java:27`、`ItemStackTemplate.java:83`、`ItemStack.java:995`(`applyComponentsAndValidate`)。`validateComponents` 裡「不能又可損壞又可堆疊」(`ItemStack.java:284-287`)同理。

**答案:除了 `minecraft:max_stack_size`,沒有第二顆 component 會動到 max。** 摘要的 `count >= max` 判準只要把這一顆看好,其餘全部無關。

三個必須看好的特例(全部實測過):
- `minecraft:max_stack_size` 本身值不合法 ⟹ 真值退回原廠上限(可用,但保守起見我建議棄權)。
- **移除記號 `!minecraft:max_stack_size`**(`DataComponentPatch.java:339-369`,`REMOVED_PREFIX = "!"`)⟹ 真 max 變 **1**。實測 `count=1 max=1`,vanilla 判「滿」。
- **省略命名空間**:`PatchKey.CODEC` 走 `Identifier.tryParse`,所以 `"max_stack_size"`(沒有 `minecraft:`)**是合法且生效的**。實測 `max=16`。現行 template 的字串全等比對會漏掉這種寫法——目前因為「漏掉就棄權」而安全,**放寬後必須正規化命名空間,否則會變成真的漏洞**。
- 兩種寫法同時出現(`minecraft:max_stack_size` 與 `max_stack_size`)時,誰贏由 `NbtOps` 的 map 迭代序決定(`DispatchedMapCodec.java:63` 是 `putIfAbsent`,先到先贏),**外部無法便宜地複現**。實測 `DupKeyProbe`:keySet 顯示 `minecraft:` 在前,生效的卻是無命名空間那顆。⟹ **命中 ≥2 次就棄權**。

---

## (3) 可安全放寬的白名單

### 我的主張:白名單其實不必列舉

由 (1) + (2) 得到一條可證明的規則:

> **一個 component key 對摘要有影響,若且唯若它正規化後等於 `minecraft:max_stack_size`(含 `!` 移除形式)。其餘一切 key——認得的、不認得的、值壞掉的、外來命名空間的、根本不是合法 id 的——都既不會讓格子變空,也不會改變 max。**

依據:格子是否為空只看 `id` 與 `count`(`ItemStack.java:333-335` 的 `isEmpty()`);max 只看 `MAX_STACK_SIZE`(`ItemInstance.java:14-16`);壞 component 只會被 partial 丟掉(`DispatchedMapCodec.java:49` + `TagValueInput.java:426-430`)。

### 建議的 V2 判準(取代 template 686-712 行)

```
components 不是 CompoundTag ⟹ 不乾淨(保守;真值是 EMPTY,但不必賺這一點)
否則逐 key:
  正規化(去掉開頭 "!" 記為 removed;Identifier.tryParse;比對 namespace=minecraft && path=max_stack_size)
    不是 max_stack_size  ⟹ 略過(★ 這就是放寬)
    是,但 removed       ⟹ 不乾淨
    是,但命中第二次      ⟹ 不乾淨
    是,值非 NumericTag 或 intValue 不在 [1,99] ⟹ 不乾淨
    否則 max = 該值
```
其餘(Slot / id / count 三段)一字不動。

### 若服主要「分階段、只認白名單」,這是清單

每條都實測過「值壞掉時 ItemStack 仍在、max 不變」:

| component | 註冊行 | 為何安全 | 反例/注意 |
|---|---|---|---|
| `minecraft:custom_name` | `DataComponents.java:124` | 純顯示;`ComponentSerialization.CODEC` 失敗只丟自己 | 無 |
| `minecraft:item_name` | `:133` | 同上 | 無 |
| `minecraft:lore` | `:139` | `ItemLore.CODEC` | 無 |
| `minecraft:custom_data` | `:109` | `CustomData.CODEC`,外掛任意 NBT | 無 |
| `minecraft:enchantments` | `:143` | 不參與 max | 無 |
| `minecraft:damage` | `:117` | `NON_NEGATIVE_INT`;只影響耐久顯示 | 原版可損壞物品原廠 max 本來就 1,`count>=1` 自然成立 |
| `minecraft:repair_cost` | `:161` | 純鐵砧成本 | 無 |
| `minecraft:custom_model_data` | `:155` | 純模型 | 無 |
| `minecraft:dyed_color` | `:224` | 純顏色 | 無 |
| `minecraft:trim` | `:256` | 純外觀 | 無 |
| `minecraft:attribute_modifiers` | `:152` | 屬性,不含堆疊上限 | 無 |
| `minecraft:rarity` / `minecraft:unbreakable` | `:141` / `:119` | 不參與 max | 無 |
| `minecraft:container` / `minecraft:bundle_contents` | `:318` / `:238` | 內層物品壞掉不影響外層 stack;`validateContainedItemSizes` 只在 `validateStrict`,不在載入路徑 | 實測「內層是不存在的物品」外層仍 `count=64 max=64` |
| **`minecraft:max_stack_size`** | `:110-112` | **❌ 唯一不可略過的** | 見上面三個特例 |
| 任何未列出的 key | — | `PatchKey.CODEC` 解不出就整顆丟掉(`DataComponentPatch.java:348-356`) | 未來版本若新增會動 max 的 component,白名單版本會自動棄權、deny-only 版本會漏掉 ⟹ 這是白名單唯一的優勢 |

**白名單版本的隱性陷阱**:白名單比對也必須正規化命名空間,否則 `"custom_name"`(無前綴)會被當成未知 key 而棄權——不會出錯,只是白白少省。

---

## (4) 對廢土商店/倉庫的影響:0% → 100%

現況等於「只要物品有名字,摘要就永遠答不出滿」。艦隊的商店箱、倉庫、抽獎箱幾乎每一件都帶 `custom_name` + `lore` + `custom_data` ⟹ **現行摘要在這些容器上證明「滿」的機率是 0**,漏斗每次問 `isFullContainer` 都落到整箱解碼——這正是 s45 那條 `isFullContainer → CompoundContainer.getItem → getItems guard → ensure` 凍結鏈的來源。

兩支差分 fuzz(源碼與結果都在 `/tmp/claude-1000/-home-logocat-Server-claude-sandbox/a0b371fe-72c5-491e-ab05-2164cd8dad7c/scratchpad/probe/`):

**A. 廢土商店情境**(`ShopFuzz.java` → `shop-result.txt`;27 格全滿堆、每件都帶 custom_name/lore/custom_data,另有 1/40 的格子故意塞一顆壞掉的 `enchantments`):
```
rounds=20000  vanilla 真的全滿=20000
V1 證明全滿=0          ← 現行規則
V2 證明全滿=20000      ← 放寬後
V2 與 vanilla 衝突=0
```

**B. 惡意隨機 fuzz**(`WidenFuzz.java` → `fuzz-result.txt`;20 萬個容器,亂數混入壞 id、air、缺 Slot、`!minecraft:max_stack_size`、無命名空間 `max_stack_size`、無法 parse 的 key、各式壞值):
```
V1 佔滿但答不出來 = 23746
V2 佔滿但答不出來 =  1256      ← −94.7%
V2 與 vanilla 滿判定衝突 = 0
V2 假「證明為空」的格 = 0
```

定性結論:**「佔滿但證明不了」這一類會塌掉九成以上,而廢土商店那種情境是從全滅變全中。**

---

## 落地建議(不改資料、不動熱路徑)

1. **先量再改**:上線前先讓 `fullQ=證明滿/證明不滿/佔滿但不知道/整份放棄` 這組計數在正式服跑一輪(`LazyContainerRuntime.java:163-190` 已經寫好,只差 attribution 開著)。目前 109 台的 log 裡沒有 `fullQ=` 這行,所以「放寬能救多少 % 的解碼」在生產上還是估的,只有離線 fuzz 數字。
2. **改動面積極小**:只有 `LazyContainerTemplate.java:686-712` 這 27 行。Slot / id / count / 後寫者勝 / 三態打包全部不動,不碰執行緒、不碰存檔、不碰逐位元組不變。
3. **用現成的 shadow 覆核滾**:`lazycontainer$verifyFull`(template 522-538 行)已經會拿真 items 重算 vanilla 判定並比對主張,不符就 `onSummaryMismatch` 並改答「不知道」。先開 shadow 上一台 → `summaryMismatch=0` → 再關 shadow 放大。這條路徑不需要任何新機制。
4. **差分測試補三組案例**:`!minecraft:max_stack_size`、無命名空間 `max_stack_size`、兩種寫法同時出現。這三個是放寬後**唯一**的新增出錯面。
5. **版本升級檢查表加一行**:升 MC 版本時 grep `getOrDefault(DataComponents.MAX_STACK_SIZE` 的取值點是否仍只有 `ItemInstance` 一處。

---

## 對「逐格延遲解碼」的意涵

放寬摘要打的是 `isFullContainer` 這條路——答得出「滿」就**零解碼**,連逐格都不用。逐格解碼只在「答不出滿、漏斗真的要推入」時才有價值,而那本來就是必要工作。所以順序應該是:**先放寬摘要,量完 `fullQ` 剩下的殘留量,再決定逐格解碼值不值得。**