# 自己測試 LazyContainerAgent

測試服在 `.lctest`(最小、無外掛、flat 世界、離線模式、port 25801,
用 symlink(符號連結,類似捷徑)共用重資產、不另外佔空間)。先確保 jar 是最新的:
```bash
cd LazyContainerAgent && bash build.sh
cp target/LazyContainerAgent.jar .lctest/
```

## 1) 一鍵自動測(資料完整性,~90s)
```bash
cd .lctest && bash run-test.sh
```
放一個帶 3 物品(含 component,也就是 1.20.5 之後物品資料的新格式,附魔、命名這些都算)的箱子 → 存 → 重啟(這時走磁碟 lazy 載入,也就是延遲載入:先只把箱子的原始資料讀進記憶體、暫不拆解)→ 未碰即存(raw 回寫:把當初讀進來的原始位元組原封不動寫回去,不重新打包)→ 再重啟讀回比對。
最後直接印 **✅ PASS / ❌ FAIL**,並列各 boot(每次啟動)的計數。`stash>0`=lazy 載入有生效;`rawSave>0`=raw 回寫有生效;
**`shadowMismatch` 必須是 0**。

## 2) 自己用 MC client 手動玩測(最有說服力)
```bash
cd .lctest && bash play.sh
```
前景啟動(此終端就是 console,打 `stop` 結束),預設開 **shadow + verbose**(shadow=影子模式:同時把原版該寫的那份資料也算出來對照,一發現不一致就自動改用原版那份,確保不會弄壞存檔;verbose=詳細記錄:每隔一段時間把計數印出來)。
用你自己的 Minecraft client 連 `<這台機器IP>:25801`(離線模式,任何名字都能進)。建議清單:
- 放箱子/木桶/界伏盒,塞各種物品(附魔裝、命名、藥水、含 NBT 的東西;NBT 是 Minecraft 存物品/方塊資料的二進位格式)、**雙箱**。
- `stop` 重啟,回去開箱 → 東西在不在、數量對不對。
- 漏斗對著箱子抽/灌;比較器讀箱子滿度;破壞**裝滿的界伏盒** → 掉落物含內容。
- 全程看每 10s 的 `[LazyContainer] ... shadowMismatch=0`。**只要 shadowMismatch 一直是 0,就代表磁碟輸出跟 vanilla(原版未經改動的 Minecraft)逐位元組相同。**

## 3) 拿你「真實世界副本」驗 shadow(零風險、最接近正式)
```bash
# 複製某節點的世界(只複製副本,不碰正式檔)
cp -r /path/to/realshard/world /tmp/realworld-copy
cd .lctest && bash play.sh /tmp/realworld-copy
```
連進去、到處飛載入有箱子的區域、`save-all flush`、`stop`。看 `shadowMismatch`:
- **=0** → 在你真實資料上,raw 回寫與 vanilla 完全一致。這是關 shadow 上線的前提證據。
- **>0** → 有分歧,log 會印是哪個座標、什麼型別。把該座標貼給我查(shadow 模式下它已自動寫 vanilla 那份,不會壞資料)。

## 4) 計數怎麼讀
| 計數 | 意義 |
|---|---|
| `stash` | 載入時擷取 raw、跳過 decode(解碼:把原始資料拆解成遊戲內的物品物件)的容器數(lazy 載入生效) |
| `ensure` | 首次被存取而真正 decode 的次數 |
| `rawSave` | 卸載時逐位元組回寫 raw、跳過 encode(編碼:把物品物件重新打包回原始資料)的次數(省 encode 生效) |
| `eagerLoad` | 退回 eager 載入(急切載入:一載入就立刻整包拆解,也就是原版做法)的次數(input 不是 TagValueInput 時;TagValueInput 是原版讀取方塊資料時用的輸入型別,不是這個型別就退回原版做法;正常應為 0) |
| `shadowMismatch` | **必須 0**;>0 表示 raw 與 vanilla 輸出有差(已自動改寫 vanilla 那份) |

## 5) 在真實節點自己掛(canary:金絲雀式試跑,先在一個節點小範圍上線觀察,沒問題再推廣;要開 shadow)
在該節點啟動指令的 `java` 後、`-jar` 前加:
```
-javaagent:/abs/path/LazyContainerAgent.jar -Dlazycontainer.shadow=true -Dlazycontainer.verbose=true
```
開機 log 應出現 `spliced 2 fields + 6 methods`(splice=把 agent 的欄位/方法接進原版類別裡)+ 三個 `transformed leaf`(被改寫的目標類別)。**先確認備份到位。**
跑數天看 `shadowMismatch=0` + 無玩家回報少東西 → 才考慮關 shadow 拿效能。
**回滾**:拔掉那段旗標重啟即回 100% vanilla,不需任何資料遷移(磁碟格式從未改變)。

> ⚠️ 版本綁 1.21.11/Java21。版本不符會在開機/第一次載箱子時**大聲報錯**(VerifyError/NoSuchMethod),不會靜默毀資料,但別硬上。
