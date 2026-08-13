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
                + " benignReorder=" + benignReorder.get();
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
