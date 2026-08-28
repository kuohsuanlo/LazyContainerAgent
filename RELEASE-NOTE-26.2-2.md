# LazyContainerAgent v26.2-2

> SHA-256(`LazyContainerAgent.jar`):`bfd6fc8c342a717218949c57a4b7c9083effff6765d2ed5945326f1b48bbf3f4`
> MD5:`8079c21a399d6fd951212bd24078d8cb`

> 兩個**正確性**修正。沒有新功能、沒有效能取捨、硬碟存檔格式沒變。
> 只有在 **EndRod / Folia 系多執行緒核心**上才會踩到 A1;純 Paper 單主緒不受影響(但修法對 Paper 也零損)。

## 這版改了什麼(一句話)

1. **A1 — 跨執行緒物化視窗**:`ensure()` 舊版是「**先清 pending 旗標、再逐格填清單**」。在允許非擁有 region 執行緒讀活體容器的核心上,另一條執行緒會在旗標已清、清單只填到一半時把箱子當成「已物化」使用 → 掉物 / 複製 / 存檔殘缺。改成「**填完再翻旗標**」,並把載入 / 物化 / 存檔 / 整批替換四條會碰 `pending`/`raw` 的路徑全部收進 `this` monitor。
2. **A2 — 摘要誤判**:`Items` entry 的 `Slot` 欄位存在但**不是數值**(字串 / 清單 / compound / 陣列)時,舊版把它當 slot 0 且仍允許標成「乾淨滿堆」。26 格滿 + 一個壞 `Slot` 的容器會被證成「全滿」,漏斗從此不推入。改成**整份棄答**。

---

## A1:為什麼「先清旗標」在 EndRod 上不是「既有設計邊界」

`FINDINGS.md` §4 與 `ADVERSARIAL-REVIEW.md` 的 thread-safety 群組都寫過:「載入寫 / tick 讀寫 / 卸載存檔三路徑皆單一主緒 → `pending`/`raw` 不需 volatile」,並把「未持鎖讀者可能讀到旗標已清、清單未填完」記成**既有設計邊界**。那個結論在**純 Paper** 上是對的,在 **EndRod** 上是**錯的**——因為前提(只有主緒會碰活體容器)不成立:

- **PIW(Plugin-Intent-Wins,R39)明文允許非擁有 region 的插件執行緒讀活體容器。**
- paper-server 的 `CraftInventory.getItem` / `getContents` 是**先呼叫 NMS 的 `getItem`/`getContents`**、拿到結果之後才做跨區快照。也就是說 **leaf 的 guard 與整段 `ensure()` 解碼都跑在插件執行緒上**,快照只是事後包裝,擋不住視窗。
- `Level.getBlockEntity` 對 off-region 執行緒回傳的是**活體 BE**,不是副本。

所以現實的執行序是:**插件執行緒 A** 進 `ensure()`、清掉旗標、開始逐格填;**擁有 region 的執行緒 B** 在同一微秒讀到 `pending==false`,guard 判定「已物化」直接跳過 `ensure()`,拿走那份**只填了一部分**的 `NonNullList`。B 接下來做什麼,決定災情形狀。

### 五條可證後果(皆為 B 在視窗內拿到半填清單的直接結果)

| # | B 的動作 | 後果 |
|---|---|---|
| 1 | 漏斗**推入**(`tryMoveItems` → `setItem`) | B 把物品塞進「看起來是空的」格;A 隨後把該格解碼結果覆蓋上去 → **推進去的物品憑空消失**。 |
| 2 | 漏斗**抽出**(`removeItem` 後扣量) | B 從半填清單抽走 n 個、把該格改成 count-n;A 隨後用原始 count 覆蓋整格 → **抽出的 n 個被複製**(容器沒少、B 那邊多)。 |
| 3 | **破壞掉落**(`getDrops` / `collectComponents` 經 `getItems()`) | 箱子被打掉時清單只填了一半 → **少掉落**,而 BE 隨即消滅,raw 也一起沒了 → 永久丟失。 |
| 4 | **autosave 編碼**(`saveAdditional` → `saveAllItems`) | 舊版兩個 save 入口在鎖外讀 `pending`,讀到 false 就走 vanilla encode,把半填清單寫盤。**而 autosave 對「只被讀、沒被弄髒」的 chunk 之後不會再重寫** → 磁碟上的殘缺是**永久**的。 |
| 5 | **`getState()` 快照**(插件常態動作) | 快照少物品;插件若把快照寫回(常見的「讀-改-寫」模式)→ 殘缺被扶正成真實內容。 |

第 4 條最惡劣:1、2、3 都需要 B 恰好在做特定事,第 4 條只需要**世界在存檔**——而存檔隨時在跑。本版測試實測到的最壞情況是 **27 格滿箱被寫成 `Items: []`**(見下方「測試證據」)。

### 修法與 JMM 正確性論證(摘要)

`pending` 改 **volatile**;`ensure()` 的寫入序固定為:

> 逐格填清單(普通寫)→ `raw = null` → **`pending = false`(volatile 寫)** → 釋放 monitor

- **未持鎖讀者 R 讀到 `false`**:R 的 volatile 讀與 W 的 volatile 寫**同步**,依 happens-before,W 在寫 `false` 之前的所有填格對 R 皆可見 ⟹ **R 拿到的必是完整清單**。
- **R 讀到 `true`**:R 進 `ensure()` → 在 monitor 上等 W → 取得 monitor 後二次檢查看到 `false` → 返回;monitor 釋放/取得同樣構成 happens-before ⟹ R 之後讀清單也是完整的。R 只會「等待」,**不存在拿到半填的路徑**。
- **失敗路徑**:解碼拋出時旗標從未被翻、`raw` 仍在 ⟹ 任何讀者仍看到 `pending=true` → 下次存取重試;存檔在 monitor 內看到 `pending && raw!=null` → 寫回**完整的原 raw**。永不靜默丟失。
- **重入**:W 在 monitor 內呼叫 `this.getItems()`,而 leaf 的 `getItems()` 入口就是 `if (pending) ensure();`——此時 `pending` **仍是 true**(填完才翻),monitor 可重入,不擋就是無限遞迴。新增 `lazycontainer$ensuring`(`Thread`)記錄「誰正在物化」,入口比對到自己就直接返回,讓 `getItems()` 把清單交出去給 `loadAllItems` 填。
  - `ensuring` 的未持鎖讀是安全的:值為 T 的寫入**只可能出自 T 自己**,T 的讀必看見自己程式序上最後一次寫,因此不存在「T 沒在物化卻讀到 `ensuring==T`」的誤判。
- **與 `setItems`(`lazycontainer$clear`)交錯**:`clear` 也進 monitor ⟹ 只能整個發生在 W 之前或之後。之前 ⟹ W 二次檢查看到 `false` 直接返回;之後 ⟹ W 填舊清單、leaf 隨後換上新清單並清旗標。最終狀態恆為「新清單、`pending=false`、`raw=null`」= vanilla 的 `setItems` 結果(**setItems 贏**)。

### 改了哪些成員

| 成員 | 26.2-1 | 26.2-2 |
|---|---|---|
| `lazycontainer$pending` | plain `boolean` | **`volatile boolean`** |
| `lazycontainer$ensuring` | (不存在) | **新增 `Thread`**,重入偵測用 |
| `lazycontainer$ensure()` | 整個方法 `synchronized`,進來立刻 `pending=false` | 快路徑鎖外讀 volatile;`synchronized` 區塊內**填完才** `pending=false` |
| `lazycontainer$load()` | 普通方法,`pending=true` 早寫 | **`synchronized`**,寫入序 raw → 摘要 → **最後** `pending=true` |
| `lazycontainer$save()` / `saveNoEmpty()` | 普通方法(鎖外讀 `pending`/`raw`) | **`synchronized`**(`trySaveRaw` 的呼叫端持鎖,`pending`+`raw` 才是一致快照) |
| `lazycontainer$clear()` | 普通方法 | **`synchronized`** |
| transformer 的 `GUARD_CLEAR`(`setItems()` 入口) | 就地 `PUTFIELD pending=0; PUTFIELD raw=null` | **`INVOKEVIRTUAL lazycontainer$clear()V`** |

> **最容易漏的一步**:`lazycontainer$clear()` 加了 `synchronized` 但 transformer 的 `GUARD_CLEAR` 還在就地 `PUTFIELD`,
> 那個 `synchronized` 就是死碼——`setItems` 仍然在鎖外改 `pending`/`raw`,等於沒修。本版一併把 `GUARD_CLEAR` 改成
> 呼叫 `lazycontainer$clear()`,並移除只剩它在用的 `RAW_DESC` 常數。離線反組譯確認(三個 leaf 皆同):
>
> ```
> protected void setItems(NonNullList<ItemStack>);
>   0: aload_0
>   1: invokevirtual #399   // Method lazycontainer$clear:()V
>   4: aload_0
>   5: aload_1
>   6: putfield      #1     // Field items:Lnet/minecraft/core/NonNullList;
>   9: return
> ```
>
> 這條路徑在 vanilla 只有 `ChestBlockEntity.swapContents`(舊世界升級的 `UpgradeData` 修正器)會走,**極冷**,
> 開機煙霧測不到——所以用「離線跑真正的 `LazyContainerTransformer.transform()` 再 `javap`」驗證,不是用眼睛看。

### 熱路徑成本

- **已物化的容器**(穩態下的絕大多數):guard 只多**一個 volatile 讀**。x86 上 volatile 讀就是普通 `mov`(零額外指令,只擋編譯器重排);ARM 為 `ldar`。**不進 monitor、不進 `ensure()`**。
- `ensure()` 本身:每容器每次載入**至多執行一次**,不在 tick 熱路徑。
- `load` / `save` / `clear` 新增的 monitor:全是**無競爭**的 thin-lock CAS,且都不是每 tick 路徑(存檔本來就是週期性動作)。
- 摘要查詢(漏斗的十億級滿/空檢查)**完全不受影響**:仍然只讀欄位,不進鎖。

**結論:效能沒有可量測的退化,也沒有拿效能換正確性的取捨。**

---

## A2:摘要對「非數值 `Slot`」的缺口

摘要(`lazycontainer$computeSummary`)在載入當下趁 NBT 樹還在手上建好,讓漏斗的滿/空檢查不必觸發整箱解碼。它的不變式是:**只有在答案可證明與 vanilla 解碼後行為完全一致時才給定論**,任何不確定一律回「不知道」讓呼叫端走原路。

**缺口**:`Slot` 欄位**存在但不是數值**(字串 `"0"`、清單、compound、位元組陣列…)時,舊版把它當「回退預設 0」處理,而且**仍允許整份摘要標成 clean**。後果:

- 26 格滿 + 一個 `Slot:"0"` 的滿堆 entry ⟹ 摘要證成「**全滿**」⟹ 漏斗永不推入 ⟹ 容器永不物化 ⟹ raw 原樣回寫 ⟹ **壞 entry 永久留存**,而且沒有任何人會發現。
- 更根本的問題是**這個語意不可靠**:DFU 對「欄位在但解不出」的 partial 語意,究竟是「保留 entry、`Slot` 回退 0」還是「整個 entry 丟掉」,兩家稽核結論相反(本機 Paper 26.2 build-40 實測為前者)。**兩種語意下該 entry 都不該被標成乾淨滿堆。**

**修法**:`Slot` 欄位存在但非數值 ⟹ **整份棄答**(`LAZYCONTAINER$SUMMARY_GIVEUP`)。代價只是這個(本來就壞的)容器少省一次解碼,而且與版本語意無關地安全。

**注意**:`Slot` **缺欄位**(不是「存在但非數值」)仍然是「回退 0、entry 保留」——這條是 optional 欄位語意,已用**真 codec** 差分實測確認,不能一起棄答(否則 slot 0 會被誤標成「證明為空」,那是更早一版差分測試抓到的真 bug)。

---

## 測試證據

新增 `tests/io/github/kuohsuanlo/lazycontainer/EnsureRaceTest.java`(+ `NmsTestSupport.java`:零 Minecraft server 的 headless NMS 啟動)。做法:直接繼承 `LazyContainerTemplate` 做一個可實例化的測試子類,其 `getItems()` 與 transformer 注入 leaf 的 guard 語意一字不差;每輪新實例、27 格各不相同的物品(id 全異、count = slot+1,方便抓「數量被原量覆蓋」),T1 呼叫 `ensure()`,T2 迴圈做「讀 `pending`;看到 `false` 就檢查 27 格」——這正是擁有執行緒 guard 的動作。

### 先證明測試抓得到 bug(紅)

把 template 複製到暫存目錄,**只**把 `ensure()` 改回舊順序(2 行 diff:`pending=false` 移回進 monitor 之後、解碼之前;結尾那次寫入拿掉),其餘一字不動,用**同一份未修改的** `EnsureRaceTest` 編譯執行:

```
[EnsureRaceTest] rounds=2000 raced=54 bad=1943 samples=[round#0 pending=false 但清單不完整
  [0:empty, 1:empty, 2:empty, ..., 26:empty], ...]

╷
├─ ensure 跨執行緒視窗(A1) ✔
│  ├─ ensure 中途(loadAllItems 前)存檔:輸出必為原 raw 或完整清單,絕非子集 ✔
│  └─ T2 看到 pending=false 時清單必須已完整(≥2000 輪) ✘
│       T2 看到 pending=false 卻讀到半填清單的輪數 = 1943 / 2000 ==> expected: <0> but was: <1943>
[         1 tests failed          ]
```

**2000 輪中 1943 輪紅**,而且樣本顯示 T2 拿到的是**全 27 格皆空**的清單——也就是漏斗會把它當成空箱、破壞會零掉落。

再把存檔那條也改回舊版(兩個 save 入口拿掉 `synchronized`,搭配舊順序 `ensure`),第二條測試也紅:

```
[EnsureRaceTest] save-during-ensure: saveFinishedWhileEnsureStalled=true
  savedEntries=0 equalsRaw=false equalsFull=false

│  ├─ ensure 中途(loadAllItems 前)存檔:輸出必為原 raw 或完整清單,絕非子集 ✘
│       存檔輸出既不等於原 raw 也不等於完整清單(entries=0):[] ==> expected: <true> but was: <false>
[         2 tests failed          ]
```

`savedEntries=0`:**一個 27 格滿箱被寫成空的 `Items: []`**,這就是上表第 4 條後果的實測。

暫存目錄已刪除,repo 內的 template 全程未被更動。

### 修正後(綠)

```
[EnsureRaceTest] save-during-ensure: saveFinishedWhileEnsureStalled=false
  savedEntries=27 equalsRaw=true equalsFull=true
[EnsureRaceTest] rounds=2000 raced=1985 bad=0

[        38 tests successful      ]
[         0 tests failed          ]
```

`raced=1985`:2000 輪中有 1985 輪 T2 確實觀察到過 `pending=true`(真的搶到了視窗,不是空綠);`bad=0`:**沒有任何一輪**在讀到 `false` 之後拿到半填清單。測試本身有 `assertTrue(raced > 0)` 守著,若哪天排程讓兩條執行緒不再交錯,測試會判定「沒真的搶到、無效」而**不是**假綠。

A2 的四個雙向差分案例(非數值 `Slot` / 缺 `Slot` / 越界 `Slot` / `ByteTag` count)併入 `SummaryDifferentialTest`,對照組是**真正的** `ContainerHelper.loadAllItems`,不是人寫的期望值。

`./test.sh` 現在一併編譯執行三支測試,總計 **38 tests successful / 0 failed**。

---

## 升級方式

換 jar、**停服重啟**。不要用熱重載(這是 Java agent,`-javaagent:` 開機掛載,熱重載換不掉)。硬碟存檔格式完全沒變,不需要資料遷移;回滾就是拔掉 `-javaagent:` 那幾行參數重啟。

開機 banner 這版起會印版本號,log 裡就能斷定這台掛的是哪一版:

```
[LazyContainer] LazyContainerAgent 26.2-2 —— crafted by 廢土貓大 LogoCat · 廢土 · mcfallout.net
[LazyContainer] spliced 6 fields + 18 methods into BaseContainerBlockEntity
```

`spliced` 那行的 fields 數這版從 **5 變 6**(新增 `lazycontainer$ensuring`),methods 從 **17 變 18**(新增 `lazycontainer$clear`)。transformer 是**按 `lazycontainer$` 前綴掃 template 的成員**、不是寫死清單,所以新成員自動被 splice;若哪天看到數字沒跟著 template 變,就是 template 沒重新編進 jar。

## 開機煙霧(Paper 26.2-40,sv-papertest)

`-javaagent` + `-Dlazycontainer.shadow=true -Dlazycontainer.verbose=true`,兩次開機:

- **boot 1** — `forceload` 一個 chunk,`setblock` 箱子 / 木桶 / 界伏盒各一,`data merge block` 塞物品
  (含 `components:{"minecraft:damage":10}` 的鑽石劍),`save-all flush`、`stop`。
  收工計數:`stash=3 ensure=0 rawSave=5 eagerLoad=0 shadowMismatch=0`。
- **boot 2** — 重開,三個容器從磁碟 lazy 載入,`data get block` 讀回:**逐字相同**(含 data component)。
  再 `setblock 0 -60 0 air destroy` 打掉箱子 → 掉落物是 `diamond×42` / `netherite_ingot×7` /
  `diamond_sword{damage:10}` + 箱子本體,**一件不少**。收工計數:
  `stash=3 ensure=1 rawSave=8 eagerLoad=0 shadowMismatch=0 attrDrop=1`
  (`ensure=1` + `attrDrop=1` = 那次破壞掉落確實走了物化路徑,而且歸因分到「破壞掉落」桶)。

兩次開機皆:`spliced 6 fields + 18 methods` + 三個 `transformed leaf` + `hooked 2 hopper check(s)`,
**零 `VerifyError` / `NoSuchMethodError` / `NoSuchFieldError` / `ClassFormatError`**,開機完成。

---

## 為什麼舊文件寫「單主緒、不需 volatile」

`FINDINGS.md` §4 與 `ADVERSARIAL-REVIEW.md` 的 thread-safety 結論是在**純 Paper** 的前提下做的,前提本身沒錯、推論也沒錯,錯的是**這個前提不適用於 EndRod**。兩份文件的該段已就地加註標為「26.2-2 已修正」,原文保留不刪——結論會過期,證據鏈不會,留著才知道當初漏看了什麼。

---
Crafted by 廢土貓大 LogoCat · [mcfallout.net](https://mcfallout.net)
