# 測試案例清單

這份是「目前存在哪些測試案例、各自守什麼」的完整清點,對照表在 [`README.md`](README.md) 的功能總表。
**新增功能時,這份與功能總表要一起更新**——沒有出現在這裡的測試案例等於沒有人知道它存在。

清點方式:`tests/**/*.java` 的每個 `@Test`、`tools/*.java` 的每條規則、`gates/g*.sh` 的每條斷言、
`gates/py/*.py` 的每條判定。數字用 `bash test.sh` 的輸出核對。

---

## 一、離線單元/差分測試(G3,`bash test.sh`,64 個 @Test / 6 個類別)

零 Minecraft server,對**目標版本的真 codec** 跑(headless NMS,見 `tests/NmsTestSupport.java`)。

### `SummaryDifferentialTest` — 25 個(漏斗摘要 vs 真解碼)

同一份未解碼的 `Items`,一邊餵摘要、一邊餵真正的 `ContainerHelper.loadAllItems`,比對兩邊結論。
**摘要棄答不算失敗;只要開口回答就必須與 vanilla 一致。**

| 測試 | 守什麼 |
|---|---|
| `emptyList` / `allFull` / `oneShort` | 基本三態 |
| `negativeFractionalSlot` | 負小數 Slot 必須用 `box()` 取值(`byteValue()` 是 `Mth.floor`,會差一格) |
| `slotTypesAndWrap` / `byteTagCount` | Slot/count 各種 tag 型別 |
| `countEdges` / `idEdges` | count 邊界(1..99)、未知 id、air |
| `duplicateSlots` / `duplicateSlotLastWriterWins` | 同一格多筆 entry,後寫者勝 |
| `componentsHandling` / `componentsMustNotBlockFullProof` / `brokenComponentKeepsItemAndFullProof` | 放寬規則:component 不擋「滿」的證明 |
| `maxStackSizeEdgesAgreeBothWays` / `maxStackSizeSpellingsAgreeBothWays` | `max_stack_size` 的值域與各種拼法(裸的、`:` 開頭、`!` 移除記號) |
| `nonNumericSlotMustNotProveFull` | **A2**:Slot 存在但非數值 ⟹ 整份棄答 |
| `absentSlotDefaultsToZero` | 缺 Slot ⟹ 回退 0(早期版本誤當丟棄,是抓到的真 bug) |
| `slotBeyondSize` | Slot 越界 ⟹ vanilla 丟棄 |
| `structuralGarbage` / `containerSizes` | 結構垃圾、各種容器大小 |
| `rawBytesRoundTrip` / `rawBytesRoundTripNested` | raw 存成 bytes 再解回來:**結構相等 + 決定性**(不能斷言逐位元組,Paper 的 CompoundTag 是雜湊表) |
| `randomizedFuzz` / `randomizedFuzzFullContainers` / `randomizedFuzzNearFull` | 隨機語料;**滿箱語料是刻意加的**——純隨機從不產生「開口答滿」,有害方向零覆蓋 |

### `AttributionClassifyTest` — 19 個(觀測)

歸因八桶 13 個(`hopperPull` / `minecartHopper` / `comparatorRead` / `quickshopCount` / `quickshopBeatsHopper` /
`saveFallback` / `playerOpenVanilla` / `blockBreakComponents262` / `blockBreakDrop` / `unknownPlugin` /
`pluginHopperClassIsNotVanillaHopper` / `getStateSnapshotFromPluginIsPlugin` / `vanillaOther`)、
解碼耗時與分佈 5 個(`decodeTimingAccumulates` / `zeroNanosDoesNotCount` / `decodeHistogramBuckets` /
`zeroNanosNotInHistogram` / `slowThresholdDefault100ms`)、`fullQ` 四桶 1 個(`fullQueryOutcomeBuckets`)。

### `RawPassthroughFramingTest` — 10 個(存檔直寫)

| 測試 | 守什麼 |
|---|---|
| `injectedWriteReadsBackIdenticalToVanilla` | 注入的寫法與 vanilla 的 `writeNamedTag` 逐位元組相同 |
| `rawEmptyListDetectionWithoutParsing` | 不解析就判空(界伏盒 `allowEmpty=false` 要用) |
| `walkerAcceptsEverythingVanillaEncodes` | 走訪器不能比 vanilla 嚴到擋掉正常資料 |
| `walkerNeverAcceptsWhatVanillaRejects` | **致命方向**:走訪器接受的,vanilla 一定讀得回 |
| `walkerRejectsOversizedArraysLikeVanilla` | `byte[]`/`int[]` 的 2^24 上限(唯一曾經比 vanilla 寬鬆的地方) |
| `walkerRejectsTruncationTrailingBytesAndExcessiveDepth` | 截斷、多餘位元組、深度 |
| `walkerRejectsInvalidModifiedUtf8ThatVanillaRejects` | Java modified-UTF-8 |
| `passthroughShadowIsUnavailableWithoutTransformedCompoundTag` | 沒被改寫時觀測模式要能自己發現 |
| `malformedRawThrowsRuntimeNotIoException` | **26.2 的 NbtIo 丟 RuntimeException 不是 IOException**——只 catch IOException 會讓整個 chunk 不落盤 |
| `chunkSaveDepthIsPerThreadAndBalanced` | 存檔視窗旗標是 per-thread 且進出平衡 |

> ⚠ 這支檔案含真的 NUL 位元組(測非法 UTF-8),`grep` 會當二進位檔靜默略過——要用 `grep -a`。

### `EnsureRaceTest` — 5 個(跨執行緒)

`pendingFalseImpliesComplete`(兩執行緒搶 ensure)、`saveDuringEnsureIsNeverPartial`、
`pendingStaysTrueUntilListIsComplete`(**確定性閘門**,不靠搶跑機率)、
`leafReloadDuringEnsureMustNotResurrectItems` 與 `withoutLoadGuardItemsDoResurrect`(A1:沒有 guard 時 26/26 格復活)。

### `PassthroughDeficitTest` — 4 個(直寫對帳告警)

`sideCarDroppedRaisesAlarm`(側車全丟必須叫)、`normalIoLagIsSilent`、
`bulkSaveBurstIsSilent`(**爆量存檔連續落後不得告警**——第一版判準在這裡誤報,G4 實測抓到)、
`transientSpikeIsSilent`。

### `ComponentPartialSemanticsTest` — 1 個 / 16 種形狀

`partialSemanticsPinned`:把「逐 component 各自 partial」的語意對真 codec 釘死 16 種形狀。
**這支變紅 = 新版 codec 語意變了**,不是測試壞了。

---

## 二、bytecode 政策閘門(G1,`tools/LockPolicyCheck.java`,23 項)

對**改寫後的真實 NMS 類別**做機械驗證,不是看原始碼。

| 規則 | 守什麼 |
|---|---|
| R0 | splice 數量(欄位數硬編碼,改 template 就要同步) |
| R1 | 碰 raw/摘要/ensuring 的方法都在 monitor 內 |
| R2 | 未持鎖的讀者先讀 volatile `pending` |
| R3 | 三個 leaf 的入口 guard 到位(13 個入口) |
| R4 | leaf 內無殘留的 `ContainerHelper.load/saveAllItems` |
| R5 | 漏斗兩個 hook 在方法入口(`hooks != 2` 就紅) |
| R6 | `CompoundTag` 兩個欄位 + `write` 開頭注入 + `copy` 帶欄位 + `LevelChunk` 的 enter/try/orig/finally-exit |

## 三、注入形狀指紋(G2,`tools/InjectionShapeCheck.java`,26.2 基準 110 行)

10 類指紋行,與 `gates/shape/<版本>.txt` 逐行 diff。**消失要紅,新增也要紅**(新增的行必須有人看過)。

`METHOD`(15 個必須存在的注入目標)、`CLASS`(10 類)、`SUPER`、`FIELD`、
`ITEMSREF`(33 行:誰直接碰 `items` 欄位——新增就是繞過 `getItems()` 咽喉的候選)、
`CHCALL`(27 行)、`CALLER-BE-NBT`(2 行:存檔視窗的呼叫者)、`HOPPERCALL`、`WRITECALL`、
`NBTUSE`(**最關鍵**:存檔視窗呼叫者對 NBT 家族用了哪些方法;26.2 兩個都是 `(none)` ⟹ 直寫的側車活得到 `write()`)。

## 四、關卡斷言數(G0–G8)

| 關卡 | 斷言數 | 代表性的幾條 |
|---|---|---|
| G0 | 約 20 | JDK major、核心 `version.json`、classfile major、nms-lib jar 數、工具、素材來源或自備素材 |
| G1 | 7 | build 綠、jar 是本輪產出、版本字串一致、template classfile major、政策閘門 0 違規 |
| G2 | 1 | 形狀指紋與基準完全一致 |
| G3 | 4 | test.sh 綠、失敗 0、成功數 ≥55(防「測試沒編進去」)、六個類別都真的有跑 |
| G4 | 19 | 每模式:無 VerifyError/例外/壞 bytes 落檔、transform 全成功、`stash`/`rawSave` 達標、`badRaw`/`eagerLoad`/`shadowMismatch`/`summaryMismatch` 為 0;A 另驗 `rawEmit ≥ 0.99×rawPassthrough` 與無 `BAD PASSTHROUGH`;B 驗 `rawPassthrough=0`;C 驗 `ptShadowOk` 大量且 `ptShadowMismatch=0`;最後四模式結構比對 |
| G5 | 2 | 裁判全部跑完無工作失敗、逐格零差異 |
| G6 | 7 | SHADOW 模式啟動、`shadowMismatch=0`、`summaryMismatch=0`、`summaryBuild` 達標、**摘要真的被問過**、`ensure>0`、無 VerifyError |
| G7 | 1(內含單一總判定 40+ 條) | 見下 |
| G8 | 6 | 版本、commit、README 標的版本、**splice 數字 = 設定值**、測試報告、交付夾 |

## 五、G7 單一總判定(`gates/py/judge.py`,六節)

1. **產物**:每個產物都要存在**且 mtime 晚於本輪開始**(殘留檔一律判紅)
2. **agent / 直寫**:開機四行、`rawPassthrough ≥ 0.9×rawSave`、`rawEmit ≥ 0.99×rawPassthrough`、
   `badRaw`/`summaryMismatch`/`shadowMismatch`/`eagerLoad` 為 0、`attrHopper>0`(tick step 真的推進了漏斗)
3. **健康**:VerifyError / NoSuchMethodError / `BAD RAW` / `BAD PASSTHROUGH` / lazycontainer 例外全為 0、
   `lc-badraw` 檔數 0、每份快照 `mca_restore.py verify --deep` 乾淨
4. **操作痕跡**(反空洞的核心):bot 宣稱的每個開箱/點擊/挖箱都要有伺服器端事件對得上、
   `attrPlayer ≥ 0.8×非空開箱數`、chunk 真的卸載重載過、重開階段有第二次 OPEN、
   API 操作全 OK、指令回饋逐條核對、金絲雀拿得到 Items、
   玩家關箱看到的非空格數 == 輸入(雙箱用事件配對,不猜鄰居)
5. **輸入→中段(零容忍)**:裁判讀到的容器數 == 普查數、chunk 數、解碼非空格數、輸出檔 ≠ 輸入檔、
   未被碰的容器沒有逐格內容變動、**箱/木桶/界伏盒的非預期差異(無機械事件證據)= 0**、
   正控制逐格斷言(見下)
6. **中段→最終(漏斗段)**:箱/木桶/界伏盒的變動全部有機械事件證據、物品守恆(含巢狀容器內容)、
   容器沒有消失/多出;開過的箱子 ≥70% 其 chunk 在開箱之後被重新寫回

### 正控制(刻意製造變化,驗判定抓不抓得到)

bot 操作 5 種(`open` / `pickput` / `move` / `take` / `dig`)+ 卸載重載 + 重開;
外掛 API 5 種(`stateupdate-noop` / `stateupdate` / `setcontents-noop` / `setcontents` / `clear`);
伺服器指令 7 類(`datamod` / `setblock` / `itemsx` / `itemreplace` / `clone` / `loot` / `slot40`)+ `/data get` 金絲雀。

斷言 kind 四種(`SAME` / `DIFF` / `MISSING` / `EXTRA`),assert 六種:
`slot`+`item`、`all_empty`、`emptied`、`moved_from`+`moved_to`、`others_contains`、`equals_source`,
**外加「沒被指定的格不准變」**。

## 五之二、存檔守門(`SilentWipeGuardTest`,8 例)

守的不變式:**這個容器載入時有東西、從載入到現在沒有任何存取點碰過它、存檔卻要寫出空的。**
正常運作下不可能成立 —— 沒被碰過的容器是把載入時收下的原始位元組原樣寫回去的;而任何合法的取走
(玩家、漏斗、比較器、外掛、指令)都必須先走 `getItems()/getContents()`,那就會把 `accessed` 設起來。

誤報比漏報更糟(把玩家正當取走的東西寫回去 = 複製),所以五個負向案例的份量比正向多。

| 測試 | 守什麼 |
|---|---|
| `wipedButRawStillThere` | raw 還在 ⟹ 當場寫回原始五格,`silentWipeHealed` +1 |
| `wipedAfterMaterialization` | raw 已被物化吃掉 ⟹ 救不回來,但**不寫出空的 Items**,`silentWipe` +1 且 healed 不加 |
| `alarmTripsSafeMode` | 報警同時就地降級:`passthrough()` 之後恆為 false(不必重啟) |
| `legitimateEmptyingIsSilent` | 玩家經 `getItems()` 取光 ⟹ 安靜,照常寫出空清單 |
| `loadedEmptyIsSilent` | 載入時本來就是空的 ⟹ 安靜 |
| `setItemsIsSilent` | `setItems` 整批換清單(GUARD_CLEAR)⟹ 安靜 |
| `partiallyEmptiedIsSilent` | 還剩一格有東西 ⟹ 安靜(守門只管全空) |
| `reentrantEnsureDoesNotCountAsAccess` | `ensure()` 內部呼叫 `this.getItems()` 會再走一次 leaf guard;若把它算成存取,守門對每個物化過的容器永久失效 |

成本:熱路徑只有兩個 boolean 讀。`loadedNonEmpty` 在載入時由 `rawListIsEmpty` 讀 6 個 byte 的
ListTag 表頭算出(不解析);`accessed` 是一個 volatile 寫,而且只在 `pending` 還是 true 時走得到
(每個容器每次載入至多一次)。解碼只發生在報警分支,那條路正常應該永遠不會執行。

## 五之三、紅綠驗證台(`gates/red.sh`)

單元測試證明的是「程式碼在假資料上會走進那個分支」。紅綠驗證台證明的是另一件事:
**在真的伺服器、真的正式站 region 上,把資料弄壞,紅燈真的會亮**——而且同一個故障在
關掉守門時真的會把東西弄丟。少了最後這一條,前面的紅可能只是「故障根本沒破壞力」。

| 回合 | 注入什麼 | 應該發生什麼 |
|---|---|---|
| `green` | 不注入 | 完全安靜、磁碟零流失(沒有假紅) |
| `wipeKeepRaw` | 清單被清空,但 raw 還在 | **磁碟零流失**:沒被碰過的容器本來就是原樣寫回,這是主要防線 |
| `wipe` | 清單被清空,raw 已被物化吃掉 | `SILENT WIPE` + `silentWipe` 計數器 + `SAFE MODE` |
| `wipe-noguard` | 同上,但 `-Dlazycontainer.guard=false` | **沒有任何警報,而且磁碟真的掉容器**(對照組) |
| `corruptRaw` | raw 被改壞一個 byte | `BAD RAW` + `lc-badraw` 落檔 + `SAFE MODE` |

故障注入預設全關,啟用時開機會印一整段 `FAULT INJECTION ACTIVE` 大字,G4 每個模式都會
grep 那段字判紅——避免有人把紅綠驗證台的旗標帶進出貨驗證。

## 六、離線比對判定

- `structcmp.py`:每份輸出「時間戳真的變過的 chunk ≥90%」(反空洞)+ 五個版本兩兩結構比對
  (**V 原版對照組的差異只報告不判紅**——那是原版自己的正規化)
- `judge_frozen.py`(G5):逐格解碼差異 0 / 缺少多出 0 / 物品多重集合相同 / 未被碰的結構原封 / 容器數 == 普查數
- `saved_after_open.py`:開過的箱子 ≥70% 其 chunk 在開箱之後被寫回

---

## 已知缺口(誠實記錄)

| 缺口 | 現況 |
|---|---|
| 壞 bytes 的終局處理(`onBadRaw` 落檔 / fallback / chunk 照常落盤) | `malformedRawThrowsRuntimeNotIoException` 守住「例外型別」這一半;**落檔與 fallback 這條路徑目前沒有測試案例**,只有 G4/G7 驗 `badRaw=0`(即真實資料上不該發生) |
| 存檔守門攔不到「有人碰過之後才被清空」 | 不變式的前提就是「沒人碰過」。外掛先 `getInventory()` 再清空,對 agent 而言與玩家取光無法區分 ⟹ 要靠外部的 `tools/container_loss_watch.py` 對帳,不是靠這一關 |
| 自動降級只關直寫,不關延遲載入 | 直寫(#261)是最年輕、最沒有生產里程的一層,先關它;延遲載入從 26.2-1 就在跑。要整個停用仍然只能換 jar |
| `-Dlazycontainer.summary=false` / `-Dlazycontainer.attribution=false` | 兩支 kill switch 沒有專屬測試 |
| `tools/LazyModelCheck.java` | 401 行的窮舉小模型探索器,不被任何 gate 執行(研究用,留著當文件) |
| EndRod / Folia 系核心 | 所有實機關卡跑在 Paper 上;跨 region 執行緒那一層只有 `EnsureRaceTest` 與 javap 簽章比對,**沒有 EndRod 實機關卡** |
