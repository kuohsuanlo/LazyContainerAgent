# LazyContainerAgent

**繁體中文** ｜ [English](README.en.md)

> **箱子物品「延遲反序列化 + 沒碰過就原樣寫回」的 Java agent。**
> 把 chunk 載入時「立刻把每個箱子的物品從 NBT 解包」與卸載時「重新打包」這兩筆白工砍掉。

⚠️ **這不是外掛(plugin),是 Java agent** —— 用 `-javaagent:` 掛在 JVM 上,**不要丟 `plugins/`**(丟了沒用)。

---

## 快速上手

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
偵測失敗可用 `-Dlazycontainer.version=1.21.11` 手動指定。詳細狀態定義見 [`docs/PROGRESS.md`](PROGRESS.md)。

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
