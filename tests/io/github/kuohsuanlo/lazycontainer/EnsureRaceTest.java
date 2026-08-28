package io.github.kuohsuanlo.lazycontainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 跨執行緒物化視窗的併發測試(26.2-2 / A1)。
 *
 * <p><b>為什麼要測</b>:EndRod 的 PIW(R39)允許非擁有 region 的插件執行緒讀活體容器,
 * 而 paper-server 的 {@code CraftInventory.getItem/getContents} 先呼叫 NMS {@code getItem/getContents}
 * 再做跨區快照 ⟹ leaf 的 guard 與整個 {@code ensure()} 解碼都會在插件執行緒上跑。舊版 ensure
 * 「先清 pending 旗標、再逐格填清單」:擁有執行緒的 guard 看到 pending=false 就直接讀「填到一半的清單」
 * (漏斗推入被解碼端覆蓋、抽出後被原量覆蓋、破壞掉落少件、存檔寫出半填清單)。</p>
 *
 * <p><b>怎麼測</b>:直接繼承 {@link LazyContainerTemplate} 做一個可實例化的測試子類
 * (型別 {@code BlockEntityTypes.CHEST} + {@code Blocks.CHEST} 方塊狀態),其 {@code getItems()}
 * 模擬 transformer 在 leaf 入口注入的 guard({@code if (pending) ensure();})。每輪新實例、
 * 餵 27 格各不相同的物品、T1 呼叫 {@code ensure()}、T2 迴圈做「讀 pending;看到 false 就檢查 27 格」
 * ——這正是擁有執行緒 guard 的動作(看到 false ⟹ 跳過 ensure ⟹ 直接用清單)。
 * 任何一輪 T2 看到 false 卻配到半填清單即為紅。</p>
 *
 * <p>2026-08-28 於修正前(commit 1128b58 的 template)實跑:2000 輪中 T2 觀察到半填清單者 ≈ 全部
 * (紅);修正後 0(綠)。紅的輸出留存於 RELEASE-NOTE-26.2-2.md。</p>
 */
@DisplayName("ensure 跨執行緒視窗(A1)")
class EnsureRaceTest {

    private static final int SIZE = 27;
    private static final int ROUNDS = Integer.getInteger("lazycontainer.test.rounds", 2000);

    private static final String[] IDS = {
        "minecraft:stone", "minecraft:dirt", "minecraft:cobblestone", "minecraft:oak_planks",
        "minecraft:sand", "minecraft:gravel", "minecraft:gold_ore", "minecraft:iron_ingot",
        "minecraft:gold_ingot", "minecraft:diamond", "minecraft:emerald", "minecraft:coal",
        "minecraft:redstone", "minecraft:lapis_lazuli", "minecraft:quartz", "minecraft:netherite_ingot",
        "minecraft:stick", "minecraft:apple", "minecraft:bread", "minecraft:wheat",
        "minecraft:sugar", "minecraft:paper", "minecraft:book", "minecraft:feather",
        "minecraft:flint", "minecraft:brick", "minecraft:glass",
    };

    /** 期望的每格數量:1..27,各不相同,方便抓「數量被原量覆蓋」。 */
    private static int expectedCount(int slot) {
        return slot + 1;
    }

    // ───────────────────────── 測試子類:模擬被 transformer 改寫後的 leaf ─────────────────────────

    /**
     * 可實例化的 template 子類。{@code getItems()} 入口的 guard 與 transformer 對 Chest/Barrel/Shulker
     * 注入的 GUARD_ENSURE 一字不差(語意上);{@code hook} 供存檔交錯測試在「ensure 取得清單、
     * 尚未 loadAllItems」的位置卡住 T1(一次性)。
     */
    static final class TestChest extends LazyContainerTemplate {
        private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        final AtomicReference<Runnable> hook = new AtomicReference<>();

        TestChest() {
            super(BlockEntityTypes.CHEST, BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        }

        @Override
        protected NonNullList<ItemStack> getItems() {
            if (this.lazycontainer$pending) {          // = GUARD_ENSURE
                this.lazycontainer$ensure();
            }
            Runnable h = hook.getAndSet(null);
            if (h != null) {
                h.run();
            }
            return items;
        }

        /** 不經 guard 的清單:模擬「guard 看到 pending=false 之後直接回傳欄位」那一步。 */
        NonNullList<ItemStack> rawItems() {
            return items;
        }

        @Override
        protected void setItems(NonNullList<ItemStack> list) {
            items = list;
        }

        @Override
        protected Component getDefaultName() {
            return Component.literal("test-chest");
        }

        @Override
        protected AbstractContainerMenu createMenu(int id, Inventory inv) {
            return null;
        }

        @Override
        public int getContainerSize() {
            return SIZE;
        }

        @Override
        public int getMaxStackSize() {
            return NmsTestSupport.MAX_STACK;
        }

        @Override
        public void setMaxStackSize(int size) {
        }

        @Override
        public void onOpen(CraftHumanEntity who) {
        }

        @Override
        public void onClose(CraftHumanEntity who) {
        }

        @Override
        public List<HumanEntity> getViewers() {
            return List.of();
        }

        @Override
        public InventoryHolder getOwner() {
            return null;
        }

        @Override
        public List<ItemStack> getContents() {
            return getItems();
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private static ListTag fullItems() {
        ListTag items = new ListTag();
        for (int i = 0; i < SIZE; i++) {
            CompoundTag e = new CompoundTag();
            e.putByte("Slot", (byte) i);
            e.putString("id", IDS[i]);
            e.putInt("count", expectedCount(i));
            items.add(e);
        }
        return items;
    }

    private static ValueInput input(ListTag items) {
        CompoundTag root = new CompoundTag();
        root.put("Items", items);
        return TagValueInput.create(ProblemReporter.DISCARDING, NmsTestSupport.registries(), root);
    }

    /** 建一個已 lazy 載入(pending=true、raw 就緒)的測試箱。 */
    private static TestChest pendingChest(ListTag items) {
        TestChest be = new TestChest();
        be.lazycontainer$load(input(items), be.rawItems());
        assertTrue(be.lazycontainer$pending, "lazy 載入後應為 pending");
        assertNotNull(be.lazycontainer$raw, "lazy 載入後 raw 應就緒");
        return be;
    }

    /** 檢查 27 格全部非空且 id/數量正確;回傳缺陷描述(null = 完整)。 */
    private static String checkComplete(NonNullList<ItemStack> list) {
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            ItemStack s = list.get(i);
            if (s.isEmpty()) {
                bad.add(i + ":empty");
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (!IDS[i].equals(id) || s.getCount() != expectedCount(i)) {
                bad.add(i + ":" + id + "x" + s.getCount());
            }
        }
        return bad.isEmpty() ? null : bad.toString();
    }

    /** vanilla 對完整清單的編碼結果(存檔交錯測試的「合法答案之二」)。 */
    private static Tag vanillaEncoded(ListTag items) {
        NonNullList<ItemStack> list = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input(items), list);
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, NmsTestSupport.registries());
        ContainerHelper.saveAllItems(out, list);
        return out.buildResult().get("Items");
    }

    @BeforeAll
    static void bootstrap() {
        NmsTestSupport.bootstrap();
        for (String id : IDS) {
            assertTrue(BuiltInRegistries.ITEM.get(Identifier.parse(id)).isPresent(), "測試物品不存在:" + id);
        }
        // 前置健全性:單執行緒 load + ensure 必須得到 27 格完整(否則後面的併發斷言沒有意義)
        TestChest be = pendingChest(fullItems());
        be.lazycontainer$ensure();
        assertFalse(be.lazycontainer$pending, "ensure 後應非 pending");
        assertEquals(null, checkComplete(be.rawItems()), "單執行緒 ensure 應得到完整清單");
    }

    // ───────────────────────── 測試一:pending=false 必須配完整清單 ─────────────────────────

    @Test
    @DisplayName("T2 看到 pending=false 時清單必須已完整(≥2000 輪)")
    void pendingFalseImpliesComplete() throws Exception {
        ListTag items = fullItems();
        AtomicInteger badRounds = new AtomicInteger();
        AtomicInteger racedRounds = new AtomicInteger();     // T2 至少看過一次 pending=true(真的有搶到)
        List<String> samples = new ArrayList<>();
        AtomicReference<Throwable> t1Error = new AtomicReference<>();

        for (int round = 0; round < ROUNDS; round++) {
            TestChest be = pendingChest(items);
            AtomicBoolean t1Done = new AtomicBoolean();
            CountDownLatch go = new CountDownLatch(1);

            Thread t1 = new Thread(() -> {
                try {
                    go.await();
                    be.lazycontainer$ensure();
                } catch (Throwable t) {
                    t1Error.compareAndSet(null, t);
                } finally {
                    t1Done.set(true);
                }
            }, "lc-ensure-T1");

            final int r = round;
            Thread t2 = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                boolean sawPending = false;
                while (true) {
                    boolean done = t1Done.get();            // volatile 讀在前:下面的 pending 讀不會被 JIT 提出迴圈
                    boolean pending = be.lazycontainer$pending;
                    if (pending) {
                        sawPending = true;
                        if (done) {
                            // T1 已結束卻仍 pending:ensure 拋錯(t1Error 會記錄),交給主緒判定
                            return;
                        }
                        Thread.onSpinWait();
                        continue;
                    }
                    // = 擁有執行緒 guard 看到 false ⟹ 直接用清單
                    String defect = checkComplete(be.rawItems());
                    if (defect != null) {
                        badRounds.incrementAndGet();
                        synchronized (samples) {
                            if (samples.size() < 5) {
                                samples.add("round#" + r + " pending=false 但清單不完整 " + defect);
                            }
                        }
                    }
                    if (sawPending) {
                        racedRounds.incrementAndGet();
                    }
                    return;
                }
            }, "lc-reader-T2");

            t1.start();
            t2.start();
            go.countDown();
            t1.join(10_000);
            t2.join(10_000);
            if (t1.isAlive() || t2.isAlive()) {
                fail("round#" + round + " 執行緒未在 10s 內結束(疑似死結/無限遞迴)");
            }
            if (t1Error.get() != null) {
                fail("round#" + round + " ensure() 拋出例外:" + t1Error.get(), t1Error.get());
            }
        }

        System.out.println("[EnsureRaceTest] rounds=" + ROUNDS + " raced=" + racedRounds.get()
                + " bad=" + badRounds.get() + (samples.isEmpty() ? "" : " samples=" + samples));
        assertTrue(racedRounds.get() > 0, "T2 從未在 T1 物化期間觀察到 pending=true,測試沒有真的搶到,無效");
        assertEquals(0, badRounds.get(),
                "T2 看到 pending=false 卻讀到半填清單的輪數 = " + badRounds.get() + " / " + ROUNDS + ";樣本:" + samples);
    }

    // ───────────────────────── 測試二:存檔與在途 ensure 交錯 ─────────────────────────

    @Test
    @DisplayName("ensure 中途(loadAllItems 前)存檔:輸出必為原 raw 或完整清單,絕非子集")
    void saveDuringEnsureIsNeverPartial() throws Exception {
        ListTag items = fullItems();
        TestChest be = pendingChest(items);
        CountDownLatch inEnsure = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        be.hook.set(() -> {
            inEnsure.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread t1 = new Thread(() -> {
            try {
                be.lazycontainer$ensure();
            } catch (Throwable t) {
                err.compareAndSet(null, t);
            }
        }, "lc-ensure-T1");
        t1.start();
        assertTrue(inEnsure.await(10, TimeUnit.SECONDS), "T1 未進入 ensure 的解碼點");

        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, NmsTestSupport.registries());
        Thread t2 = new Thread(() -> {
            try {
                be.lazycontainer$save(out, be.rawItems());     // = leaf saveAdditional 內被 redirect 的呼叫
            } catch (Throwable t) {
                err.compareAndSet(null, t);
            }
        }, "lc-save-T2");
        t2.start();
        // 修正後 T2 會卡在 monitor 等 T1;修正前 T2 立刻用半填清單編碼。給它 300ms 表態,再放行 T1。
        t2.join(300);
        boolean saveFinishedWhileEnsureStalled = !t2.isAlive();
        release.countDown();
        t1.join(10_000);
        t2.join(10_000);
        assertFalse(t1.isAlive() || t2.isAlive(), "執行緒未結束(疑似死結)");
        if (err.get() != null) {
            fail("例外:" + err.get(), err.get());
        }

        Tag saved = out.buildResult().get("Items");
        Tag expectFull = vanillaEncoded(items);
        boolean equalsRaw = items.equals(saved);
        boolean equalsFull = expectFull.equals(saved);
        int savedSize = (saved instanceof ListTag) ? ((ListTag) saved).size() : -1;
        System.out.println("[EnsureRaceTest] save-during-ensure: saveFinishedWhileEnsureStalled="
                + saveFinishedWhileEnsureStalled + " savedEntries=" + savedSize
                + " equalsRaw=" + equalsRaw + " equalsFull=" + equalsFull);
        assertTrue(equalsRaw || equalsFull,
                "存檔輸出既不等於原 raw 也不等於完整清單(entries=" + savedSize + "):" + saved);
        assertEquals(null, checkComplete(be.rawItems()), "ensure 結束後清單應完整");
        assertFalse(be.lazycontainer$pending, "ensure 結束後應非 pending");
    }
}
