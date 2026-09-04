package io.github.kuohsuanlo.lazycontainer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 純 JDK 相依的執行期支援 + 公開狀態/計數器。
 *
 * <p><b>掛在 bootstrap classloader</b>(agent 會把整個 jar {@code appendToBootstrapClassLoaderSearch}),
 * 故被 splice 進 NMS {@code BaseContainerBlockEntity} 的方法(在 Paper 隔離 classloader)可透過 parent
 * 委派看到「同一份」本類別 —— 與 LibSetEntityTick 的 registry 模式相同。bootstrap 上的類別只能參照 JDK,
 * 因此這裡不可出現任何 {@code net.minecraft.*} / {@code org.bukkit.*}。</p>
 */
public final class LazyContainerRuntime {

    private LazyContainerRuntime() {
    }

    /** transformer 成功注入後設為 true。 */
    public static volatile boolean injected = false;

    /** 啟動參數 {@code -Dlazycontainer.shadow=true} 開啟 shadow 驗證(存檔逐位元組比對 eager,不一致寫 eager)。 */
    private static final boolean SHADOW = Boolean.getBoolean("lazycontainer.shadow");

    /** 啟動參數 {@code -Dlazycontainer.verbose=true} 開背景執行緒定期印計數。 */
    private static final boolean VERBOSE = Boolean.getBoolean("lazycontainer.verbose");

    // ── 觀測計數器(證明優化確實生效)──
    /** 載入時擷取未解碼 raw、跳過 decode 的容器次數。 */
    public static final AtomicLong stash = new AtomicLong();
    /** 首次被存取而觸發物化(真正 decode)的次數。 */
    public static final AtomicLong ensure = new AtomicLong();
    /** 卸載時逐位元組回寫 raw、跳過 encode 的次數。 */
    public static final AtomicLong rawSave = new AtomicLong();
    /** 因 input 非 TagValueInput 而退回 eager 載入的次數。 */
    public static final AtomicLong eagerLoad = new AtomicLong();
    /** shadow 模式偵測到 raw 與 eager 有「真正結構差異」(已改寫 eager)的次數。 */
    public static final AtomicLong shadowMismatch = new AtomicLong();

    /** 良性:raw 與 eager 只是 Items 清單順序不同、物品與槽位完全相同(已安全寫 raw,不算 mismatch)。 */
    public static final AtomicLong benignReorder = new AtomicLong();

    /**
     * {@code -Dlazycontainer.summary=false} 單獨關掉「摘要(ensure 快取)」,保留延遲解碼本體。
     * <p>獨立 kill switch:本 agent 已在 production,萬一摘要在真實地圖上冒出行為差異,
     * 服主可只關這一項、不必整包回滾掉已驗證的延遲解碼。</p>
     */
    private static final boolean SUMMARY = !"false".equalsIgnoreCase(System.getProperty("lazycontainer.summary"));

    /**
     * 摘要回答了漏斗的「整箱滿不滿」檢查的次數。
     * <p><b>注意語意</b>:這不等於「省下的解碼次數」——只有答「滿」才真的讓漏斗停手;
     * 答「不滿」只是省掉一圈掃描,後續 hopperPush 仍可能觸發物化。真正跳過解碼的是 {@link #summarySkip}。
     * 用 LongAdder:此計數在漏斗 tick 熱路徑上,regionized 平台多執行緒同時累加時避免 CAS 對撞。</p>
     */
    public static final java.util.concurrent.atomic.LongAdder summaryFull = new java.util.concurrent.atomic.LongAdder();

    /** 摘要證明「此格為空」而讓漏斗直接跳過該格的次數(真正跳過整箱解碼的那條路徑)。 */
    public static final java.util.concurrent.atomic.LongAdder summarySkip = new java.util.concurrent.atomic.LongAdder();

    /** 摘要建構「嘗試」次數(在入口計數;每個 pending 容器每次載入至多一次)。 */
    public static final java.util.concurrent.atomic.LongAdder summaryBuild = new java.util.concurrent.atomic.LongAdder();

    /** shadow 覆核抓到「摘要答案與真實 items 不符」的次數。<b>必須恆為 0</b>;>0 已自動棄答(退回原版路徑)。 */
    public static final java.util.concurrent.atomic.LongAdder summaryMismatch = new java.util.concurrent.atomic.LongAdder();

    public static boolean shadow() {
        return SHADOW;
    }

    public static boolean isActive() {
        return injected;
    }

    public static void onStash() {
        stash.incrementAndGet();
    }

    public static void onEnsure() {
        ensure.incrementAndGet();
    }

    public static void onRawSave() {
        rawSave.incrementAndGet();
    }

    public static void onEagerLoad() {
        eagerLoad.incrementAndGet();
    }

    public static void onShadowMismatch() {
        shadowMismatch.incrementAndGet();
    }

    public static boolean summary() {
        return SUMMARY;
    }

    public static void onSummaryFull() {
        summaryFull.increment();
    }

    public static void onSummarySkip() {
        summarySkip.increment();
    }

    public static void onSummaryBuild() {
        summaryBuild.increment();
    }

    private static final java.util.concurrent.atomic.AtomicInteger summaryLogN = new java.util.concurrent.atomic.AtomicInteger();

    /** shadow 覆核不符:計數 + 前 30 次印出細節(之後只累加,避免洗版)。呼叫端已改為棄答。 */
    public static void onSummaryMismatch(String detail) {
        summaryMismatch.increment();
        if (summaryLogN.incrementAndGet() <= 30) {
            System.err.println("[LazyContainer] SUMMARY mismatch — " + detail
                    + " — falling back to vanilla path (safe)");
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger benignLogN = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 良性重排:物品與槽位完全相同、只是 Items 清單順序不同(已安全寫 raw)。
     * 仍「偵測並回報」——印出座標,但明確標示 <b>NO IMPACT</b>;前 30 次印 log,之後僅累加計數避免洗版。
     * dump 開啟時另存 {@code lc-benign-N} 供比對確認。
     */
    public static void onBenignReorder(String pos, String rawSnbt, String eagerSnbt) {
        long c = benignReorder.incrementAndGet();
        if (benignLogN.incrementAndGet() <= 30) {
            System.err.println("[LazyContainer] benign reorder @ " + pos
                    + " — same items & slots, list order only — NO IMPACT (raw kept). benignReorder=" + c);
        }
        dumpTo("lc-benign-", benignDumpN, pos, rawSnbt, eagerSnbt);
    }

    // ── ensure 歸因(交付 #223 未結②:冷機谷/殘餘物化到底是誰觸發的)──────────────────────
    //
    // ensure() 每個容器每次載入至多發生一次(不在 tick 熱路徑),所以在物化當下抓一次
    // stack trace(~10µs)把觸發者分桶,常駐開著也量不到成本;-Dlazycontainer.attribution=false 可關。

    /** 啟動參數 {@code -Dlazycontainer.attribution=false} 關閉 ensure 觸發者歸因(預設開)。 */
    private static final boolean ATTRIBUTION = !"false".equalsIgnoreCase(System.getProperty("lazycontainer.attribution"));

    public static final java.util.concurrent.atomic.LongAdder attrHopper = new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder attrComparator = new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder attrPlayer = new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder attrQuickshop = new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder attrSave = new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder attrDrop = new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder attrVanilla = new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder attrPlugin = new java.util.concurrent.atomic.LongAdder();

    public static boolean attribution() {
        return ATTRIBUTION;
    }

    // ── 摘要「滿判定」的回答分佈:逐格解碼值不值得做的判準 ────────────────────────────
    //
    // 漏斗往外推之前先問 isFullContainer;那裡掛了摘要快查,答得出來就零解碼。它會落到整箱解碼
    // 只有一種情況:摘要不肯答。「為什麼不肯答」決定下一步——
    //   fullUnknownDirty(佔滿但有格證明不了:物品帶自訂 component / 未知 id 等)⟹ 放寬證明規則,零解碼
    //   fullAnsweredNotFull(證明不滿)⟹ 漏斗接著真的要推入,那是必要解碼,逐格解碼才有用
    //   fullGaveUp(整份放棄:非 ListTag / 非數值 Slot / 摘要關閉)⟹ 看放棄的是什麼
    // 只計 pending 容器(已物化的不經摘要,也不會解碼)。

    /** 摘要證明「全滿」的次數(漏斗停手,零解碼)。 */
    public static final java.util.concurrent.atomic.LongAdder fullAnsweredFull = new java.util.concurrent.atomic.LongAdder();
    /** 摘要證明「不滿」的次數(漏斗會推入 ⟹ 隨後整箱解碼,屬必要工作)。 */
    public static final java.util.concurrent.atomic.LongAdder fullAnsweredNotFull = new java.util.concurrent.atomic.LongAdder();
    /** 每格都有 entry、但有格證明不了(count/max 算不出:自訂 component、未知物品…)⟹ 答不知道 ⟹ 整箱解碼。 */
    public static final java.util.concurrent.atomic.LongAdder fullUnknownDirty = new java.util.concurrent.atomic.LongAdder();
    /** 摘要整份放棄(sumState!=1)⟹ 答不知道 ⟹ 整箱解碼。 */
    public static final java.util.concurrent.atomic.LongAdder fullGaveUp = new java.util.concurrent.atomic.LongAdder();

    /**
     * template 的 fullState() 在 pending 容器上被問時呼叫。
     *
     * @param sumState 0=未建 1=有效 2=放棄
     * @param tri      sumState==1 時的三態:1 滿 / 0 不滿 / -1 不知道(其餘忽略)
     */
    public static void onFullQuery(int sumState, int tri) {
        if (sumState != 1) {
            fullGaveUp.increment();
        } else if (tri == 1) {
            fullAnsweredFull.increment();
        } else if (tri == 0) {
            fullAnsweredNotFull.increment();
        } else {
            fullUnknownDirty.increment();
        }
    }

    // ── raw passthrough(#261):chunk 存檔時未物化容器的 Items 直接寫 bytes,不再 bytes→樹→bytes ──
    //
    // 機制:transformer 在 net.minecraft.nbt.CompoundTag 加兩個欄位(lazycontainer$rawKey / lazycontainer$rawBytes)
    // 並在 CompoundTag.write(DataOutput) 開頭注入「rawBytes!=null ⟹ 先把它寫成一個具名 entry」;
    // LevelChunk.getBlockEntityNbtForSaving 被包成 try/finally 設 per-thread 旗標,只有這條(chunk 存檔)
    // 路徑會掛 raw——其他呼叫者(/data、structure、getState、封包)照舊解析成樹,因為它們會「讀」那棵樹。
    // -Dlazycontainer.passthrough=false 整條關閉(transformer 不改 CompoundTag/LevelChunk,template 走舊路)。

    private static final boolean PASSTHROUGH = !"false".equalsIgnoreCase(System.getProperty("lazycontainer.passthrough"));

    public static boolean passthrough() {
        return PASSTHROUGH;
    }

    /** 走 passthrough 的存檔次數(rawSave 的子集)。 */
    public static final java.util.concurrent.atomic.LongAdder rawPassthrough = new java.util.concurrent.atomic.LongAdder();

    public static void onRawPassthrough() {
        rawPassthrough.increment();
    }

    /** per-thread 巢狀深度:>0 表示此執行緒正在 LevelChunk.getBlockEntityNbtForSaving 內(chunk 存檔)。 */
    private static final ThreadLocal<int[]> CHUNK_SAVE_DEPTH = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };

    public static void enterChunkSave() {
        CHUNK_SAVE_DEPTH.get()[0]++;
    }

    public static void exitChunkSave() {
        int[] d = CHUNK_SAVE_DEPTH.get();
        if (d[0] > 0) {
            d[0]--;
        }
    }

    public static boolean inChunkSave() {
        return CHUNK_SAVE_DEPTH.get()[0] > 0;
    }

    /** raw 是 {@code NbtIo.writeAnyTag} 框架([typeId][payload]);ListTag 的 typeId = 9。 */
    public static boolean rawIsListTag(byte[] raw) {
        return raw != null && raw.length >= 6 && raw[0] == 9;
    }

    /** ListTag payload = [elemType byte][int32 length]…;不解析就能判空(shulker 的 allowEmpty=false 要用)。 */
    public static boolean rawListIsEmpty(byte[] raw) {
        if (!rawIsListTag(raw)) {
            return false;
        }
        int n = ((raw[2] & 0xFF) << 24) | ((raw[3] & 0xFF) << 16) | ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
        return n == 0;
    }

    /**
     * 注入進 CompoundTag.write 開頭:把 raw 寫成一個具名 entry。
     * 格式與 CompoundTag.writeNamedTag 逐位元組相同:[typeId][modified-UTF name][payload];
     * raw[0] 就是 typeId、raw[1..] 就是 payload(writeAnyTag 框架)。typeId==0(END)不寫——那是 compound 的結尾符。
     */
    public static void writeRawEntry(String key, byte[] raw, java.io.DataOutput out) throws java.io.IOException {
        if (raw == null || raw.length == 0 || raw[0] == 0) {
            return;
        }
        out.writeByte(raw[0]);
        out.writeUTF(key);
        out.write(raw, 1, raw.length - 1);
    }

    private static volatile java.lang.reflect.Field RAW_KEY_FIELD;
    private static volatile java.lang.reflect.Field RAW_BYTES_FIELD;
    private static volatile boolean RAW_FIELDS_MISSING;

    /**
     * 把 raw 掛到存檔輸出的 CompoundTag 上(template 不能直接參照 splice 進 CompoundTag 的欄位——
     * 它是對未改寫的 NMS 編譯的——所以由 bootstrap 這邊用反射,欄位物件快取一次)。
     * 回傳 false = CompoundTag 沒被改寫(passthrough 關閉/transform 失敗)⟹ 呼叫端走舊的解析路徑。
     */
    public static boolean attachRaw(Object compoundTag, String key, byte[] raw) {
        if (RAW_FIELDS_MISSING || compoundTag == null) {
            return false;
        }
        try {
            java.lang.reflect.Field fk = RAW_KEY_FIELD;
            java.lang.reflect.Field fb = RAW_BYTES_FIELD;
            if (fk == null || fb == null) {
                Class<?> c = compoundTag.getClass();
                fk = c.getField("lazycontainer$rawKey");
                fb = c.getField("lazycontainer$rawBytes");
                RAW_KEY_FIELD = fk;
                RAW_BYTES_FIELD = fb;
            }
            fk.set(compoundTag, key);
            fb.set(compoundTag, raw);
            return true;
        } catch (NoSuchFieldException e) {
            RAW_FIELDS_MISSING = true;
            System.err.println("[LazyContainer] passthrough unavailable: CompoundTag not transformed — falling back to parse path");
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── passthrough 寫入前自檢 + passthrough shadow ─────────────────────────────────────
    //
    // 服主的最壞情況=框架寫錯 ⟹ 該 chunk 整份讀不回來。直寫的框架是固定三條指令,跟資料無關;唯一跟資料相關的是
    // raw 陣列本身。這裡在「真的把 raw 掛上輸出」之前做一次零配置的 NBT 走訪:確認 raw 剛好是一個完整的 ListTag、
    // 長度分毫不差、字串是合法的 modified-UTF-8。走訪只算長度、不建樹、不跑 codec。任何不合法 ⟹ 退回舊的解析路徑
    // (那條會真的用 NbtIo 解,解不開就 ensure 走正常 encode,不掉資料)。走訪「比 vanilla 嚴格」只會多退回,無害;
    // 「比 vanilla 寬鬆」才危險,所以每條規則都取 vanilla 讀取端的同款或更嚴的版本。

    /** {@code -Dlazycontainer.passthrough.shadow=true}:磁碟照舊寫解析出的樹(=26.2-2),直寫結果只寫進暫存緩衝、讀回來比對。 */
    private static final boolean PASSTHROUGH_SHADOW = Boolean.getBoolean("lazycontainer.passthrough.shadow");

    public static boolean passthroughShadow() {
        return PASSTHROUGH_SHADOW;
    }

    /** 自檢拒絕(raw 不是一個完整合法的 ListTag)⟹ 走解析路徑;正常應恆為 0。 */
    public static final java.util.concurrent.atomic.LongAdder rawWalkReject = new java.util.concurrent.atomic.LongAdder();
    /** passthrough shadow:模擬直寫 → 讀回 → 與解析樹結構相等。 */
    public static final java.util.concurrent.atomic.LongAdder ptShadowOk = new java.util.concurrent.atomic.LongAdder();
    /** passthrough shadow:讀回結果與解析樹不同(磁碟寫的是樹,安全);正常應恆為 0。 */
    public static final java.util.concurrent.atomic.LongAdder ptShadowMismatch = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.AtomicInteger rawWalkLogN = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger ptShadowLogN = new java.util.concurrent.atomic.AtomicInteger();

    /** 走訪次數與耗時(每份 raw 只走一次,結果快取在 BE 的 lazycontainer$rawOk)。 */
    public static final java.util.concurrent.atomic.LongAdder rawWalk = new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder rawWalkNanos = new java.util.concurrent.atomic.LongAdder();
    public static final AtomicLong rawWalkMaxNanos = new AtomicLong();
    /** shadow:raw 太大而跳過完整讀回。 */
    public static final java.util.concurrent.atomic.LongAdder ptShadowSkipped = new java.util.concurrent.atomic.LongAdder();
    /** shadow:CompoundTag 未被改寫(真模式同樣不會啟用)。 */
    public static final java.util.concurrent.atomic.LongAdder ptShadowUnavailable = new java.util.concurrent.atomic.LongAdder();
    /** raw 連 vanilla 讀取端都拒收(絕不寫進 chunk);正常應恆為 0。 */
    public static final java.util.concurrent.atomic.LongAdder badRaw = new java.util.concurrent.atomic.LongAdder();

    /** shadow 完整讀回的大小上限(超過只計數):避免在 tick 執行緒上為單一巨型容器多做一次完整解析。 */
    public static final int PT_SHADOW_MAX_BYTES = Integer.getInteger("lazycontainer.passthrough.shadow.maxBytes", 512 * 1024);
    /** shadow 讀回的 accounter 預算(有界:框架若錯位,壞掉的長度欄位可能要求配置數 GB)。 */
    public static final long PT_SHADOW_READ_BUDGET = 64L << 20;

    public static void onRawWalk(long nanos) {
        rawWalk.increment();
        rawWalkNanos.add(nanos);
        long prev = rawWalkMaxNanos.get();
        while (nanos > prev && !rawWalkMaxNanos.compareAndSet(prev, nanos)) {
            prev = rawWalkMaxNanos.get();
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger badRawDumpN = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * raw 被判定為「vanilla 讀取端不會接受」——這份 bytes 絕不能寫進 chunk(會讓整個 chunk 讀不回來)。
     * 落檔保存原始 bytes(給 tools/mca_restore.py 用)+ 印座標;呼叫端負責讓該容器改走 vanilla encode。
     * 現實上不可達:raw 是我們自己從一棵已解析的樹 encode 出來的。
     */
    public static void onBadRaw(String pos, byte[] raw, String why) {
        badRaw.increment();
        rawWalkReject.increment();
        if (rawWalkLogN.incrementAndGet() <= 30) {
            System.err.println("[LazyContainer] BAD RAW @ " + pos + " (" + (raw == null ? 0 : raw.length)
                    + " bytes) — " + why + " — this container falls back to vanilla encode; bytes dumped for recovery");
        }
        if (raw == null || badRawDumpN.incrementAndGet() > 30) {
            return;
        }
        try {
            String safe = pos.replaceAll("[^0-9A-Za-z_-]+", "_");
            java.io.File f = new java.io.File(System.getProperty("lazycontainer.dump.dir", "."),
                    "lc-badraw-" + safe + "-" + badRawDumpN.get() + ".bin");
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            try {
                os.write(raw);
            } finally {
                os.close();
            }
            System.err.println("[LazyContainer] BAD RAW bytes written to " + f.getAbsolutePath());
        } catch (Throwable t) {
            System.err.println("[LazyContainer] BAD RAW dump failed: " + t);
        }
    }

    public static void onPassthroughShadowSkipped(String pos, int len) {
        ptShadowSkipped.increment();
        if (ptShadowLogN.incrementAndGet() <= 30) {
            System.err.println("[LazyContainer] passthrough shadow skipped (raw " + len + " bytes > "
                    + PT_SHADOW_MAX_BYTES + ") @ " + pos);
        }
    }

    public static void onPassthroughShadowUnavailable() {
        ptShadowUnavailable.increment();
    }

    public static void onPassthroughShadow(boolean ok, String pos, String detail) {
        if (ok) {
            ptShadowOk.increment();
            return;
        }
        ptShadowMismatch.increment();
        if (ptShadowLogN.incrementAndGet() <= 30) {
            System.err.println("[LazyContainer] PASSTHROUGH shadow MISMATCH @ " + pos + " — " + detail
                    + " — disk got the parsed tree (safe)");
        }
    }

    /** 走訪深度上限:vanilla NbtAccounter 是 512;這裡取更嚴的 500(嚴格方向只會多退回)。 */
    private static final int WALK_MAX_DEPTH = 500;

    /**
     * 零配置 NBT 走訪:raw 必須剛好是「一個完整的 ListTag」({@code NbtIo.writeAnyTag} 框架 [9][elemType][int32 n][elements]),
     * 走完的 offset 必須等於 {@code raw.length}(不多不少)。規則逐條對齊 vanilla 讀取端(ListTag/CompoundTag 的 load、
     * DataInputStream.readUTF)或更嚴:
     * <ul>
     *   <li>型別碼 1..12;compound 內 0 = END。非空清單的 elemType 為 0 或 &gt;12 ⟹ 拒(vanilla:0 丟 "Missing type on ListTag",
     *       &gt;12 在讀第一個元素時丟例外);空清單(n==0)不看 elemType(vanilla 也不讀任何元素)。</li>
     *   <li>int32 長度(byte/int/long 陣列、清單)為負 ⟹ 拒(vanilla 會丟 NegativeArraySize/accounter 例外)。</li>
     *   <li>字串:uint16 長度 + modified-UTF-8,逐位元組驗 {@code readUTF} 的規則(單 byte 0x01..0x7F;0xC0..0xDF + 1 續;
     *       0xE0..0xEF + 2 續;續位元組必為 10xxxxxx;其餘一律拒)。</li>
     *   <li>巢狀深度 &gt; 500 ⟹ 拒。</li>
     * </ul>
     */
    public static boolean rawWellFormedList(byte[] raw) {
        if (raw == null || raw.length < 6 || raw[0] != 9) {
            return false;
        }
        try {
            long end = walkPayload(raw, 1L, 9, 1);
            return end == raw.length;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 回傳 payload 結束後的 offset;任何不合法 ⟹ -1。所有 offset 用 long 算,避免 int32 長度相加溢位。 */
    private static long walkPayload(byte[] b, long off, int type, int depth) {
        if (off < 0 || off > b.length) {
            return -1;
        }
        switch (type) {
            case 1:
                return fixed(b, off, 1);
            case 2:
                return fixed(b, off, 2);
            case 3:
            case 5:
                return fixed(b, off, 4);
            case 4:
            case 6:
                return fixed(b, off, 8);
            // vanilla 對 byte[] 與 int[] 另有 Preconditions.checkArgument(len < 16777216)(2^24),long[] 沒有——
            // 這是規則集裡唯一「走訪可能比 vanilla 寬鬆」的地方,必須補上,否則走訪接受的 bytes 讀取端會拒。
            case 7:
                return sized(b, off, 1L, 1 << 24);
            case 11:
                return sized(b, off, 4L, 1 << 24);
            case 12:
                return sized(b, off, 8L, Integer.MAX_VALUE);
            case 8:
                return string(b, off);
            case 9: {
                if (depth > WALK_MAX_DEPTH) {
                    return -1;
                }
                if (off + 5 > b.length) {
                    return -1;
                }
                int et = b[(int) off] & 0xFF;
                long n = int32(b, off + 1);
                if (n < 0) {
                    return -1;
                }
                long p = off + 5;
                if (n == 0) {
                    return p;
                }
                if (et == 0 || et > 12) {
                    return -1;
                }
                for (long i = 0; i < n; i++) {
                    p = walkPayload(b, p, et, depth + 1);
                    if (p < 0) {
                        return -1;
                    }
                }
                return p;
            }
            case 10: {
                if (depth > WALK_MAX_DEPTH) {
                    return -1;
                }
                long p = off;
                while (true) {
                    if (p >= b.length) {
                        return -1;
                    }
                    int t = b[(int) p] & 0xFF;
                    p++;
                    if (t == 0) {
                        return p;
                    }
                    if (t > 12) {
                        return -1;
                    }
                    p = string(b, p);
                    if (p < 0) {
                        return -1;
                    }
                    p = walkPayload(b, p, t, depth + 1);
                    if (p < 0) {
                        return -1;
                    }
                }
            }
            default:
                return -1;
        }
    }

    private static long fixed(byte[] b, long off, int size) {
        long e = off + size;
        return e <= b.length ? e : -1;
    }

    /** [int32 n][n × elem];n 為負或 ≥ maxLen ⟹ 拒(maxLen 對齊 vanilla 的 checkArgument)。 */
    private static long sized(byte[] b, long off, long elem, long maxLen) {
        long n = int32(b, off);
        if (n < 0 || n >= maxLen) {
            return -1;
        }
        long e = off + 4 + n * elem;
        return e <= b.length ? e : -1;
    }

    /** 回傳 int32(signed);讀不到 ⟹ -1(呼叫端把負值一律當不合法)。 */
    private static long int32(byte[] b, long off) {
        if (off + 4 > b.length) {
            return -1;
        }
        int i = (int) off;
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    /** [uint16 n][n bytes modified-UTF-8],逐位元組驗 readUTF 規則。 */
    private static long string(byte[] b, long off) {
        if (off + 2 > b.length) {
            return -1;
        }
        int n = ((b[(int) off] & 0xFF) << 8) | (b[(int) off + 1] & 0xFF);
        long s = off + 2;
        long e = s + n;
        if (e > b.length) {
            return -1;
        }
        int i = (int) s;
        int end = (int) e;
        while (i < end) {
            int c = b[i] & 0xFF;
            if (c >= 0x01 && c <= 0x7F) {
                i++;
            } else if (c >= 0xC0 && c <= 0xDF) {
                if (i + 1 >= end || (b[i + 1] & 0xC0) != 0x80) {
                    return -1;
                }
                i += 2;
            } else if (c >= 0xE0 && c <= 0xEF) {
                if (i + 2 >= end || (b[i + 1] & 0xC0) != 0x80 || (b[i + 2] & 0xC0) != 0x80) {
                    return -1;
                }
                i += 3;
            } else {
                return -1;   // 0x00、0x80..0xBF、0xF0..0xFF:readUTF 一律丟 UTFDataFormatException
            }
        }
        return e;
    }

    // ── 解碼耗時(交付 #223 未結①:冷機谷「還剩多少」要的是秒數,不是次數)──
    /** 物化解碼累計耗時(奈秒)。 */
    public static final java.util.concurrent.atomic.LongAdder decodeNanos = new java.util.concurrent.atomic.LongAdder();
    /** 單次最慢物化(奈秒)。 */
    public static final AtomicLong decodeMaxNanos = new AtomicLong();

    // ── 解碼耗時分佈:回答「尖峰多常發生」──────────────────────────────────────────
    //
    // 總量早就不是瓶頸(艦隊實測 4 台 6 小時合計只花 372 秒解碼,一台平均 0.4% 的單執行緒)。
    // 真正會被玩家感覺到的是「單次數百毫秒」——那是一個 region 當場凍結半秒。
    // 慢解碼點名有 30 筆上限,印完就沒了,看不出頻率;這組桶補的正是那個缺口:
    // 若 100ms+ 一天只有幾次 ⟹ 不值得為它動危險的存檔路徑;若每分鐘都在發生 ⟹ 才談逐格解碼。

    /** 解碼 &lt;1ms 的次數。 */
    public static final java.util.concurrent.atomic.LongAdder decodeLt1ms = new java.util.concurrent.atomic.LongAdder();
    /** 解碼 1–10ms 的次數。 */
    public static final java.util.concurrent.atomic.LongAdder decode1to10ms = new java.util.concurrent.atomic.LongAdder();
    /** 解碼 10–100ms 的次數。 */
    public static final java.util.concurrent.atomic.LongAdder decode10to100ms = new java.util.concurrent.atomic.LongAdder();
    /** 解碼 &gt;=100ms(尖峰)的次數。 */
    public static final java.util.concurrent.atomic.LongAdder decodeGe100ms = new java.util.concurrent.atomic.LongAdder();
    /** 尖峰桶的總耗時(奈秒)——用來直接算「把尖峰全部消掉能省多少」。 */
    public static final java.util.concurrent.atomic.LongAdder decodeGe100Nanos = new java.util.concurrent.atomic.LongAdder();

    /** 把一次解碼耗時歸進四個桶之一;nanos&lt;=0(不計時的呼叫形式)不進桶,避免灌水。 */
    private static void histogram(long nanos) {
        if (nanos <= 0) {
            return;
        }
        if (nanos < 1_000_000L) {
            decodeLt1ms.increment();
        } else if (nanos < 10_000_000L) {
            decode1to10ms.increment();
        } else if (nanos < 100_000_000L) {
            decode10to100ms.increment();
        } else {
            decodeGe100ms.increment();
            decodeGe100Nanos.add(nanos);
        }
    }

    /** 慢解碼門檻(毫秒),{@code -Dlazycontainer.slowMs=} 可調;≤0 關閉慢解碼點名。 */
    private static final long SLOW_NANOS = Long.getLong("lazycontainer.slowMs", 100L) * 1_000_000L;

    /** template 用:是否需要為這次物化準備座標字串(只有慢的才值得建字串)。 */
    public static boolean slowDecode(long nanos) {
        return SLOW_NANOS > 0 && nanos >= SLOW_NANOS;
    }

    private static final java.util.concurrent.atomic.AtomicInteger slowLogN = new java.util.concurrent.atomic.AtomicInteger();

    /** 慢解碼點名:印出座標、觸發者桶與耗時(上限 30 筆)——冷機谷的元凶容器會自己現形。 */
    private static void onSlowDecode(String pos, String bucket, long nanos) {
        if (slowLogN.incrementAndGet() <= 30) {
            System.out.println("[LazyContainer] slow decode " + (nanos / 1_000_000L) + "ms @ " + pos
                    + " trigger=" + bucket);
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger attrSampleN = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.Set<String> attrSeen = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** template 的 ensure() 在真正物化時呼叫:分桶計數;plugin/vanilla 桶另印前 30 個未見過的觸發點。 */
    public static void onEnsureAttributed(StackTraceElement[] st) {
        onEnsureAttributed(st, 0L, null);
    }

    /**
     * 分桶 + 記錄解碼耗時。
     *
     * @param nanos 本次物化解碼耗時(奈秒);0 表示不計時
     * @param slowPos 本次若超過慢門檻,由呼叫端備好的座標字串(否則 null——不建字串不浪費)
     */
    public static void onEnsureAttributed(StackTraceElement[] st, long nanos, String slowPos) {
        if (nanos > 0) {
            decodeNanos.add(nanos);
            histogram(nanos);
            long prev = decodeMaxNanos.get();
            while (nanos > prev && !decodeMaxNanos.compareAndSet(prev, nanos)) {
                prev = decodeMaxNanos.get();
            }
        }
        try {
            String bucket = classifyEnsure(st);
            if (slowPos != null) {
                onSlowDecode(slowPos, bucket, nanos);
            }
            switch (bucket) {
                case "hopper" -> attrHopper.increment();
                case "comparator" -> attrComparator.increment();
                case "quickshop" -> attrQuickshop.increment();
                case "save" -> attrSave.increment();
                case "drop" -> attrDrop.increment();
                case "plugin" -> {
                    attrPlugin.increment();
                    sampleAttr(st, "plugin", false);
                }
                case "player" -> {
                    attrPlayer.increment();
                    // 鏈上若有外掛 frame(程式化代開:CraftHumanEntity#openInventory),把外掛名印出來,
                    // 桶仍記 player——營運才分得出「自然玩家」與「外掛代開」(審查 low)
                    sampleAttr(st, "player", false);
                }
                default -> {
                    attrVanilla.increment();
                    sampleAttr(st, "vanilla", true);
                }
            }
        } catch (Throwable ignored) {
            // 歸因純觀測,任何失敗都不得影響物化
        }
    }

    /**
     * 把「實際觸發者 frame」印出來(去重、上限 30),供下一輪補分類規則。
     * <p>上限先於 {@code attrSeen.add} 檢查——否則超限後集合仍無限收新 sig
     * (外掛熱重載的 hidden class 名每次不同,會慢性膨脹;審查 low)。</p>
     *
     * @param fallbackToInner 鏈上沒有非平台 frame 時,是否退而印鏈內側的平台 frame
     *                        (vanilla 桶要 true——26.2 破壞鏈就是這樣抓到的;
     *                        player 桶要 false——自然玩家開箱不用印,只印外掛代開)
     */
    private static void sampleAttr(StackTraceElement[] st, String bucket, boolean fallbackToInner) {
        if (attrSampleN.get() >= 30) {
            return;
        }
        StackTraceElement actor = firstNonPlatformFrame(st);
        if (actor == null) {
            if (!fallbackToInner || st.length == 0) {
                return;
            }
            actor = st[Math.min(2, st.length - 1)];
        }
        String sig = actor.getClassName() + "#" + actor.getMethodName();
        if (attrSeen.add(sig) && attrSampleN.incrementAndGet() <= 30) {
            System.out.println("[LazyContainer] ensure-attr " + bucket + " sample: " + sig);
        }
    }

    /**
     * 把一條 ensure 物化的呼叫堆疊分類成觸發者桶。純函式,可獨立測試。
     *
     * <p>先跳過自家 {@code lazycontainer$*} frame,再依「具體優先」掃整條鏈:
     * quickshop(一中即回)→ save(但鏈上有外掛 frame 時歸 plugin——getState 快照的發起者是外掛)
     * → hopper → comparator → drop → player;都不中時,鏈上第一個非平台套件的 frame 定
     * plugin,否則 vanilla。類名比對限定 {@code net.minecraft.} 前綴,外掛的 *Hopper* 類不會
     * 被誤吃進 hopper 桶。</p>
     *
     * <p><b>player 桶語意</b>:玩家「面前開啟」的物化——含外掛經 {@code CraftHumanEntity#openInventory}
     * 程式化代開(此時鏈上的外掛名會由 sample 機制印出,桶仍記 player)。</p>
     *
     * @return {@code "hopper"|"comparator"|"player"|"quickshop"|"save"|"drop"|"plugin"|"vanilla"}
     */
    public static String classifyEnsure(StackTraceElement[] st) {
        boolean saveFallback = false;
        boolean hopper = false;
        boolean comparator = false;
        boolean drop = false;
        boolean player = false;
        for (StackTraceElement e : st) {
            String cls = e.getClassName();
            String m = e.getMethodName();
            if (m.startsWith("lazycontainer$")) {
                if (m.startsWith("lazycontainer$trySaveRaw")) {
                    saveFallback = true;        // 存檔後備物化——但不能提前 return:
                }                               // getState() 快照鏈的外層可能是外掛(見末端仲裁)
                continue;                       // 其餘自家 frame 跳過
            }
            // 注意:容器類(BCE/leaf)自己的 frame「不能」整類跳過——26.2 的破壞鏈第一觸點是
            // BaseContainerBlockEntity#collectImplicitComponents(rig 實測抓到),整類跳過會漏分類。
            // 守門 frame(getItems/getContents/getItem)本來就不會命中任何桶規則,留著無害。
            String lower = cls.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("quickshop")) {
                return "quickshop";             // 最具體:發起者是 QuickShop,就算鏈上也有漏斗
            }
            // 類名比對一律限定 net.minecraft. 前綴——EpicHoppers/WildStacker 這類外掛的
            // *Hopper* 類若被吃進 hopper 桶,外掛觸發者就隱形了(審查 medium)
            boolean mc = cls.startsWith("net.minecraft.");
            if (mc && cls.contains("Hopper")) {
                hopper = true;                  // HopperBlockEntity / MinecartHopper / HopperBlock
            } else if ((mc && cls.contains("ComparatorBlock"))
                    || m.equals("getAnalogOutputSignal") || m.equals("getRedstoneSignalFromBlockEntity")) {
                comparator = true;
            } else if (cls.equals("net.minecraft.world.Containers") || m.equals("dropContents")
                    || m.equals("collectImplicitComponents") || m.equals("preRemoveSideEffects")) {
                // 後兩者 = 26.2 破壞/轉掉落物路徑(方塊→物品的 component 收集),rig 實測的第一觸點
                drop = true;
            } else if (m.equals("useWithoutItem") || m.equals("useItemOn")
                    || m.equals("openMenu") || m.equals("openInventory")) {
                player = true;
            }
        }
        // 末端仲裁:save 鏈上有外掛 frame ⇒ 觸發者是那個外掛(getState 快照),不是 chunk 存檔機器
        if (saveFallback) {
            return firstNonPlatformFrame(st) != null ? "plugin" : "save";
        }
        if (hopper) {
            return "hopper";
        }
        if (comparator) {
            return "comparator";
        }
        if (drop) {
            return "drop";
        }
        if (player) {
            return "player";
        }
        return firstNonPlatformFrame(st) != null ? "plugin" : "vanilla";
    }

    /** 鏈上第一個「不是平台(JDK/NMS/Bukkit/Paper)也不是自家」的 frame = 外掛觸發者;沒有則 null。 */
    private static StackTraceElement firstNonPlatformFrame(StackTraceElement[] st) {
        for (StackTraceElement e : st) {
            String cls = e.getClassName();
            if (cls.startsWith("net.minecraft.") || cls.startsWith("com.mojang.")
                    || cls.startsWith("org.bukkit.") || cls.startsWith("io.papermc.")
                    || cls.startsWith("org.spigotmc.") || cls.startsWith("ca.spottedleaf.")
                    || cls.startsWith("java.") || cls.startsWith("jdk.")
                    || cls.startsWith("io.github.kuohsuanlo.lazycontainer")) {
                continue;
            }
            return e;
        }
        return null;
    }

    private static final boolean DUMP = Boolean.getBoolean("lazycontainer.dump");
    private static final java.util.concurrent.atomic.AtomicInteger dumpN = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger benignDumpN = new java.util.concurrent.atomic.AtomicInteger();

    /** {@code -Dlazycontainer.dump=true} 時把前 30 次「真 mismatch」的 raw / eager SNBT 各落一檔,供離線逐欄位 diff。 */
    public static void dumpMismatch(String pos, String rawSnbt, String eagerSnbt) {
        dumpTo("lc-mismatch-", dumpN, pos, rawSnbt, eagerSnbt);
    }

    private static void dumpTo(String prefix, java.util.concurrent.atomic.AtomicInteger ctr, String pos, String rawSnbt, String eagerSnbt) {
        if (!DUMP) {
            return;
        }
        int n = ctr.incrementAndGet();
        if (n > 30) {
            return;
        }
        try {
            String dir = System.getProperty("lazycontainer.dump.dir", ".");
            String safe = pos.replaceAll("[^0-9A-Za-z_-]", "_");
            java.nio.file.Files.writeString(java.nio.file.Path.of(dir, prefix + n + "-" + safe + ".raw.snbt"), rawSnbt);
            java.nio.file.Files.writeString(java.nio.file.Path.of(dir, prefix + n + "-" + safe + ".eager.snbt"), eagerSnbt);
            System.err.println("[LazyContainer] dumped " + prefix + n + " (" + pos + ") → " + dir + "/" + prefix + n + "-*.snbt");
        } catch (Throwable t) {
            System.err.println("[LazyContainer] dump failed: " + t);
        }
    }

    public static String stats() {
        return "stash=" + stash.get()
                + " ensure=" + ensure.get()
                + " rawSave=" + rawSave.get()
                + " rawPassthrough=" + rawPassthrough.sum()
                + " rawWalk=" + rawWalk.sum()
                + " rawWalkMaxMs=" + (rawWalkMaxNanos.get() / 1_000_000L)
                + " badRaw=" + badRaw.sum()
                + (PASSTHROUGH_SHADOW
                        ? " ptShadowOk=" + ptShadowOk.sum() + " ptShadowMismatch=" + ptShadowMismatch.sum()
                            + " ptShadowSkipped=" + ptShadowSkipped.sum()
                            + " ptShadowUnavailable=" + ptShadowUnavailable.sum()
                        : "")
                + " eagerLoad=" + eagerLoad.get()
                + " summaryFull=" + summaryFull.sum()
                + " summarySkip=" + summarySkip.sum()
                + " summaryBuild=" + summaryBuild.sum()
                + " summaryMismatch=" + summaryMismatch.sum()
                + " shadowMismatch=" + shadowMismatch.get()
                + " benignReorder=" + benignReorder.get()
                // 關閉時印明確標記而非八個 0——值班的人才分得出「功能關著」與「歸因掛了」(審查 low)
                + (ATTRIBUTION
                        ? " attrHopper=" + attrHopper.sum()
                            + " attrComparator=" + attrComparator.sum()
                            + " attrPlayer=" + attrPlayer.sum()
                            + " attrQuickshop=" + attrQuickshop.sum()
                            + " attrSave=" + attrSave.sum()
                            + " attrDrop=" + attrDrop.sum()
                            + " attrVanilla=" + attrVanilla.sum()
                            + " attrPlugin=" + attrPlugin.sum()
                            + " decodeMs=" + (decodeNanos.sum() / 1_000_000L)
                            + " decodeMaxMs=" + (decodeMaxNanos.get() / 1_000_000L)
                            // 分佈:<1ms/1-10ms/10-100ms/100ms+,末位括號=尖峰桶總耗時
                            + " decodeHist=" + decodeLt1ms.sum() + "/" + decode1to10ms.sum()
                                + "/" + decode10to100ms.sum() + "/" + decodeGe100ms.sum()
                            + " decodeSpikeMs=" + (decodeGe100Nanos.sum() / 1_000_000L)
                            // 滿判定回答分佈:證明滿/證明不滿/佔滿但證明不了/整份放棄
                            + " fullQ=" + fullAnsweredFull.sum() + "/" + fullAnsweredNotFull.sum()
                                + "/" + fullUnknownDirty.sum() + "/" + fullGaveUp.sum()
                        : " attribution=off");
    }

    /**
     * premain 呼叫:若 verbose 則開一條 daemon 每 30s 印一次計數(僅供測試觀測)。
     * <p>必須 public:premain 的 AgentMain 在 app loader 執行,本類別在 bootstrap loader,
     * 跨 loader 呼叫 package-private 會 IllegalAccessError。</p>
     */
    public static void maybeStartVerboseLogger() {
        if (!VERBOSE) {
            return;
        }
        long ms = Long.getLong("lazycontainer.verbose.ms", 30_000L);
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    return;
                }
                System.out.println("[LazyContainer] " + stats()
                        + (shadow() ? " (SHADOW)" : "") + " active=" + injected);
            }
        }, "LazyContainer-stats");
        t.setDaemon(true);
        t.start();
    }
}
