package io.github.kuohsuanlo.lazycontainer;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * Java agent 進入點。以 {@code -javaagent:LazyContainerAgent.jar} 啟動 server。
 *
 * <p>Paper(paperclip)用隔離 classloader 載入 {@code net.minecraft.*},看不到 -javaagent 所在的 app
 * classloader。因此先把本 jar(純 JDK + relocated ASM)以
 * {@link Instrumentation#appendToBootstrapClassLoaderSearch} 掛到 bootstrap classloader(所有
 * classloader 的共同祖先),讓被 splice 進 NMS {@code BaseContainerBlockEntity} 的方法能透過 parent
 * 委派看到同一份 {@link LazyContainerRuntime};再掛上 {@link LazyContainerTransformer}。</p>
 *
 * <p>骨架對齊 LibSetEntityTick。</p>
 */
public final class LazyContainerAgentMain {

    /** 作者署名(無傷大雅)。 */
    public static final String AUTHOR = "廢土貓大 LogoCat";
    public static final String SITE = "mcfallout.net";

    /**
     * jar manifest 的 {@code Implementation-Version}(= pom {@code <version>})。
     * 開機 banner 印出來,讓 log 就能斷定「這台掛的是哪一版 agent」——26.2-2 的兩個正確性修正
     * 沒有任何外顯行為差異,不印版本就無從在事後從 log 分辨。取不到時為 "unknown"(絕不影響啟動)。
     */
    private static String version() {
        try {
            String v = LazyContainerAgentMain.class.getPackage().getImplementationVersion();
            return v == null ? "unknown" : v;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private LazyContainerAgentMain() {
    }

    public static void premain(String args, Instrumentation inst) {
        signature();
        bootstrap(inst);
        install(inst);
        LazyContainerRuntime.maybeStartVerboseLogger();
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> System.out.println("[LazyContainer] shutdown stats: " + LazyContainerRuntime.stats()),
                "LazyContainer-shutdown-stats"));
    }

    public static void agentmain(String args, Instrumentation inst) {
        signature();
        bootstrap(inst);
        install(inst);
        LazyContainerRuntime.maybeStartVerboseLogger();
        retransformIfLoaded(inst);
    }

    /**
     * 作者署名(無傷大雅,純記名、不影響功能):
     * <ol>
     *   <li>開機 banner——一行作者/伺服器資訊。</li>
     *   <li>system property {@code lazycontainer.author}——可被 jcmd / 任何工具讀到。</li>
     *   <li>一條永遠休眠的 daemon thread,名字帶署名——會出現在 spark / thread dump 的執行緒清單,
     *       讓 profile 也留得到名(休眠不佔 CPU)。</li>
     * </ol>
     */
    private static void signature() {
        try {
            System.setProperty("lazycontainer.author", AUTHOR + " (" + SITE + ")");
            System.out.println("[LazyContainer] LazyContainerAgent " + version()
                    + " —— crafted by " + AUTHOR + " · 廢土 · " + SITE);
            Thread sig = new Thread(() -> {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "LazyContainer-signature · " + AUTHOR + " · " + SITE);
            sig.setDaemon(true);
            sig.start();
        } catch (Throwable ignored) {
            // 署名失敗絕不影響 agent 運作
        }
    }

    /** 把本 agent jar 掛到 bootstrap classloader,讓被注入的 NMS 類找得到 LazyContainerRuntime。 */
    private static void bootstrap(Instrumentation inst) {
        try {
            File self = new File(
                    LazyContainerAgentMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            inst.appendToBootstrapClassLoaderSearch(new JarFile(self));
            System.out.println("[LazyContainer] appended to bootstrap classpath: " + self);
        } catch (Throwable t) {
            System.err.println("[LazyContainer] FATAL: appendToBootstrapClassLoaderSearch failed: " + t);
            t.printStackTrace();
        }
    }

    private static void install(Instrumentation inst) {
        LazyContainerTransformer t = new LazyContainerTransformer();
        boolean ready = t.prepare();    // premain 就把 template 讀好:leaf/hopper 閘門看的是它,不再賭類載入順序
        inst.addTransformer(t, true);
        System.out.println("[LazyContainer] agent installed (transformer registered, template="
                + (ready ? "ready" : "MISSING") + ")"
                + (LazyContainerRuntime.shadow() ? " [SHADOW mode]" : ""));
    }

    /** agentmain(動態 attach)時:目標 NMS 類可能已載入,逐一 retransform。 */
    private static void retransformIfLoaded(Instrumentation inst) {
        if (!inst.isRetransformClassesSupported()) {
            return;
        }
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String n = c.getName();
            if (n.equals("net.minecraft.world.level.block.entity.BaseContainerBlockEntity")
                    || n.equals("net.minecraft.world.level.block.entity.ChestBlockEntity")
                    || n.equals("net.minecraft.world.level.block.entity.BarrelBlockEntity")
                    || n.equals("net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity")
                    || n.equals("net.minecraft.world.level.block.entity.HopperBlockEntity")) {
                try {
                    inst.retransformClasses(c);
                } catch (Throwable t) {
                    System.err.println("[LazyContainer] retransform failed for " + n + ": " + t);
                }
            }
        }
    }
}
