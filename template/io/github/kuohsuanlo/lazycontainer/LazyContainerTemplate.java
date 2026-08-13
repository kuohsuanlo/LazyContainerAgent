package io.github.kuohsuanlo.lazycontainer;

import java.util.Objects;
import net.minecraft.core.NonNullList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * <b>編譯期樣板 — 永不在執行期被載入為類別。</b>
 *
 * <p>本類別對「真實的」1.21.11 mojmap NMS 編譯,目的只是讓 javac 產生「正確的、含 NMS 符號參照與
 * stackmap frame 的」bytecode。Agent 在啟動時讀取本類別的 {@code .class} bytes,把所有
 * {@code lazycontainer$...} 欄位與方法「splice(嫁接)」進真正的
 * {@link net.minecraft.world.level.block.entity.BaseContainerBlockEntity},並把 owner
 * 由本類別 remap 成 BaseContainerBlockEntity。因此這裡的 {@code this} 在執行期就是一個
 * BaseContainerBlockEntity(chest / barrel / shulker)。</p>
 *
 * <p>絕對不可在 agent 程式碼裡以「型別」參照本類別(會觸發 bootstrap classloader 載入 → 找不到 NMS
 * → NoClassDefFoundError);只能讀其 bytes。</p>
 *
 * <h3>不變式(資料安全鐵律)</h3>
 * <ul>
 *   <li>{@code lazycontainer$pending == true} ⟺ items 清單「尚未物化」(仍是載入時建立的全空清單),
 *       真正內容以未解碼的原始 "Items" {@link Tag} 暫存在 {@code lazycontainer$raw}。</li>
 *   <li>一旦任何存取點呼叫 {@code getItems()/getContents()},entry-guard 會先呼叫
 *       {@link #lazycontainer$ensure()} 把 raw 解碼進清單,並把 pending 設為 false、raw 設為 null
 *       (raw 立即作廢,永不再被寫回)。</li>
 *   <li>存檔時:pending 且 raw!=null 且 output 是 TagValueOutput ⟹ 逐位元組把 raw 寫回(跳過 encode);
 *       否則 ⟹ 先物化再正常 encode。最壞只是少省一次,絕不掉資料。</li>
 * </ul>
 */
public abstract class LazyContainerTemplate extends BaseContainerBlockEntity {

    /** true = items 尚未物化,內容在 {@link #lazycontainer$raw}。預設 false ⟹ 非目標容器行為完全不變。 */
    public boolean lazycontainer$pending;

    /** 載入時暫存的未解碼原始 "Items" ListTag(可能為 null = 原本就沒有 Items)。 */
    public Tag lazycontainer$raw;

    // ── 摘要(ensure 快取):讓漏斗的滿/空檢查不必觸發整箱解碼 ──
    // 不變式:摘要只能在「答案可證明與 vanilla 解碼後行為完全一致」時給出定論;
    // 任何不確定(詭異 NBT、未知物品、意料外的 component)⟹ 回「不知道」⟹ 呼叫端走原路(物化)。
    // 錯誤方向不對稱:錯報「不滿/非空」只是多做一次原版掃描(無害);錯報「滿/空」會讓漏斗停搬(有害),
    // 因此「滿=1」「此格為空=true」只在完整證明鏈成立時回答。

    /** 摘要狀態:0=未建、1=有效、2=放棄(整份 raw 無法安全摘要)。pending=false 時無意義。 */
    public int lazycontainer$sumState;

    /** bit i = slot i「有 entry」(佔用宣告)。位元為 0 ⟹ 該格證明為空(raw 內沒有任何 entry 指到它)。 */
    public int lazycontainer$sumBits;

    /** 滿判定三態:1=證明全滿(每格 count>=maxStackSize)、0=證明不滿、-1=無法證明。 */
    public int lazycontainer$sumFullTri;

    /** 永不被呼叫;僅為通過編譯。splice 時不會嫁接 {@code <init>}。 */
    protected LazyContainerTemplate(BlockEntityType<?> type, BlockPos pos, BlockState st) {
        super(type, pos, st);
    }

    // ── 載入 redirect 目標(取代 leaf loadAdditional/loadFromTag 內的 ContainerHelper.loadAllItems 呼叫)──

    /**
     * 取代 {@code ContainerHelper.loadAllItems(input, items)}。
     * 若 input 是 TagValueInput(chunk 載入恆是),擷取未解碼的 "Items" tag 暫存、標記 pending,
     * <b>跳過昂貴的 decode</b>;否則退回 eager(安全)。
     */
    public void lazycontainer$load(ValueInput input, NonNullList<ItemStack> items) {
        this.lazycontainer$sumState = 0;    // 換了新 raw,舊摘要作廢
        if (input instanceof TagValueInput) {
            this.lazycontainer$raw = ((TagValueInput) input).input.get("Items");
            this.lazycontainer$pending = true;
            LazyContainerRuntime.onStash();
            return;
        }
        ContainerHelper.loadAllItems(input, items);
        this.lazycontainer$pending = false;
        LazyContainerRuntime.onEagerLoad();
    }

    /**
     * 首次被任何存取點觸發時,把暫存的 raw 解碼進真正的 items 清單。set-flag-first 保證 reentrancy 安全。
     * <p>synchronized:Paper 單主緒下無競爭(uncontended thin-lock CAS;且 guard 先讀 pending 才呼叫,
     * 已物化容器的熱路徑根本不進 monitor);regionized 平台(EndRod/Folia 系)上防止
     * 兩條 region 執行緒同時物化同一容器(併發 loadAllItems 互踩=真毀損)。注意這只序列化「物化」本身;
     * 未持鎖讀 pending 的執行緒理論上仍可能讀到旗標已清、清單未填完的瞬間(既有設計邊界,與本鎖無關)。</p>
     */
    public synchronized void lazycontainer$ensure() {
        if (!this.lazycontainer$pending) {
            return;
        }
        this.lazycontainer$pending = false;     // 先清旗標 → 下面 getItems() 不會再 reenter 本方法
        Tag raw = this.lazycontainer$raw;
        if (raw != null) {
            try {
                CompoundTag tmp = new CompoundTag();
                tmp.put("Items", raw);
                ValueInput vi = TagValueInput.createGlobal(ProblemReporter.DISCARDING, tmp);
                ContainerHelper.loadAllItems(vi, this.getItems());  // 依 slot set,冪等 → 失敗可安全重試
                this.lazycontainer$raw = null;                      // 僅「成功物化後」才作廢 raw
                LazyContainerRuntime.onEnsure();
            } catch (Throwable t) {
                // 物化失敗(理論上不可達,DISCARDING 吞解碼錯):還原 pending、保留 raw,下次存取重試,絕不靜默丟失
                this.lazycontainer$pending = true;
                throw t;
            }
        } else {
            this.lazycontainer$raw = null;
        }
    }

    // ── 存檔 redirect 目標(取代 leaf saveAdditional 內的 ContainerHelper.saveAllItems 呼叫)──

    /** 取代 {@code ContainerHelper.saveAllItems(output, items)}(allowEmpty=true:chest/barrel)。 */
    public void lazycontainer$save(ValueOutput output, NonNullList<ItemStack> items) {
        if (this.lazycontainer$trySaveRaw(output, true)) {
            return;
        }
        ContainerHelper.saveAllItems(output, items);
    }

    /** 取代 {@code ContainerHelper.saveAllItems(output, items, false)}(allowEmpty=false:shulker)。 */
    public void lazycontainer$saveNoEmpty(ValueOutput output, NonNullList<ItemStack> items) {
        if (this.lazycontainer$trySaveRaw(output, false)) {
            return;
        }
        ContainerHelper.saveAllItems(output, items, false);
    }

    /**
     * 若容器自載入後從未物化(pending)且可安全回寫,就把原始 "Items" tag 逐位元組塞回 output,回傳 true
     * (呼叫端跳過 encode)。否則先物化(ensure)再回傳 false(呼叫端走正常 encode)。
     *
     * @param allowEmpty 對應 vanilla saveAllItems 的 allowEmpty(shadow 模式用以算出 byte-identical 的 eager 結果)
     */
    private boolean lazycontainer$trySaveRaw(ValueOutput output, boolean allowEmpty) {
        if (!this.lazycontainer$pending) {
            return false;
        }
        Tag raw = this.lazycontainer$raw;
        // 只在 raw 為「真正的 ListTag」時走快路徑;且 allowEmpty==false(shulker)遇空清單不可寫 raw
        // (vanilla 對空清單會 discard "Items")。非 ListTag / 空-shulker 一律退回 ensure+正常 save,
        // 對所有輸入(含外部/損毀 NBT)逐位元組對齊 vanilla,且不掉物。
        boolean canWriteRaw = (raw instanceof ListTag)
                && !(!allowEmpty && ((ListTag) raw).isEmpty());
        if (canWriteRaw && output instanceof TagValueOutput) {
            CompoundTag out = ((TagValueOutput) output).buildResult();
            if (LazyContainerRuntime.shadow()) {
                Tag eager = this.lazycontainer$eagerItems(raw, allowEmpty);
                if (!Objects.equals(eager, raw)) {
                    if (this.lazycontainer$sameItems(raw, eager)) {
                        // 只是 Items 清單順序不同、物品與槽位完全相同 → 良性:仍偵測回報(標 NO IMPACT)、落到下方寫 raw(安全)
                        LazyContainerRuntime.onBenignReorder(String.valueOf(this.getBlockPos()),
                                String.valueOf(raw), String.valueOf(eager));
                    } else {
                        LazyContainerRuntime.onShadowMismatch();
                        LazyContainerRuntime.dumpMismatch(String.valueOf(this.getBlockPos()),
                                String.valueOf(raw), eager == null ? "<discard>" : String.valueOf(eager));
                        System.err.println("[LazyContainer] SHADOW mismatch @ " + this.getBlockPos()
                                + " — writing eager (safe). rawType=" + raw.getClass().getSimpleName());
                        if (eager != null) {
                            out.put("Items", eager);
                        } else {
                            out.remove("Items");
                        }
                        return true;
                    }
                }
            }
            // 必須寫 raw 的深拷貝:raw 是活體 BE 欄位(lazycontainer$raw),寫回後 pending 不清、raw 保留。
            // 若直接把本體放進存檔 compound,存檔輸出會與活容器別名(vanilla 每次 fresh encode 天生隔離):
            // (a) 該 compound 被 Moonrise 交 IO 執行緒非同步序列化,主線程 /data modify 就地改 raw → 撕裂寫檔;
            // (b) /clone、structure SAVE、CraftBukkit getState() 快照沿 save→load 把同一 ListTag 塞進第二個 BE,
            //     事後對任一箱 /data modify|remove 會就地改到另一箱(掉物/複製)。copy 樹複製仍遠比 codec encode 便宜。
            out.put("Items", raw.copy());
            LazyContainerRuntime.onRawSave();
            return true;
        }
        // raw==null / 非 ListTag / 空清單-shulker / output 非 TagValueOutput:
        // 物化後讓呼叫端走正常 encode(語意逐位元組等同 vanilla)
        this.lazycontainer$ensure();
        return false;
    }

    /** shadow 用:把 raw 完整 parse→encode 一次,回傳 eager 會寫出的 "Items" tag(可能 null = 被 discard)。 */
    private Tag lazycontainer$eagerItems(Tag raw, boolean allowEmpty) {
        CompoundTag reIn = new CompoundTag();
        reIn.put("Items", raw);
        NonNullList<ItemStack> tmp = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(TagValueInput.createGlobal(ProblemReporter.DISCARDING, reIn), tmp);
        TagValueOutput eagerOut = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, MinecraftServer.getServer().registryAccess());
        ContainerHelper.saveAllItems(eagerOut, tmp, allowEmpty);
        return eagerOut.buildResult().get("Items");
    }

    /**
     * raw 與 eager 是否「同一組物品、只是 Items 清單順序不同」。
     * 把兩邊的 Items 當 multiset 比(每個 entry 自帶 Slot,清單順序不影響槽位);
     * 元素用 NBT Tag.equals(CompoundTag 為 map 比對,與 key 順序無關)。
     * 是 → 良性,寫 raw 安全(物品、槽位皆同,玩家驗證不出差異)。
     */
    private boolean lazycontainer$sameItems(Tag rawTag, Tag eagerTag) {
        if (!(rawTag instanceof ListTag) || !(eagerTag instanceof ListTag)) {
            return false;
        }
        ListTag a = (ListTag) rawTag;
        ListTag e = (ListTag) eagerTag;
        int n = a.size();
        if (n != e.size()) {
            return false;
        }
        boolean[] used = new boolean[n];
        for (int i = 0; i < n; i++) {
            Tag ai = a.get(i);
            boolean found = false;
            for (int j = 0; j < n; j++) {
                if (!used[j] && ai.equals(e.get(j))) {
                    used[j] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    // ── 摘要(ensure 快取):HopperBlockEntity hook 的進入點 ──

    /**
     * 漏斗推(eject)前的滿檢查。回 1=證明全滿、0=證明不滿、-1=不知道(呼叫端走原版逐格掃描)。
     * 支援單箱(BaseContainerBlockEntity)與雙箱(CompoundContainer,兩半各查)。
     * 任何意外(含在非 Paper 系 jar 上 CompoundContainer 欄位不可見)⟹ catch 後回 -1,行為退回原版。
     */
    public static int lazycontainer$containerFullState(Container c) {
        // 編譯期注意:lazycontainer$ 成員在 javac 眼裡只存在於本 template 類,故以本類型別做
        // instanceof/cast/呼叫;splice 時 remapper 會把這些 owner 全數改寫成 BaseContainerBlockEntity。
        if (!LazyContainerRuntime.summary()) {
            return -1;
        }
        int r = -1;
        try {
            if (c instanceof LazyContainerTemplate) {
                r = ((LazyContainerTemplate) c).lazycontainer$fullState();
            } else if (c instanceof CompoundContainer) {
                CompoundContainer cc = (CompoundContainer) c;
                Container c1 = cc.container1;
                Container c2 = cc.container2;
                if (c1 instanceof LazyContainerTemplate && c2 instanceof LazyContainerTemplate) {
                    int f1 = ((LazyContainerTemplate) c1).lazycontainer$fullState();
                    int f2 = ((LazyContainerTemplate) c2).lazycontainer$fullState();
                    if (f1 == 0 || f2 == 0) {
                        r = 0;      // 任一半證明不滿 ⟹ 整體必不滿
                    } else if (f1 == 1 && f2 == 1) {
                        r = 1;      // 兩半都證明全滿 ⟹ 整體全滿
                    }
                }
            }
            if (r >= 0 && LazyContainerRuntime.shadow()) {
                r = lazycontainer$verifyFull(c, r);     // shadow:拿真實 items 覆核,不一致就棄答+告警
            }
        } catch (Throwable t) {
            // 摘要永遠只能是加速,不能是新故障源
            return -1;
        }
        if (r >= 0) {
            LazyContainerRuntime.onSummaryFull();
        }
        return r;
    }

    /**
     * 漏斗抽(suck)的逐格前置檢查:此格是否「證明為空」(raw 內沒有任何 entry 指到它)。
     * true ⟹ 呼叫端可直接跳過此格(vanilla 對空格的行為就是跳過,零副作用);false ⟹ 走原路。
     * 雙箱依 {@code CompoundContainer.getItem} 的同一套 slot 映射拆到對應半箱。
     */
    public static boolean lazycontainer$slotProvenEmpty(Container c, int slot) {
        if (!LazyContainerRuntime.summary()) {
            return false;
        }
        boolean empty = false;
        try {
            if (c instanceof LazyContainerTemplate) {
                empty = ((LazyContainerTemplate) c).lazycontainer$slotEmpty(slot);
            } else if (c instanceof CompoundContainer) {
                CompoundContainer cc = (CompoundContainer) c;
                Container c1 = cc.container1;
                int n = c1.getContainerSize();
                if (slot < n) {
                    empty = c1 instanceof LazyContainerTemplate
                            && ((LazyContainerTemplate) c1).lazycontainer$slotEmpty(slot);
                } else {
                    Container c2 = cc.container2;
                    empty = c2 instanceof LazyContainerTemplate
                            && ((LazyContainerTemplate) c2).lazycontainer$slotEmpty(slot - n);
                }
            }
            if (empty && LazyContainerRuntime.shadow()) {
                empty = lazycontainer$verifySlotEmpty(c, slot);
            }
        } catch (Throwable t) {
            // 同上:任何意外 ⟹ 當作不知道
            return false;
        }
        if (empty) {
            LazyContainerRuntime.onSummarySkip();
        }
        return empty;
    }

    /** shadow 覆核:用真實(必要時就地物化的)items 重算 vanilla 的滿判定,與摘要主張比對。 */
    private static int lazycontainer$verifyFull(Container c, int claimed) {
        boolean vanillaFull = true;
        int n = c.getContainerSize();
        for (int i = 0; i < n; i++) {
            ItemStack s = c.getItem(i);
            if (s.getCount() < s.getMaxStackSize()) {
                vanillaFull = false;
                break;
            }
        }
        if ((claimed != 0) != vanillaFull) {
            LazyContainerRuntime.onSummaryMismatch("full claimed=" + claimed + " vanilla=" + vanillaFull);
            return -1;
        }
        return claimed;
    }

    /** shadow 覆核:摘要說「此格證明為空」時,拿真實 items 確認。 */
    private static boolean lazycontainer$verifySlotEmpty(Container c, int slot) {
        if (!c.getItem(slot).isEmpty()) {
            LazyContainerRuntime.onSummaryMismatch("slotEmpty slot=" + slot + " vanilla=nonEmpty");
            return false;
        }
        return true;
    }

    /**
     * 未開封的 loot 容器必須棄答。
     * <p><b>關鍵事實(Paper 專屬,與原版 vanilla 不同)</b>:Paper 的 LootTable API patch 讓
     * {@code RandomizableContainer.tryLoadLootTable} 回傳 {@code lootTable != null && lootableData() == null},
     * 而 {@code RandomizableContainerBlockEntity.lootableData} 是欄位初始化的 {@code new PaperLootableInventoryData()}
     * ——<b>恆非 null ⟹ tryLoadLootTable 在 Paper 上恆回 false</b>。因此 loot 箱同樣會走
     * {@code loadAllItems}(被 redirect)⟹ 同樣 pending,且其 raw 是「空的 Items 清單」。
     * 若照常回答「每格證明為空」,漏斗的 {@code tryTakeInItemFromSlot} 會在
     * {@code RandomizableContainerBlockEntity.getItem(0)} 之前被短路,
     * 那行的 {@code unpackLootTable} 就永遠不會觸發 ⟹ 漏斗再也抽不出未開封的戰利品箱(靜默行為回歸)。
     * 一旦 {@code unpackLootTable} 真的跑過,它會 {@code setLootTable(null)},此判斷自然放行。</p>
     */
    private boolean lazycontainer$lootPending() {
        return this instanceof RandomizableContainer
                && ((RandomizableContainer) this).getLootTable() != null;
    }

    /** 單箱滿判定三態;非 pending(已物化/非 lazy 容器)或未開封 loot 容器恆回 -1。計數由聚合端負責。 */
    public int lazycontainer$fullState() {
        if (!this.lazycontainer$pending || this.lazycontainer$lootPending()) {
            return -1;
        }
        if (this.lazycontainer$sumState == 0) {
            this.lazycontainer$buildSummary();
        }
        if (this.lazycontainer$sumState != 1) {
            return -1;
        }
        return this.lazycontainer$sumFullTri;
    }

    /** 單箱單格「證明為空」;非 pending 或未開封 loot 容器恆回 false。計數由聚合端負責。 */
    public boolean lazycontainer$slotEmpty(int slot) {
        if (!this.lazycontainer$pending || this.lazycontainer$lootPending()) {
            return false;
        }
        if (this.lazycontainer$sumState == 0) {
            this.lazycontainer$buildSummary();
        }
        if (this.lazycontainer$sumState != 1) {
            return false;
        }
        if (slot < 0 || slot >= 32 || slot >= this.getContainerSize()) {
            return false;
        }
        return (this.lazycontainer$sumBits >>> slot & 1) == 0;
    }

    /**
     * 從 raw(未解碼的 "Items" ListTag)一次掃出摘要。逐 entry 精確重演 vanilla 解碼語意:
     * <ul>
     *   <li>Slot:{@code ExtraCodecs.UNSIGNED_BYTE} + 預設 0 ⟹ 缺欄位=0;任何 NumericTag 依
     *       {@code Number.byteValue() & 0xFF} 決定落點(與 Codec.BYTE 的數值強轉逐位相同);
     *       非數值 ⟹ 該 entry 解碼必失敗、不落任何格 ⟹ 忽略。越界(>=size)= vanilla
     *       {@code isValidInContainer} 丟棄 ⟹ 忽略。</li>
     *   <li>佔用宣告(bit):slot 只要被任何 entry 指到就標佔用——即使那個 entry 其實會解碼失敗。
     *       「證明為空」只給完全沒有 entry 的格,方向絕對安全。</li>
     *   <li>滿判定:每格以「最後一個指到它的 entry」為準(vanilla {@code items.set} 後寫者勝)。
     *       只有該 entry 走完完整證明鏈(id 可解析非 air、count 為數值 intValue∈[1,99](缺=1)、
     *       components 缺席或只含合法的 minecraft:max_stack_size)才算「乾淨」,
     *       乾淨才能參與「全滿=1」的證明;乾淨且 count&lt;max 則可直接證明「不滿=0」。</li>
     * </ul>
     */
    public void lazycontainer$buildSummary() {
        LazyContainerRuntime.onSummaryBuild();
        Tag raw = this.lazycontainer$raw;
        int size = this.getContainerSize();
        if (!(raw instanceof ListTag) || size <= 0 || size > 32) {
            this.lazycontainer$sumState = 2;
            return;
        }
        ListTag list = (ListTag) raw;
        int hasBits = 0;
        int cleanBits = 0;
        int atMaxBits = 0;
        for (int i = 0; i < list.size(); i++) {
            Tag e = list.get(i);
            if (!(e instanceof CompoundTag)) {
                continue;   // MapCodec 對非 compound 必失敗、不落格 ⟹ 忽略
            }
            CompoundTag t = (CompoundTag) e;
            Tag slotTag = t.get("Slot");
            int slot;
            if (slotTag == null) {
                slot = 0;                                            // optionalAlwaysPresentFieldOf 預設 0
            } else if (slotTag instanceof NumericTag) {
                // 必須用 box():vanilla 是 NbtOps.getNumberValue → Tag.asNumber() → box() → Codec.BYTE 的
                // Number.byteValue()(java.lang.Double/Float = 向零截斷)。NumericTag.byteValue() 對
                // Double/FloatTag 是 Mth.floor,負小數會與 vanilla 差 1 格 ⟹ 摘要漏標佔用 ⟹ 假「證明為空」。
                slot = ((NumericTag) slotTag).box().byteValue() & 0xFF;
            } else {
                continue;                                            // Codec.BYTE 必失敗 ⟹ 不落格
            }
            if (slot >= size) {
                continue;                                            // isValidInContainer 丟棄
            }
            int bit = 1 << slot;
            hasBits |= bit;
            // ── 以下判定此 entry 是否「乾淨」(可證明解碼成功且 count/max 可算)──
            boolean clean = false;
            boolean atMax = false;
            body: {
                Tag idTag = t.get("id");
                if (!(idTag instanceof StringTag)) {
                    break body;
                }
                Identifier id = Identifier.tryParse(((StringTag) idTag).value());
                if (id == null) {
                    break body;
                }
                // 注意:template 內禁用 lambda/method-ref(invokedynamic 會生出不帶 lazycontainer$ 前綴的
                // 合成方法,不會被 splice ⟹ 執行期 NoSuchMethodError),一律寫成普通流程。
                java.util.Optional<net.minecraft.core.Holder.Reference<Item>> itemRef = BuiltInRegistries.ITEM.get(id);
                Item item = itemRef.isPresent() ? itemRef.get().value() : null;
                if (item == null || item == Items.AIR) {
                    break body;                                      // 未知 id / air:codec 必拒(Item 不得為 air)
                }
                int count;
                Tag countTag = t.get("count");
                if (countTag == null) {
                    count = 1;                                       // optionalAlwaysPresentFieldOf 預設 1
                } else if (countTag instanceof NumericTag) {
                    count = ((NumericTag) countTag).box().intValue();  // 同上:box() 才是 Codec.INT 的取值路徑
                } else {
                    break body;
                }
                if (count < 1 || count > 99) {
                    break body;                                      // intRange(1,99) 之外 ⟹ entry 必失敗
                }
                int max = item.getDefaultMaxStackSize();
                Tag compsTag = t.get("components");
                if (compsTag != null) {
                    if (!(compsTag instanceof CompoundTag)) {
                        break body;
                    }
                    CompoundTag comps = (CompoundTag) compsTag;
                    // 只允許「沒有 key」或「唯一 key = 合法的 minecraft:max_stack_size」;
                    // 其他任何 component 都可能讓 DataComponentPatch 解碼失敗(該格實為空)⟹ 不得參與「全滿」證明。
                    for (String key : comps.keySet()) {
                        if (!"minecraft:max_stack_size".equals(key)) {
                            break body;
                        }
                    }
                    Tag maxTag = comps.get("minecraft:max_stack_size");
                    if (maxTag != null) {
                        if (!(maxTag instanceof NumericTag)) {
                            break body;
                        }
                        int m = ((NumericTag) maxTag).box().intValue();
                        if (m < 1 || m > 99) {
                            break body;
                        }
                        max = m;
                    }
                }
                clean = true;
                atMax = count >= max;                                // isFullContainer 的判準:count < max ⟹ 不滿
            }
            // 後寫者勝:此 entry 覆蓋此格先前的乾淨/滿標記
            if (clean) {
                cleanBits |= bit;
                if (atMax) {
                    atMaxBits |= bit;
                } else {
                    atMaxBits &= ~bit;
                }
            } else {
                cleanBits &= ~bit;
                atMaxBits &= ~bit;
            }
        }
        int all = (size == 32) ? -1 : (1 << size) - 1;
        int tri;
        if ((hasBits & all) != all) {
            tri = 0;                                                 // 有證明為空的格 ⟹ 必不滿
        } else if ((cleanBits & all) == all && (atMaxBits & all) == all) {
            tri = 1;                                                 // 每格最終 entry 皆乾淨且 count>=max ⟹ 證明全滿
        } else if ((cleanBits & ~atMaxBits & all) != 0) {
            tri = 0;                                                 // 有格最終 entry 乾淨且 count<max ⟹ 證明不滿
        } else {
            tri = -1;                                                // 佔滿但有無法證明的格 ⟹ 滿與否不知道
        }
        this.lazycontainer$sumBits = hasBits;
        this.lazycontainer$sumFullTri = tri;
        this.lazycontainer$sumState = 1;
    }
}
