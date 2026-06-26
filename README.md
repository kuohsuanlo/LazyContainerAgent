# LazyContainerAgent

**繁體中文** ｜ [English](docs/README.en.md)

> **箱子物品「延遲反序列化 + 沒碰過就原樣寫回」的 Java agent。**
> 把 chunk 載入時「立刻把每個箱子的物品從 NBT 解包」與卸載時「重新打包」這兩筆白工砍掉。

⚠️ **這不是外掛(plugin),是 Java agent** —— 用 `-javaagent:` 掛在 JVM 上,**不要丟 `plugins/`**(丟了沒用)。

> 🔒 **版本敏感(務必先讀)**
> 本 agent 以 bytecode **直接織入 Paper 26.2 / Java 25** 的內部類別(template classfile major 69),屬**版本綁死**的工具。
> - **僅適用於 Paper 26.2 + Java 25。** 任何其他 Minecraft 版本或 Java 版本,**一律不要直接套用**。
> - 換版必須:① 以對應版本的 NMS 重新編譯 `template/`、② 將 ASM 升級到能解析目標 classfile 版本、③ 重新以 shadow 模式驗證。
> - 版本不符時會在**開機或首次載入箱子時直接拋出例外**(`VerifyError` / `NoSuchMethodError`)。這是**刻意的「安全停機」設計——絕不會靜默改壞或弄丟資料**,但該節點會無法啟動,因此**務必先在測試環境驗證**再上線。
> - 測試素材(region / 物品 dump)為目標版格式,請勿在其他版本載入。
> - 26.2 實機測試報告:[`docs/test-reports/26.2.md`](docs/test-reports/26.2.md)。
---

## 快速上手

> 前提:**Paper 26.2 + Java 25**(其他版本請先看上面的「版本敏感」)。

**1. 放 jar** —— 把 `LazyContainerAgent.jar` 放到節點看得到的位置(跟你的伺服器 jar 放同一層最省事)。

**2. 改啟動引數** —— 在 `java` 那行、`-jar` 的**前面**,加上:

```bash
java ... \
  -javaagent:LazyContainerAgent.jar \
  -Dlazycontainer.shadow=true \
  -Dlazycontainer.verbose=true \
  -jar <你的 Paper>.jar nogui
```

**3. 先驗證** —— 開著 `shadow=true` 跑幾天,確認 `shadowMismatch` 一直是 **0** 再關 shadow 換真效能。

**4. 回滾** —— 拔掉 `-javaagent` 旗標重啟即回 100% vanilla,不需任何資料遷移。

---

## 版本支援

| 版本 | Java | 狀態 |
|------|------|------|
| Paper 1.21.11 | Java 21 | ✅ **verified** — 實際測試通過 |
| Paper 1.20.4 | Java 17 | 🟡 mapping defined |
| Paper 1.19.4 | Java 17 | 🟡 mapping defined |
| Paper 1.18.2 | Java 17 | 🟡 mapping defined |
| Paper 1.17.1 | Java 16 | 🟡 mapping defined |
| Paper 1.16.5 | Java 8  | 🟡 mapping defined (Spigot) |
| 1.12.2 | Java 8  | ❌ 不支援 (ContainerHelper 不存在) |

啟動時自動偵測 MC 版本（讀 `MinecraftServer` classfile major + 常數池掃描）。
偵測失敗可用 `-Dlazycontainer.version=1.21.11` 手動指定。詳細狀態定義見 [`docs/PROGRESS.md`](docs/PROGRESS.md)。

---

## 檔案地圖

```
src/main/java/io/github/kuohsuanlo/lazycontainer/
  LazyContainerAgentMain.java       premain / agentmain 入口
  LazyContainerRuntime.java         純 JDK 計數器 + verbose logger
  ContainerHelperInterceptor.java   攔截器核心 (pending map + ensure + shadow roundtrip)
  LazyContainerTransformer.java     ASM: ContainerHelper 2 method + leaf getItems guard
  VersionDetector.java              MC 版本偵測 (classfile major + CP scan)
  NmsRegistry.java                  Per-version NMS mapping table
  DetachManager.java                安全 detach/reload (flush pending + unregister + retransform)
template/                           v1.0 splice 參考 (保留未刪)
build.sh                           建置腳本
```

---

## 旗標

| 旗標 | 作用 |
|------|------|
| `-Dlazycontainer.shadow=true` | 開啟 shadow 驗證 (寫 raw 前比對 eager, mismatch 自動寫 eager) |
| `-Dlazycontainer.verbose=true` | 背景 daemon 定期印計數 (stash/ensure/rawSave/shadowMismatch/pending) |
| `-Dlazycontainer.verbose.ms=8000` | verbose 列印間隔 (ms,預設 30000) |
| `-Dlazycontainer.dump=true` | mismatch/benign reorder 時 dump SNBT 檔 (前 30 次) |
| `-Dlazycontainer.version=1.21.11` | 手動指定 MC 版本 (覆蓋自動偵測) |

---

## 建置

```bash
bash build.sh        # JDK 21+; 產出 target/LazyContainerAgent.jar
```

不再需要 `nms-lib/` 目錄與 template 編譯步驟。

### CI 產出檔名說明

GitHub Actions 會在 JDK 21 / 22 / 23 三個版本上分別建置，產出：

```
LazyContainerAgent-<版本>-jdk21.jar
LazyContainerAgent-<版本>-jdk22.jar
LazyContainerAgent-<版本>-jdk23.jar
```

版本號規則：
- **Tag build**（`git tag v1.0`）→ 產出 `LazyContainerAgent-1.0-jdk21.jar`（自動去掉 `v`）
- **Dev nightly** → `pom.xml` 版本 + commit short sha，如 `LazyContainerAgent-1.1-SNAPSHOT-abc1234-jdk21.jar`

### 發行流程

使用 `tools/bump_version.sh` 管理版本：

```bash
# 開發階段：升到下一版
bash tools/bump_version.sh minor        # 1.0 → 1.1-SNAPSHOT, commit 不 tag

# 準備發行：去掉 -SNAPSHOT，打 tag
bash tools/bump_version.sh release      # 1.1-SNAPSHOT → 1.1, tag v1.1
git push && git push --tags             # CI 自動產 release

# 發行後：升到下一版
bash tools/bump_version.sh minor        # 1.1 → 1.2-SNAPSHOT
git push
```

詳細流程及 CI 守門員規則見 [`docs/notes/PR.md`](docs/notes/PR.md)。

**三個 JAR 內容完全一樣**（agent target Java 21 bytecode），只是確保 agent 在不同 JDK 下都能正常編譯與執行。使用者只需挑選跟 **伺服器執行的 JDK 主版本號** 相同的檔案即可：

- 伺服器用 JDK 21 → 下載 `*-jdk21.jar`
- 伺服器用 JDK 22 → 下載 `*-jdk22.jar`
- 伺服器用 JDK 23 → 下載 `*-jdk23.jar`

若不確定伺服器 JDK 版本，執行 `java -version` 查看。任何一個都能用，只是檔名標示便於辨識。

---

## 它怎麼運作

```
Chunk load:
  ContainerHelper.loadAllItems(input, items)
    → input instanceof TagValueInput?
        ├─ yes → stash raw "Items" tag → skip decode
        └─ no  → normal decode

Container access:
  getItems()/getContents()
    → ContainerHelperInterceptor.ensure(this)
        → find items field → pending? → materialize via ContainerHelper.loadAllItems

Chunk save:
  ContainerHelper.saveAllItems(output, items)
    → pending? + canWriteRaw?
        ├─ yes → raw writeback
        └─ no  → normal encode (or ensure first)
```

Shadow mode 在寫 raw 前多做一次 eager roundtrip (parse→encode) 並與 raw 逐位元組比對,
一致或 benign reorder (同物品不同順序) → 寫 raw, 真不一致 → 寫 eager (安全)。

---

## 為什麼不會掉資料

- 沒碰的箱子 → 寫回載入時讀到的同一份 bytes (逐位元組相同)
- 被碰的箱子 → ensure 物化後跟 vanilla 一模一樣地存回
- Shadow mode → 輸出在數學上不可能跟 vanilla 不同 (mismatch 時自動寫 eager)
- 只動箱子的 Items,不碰地形/方塊/實體/光照/其他 BE

---

## 旗標完整說明

- `-Dlazycontainer.shadow=true` 為**上線前必開的驗證模式**。跑數日確認 shadowMismatch=0 後才關掉換效能。
- `-Dlazycontainer.version=1.21.11` 只在自動偵測失敗時需要。
