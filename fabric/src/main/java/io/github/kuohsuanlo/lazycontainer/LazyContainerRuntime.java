package io.github.kuohsuanlo.lazycontainer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 純 JDK 相依的執行期支援 + 公開狀態/計數器。
 *
 * <p>移植自 LazyContainerAgent(Paper 版)。Fabric 版以 Mixin 注入,不需 bootstrap
 * classloader 掛載,但沿用同一套 system property 與計數器,方便對照原版文件與監控。</p>
 */
public final class LazyContainerRuntime {

    private LazyContainerRuntime() {
    }

    /** Mixin 套用且 mod 初始化完成後設為 true。 */
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
    /** 因 input 非 TagValueInput 而退回 eager 載入的次數(1.21.6+ 才可能發生)。 */
    public static final AtomicLong eagerLoad = new AtomicLong();
    /** shadow 模式偵測到 raw 與 eager 有「真正結構差異」(已改寫 eager)的次數。 */
    public static final AtomicLong shadowMismatch = new AtomicLong();
    /** 良性:raw 與 eager 只是 Items 清單順序不同、物品與槽位完全相同(已安全寫 raw,不算 mismatch)。 */
    public static final AtomicLong benignReorder = new AtomicLong();

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

    private static final AtomicInteger benignLogN = new AtomicInteger();

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
    private static final AtomicInteger dumpN = new AtomicInteger();
    private static final AtomicInteger benignDumpN = new AtomicInteger();

    /** {@code -Dlazycontainer.dump=true} 時把前 30 次「真 mismatch」的 raw / eager SNBT 各落一檔,供離線逐欄位 diff。 */
    public static void dumpMismatch(String pos, String rawSnbt, String eagerSnbt) {
        dumpTo("lc-mismatch-", dumpN, pos, rawSnbt, eagerSnbt);
    }

    private static void dumpTo(String prefix, AtomicInteger ctr, String pos, String rawSnbt, String eagerSnbt) {
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
                + " shadowMismatch=" + shadowMismatch.get()
                + " benignReorder=" + benignReorder.get();
    }

    /** mod 初始化時呼叫:若 verbose 則開一條 daemon 定期印一次計數(僅供觀測)。 */
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
