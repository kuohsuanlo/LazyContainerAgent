package io.github.kuohsuanlo.lazycontainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Objects;
import net.minecraft.core.NonNullList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
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
 *       真正內容以 <b>NBT 二進位序列化的 {@code byte[]}</b> 暫存在 {@code lazycontainer$raw}
 *       (原本是 {@link Tag} 樹;s18 材料站 OOM 事故證明 26 MB 的 Items 樹會佔 80–260 MB heap,
 *       改存 bytes 後滯留量就是 bytes 本身,見面板 #160 方案 A′)。</li>
 *   <li>一旦任何存取點呼叫 {@code getItems()/getContents()},entry-guard 會先呼叫
 *       {@link #lazycontainer$ensure()} 把 raw 解碼進清單,並把 pending 設為 false、raw 設為 null
 *       (raw 立即作廢,永不再被寫回)。</li>
 *   <li>存檔時:pending 且 raw!=null 且 output 是 TagValueOutput ⟹ 把 raw 解回 Tag 塞進 output
 *       (每次都是全新的私有樹,結構性杜絕「存檔輸出與活容器共用同一棵樹」的別名家族);
 *       寫進磁碟的位元組與讀進來的等價(NBT 二進位往返保序)。否則 ⟹ 先物化再正常 encode。
 *       最壞只是少省一次,絕不掉資料。</li>
 *   <li>摘要(sumState/sumBits/sumFullTri)在載入當下「趁樹還在手上」eager 建好——
 *       漏斗的十億級查詢永遠不需要 parse bytes。</li>
 * </ul>
 */
public abstract class LazyContainerTemplate extends BaseContainerBlockEntity {

    /** true = items 尚未物化,內容在 {@link #lazycontainer$raw}。預設 false ⟹ 非目標容器行為完全不變。 */
    public boolean lazycontainer$pending;

    /**
     * 載入時暫存的原始 "Items" tag,以 <b>NBT 二進位</b>({@code NbtIo.writeAnyTag} 框架:1 byte 型別 + payload)
     * 序列化保存;null = 原本就沒有 Items。
     * <p>為什麼是 {@code byte[]} 不是 {@link Tag} 樹:NBT 物件樹(每層一個 Object2ObjectOpenHashMap +
     * entry 陣列 + String key + tag 物件頭)是原始 bytes 的 3–10 倍;材料站那種單 chunk 26 MB Items 的
     * 場景,樹形滯留直接灌爆 heap(s18 2026-08-14 OOM,面板 #160)。bytes 形式的滯留量就是資料本身。</p>
     * <p>為什麼不能連 bytes 都省(直接引用磁碟 buffer):agent 掛在 ValueInput 層,拿到的已是解析完的樹,
     * 上游的壓縮串流早就不在了;而 {@code Tag} 是 sealed interface,也做不出 byte-backed 的假 Tag
     * 讓存檔直接吐 bytes——那兩條都是方案 B(改核心)的領域。</p>
     */
    public byte[] lazycontainer$raw;

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
     * 若 input 是 TagValueInput(chunk 載入恆是),把 "Items" tag 序列化成 bytes 暫存、標記 pending,
     * <b>跳過昂貴的物品 decode</b>;否則退回 eager(安全)。
     * <p>兩件事都趁「樹還在手上」做完:(1) encode 成 bytes(之後 chunk 樹整棵可被 GC,
     * 滯留只剩 bytes);(2) eager 建摘要(漏斗查詢永不需要 parse bytes)。
     * encode 有任何意外 ⟹ 整個退回 eager 載入,行為=vanilla。</p>
     */
    public void lazycontainer$load(ValueInput input, NonNullList<ItemStack> items) {
        this.lazycontainer$sumState = 0;    // 換了新 raw,舊摘要作廢
        if (input instanceof TagValueInput) {
            Tag itemsTag = ((TagValueInput) input).input.get("Items");
            byte[] encoded;
            try {
                encoded = lazycontainer$encodeRaw(itemsTag);
            } catch (Throwable t) {
                // encode 失敗(現實上不可達):退回 eager,行為與 vanilla 完全相同
                ContainerHelper.loadAllItems(input, items);
                this.lazycontainer$pending = false;
                LazyContainerRuntime.onEagerLoad();
                return;
            }
            this.lazycontainer$raw = encoded;
            this.lazycontainer$pending = true;
            // 摘要 eager 建置:樹此刻還在(免 parse);查詢端不再 lazy build(sumState==0 一律當「不知道」)
            if (LazyContainerRuntime.summary()) {
                long packed = lazycontainer$computeSummary(itemsTag, this.getContainerSize());
                if (packed == LAZYCONTAINER$SUMMARY_GIVEUP) {
                    this.lazycontainer$sumState = 2;
                } else {
                    this.lazycontainer$sumBits = (int) (packed >>> 32);
                    this.lazycontainer$sumFullTri = ((int) (packed & 0xFFFFFFFFL)) - 1;
                    this.lazycontainer$sumState = 1;
                }
                LazyContainerRuntime.onSummaryBuild();
            } else {
                this.lazycontainer$sumState = 2;
            }
            LazyContainerRuntime.onStash();
            return;
        }
        ContainerHelper.loadAllItems(input, items);
        this.lazycontainer$pending = false;
        LazyContainerRuntime.onEagerLoad();
    }

    /** Tag → NBT 二進位({@code NbtIo.writeAnyTag} 框架);null 進 null 出。 */
    public static byte[] lazycontainer$encodeRaw(Tag tag) throws java.io.IOException {
        if (tag == null) {
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
        DataOutputStream dos = new DataOutputStream(bos);
        NbtIo.writeAnyTag(tag, dos);
        return bos.toByteArray();
    }

    /** NBT 二進位 → Tag(與 {@link #lazycontainer$encodeRaw} 嚴格對稱);null 進 null 出。 */
    public static Tag lazycontainer$decodeRaw(byte[] bytes) throws java.io.IOException {
        if (bytes == null) {
            return null;
        }
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        return NbtIo.readAnyTag(dis, NbtAccounter.unlimitedHeap());
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
        byte[] rawBytes = this.lazycontainer$raw;
        if (rawBytes != null) {
            // 歸因(#223 未結②)+ 解碼計時(#223 未結①):堆疊要在解碼「之前」抓(之後鏈就沒了),
            // 耗時要包住真解碼。每容器每次載入至多一次、不在 tick 熱路徑。
            // 兩者都只在 rawBytes!=null 分支內:raw==null 的 no-op 物化零解碼成本,不計桶也不計時
            // (否則 Σattr>ensure,冷機谷報表被幽靈數字稀釋)。
            StackTraceElement[] attrStack = null;
            long attrT0 = 0L;
            if (LazyContainerRuntime.attribution()) {
                try {
                    attrStack = new Exception().getStackTrace();
                    attrT0 = System.nanoTime();
                } catch (Throwable ignored) {
                    attrStack = null;           // 觀測失敗絕不影響物化
                }
            }
            try {
                Tag raw = lazycontainer$decodeRaw(rawBytes);        // bytes → Tag(自家 encode 的往返,失敗即拋)
                CompoundTag tmp = new CompoundTag();
                tmp.put("Items", raw);
                ValueInput vi = TagValueInput.createGlobal(ProblemReporter.DISCARDING, tmp);
                ContainerHelper.loadAllItems(vi, this.getItems());  // 依 slot set,冪等 → 失敗可安全重試
                this.lazycontainer$raw = null;                      // 僅「成功物化後」才作廢 raw
                LazyContainerRuntime.onEnsure();
                if (attrStack != null) {
                    try {
                        long took = System.nanoTime() - attrT0;
                        // 座標字串只有慢解碼才建(冷機幾千次物化不能每次配置字串)
                        String pos = null;
                        if (LazyContainerRuntime.slowDecode(took)) {
                            pos = this.getBlockPos().toShortString();
                        }
                        LazyContainerRuntime.onEnsureAttributed(attrStack, took, pos);
                    } catch (Throwable ignored) {
                        // 觀測失敗絕不影響物化(此時物品已就位)
                    }
                }
            } catch (java.io.IOException io) {
                // decode 自家 bytes 失敗(現實上不可達):還原 pending、保留 raw,下次重試,絕不靜默丟失
                this.lazycontainer$pending = true;
                throw new IllegalStateException("lazycontainer raw decode failed", io);
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
        byte[] rawBytes = this.lazycontainer$raw;
        if (rawBytes == null || !(output instanceof TagValueOutput)) {
            // 沒有 Items 可回寫 / output 型別非預期:物化後讓呼叫端走正常 encode(語意等同 vanilla)
            this.lazycontainer$ensure();
            return false;
        }
        Tag raw;
        try {
            raw = lazycontainer$decodeRaw(rawBytes);    // 每次存檔 parse 一棵**全新的私有樹**(取代舊版的 raw.copy())
        } catch (java.io.IOException io) {
            this.lazycontainer$ensure();                // 自家 bytes 解不開(不可達):退回正常 encode,不掉資料
            return false;
        }
        // 只在 raw 為「真正的 ListTag」時走快路徑;且 allowEmpty==false(shulker)遇空清單不可寫 raw
        // (vanilla 對空清單會 discard "Items")。非 ListTag / 空-shulker 一律退回 ensure+正常 save,
        // 對所有輸入(含外部/損毀 NBT)逐位元組對齊 vanilla,且不掉物。
        boolean canWriteRaw = (raw instanceof ListTag)
                && !(!allowEmpty && ((ListTag) raw).isEmpty());
        if (!canWriteRaw) {
            this.lazycontainer$ensure();
            return false;
        }
        CompoundTag out = ((TagValueOutput) output).buildResult();
        if (LazyContainerRuntime.shadow()) {
            // shadow 是「純觀測」模式:偵測並回報 raw 與 vanilla 重新編碼的差異,但**絕不改寫玩家資料**。
            // 設計原則(服主要求):寫回磁碟的必須逐位元組等於讀進來的那份,不論兩者語意是否等價。
            Tag eager = this.lazycontainer$eagerItems(raw, allowEmpty);
            if (!Objects.equals(eager, raw)) {
                if (this.lazycontainer$sameItems(raw, eager)) {
                    LazyContainerRuntime.onBenignReorder(String.valueOf(this.getBlockPos()),
                            String.valueOf(raw), String.valueOf(eager));
                } else {
                    LazyContainerRuntime.onShadowMismatch();
                    LazyContainerRuntime.dumpMismatch(String.valueOf(this.getBlockPos()),
                            String.valueOf(raw), eager == null ? "<discard>" : String.valueOf(eager));
                    System.err.println("[LazyContainer] SHADOW mismatch @ " + this.getBlockPos()
                            + " — reporting only, raw kept verbatim. rawType=" + raw.getClass().getSimpleName());
                }
            }
        }
        // 不再需要 .copy():raw 是本次 parse 出的全新樹,存檔 compound 是它唯一的持有者。
        // 舊版把「活體 BE 欄位上的樹」放進存檔輸出才有別名家族(/clone、structure、getState、async 寫盤互撞);
        // bytes 形式下每個消費者天生拿到獨立副本,那一整族問題結構性消失。
        out.put("Items", raw);
        LazyContainerRuntime.onRawSave();
        return true;
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

    /**
     * 單箱滿判定三態;非 pending(已物化/非 lazy 容器)或未開封 loot 容器恆回 -1。計數由聚合端負責。
     * <p>摘要一律在載入時 eager 建好;{@code sumState==0}(不該發生)直接當「不知道」——
     * 絕不在查詢路徑 parse bytes(漏斗每 tick 打十億次,一次 26 MB parse 就是一次凍結)。</p>
     */
    public int lazycontainer$fullState() {
        if (!this.lazycontainer$pending || this.lazycontainer$sumState != 1 || this.lazycontainer$lootPending()) {
            return -1;
        }
        return this.lazycontainer$sumFullTri;
    }

    /** 單箱單格「證明為空」;非 pending 或未開封 loot 容器恆回 false。計數由聚合端負責。 */
    public boolean lazycontainer$slotEmpty(int slot) {
        if (!this.lazycontainer$pending || this.lazycontainer$sumState != 1 || this.lazycontainer$lootPending()) {
            return false;
        }
        if (slot < 0 || slot >= 32 || slot >= this.getContainerSize()) {
            return false;
        }
        return (this.lazycontainer$sumBits >>> slot & 1) == 0;
    }

    /** {@link #lazycontainer$computeSummary} 的「無法摘要」哨兵值。 */
    public static final long LAZYCONTAINER$SUMMARY_GIVEUP = Long.MIN_VALUE;

    /**
     * 摘要演算法本體,寫成<b>純函式</b>(不碰 this、不碰計數器)——如此才能用純 JUnit 直接對它做
     * 差分測試(餵同一份 raw 給它與真 {@code ContainerHelper.loadAllItems},比對結論),
     * 不必啟一台 Minecraft server。逐 entry 精確重演 vanilla 解碼語意:
     * <ul>
     *   <li>Slot:{@code ExtraCodecs.UNSIGNED_BYTE} + 預設 0;NumericTag 依 {@code box().byteValue() & 0xFF}
     *       決定落點(= Codec.BYTE 的數值強轉,向零截斷);<b>非數值或缺欄位一律回退預設 0、entry 不會被丟棄</b>
     *       (optional 欄位語意,已用真 codec 逐型別實測)。越界(>=size)= isValidInContainer 丟棄。</li>
     *   <li>佔用宣告(bit):slot 只要被任何 entry 指到就標佔用——即使那個 entry 其實會解碼失敗。
     *       「證明為空」只給完全沒有 entry 的格,方向絕對安全。</li>
     *   <li>滿判定:每格以「最後一個指到它的 entry」為準(vanilla {@code items.set} 後寫者勝)。
     *       只有該 entry 走完完整證明鏈(id 可解析非 air、count 為數值 intValue∈[1,99](缺=1)、
     *       components 缺席或只含合法的 minecraft:max_stack_size)才算「乾淨」,
     *       乾淨才能參與「全滿=1」的證明;乾淨且 count&lt;max 則可直接證明「不滿=0」。</li>
     * </ul>
     *
     * @return 打包值:高 32 位 = 佔用 bitmap、低 32 位 = 滿判定三態 +1(0/1/2 對應 -1/0/1);
     *         整份無法摘要時回 {@link #LAZYCONTAINER$SUMMARY_GIVEUP}。
     */
    public static long lazycontainer$computeSummary(Tag raw, int size) {
        if (!(raw instanceof ListTag) || size <= 0 || size > 32) {
            return LAZYCONTAINER$SUMMARY_GIVEUP;
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
            if (slotTag instanceof NumericTag) {
                // 必須用 box():vanilla 是 NbtOps.getNumberValue → Tag.asNumber() → box() → Codec.BYTE 的
                // Number.byteValue()(java.lang.Double/Float = 向零截斷)。NumericTag.byteValue() 對
                // Double/FloatTag 是 Mth.floor,負小數會與 vanilla 差 1 格 ⟹ 摘要漏標佔用 ⟹ 假「證明為空」。
                slot = ((NumericTag) slotTag).box().byteValue() & 0xFF;
            } else {
                // 缺欄位、或欄位存在但不是數值(字串/清單/compound/位元組陣列…):
                // Slot 用的是 optionalAlwaysPresentFieldOf(..., 預設 0),欄位解不出時
                // 一律回退預設值 0、**entry 不會被丟棄**(已用真 codec 實測逐型別確認)。
                // 早期版本在此 continue,導致 slot 0 被誤標成「證明為空」——差分測試抓到的真 bug。
                slot = 0;
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
        return ((long) hasBits << 32) | ((long) (tri + 1) & 0xFFFFFFFFL);
    }
}
