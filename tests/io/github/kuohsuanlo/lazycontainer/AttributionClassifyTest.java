package io.github.kuohsuanlo.lazycontainer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ensure 歸因分類器(交付 #223 未結②)的純 JDK 測試——零 Minecraft server。
 *
 * <p>餵入十種「真實呼叫鏈形狀」的合成 StackTraceElement 陣列,驗證
 * {@link LazyContainerRuntime#classifyEnsure(StackTraceElement[])} 把物化觸發者分進正確的桶。
 * 堆疊形狀取自 Paper 26.2 mojmap 反編譯源碼的實際呼叫鏈(HopperBlockEntity 拉取、
 * ComparatorBlock 讀訊號、Containers.dropContents 破壞掉落、ChestBlock.useWithoutItem 玩家開箱),
 * 外掛側取自 QuickShop-Reloaded 實際套件名。</p>
 */
class AttributionClassifyTest {

    /** 縮寫:合成一個 stack frame。 */
    private static StackTraceElement f(String cls, String method) {
        return new StackTraceElement(cls, method, cls.substring(cls.lastIndexOf('.') + 1) + ".java", 1);
    }

    private static final StackTraceElement SELF_ENSURE =
            f("net.minecraft.world.level.block.entity.BaseContainerBlockEntity", "lazycontainer$ensure");
    private static final StackTraceElement SELF_GETITEMS =
            f("net.minecraft.world.level.block.entity.ChestBlockEntity", "getItems");

    @Test
    void hopperPull() {
        assertEquals("hopper", LazyContainerRuntime.classifyEnsure(new StackTraceElement[] {
                SELF_ENSURE, SELF_GETITEMS,
                f("net.minecraft.world.level.block.entity.HopperBlockEntity", "tryTakeInItemFromSlot"),
                f("net.minecraft.world.level.block.entity.HopperBlockEntity", "suckInItems"),
                f("net.minecraft.world.level.block.entity.HopperBlockEntity", "tryMoveItems"),
        }));
    }

    @Test
    void minecartHopper() {
        assertEquals("hopper", LazyContainerRuntime.classifyEnsure(new StackTraceElement[] {
                SELF_ENSURE, SELF_GETITEMS,
                f("net.minecraft.world.CompoundContainer", "getItem"),
                f("net.minecraft.world.entity.vehicle.MinecartHopper", "suckInItems"),
        }));
    }

    @Test
    void comparatorRead() {
        assertEquals("comparator", LazyContainerRuntime.classifyEnsure(new StackTraceElement[] {
                SELF_ENSURE, SELF_GETITEMS,
                f("net.minecraft.world.inventory.AbstractContainerMenu", "getRedstoneSignalFromBlockEntity"),
                f("net.minecraft.world.level.block.ChestBlock", "getAnalogOutputSignal"),
                f("net.minecraft.world.level.block.ComparatorBlock", "calculateOutputSignal"),
        }));
    }

    @Test
    void quickshopCount() {
        assertEquals("quickshop", LazyContainerRuntime.classifyEnsure(new StackTraceElement[] {
                SELF_ENSURE, SELF_GETITEMS,
                f("org.bukkit.craftbukkit.inventory.CraftInventory", "getContents"),
                f("com.ghostchu.quickshop.util.Util", "countItems"),
                f("com.ghostchu.quickshop.shop.SimpleShopManager", "getRemainingStock"),
        }));
    }

    /** 同鏈同時出現 QuickShop 與漏斗:歸給更具體的 quickshop(它才是發起者)。 */
    @Test
    void quickshopBeatsHopper() {
        assertEquals("quickshop", LazyContainerRuntime.classifyEnsure(new StackTraceElement[] {
                SELF_ENSURE, SELF_GETITEMS,
                f("net.minecraft.world.level.block.entity.HopperBlockEntity", "pushItemsTick"),
                f("com.ghostchu.quickshop.listener.HopperListener", "onInventoryMove"),
        }));
    }

    /** trySaveRaw 解不開 bytes 的後備物化(現實上不可達,但要能認得)。 */
    @Test
    void saveFallback() {
        assertEquals("save", LazyContainerRuntime.classifyEnsure(new StackTraceElement[] {
                SELF_ENSURE,
                f("net.minecraft.world.level.block.entity.BaseContainerBlockEntity", "lazycontainer$trySaveRaw"),
                f("net.minecraft.world.level.block.entity.BaseContainerBlockEntity", "lazycontainer$save"),
                f("net.minecraft.world.level.block.entity.ChestBlockEntity", "saveAdditional"),
                f("net.minecraft.world.level.chunk.storage.SerializableChunkData", "copyOf"),
        }));
    }

    @Test
    void playerOpenVanilla() {
        assertEquals("player", LazyContainerRuntime.classifyEnsure(new StackTraceElement[] {
                SELF_ENSURE, SELF_GETITEMS,
                f("net.minecraft.world.level.block.ChestBlock", "useWithoutItem"),
                f("net.minecraft.server.level.ServerPlayerGameMode", "useItemOn"),
        }));
    }

    /**
     * 26.2 實測(rig boot2)抓到的真實破壞鏈:第一觸點是 collectImplicitComponents
     * (方塊→掉落物的 component 收集),不是 Containers.dropContents。
     * 這條在第一版分類器被誤分 vanilla——本測試就是那次誤分的回歸測試。
     */
    @Test
    void blockBreakComponents262() {
        assertEquals("drop", LazyContainerRuntime.classifyEnsure(new StackTraceElement[] {
                SELF_ENSURE, SELF_GETITEMS,
                f("net.minecraft.world.level.block.entity.BaseContainerBlockEntity", "collectImplicitComponents"),
                f("net.minecraft.world.level.block.entity.BlockEntity", "saveToItem"),
                f("net.minecraft.world.level.block.Block", "getDrops"),
        }));
    }

    @Test
    void blockBreakDrop() {
        assertEquals("drop", LazyContainerRuntime.classifyEnsure(new StackTraceElement[] {
                SELF_ENSURE, SELF_GETITEMS,
                f("net.minecraft.world.Containers", "dropContents"),
                f("net.minecraft.world.level.block.ChestBlock", "affectNeighborsAfterRemoval"),
        }));
    }

    /** 未知外掛直接讀容器:歸 plugin(fallback 找第一個非平台套件的 frame)。 */
    @Test
    void unknownPlugin() {
        assertEquals("plugin", LazyContainerRuntime.classifyEnsure(new StackTraceElement[] {
                SELF_ENSURE, SELF_GETITEMS,
                f("org.bukkit.craftbukkit.inventory.CraftInventory", "getItem"),
                f("me.randomdev.storageplugin.StorageScanner", "scanAll"),
        }));
    }

    /**
     * 審查發現(medium):類名含 Hopper 的第三方外掛(EpicHoppers/WildStacker/HopperFilter 系)
     * 不得被誤分進 hopper 桶——那會讓外掛觸發者隱形,打在歸因功能的正臉上。
     * hopper 桶只認 net.minecraft.* 的漏斗類。
     */
    @Test
    void pluginHopperClassIsNotVanillaHopper() {
        assertEquals("plugin", LazyContainerRuntime.classifyEnsure(new StackTraceElement[] {
                SELF_ENSURE, SELF_GETITEMS,
                f("org.bukkit.craftbukkit.inventory.CraftInventory", "getContents"),
                f("me.example.hopperfilter.HopperFilterListener", "scanContainer"),
        }));
    }

    /**
     * 審查發現(low):外掛呼叫 getState() 快照(CraftBlockEntityState → saveAdditional →
     * trySaveRaw)踩到 raw==null 後備物化時,觸發者是外層的外掛,不是 chunk 存檔機器——
     * save 桶不得把外掛吃掉。
     */
    @Test
    void getStateSnapshotFromPluginIsPlugin() {
        assertEquals("plugin", LazyContainerRuntime.classifyEnsure(new StackTraceElement[] {
                SELF_ENSURE,
                f("net.minecraft.world.level.block.entity.BaseContainerBlockEntity", "lazycontainer$trySaveRaw"),
                f("net.minecraft.world.level.block.entity.BaseContainerBlockEntity", "lazycontainer$save"),
                f("net.minecraft.world.level.block.entity.ChestBlockEntity", "saveAdditional"),
                f("org.bukkit.craftbukkit.block.CraftBlockEntityState", "refreshSnapshot"),
                f("me.somedev.inspector.ContainerInspector", "peek"),
        }));
    }

    /** 解碼耗時累計:總和相加、最大值取大不取小(#223 未結①要的是秒數不是次數)。 */
    @Test
    void decodeTimingAccumulates() {
        StackTraceElement[] st = new StackTraceElement[] {
                SELF_ENSURE, SELF_GETITEMS,
                f("net.minecraft.world.level.block.entity.HopperBlockEntity", "tryTakeInItemFromSlot"),
        };
        long sum0 = LazyContainerRuntime.decodeNanos.sum();
        long max0 = LazyContainerRuntime.decodeMaxNanos.get();

        LazyContainerRuntime.onEnsureAttributed(st, 5_000_000L, null);
        LazyContainerRuntime.onEnsureAttributed(st, 20_000_000L, null);
        LazyContainerRuntime.onEnsureAttributed(st, 1_000_000L, null);

        assertEquals(26_000_000L, LazyContainerRuntime.decodeNanos.sum() - sum0);
        assertEquals(Math.max(max0, 20_000_000L), LazyContainerRuntime.decodeMaxNanos.get());
    }

    /** nanos=0(不計時的舊呼叫形式)不得污染耗時統計。 */
    @Test
    void zeroNanosDoesNotCount() {
        StackTraceElement[] st = new StackTraceElement[] {
                SELF_ENSURE, f("net.minecraft.world.Containers", "dropContents"),
        };
        long sum0 = LazyContainerRuntime.decodeNanos.sum();
        LazyContainerRuntime.onEnsureAttributed(st);
        assertEquals(0L, LazyContainerRuntime.decodeNanos.sum() - sum0);
    }

    /**
     * 解碼耗時分佈(四桶)。回答的是「尖峰多常發生」——總量早就不是問題(艦隊 4 台 6 小時
     * 只花 372 秒),真正會被玩家感覺到的是單次數百毫秒的 region 凍結。
     * 桶界:&lt;1ms / 1-10ms / 10-100ms / &gt;=100ms;最後一桶另記總耗時,
     * 用來回答「把尖峰全部消掉能省多少」。
     */
    @Test
    void decodeHistogramBuckets() {
        StackTraceElement[] st = new StackTraceElement[] {
                SELF_ENSURE, SELF_GETITEMS,
                f("net.minecraft.world.level.block.entity.HopperBlockEntity", "tryTakeInItemFromSlot"),
        };
        long[] before = {
            LazyContainerRuntime.decodeLt1ms.sum(), LazyContainerRuntime.decode1to10ms.sum(),
            LazyContainerRuntime.decode10to100ms.sum(), LazyContainerRuntime.decodeGe100ms.sum(),
            LazyContainerRuntime.decodeGe100Nanos.sum(),
        };
        LazyContainerRuntime.onEnsureAttributed(st,     999_999L, null);   // <1ms
        LazyContainerRuntime.onEnsureAttributed(st,   1_000_000L, null);   // 1-10ms(下界含)
        LazyContainerRuntime.onEnsureAttributed(st,   9_999_999L, null);   // 1-10ms
        LazyContainerRuntime.onEnsureAttributed(st,  10_000_000L, null);   // 10-100ms(下界含)
        LazyContainerRuntime.onEnsureAttributed(st,  99_999_999L, null);   // 10-100ms
        LazyContainerRuntime.onEnsureAttributed(st, 100_000_000L, null);   // 100ms+(下界含)
        LazyContainerRuntime.onEnsureAttributed(st, 500_000_000L, null);   // 100ms+

        assertEquals(1, LazyContainerRuntime.decodeLt1ms.sum() - before[0], "<1ms 桶");
        assertEquals(2, LazyContainerRuntime.decode1to10ms.sum() - before[1], "1-10ms 桶");
        assertEquals(2, LazyContainerRuntime.decode10to100ms.sum() - before[2], "10-100ms 桶");
        assertEquals(2, LazyContainerRuntime.decodeGe100ms.sum() - before[3], "100ms+ 桶");
        assertEquals(600_000_000L, LazyContainerRuntime.decodeGe100Nanos.sum() - before[4],
                "100ms+ 桶的總耗時(用來算削峰能省多少)");
    }

    /** nanos=0(不計時的呼叫形式)不得進任何桶,否則分佈會被灌水。 */
    @Test
    void zeroNanosNotInHistogram() {
        StackTraceElement[] st = new StackTraceElement[] {
                SELF_ENSURE, f("net.minecraft.world.Containers", "dropContents"),
        };
        long b = LazyContainerRuntime.decodeLt1ms.sum();
        LazyContainerRuntime.onEnsureAttributed(st);
        assertEquals(0, LazyContainerRuntime.decodeLt1ms.sum() - b, "nanos=0 不該算成 <1ms");
    }

    /** 慢解碼門檻:預設 100ms,低於門檻不要求呼叫端建座標字串。 */
    @Test
    void slowThresholdDefault100ms() {
        org.junit.jupiter.api.Assertions.assertFalse(LazyContainerRuntime.slowDecode(99_000_000L));
        org.junit.jupiter.api.Assertions.assertTrue(LazyContainerRuntime.slowDecode(100_000_000L));
    }

    /** 全鏈都是平台(net.minecraft/craftbukkit)但不落入任何具體桶:歸 vanilla。 */
    @Test
    void vanillaOther() {
        assertEquals("vanilla", LazyContainerRuntime.classifyEnsure(new StackTraceElement[] {
                SELF_ENSURE, SELF_GETITEMS,
                f("net.minecraft.world.CompoundContainer", "getItem"),
                f("net.minecraft.world.level.block.entity.BlockEntity", "somethingInternal"),
        }));
    }
}
