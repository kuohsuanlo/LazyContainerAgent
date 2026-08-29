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
