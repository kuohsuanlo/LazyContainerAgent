# Benchmark — Spark Profiles(靜態存檔)

為避免 `spark.lucko.me` 連結過期,4 份原始 profile 已存於本目錄(`*.sparkprofile` = 原始 protobuf〔Google 的二進位序列化格式〕;可丟回 <https://spark.lucko.me/> 重新檢視)。

**測試環境**:`.lctest` 最小測試服(Paper 1.21.11),同一塊「放滿地圖畫/唱片的密集容器」終界 chunk,**無玩家**、`forceload` churn(反覆載入→卸載的翻攪)三輪;`agent` 為 shadow off(關閉 shadow 模式,量到的是真實效能數字)。

| Run | 模式 | spark id | 容器解碼佔主執行緒 | profile 節點數 | 最重路徑深度 |
|---|---|---|---|---|---|
| 1 | vanilla | [WOVkupfiJx](run1-vanilla-WOVkupfiJx.sparkprofile) | **62.17%** | 3378 | 200 層 |
| 1 | **agent** | [wGDbUTbZKN](run1-agent-wGDbUTbZKN.sparkprofile) | **0.00%** | 482 | 9 層 |
| 2 | vanilla | [AjXLAdXzTd](run2-vanilla-AjXLAdXzTd.sparkprofile) | **65.65%** | 4305 | 200 層 |
| 2 | **agent** | [caXFofKSVQ](run2-agent-caXFofKSVQ.sparkprofile) | **0.00%** | 763 | 36 層 |

兩輪重現:vanilla 主執行緒 **62~66%** 在解容器物品 → agent **0%**。整座解碼塔(3378 / 4305 節點、200 層深)在 agent profile **直接消失**(482 / 763 節點、9 / 36 層)。

## 那 200 層是什麼(波動拳呼叫鏈)

完整鏈見 [`run1-vanilla-callchain-206lines.txt`](run1-vanilla-callchain-206lines.txt)。

- 從「載入這塊 chunk」到最底層那一行,約 **200~209 層**呼叫。
- 深因 = **真實巢狀資料**(箱子 → 界伏盒 → 地圖畫/唱片 → lore → 不同顏色的子文字;容器套容器、文字套子文字,Mojang 用 `RecursiveCodec`〔會自己一層層往下鑽的遞迴式 codec〕遞迴解;codec 是 Minecraft 把資料在「Java 物件 ↔ 存檔格式」之間互轉的編解碼器)**×** **Mojang serialization combinator 框架**(把很多小 codec 像積木一樣拼成大 codec 的機制——每解一個欄位,就往呼叫堆疊上多疊 ~15-20 個 `MapCodec/RecordCodecBuilder/DataResult.flatMap/…` frame,frame 就是呼叫堆疊上的一層)。
- 最底層葉子:`TextColor.parseColor`(解析 lore 顏色)/ `String.equals`(在 NBT〔Minecraft 存物品/方塊資料的二進位格式〕的 compound〔一包鍵值對,像 Java 的 Map〕裡比對欄位名)——「為了查一個顏色 / 比較兩個字串,疊了 200 層」。

> ⚠️ 數字情境:62~66% 是「把容器解碼**單獨隔離**」的壓力測(場景只有容器、沒別的負載)。真實混合負載下佔比為載入 **~24%** + 卸載 **~11%**,負載最重的節點可達 **~45%**。agent 省的是「解包/打包」CPU,不省 I/O / GC。

## 測試素材(結構與數量)

vanilla 之所以衝到 62~66%,是因為這塊 fixture 是極端密集的地圖畫倉庫——**兩個相鄰 chunk(fixture 內座標 0,0、1,0)**就塞了:

| 內容 | 數量(約) |
|---|---|
| 放置的箱子(方塊實體) | 數千個 |
| 界伏盒(箱內物品) | ~20,995 |
| `filled_map`(地圖畫) | **~755,820** |

巢狀結構(每張地圖畫還帶 lore + 多段顏色文字,就是那 200 層的來源):

```
chest(方塊實體)
└ shulker_box(物品)
   └ filled_map ×N(每張帶 custom_name + lore + 多段顏色文字)
```

> 地圖二進位(`region/*.mcc`〔Minecraft 為塞不進 region 檔的超大 chunk 另存的外部檔〕,解壓後 ~450 MB)與完整箱子 dump(整包匯出)**含玩家 UUID/作者等個資,不放進公開 repo**。
> 要完整重現,請自備同等密度的地圖倉 region(**1.21.11 格式**),drop 進世界 `region/` 後對該區 `forceload` 即可。
