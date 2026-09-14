package io.github.kuohsuanlo.lazycontainer;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric 進入點。原 Paper 版是 -javaagent + ASM splice;Fabric 版改用 Mixin,
 * 邏輯(延遲反序列化 / ensure-on-access / 原樣寫回 / shadow 驗證)逐一對齊原版。
 *
 * <p>原作:廢土貓大 LogoCat · 廢土 · mcfallout.net
 * (https://github.com/kuohsuanlo/LazyContainerAgent)</p>
 */
public final class LazyContainerMod implements ModInitializer {

    public static final String AUTHOR = "廢土貓大 LogoCat";
    public static final String SITE = "mcfallout.net";

    @Override
    public void onInitialize() {
        // Mixin 在類別載入時即已套用;走到這裡代表 mixin config 沒有炸(defaultRequire=1,失敗會直接 crash = 安全停機)
        LazyContainerRuntime.injected = true;
        System.setProperty("lazycontainer.author", AUTHOR + " (" + SITE + ")");
        System.out.println("[LazyContainer] LazyContainer (Fabric) —— ported from LazyContainerAgent, crafted by "
                + AUTHOR + " · 廢土 · " + SITE);
        System.out.println("[LazyContainer] mixins applied"
                + (LazyContainerRuntime.shadow() ? " [SHADOW mode]" : " [performance mode]"));
        LazyContainerRuntime.maybeStartVerboseLogger();
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> System.out.println("[LazyContainer] shutdown stats: " + LazyContainerRuntime.stats()),
                "LazyContainer-shutdown-stats"));
    }
}
