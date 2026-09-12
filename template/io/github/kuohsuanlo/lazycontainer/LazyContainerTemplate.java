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
 *       {@link #lazycontainer$ensure()} 把 raw 解碼進清單,<b>填完</b>才把 pending 設為 false、raw 設為 null
 *       (raw 立即作廢,永不再被寫回)。</li>
 *   <li><b>跨執行緒鐵律(26.2-2)</b>:{@code pending} 是 volatile;任何執行緒只要讀到 {@code pending==false},
 *       清單就已經是完整的(happens-before 證明見 {@link #lazycontainer$ensure()})。載入、物化、存檔、
 *       整批替換四條會碰 pending/raw 的路徑全部在 {@code this} monitor 內,彼此序列化;
 *       未持鎖的讀者(leaf guard、漏斗摘要查詢)只讀 volatile 旗標,永遠不會拿到「旗標已清、清單半填」的狀態。</li>
 *   <li>存檔時(monitor 內):pending 且 raw!=null 且 output 是 TagValueOutput ⟹ 把 raw 解回 Tag 塞進 output
 *       (每次都是全新的私有樹,結構性杜絕「存檔輸出與活容器共用同一棵樹」的別名家族);
 *       寫進磁碟的 Items 與讀進來的<b>結構相等</b>(NBT Tag.equals;compound 內 key 順序可能因 Paper 的
 *       fastutil CompoundTag 雜湊迭代序而不同,vanilla 重存亦然——不是資料變更)。否則 ⟹ 先物化再正常 encode。
 *       兩種結果都是「完整」的:要嘛原 raw、要嘛物化後的完整清單,絕不是子集。最壞只是少省一次,絕不掉資料。</li>
 *   <li>摘要(sumState/sumBits/sumFullTri)在載入當下「趁樹還在手上」eager 建好——
 *       漏斗的十億級查詢永遠不需要 parse bytes。摘要欄位在 {@code pending=true}(volatile 寫)之前寫入,
 *       讀到 pending=true 的查詢端必看得到完整摘要。</li>
 * </ul>
 */
public abstract class LazyContainerTemplate extends BaseContainerBlockEntity {

    /**
     * true = items 尚未物化,內容在 {@link #lazycontainer$raw}。預設 false ⟹ 非目標容器行為完全不變。
     * <p><b>volatile</b>(26.2-2):leaf guard 在任何執行緒上都可能讀它(EndRod PIW 允許非擁有 region 的插件
     * 執行緒讀活體容器),而 {@link #lazycontainer$ensure()} 只在清單填完後才寫 false——volatile 寫/讀成對
     * 構成 happens-before,讀到 false 的執行緒必看得到完整清單。已物化容器的熱路徑只多一個 volatile 讀
     * (x86 上就是普通 load,零額外指令;ARM 為 ldar)。</p>
     */
    public volatile boolean lazycontainer$pending;

    /**
     * 正在執行 {@link #lazycontainer$ensure()} 物化的執行緒;null = 沒有人在物化。
     * <p>用途只有一個:<b>重入偵測</b>。ensure 在 monitor 內呼叫 {@code this.getItems()} 取得清單,
     * 而 leaf 的 {@code getItems()} 入口有 guard({@code if (pending) ensure();})——此時 pending 仍為 true
     * (填完才翻),guard 會再進 ensure;monitor 可重入,若不擋就是無限遞迴。ensure 入口看到
     * {@code ensuring == Thread.currentThread()} 就直接返回,讓 getItems 把清單交出來給 loadAllItems 填。</p>
     * <p>只在持有 {@code this} monitor 時寫入(進入時設為當前執行緒、finally 清 null)。入口處的未持鎖讀是安全的:
     * 值為 T 的寫入只可能出自執行緒 T 自己,T 的讀必看見自己程式序上最後一次寫(進 ensure 前必為 null 或他人),
     * 因此絕不會有「T 沒在物化卻讀到 ensuring==T」的誤判。</p>
     */
    public Thread lazycontainer$ensuring;

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
     * <p><b>synchronized + 寫入順序</b>(26.2-2):chunk 載入時 BE 尚未對外可見,但 {@code /data merge}、
     * {@code loadWithComponents} 會對<b>活體</b> BE 重跑 loadAdditional;持 monitor 讓它與在途的 ensure/存檔序列化。
     * 欄位寫入順序固定為 raw → 摘要 → <b>最後</b> {@code pending=true}(volatile 寫):未持鎖的查詢端
     * (漏斗摘要 hook)一旦讀到 pending=true,依 happens-before 必看得到 raw 與完整摘要。</p>
     */
    public synchronized void lazycontainer$load(ValueInput input, NonNullList<ItemStack> items) {
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
            // 摘要 eager 建置:樹此刻還在(免 parse);查詢端不再 lazy build(sumState==0 一律當「不知道」)
            if (LazyContainerRuntime.summary()) {
                long packed;
                try {
                    packed = lazycontainer$computeSummary(itemsTag, this.getContainerSize());
                } catch (Throwable t) {
                    // 摘要是純觀測,任何例外(例如 Holder components 尚未綁定的 NPE)都不得逃出 load():
                    // 逃出去的話 chunk 載入路徑會讓 BlockEntity.loadStatic 丟掉整個 BE、活體 reload 路徑會在
                    // 下次存檔寫出空清單 ⟹ 整箱從磁碟消失。這裡一律當「整份放棄」,raw 照存、行為=不用摘要。
                    packed = LAZYCONTAINER$SUMMARY_GIVEUP;
                }
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
            this.lazycontainer$pending = true;  // volatile 寫,必須是最後一步(發佈 raw + 摘要)
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
     * 首次被任何存取點觸發時,把暫存的 raw 解碼進真正的 items 清單。<b>填完再翻旗標</b>(26.2-2)。
     *
     * <h4>為什麼舊版「先清旗標再填」在 EndRod 上不是「既有設計邊界」</h4>
     * <p>Paper 單主緒下載入/tick/存檔全在同一條執行緒,旗標順序無所謂。EndRod 的 PIW(R39)明文允許非擁有
     * region 的插件執行緒讀活體容器:paper-server {@code CraftInventory.getItem/getContents} 先呼叫 NMS
     * {@code getItem/getContents} 再做跨區快照,{@code Level.getBlockEntity} 對 off-region 執行緒也回傳活體 BE,
     * 因此 leaf guard 與本方法的整段解碼會在插件執行緒上跑。舊版翻旗標在前:插件執行緒剛翻完、
     * 擁有 region 執行緒的 guard 讀到 false 就直接拿「填到一半的清單」去做漏斗推入/抽出、破壞掉落、
     * autosave 編碼——結果是掉物/複製/磁碟殘缺。單執行緒時全部 SAFE,所以只有 EndRod 會踩到。</p>
     *
     * <h4>正確性證明(JMM)</h4>
     * <ul>
     *   <li>寫者 W(持 monitor):逐格填清單(普通寫)→ {@code raw=null} → {@code pending=false}(volatile 寫)→ 釋放 monitor。</li>
     *   <li>未持鎖讀者 R(leaf guard)讀 volatile {@code pending}:
     *     <ul>
     *       <li>讀到 <b>false</b>:該 volatile 讀與 W 的 volatile 寫同步,W 在寫 false 之前的所有填格
     *           對 R 皆可見(happens-before)⟹ R 拿到的是完整清單。</li>
     *       <li>讀到 <b>true</b>:R 進本方法 → 在 monitor 上等 W → 取得 monitor 後二次檢查看到 false → 返回;
     *           monitor 釋放/取得同樣構成 happens-before ⟹ R 之後讀清單也是完整的。R 只是「等待」,絕不會拿到半填。</li>
     *     </ul>
     *   </li>
     *   <li>失敗路徑:解碼拋出時 pending 從未被翻、raw 仍在,任何讀者仍看到 pending=true → 下次存取重試;
     *       存檔在 monitor 內看到 pending 且 raw!=null → 寫回完整的原 raw。永不靜默丟失。</li>
     *   <li>重入:W 在 monitor 內呼叫 {@code this.getItems()},leaf guard 看到 pending=true 會再進本方法;
     *       {@link #lazycontainer$ensuring} 記錄「W 正在物化」,入口比對到自己就直接返回,getItems 把清單交出來。</li>
     *   <li>與 {@link #lazycontainer$clear()}(setItems 入口)交錯:clear 也在 monitor 內,只能發生在 W 之前或之後。
     *       之前 ⟹ W 二次檢查看到 false 直接返回;之後 ⟹ W 填的是舊清單、leaf 隨後換上新清單並清旗標,
     *       最終狀態 = 新清單、pending=false、raw=null,與 vanilla 的 setItems 結果相同(setItems 贏)。</li>
     * </ul>
     * <p>成本:已物化容器的熱路徑只有 guard 的一個 volatile 讀,不進本方法;本方法每容器每次載入至多執行一次。</p>
     */
    public void lazycontainer$ensure() {
        if (!this.lazycontainer$pending) {
            return;                                     // volatile 讀:false ⟹ 清單已完整(見上證明)
        }
        if (this.lazycontainer$ensuring == Thread.currentThread()) {
            return;                                     // 重入:自己正在 monitor 內填清單(loadAllItems→getItems→guard→此處)
        }
        synchronized (this) {
            if (!this.lazycontainer$pending) {
                return;                                 // 等待期間已被別的執行緒物化(或 setItems 清掉)
            }
            this.lazycontainer$ensuring = Thread.currentThread();
            try {
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
                    Tag raw;
                    try {
                        raw = lazycontainer$decodeRaw(rawBytes);    // bytes → Tag(自家 encode 的往返,失敗即拋)
                    } catch (java.io.IOException io) {
                        // decode 自家 bytes 失敗(現實上不可達):pending 未翻、raw 仍在,下次重試,絕不靜默丟失
                        throw new IllegalStateException("lazycontainer raw decode failed", io);
                    }
                    CompoundTag tmp = new CompoundTag();
                    tmp.put("Items", raw);
                    ValueInput vi = TagValueInput.createGlobal(ProblemReporter.DISCARDING, tmp);
                    // 依 slot set,冪等 → 中途拋出時 pending 仍 true、raw 仍在,下次存取重試
                    ContainerHelper.loadAllItems(vi, this.getItems());
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
                } else {
                    this.lazycontainer$raw = null;
                }
                this.lazycontainer$pending = false;     // volatile 寫:清單已完整,才對所有執行緒發佈
            } finally {
                this.lazycontainer$ensuring = null;
            }
        }
    }

    /**
     * {@code setItems()} 入口(transformer 的 GUARD_CLEAR)呼叫:整批替換清單 ⟹ lazy 狀態作廢。
     * <p>synchronized(26.2-2):與在途的 {@link #lazycontainer$ensure()} 序列化。若不持鎖,可能發生
     * 「setItems 清了旗標、leaf 換上新清單,而 ensure 接著呼叫 getItems() 拿到<b>新</b>清單、把舊 raw 解進去」
     * ——新清單被舊內容覆蓋,與 vanilla 不同。持鎖後 clear 只能在 ensure 之前或之後整個發生,
     * 最終狀態恆為「新清單、pending=false、raw=null」= vanilla 的 setItems 結果。setItems 極少被呼叫,不在熱路徑。</p>
     */
    public synchronized void lazycontainer$clear() {
        this.lazycontainer$pending = false;
        this.lazycontainer$raw = null;
    }

    // ── 存檔 redirect 目標(取代 leaf saveAdditional 內的 ContainerHelper.saveAllItems 呼叫)──
    //
    // 兩個入口都是 synchronized(26.2-2):整段「讀 pending → 讀 raw → 寫 raw 或編碼清單」在 monitor 內,
    // 與在途的 ensure 序列化。結果只有兩種、都是完整的:
    //   (a) pending 且 raw!=null ⟹ 原樣寫 raw bytes(不可變、完整);
    //   (b) 已物化(或 ensure 剛在 monitor 內完成)⟹ vanilla 編碼完整清單。
    // 舊版在鎖外讀 pending 後再讀 raw,ensure 中途存檔會把「半填清單」編碼寫盤(autosave 對純讀不弄髒的 chunk
    // 之後不再重寫 ⟹ 磁碟永久殘缺)。存檔本來就不是每 tick 熱路徑,多一次無競爭 monitor 進出可忽略。

    /** 取代 {@code ContainerHelper.saveAllItems(output, items)}(allowEmpty=true:chest/barrel)。 */
    public synchronized void lazycontainer$save(ValueOutput output, NonNullList<ItemStack> items) {
        if (this.lazycontainer$trySaveRaw(output, true)) {
            return;
        }
        ContainerHelper.saveAllItems(output, items);
    }

    /** 取代 {@code ContainerHelper.saveAllItems(output, items, false)}(allowEmpty=false:shulker)。 */
    public synchronized void lazycontainer$saveNoEmpty(ValueOutput output, NonNullList<ItemStack> items) {
        if (this.lazycontainer$trySaveRaw(output, false)) {
            return;
        }
        ContainerHelper.saveAllItems(output, items, false);
    }

    /**
     * 若容器自載入後從未物化(pending)且可安全回寫,就把原始 "Items" tag 原樣塞回 output(結構相等;compound 內 key 順序可能因 Paper CompoundTag 雜湊序而異),回傳 true
     * (呼叫端跳過 encode)。否則先物化(ensure)再回傳 false(呼叫端走正常 encode)。
     * <p><b>呼叫端必須持有 {@code this} monitor</b>(兩個 save 入口皆 synchronized):pending 與 raw 的讀取
     * 只有在鎖內才是一致的快照。</p>
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
        // 對所有輸入(含外部/損毀 NBT)結構上對齊 vanilla(Tag.equals),且不掉物。
        boolean canWriteRaw = (raw instanceof ListTag)
                && !(!allowEmpty && ((ListTag) raw).isEmpty());
        if (!canWriteRaw) {
            this.lazycontainer$ensure();
            return false;
        }
        CompoundTag out = ((TagValueOutput) output).buildResult();
        if (LazyContainerRuntime.shadow()) {
            // shadow 是「純觀測」模式:偵測並回報 raw 與 vanilla 重新編碼的差異,但**絕不改寫玩家資料**。
            // 設計原則(服主要求):寫回磁碟的必須是讀進來的那份原始資料,不做任何正規化(count:1 等明確
            // 預設值一律保留),不論語意是否等價。位元組層面唯一的差異來源是 Paper CompoundTag 的雜湊迭代序
            // (key 順序),那不是資料——見 SummaryDifferentialTest.rawBytesRoundTrip 的三條斷言。
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
        if (!this.lazycontainer$pending || this.lazycontainer$lootPending()) {
            return -1;                          // 非 pending:不經摘要也不會解碼;loot:刻意不答(見 lootPending)
        }
        int st = this.lazycontainer$sumState;
        int tri = (st == 1) ? this.lazycontainer$sumFullTri : -1;
        if (LazyContainerRuntime.attribution()) {
            LazyContainerRuntime.onFullQuery(st, tri);   // 「為什麼不肯答」的分佈:逐格解碼值不值得做的判準
        }
        return tri;
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
     *       決定落點(= Codec.BYTE 的數值強轉,向零截斷);<b>缺欄位</b>回退預設 0、entry 保留(optional 欄位語意,
     *       差分測試以真 codec 確認);<b>欄位存在但非數值</b>(字串/清單/compound/陣列)⟹ <b>整份棄答</b>(26.2-2 / A2):
     *       DFU 對「欄位在但解不出」的 partial 語意是「保留 entry、Slot 回退 0」還是「整個 entry 丟掉」,
     *       兩家稽核結論相反(本機 Paper 26.2 build-40 實測為前者),而兩種語意下該 entry 都<b>不得</b>被標成
     *       乾淨滿堆——否則 26 格滿 + 一個壞 Slot 的滿堆會被證成「全滿」,漏斗永不推入、容器永不物化、
     *       raw 原樣回寫讓壞 entry 永久存在。棄答只是少省一次,與版本無關地安全。越界(>=size)= isValidInContainer 丟棄。</li>
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
            if (slotTag == null) {
                // 缺欄位:Slot 是 optional 欄位、預設 0,entry 保留(差分測試以真 codec 確認:26 格滿 + 缺 Slot
                // 的滿堆 ⟹ vanilla 判全滿)。早期版本在此 continue,導致 slot 0 被誤標成「證明為空」——真 bug。
                slot = 0;
            } else if (slotTag instanceof NumericTag) {
                // 必須用 box():vanilla 是 NbtOps.getNumberValue → Tag.asNumber() → box() → Codec.BYTE 的
                // Number.byteValue()(java.lang.Double/Float = 向零截斷)。NumericTag.byteValue() 對
                // Double/FloatTag 是 Mth.floor,負小數會與 vanilla 差 1 格 ⟹ 摘要漏標佔用 ⟹ 假「證明為空」。
                slot = ((NumericTag) slotTag).box().byteValue() & 0xFF;
            } else {
                // 欄位存在但不是數值(字串/清單/compound/位元組陣列…):整份棄答(26.2-2 / A2)。
                // 舊版把它當 slot 0 且可標 clean+滿 ⟹ 假「全滿」。DFU partial 語意(保留回退 0 / 丟棄 entry)
                // 版本間可能不同,棄答在兩種語意下都安全;代價只是這個(本來就壞的)容器少省一次。
                return LAZYCONTAINER$SUMMARY_GIVEUP;
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
                // 26.2 + DFU 10.0.21 真 codec 實測(ComponentPartialSemanticsTest 釘住,版本升級若變會先紅):
                // components 是「逐 component 各自 partial」——壞的被逐個丟掉、好的留著、物品永遠在;
                // 整個 components 欄位非 compound 也只是整包被丟。所以除了 max_stack_size 之外的任何
                // component(合法或壞)都不影響「格子有無物品」與「max」。舊規則對帶任何 component 的
                // entry 一律不敢證明滿,s3 商場掃描 12,754 個全滿箱有 76.3% 因此被迫整箱解碼。
                if (compsTag instanceof CompoundTag) {
                    CompoundTag comps = (CompoundTag) compsTag;
                    // key 的解析要跟 vanilla 同一支:DataComponentPatch.PatchKey.CODEC 先剝 "!" 再 Identifier.tryParse
                    // ⟹ 裸 max_stack_size / :max_stack_size 都會正規化成 minecraft:max_stack_size 而生效;
                    // 大寫命名空間/尾端空白 tryParse 失敗 ⟹ 被丟(真 codec 實測 NsProbe / ComponentPartialSemanticsTest)。
                    int hits = 0;
                    boolean removal = false;
                    Tag maxTag = null;
                    for (String key : comps.keySet()) {
                        String k = key;
                        boolean rem = false;
                        if (k.startsWith("!")) {
                            rem = true;
                            k = k.substring(1);
                        }
                        Identifier cid = Identifier.tryParse(k);
                        if (cid != null && "minecraft".equals(cid.getNamespace()) && "max_stack_size".equals(cid.getPath())) {
                            hits++;
                            if (rem) {
                                removal = true;
                            } else {
                                maxTag = comps.get(key);
                            }
                        }
                    }
                    if (hits >= 2 || removal) {
                        // 重複拼法:勝者由 fastutil map 迭代序決定(實測兩種插入序都取同一個),外部不可複現;
                        // 移除記號:值為 compound 才生效、值為 Int 被丟,且與設定並存時順序曖昧 ⟹ 一律不證明
                        break body;
                    }
                    if (maxTag instanceof NumericTag) {
                        int m = ((NumericTag) maxTag).box().intValue();
                        if (m >= 1 && m <= 99) {
                            max = m;                                 // 合法 ⟹ vanilla 用它(壞 sibling 也不影響,實測)
                        }
                        // 超範圍 ⟹ 該 component 被丟 ⟹ 物品預設 max(實測)
                    }
                    // 非數值 ⟹ 被丟 ⟹ 物品預設 max(實測);其他任何 component 一律不影響格子與 max
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
