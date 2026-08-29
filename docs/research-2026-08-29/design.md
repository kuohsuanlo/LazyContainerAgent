# design

**結論**:值得做，但先做便宜的那兩刀：逐格解碼的實測破口是 17.5 格（單格 2.7 µs vs 整箱 47.5 µs）、漏斗常態只碰 1–3 格 ⟹ 佔 71–99% 解碼量的漏斗路徑會降到今天的 6–11%，且 offsets 記錄實測 byte-identical、編碼零成本；但同一輪追查發現「漏斗每次推入都問 isEmpty()（HopperBlockEntity.java:592）」與「clearContent 先解再丟（BaseContainerBlockEntity.java:137-139）」這兩條**不需要逐格、不需要改存檔規則**就能砍掉，風險低一個數量級，應該先出，順便收一輪 fullQ 分佈再決定逐格的投資規模。

## 硬事實
- raw 的框架可以直接 seek：`NbtIo.writeAnyTag` 寫的是 [tagId=0x09][elementType][int32 count][連續 entry payload]（NbtIo.java:155-160 + ListTag.java:182-190），每個 entry payload 是自帶 0x00 結尾的 CompoundTag.write（CompoundTag.java:172-179），因此可獨立 seek + `CompoundTag.TYPE.load` 解出。實測 4/4 entry 亂序 seek 全對、剩餘位元組 0（FramingProbe，paper-26.2-mojmap.jar）。
- 「一邊寫一邊記 offset」的手寫 writer 與 `NbtIo.writeAnyTag` 的輸出 **byteIdentical=true**（275 bytes，offsets=[6,49,94,233,275]），所以記錄 offsets 不會破壞「逐位元組不變」鐵則。
- 編碼端記 offsets 的成本 = 0：writeAnyTag 9.64 µs vs offset-recording 9.41 µs（-2.3%，在雜訊內；因為省掉 identifyRawElementType 與每格 wrapIfNeeded）。99.6% 從沒被碰過的容器在 CPU 上不用付帳，只多付約 180 B 記憶體（int[n+1] + byte[size]）。
- 逐格 vs 整箱的實測破口是 **17.5 格**：27 格帶 custom_name+lore 的箱子，整箱解碼 47.5 µs、單格 seek+解碼 2.7 µs（比值 0.057）。漏斗抽取常態只碰 1–3 格 ⟹ 成本降到今天的 6–11%。
- 兩種 raw 必須拒收逐格：清單型別不齊（`ListTag.identifyRawElementType` 一律回 TAG_COMPOUND，ListTag.java:193-206）與 wrapper 形狀 `{"":x}` 的 entry（`wrapIfNeeded` 會再包一層，ListTag.java:169-175）。兩者實測 naiveMatchesRef=false。閘門用 O(n) 結構檢查（每個元素 instanceof CompoundTag 且非 size()==1&&contains("")），不必多做一次序列化。
- slot 對映一定要走真 codec `ExtraCodecs.UNSIGNED_BYTE`（ExtraCodecs.java:143-147），不要沿用摘要那套手寫 `box().byteValue()&0xFF`。實測兩者 8/8 一致（含 300→44、-1→255、-0.5d→0、-1.5f→255、130s→130），但摘要算錯只是少省一次（不對稱安全），逐格算錯會誤刪 entry ⟹ 靜默掉物，安全等級不同。
- `ContainerHelper.loadAllItems` 是「逐 entry 獨立、依清單順序、`items.set` 後寫者勝」的摺疊（ContainerHelper.java:41-45），單一 entry 失敗只會被 `TypedListWrapper` 報 problem 後 filter 掉、不影響其他 entry（TagValueInput.java 的 stream/iterator 實作）。這是逐格解碼在語意上等價於整份解碼的基礎定理。
- 混合存檔政策 S4（未解格 entry 原始順序原始位元組保留、已解格的 entry 刪掉、權威 entry 用真 `ContainerHelper.saveAllItems` 產生並接在最後）實測通過：raw 含重複 Slot／缺 Slot／越界 slot 200／非 compound IntTag／未知物品 id 共 6 筆，解出 slot 3 再抽走 1 顆鑽石後，hybrid reload 與 live 狀態 **MATCH=true**（SavePolicyProbe）。
- `BaseContainerBlockEntity` 的六個 accessor 全部經 `this.getItems()`（BaseContainerBlockEntity.java:95,106,111,121,126,138），而 leaf 的 getItems() 掛著 GUARD_ENSURE ⟹ 不做「不掛 guard 的分身 `lazycontainer$rawItems()`」的話，任何逐格路徑都會被自己的 guard 打回整箱解碼。三個 leaf 都是 `protected NonNullList<ItemStack> getItems()`（ChestBlockEntity.java:194、BarrelBlockEntity.java:125、ShulkerBoxBlockEntity.java:265），且都沒有覆寫那六個 accessor（已 grep 確認）。
- 漏斗「推入」路徑每次都呼叫 `container.isEmpty()`（HopperBlockEntity.java:592）⟹ 今天必定整箱解碼；而 `wasEmpty` 只在 `container instanceof HopperBlockEntity` 時才被用到（HopperBlockEntity.java:615），對箱子而言是純浪費。這條用 `emptyState()` 摘要就能答，**不需要逐格解碼**。
- `clearContent()`（BaseContainerBlockEntity.java:137-139）今天會整箱解碼再 `NonNullList.clear()` 把每格設回 EMPTY（NonNullList.java:67-75）——解出來的東西沒有任何人看過。加一個 prologue 短路即可零解碼，**不需要逐格解碼**。
- 逐格的回歸風險來源是無短路的全格掃描：`AbstractContainerMenu.getRedstoneSignalFromContainer`（AbstractContainerMenu.java:889-905，比較器）與 `LootTable.getAvailableSlots` 都會掃完 27 格。純逐格 = 27×2.7 = 73 µs vs 今天 47.5 µs（1.55× 差）。對策是 `promote` 門檻（bitCount(decoded) ≥ 6 就轉 ensureAll），最壞 63.7 µs（1.34×）。
- 「解過但沒改，raw 仍有效」這個誘人的簡化是錯的：`HopperBlockEntity.java:610` 的 `current.grow(count)` 直接改 `getItem(slot)` 回傳的物件而完全不呼叫 setItem；`ContainerHelper.java:14` 的 removeItem 是 `.split(count)` 就地改；`hopperPull` 在 HopperBlockEntity.java:285 直接 `movedItem.setCount(...)` 改活體堆疊。⟹ decoded 必須蘊含 dirty。
- 摘要在部分已解後**完全不需要維護**：`sumHas/sumClean/sumAtMax` 三張 bit plane 只描述 raw、載入後凍結，查詢時按 `decoded` 決定看 list 還是看 plane。`sumClean`/`sumAtMax` 就是今天 `computeSummary` 已經算出來、卻被摺成一個 tri 就丟掉的 cleanBits/atMaxBits——留下來即可，不用寫新演算法。
- 跨執行緒論證可完整沿用今天的 A1 修法（README.md:126 / RELEASE-NOTE-26.2-2.md:11）：把 `pending` 換成 `decoded`（volatile int），寫序固定為 `list.set(i,…)` → `decoded |= bit`(volatile) → `decoded==all` → `pending=false`(volatile)。無鎖讀者讀到 bit=1 必看得到完整那一格；讀到 0 就進 monitor 等待。
- `decoded` 必須從 0 起算，不能為了省事初始化成 `~sumHas`：漏斗推物進「原本沒有 entry 的空格」時 bit 本來就是 1，會讓存檔誤判為未動過而走 verbatim ⟹ 推進去的物品消失。
- 存檔鐵則要改寫成兩句：`decoded == 0` ⟹ 逐位元組原封回寫（99.6% 的容器走這條，完全不變）；`decoded != 0` ⟹ 混合體（未動的 entry 保持原始位元組、已動的格用 vanilla 重編碼）。

## 風險
- 【最危險】ensureAll 在部分已解之後，若照舊 `ContainerHelper.loadAllItems(vi, list)`，會用舊 raw 蓋掉漏斗剛抽空的格 ⟹ 物品原地復活 = 複製。與 LazyContainerTransformer.java:317-324 記載的「無 guard 時 26/26 格復活」是同一種病。修法：解進 temp、只填 decoded bit 為 0 的格；必須寫對抗測試。
- 混合存檔第一半的 `continue` 是唯一會刪東西的動作。slot 判讀若把「其實屬於未解格」的 entry 判成「屬於已解格」，那格永久空白。緩解：slot 走真 codec、present-but-unparseable 一律停用逐格、權威 entry 放最後讓判讀錯誤被覆蓋掉。
- clear()/setItems/loadAdditional 路徑若忘了把 `entryOff`、`slotEntryIdx` 一起設 null（INV-7），新 raw 配舊 offsets 會從錯誤的位元組位置解出一個「看起來完全合法」的 entry ⟹ 隨機物品、無例外、無 log。/data merge 與 `Chest.getState().update()` 就會走這條。
- 發佈順序寫反（`decoded |= bit` 排在 `list.set` 之前）＝ A1 事故的逐格版：別的執行緒讀到 bit=1、拿到還沒填的那格。
- 「解過但沒改可以繼續原封回寫」是錯的假設，而且錯了會靜默掉數量（HopperBlockEntity.java:610 的 grow / ContainerHelper.java:14 的 split / HopperBlockEntity.java:285 的 setCount 都不經 setItem）。
- `decoded` 初值若設成 `~sumHas`，漏斗推進原本沒有 entry 的空格時會讓存檔誤判為未動過 ⟹ 推進去的物品消失。
- §1.3 閘門若沒擋住 wrapper / heterogeneous 的 raw，offsets 與位元組會錯位、解出隔壁 entry（實測 naiveMatchesRef=false）。建議 shadow 模式加 `Arrays.equals(offsetWriterBytes, writeAnyTag(...))` 斷言。
- 無短路全格掃描（比較器 AbstractContainerMenu.java:889-905、LootTable.getAvailableSlots）在純逐格下是 1.55× 的效能回歸，必須有 promote 門檻。
- `lazycontainer$rawItems()` 的 call-site 改寫會影響 BaseContainerBlockEntity 的**所有**子類（熔爐、發射器、釀造台…）。spliced 預設實作 `return this.getItems();` 讓它們語意不變，但多一個 invokevirtual（JIT 應可 inline）；這是 transformer 第一次「修改既有方法體」而不只是附加成員，需要新的 ASM 能力與更嚴的 fork 形狀比對。
- 混合體會保留原 raw 的 junk entry（重複 / 越界 / 非 compound），輸出比 vanilla 多幾筆。只發生在本來就有 junk 的容器，且下一輪 load→save 就正規化、不會累積；但如果服主有外部工具直接讀 chunk NBT 做稽核，形狀會跟 vanilla 存檔不同。
- 本次量到的 47.5 µs / 2.7 µs 是 27 格小容器的數字。s45 那種 16.19 s 凍結對應的是巨大巢狀 shulker 資料，比值應該持平或更好（單格成本只跟該 entry 大小有關），但沒有實機樣本可證。
- 目前 logs/ 底下抓不到生產的 `fullQ=證明滿/證明不滿/佔滿但證明不了/整份放棄` 分佈（全是本機測試 log）。逐格的收益上限正好等於「佔滿但證明不了」那一格的次數，建議先出階段 0 收一輪這個數字再決定階段 1 的投資規模。

## 報告
# 逐格延遲解碼（per-slot lazy decode）設計規格

> 全部結論都指得到 `4-folia` 反編譯源碼行號，或指得到我這次在真實 `paper-26.2-mojmap.jar` 上跑出來的四支探針數字。
> 探針原始碼在 `/tmp/claude-1000/-home-logocat-Server-claude-sandbox/a0b371fe-72c5-491e-ab05-2164cd8dad7c/scratchpad/framing/`
> （`FramingProbe.java` / `SavePolicyProbe.java` / `BailProbe.java` / `Bench.java` / `Bench2.java`）。

---

## 0. 一句話結論

**值得做，但「逐格解碼」不是最大的那塊肉。**
實測破口是 **17.5 格**（單格解碼 2.7 µs vs 整箱 27 格 47.5 µs），而漏斗抽取只碰 **1–3 格** ——
所以在佔了 71–99% 解碼量的漏斗路徑上，成本會掉到 **今天的 6–11%**。
但同一輪追查也挖出兩個**不需要逐格解碼、風險低一個數量級**的漏洞，建議先修：

| 洞 | 源碼位置 | 今天發生什麼 |
|---|---|---|
| 漏斗每次「推入」都問 `container.isEmpty()` | `HopperBlockEntity.java:592` → `BaseContainerBlockEntity.java:94-101` | **整箱解碼**，而且 `wasEmpty` 只在 `container` 是漏斗時才被用到（`HopperBlockEntity.java:615`），對箱子而言是純浪費 |
| `clearContent()` 先解碼再全部丟掉 | `BaseContainerBlockEntity.java:137-139` | 整箱解碼 → `NonNullList.clear()` 把每格設回 EMPTY（`NonNullList.java:67-75`）。解出來的東西沒有任何人看過 |

這兩個補起來後再做逐格，風險曲線比較好看。

---

## 1. 事實層：ListTag 的二進位框架（可不可以 seek）

### 1.1 raw 的實際位元組排版

今天的 `lazycontainer$encodeRaw` 走 `NbtIo.writeAnyTag`（`NbtIo.java:155-160`）：

```
[0]      0x09                       ← tag id (TAG_List)              NbtIo.java:156
[1]      elementType                ← ListTag.write                  ListTag.java:184
[2..5]   int32 count (big-endian)   ←                                ListTag.java:185
[6..]    count × entry payload      ← wrapIfNeeded(t,e).write(out)   ListTag.java:187-189
```

每個 entry payload 是 `CompoundTag.write`（`CompoundTag.java:172-179`）：
`{ byte type; UTF key; tagData }` 反覆，最後一個 `0x00` 結束。
**它自帶結束標記 ⟹ 自我界定 ⟹ 可以單獨 seek 出來解。**

### 1.2 實測：offset 記錄不會改變任何一個位元組

`FramingProbe` 用「一邊寫一邊記 `DataOutputStream.size()`」的手寫 writer，對照 `NbtIo.writeAnyTag`：

```
byteIdentical=true  len=275
offsets=[6, 49, 94, 233, 275]
  entry[2] start=94  len=139 equal=true leftoverBytes=0 slot=7
  entry[0] start=6   len=43  equal=true leftoverBytes=0 slot=0
  entry[3] start=233 len=42  equal=true leftoverBytes=0 slot=26
  entry[1] start=49  len=45  equal=true leftoverBytes=0 slot=3
seekDecodeAllOk=true
```

亂序 seek、單獨 `CompoundTag.TYPE.load(...)`（`CompoundTag.java:40`，public）四筆全對、剩餘位元組為 0。
**（1）的答案是「可以」，而且不動到「逐位元組不變」鐵則。**

### 1.3 但有兩種 raw 一定要拒收（實測）

`BailProbe`：

```
A heterogeneous (compound + IntTag): elemType=10  naiveMatchesRef=false
B wrapper elem  {"" : "hi"}        : elemType=10  naiveMatchesRef=false
C empty list                       : refBytes=[9, 0, 0, 0, 0, 0]
D 全部是普通 compound               :             naiveMatchesRef=true
```

原因在 `ListTag.identifyRawElementType`（`ListTag.java:193-206`）：清單型別不齊時**一律回報 TAG_COMPOUND**，
然後 `wrapIfNeeded`（`ListTag.java:169-175`）把非 compound 的元素包成 `{"": tag}`。
`isWrapper` 形狀（`size()==1 && contains("")`）的 compound 也會被再包一層。
兩種情況下「元素的記憶體形狀」≠「元素的磁碟形狀」，手寫 writer 一定對不上。

**閘門（載入時 O(n) 檢查，不用多做一次序列化）**：

```
啟用逐格 ⟺ itemsTag instanceof ListTag
         ∧ 每個元素 instanceof CompoundTag
         ∧ 沒有元素滿足 (size()==1 && contains(""))     ← CompoundTag.java:220 / :276 皆 public
         ∧ n ≤ 127                                      ← slotEntryIdx 用 byte[]，見 §3.1
         ∧ 每個 entry 的 Slot 都能用真 codec 解出（見 §2.2）
不滿足 ⟹ offsets = null ⟹ 行為與今天 100% 相同（只能整箱 ensure）
```

### 1.4 編碼端的額外成本 = 0

`Bench2`（27 格、每格帶 custom_name + lore）：

```
writeAnyTag        = 9.64 us
offset-recording   = 9.41 us  (+-2.3%)
```

在雜訊內，甚至略快（我們已經在閘門確定了 elementType，省掉 `identifyRawElementType` 與每格的 `wrapIfNeeded`）。
**99.6% 從沒被碰過的容器，在 CPU 上完全不用付這筆帳**；只多付記憶體（§3.1）。

---

## 2. 規格（1）：編碼與 slot 對映

### 2.1 encodeRaw 改寫（template）

```java
// 回傳 raw bytes；副作用：填好 this.lazycontainer$entryOff（長度 n+1）
public byte[] lazycontainer$encodeRawWithOffsets(Tag tag) throws IOException {
    if (tag == null) return null;
    if (!(tag instanceof ListTag)) return lazycontainer$encodeRaw(tag);   // 舊路徑，offsets 保持 null
    ListTag l = (ListTag) tag;
    int n = l.size();
    if (n == 0 || n > 127) return lazycontainer$encodeRaw(tag);
    for (int i = 0; i < n; i++) {                                         // 閘門，見 §1.3
        Tag e = l.get(i);
        if (!(e instanceof CompoundTag)) return lazycontainer$encodeRaw(tag);
        CompoundTag c = (CompoundTag) e;
        if (c.size() == 1 && c.contains("")) return lazycontainer$encodeRaw(tag);
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
    DataOutputStream dos = new DataOutputStream(bos);
    int[] off = new int[n + 1];
    dos.writeByte(9);            // NbtIo.java:156
    dos.writeByte(10);           // ListTag.java:184，閘門已保證 elementType==TAG_COMPOUND
    dos.writeInt(n);             // ListTag.java:185
    for (int i = 0; i < n; i++) { off[i] = dos.size(); l.get(i).write(dos); }
    dos.flush();
    off[n] = dos.size();
    this.lazycontainer$entryOff = off;
    return bos.toByteArray();
}
```

### 2.2 slot 對映：**不要自己算**

今天的摘要用手寫的 `((NumericTag) slotTag).box().byteValue() & 0xFF`（template 約 630–640 行）。
我用 `FramingProbe` 對 8 種數值型別做過差分：

```
300→44/44   -1→255/255   5b→5/5   -0.5d→0/0   3.9d→3/3   259L→3/3   -1.5f→255/255   130s→130/130
```

**手寫版與真 codec 目前 8/8 一致**（`ExtraCodecs.UNSIGNED_BYTE = Codec.BYTE.flatComapMap(UnsignedBytes::toInt, …)`，
`ExtraCodecs.java:143-147`；`ItemStackWithSlot.CODEC` 用 `optionalAlwaysPresentFieldOf(UNSIGNED_BYTE,"Slot",0)`，
`ItemStackWithSlot.java:11`）。

但摘要算錯只會「少省一次」（不對稱安全），**逐格算錯會把物品放到錯的格 / 把 entry 誤刪 ⟹ 靜默掉物**。
安全等級不一樣，所以逐格路徑一律走真 codec：

```java
// -1 = 無法決定（整份停用逐格）
private static int lazycontainer$slotOf(CompoundTag entry) {
    Tag s = entry.get("Slot");
    if (s == null) return 0;                                     // optionalAlwaysPresentFieldOf 預設 0
    java.util.Optional<Integer> r =
        net.minecraft.util.ExtraCodecs.UNSIGNED_BYTE.parse(net.minecraft.nbt.NbtOps.INSTANCE, s).result();
    if (!r.isPresent()) return -1;                               // 欄位在但解不出 ⟹ 停用逐格（沿用今天的 A2 棄答）
    return r.get().intValue();
}
```

成本：每個 entry 一次極輕的 codec parse（沒有 ItemStack、沒有 DataComponentPatch），
比今天摘要迴圈裡的 `Identifier.tryParse` + registry lookup 便宜。**template 禁 lambda 的限制沒被違反**
（`DataResult.result()` 回 `Optional`，用 `isPresent()/get()`，不需要 lambda）。

### 2.3 載入時一次 O(n) 建好三張表

```
entryOff[n+1]      byte 範圍（§2.1）
slotEntryIdx[size] 每格「最後一個指到它的 entry index」；-1 = 沒有 entry     ← 後寫者勝，對齊 ContainerHelper.java:43 的 items.set
sumHas / sumClean / sumAtMax   三張 bit plane（§5）
```

`slotEntryIdx` 就是「O(n) 不是 O(n²)」的關鍵：建表一次，之後每次 `ensureSlot(s)` 是 **O(1) 查表 + O(該 entry 位元組數) 解碼**。

---

## 3. 規格（2）：狀態、欄位、轉移、不變式

### 3.1 欄位清單（`lazycontainer$` 前綴省略）

| 欄位 | 型別 | 說明 |
|---|---|---|
| `pending` | `volatile boolean` | 沿用 |
| `ensuring` | `Thread` | 沿用（重入偵測） |
| `raw` | `byte[]` | 沿用 |
| **`entryOff`** | `int[]` (n+1) | 新；`null` = 逐格停用 |
| **`slotEntryIdx`** | `byte[]` (size) | 新；每格的 winning entry index，-1 = 無（`n ≤ 127` 才啟用） |
| **`decoded`** | `volatile int` | 新；bit i = 「slot i 的真相在 list 裡」 |
| `sumState` | `int` | 沿用（0 未建 / 1 有效 / 2 放棄） |
| `sumHas` | `int` | 沿用（原 `sumBits`） |
| **`sumClean`** | `int` | 新；bit i = 「raw 側第 i 格可證明解碼成功」 |
| **`sumAtMax`** | `int` | 新；bit i = 「raw 側第 i 格 count ≥ max」 |
| ~~`sumFullTri`~~ | — | **刪掉**，改成每次查詢由三張 plane 現算（§5） |

記憶體：`int[28]` + `byte[27]` ≈ 112 + 27 + 兩個物件頭 ≈ **約 180 B / pending 容器**。
raw 本身通常幾百到幾千 B，所以是 +5%~+20%。
（如果服主覺得太貴，`entryOff` 可以壓成 `short[]`（raw < 32 KB 時）或延後建表，但延後就必須重掃 NBT，要多寫一個 skipper——不建議。）

**不需要**「捕捉 list 參照」的欄位：逐格路徑用 §6.1 的 `lazycontainer$rawItems()` 拿清單，天生跟著 leaf 欄位走，不會變 stale。

### 3.2 狀態

* **S_EAGER**：`pending=false, raw=null`。清單全權威。（非目標容器 / eager 退路 / 畢業後）
* **S_LAZY(mask)**：`pending=true, raw≠null, decoded=mask`。
  slot i 的真相：`decoded` bit i 為 1 → `list.get(i)`；為 0 → `raw` 的 entry `slotEntryIdx[i]`（-1 表示 EMPTY）。

`decoded` **一律從 0 起算**，即使那一格 raw 裡根本沒有 entry。
（把「沒 entry 的格」預設成 decoded 是個陷阱，見 §8 第 6 條。）

### 3.3 轉移表

| 轉移 | 觸發 | 持鎖 | 動作 | 之後 |
|---|---|---|---|---|
| **T-load** | leaf `loadAdditional`/`loadFromTag` 內被 redirect 的 `loadAllItems` | ✔ this | `sumState=0` → encode+offsets → 建 `slotEntryIdx` + 三 plane → `decoded=0` → **最後** `pending=true`(volatile) | S_LAZY(0) 或 S_EAGER(退路) |
| **T-ensureSlot(s)** | BCE `getItem/setItem/removeItem/removeItemNoUpdate` 的 prologue | ✔ this（外層先做無鎖 `decoded` 快查） | 解 entry `slotEntryIdx[s]` → `list.set(s,…)` → `decoded \|= bit`(volatile) | S_LAZY(mask∪bit) 或畢業 |
| **T-ensureAll** | leaf `getItems()/getContents()` guard、`promote` 門檻、任何退路 | ✔ this | 解整份到 **temp**，只填 `decoded` 為 0 的格 → `decoded=all` → `pending=false` | S_EAGER |
| **T-setItem/removeItem** | BCE 對應方法 | 不持鎖（prologue 先 ensureSlot） | vanilla 原樣邏輯，跑在 `rawItems()` 上 | mask 不變（該格已 decoded） |
| **T-setItems** | leaf `setItems` GUARD_CLEAR | ✔ this | `pending=false; raw=null; entryOff=null; slotEntryIdx=null; decoded=0; sumState=0` | S_EAGER |
| **T-clearContent** | BCE `clearContent` prologue（新增） | ✔ this | 同上（raw 整份作廢），再讓原方法 `getItems().clear()` 跑 | S_EAGER，**零解碼** |
| **T-save** | leaf `saveAdditional` 內被 redirect 的 `saveAllItems` | ✔ this | `decoded==0` ⟹ 原封回寫；否則走混合體（§4） | 狀態不變 |
| **T-reload** | `/data merge`、`getState().update()`、`loadWithComponents` | ✔ this | GUARD_CLEAR 先整份作廢（`LazyContainerTransformer.java:325-328`），再走 T-load | S_LAZY(0) |

### 3.4 不變式清單

* **INV-1（唯一真相）** 每個 slot i 的真相唯一：`decoded` bit i=1 ⟹ `list.get(i)`；=0 ⟹ raw 的 `slotEntryIdx[i]`（-1 ⟹ EMPTY）。
* **INV-2（未解格恆 EMPTY）** `decoded` bit i=0 ⟹ `list.get(i)` 從載入起沒被寫過，恆為 `ItemStack.EMPTY`。
* **INV-3（覆蓋）** 存檔輸出必須同時涵蓋兩半：raw 側未被取代的 entry ＋ 已解格的權威 entry。少一半就是掉物。
* **INV-4（發佈順序）** `list.set(i,…)`（普通寫）→ `decoded |= bit`（volatile 寫）；`decoded=all` → `pending=false`（volatile 寫）。順序不可對調。
* **INV-5（plane 只描述 raw）** `sumHas/sumClean/sumAtMax` **載入後永不修改**；已解格一律直接讀 list。
  （這條讓所有寫入路徑不必維護摘要 ⟹ 消滅一整族「忘了失效」的 bug。）
* **INV-6（decoded ⟹ dirty）** 只要一格被 decode 過，就必須當成「可能已被就地改動」。理由見 §8 第 5 條。
* **INV-7（offsets 與 raw 同生共死）** 任何把 `raw` 換掉或清掉的地方，同一個 monitor 區段內必須把 `entryOff`、`slotEntryIdx` 一起設 null。
* **INV-8（畢業）** `decoded == allMask` ⟹ 立刻 `raw=null; entryOff=null; slotEntryIdx=null; pending=false`，回到今天的穩態。

---

## 4. 規格（3）：存檔的混合體

### 4.1 為什麼一定要換規則

今天的鐵則是「pending ⟹ 逐位元組原封回寫」。逐格之後，**部分已解的容器就不是「沒被碰過」了**，
鐵則必須改寫成兩句：

> `decoded == 0` ⟹ 逐位元組原封回寫（**完全不變**，99.6% 的容器走這條）
> `decoded != 0` ⟹ 輸出混合體：**未動到的 entry 保持原始位元組、已動的格用 vanilla 重編碼**

### 4.2 政策（我選 S4：只刪已解格的 entry，權威在最後）

```
out = new ListTag()

# 第一半：原始順序、原始 Tag（= 原始位元組），只跳過「屬於已解格」的 entry
for j in 0 .. n-1:
    s = slotOf(orig.get(j))                       # §2.2 真 codec
    if 0 <= s < size and (decoded >> s & 1) == 1: continue
    out.add(orig.get(j))

# 第二半：已解格的權威 entry，用真 vanilla 編碼器產生，接在最後 ⟹ 後寫者勝
tmp = NonNullList.withSize(size, EMPTY)
for i where (decoded >> i & 1) == 1: tmp.set(i, rawItems().get(i))
ContainerHelper.saveAllItems(output, tmp, /*alsoWhenEmpty=*/true)   # 直接寫進真 output，registry context 由 vanilla 處理
auth = ((TagValueOutput) output).buildResult().get("Items")
out.addAll(0, <auth 之前的第一半>)   # 實作上：先讓 vanilla 寫 auth，再把第一半 insert 到 index 0

if out.isEmpty() and !allowEmpty: output.discard("Items")           # 對齊 ContainerHelper.java:35-37
else:                             buildResult().put("Items", out)
```

**為什麼順序是「原始在前、權威在後」**：`ContainerHelper.loadAllItems` 是 `items.set(slot, stack)` 的
逐項覆寫（`ContainerHelper.java:41-45`），後面的贏。權威放最後 ⟹ 就算我們的 slot 判讀出錯、
某個原始 entry 其實也指到同一格，權威值仍然勝出。**這是刻意設計的容錯方向。**

**為什麼「保留 junk」而不是輸出 vanilla 的乾淨形狀**：另一個可選政策（S2：升冪、每格一筆、丟掉重複與越界）
輸出形狀跟 vanilla 一模一樣、比較乾淨，但它必須依賴 slot 判讀來**刪除**未解格的 entry ——
判讀錯一次就是永久掉物。S4 只在「該格已解」時刪，錯的代價被權威 entry 蓋掉。**安全優先，選 S4。**

### 4.3 語意對齊（四種怪 entry）

`SavePolicyProbe` 餵一份故意做壞的 raw（6 筆）：重複 Slot、缺 Slot 欄位、越界 slot 200、非 compound 的 `IntTag(42)`、未知物品 id。

| 情況 | vanilla 語意 | 混合體怎麼做 |
|---|---|---|
| 重複 Slot（後寫者勝） | `ContainerHelper.java:43` 的 `set` 後蓋前 | 兩筆都在第一半保留（若該格未解）；若該格已解，兩筆都刪、權威接在最後 |
| 越界 slot（≥ size） | `isValidInContainer` 丟棄（`ItemStackWithSlot.java:17-19`） | 永遠不會等於任何已解格 ⟹ 原封留著 |
| 缺 `Slot` 欄位 | 預設 0（`ItemStackWithSlot.java:11`） | `slotOf` 回 0，跟一般 entry 同路 |
| 非 compound 元素 | `TypedListWrapper` 逐項失敗、報 problem、`filter(Objects::nonNull)` 跳過（`TagValueInput.java:~423-434`） | `slotOf` 回 -1 ⟹ 不可能被誤刪 ⟹ 原封留著。**同時它會讓 §1.3 閘門直接關掉逐格** |

實測結果（slot 3 被解出來後 hopper 抽走 1 顆鑽石）：

```
vanilla load     : 0=dirt x5  3=diamond x2
hybrid reload    : 0=dirt x5  3=diamond x1
expected (live)  : 0=dirt x5  3=diamond x1
MATCH=true
hybrid out size=5   (vanilla save size=2，混合體多留 3 筆 junk)
```

多留的 3 筆 junk 只在「本來就有 junk 的容器」出現，而且**下一輪 load→save 之後就被正規化**，不會累積。

---

## 5. 規格（4）：摘要在部分已解之後怎麼維護

**答案：完全不維護。** 三張 plane 只描述 raw，載入後凍結（INV-5）；查詢時按 `decoded` 決定看哪一邊。

```java
/** 1=證明全滿 0=證明不滿 -1=不知道 */
public int lazycontainer$fullState() {
    if (!this.lazycontainer$pending || this.lazycontainer$lootPending()) return -1;   // loot 邏輯完全沿用
    if (this.lazycontainer$sumState != 1) return -1;
    int size = this.getContainerSize();
    int dec = this.lazycontainer$decoded;                 // volatile 讀一次，之後用同一份快照
    NonNullList<ItemStack> l = this.lazycontainer$rawItems();
    boolean unknown = false;
    for (int i = 0; i < size; i++) {
        if ((dec >>> i & 1) != 0) {
            ItemStack s = l.get(i);
            if (s.getCount() < s.getMaxStackSize()) return 0;      // 精確，零解碼
        } else if ((this.lazycontainer$sumHas   >>> i & 1) == 0) {
            return 0;                                              // 該格證明為空 ⟹ 必不滿
        } else if ((this.lazycontainer$sumClean >>> i & 1) == 0) {
            unknown = true;                                        // 證不出來
        } else if ((this.lazycontainer$sumAtMax >>> i & 1) == 0) {
            return 0;                                              // 證明不滿
        }
    }
    return unknown ? -1 : 1;
}

/** 1=證明全空 0=證明非空 -1=不知道（給 BCE.isEmpty 用） */
public int lazycontainer$emptyState() { /* 同形狀：已解格讀 list、未解格看 sumHas/sumClean */ }

/** 沿用，只多一句已解格的精確分支 */
public boolean lazycontainer$slotEmpty(int slot) {
    if (!this.lazycontainer$pending || this.lazycontainer$lootPending()) return false;
    if ((this.lazycontainer$decoded >>> slot & 1) != 0) return this.lazycontainer$rawItems().get(slot).isEmpty();
    if (this.lazycontainer$sumState != 1) return false;
    if (slot < 0 || slot >= 32 || slot >= this.getContainerSize()) return false;
    return (this.lazycontainer$sumHas >>> slot & 1) == 0;
}
```

* 成本從 O(1)（今天的快取 tri）變成 ≤27 次整數運算。對照 vanilla 自己的 `isFullContainer`
  （`HopperBlockEntity.java:477-488`，27 次虛擬 `getItem` + `getMaxStackSize()`，後者還要查 data component），**我們這個仍然便宜得多**。
* 不對稱鐵律不變：答「滿 / 此格為空」需要完整證明鏈；答「不滿 / 非空 / 不知道」是安全方向。
* `sumClean` / `sumAtMax` 就是今天 `computeSummary` 迴圈裡已經算出來的 `cleanBits` / `atMaxBits`
  （template 尾段），只是今天被摺成一個 tri 就丟掉了。**改成留下來，不用寫任何新演算法。**

---

## 6. 規格（5）：跨執行緒

### 6.1 前置：`getItems()` 的分身

`BaseContainerBlockEntity` 的六個 accessor 全部經 `this.getItems()`
（`BaseContainerBlockEntity.java:95, 106, 111, 121, 126, 138`），而 leaf 的 `getItems()` 上掛著 GUARD_ENSURE。
所以只要不動它，**任何逐格路徑都會被自己的 guard 打回整箱解碼**。

作法（不改任何一行 vanilla 邏輯）：

1. splice 進 BCE 一個預設實作：`public NonNullList<ItemStack> lazycontainer$rawItems() { return this.getItems(); }`
   → 對 furnace / dispenser / brewing stand 等非目標子類，語意 100% 不變。
2. leaf transform 時用 ASM tree API 把原本的 `getItems()` **MethodNode 複製一份、改名成 `lazycontainer$rawItems`**（不掛 guard），
   原本那個照舊掛 GUARD_ENSURE。
   三個 leaf 都是 `protected NonNullList<ItemStack> getItems()`
   （`ChestBlockEntity.java:194`、`BarrelBlockEntity.java:125`、`ShulkerBoxBlockEntity.java:265`），
   複製法自動處理 shulker 的欄位叫 `itemStacks` 這件事。
3. BCE 那六個方法：入口插 prologue，**方法體內把 `INVOKEVIRTUAL getItems()` 改寫成 `INVOKEVIRTUAL lazycontainer$rawItems()`**。
   vanilla 的邏輯一個字都不用重寫（不必手抄 `setItem` 那三行）。

三個 leaf 都沒有覆寫這六個方法（已 grep 確認），
`RandomizableContainerBlockEntity` 有覆寫但都是「先 `unpackLootTable` 再 `super.xxx`」
（`RandomizableContainerBlockEntity.java:49-76`）⟹ **loot 的解封順序不受影響**。

### 6.2 happens-before：現有論證可以直接沿用，只是多一個 volatile

寫者 W（持 `this` monitor）：
`list.set(i, stack)`（普通寫） → **`decoded |= bit`（volatile 寫）** → 〔若 all〕`raw=null` → `pending=false`（volatile 寫） → 釋放 monitor

未持鎖的讀者 R：
* 讀 `decoded` bit i = **1** ⟹ 這個 volatile 讀與 W 的 volatile 寫同步 ⟹ W 在那之前做的 `list.set(i,…)` 與該 ItemStack 的欄位初始化對 R 全部可見。**拿到的是完整的一格。**
* 讀 bit i = **0** ⟹ R 進 `ensureSlot(i)` → 卡在 monitor 上等 W → 取得 monitor 後二次檢查 → 看到 1 就返回。monitor 的 release/acquire 同樣構成 happens-before。**R 只會等待，不會拿到半寫。**
* 讀 `pending == false` ⟹ 因為 `decoded=all` 的 volatile 寫在 `pending=false` 的 volatile 寫之前，且 volatile 寫彼此不重排 ⟹ R 也看得到整份清單。

**這就是今天 A1 修法（README.md:126 / RELEASE-NOTE-26.2-2.md:11）的逐格版，論證形狀完全一樣。**

### 6.3 必須在 monitor 內的步驟

| 步驟 | 為什麼 |
|---|---|
| `load` | 沿用（`/data merge` 對活體 BE 重跑） |
| `ensureSlot` | 兩個以上執行緒可能同時 miss 同一格；`decoded` 的讀-改-寫不是原子 |
| `ensureAll` | 沿用；**而且它現在要讀 `decoded` 決定填哪些格**，必須是一致快照 |
| `clear` / `clearLazy` | 沿用（INV-7 的三個 null 要在同一個區段） |
| `save`（含混合體） | 存檔要同時讀 `raw`、`decoded`、`list`，三者必須是同一個快照。今天已 synchronized，維持 |

無鎖的只有：`pending` 快查、`decoded` 快查、摘要查詢（`fullState`/`slotEmpty`/`emptyState`）。全部只讀 volatile + 不可變 plane。

**沒有新增的執行緒風險。** 唯一「新」的東西——`ItemStack` 本身可變、被多執行緒同時 `grow/split`——
在 vanilla + Folia 上今天就已經存在（`HopperBlockEntity.java:610` 的 `current.grow(count)` 就是），不是逐格帶來的。

---

## 7. 規格（6）：跟漏斗 hook 怎麼配合

**兩個現有 hook 一行都不用改，而且逐格會自動讓它們的 fallback 變便宜。**

* `isFullContainer`（`HopperBlockEntity.java:477-488`）
  入口的 `lazycontainer$containerFullState` 照舊當「零解碼快查」。
  答不出來（-1）落回原方法時，那個 `for (slot) { container.getItem(slot) … }` 迴圈**每一步只解一格**，
  而且遇到第一個 `count < maxStackSize` 就 `return false`。
  → **s45 那條 16.19 s 的鏈（`isFullContainer → CompoundContainer.getItem → getItems guard → ensure`）
  從「一次解 27 格」變成「解到第一個沒滿的格為止」。** 這正是逐格最直接命中的痛點。

* `tryTakeInItemFromSlot`（`HopperBlockEntity.java:522-529`）
  `slotProvenEmpty` 照舊擋掉空格。擋不掉時 `container.getItem(slot)`（:523）只解那一格。
  `suckInItems`（:499-503）由 slot 0 往上、抽到就 `return true` ⟹ **常態只解 1 格**。

* 雙箱：`CompoundContainer.getItem`（`CompoundContainer.java:78-80`）把 slot 拆到兩半，
  兩半各自是獨立的 BE、各自逐格。現有 `containerFullState`/`slotProvenEmpty` 的雙箱拆法完全沿用。

* **建議新增第三個 hook（成本最低、收益可能最大）**：`HopperBlockEntity.java:592` 的 `container.isEmpty()`。
  今天每次「推入」都會整箱解碼。可以：
  (a) 在 BCE `isEmpty()` 入口插 `lazycontainer$emptyState()` 快查（推薦，通用）；或
  (b) 在 `tryMoveInItem` 內把 `isEmpty()` 移進 `if (success)` 分支——但那會改動 loot 箱的 unpack 時機，**不建議**。

* **`promote` 門檻（必要，防回歸）**
  `AbstractContainerMenu.getRedstoneSignalFromContainer`（:889-905）與
  `LootTable.getAvailableSlots` 都是**無短路的全 27 格掃描**。
  純逐格會讓它們付 27 × 2.7 µs = 73 µs，比今天的 47.5 µs 差 1.55×。
  對策：`ensureSlot` 進入時 `if (Integer.bitCount(decoded) >= PROMOTE) { ensureAll(); return; }`，
  `PROMOTE = 6`（≈ size/4）。最壞 6×2.7 + 47.5 = 63.7 µs（1.34×），
  漏斗常態 1–2 格 = 2.7–5.4 µs（**今天的 6–11%**）。

---

## 8. 誠實標註：哪一步最容易靜默掉物品

依「踩到的機率 × 後果嚴重度」排序。

**① `ensureAll` 在部分已解之後，用舊 raw 蓋掉已改過的格 —— 最危險**
天真寫法 `ContainerHelper.loadAllItems(vi, list)` 會把 raw 的舊值寫回**每一格**，包括漏斗剛抽空的那格。
後果 = **物品原地復活 = 複製**。這正是 `LazyContainerTransformer.java:317-324` 記載的
「無此 guard 時 26/26 格復活」的同一種病。
修法：解進 temp，只填 `decoded` bit 為 0 的格。**這一段一定要寫對抗測試。**

**② 混合存檔誤刪一筆 entry**
第一半的 `continue` 是唯一會「刪東西」的動作。若 slot 判讀把一個「其實屬於未解格」的 entry 判成「屬於已解格」，那格永久空白。
修法：slot 一律走真 codec（§2.2）；`present-but-unparseable` 一律停用逐格；權威 entry 放最後（§4.2 的容錯方向）。

**③ `clear()` 忘了把 `entryOff` / `slotEntryIdx` 清 null（INV-7）**
`/data merge`、`Chest s=(Chest)block.getState(); …; s.update()` 會對活體 BE 重跑 `loadAdditional`。
新 raw 配舊 offsets ⟹ **從錯誤的位元組位置解出一個看起來完全合法的 entry** ⟹ 隨機物品，而且不會有例外。
這是最難察覺的一種：沒有堆疊、沒有 log，只有玩家說「我的東西變成別的了」。

**④ 發佈順序寫反（INV-4）**
`decoded |= bit` 若寫在 `list.set` 之前，就是 A1 事故的逐格版：別的執行緒讀到 bit=1、拿到還沒填的那格。

**⑤ 誤以為「解過但沒改」可以繼續原封回寫**
`HopperBlockEntity.java:610` 的 `current.grow(count)` 直接改 `getItem(slot)` 回傳的物件，**完全不呼叫 `setItem`**；
`ContainerHelper.java:14` 的 `removeItem` 也是 `.split(count)` 就地改；
`hopperPull` 更是在 `HopperBlockEntity.java:285` 直接 `movedItem.setCount(...)` 改活體堆疊。
⟹ **只要一格被 decode 過就必須當成 dirty（INV-6）**，不能為了「多留一點 verbatim」而區分讀/寫。
這個假設很誘人、看起來很安全，而且錯了會靜默掉數量。

**⑥ `decoded` 初值設成 `~sumHas`（「沒 entry 的格已經是對的」）**
看起來是免費的優化，實際上：漏斗把物品推進一個原本沒有 entry 的空格 → 那格的 bit 本來就是 1 →
`decoded != 0` 的判斷失效 → 存檔走 verbatim → **推進去的物品消失**。
所以 `decoded` 必須從 0 起算，「沒 entry」的格由一次 O(1) 的 `ensureSlot`（不解碼，只設 bit）帶過。

**⑦ §1.3 的閘門沒擋住 wrapper / heterogeneous**
實測 `naiveMatchesRef=false`。offsets 與 raw 對不上 ⟹ 解出隔壁 entry 的位元組。
建議 shadow 模式加一條斷言：`Arrays.equals(offsetWriterBytes, NbtIo.writeAnyTag(...))`，正式模式不做（會多一次序列化）。

---

## 9. 建議的分階段交付

| 階段 | 內容 | 需要動存檔嗎 | 風險 |
|---|---|---|---|
| **0** | 保留 `sumClean`/`sumAtMax` 兩張 plane；`BCE.isEmpty()` 加 `emptyState()` 快查；`BCE.clearContent()` 加短路 | ❌ | **低**。摘要方向仍不對稱、存檔規則一字不改 |
| **1** | offsets + `slotEntryIdx` + `ensureSlot` + `promote` 門檻 + 混合存檔 | ✔ | 中。核心是 §8 的 ①②③ |
| **2** | shadow 差分（混合體 vs 全物化重編碼，逐格摘要 vs vanilla）；`fullQ` 分佈觀測回收 | ❌ | 低 |

階段 0 幾乎沒有新的失效模式，卻直接砍掉「漏斗推入 → 整箱解碼」與「clearContent → 解了再丟」兩條，
建議先出這一版、量一輪 `fullQ=證明滿/證明不滿/佔滿但證明不了/整份放棄` 的實際分佈，
再決定階段 1 的投資規模——因為**逐格的收益上限，正好等於「佔滿但證明不了」那一格的次數**。
