# 26.2-3 ~ 26.2-8 想解的問題,安全的另闢蹊徑(設計提案,**未實作、未鋪**)

> 日期 2026-09-12。作者 Claude Fable 5.1。狀態:**提案**,等服主裁示才動一行 runtime 程式碼。
> 線上 = 26.2-2 / 26.2-9(class 逐檔相同)。26.2-3 ~ 26.2-8 一律不准鋪。

## 0. 一句話

**核心已經把「寫盤」做成非同步了,我們唯一還在 tick 執行緒上付的存檔成本是「快照時把 raw bytes 解成 NBT 樹」;
這一段可以用多核心平行做完、輸出跟 26.2-2 一個 byte 都不差;不碰核心寫盤路徑、不掛側車、不放自訂 Tag。**
物化(ensure)那邊的巨型界伏盒尖峰(單次 600+ ms)則可以「一箱 27 格平行解」。
兩者都是「同樣的函數、同樣的輸入、換一條執行緒算」,失敗一律退回今天的路徑。

## 1. 當初 -3 ~ -8 想解什麼,為什麼那條路封了

| 版本 | 想解的問題 | 手法 | 下場 |
|---|---|---|---|
| 26.2-3/4/5 | 存檔時每個沒被碰過的箱子都要「bytes → 樹 → bytes」繞一圈 | **直寫**:把 raw bytes 掛在 CompoundTag 側車上,寫盤時直接吐 bytes | 09-08 s3 商場整 chunk 清空 |
| 26.2-7/8 | 直寫出事,想用守門補救 | 沒人碰過卻要寫空→補回;raw 多留 10 秒;寫前寫後數量比對;MASS EMPTY 警報 | 09-12 s3+s37+s69 再清空,守門全沒響 |

被封的不是「省解碼」這個目標,是**手法**:
- ⛔ 任何形式的「繞過核心 NBT 樹、直接吐 bytes」(側車、自訂 Tag 型別、write() 時才解)。
- ⛔ 任何「用警報/守門當安全網」的上線方式。
- ⛔ 在直寫上再打補丁。

## 2. 今天(26.2-2)時間花在哪

### 2.1 存檔路徑(tick 執行緒)

呼叫鏈(用 javap 對 Paper 26.2 mojmap jar 核對過):
`NewChunkHolder.saveChunk`(tick/region 執行緒)→ `SerializableChunkData.copyOf` → 每個方塊實體
`getBlockEntityNbtForSaving` → `saveAdditional` → **我們的 `lazycontainer$save`** → `decodeRaw(raw)`
(`NbtIo.readAnyTag`,純 NBT parse,**不跑物品 codec**)→ 樹放進 output。之後樹交給核心的 IO 執行緒序列化+寫盤(已非同步)。

所以 tick 執行緒付的只有 **parse**。離線量測(`bash tools/decode_bench.sh`,素材 = s3 商場 r.1.0 今早鏡像,read-only):

| | 數字 |
|---|---|
| 容器 | 56,265 個有 Items(非空 30,232),raw 共 569 MB,平均 10.6 KB/容器(巢狀界伏盒) |
| A 序列 parse 整個 region | **2,655 ms**(47 µs/容器) |
| B 平行 ×2 / ×4 / ×8 / ×24 | 1,287 / 845 / **608** / 570 ms(2.1× / 3.1× / 4.4× / 4.7×,配置記憶體是上限) |

換算:商場一個 chunk 平均 55 個容器 ≈ 2.6 ms;`max-auto-save-chunks-per-tick: 16` ⟹ autosave 那幾 tick 每 tick 最多 ≈ **41 ms 在 parse**(tick 預算 50 ms)。
極端 chunk((195,-198) 266 個容器/994 萬件)一個就 ≈ 12 ms 以上。

### 2.2 物化路徑(ensure,tick 執行緒,跑物品 codec)

| | 數字 |
|---|---|
| C 序列物化整個 region | 7,884 ms(140 µs/容器) |
| D 平行 ×8 | 2,400 ms(3.3×),**與序列逐格 `ItemStack.matches` 比對 0 差異** |

### 2.3 艦隊今天實況(26.2-2,從 .106 讀 log,04:xx 重啟起算)

量到 103/109 台(6 台 log 太大逾時略過;都是 26.2-2,今天 04:xx 重啟起算約 14 小時):

| 指標 | 全艦隊 |
|---|---|
| 懶載入暫存(stash) | 383,701,990 |
| 物化(ensure) | 3,012,321(解碼累計 579 秒;≥100 ms 的單次 202 次;最慢 s37 692 ms、s3 648 ms、s69 495 ms) |
| 存檔時 parse(rawSave) | 37,374,855 次 ⟹ 以 47 µs/次估 ≈ **1,757 秒 tick 執行緒時間**(平均每台 17 秒/14 小時) |
| 看門貓卡頓事件 | 149 次;其中堆疊含 `decodeRaw`(存檔 parse)**16 次(11%)**,含方塊實體存檔幀 19 次,含 ensure 0 次 |
| 存檔量最大 | s66 4.76M、s64 1.03M、s9 1.03M、s59 0.87M |
| 卡頓最多 | s3 21(decodeRaw 1)、s5 20(0)、s59 15(0)、s66 14(**5**)、s37 13(2)、s26 7(2) |

解讀:存檔 parse 平均攤開很小(17 秒/台/天),但它**集中在少數幾台、少數幾格**(s66 一天 4.76M 次、商場 chunk 一次 12 ms 以上),
所以它出現在看門貓的方式是「autosave 那幾 tick 抖一下」,佔全部卡頓事件的一成左右。物化那邊則是「單箱尖峰」(一天 202 次超過 100 ms)。

**誠實結論**:在今天的 26.2-2 上,存檔 parse 只佔看門貓卡頓的一小部分;剩下的多數不在 LazyContainer(EndRod 全域車道 / PoiManager / GC 等,見 08-25 歸因)。
這個提案能省的是「那一小部分 + autosave 期間的 tick 抖動」,不是全部卡頓。**值不值得動,服主看數字決定。**

## 3. 能動的地方、不能動的地方

| 能動 | 不能動 |
|---|---|
| 快照時 parse 用哪條執行緒算 | 核心寫盤路徑(copyOf 之後的一切) |
| 物化時 27 格用哪條執行緒算 | CompoundTag / ListTag 的型別與內容 |
| 觀測計數器 | 「沒人碰過就原樣寫回」以外的任何存檔語意 |
| Paper 設定檔的 autosave 節奏 | 用警報當上線閘門 |

## 4. 候選方案

| 代號 | 做法 | 風險等級 | 收益 | 判定 |
|---|---|---|---|---|
| **P0** | 純設定:`max-auto-save-chunks-per-tick` 16 → 4~8,把同樣的 parse 攤到更多 tick | 零(設定檔,可逆) | autosave 期間單 tick parse 41 ms → 10~20 ms;總量不變 | 先問服主,可先在 s3 試 |
| **P1 存檔預解析** | 一個 chunk 開始存檔時,把這 chunk 其他 pending 容器的 `decodeRaw` 丟給小型工作池平行算;輪到該容器存檔時取回**同一份 bytes 的**結果;任何不對勁就當場自己 parse | 低(純函數、不可變輸入、輸出等價) | 快照 parse 時間 ÷ 3 左右(2 工作緒 + tick 執行緒) | **推薦** |
| **P2 逐格平行物化** | `ensure()` 內把 27 格的物品 codec 平行算,算完在 monitor 內一次寫入清單 | 中低(物品 codec 本來就在 netty 執行緒跑:`ServerboundSetCreativeModeSlotPacket` 用 `ItemStack.OPTIONAL_UNTRUSTED_STREAM_CODEC`,javap 核對;bench 0 差異) | 單箱 600+ ms 尖峰 → ~200 ms;只影響巨型巢狀箱 | 第二步,P1 站穩後 |
| P3 raw 壓縮 | raw bytes 用 LZ4 存,省記憶體(s3 一個 region 569 MB) | 中(parse 前多一步解壓) | 記憶體 ÷ 3~5;速度變慢 | 不在本題,另議 |
| ✗ | 直寫 / 側車 / 自訂 Tag / write() 時才解 | — | — | **封殺** |
| ✗ | 跨次存檔快取解好的樹 | 記憶體(= 08-14 s18 那種 8 G OOM) | — | 封殺 |
| ✗ | 自己開執行緒寫盤、改核心 copyOf | 動核心 | — | 封殺 |
| ✗ | 預測漏斗要碰誰,載入時就先物化 | 放棄懶載入的記憶體收益,預測不準 | — | 不做 |

## 5. P1 詳細設計

### 5.1 名詞
- **pending 容器**:載入後沒人碰過,內容在 `lazycontainer$raw`(不可變 byte[])。
- **一次 pass**:核心存一個 chunk 時,依序對該 chunk 每個方塊實體呼叫存檔;全程在同一條 tick 執行緒、不夾 tick。

### 5.2 新增欄位(template,只在存檔路徑讀寫)
```
volatile Future<Tag> lazycontainer$pre;   // 工作池正在/已經算好的樹
volatile byte[]      lazycontainer$preRaw; // 那棵樹是從哪份 bytes 算的(身分比對用)
```

### 5.3 流程(改 `lazycontainer$trySaveRaw`,其餘不動)
```
1. (同今天)pending 且 raw!=null 且 output 是 TagValueOutput,否則 ensure 後走原路。
2. f = pre; pre = null; from = preRaw; preRaw = null;
   若 f!=null 且 from == raw(同一個 byte[] 物件):
       tree = 取回(f):已完成→取值;還沒開始→cancel 成功→null;正在跑→等它(最多一個 parse 的時間)
   否則 tree = null(stale 或沒預解析)
3. 若 tree == null:
       若「本次呼叫在 chunk 存檔 pass 內」且啟用 ⟹ 對同 chunk 其他 pending 且 pre==null 的容器,
           各丟一個 decodeRaw(它的 raw) 任務到工作池,寫入它們的 pre/preRaw
       tree = decodeRaw(raw)          // 自己這個容器照今天 inline 算(跟工作緒重疊)
4. (同今天)tree 必須是 ListTag、shulker 空清單規則、shadow 比對、out.put("Items", tree)
```

兄弟容器的列舉:`level.getChunkIfLoaded(pos)` → `LevelChunk.getBlockEntities()`(公開 Map,javap 核對),只挑本 template 型別且 `pending==true` 的;
核心自己的 copyOf 也是走 `getBlockEntitiesPos()` 逐一 `getBlockEntityNbtForSaving`(javap 核對),所以「同一個 pass 會輪到每個兄弟」這個前提成立。

### 5.4 「在 chunk 存檔 pass 內」怎麼判
用 `StackWalker` 往上看 ≤ 8 層有沒有 `SerializableChunkData.copyOf`。只在「要不要預解析」這個決定用,
判錯的後果只是「沒加速」,永不影響正確性。每個 chunk 的 pass 只走一次(第一個 pending 容器),之後的兄弟都有 pre 直接取。
**不 transform LevelChunk / SerializableChunkData**(26.2-5 用 thread-local 視窗包 `getBlockEntityNbtForSaving`,那條線不再用)。

### 5.5 工作池
- 專用、守護執行緒、`MIN_PRIORITY`,大小 `-Dlazycontainer.preparse.threads`(**預設 2**;一台實體機 12 個 JVM,不能開大)。
- 佇列有上限(例如 4,096 任務);滿了就不預解析,inline。
- 任務內容只有 `NbtIo.readAnyTag(bytes)`:**只碰不可變 byte[] 與自己 new 的樹**,不碰 BE、level、清單、registry。

### 5.6 不變式(每一條都有對應測試)
- **I1** 寫進存檔的 `Items` 永遠 = `readAnyTag(這個容器當下的 raw)`。身分比對 `from == raw` 保證「當下」。
- **I2** 核心寫盤路徑零改動;樹裡沒有任何非核心型別;沒有側車。
- **I3** 任何失敗(池滿、例外、cancel、stale、不在 pass 內、旗標關)⟹ 退回 26.2-2 inline parse,行為完全相同。
- **I4** 工作緒不碰 BE 狀態;消費只在 tick 執行緒、持本容器 monitor(= 今天的臨界區,一格不改)。
- **I5** 記憶體:預解析結果在同一個 pass 內被消費並清空;pass 中途失敗留下的結果在下一次 pass 被身分比對丟棄。
  上限 = 一個 chunk 的 pending 樹(≈ raw 的 2~3 倍)。
- **I6** 旗標 `-Dlazycontainer.preparse=false`(**預設 false**)時,新增成本 = 存檔路徑一個 boolean 讀。

### 5.7 失敗模式
| 情況 | 結果 |
|---|---|
| 兄弟容器在預解析與消費之間被 ensure(插件執行緒) | 它的 pending=false ⟹ 走 vanilla encode;預解析結果丟棄 |
| 兄弟的 raw 被換掉(reload/setItems) | `from != raw` ⟹ stale,inline parse |
| 工作緒 parse 拋例外 | 取回得 null ⟹ inline parse(inline 也拋就是今天的行為) |
| 池滿 / 執行緒死 | 不預解析 ⟹ 今天的行為 |
| copyOf 中途拋出,pass 沒走完 | 留下的 pre 在下次 pass 丟棄;不進磁碟 |
| 不是 chunk 存檔(getState / clone / structure) | StackWalker 看不到 copyOf ⟹ 不預解析 |

### 5.8 觀測
`preSubmit / preHit / preInline / preStale / preSkip` 五個計數器 + `saveParseMs`(tick 執行緒 inline parse 累計毫秒,
**26.2-2 目前沒有這個數字**)。就算 P1 被否決,`saveParseMs` 這一個計數器也值得單獨出。

## 6. 驗證計畫(全綠只是「准做 canary」,不是「安全」)
1. **G3 單元**:(a) 等價 —— 隨機 raw 走 P1 與 inline,`Tag.equals` 必相等;(b) 競態 —— 預解析中換 raw / ensure / clear,
   斷言消費端永遠 stale-丟棄;(c) 池滿/例外/cancel 三種 fallback 都走 inline。
2. **G4 A/V**:真倉庫 77,611 容器,P1 開 vs 原版,結構不同 = 0(既有關卡直接套用)。
3. **G6 shadow**:P1 開 + shadow,mismatch = 0。
4. **RED**:故意讓工作池回錯的樹(注入旗標,只在測試 jar),身分/等價檢查必須丟棄 ⟹ 磁碟零差異。
5. **外部閘門(真正的閘門)**:canary 期間每日 `tools/container_loss_watch.py` 鏡像 vs 線上,**零流失** 才算過;
   任何一筆流失即撤,不看內部計數器。

## 7. 上線規則(服主定,寫在這裡是建議)
- 26.2-10 = 26.2-9 + 計數器 + P1(**預設關**)。關著時 = 26.2-2 行為。
- canary 只開一台(建議 s45),旗標開;**7 天**外部 diff 零流失才談第二台。
- 不做「先鋪再觀察」:沒有外部 diff 就不開旗標。

## 8. 預期收益 / 成本
- 收益:autosave/unload 期間 tick 執行緒 parse 時間 ÷ ~3(2 工作緒);看門貓含 `decodeRaw` 幀的事件趨近 0;商場區 autosave 抖動下降。
- 成本:每 JVM 2 條低優先執行緒;預解析期間額外持有一個 chunk 的樹(毫秒級);存檔路徑多一個 boolean + 一次 StackWalker/chunk。
- 不會變好的:非 LazyContainer 的卡頓(佔多數);單一巨型箱的物化尖峰(那是 P2)。

## 9. 不做的事(再說一次)
直寫、側車、自訂 Tag、write() 時才解、跨存檔快取樹、自己寫盤、改核心 copyOf、用警報當安全網、先鋪再觀察。
