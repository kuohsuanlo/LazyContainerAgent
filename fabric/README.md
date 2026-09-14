# LazyContainer (Fabric)

> **箱子物品「延遲反序列化 + 沒碰過就原樣寫回」的 Fabric mod。**
> [LazyContainerAgent](https://github.com/kuohsuanlo/LazyContainerAgent)(Paper 1.21.11 Java agent)的 Fabric 移植版,
> 支援 **Minecraft 1.21 ~ 1.21.11 全系列**(Stonecutter 多版本建置)。
> 原作:廢土貓大 LogoCat · 廢土 · mcfallout.net

把 chunk 載入時「立刻把每個箱子的物品從 NBT 解包」與卸載時「重新打包」這兩筆白工砍掉:
沒人要看的箱子,別急著拆;沒人動過的箱子,原封不動放回去。原理、效能實證、資料安全分析
請見原專案的 README / FINDINGS / ADVERSARIAL-REVIEW。

## 與 Paper agent 版的差異

| | Paper 版(原作) | Fabric 版(本專案) |
|---|---|---|
| 注入方式 | `-javaagent:` + ASM bytecode splice | **Fabric mod + Mixin**(`@Redirect` / `@Inject`) |
| 安裝位置 | 伺服器 jar 旁,啟動參數掛載 | **直接丟 `mods/`** |
| 版本綁定 | 只支援 Paper 1.21.11 | 1.21~1.21.11,五個 jar 各涵蓋一段(見下表) |
| `getContents()` guard | 需要(CraftBukkit 加的繞過點) | 不需要(vanilla 無此方法,唯一咽喉就是 `getItems()`) |
| 安全停機 | 版本不符 → VerifyError 開機炸 | mixin 套用失敗(`defaultRequire=1`)→ 開機炸,同樣**絕不靜默改壞資料** |
| 邏輯 / 旗標 / 計數器 | — | **逐一對齊原版**(shadow、benignReorder、dump、verbose 全保留) |

## 版本對應

| 建置目標 | 涵蓋的 Minecraft 版本 | NBT API 路徑 |
|---|---|---|
| 1.21.1 | 1.21 ~ 1.21.1 | `CompoundTag` + `HolderLookup.Provider` |
| 1.21.4 | 1.21.2 ~ 1.21.4 | 同上 |
| 1.21.5 | 1.21.5 | 同上 |
| 1.21.8 | 1.21.6 ~ 1.21.8 | `ValueInput` / `ValueOutput`(1.21.6 起) |
| 1.21.11 | 1.21.9 ~ 1.21.11 | 同上 |

> 跨版本相容範圍是以「本 mod 有碰到的 NMS 介面在該區間內未變動」為準所宣告;
> fabric.mod.json 會擋掉範圍外的版本。**上線前一律先在測試環境跑 shadow 驗證**(見下)。

## 快速上手

1. 把對應版本的 `lazycontainer-1.0.0+<版本>.jar` 丟進伺服器 `mods/`(需要 Fabric Loader ≥ 0.16,**不需要 Fabric API**)。
2. 第一次啟動請加 shadow 驗證旗標(跟原版完全相同的 `-D` 參數,加在 `java` 那行、`-jar` 前面):

```
java ... \
  -Dlazycontainer.shadow=true \
  -Dlazycontainer.verbose=true \
  -jar fabric-server-launch.jar nogui
```

3. **先驗證,別急著上真效能** —— 開著 `shadow=true` 跑幾天。它會把優化後的輸出跟原版做法逐位元組對照:
   只要 `shadowMismatch` 一直是 **0**,代表輸出跟 vanilla 完全一致、資料零風險(此階段兩套都做,暫時不會變快)。
   - 開機 log 應出現 `[LazyContainer] mixins applied [SHADOW mode]`。
   - verbose 每隔一段印一行 `stash=… rawSave=… shadowMismatch=0 …`;`stash` 持續往上爬 = 正在運作。
4. 跑數天 `shadowMismatch=0`、也沒玩家回報少東西 → 把 `-Dlazycontainer.shadow=true` 拿掉、重啟,效能才真正省下來。

**回滾**:把 jar 從 `mods/` 拿掉、重啟 → 100% 回 vanilla,不需任何資料遷移(硬碟格式從頭到尾沒被改過)。

## 旗標(與原版相同)

| 旗標 | 作用 |
|---|---|
| `-Dlazycontainer.shadow=true` | **上線必開**。輸出保證等同 vanilla;暫無加速。 |
| `-Dlazycontainer.verbose=true` | 背景 daemon 定期印計數。 |
| `-Dlazycontainer.verbose.ms=8000` | verbose 列印間隔(ms,預設 30000)。 |
| `-Dlazycontainer.dump=true` | mismatch / benign reorder 時把 raw/eager SNBT 各落一檔(`lc-mismatch-N` / `lc-benign-N`,各前 30 次)。 |
| `-Dlazycontainer.dump.dir=<路徑>` | dump 落檔目錄(預設 `.`)。 |

## 建置

需求:**JDK 21** + **Gradle 9.6+**(首次可用系統 Gradle 產生 wrapper:`gradle wrapper`)。

```
./gradlew buildAndCollect      # 一次建出全部 5 個版本,收集到 build/libs/1.0.0/
./gradlew "Set active project to 1.21.1"   # 切換 IDE 作用中版本(Stonecutter)
```

程式碼的版本差異用 Stonecutter 條件註解(`//? if >=1.21.6 { ... //?} else { ... //?}`)管理,
單一程式碼庫、一次建出各版本 jar。

## 架構(對照原版)

| 原版(agent) | Fabric 版 |
|---|---|
| template splice 進 `BaseContainerBlockEntity` 的 `lazycontainer$pending/raw` 欄位 | `BaseContainerBlockEntityMixin` 的 `@Unique` 欄位 + `LazyContainerState` duck interface |
| leaf 的 `ContainerHelper.loadAllItems/saveAllItems` 呼叫 redirect | `Chest/Barrel/ShulkerBox` mixin 的 `@Redirect` → `LazyContainerLogic` |
| `getItems()/getContents()` 入口 ensure-guard、`setItems()` 清旗標 | `@Inject(at=HEAD)` 於 `getItems`/`setItems` |
| `((TagValueInput)input).input` 取未解碼 raw | `TagValueInputAccessor`(`@Accessor`,1.21.6+) |
| `LazyContainerRuntime`(shadow/計數器/dump) | 原樣移植 |

不變式與原版完全相同:pending ⟺ 未物化、ensure 先清旗標再解碼(reentrancy 安全、失敗還原重試)、
寫回一律 `raw.copy()`(避免存檔輸出與活容器別名)、空清單 shulker 不走 raw(對齊 vanilla discard 行為)、
loot table 容器不進 lazy 路徑(正交)。

## 限制與注意

- **益處依賴「箱子沒被碰」**:churn / 閒置儲存大勝;活躍的漏斗/比較器分類倉會把箱子 ensure 掉,
  純省比例變小(主要益處變成把載入尖峰打散)。與原版相同。
- **其他效能 mod 相容性**:任何**繞過 `getItems()` 直接讀 items 欄位**的 mod 都可能看到未物化的空清單。
  已知 **Lithium 的 hopper 快取**(`mixin.block.hopper`)屬此類 —— 同裝時請先在測試環境驗證,
  或在 lithium 設定檔停用該項。vanilla 伺服器(不裝其他容器優化 mod)無此顧慮。
- **1.21.9~1.21.11 / 1.21.2~1.21.4 的跨版本 jar** 若在某個小版本上 mixin 套用失敗,會開機直接報錯
  (安全停機,不會靜默壞資料);屆時請用 Stonecutter 對該版本單獨建置。
- 用戶端單機(整合伺服器)也能用,但收益場景主要在伺服器。

## 檔案地圖

```
src/main/java/io/github/kuohsuanlo/lazycontainer/
  LazyContainerMod.java        Fabric 進入點(banner / verbose / shutdown stats)
  LazyContainerRuntime.java    shadow 開關 + 計數器 + dump(原樣移植)
  LazyContainerLogic.java      核心:lazy load / ensure / raw save / shadow 比對(雙 API 路徑)
  LazyContainerState.java      duck interface(pending / raw / regs)
  mixin/
    BaseContainerBlockEntityMixin.java   狀態欄位 + ensure 實作
    ChestBlockEntityMixin.java           redirect + guards
    BarrelBlockEntityMixin.java          redirect + guards
    ShulkerBoxBlockEntityMixin.java      redirect(loadFromTag / allowEmpty=false)+ guards
    TagValueInputAccessor.java           1.21.6+ 取底層 CompoundTag
settings.gradle.kts / stonecutter.gradle.kts / build.gradle.kts / stonecutter.properties.toml
```

## 致謝

核心設計、資料安全鐵律、shadow 驗證方法論皆出自
[kuohsuanlo/LazyContainerAgent](https://github.com/kuohsuanlo/LazyContainerAgent)(廢土貓大 LogoCat)。
本專案僅做 Fabric / 多版本適配。
