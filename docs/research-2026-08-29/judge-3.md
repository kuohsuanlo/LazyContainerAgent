# 裁決 3:do-relax-summary-first

工程優先(改動面/可測性/可回滾)三個軸,放寬摘要都贏一個數量級,而且它已經寫好了。

【改動面】放寬 = LazyContainerTemplate.java 686-712 一個方法內的 27 行(實測 `git diff --stat` = 36 行,全在 computeSummary 的 components 區塊)。不碰執行緒、不碰 hook 面、不碰 ensure、不碰存檔、磁碟格式零變更。逐格 = 新增 entryOff/slotEntryIdx/decoded 三個欄位 + 把 pending 的「純 volatile 寫」換成 bitmask 的 read-modify-write(design 鏡頭 B9、static-analysis 鏡頭都獨立指出這是現行設計結構上不可能有的一整類新競態) + 混合存檔把兩個分支長成樹 + ASM 首次改寫既有方法體(lazycontainer$rawItems 的 call-site rewrite,transformer 全新能力,需要重寫 fork 形狀比對)。這是重寫,不是優化。

【可測性】放寬的正確性判準是純函式差分:同一份 Items ListTag,摘要開口回答就必須與真 ContainerHelper.loadAllItems + isFullContainer 判準一致。現成 harness 已經在跑(SummaryDifferentialTest 2.8 萬案例),不需要新機制。逐格的核心不變式(INV-1..INV-8、發佈順序、ensureAll 只填未解格、混合存檔的 slot 判讀)全部是狀態機與跨執行緒性質,static-analysis 鏡頭誠實承認窮舉模型「證明的是模型不是那份 jar」,而它自己一個下午就出了兩次模型錯。

【可回滾】放寬版回退 = 換回舊 jar,磁碟上沒有任何位元組被改過形狀,downgrade-safe。逐格版一旦寫出過混合體存檔(未動 entry 保留原位元組 + 已動格重編碼 + 保留 junk),那些 chunk 的 Items 形狀已改變,回舊 jar 不乾淨。109 台正式服,這一條就定序。

【今天實跑查證】
1) 放寬版已在工作樹、未 commit(`git status` M template + M test.sh + M SummaryDifferentialTest + 3 個未追蹤新測試),`bash test.sh` 48 tests successful / 0 failed。
2) 生產完全沒有判準資料:`zgrep -h "fullQ=" logs/*.log.gz | wc -l` = 0,fullQ 是最新 commit f4aabad 才加的。逐格的收益上限正好等於 fullUnknownDirty 的次數,現在開工等於零根據投資。
3) 我在真 headless NMS 上測出放寬版的一個真破口(scratchpad/nsprobe/NsProbe.java):components 寫成無命名空間的 `max_stack_size=99`(vanilla 走 Identifier.tryParse 照樣生效,探針印出 vanilla max=99),27 格 count=64 的圓石 ⟹ vanilla 判「不滿」,而摘要答 PROVEN_FULL;兩種寫法並存(16/99)同樣假全滿。現行 template 只做 `comps.get("minecraft:max_stack_size")` 的字串全等比對。後果不是掉物,是假全滿 ⟹ 漏斗永久停止推入 ⟹ 該容器永不物化(components 鏡頭自己警告過這個形狀,但沒落實到程式碼)。舊嚴格規則因「看到別的 key 就棄權」而免疫,是放寬打開的洞。修法零配置:掃 keySet,key 不含子字串 "max_stack_size" 就跳過,含才正規化比對,命中 ≥2 次棄權(舊規則本來就 iterate keySet,成本不是新的)。

【鏡頭間衝突,已裁】paper 鏡頭建議把 HopperBlockEntity:579 的 container.isEmpty() 改寫成 `(container instanceof HopperBlockEntity) && container.isEmpty()` 並稱「逐字等價」——不等價。RandomizableContainerBlockEntity.isEmpty:49-52 會順手 unpackLootTable(null),而 getItem:55-57 只在 slot==0 才 unpack;推到 slot 3 的未開封戰利品箱開封時機會變。要打這條只能走 BaseContainerBlockEntity.isEmpty() 入口掛摘要(unpack 已先發生,時機不變),且不進本次出貨,單獨一批。

【不選 do-both / do-neither-yet 的理由】do-both 會讓一次出貨同時帶「新判定規則」與「新狀態機」,出事時無法二分定位,違反可回滾原則。do-neither-yet 不成立:量測本身需要出貨(fullQ 只在新 jar 裡),而放寬版已寫好且測試全綠,壓著不出等於白付 s3 商場 76.3% 全滿箱被迫整箱解碼的帳。

【下一步的判準,不是現在的決定】放寬上線 + fullQ 收滿一輪後再回來看:fullUnknownDirty 若塌到接近 0 而尖峰仍在,那剩下的就是 fullAnsweredNotFull(漏斗真的要推入),而 hopper-access 與 paper 兩個鏡頭都獨立證明那條路在 tryMoveInItem:589/592 兩行內必然整箱物化 ⟹ 逐格在那裡省不到,該做的是「同一 tick 首觸攤平」而不是「單次解碼變便宜」。

## 閘門
- G1 命名空間閘門(現在是紅的,必修):新增 SummaryDifferentialTest#maxStackSizeNamespaceSpellings —— 對 `minecraft:max_stack_size` / 裸 `max_stack_size` / `!minecraft:max_stack_size` / 裸 `!max_stack_size` / 兩種寫法並存 五種寫法,斷言 `tri==1 ⟹ vanillaFull==true`。今天實測 NsProbe:裸寫 max_stack_size=99 + count=64 的圓石,摘要答 PROVEN_FULL 而 vanilla 判不滿(兩種寫法並存同樣假全滿)⟹ 未修不得出貨。
- G2 偵測子字串窮盡性:SummaryDifferentialTest#maxStackSizeKeyDetectorIsExhaustive —— 對亂數 key 語料斷言『Identifier.tryParse(去掉前導 ! 的 key) 正規化後等於 minecraft:max_stack_size ⟹ 該 key 必含子字串 "max_stack_size"』,釘住快篩不會漏;並斷言命中 ≥2 次時摘要棄權(不得回答滿)。
- G3 全語料不對稱鐵律:既有 SummaryDifferentialTest 全綠且斷言 —— 摘要開口回答就與 vanilla 一致,`false PROVEN_FULL == 0`、假『證明為空』的格 == 0;同時輸出並記錄本次放寬把多少容器從 UNKNOWN 轉成 PROVEN_FULL(必須 >0,否則這次出貨沒有收益)。
- G4 前提釘樁:ComponentPartialSemanticsTest#partialSemanticsPinned 綠(壞 component 走 DFU partial、物品仍在、max 只由合法 max_stack_size 決定),外加一條斷言『id 不存在 / id=air ⟹ 該格真的為空』。這支是 MC/DFU 升版時第一個該紅的哨兵。
- G5 存檔零變更:斷言本次 diff 不觸及 encodeRaw / trySaveRaw / ensure / clear(`git diff --stat` 只有 computeSummary 的 components 區塊),並跑 EnsureRaceTest#saveDuringEnsure 得到 equalsRaw||equalsFull==true、savedEntries==27。磁碟逐位元組不變是本次出貨的免責基礎。
- G6 併發回歸:EnsureRaceTest 五支全綠、ROUNDS>=2000 連跑三輪 bad==0。出貨前必須說明 logs/2026-08-28-*.log.gz 內數筆 `raced=13 bad=1`(樣本 round#96 pending=false 但清單不完整 [13:empty])是哪一版產生的 —— 測試第 331 行 assertEquals(0, badRounds) 表示 bad>=1 是紅燈不是雜訊。
- G7 織入面機械檢查接進 build.sh:把 scratchpad 的 LockPolicyCheck 搬進 tools/ 並補上 build.sh:34-42 只驗 manifest 的缺口,要求 18 項通過 0 違規,且突變測試(拿掉任一 leaf 的 loadAdditional GUARD_CLEAR)必須立刻變紅 4 項。
- G8 金絲雀:單台非主力分流開 shadow(lazycontainer$verifyFull)+ attribution 連跑 ≥72h,斷言 summaryMismatch==0 且 log 確實出現 `fullQ=` 行(今天 109 台一行都沒有)。未達成不得擴散;擴散後再關 shadow。
- G9 回滾演練:提供 -Dlazycontainer.summary.strict=true 退回舊嚴格規則(用 SummaryDifferentialTest 斷言該旗標下逐案輸出與舊規則相同),並實際演練一次『換回前一顆 jar』確認容器行為正常 —— 本次出貨磁碟格式零變更,回滾必須是換 jar 而已。
- G10 逐格解碼的開工閘門(本次不做,先寫死):在 ≥3 台代表性分流收滿 ≥7 天的 fullQ 分佈之前不得開工;開工條件是放寬後 fullUnknownDirty 已塌、但 fullAnsweredNotFull 仍撐著尖峰。屆時若動工,三條硬約束缺一不可 —— 存檔一律 collapse 不准 merge、每格先寫值後標位元、decoded bitmask 只在 this monitor 內改。

## 白話摘要
【結論】先做「放寬摘要」,逐格延遲解碼這次不要動。原因很直白:放寬只改一個方法裡的 27 行,不碰多執行緒、不碰存檔、硬碟上的資料一個位元組都不會變樣,出事就換回舊 jar 就好;逐格解碼要新增三個欄位、把旗標換成會互相蓋掉的位元遮罩、還要讓存檔長出第三種「半解開」的寫法,那是在 109 台正式服上重寫,不是優化。而且六個鏡頭裡有三個各自算出同一件事:漏斗只要真的要往箱子塞東西,兩行之後整箱還是會被解開,逐格在那條路上省不到。

【好消息與壞消息】好消息是放寬版其實已經寫好了,躺在工作資料夾裡還沒送出,我今天把 48 個測試全跑過,一個都沒紅。它能救的正是廢土商店那種「每件商品都有名字」的滿箱——以前這種箱子永遠證明不了「滿」,漏斗每次來問都要整箱拆開,這就是 s45 卡 16 秒那條鏈的來源。壞消息是我用真的 Minecraft 程式碼實測,發現這個放寬版有一個洞:物品資料裡的堆疊上限如果寫成沒有前綴的寫法(遊戲照吃,我們的判斷式看不到),我們會誤判成「滿」。東西不會掉,但漏斗會從此再也不往那個箱子放東西,而且完全沒有錯誤訊息。這個洞是放寬打開的,舊的嚴格規則反而免疫。修法很小:多掃一次鍵名、遇到可疑寫法就放棄回答。

【出貨順序】先把上面那個洞補起來並補三組測試(沒補不准出),接著在一台非主力分流開「對帳模式」跑三天,確認一次誤判都沒有,再擴散到全艦隊。同一批不要夾帶任何別的改動——特別是有人提議去動漏斗那行 isEmpty(),那會改到未開封戰利品箱的開封時機,不是純優化,要另外一批。等新版本在線上跑滿一週、把「摘要為什麼答不出來」的四個數字收回來,我們才有資格談逐格解碼值不值得;現在 109 台的紀錄裡這個數字一筆都沒有,現在開工等於憑感覺花錢。