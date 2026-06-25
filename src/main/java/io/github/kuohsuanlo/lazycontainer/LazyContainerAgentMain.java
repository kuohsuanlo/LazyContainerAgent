package io.github.kuohsuanlo.lazycontainer;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * v2 Java agent 進入點。
 *
 * <p>支援 premain (啟動時掛載) 與 agentmain (動態 attach) 兩種模式。</p>
 *
 * <pre>
 * Phase 3: DetachManager 整合。
 *   agentmain 支援 args:
 *     "detach"   — flush pending + unregister + retransform 還原
 *     "reload"   — flush pending + re-init (重新載入 transformer)
 *     無 args    — 正常 attach
 * </pre>
 */
public final class LazyContainerAgentMain {

    private static LazyContainerTransformer transformer;

    private LazyContainerAgentMain() {}

    public static void premain(String args, Instrumentation inst) {
        banner();
        if (!initVersionAndTransformer()) return;
        appendToBootstrap(inst);
        registerTransformer(inst);
        LazyContainerRuntime.maybeStartVerboseLogger();
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> System.out.println("[LazyContainer] final stats: " + LazyContainerRuntime.stats()),
                "LazyContainer-shutdown"));
    }

    public static void agentmain(String args, Instrumentation inst) {
        banner();
        if (args != null && args.equals("detach")) {
            DetachManager.detach(inst);
            return;
        }
        if (args != null && args.equals("reload")) {
            DetachManager.deactivate();
            // re-init
            transformer = new LazyContainerTransformer();
            transformer.init();
            if (!transformer.isReady()) {
                System.err.println("[LazyContainer] reload failed: version not supported");
                return;
            }
            appendToBootstrap(inst);
            registerTransformer(inst);
            retransformIfLoaded(inst);
            System.out.println("[LazyContainer] agent reloaded");
            return;
        }
        // normal attach
        if (!initVersionAndTransformer()) return;
        appendToBootstrap(inst);
        registerTransformer(inst);
        LazyContainerRuntime.maybeStartVerboseLogger();
        retransformIfLoaded(inst);
    }

    // ── 內部實作 ──

    private static void banner() {
        System.out.println("[LazyContainer] LazyContainerAgent v2 — crafted by 廢土貓大 LogoCat · mcfallout.net");
    }

    /** 版本偵測 + transformer 初始化。失敗時 agent 不啟動。 */
    private static boolean initVersionAndTransformer() {
        VersionDetector.McVersion v = VersionDetector.detect();
        if (v == VersionDetector.McVersion.UNKNOWN) {
            System.err.println("[LazyContainer] FATAL: could not detect MC version; agent DISABLED");
            return false;
        }
        if (NmsRegistry.forVersion(v) == null) {
            String reason;
            if (v == VersionDetector.McVersion.V1_12_2) {
                reason = "ContainerHelper class does not exist in 1.12.2 (introduced in 1.13). "
                       + "This agent architecture depends on ContainerHelper interception.";
            } else {
                reason = "no NMS mapping registered for this version.";
            }
            System.err.println("[LazyContainer] FATAL: MC version " + v + " not supported — " + reason);
            System.err.println("[LazyContainer]        use -Dlazycontainer.version=1.xx.x to override,"
                    + " or add mapping to NmsRegistry.java");
            return false;
        }
        transformer = new LazyContainerTransformer();
        transformer.init();
        return true;
    }

    private static void appendToBootstrap(Instrumentation inst) {
        try {
            File self = new File(
                    LazyContainerAgentMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            inst.appendToBootstrapClassLoaderSearch(new JarFile(self));
        } catch (Throwable t) {
            System.err.println("[LazyContainer] FATAL: appendToBootstrapClassLoaderSearch failed: " + t);
        }
    }

    private static void registerTransformer(Instrumentation inst) {
        inst.addTransformer(transformer, true);
        ContainerHelperInterceptor.active = true;
        LazyContainerRuntime.injected = true;
        DetachManager.registeredTransformer = transformer;
        System.out.println("[LazyContainer] transformer registered"
                + (LazyContainerRuntime.shadow() ? " [SHADOW mode]" : ""));
    }

    private static void retransformIfLoaded(Instrumentation inst) {
        if (!inst.isRetransformClassesSupported()) return;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String n = c.getName();
            if (n.equals("net.minecraft.world.ContainerHelper")
                    || n.equals("net.minecraft.world.level.block.entity.ChestBlockEntity")
                    || n.equals("net.minecraft.world.level.block.entity.BarrelBlockEntity")
                    || n.equals("net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity")) {
                try {
                    inst.retransformClasses(c);
                    System.out.println("[LazyContainer] retransformed " + n);
                } catch (Throwable t) {
                    System.err.println("[LazyContainer] retransform failed for " + n + ": " + t);
                }
            }
        }
    }
}
