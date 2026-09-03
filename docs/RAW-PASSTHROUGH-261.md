# #261 存檔 raw passthrough —— 設計、閘門與證據(2026-09-03)

## 問題

EndRod 核心的卡頓歸因(#261)點名:未物化(從未被碰過)的箱子在 chunk 存檔時,agent 把暫存的原始 bytes
解析成 NBT 樹(`lazycontainer$decodeRaw`),再由核心逐節點重新序列化。這段在 tick 執行緒上跑,
是艦隊 5 秒級卡頓的來源之一(s45 的 127 筆裡:84 筆是原版 `saveAllItems` 被貼錯標籤、41 筆是這條 pending-save 解碼、2 筆 load encode)。

## 作法

- `CompoundTag` 多兩個欄位 `lazycontainer$rawKey` / `lazycontainer$rawBytes`。
- `CompoundTag.write(DataOutput)` 開頭插 prologue:`rawBytes != null` 就先用 `Runtime.writeRawEntry` 以標準 named-tag 框架
  `[typeId][UTF name][payload]` 吐出(與 `NbtIo` 寫一個 entry 一字不差),其餘欄位照常;`copy()` 一併帶走兩欄位。
- `LevelChunk.getBlockEntityNbtForSaving`(`SerializableChunkData.copyOf` 收集方塊實體 NBT 的唯一呼叫者)
  改名保留為 `lazycontainer$orig$…`,同簽名包裝 `enterChunkSave(); try { orig } finally { exitChunkSave(); }`(per-thread 深度計數)。
- template `trySaveRaw`:只在「視窗內 ∧ 非 shadow ∧ raw 是 ListTag ∧ (allowEmpty ∨ 清單非空)」時
  `buildResult().remove("Items")` 後 `attachRaw`;任何條件不成立(含 `/data`、structure、`getState()`、封包等會「讀」樹的呼叫者)
  一律退回舊的解析路徑,行為與 26.2-2 相同。
- 旗標 `-Dlazycontainer.passthrough=false` 關;stats 行新增 `rawPassthrough=`(應等於 `rawSave=` 的絕大部分)。

## 閘門(全部通過)

| 閘門 | 結果 |
|---|---|
| 單元測試(`test.sh`,headless 真 NMS codec) | 53/53,新增 `RawPassthroughFramingTest`(注入寫法 vs `NbtIo` 3000 輪結構相等、空清單判定、per-thread 深度) |
| bytecode 政策閘門(`tools/LockPolicyCheck.java`,build.sh 第 5 步) | 23 項 0 違規,新增 R6(CompoundTag 欄位/prologue/copy;LevelChunk 改名+包裝形狀) |
| 離線 javap 對帳 | `write` 單一 overload、`copy()` 單一 `areturn` 且堆疊頂為新物件、bridge 委派;包裝 try 範圍 [3,10) 正常/例外各呼叫一次 `exitChunkSave`,F_FULL frame 正確 |
| 真實 region 端對端 A/B(Paper 26.2 + ChunkGuardAgent) | 見下 |

### 端對端:s3 商場 `r.1.0.mca`(1024 chunk、28,370 個容器)

同一份 region 各跑一次 A(passthrough 開)與 B(`-Dlazycontainer.passthrough=false`),
四批 `forceload` 載滿 1024 chunk、`save-all flush`,用 stdlib 掃描器比對每個容器 Items 的**結構**(compound key 排序後)。

| | 輸入↔A | 輸入↔B | A↔B |
|---|---|---|---|
| 容器數 | 28,370 / 28,370 | 28,370 / 28,370 | 28,370 / 28,370 |
| 只在一邊 | 0 | 0 | 0 |
| **結構不同** | **0** | **0** | **0** |

計數:A `stash=28370 ensure=0 rawSave=28252 rawPassthrough=28252`;B 相同但 `rawPassthrough=0`。
兩輪皆 1024/1024 chunk 真的被改寫(mca 時間戳)、`transform_failed=0 circularity=0`、零 VerifyError/例外、
ChunkGuard `inspected=1494 fakeFullBlocked=0 readGuardAlerts=0 inspectErrors=0`(寫入屏障與讀取守門皆未被觸發)。

(第一輪沒凍 tick 時,漏斗真的在搬東西:`ensure=236` 全歸 attrHopper,三方各有 4~5 個結構差異且**全在同一條漏斗鏈的座標**、
差異只是同槽位數量 23→57 之類;第二輪 `tick freeze` 後歸零,證明差異來自漏斗不是存檔。)

### 口徑

- 「結構相等」而非「逐位元組相等」:Paper 的 `CompoundTag` 是 fastutil 雜湊表,compound 內 key 順序由它決定。
  直寫吐出的是「載入解析後」的順序,舊路徑再解析一次寫出的順序可能不同(輸入↔A 雜湊相同 13,261、輸入↔B 18,076),
  但 NBT compound 語意上是無序 map,兩者結構全等、都不做正規化。

### 三個會讓 E2E「空洞通過」的坑(已寫進 harness 閘門)

1. `forceload add` 單次上限 256 chunk,整個 region 一次下會被拒(console 紅字),伺服器照樣存檔、輸出=原檔照抄。
2. 26.2 的 overworld 目錄是 `world/dimensions/minecraft/overworld/region/`;放到 `world/region/` 核心不讀、自己生成平地。
3. 閘門必須同時要求 `stash>0`、`rawPassthrough>0` 且 mca 時間戳被改寫的 chunk ≥ 1000。

## 上線建議

- 先鋪 s45(#261 點名 9 筆)重啟,看 stats 行 `rawPassthrough=` 增長、watchdog 堆疊裡 `lazycontainer$decodeRaw` 出現在存檔路徑的次數應歸零。
- 回滾:`-Dlazycontainer.passthrough=false`(免換 jar),或換回 26.2-2。
