package io.github.kuohsuanlo.lazycontainer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.item.Item;

/**
 * headless NMS 測試共用前置(零 Minecraft server)。
 *
 * <p>做三件事,全部冪等(多個測試類各自呼叫也只做一次):</p>
 * <ol>
 *   <li>{@code SharedConstants.tryDetectVersion + Bootstrap.bootStrap}:註冊表就緒。</li>
 *   <li>物品 data component 綁定:26.2 的 component 平常由資料包重載管線綁,headless 起不動;
 *       這裡用公開的 {@link Holder.Reference#bindComponents} 綁一份合成 map(只含 max_stack_size),
 *       摘要與 vanilla 解碼兩邊吃同一份,差分依然成立。</li>
 *   <li>安裝一個「只有 registryAccess() 能用」的假 {@link MinecraftServer}:
 *       template 的 {@code ensure()} 走 {@code TagValueInput.createGlobal},它會呼叫
 *       {@code MinecraftServer.getServer().registryAccess()}(已用 javap 核對 26.2 bytecode);
 *       headless 下 {@code getServer()} 為 null ⟹ NPE。這裡以 {@code Unsafe.allocateInstance}
 *       配一個未初始化的 {@link DedicatedServer},只填 {@code registries} 欄位
 *       ({@link RegistryLayer#createRegistryAccess} 建的靜態層),再塞進 {@code MinecraftServer.SERVER}。
 *       只有 {@code registryAccess()} 會被碰到;其他方法沒人呼叫。</li>
 * </ol>
 */
final class NmsTestSupport {

    /** 測試用的堆疊上限;綁給所有物品,摘要與 vanilla 共用同一值。 */
    static final int MAX_STACK = 64;

    private static boolean done;
    private static RegistryAccess.Frozen registries;
    private static List<String> itemIds;

    private NmsTestSupport() {
    }

    static synchronized void bootstrap() {
        if (done) {
            return;
        }
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DataComponentMap synthetic = DataComponentMap.builder()
                .set(DataComponents.MAX_STACK_SIZE, MAX_STACK)
                .build();
        List<String> ids = new ArrayList<>();
        for (Holder.Reference<Item> h : BuiltInRegistries.ITEM.listElements().toList()) {
            try {
                h.bindComponents(synthetic);
            } catch (Throwable ignored) {
                // 已綁定過的重複綁定無所謂
            }
            ids.add(h.key().identifier().toString());
        }
        ids.remove("minecraft:air");
        itemIds = ids;
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        installFakeServer();
        done = true;
    }

    static RegistryAccess.Frozen registries() {
        return registries;
    }

    static List<String> itemIds() {
        return itemIds;
    }

    private static void installFakeServer() {
        if (MinecraftServer.getServer() != null) {
            return;
        }
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            DedicatedServer fake = (DedicatedServer) unsafe.allocateInstance(DedicatedServer.class);
            LayeredRegistryAccess<RegistryLayer> layered = RegistryLayer.createRegistryAccess();
            Field regs = MinecraftServer.class.getDeclaredField("registries");
            regs.setAccessible(true);
            regs.set(fake, layered);
            Field server = MinecraftServer.class.getDeclaredField("SERVER");
            server.setAccessible(true);
            server.set(null, fake);
            if (MinecraftServer.getServer().registryAccess() == null) {
                throw new IllegalStateException("fake server registryAccess() is null");
            }
        } catch (Exception e) {
            throw new IllegalStateException("無法安裝 headless 假 MinecraftServer(ensure() 需要 registryAccess)", e);
        }
    }
}
