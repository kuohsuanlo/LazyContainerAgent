package io.github.kuohsuanlo.lazycontainer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 純 JDK 執行期支援 + 公開狀態/計數器。掛在 bootstrap classloader。
 */
public final class LazyContainerRuntime {

    private LazyContainerRuntime() {}

    public static volatile boolean active = false;

    private static final boolean SHADOW = Boolean.getBoolean("lazycontainer.shadow");
    private static final boolean VERBOSE = Boolean.getBoolean("lazycontainer.verbose");
    private static final boolean DUMP = Boolean.getBoolean("lazycontainer.dump");

    // ── 計數器 ──
    public static final AtomicLong stash = new AtomicLong();
    public static final AtomicLong ensure = new AtomicLong();
    public static final AtomicLong rawSave = new AtomicLong();
    public static final AtomicLong eagerLoad = new AtomicLong();
    public static final AtomicLong shadowMismatch = new AtomicLong();
    public static final AtomicLong benignReorder = new AtomicLong();

    private static final AtomicLong benignLogN = new AtomicLong();
    private static final AtomicLong dumpN = new AtomicLong();
    private static final AtomicLong benignDumpN = new AtomicLong();

    public static boolean shadow() { return SHADOW; }
    public static boolean isActive() { return active; }

    public static void onStash() { stash.incrementAndGet(); }
    public static void onEnsure() { ensure.incrementAndGet(); }
    public static void onRawSave() { rawSave.incrementAndGet(); }
    public static void onEagerLoad() { eagerLoad.incrementAndGet(); }
    public static void onShadowMismatch() { shadowMismatch.incrementAndGet(); }

    /**
     * 良性重排:物品與槽位完全相同、只是 Items 清單順序不同。
     * 前 30 次印 log,之後僅累加。
     */
    public static void onBenignReorder(String pos, String rawSnbt, String eagerSnbt) {
        benignReorder.incrementAndGet();
        if (benignLogN.incrementAndGet() <= 30) {
            System.err.println("[LazyContainer] benign reorder @ " + pos
                    + " — same items & slots, list order only — NO IMPACT (raw kept). benignReorder=" + benignReorder.get());
        }
        dumpTo("lc-benign-", benignDumpN, pos, rawSnbt, eagerSnbt);
    }

    /** 真 mismatch 時 dump raw/eager SNBT。 */
    public static void dumpMismatch(String pos, String rawSnbt, String eagerSnbt) {
        dumpTo("lc-mismatch-", dumpN, pos, rawSnbt, eagerSnbt);
    }

    private static void dumpTo(String prefix, AtomicLong ctr, String pos, String rawSnbt, String eagerSnbt) {
        if (!DUMP) return;
        long n = ctr.incrementAndGet();
        if (n > 30) return;
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
                + " benignReorder=" + benignReorder.get()
                + " pending=" + ContainerHelperInterceptor.pendingCount();
    }

    public static void maybeStartVerboseLogger() {
        if (!VERBOSE) return;
        long ms = Long.getLong("lazycontainer.verbose.ms", 30_000L);
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(ms); } catch (InterruptedException e) { return; }
                System.out.println("[LazyContainer] " + stats()
                        + (shadow() ? " (SHADOW)" : "") + " active=" + active);
            }
        }, "LazyContainer-stats");
        t.setDaemon(true);
        t.start();
    }
}
