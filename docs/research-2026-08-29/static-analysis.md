# static-analysis

**結論**:從可證明性看逐格解碼是可以做的——窮舉驗證意外地便宜(27 格 1,197 狀態、全操作序列 26 秒)且能確定性重現我們修過的每一個真 bug——但它不是免費的:必須先接受三條硬約束(存檔一律 collapse 不准 merge、每格先寫值後標位元、decodedMask 只在 monitor 內改),並且先把 bytecode 政策檢查器(半天)與窮舉探索器(1 天)接進 build;做不到這三條就不要動,因為逐格會同時用掉現行最強的兩個證據(byte-identical shadow 與兩態存檔不變式),而且開工前應先花 10 分鐘看 onFullQuery 的棄答分佈——很可能放寬摘要就夠了。

## 硬事實
- 窮舉模型對 27 格逐格設計、2 執行緒只有 1,197 個狀態、3 毫秒跑完;狀態數 = 44N+9,對格數呈線性(實測 /tmp/.../scratchpad/LazyModelCheck3.java 第 C 節)。所以「27 格縮成 3-4 格」這個前提不必要。
- 「所有操作序列」也跑得完:6 種操作的菜單、每條執行緒 3 個操作 ⟹ 216 支程式、46,656 對配對、3,536 萬個狀態、26 秒,零反例(LazyModelCheck3.java 第 E 節)。
- 狀態空間之所以小,是現行鎖紀律買來的:同樣逐格解碼但把 mask 放到鎖外,狀態數每多一格 ×2.7(N=7 已 228,951),推到 27 格約 10^14,跑不完(第 D 節對照組)。
- 窮舉模型確定性重現兩個歷史真 bug:26.2-1 的「先翻旗標再填清單」(1 ms 抓到半填清單 [0,0,0])與「leaf reload 無 clear guard 導致物品復活 [1,2,3]」——皆與 RELEASE-NOTE-26.2-2.md 記錄的 A1/A2 相符。
- 現有 EnsureRaceTest 的偵測力在同一台機器上從 0% 跳到 99.7%:logs/*.log.gz 實際出現 rounds=10 raced=0(8 次)、rounds=2000 raced=13(17 次)、rounds=2000 raced≈1980(16 次);其中 rounds=2000 raced=13 bad=1 代表 2000 輪只抓到 1 次。
- bytecode 政策檢查器對真實產物(nms-lib/paper-26.2-mojmap.jar + target/classes 的 transformer)實跑:18 項通過、0 違規、0.138 秒;涵蓋 splice 數量、鎖紀律、13 個 leaf 入口 guard、redirect 殘留、2 個 hopper hook。
- 突變測試證明檢查器有牙齒:拿掉 LazyContainerTransformer.java:325-328 的 loadAdditional/loadFromTag GUARD_CLEAR 後,立刻報 4 個違規(Chest/Barrel/Shulker 的 loadAdditional + Shulker 的 loadFromTag)。
- 天真的鎖規則會誤報:trySaveRaw 自己不是 synchronized,需要「private 且所有呼叫端都持鎖」的呼叫閉包規則才判為合規——與 LazyContainerTemplate.java:336-337 的註解「呼叫端必須持有 this monitor」一致。
- build.sh:34-42 的第 4 步只檢查 manifest 與 jar 內容,完全沒有驗證改寫結果;FINDINGS §3.1 的離線 link/verify 是一次性手工做的,沒有留在 build 流程裡。
- vanilla ContainerHelper.loadAllItems(ContainerHelper.java:40-46)只對「有 entry」的格做 itemStacks.set,缺席的 entry 不會清掉原值——這正是「物品復活」的機制;我第一版模型寫成無條件寫入,導致 A2 的真 bug 從紅變綠。
- 我自己的模型枚舉器有一個 jump 偏移 bug(迴圈內用會漂移的 here()),產生 3,202 個假反例(46,656 對中的 6.9%),修掉後全綠——模型的 bug 與真發現長得一模一樣。
- 逐格設計的四種寫法錯誤全部被模型抓到:merge 用「空格=未解碼」判準(物品復活)、reload 忘了歸零 decodedMask(新物品遺失)、先標 mask 再寫值(逐格版的 26.2-1 bug)、mask 在鎖外 |= 造成 lost update。
- decoded 改成 bitmask 會新增一整類現行設計不可能有的競態:現行 pending 是 boolean、只有寫沒有 read-modify-write;bitmask 的 |= 是 RMW,鎖外做就會 lost update(模型 B9 實證)。
- 逐格解碼若採 merge 存檔,shadow 的判準會從「逐位元組相等」退化成「多重集合相等」,等於弄丟目前最強的活證據(shadow 比對邏輯在 LazyContainerTemplate.java:368-384);採 collapse-on-save 則 shadow 完全不用改。
- 逐格解碼不會跳過 NBT 解析:raw 自方案 A′ 起是 byte[](LazyContainerTemplate.java:94-104),要拿任何一格都得先 NbtIo.readAnyTag 整份(:190-196、:261);省下的是 codec/component 那層,整份解析照付。
- 摘要欄位 sumState/sumBits/sumFullTri 是普通(非 volatile)欄位、在鎖外被讀(LazyContainerTemplate.java:571-592),安全性靠 load 的寫入序 raw→摘要→最後 pending=true(:140-169);容器被載入兩次的情境經人工檢查安全(volatile 存取全序),但沒有任何工具會驗這條。
- hopper 的 isFullContainer 一遇到 count<maxStackSize 就 return false(HopperBlockEntity.java:477-488),所以逐格解碼在這條路徑上通常只需要解第一格——這正是 s45 那條 16.19 秒凍結鏈的觸點。
- LazyContainerRuntime.onFullQuery 已在記錄「摘要為什麼不肯答」的分佈(LazyContainerTemplate.java:577-579),開工前 10 分鐘就能判斷「放寬摘要的乾淨證明鏈」是不是比整套逐格解碼便宜一個數量級。

## 風險
- 模型落差是路線 (a) 的頭號風險,而且已經實證:我今天寫的模型出了兩次錯,一次(FILL 語意違反 ContainerHelper.java:41-45)讓已知真 bug 從紅變綠、一次(枚舉器 jump 偏移)生出 3,202 個假反例。窮舉綠燈只證明「模型」對,不證明那份 jar 對。
- 窮舉與 Lincheck 都假設順序一致,對 JMM 弱記憶體重排零偵測力;現行 A1 證明(LazyContainerTemplate.java:209-227)至今只有人工推導,jcstress 也只能加壓不能證明。
- 我只驗到 2 條執行緒;EndRod 實際同時活動的執行緒更多。鎖紀律在的話狀態數應該還撐得住,但沒量過就不能宣稱。
- 逐格解碼若採 merge 存檔,會弄丟 shadow 的逐位元組判準——那是目前唯一在真實玩家資料上持續運作的證據來源。必須以 collapse-on-save 為硬規則。
- decodedMask 一旦要在鎖外被讀(為了效能),就需要 volatile + 只讀不算的紀律;任何在鎖外做 |= 的寫法都會 lost update,而這類 bug 在生產上表現為「某一格的東西偶爾被 raw 蓋回去」,幾乎不可能靠玩家回報定位。
- bytecode 檢查器的規則越天真、假警報越多,團隊就越快學會忽略紅燈(trySaveRaw 就是第一個假警報)。規則要跟著設計演進維護,否則它會從防護網退化成噪音。
- 逐格解碼為了避免重複解析整份 NBT,可能被誘導去快取解析後的 Tag 樹——那正是 s18 材料站 OOM 的成因(26 MB Items 樹佔 80–260 MB heap,LazyContainerTemplate.java:94-99)。記憶體滯留是安全性以外的性質,任何「物品守恆」的證明都碰不到。
- ADVERSARIAL-REVIEW.md §四列出的七個未覆蓋失效模式(crash/kill -9 存檔原子性、/data merge、structure block、/clone、raw 滯留記憶體、關 shadow 後的長期等冪、Items 以外的 BE 欄位)至今仍未關;靜態分析對這七條完全使不上力。
- 兩支原型目前放在 scratchpad,會被清掉;沒搬進 repo 並接上 build.sh 之前,本報告的所有「機器每次都會重驗」都還只是「今天驗過一次」。

## 報告
> 鏡頭:能不能「嚴格靜態分析」證明沒有複製/遺失
> 本文所有結論都附源碼行號或我今天實測的數字。兩支原型程式我已經寫好並跑過真實產物,路徑在文末。

## 0. 一句話先講

**做得到的是「在寫明的假設下,每次 build 都由機器重新驗一遍」;做不到的是「數學上證明線上永遠不會掉東西」。**
而且我今天實測發現:**最貴的不是驗證器,是驗證器本身的 bug**——我自己寫的窮舉模型,一個下午就出了兩次錯,一次讓真 bug 漏掉、一次生出 3,202 個假警報。

---

## 1. 我實際做了什麼(不是紙上談兵)

| 原型 | 做什麼 | 跑在什麼上面 | 結果 |
|---|---|---|---|
| `LazyModelCheck3.java` | 把 pending/raw/decodedMask/items/清單世代 + 兩條執行緒建成微步驟模型,窮舉所有交錯 | 純 Java,零相依,`java LazyModelCheck3.java` 直接跑 | 16 個情境全部符合預期;重新抓到兩個歷史真 bug,抓到逐格設計的 4 個新 bug |
| `LockPolicyCheck.java` | 用 ASM 對**改寫後的真實 NMS bytecode** 機械驗證鎖紀律與 guard 到位 | `nms-lib/paper-26.2-mojmap.jar` + 正式 `target/classes` 的 transformer | 18 項通過、0 違規、**0.138 秒**;突變測試(拿掉一個 guard)立刻 4 紅 |

---

## 2. 五條路線的實測評分

| 路線 | 可行性 | 成本 | 能證明到什麼程度 | 建議 |
|---|---|---|---|---|
| **(a) 窮舉小模型** | ✅ 已實作,跑得完 | 1–2 天(已有雛形) | 狀態機層:2 執行緒、順序一致模型下不掉物/複製 | **做,而且逐格開工前就要做** |
| **(b) 差分模糊 vs vanilla** | ✅ 已在用 | 幾小時擴充 | 語意層:摘要/解碼與原版 codec 對同一份資料一致 | **做,逐格版更好寫** |
| **(c) JMM happens-before** | ⚠️ 只能人工 | — | 弱記憶體重排;機器無法驗 | 維持人工;可加 jcstress 當「壓力證據」 |
| **(d) bytecode 機械檢查** | ✅ 已實作 | **半天**(原型已可用) | 織入後的碼符合鎖紀律、guard 都在位、改寫真的發生了 | **今天就接進 `build.sh`,CP 值最高** |
| **(e) shadow 資料層不變式** | ✅ 生產中 | 已付 | 真實資料上「寫回的位元組 == 原版會寫的位元組」 | **維持,而且逐格設計必須為它讓路** |

---

## 3. (a) 窮舉模型:狀態空間比想像中小很多——但原因很關鍵

### 3.1 實測數字

**逐格設計、兩執行緒、T1 逐格解完整箱 + T2 存檔:**

| 格數 | 狀態數 | 耗時 |
|---|---|---|
| 3 | 141 | 0 ms |
| 9 | 405 | 1 ms |
| **27(真實箱子)** | **1,197** | **3 ms** |

狀態數 = `44N + 9`,**對格數是線性的**。所以「27 格縮成 3-4 格」這個前提其實不必要——27 格直接跑得完。

**「所有操作序列」也跑得完**(菜單 6 種操作:解 slot0/解 slot1/覆寫 slot0/覆寫 slot1/存檔/重新載入):

| 每條執行緒的操作數 | 程式支數 | 配對數 | 狀態合計 | 耗時 | 結果 |
|---|---|---|---|---|---|
| 1 | 6 | 36 | 1,869 | 2 ms | 無反例 |
| 2 | 36 | 1,296 | 301,857 | 0.25 秒 | 無反例 |
| **3** | **216** | **46,656** | **35,360,117** | **26 秒** | **無反例** |

也就是說:**「任兩條執行緒各做任意 3 個操作、所有交錯」是 26 秒的事**,完全可以掛進 CI。

### 3.2 為什麼這麼小(這句話是整份報告的重點)

因為現行設計**把所有會碰狀態的路徑都收進同一個 monitor**(`LazyContainerTemplate.java:140` load、`:230` ensure、`:303` clear、`:318`/`:326` save)。鎖把交錯壓成幾乎循序,狀態空間就塌了。

對照組——同樣逐格解碼,但把鎖拿掉(mask 用鎖外 `|=`):

| 格數 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---|---|---|---|---|---|
| 狀態數 | 786 | 3,077 | 10,073 | 30,127 | 84,778 | 228,951 |

每多一格 ×2.7,推到 27 格約 **10¹⁴ 個狀態——跑不完**。

> **結論**:「可窮舉」不是這個問題天生的性質,是**現行鎖紀律買來的**。哪天為了效能把逐格解碼放到鎖外,不只是正確性變難,是**連驗證能力都一起失去**。

### 3.3 它真的抓得到 bug 嗎(有牙齒的證據)

| 情境 | 結果 |
|---|---|
| A1 現行 ensure(填完才翻旗標)× guard 讀者 | ✅ 51 狀態,無反例 |
| A1 **舊版**(先翻旗標)× guard 讀者 | ❌ **1 ms 抓到**「pending=false 卻讀到半填清單 [0,0,0]」 |
| A1 舊版 × 不持鎖存檔 | ❌ 抓到「存檔寫出 [0,0,0]」 |
| A2 ensure × leaf reload(**無** clear guard) | ❌ 抓到「物品復活 [1,2,3](應為 [1,0,0])」 |
| A2 ensure × leaf reload(**有** guard,現行) | ✅ 無反例 |

**26.2-1 那兩個真 bug(RELEASE-NOTE-26.2-2 記錄的 A1、A2),模型都在 1 毫秒內確定性重現**——不必贏得競賽。

對照現有 `EnsureRaceTest` 的實際偵測力(我從 `logs/*.log.gz` 撈出的歷史數字):

```
17 次   rounds=2000 raced=13     ← 2000 輪只有 13 輪真的撞進視窗
 8 次   rounds=10   raced=0      ← 完全沒撞到,那一輪等於沒測
 4 次   rounds=2000 raced=13 bad=1  ← 撞到 13 次、只抓到 1 次
16 次   rounds=2000 raced≈1980   ← 同一台機器,又變成 99%
```

同一台機器,偵測力在 **0% 到 99.7% 之間亂跳**。這正是測試檔自己在 `EnsureRaceTest.java:322-330` 承認的事:自旋搶跑的偵測力隨可用核心數崩塌。**窮舉模型是確定性的,這是它唯一但決定性的優勢。**

### 3.4 誠實面:我自己在這條路線上犯了兩次錯

| 錯誤 | 後果 | 怎麼發現的 |
|---|---|---|
| `FILL` 語意寫成「無條件寫入該格」 | vanilla `loadAllItems` 其實**只 set 有 entry 的格**(`ContainerHelper.java:41-45`),缺席的 entry 不會清掉原值——這正是「物品復活」的機制。模型錯了,**A2 無 guard 的真 bug 就從紅變綠** | 因為結果「與預期不符」才回頭查 |
| 枚舉程式時 jump 偏移用了會漂移的 `here()` | 產生 **3,202 個假反例**(46,656 對中的 6.9%) | 針對性重放才發現 |

> **這是路線 (a) 的真實成本**:模型是程式,程式會有 bug,而且**模型的 bug 長得跟真發現一模一樣**。它證明的永遠是「模型」,不是「那份 jar」。

### 3.5 TLA+ / Alloy 要不要用

**不建議。** 理由:(1) 純 Java 版本已經跑得完,而且能跟現有 JUnit / `test.sh` 同一套工具鏈;(2) 多一種語言就多一份要跟程式碼同步的模型,同步落差正是 3.4 那類錯誤的溫床。

**真正值得升級的是 Lincheck(JetBrains)**:它能對**真實的 Java 物件**做有界模型檢查,不用手寫模型——而現成的 `EnsureRaceTest.TestChest`(`EnsureRaceTest.java:88-173`)已經證明 template 子類可以在 headless 下實例化,等於前置條件已備妥。這一步能直接消掉 3.4 的模型落差。**限制**:Lincheck 探索的是執行緒交錯(順序一致),不探索弱記憶體重排——不過 26.2-1 那個真 bug 本來就是交錯層的 bug,Lincheck 抓得到。

---

## 4. (b) 差分模糊:語意層唯一的辦法,而且逐格版更好寫

現況已經是這型且規模不小:`SummaryDifferentialTest` 對 20,000 + 8,000 份亂數 `Items` 做「摘要 vs 真 codec」比對,加 3,000 次 bytes 往返(`SummaryDifferentialTest.java:436-511`)。

**擴到逐格,新的待驗函式其實只有一個**:「哪些 entry 落在第 i 格」。因為逐格解碼可以**直接呼叫原版的 codec 去解那一個 entry**,語意等價是結構上成立的;會出錯的只剩「選 entry」的規則:

- `Slot` 欄位 → `byte & 0xFF`、缺欄位預設 0(現行摘要已完整重演,`LazyContainerTemplate.java:634-650`)
- 越界丟棄(`isValidInContainer`,`ContainerHelper.java:42`)
- **後寫者勝**:同一格多個 entry,以最後一個成功解碼的為準(`ContainerHelper.java:43` 的 `itemStacks.set`)

這三條是純函式,可以用現有 harness 直接對拍,**幾小時的事**。

**上限**:輸入空間無窮(任意 NBT),而且 headless 測試的堆疊上限是合成的(`SummaryDifferentialTest.java:47-51` 自己註明),真實堆疊上限只能靠 shadow 在真資料上覆核。**差分模糊給的是信心,不是證明。**

---

## 5. (c) JMM:只能人工,而且這次也確實只有人工救得了

現行 A1 的證明寫在 `LazyContainerTemplate.java:209-227`,是標準的 volatile 寫/讀成對推理。**沒有工具能驗它**,能做的只有:

- **jcstress**(OpenJDK 官方壓力工具):在真實硬體上跑,把「禁止出現的結果」宣告出來。它是**唯一**碰得到弱記憶體層的東西,但仍然是測試——測不到的不代表不存在。
- Lincheck、我的窮舉模型:都**假設順序一致**,對重排零偵測力。

**一個實例**:摘要欄位(`sumState/sumBits/sumFullTri`)是**普通欄位、鎖外被讀**(`LazyContainerTemplate.java:571-592`)。安全性靠「load 的寫入序是 raw → 摘要 → 最後 `pending=true`(volatile 寫)」(`:140-169`)。我特別檢查了「同一個容器被載入兩次」(`/data merge`、`getState().update()`)會不會讓讀者配到「舊 sumState + 新 sumBits」:**不會**,因為 volatile 存取之間有全序,讀者讀到 `pending==true` 必定與**最近一次**發佈同步。

> 這條推理**沒有任何工具會告訴你**,窮舉模型也不會——它甚至不會警告你這裡有風險。這就是 (c) 的位置:**不可自動化,但也不可省略。**

---

## 6. (d) bytecode 機械檢查:最便宜、CP 值最高,今天就能上

### 6.1 原型實測結果(對真實產物)

```
transformer.prepare() = true
[LazyContainer] spliced 6 fields + 18 methods into BaseContainerBlockEntity
...
── R1/R2. 鎖紀律(base,splice 後)──
  方法                     sync   monitor/呼叫端          碰到的欄位
  load(..)                 是     -                      w:sumState w:pending w:raw w:sumBits w:sumFullTri …
  ensure(..)               否     有 MONITORENTER         r:pending r:ensuring r:raw w:raw w:pending …
  clear(..)                是     -                      w:pending w:raw
  save/saveNoEmpty(..)     是     -                      -
  trySaveRaw(..)           否     呼叫端持鎖:[save[鎖], saveNoEmpty[鎖]]   r:pending r:raw
  fullState(..)            否     -                      r:pending r:sumState r:sumFullTri
  slotEmpty(..)            否     -                      r:pending r:sumState r:sumBits
=== 結果:通過 18 項,違規 0 項 ===   (0.138 秒)
```

### 6.2 五條規則各自驗什麼

| 規則 | 內容 | 驗到的 |
|---|---|---|
| R0 | base 真的被 splice(6 欄位 + 18 方法) | **r149 那類靜默失效**(`LazyContainerTransformer.java:73-83`:「整場 boot 沒人先碰 base ⟹ 三個 leaf 全被跳過 ⟹ agent 靜默失效」,2026-08-14 生產實測) |
| R1 | 碰 raw/摘要/ensuring 的方法必須在 monitor 內 | 鎖紀律 |
| R2 | 鎖外只能讀白名單,且讀摘要前必須先讀 volatile `pending` | §5 那條發佈順序的**前提**被機械釘住 |
| R3 | 三個 leaf 的入口 guard 都在(13 個位置) | guard 被誤刪 |
| R4 | leaf 內沒有殘留未 redirect 的 `ContainerHelper.load/saveAllItems` | redirect 漏網 |
| R5 | hopper 兩個摘要 hook 在方法入口 | fork 改了方法形狀導致 hook 落空 |

### 6.3 突變測試(證明它不是裝飾)

把 transformer 的 `guardKind` 對 `loadAdditional`/`loadFromTag` 的 `GUARD_CLEAR` 拿掉(`LazyContainerTransformer.java:325-328`),重編、重跑:

```
❌ ChestBlockEntity.loadAdditional 入口 guard 應為 lazycontainer$clear,實得 null
❌ BarrelBlockEntity.loadAdditional …
❌ ShulkerBoxBlockEntity.loadAdditional …
❌ ShulkerBoxBlockEntity.loadFromTag …
=== 結果:通過 14 項,違規 4 項 ===
```

**而這正是 `EnsureRaceTest.java:547-560` 那條「特徵測試」在守的同一件事——差別是 bytecode 檢查在 build 時就紅,不用等 race 撞到。**

### 6.4 誠實面:兩個限制

1. **天真規則會誤報**。第一版把 `trySaveRaw` 判成違規,因為它自己不是 `synchronized`;但它是 private、兩個呼叫端都 `synchronized`(template 註解 `:336-337` 明寫「呼叫端必須持有 this monitor」)。要加**呼叫閉包**規則才對(我已補上)。**規則越天真,假警報越多,人就越快學會忽略它**——這是這類檢查器最常見的死法。
2. **逐格版需要更強的比對**:規則會變成「入口 guard 是 `ensureSlot(i)`,且 `i` 就是本方法的那個 slot 參數」。這在 bytecode 上可以用窺孔比對(guard 呼叫前必須是 `ILOAD <slot 參數>`)機械驗證,**但那是形狀比對,不是通用資料流證明**——寫得再花俏的存取路徑它就看不懂了。

### 6.5 現況缺口

`build.sh` 第 4 步(`build.sh:34-42`)只檢查 manifest 與 jar 內容,**完全沒有驗證改寫結果**。FINDINGS §3.1 提到的離線 link/verify 是一次性手工做的,沒有留在 build 裡。**這個缺口用半天就能補掉。**

---

## 7. (e) shadow:最強的活證據,而逐格設計可能會把它弄丟

現行 shadow 每次要寫 raw 前,把 vanilla 的做法算一遍**逐位元組比對**(`LazyContainerTemplate.java:368-384`)。它的力量來自一件事:**現行設計只有兩種存檔結果**——

- (a) 全程沒被碰 ⟹ 原封寫回 raw bytes
- (b) 已物化 ⟹ 完整走 vanilla 編碼

**兩種都是「完整的」,所以才能拿逐位元組相等當標準。**

**逐格解碼會打破這件事**:一個「解了 3 格、動了 1 格、其餘 24 格還是 raw」的箱子,存檔輸出既不是原 raw、也不是純 vanilla 編碼。如果採用 **merge(已解用 items、未解用 raw)**,shadow 的判準就得從「位元組相等」退化成「物品多重集合相等」——**你會親手弄丟目前最強的那個證據**。

> **所以我的第一條硬建議是:逐格設計必須採「存檔前 collapse(把還沒解的補齊)再照原版編碼」,不准 merge。**
> 代價只有「被碰過的容器少省一次 encode」——那筆錢現行設計本來就在付。好處是 shadow、byte-identical 鐵則、兩態存檔證明**全部原封不動**繼續有效。
> 我的窮舉模型對 collapse 版(B1)與 merge 版(B2)都跑過:**兩者在模型層都乾淨**,差別純粹在「你還驗不驗得動」。

---

## 8. 逐格延遲解碼:從可證明性看,四個新失效模式 + 三條硬約束

我把提案設計建進模型後,**刻意種了四種寫法錯誤,四種全部被抓到**:

| 編號 | 錯誤寫法 | 後果 | 模型結果 |
|---|---|---|---|
| B3 | merge 時用「這格是空的」當「還沒解碼」的判準 | 玩家拿走的東西**復活** | ❌ 抓到:存檔 [1,2,3] vs 邏輯 [0,2,3] |
| B4 | reload 的 clear guard 忘了把 `decodedMask` 歸零 | 新載入的物品**永不物化 = 遺失** | ❌ 抓到 |
| B7 | 先標 `decoded` 位元、再寫值(發佈順序反了) | 逐格版的 26.2-1 bug:讀者看到「已解碼」卻讀到空格 | ❌ 抓到 |
| B9 | `decoded |= bit` 放在鎖外(read-modify-write) | **lost update**:位元被覆蓋掉 → 該格再被 raw 蓋一次 | ❌ 抓到 |

其中 **B9 是現行設計結構上不可能有的**:現在的 `pending` 是 boolean,只有寫、沒有 read-modify-write。換成 bitmask 就多出一整類競態。

**因此三條硬約束(缺一條就不要做):**

1. **存檔一律 collapse,不准 merge**(保住 shadow 與 byte-identical,§7)
2. **每一格:先寫值、後標位元;`pending` 只在全解完才翻**(B7)
3. **`decodedMask` 只能在 `this` monitor 內改**(B9);讀者若要鎖外讀,mask 必須是 volatile,而且**只能讀、不能算**

### 8.1 值不值得做的技術前提(這一段超出我的鏡頭,但會影響驗證成本)

- **逐格解碼並不會跳過 NBT 解析。** raw 現在是 `byte[]`(`LazyContainerTemplate.java:94-104`,s18 材料站 OOM 之後改的),要拿到任何一格都得先 `NbtIo.readAnyTag` 整份(`:190-196`、`:261`)。**省下的是 codec/component 那層(也就是 206 層波動拳的主體),但整份 NBT 解析照付。**
- 想連解析也省,得在載入時**順手記下每個 entry 的位元組區間**(反正 `computeSummary` 本來就走過每一個 entry,`:620-741`),存成 `byte[] + int[] offsets`(不要存成 27 個 `byte[]`,那是 27 個物件頭的記憶體回歸)。這個版本的副作用很好:**每格獨立、沒有共享的 raw 物件 → 狀態機更簡單,也更好驗。**
- **開工前先花 10 分鐘看資料**:`fullState()` 已經在記錄「為什麼不肯答」的分佈(`:577-579` → `LazyContainerRuntime.onFullQuery`)。如果棄答主因是「佔滿但有格證明不了」(多半是 components),那**把摘要的乾淨證明鏈放寬到常見 component**可能比整套逐格解碼便宜一個數量級,而且完全不動狀態機、驗證成本是零。

---

## 9. 「嚴格」到什麼程度是誠實的

### ✅ 機器每次 build 都能重驗(可以叫它「嚴格」)

1. **狀態機不掉物/複製**——兩執行緒、順序一致、明確的操作菜單下,窮舉所有交錯與所有操作序列(46,656 對程式、3,540 萬狀態、26 秒)
2. **織入後的 bytecode 符合鎖紀律、guard 全在位、改寫真的發生了**——0.138 秒,改壞立刻紅
3. **摘要/解碼與原版 codec 對同一份資料結論一致**——目前 2.8 萬個案例

### ❌ 只能靠測試與觀察(誠實說出來)

1. **模型與真實 jar 的落差**——模型是我寫的程式,我今天就在這裡犯了兩次錯(§3.4)。**唯一的縮小辦法是讓檢查器直接跑真實類別(Lincheck),而不是跑我手寫的模型。**
2. **Java 記憶體模型的弱記憶體重排**——(c) 只能人工;jcstress 只能加壓,不能證明
3. **無窮的 NBT 輸入空間**——外掛/外部工具/損毀資料寫出的 `Items` 沒有邊界(R1/R2 兩條 byte-identity 分歧就是這樣來的)
4. **三條以上的執行緒**——我只驗到 2 條;EndRod 上實際更多。鎖紀律在,狀態數應該還撐得住,但**我沒量過就不能說**
5. **環境互動**——crash/kill -9 的存檔原子性、`/clone`、structure block、DFU 換版、其他外掛(`ADVERSARIAL-REVIEW.md` §四那七條至今仍未關)
6. **記憶體滯留**——這是**安全性以外的性質**,任何「物品守恆」的證明都碰不到它。s18 那次 OOM 就是死在這裡,而不是死在掉物

---

## 10. 可以直接引用的白話結論(給服主)

> **問:有沒有辦法嚴格靜態分析複製或遺失問題?**
>
> **有一半,而且那一半值得做。**
>
> 可以做到的是:把箱子的狀態機(還沒解/解了哪幾格/存檔怎麼寫)縮成一個小模型,讓程式**窮舉兩條執行緒的所有可能穿插**,確認任何順序下東西都不會多也不會少。我今天實測過:27 格的箱子只有 1,197 種狀態、3 毫秒跑完;連「兩邊各做任意三個動作」的全部 46,656 種組合,也只要 26 秒。而且它**確定性地重現了我們今年修掉的兩個真 bug**——對照現有的併發測試,那兩個 bug 有時 2,000 輪只抓到 1 次、有時整輪完全沒撞到。
>
> 另一半可以做到的是:在每次編譯後,用機器檢查**改寫進 Minecraft 的那份 bytecode**——鎖有沒有上、守門有沒有在該在的 13 個位置、改寫到底有沒有真的發生。這支檢查器我也寫好了,跑一次 0.138 秒,我故意拿掉一個守門,它馬上就紅。順帶一提,去年那次「agent 靜默失效、整台什麼都沒做」的意外,這支檢查器會在編譯當下就攔下來。
>
> **做不到的是:證明「線上永遠不會掉東西」。** 三個理由,講白:
> 一、模型是我寫的另一份程式,它可能跟真正跑的程式碼對不上——我今天自己就出錯兩次,一次讓真 bug 溜過去、一次冒出三千個假警報。
> 二、Java 在多核心上的記憶體重排,全世界沒有工具能自動驗,只能靠人一行一行推。
> 三、箱子裡可能出現什麼資料是沒有上限的(別的外掛、外部工具、損毀存檔都會寫進去),測不完。
>
> **所以誠實的說法是:「能證明的是設計本身沒有掉東西的縫,而且每次改動都自動重驗一次;證不了的是這台機器今晚不會出事。」後者只能靠 shadow 模式在真實資料上持續對帳——這也是為什麼下一步的逐格解碼,絕對不能為了省一次編碼而讓 shadow 失效。**

---

## 11. 建議落地順序(由便宜到貴)

| # | 事情 | 成本 | 立即價值 |
|---|---|---|---|
| 1 | 把 `LockPolicyCheck` 收進 repo、接進 `build.sh`(補上 `build.sh:34-42` 的缺口) | **半天** | 每次 build 攔下「guard 被刪 / 改寫沒發生」;r149 那類靜默失效不再可能 |
| 2 | 把窮舉探索器搬進 `tests/` 當 JUnit,對**現行設計**建 baseline | 1 天 | 得到不受核心數影響的確定性回歸網 |
| 3 | 先看 `onFullQuery` 的棄答分佈,再決定要不要做逐格 | 10 分鐘 | 可能發現放寬摘要就夠,省下整個專案 |
| 4 | **逐格開工前**先把設計草案丟進探索器(B3/B4/B7/B9 先擋掉) | 1 天 | 唯一能在寫程式碼**之前**抓到 bug 的方法 |
| 5 | 導入 Lincheck 直接對真 `TestChest` 做有界模型檢查 | 2–3 天 | **消掉模型落差**——(a) 最大的殘餘風險 |
| 6 | 把驗證擴到 3 條執行緒 | 半天 | 貼近 EndRod 實況 |

---

## 12. 檔案

- 窮舉模型探索器:`/tmp/claude-1000/-home-logocat-Server-claude-sandbox/a0b371fe-72c5-491e-ab05-2164cd8dad7c/scratchpad/LazyModelCheck3.java`
  (`java LazyModelCheck3.java` 直接跑,零相依;`debug` 參數可重放單一交錯)
- bytecode 政策檢查器:`/tmp/claude-1000/-home-logocat-Server-claude-sandbox/a0b371fe-72c5-491e-ab05-2164cd8dad7c/scratchpad/LockPolicyCheck.java`
  (需 ASM 9.8 + `target/classes` + `template-out`,吃 `nms-lib/paper-26.2-mojmap.jar`)

**兩支都在 scratchpad,建議搬進 `/home/logocat/Server/claude-sandbox/workspace/LazyContainerAgent/tools/`(或 `tests/`)才不會被清掉。**