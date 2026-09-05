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

## 26.2-4 加固(2026-09-05)

服主的疑慮:「框架寫錯 ⟹ 整個 chunk 讀不回來」聽起來很恐怖,出事能不能救。三道加固:

### (A) 寫入前自檢

掛 bytes 之前做一次零配置 NBT 走訪(`LazyContainerRuntime.rawWellFormedList`):只算長度、不建樹、不跑 codec,
確認 raw 剛好是一個完整的 ListTag、走完的 offset 等於 `raw.length`。規則逐條對齊 vanilla 讀取端或更嚴:

| 規則 | vanilla | 走訪 |
|---|---|---|
| 型別碼 | 1..12,compound 內 0 = END | 同(以無號值判) |
| 非空清單 elemType | 0 丟 `Missing type on ListTag`;>12 讀元素時丟 | 拒 |
| 空清單 elemType | 不讀元素,任何值都成功 | 不看 |
| 清單/陣列長度為負 | `NbtFormatException` / `IllegalArgumentException` | 拒 |
| `byte[]` / `int[]` 長度 | `checkArgument(len < 2^24)` | 同(**這是唯一曾比 vanilla 寬鬆的地方**) |
| `long[]` 長度 | 無上限 | 無上限(對齊) |
| 字串 | `DataInputStream.readUTF` 的 modified-UTF-8 | 逐位元組驗同一組規則 |
| 巢狀深度 | `NbtAccounter` 512 | 500(更嚴) |

判定快取在方塊實體的 `lazycontainer$rawOk`(0 未判 / 1 合法 / 2 終局拒絕),raw 不可變所以每份只走訪一次——
否則自動存檔會在同一份 26 MB 的 raw 上反覆做 O(n) 走訪,等於在 tick 執行緒上種一個新的尖峰。
計數 `rawWalk=` / `rawWalkMaxMs=`。

**變異測試**:18,000 個變異體(翻位元、改位元組、截斷、加尾巴、改 elemType、改長度、塞非法型別),
走訪接受的 4,563 個**全部**被 vanilla 讀回且剛好讀完(致命方向零違反);抽樣的拒絕案例中 4.3% 是
vanilla 能讀而走訪拒(無害方向,只是多退回舊路徑)。

### (A′) 壞 bytes 的終局處理

**這是審查抓到的真洞**:26.2 的 `NbtIo` 對格式錯誤丟的是 `RuntimeException`(`NbtFormatException` /
`NbtAccounterException` / `ReportedNbtException` / `IllegalArgumentException`),不是 `IOException`。
原本 `trySaveRaw` 與 `ensure` 只 `catch (IOException)`,例外會一路穿出
`getBlockEntityNbtForSaving` → `SerializableChunkData.copyOf`,被 Moonrise 的 `saveChunk` 記成
`Failed to save chunk` 後**整個 chunk 這輪不落盤**(同 chunk 其他容器的變更一起沒寫),而且每次
autosave 重演;`ensure` 那條更會在漏斗 tick 上反覆炸。此洞 26.2-2 起就有,與直寫無關,一併修掉。

現在兩處都 `catch (Throwable)`,並且:標記 `rawOk=2`(終局)、原始 bytes 落檔 `lc-badraw-<座標>-N.bin`、
印座標一次、`badRaw++`,容器改走 vanilla 編碼。**絕不把已知讀不回的 bytes 寫進 chunk。**

### (B) 直寫的觀測模式 `-Dlazycontainer.passthrough.shadow=true`

磁碟照舊寫解析出來的樹(=26.2-2,安全),另外做一次**真路徑**探針:
`out.copy()` → `attachRaw` → `NbtIo.write`(真的執行被 ASM 改寫的 `CompoundTag.write`)→ vanilla 讀回 →
與「解析樹 + 其他欄位」比對 key 集合、結構、串流是否剛好讀完。

審查指出的關鍵設計修正:原本打算在 template 內「重演」prologue 再讀回,那是套套邏輯——真模式獨有的錯誤
(注入位置錯、prologue 與 map 內同名 key 重複、`copy()` 沒帶欄位)全部落在視野之外。改成走真路徑後,
這些才看得到。承重的是「不拋例外 + key 集合正確 + 串流剛好讀完」,`equals` 只是附帶的廉價斷言。
成本控制:raw 超過 512 KB 只計數不做完整讀回(`ptShadowSkipped`);讀回用**有界** accounter(64 MB),
因為框架若錯位,壞掉的長度欄位可能要求配置數 GB。

### (C) 還原工具 `tools/mca_restore.py`

`verify --deep` / `list` / `restore-chunk` / `restore-items`,純 stdlib。兩個設計重點:

- **只搬位元組**:`restore-items` 找出目標 tag 在解壓後 chunk 資料中的位元組區間,直接換成備份檔的同一段。
  不重新編碼的理由是 Java 的 modified-UTF-8(`U+0000` 寫成 `C0 80`、增補字元寫成兩個三位元組的代理對)、
  float NaN 位元樣式、compound key 順序在 Python 重寫時全都會變。
- **寫入前確認世界沒在跑**:`session.lock` 用 `fcntl.lockf` 試鎖 + 掃 `/proc/*/fd`。Paper 把區域檔的
  8 KB 檔頭與 sector 點名表快取在記憶體,伺服器在跑時改磁碟檔頭會被整份蓋掉。

實測(真實 s3 `r.1.0.mca`,1024 chunk / 28,370 容器):
`verify --deep` 全乾淨;切掉某個 54 KB 容器的 Items 再還原 ⟹ Items payload 逐位元組相同、其餘整份相同;
把 Items 換成空清單再還原 ⟹ 整份解壓內容**逐位元組等於備份**;砸爛整格壓縮資料 ⟹ `verify` 指出該格、
`restore-chunk` 修好、再驗全乾淨;世界上鎖時寫入被拒、唯讀指令照常。

### 面板整合(26.2-5)

`[LazyContainer] BAD RAW` 這一行現在印成 `minecraft:<dim> chunk (cx, cz) block x, y, z …`,格式對齊面板
`tps-viz/web/chunkguard.py` 的 `DIM_RE` / `CH_RE1`;面板端(commit `1c5acae`)把它加進觸發字串,與核心的
`chunk data will be lost` 同等待遇:凍結 region、建案、`verdict_detail` 前綴標明「容器 bytes 讀不回、已退回原版編碼,
用 mca_restore.py restore-items 從備份貼回」。營運成本為零——偵測器本來就每 60 秒 grep 同一批 log,多一個 pattern 而已;
真的命中才會凍結建案,而 `badRaw` 正常恆為 0。

面板看得到 / 看不到什麼:整格讀不回(核心印 `chunk data will be lost`)本來就會建案;單一容器 Items 錯而 chunk 正常
(log 無聲、尺寸不變)面板看不到,26.2-5 起靠 BAD RAW 行補上;26.2-4 修掉的「壞 bytes 讓整個 chunk 不落盤」
核心印的是 `Failed to save chunk`,不在面板關鍵字裡,以前也看不到。

## 上線建議

- 先鋪 s45(#261 點名 9 筆)重啟,看 stats 行 `rawPassthrough=` 增長、watchdog 堆疊裡 `lazycontainer$decodeRaw` 出現在存檔路徑的次數應歸零。
- 回滾:`-Dlazycontainer.passthrough=false`(免換 jar),或換回 26.2-2。
