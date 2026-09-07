# 出貨 gate —— 精準測試,不是大量盲測

這個目錄是 LazyContainerAgent 的**出貨閘門**:每一項優化都有自己的 use case、自己的測試素材、
自己的通過條件。跑完全綠才准出貨;任何一關紅就是不准。

```bash
bash gates/run.sh                       # 全部(G0…G8),約 90–120 分鐘
bash gates/run.sh --tiers G0,G1,G2,G3   # 只跑離線關卡,約 5 分鐘
bash gates/run.sh --version 26.3        # 換版:讀 gates/versions/26.3.env
bash gates/run.sh --keep-fixtures       # 保留素材副本(預設跑完就刪——那是正式站的資料)
```

換版(26.2 → 26.3 → …)照 [`UPGRADE.md`](UPGRADE.md) 走。設計原則是:**測試資產是版本無關的基礎建設,
換版時是「重跑」不是「重寫」**;真正要改的只有 `gates/versions/<版本>.env` 與注入形狀基準。

每個測試案例的完整清點在 [`TESTCASES.md`](TESTCASES.md)。

---

## 換一台機器要準備什麼

`gates/` 本身是自足的(全部進 git),但下面這六樣**不在 repo 裡**,要自己備好。
`gates/versions/<版本>.env` 的每一行都是 `: "${VAR:=預設}"`,所以**不必改檔,export 同名變數就能覆蓋**。

| 要準備 | 變數 | 怎麼取得 |
|---|---|---|
| **目標版 JDK** | `LC_JAVA_HOME` | 版本看核心 jar 內 `version.json` 的 `java_version`(26.2 = Java 25) |
| **NMS 編譯相依** `nms-lib/` | `LC_NMS_JAR` | 見下方「怎麼生 nms-lib」。**不入 git**(太大、含 Mojang/Paper 產物) |
| **Paper bundler jar** | `LC_PAPER_BUNDLER` | 官方下載的那支 `paper-<版本>-<build>.jar`,gate 用它建測試伺服器 |
| **ViaVersion + ViaBackwards** | `LC_VIA_DIR` | 放這兩支 jar 的目錄。bot 用舊協定連線需要,**兩支都要**,只有 ViaVersion 會被擋 |
| **bot 的 node 相依** | (自動) | 第一次跑 G7 會自己在 `$LC_GATE_HOME` 跑 `npm install`;要外網 |
| **真實 region 素材** | `LC_FIXTURES` / `LC_FIXTURES_LOCAL` | 見下方「沒有艦隊存取怎麼辦」 |

選配:`LC_CHUNKGUARD_JAR`(另一支寫入屏障 agent,存在才會一起掛,用來驗兩支 agent 共存)。

### 怎麼生 `nms-lib/`

gate 要對「目標版本的真實 NMS」編譯 template 與兩支測試外掛,所以需要 mojmap 的 server jar 加上它的相依:

```bash
# 1. 先讓 Paper bundler 解開自己(產生 versions/<版本>/paper-<版本>.jar 與 libraries/)
java -jar paper-<版本>.jar --version
# 2. 把 server jar 與所有 libraries 攤平成一層放進 nms-lib/
mkdir -p nms-lib
cp versions/<版本>/paper-<版本>.jar nms-lib/paper-<版本>-mojmap.jar   # 檔名要含 mojmap 或 paper-
i=0; find libraries -name '*.jar' | while read j; do cp "$j" "nms-lib/lib-$i-$(basename $j)"; i=$((i+1)); done
```

檔名有隱含契約:`build.sh` / `test.sh` 用 `ls nms-lib/*mojmap*.jar nms-lib/paper-*.jar | head -1` 認 server jar,
其餘視為 libraries。G0 會驗 jar 數 ≥50。

### 沒有艦隊存取怎麼辦(自備素材)

預設是從艦隊主機 `scp` 正式站 region 的唯讀副本,**跑完就刪**。別台機器沒有那個存取時:

```bash
export LC_FIXTURES_LOCAL=1
export LC_FIXTURES="w1:overworld:r.0.0 w2:overworld:r.1.0 w3:the_end:r.-1.-1"   # 自己定 label
mkdir -p $LC_GATE_HOME/fixtures/w1 && cp <你的世界>/region/r.0.0.mca $LC_GATE_HOME/fixtures/w1/
```

檔名**必須**是 `r.<x>.<z>.mca`(還原工具的安全檢查會擋別的名字)。素材要求:
容器越多越有力(艦隊那三份分別是 28,372 / 28,584 / 20,655 個箱子),而且**必須是目標版本的世界檔**。
自備模式下 gate 不會刪你的檔案。

---

## 關卡

| 關卡 | 守什麼 | 素材 | 大約耗時 | 通過條件(摘要) |
|---|---|---|---|---|
| **G0** 環境/版本 | 拿錯 JDK、拿錯核心 jar、少裝工具 —— 這關沒過,後面每一關的紅綠都不能信 | 無 | 10 秒 | JDK major、核心 `version.json`、NMS classfile major、Via 外掛、素材來源可連 |
| **G1** 建置 + 政策閘門 | 編得出來;注入形狀沒違反跨執行緒鎖政策 | 無 | 40 秒 | `build.sh` 綠、template classfile major 對得上、`LockPolicyCheck` 違規 0、jar/pom 版本字串一致 |
| **G2** 注入形狀 diff | **換版最重要的一關**:目標版 NMS 的形狀指紋與基準逐行比對 | NMS jar | 20 秒 | 消失 0 **且新增 0**(新增的行同樣要人看過) |
| **G3** 差分/併發單元 | 語意:摘要 vs 真 codec、物化的跨執行緒視窗、直寫框架與走訪器、component partial、對帳告警 | 無(headless NMS) | 90 秒 | 64 個測試全綠、六個測試類別都真的有跑(清單見 [`TESTCASES.md`](TESTCASES.md)) |
| **G4** 存檔四模式 E2E | 磁碟輸出:原版 / 直寫 / 關直寫 / 直寫觀測 四種跑法的結果必須「結構相等」 | 真實 region ×3 | 35 分鐘 | 四模式互比零結構差、各模式計數器達標、**`rawEmit ≥ 0.99×rawPassthrough`** |
| **G5** 逐格裁判 | 遊戲看到的物品:在真伺服器裡逐容器逐格 `ItemStack.matches` | G4 的輸出 | 8 分鐘 | 逐格差異 0、缺少/多出 0、物品多重集合相同、未被碰的容器 Items 結構原封 |
| **G6** 摘要/影子對抗 | 執行中的語意:漏斗真的去問摘要、真解碼當即時神諭 | 真實 region ×3 | 10 分鐘 | `shadowMismatch=0`、`summaryMismatch=0`、**摘要真的被問過**(`summaryFull+summarySkip>0`) |
| **G7** 互動對抗總判定 | 真 bot 開箱/搬格/取物/挖箱、外掛 API、伺服器指令、卸載重載、漏斗真的搬東西 | 真實 region ×3 | 40 分鐘 | 單一 FINAL VERDICT:痕跡對得上、正控制逐格斷言全成立、非預期差異 0、物品守恆 |
| **G8** 出貨物件 | 版本字串、文件、交付夾 | 無 | 5 秒 | 版本一致、交付夾備妥(jar + 還原工具 + md5) |

`--tiers` 可以任意挑,但有依賴:**G5 吃 G4 的輸出**,G7 自己跑完整流程。

---

## 功能總表(從第一版到現在)

「省什麼」欄是這項功能存在的理由;「gate」欄是它現在被哪一關守著。
沒有 gate 的功能等於沒有出貨保護——這張表就是用來確保不會有那種功能。

### 一、核心三步(專案存在的理由)

| 功能 | 出處 | 為什麼存在 / 省什麼 | test case | 驗證方式 | gate |
|---|---|---|---|---|---|
| **延遲載入** `stash` | 首版 | chunk 一載入,原版把那一區**每個箱子每一格**從 NBT 拆包;絕大多數箱子從載入到卸載沒人開過。純白工。最重節點曾有 **45% 主執行緒**卡在這條鏈 | 真實倉庫 region(28,370 / 28,582 / 20,655 個容器)載入 | `stash` 隨載入累積;spark 對照:容器解碼佔比 62% → **0%** | G4(`stash>1000` 且四模式結構相等)、G5、G7 |
| **存取時物化** `ensure` | 首版 | 真的有人開箱/漏斗抽/比較器讀,才解**那一個**;把載入尖峰打散到各 tick | `EnsureRaceTest`;G7 真 bot 開 760+ 箱 | `attrPlayer ≥ 0.8×非空開箱數`——玩家開箱確實觸發物化,不是「沒人碰所以沒事」 | G3、G7 |
| **原樣寫回** `rawSave` | 首版 | 卸載存檔時全程沒被碰 ⟹ 把載入時收著的原始 bytes 逐位元組寫回,跳過重新打包(卸載約 11%) | 四模式 E2E + 逐格裁判 | 輸出與輸入**結構相等**;未被碰的容器 Items 結構原封 100% | G4、G5 |
| **影子驗證模式** `shadow` | 首版 + `f4b721c` | 上線前的零風險驗證:每次原樣寫回之前,額外把原版做法算一遍對照。分歧只回報、**絕不改寫玩家資料**(早期版本會改寫,`f4b721c` 改掉) | G6(真世界資料 + 真呼叫者) | `shadowMismatch=0`;`benignReorder`(外掛寫法造成的順序差)只報不擋 | G6 |
| **benignEncoding**(26.2-6) | 本次 | 同樣的物品、不同的寫法:真倉庫 47 個界伏盒,巢狀 container 的 entry 省略了預設 `count:1`,原版重編碼會補上、我們原樣保留。以前算成分歧,影子模式在真實資料上就變成噪音,值班的人會學會忽略它 | G6 實測抓到 | 判定用**原版自己的解碼器**逐格 `ItemStack.matches`,不是我們認定;相同才歸這類,否則仍是 `shadowMismatch` | G6 |
| **eager 退回** `eagerLoad` | 首版 | input 不是 `TagValueInput`(理論上不會)⟹ 完全退回原版行為,不冒險 | — | 真實資料上 `eagerLoad` 必須恆為 0(>0 表示有沒想到的輸入型別) | G4、G7 |
| **安全鐵律:base 沒 splice 就不動 leaf** | 首版 | 父類別沒改成功卻改了子類別 = `NoSuchMethodError` 整台起不來 | — | 開機必須看到 `spliced N fields + M methods` + 三個 `transformed leaf` | G1(政策)、G4/G7 開機行 |
| **template 就緒閘門** | `4297288` | 舊閘門賭「總有別的類先載 base」;EndRod r149 改了初始化順序 ⟹ **三個 leaf 全被跳過、agent 靜默失效**(stash=0 但 active=true,生產實測) | — | 開機三個 `transformed leaf` + `stash>0` | G4、G7 |

### 二、記憶體與別名(審計後的加固)

| 功能 | 出處 | 為什麼存在 / 省什麼 | test case | 驗證方式 | gate |
|---|---|---|---|---|---|
| **raw 存 bytes 而非活體 NBT 樹** | `1061321` | 樹的物件開銷遠大於 bytes;而且樹會被別人共用到 | `SummaryDifferentialTest.rawBytesRoundTrip` | round-trip 後結構相等;Fable 5 審計確認記憶體有界不洩漏 | G3 |
| **每次存檔 parse 一棵新的私有樹** | `8a32690` → 26.2-3 | 舊版把「活體 BE 欄位上的樹」放進存檔輸出 ⟹ `/clone`、structure、`getState`、非同步寫盤互相踩(管理員指令才踩得到的邊角漏洞) | G7 `clone` 正控制 | clone 出來的容器內容 == 來源容器 | G7 |

### 三、漏斗摘要(讓活躍倉庫也省得到)

| 功能 | 出處 | 為什麼存在 / 省什麼 | test case | 驗證方式 | gate |
|---|---|---|---|---|---|
| **摘要快答(滿/空)** | `97a8b9c` | 漏斗每 tick 都在問「目標滿了沒/這格空不空」;答這兩個問題不需要整箱解碼。沒有它,分類倉的箱子會被漏斗一直 ensure 掉,延遲載入的好處歸零 | `SummaryDifferentialTest`(同一份未解碼 Items 餵摘要 vs 餵真 `ContainerHelper.loadAllItems`) | **摘要棄答不算失敗;只要開口回答就必須與 vanilla 一致**;執行中用 shadow 當即時神諭 | G3、G6(`summaryMismatch=0` 且 `summaryFull+summarySkip>0`) |
| **缺 `Slot` 欄位 = 0,不是丟棄** | `ebcd649` | 差分測試抓到的真 bug:早期版本 `continue`,導致 slot 0 被誤標「證明為空」 | `SummaryDifferentialTest` | 真 codec 差分 | G3 |
| **非數值 `Slot` 整份棄答(A2)** | `198a1fc` | 壞資料被當 slot 0 且標成 clean+滿 ⟹ **假「全滿」**;DFU partial 語意版本間可能不同,棄答在兩種語意下都安全 | `SummaryDifferentialTest` A2 雙向案例 | 差分 | G3 |
| **放寬:component 不再擋「滿」的證明** | `8f6b40b` | s3 商場 12,754 個全滿箱有 **76.3%** 因為帶 component 被迫整箱解碼 | `ComponentPartialSemanticsTest`(16 種形狀對真 codec 釘住) | 逐 component partial 語意:壞的被丟、好的留著、物品永遠在 | G3 |
| **雙箱委派守護** | `97a8b9c` | `CompoundContainer` 把 54 格拆成兩半各自委派;沒守會問錯半邊 | G7:`CLOSE size=54` 的容器把兩半合併後比對非空格數 | 玩家關箱看到的非空格數 == 輸入普查 | G7 |
| **loot table 容器不摘要** | `97a8b9c` | 還沒生成內容的 loot 箱不能被當成「空」 | G7 `loot` 指令正控制 | 輸出 others 必須仍含 `LootTable` 且逐格 SAME | G7 |

### 四、跨執行緒正確性(26.2-2,EndRod/Folia 才踩得到)

| 功能 | 出處 | 為什麼存在 / 省什麼 | test case | 驗證方式 | gate |
|---|---|---|---|---|---|
| **`pending` volatile + 填完才翻旗標(A1)** | `198a1fc` | EndRod 允許插件執行緒讀活體容器;舊順序讓另一條執行緒讀到**半填清單**,最壞把 27 格滿箱存成 `Items: []`。實測 2000 輪中 **1943 輪**紅 | `EnsureRaceTest`(兩執行緒搶 ensure) | 斷言「`pending=false` ⟹ 清單完整」與「ensure 中途存檔絕非子集」;**內建 `raced>0`,不會變成假綠** | G3 |
| **四條狀態路徑進 monitor** | `198a1fc` | load/ensure/save/clear 彼此序列化;未持鎖的讀者只准讀 volatile 旗標 | `LockPolicyCheck`(bytecode 機械驗證) | 對「改寫後的真實 NMS 類別」驗:碰 raw/摘要的方法都在 monitor 內、13 個 leaf 入口 guard 到位、無殘留 `ContainerHelper.load/saveAllItems` | G1 |
| **leaf 載入入口 clear guard** | `4dd733b` | 活體 BE 重跑 `loadAdditional` 會讓**被刪除的物品復活** | `EnsureRaceTest` | | G3 |

### 五、存檔直寫 #261(最新、也最危險的一項)

| 功能 | 出處 | 為什麼存在 / 省什麼 | test case | 驗證方式 | gate |
|---|---|---|---|---|---|
| **直寫 passthrough** | `cb04cf1` | 沒被碰的箱子存檔時,原本還要「解成 NBT 樹 → 核心逐節點重新序列化」。#261 點名這段是艦隊 5 秒級卡頓來源之一。s45 實測 **99.3% 的存檔跳過解碼** | 四模式 E2E:A(直寫)vs B(關直寫)vs C(觀測)vs V(原版) | 四者輸出**結構相等**;逐格裁判零差異 | G4、G5 |
| **寫入前自檢走訪器** | `03926b6` | 壞 bytes 寫進 chunk = 整個區塊讀不回來。走訪器逐條規則對齊讀取端**或更嚴**(比它寬鬆才危險) | `RawPassthroughFramingTest`(fuzz + 變異 + 超大陣列 + 非法 UTF-8) | 走訪器接受的,真 `NbtIo` 也必須接受;`badRaw` 恆 0 | G3、G4 |
| **壞 bytes 的終局處理** | `03926b6` | 26.2 的 NBT 讀取端對壞資料丟 **RuntimeException 不是 IOException**;只接 IOException 會讓例外穿出去,核心把**整個區塊這輪的存檔丟掉**(其他容器的變更一起沒寫),而且每次自動存檔重演 | `RawPassthroughFramingTest.malformedRawThrowsRuntimeNotIoException` | 壞 bytes ⟹ 落檔 `lc-badraw-*` + 印座標 + 該容器改走原版編碼,**chunk 照常落盤** | G3、G4(`lc-badraw` 檔數 0) |
| **直寫觀測模式** `passthrough.shadow` | `03926b6` | 上線前一天用真路徑對帳:磁碟照舊寫解析出的樹,直寫只做探針 | G4 模式 C | `ptShadowOk` 大量、`ptShadowMismatch=0`、`rawPassthrough=0` | G4 |
| **`BAD RAW` log 格式 + 看門貓整合** | `7cdd04f` | 面板的區塊救援偵測器要能從 log 定位並自動建案 | 面板 `chunkguard.py` 的 `HIT_RE` | 行內含 `minecraft:<維度> chunk (cx, cz) block x, y, z` | 面板端(不在本 gate) |
| **直寫對帳告警 `rawEmit`** | 26.2-6(本次新增) | **換版唯一「不會自己爆」的失效模式**:三個 hook 都 armed、`attachRaw` 成功、`rawPassthrough` 照加,但核心若在存檔鏈中途重建 CompoundTag,側車會被靜默丟掉 ⟹ 箱子存成空。唯一外顯訊號就是「掛上去的次數」追不上「真的寫出去的次數」 | `PassthroughDeficitTest` 四例(側車全丟要告警 / 正常 IO 落後 / 爆量存檔連續落後 / 一次性尖峰,後三者都不得告警) | `rawEmit ≥ 0.99×rawPassthrough`;**已經沒有新的側車掛上去、差額卻連續數輪追不上**才印 `BAD PASSTHROUGH`(第一版判準只看「差額不降」,大批存檔當下就誤報——G4 實測抓到) | G3、G4-A、G7 |
| **還原工具 `tools/mca_restore.py`** | `03926b6` | 真出事要救得回來:純位元組拼接、不重新編碼(重寫等於在每個欄位上重賭一次) | G7 對每份輸出跑 `verify --deep` | 「沒有發現問題」;寫入前確認 `session.lock` 沒被鎖、沒有程序開著該檔、先備份 | G7 |

### 六、觀測與決策證據(#223)

| 功能 | 出處 | 為什麼存在 | test case | 驗證方式 | gate |
|---|---|---|---|---|---|
| **ensure 觸發者歸因八桶** | `2fbd9da` | #223 未結②:到底是誰在觸發解碼(漏斗/比較器/玩家/商店外掛/存檔/掉落/原版/其他) | `AttributionClassifyTest` | G7 用 `attrPlayer`、`attrHopper` 當「操作真的發生」的反空洞證據 | G3、G7 |
| **解碼耗時 + 分佈 + 尖峰** | `1128b58`、`49f5c73` | #223 未結①要的是秒數不是次數;`decodeSpikeMs` 是**物化尖峰**,不是存檔尖峰 | `AttributionClassifyTest` | `decodeHist` 四桶 | G3 |
| **`fullQ` 回答分佈** | `f4aabad` | 逐格解碼值不值得做的判準(結論:不值得,見 `docs/PER-SLOT-DECODE-DECISION.md`) | `AttributionClassifyTest` | 四桶:證明滿/證明不滿/佔滿但證不出/整份放棄 | G3 |
| **注入形狀指紋** | 本次新增 | 換版時先機械 diff,再談跑不跑得動 | `tools/InjectionShapeCheck.java` | 對目標版 NMS jar 掃出 110 行指紋,與基準逐行比 | G2 |

### 七、旗標

| 旗標 | 作用 | 被哪一關驗 |
|---|---|---|
| `-Dlazycontainer.shadow=true` | 影子驗證:輸出等同原版、只回報不改資料 | G6 |
| `-Dlazycontainer.passthrough=false` | 關掉存檔直寫(回 26.2-2 舊路徑) | G4 模式 B |
| `-Dlazycontainer.passthrough.shadow=true` | 直寫觀測模式 | G4 模式 C |
| `-Dlazycontainer.summary=false` | 關掉漏斗摘要快答 | (手動;G6 是開著驗) |
| `-Dlazycontainer.attribution=false` | 關掉歸因(stats 印 `attribution=off`) | — |
| `-Dlazycontainer.verbose[.ms]` | 計數器輸出 | 每一關都靠它讀計數 |
| `-Dlazycontainer.dump[.dir]` | mismatch / 壞 bytes 落檔位置 | G4(檢查落檔數 0) |

---

## 為什麼是這些門檻(反空洞)

這套 gate 最花力氣的不是「怎麼測」,是**「怎麼讓測試沒真的測到的時候會紅」**。踩過的坑都變成了門檻:

- **`/forceload` 吃方塊座標不是 chunk 座標**,而且單次上限 256 chunk。第一版寫錯,載到的是隔壁的一個 chunk,
  `stash=0`、輸出 md5 沒變,「零差異」當然成立 —— 所以每個輸出都要驗**時間戳真的變過的 chunk 數 ≥ 90%**。
- **26.2 的維度目錄是 `world/dimensions/minecraft/<dim>/region`**,寫成舊的 `world/region` 會讓核心重新生成平地,
  一樣「零差異」—— 所以要驗裁判讀到的容器數 == 普查數。
- **bot 說它開了箱不算數**:每個 bot 宣稱的操作都要有伺服器端事件(`OPEN`/`CLICK`/`BREAK`)對得上,
  而且 `attrPlayer` 要跟得上非空開箱數。全空的世界跑一輪不可能通過。
- **「開過但沒改 ⟹ 輸出等於輸入」是空洞的相同**:所以要驗開過的箱子其 chunk 在**開箱之後**確實又被寫回。
- **只看「有沒有差異」太寬鬆**:正控制是逐格斷言(搬去哪一格、哪一格被清空、沒被指定的格不准變)。
- **產物必須是本輪的**:判定會比對每個產物的 mtime 與本輪開始時間,殘留檔一律判紅。
- **測試數量本身也是門檻**:G3 要求 ≥55 個測試通過(目前 64),避免「某個測試類別沒編進去所以全綠」。
- **「可解釋的差異」要有事件證據,不能用幾何猜**:G7 中間有 110 秒必須解凍(不解凍區塊不會卸載重載),
  那段時間世界自己的農場在動。第一版用「六鄰有漏斗」猜哪些差異是機械造成的,猜太窄——s45 的界伏盒裝填機
  是**發射器朝上把新界伏盒放到自己頭上**,bot 挖掉的箱子 15 秒後就被補了一顆(身分 UUID 不同)。
  現在由事件外掛記錄漏斗搬運與發射器動作真的碰過哪些位置,只有那些位置豁免零容忍;
  漏斗/投擲器/發射器本身不是 agent 守的型別,它們的變動歸**全 region 物品守恆**管(總數與多重集合都不准變)。
- **`forceload` 的票會存進世界檔**(`<維度>/data/minecraft/chunk_tickets.dat`):上一輪若在
  `forceload remove all` 之前被中斷,下一次開機會在「還沒 tick freeze」的狀態下把整批倉庫載回來——
  原版對照組會當場 GC 死鎖(實測:9 GB heap、`Preparing spawn area: 99%` 卡住不動)。
  每次擺素材與每次開機前都清掉;`run.sh` 也裝了 trap,被中斷時會把子關卡與伺服器一起收掉。

---

## 產物

一次執行的所有東西都在 `$LC_GATE_HOME/out/<執行代號>/`(預設 `~/Server/claude-sandbox/workspace/_lcgate`):

```
REPORT.md          總表(每關 PASS/FAIL、耗時、OK/FAIL 條數)
G<n>.log           該關完整輸出
G<n>.result        該關逐條 OK/FAIL/WARN
g4/                四模式的 console、log、計數器、輸出 region、結構比對
g5/                裁判報告 report-*.json 與判定
g7/                bot log、trace.log、快照、裁判報告、FINAL VERDICT
```

**素材(正式站 region 的唯讀副本)預設在跑完後刪除**;要連跑多輪用 `--keep-fixtures`。
