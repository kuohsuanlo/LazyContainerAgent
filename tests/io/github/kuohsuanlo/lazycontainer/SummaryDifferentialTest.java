package io.github.kuohsuanlo.lazycontainer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「刀一(ensure 快取)」摘要邏輯的<b>差分測試</b> —— 純 JUnit,零 Minecraft server。
 *
 * <p>做法:同一份未解碼的 {@code Items} ListTag,一邊餵給
 * {@link LazyContainerTemplate#lazycontainer$computeSummary}(摘要,不解碼),
 * 一邊餵給<b>真正的</b> {@link ContainerHelper#loadAllItems}(vanilla 解碼),
 * 再用 vanilla {@code HopperBlockEntity.isFullContainer} 的判準
 * ({@code count < maxStackSize} 即不滿)算出「真值」,兩邊比對。</p>
 *
 * <h3>驗的是什麼(答錯方向不對稱)</h3>
 * 摘要只有在「能證明」時才回答,答不出就退回原版路徑。因此真正致命的只有<b>答錯</b>:
 * <ul>
 *   <li>說「此格證明為空」但實際有東西 → 漏斗永遠跳過該格 → 物流卡死。</li>
 *   <li>說「證明全滿」但實際沒滿 → 漏斗停止推入。</li>
 *   <li>說「證明不滿」但實際全滿 → 只是白掃一圈(無害),仍一併檢查。</li>
 * </ul>
 *
 * <h3>headless 前置</h3>
 * 26.2 的物品 data component 平常由資料包重載管線({@code ReloadableServerResources})綁定,
 * headless 起不動。這裡改用公開的 {@link Holder.Reference#bindComponents} 自行綁一份合成 component map,
 * <b>兩邊(摘要與 vanilla)吃的是同一份</b>,故差分依然成立;唯一不擬真的是「各物品真實的堆疊上限」,
 * 那是單純且穩定的查表輸入,已由真機 shadow 覆核涵蓋。
 */
@DisplayName("摘要 vs vanilla codec 差分")
class SummaryDifferentialTest {

    /** 測試用的堆疊上限;綁給所有物品,摘要與 vanilla 共用同一值。 */
    private static final int MAX_STACK = 64;

    private static RegistryAccess.Frozen registries;
    private static List<String> itemIds;

    @BeforeAll
    static void bootstrapNms() {
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
    }

    // ───────────────────────── 差分核心 ─────────────────────────

    /** vanilla 真值:解碼後每格是否為空、以及 isFullContainer 的判準結果。 */
    private record Truth(boolean[] empty, boolean full) {}

    private static Truth vanilla(ListTag items, int size) {
        CompoundTag root = new CompoundTag();
        root.put("Items", items);
        NonNullList<ItemStack> list = NonNullList.withSize(size, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(
                TagValueInput.create(ProblemReporter.DISCARDING, registries, root), list);
        boolean[] empty = new boolean[size];
        boolean full = true;
        for (int i = 0; i < size; i++) {
            ItemStack s = list.get(i);
            empty[i] = s.isEmpty();
            if (s.getCount() < s.getMaxStackSize()) {
                full = false;   // 與 HopperBlockEntity.isFullContainer 同一判準
            }
        }
        return new Truth(empty, full);
    }

    /**
     * 對一份 Items 做完整差分斷言。摘要棄答不算失敗(那只是少省一次);
     * 只要它<b>開口回答</b>,就必須與 vanilla 一致。
     */
    private static void assertAgrees(String label, ListTag items, int size) {
        long packed = LazyContainerTemplate.lazycontainer$computeSummary(items, size);
        Truth t = vanilla(items, size);
        if (packed == LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP) {
            return;     // 整份棄答 → 走原版路徑,永遠安全
        }
        int bits = (int) (packed >>> 32);
        int tri = ((int) (packed & 0xFFFFFFFFL)) - 1;

        for (int i = 0; i < size; i++) {
            boolean provenEmpty = (bits >>> i & 1) == 0;
            if (provenEmpty) {
                assertTrue(t.empty()[i],
                        label + ":摘要說 slot " + i + " 證明為空,但 vanilla 解出有物品(會讓漏斗永久跳過該格)");
            }
        }
        if (tri == 1) {
            assertTrue(t.full(), label + ":摘要說「證明全滿」,但 vanilla 判定未滿(會讓漏斗停止推入)");
        } else if (tri == 0) {
            assertTrue(!t.full(), label + ":摘要說「證明不滿」,但 vanilla 判定全滿");
        }
    }

    // ───────────────────────── 建構工具 ─────────────────────────

    private static CompoundTag entry(Tag slot, String id, Tag count) {
        CompoundTag t = new CompoundTag();
        if (slot != null) {
            t.put("Slot", slot);
        }
        if (id != null) {
            t.putString("id", id);
        }
        if (count != null) {
            t.put("count", count);
        }
        return t;
    }

    private static ListTag list(Tag... entries) {
        ListTag l = new ListTag();
        for (Tag e : entries) {
            l.add(e);
        }
        return l;
    }

    // ───────────────────────── 針對性(對抗)案例 ─────────────────────────

    @Test
    @DisplayName("空清單:每格證明為空、判定不滿")
    void emptyList() {
        ListTag items = new ListTag();
        long packed = LazyContainerTemplate.lazycontainer$computeSummary(items, 27);
        assertEquals(0, (int) (packed >>> 32), "空清單不應宣告任何佔用");
        assertEquals(0, ((int) (packed & 0xFFFFFFFFL)) - 1, "空容器必須判定不滿");
        assertAgrees("emptyList", items, 27);
    }

    @Test
    @DisplayName("27 格全滿(count=max):證明全滿")
    void allFull() {
        ListTag items = new ListTag();
        for (int i = 0; i < 27; i++) {
            items.add(entry(ByteTag.valueOf((byte) i), "minecraft:cobblestone", IntTag.valueOf(MAX_STACK)));
        }
        long packed = LazyContainerTemplate.lazycontainer$computeSummary(items, 27);
        assertEquals(1, ((int) (packed & 0xFFFFFFFFL)) - 1, "每格 count=max 應證明全滿");
        assertAgrees("allFull", items, 27);
    }

    @Test
    @DisplayName("一格少一個:證明不滿")
    void oneShort() {
        ListTag items = new ListTag();
        for (int i = 0; i < 27; i++) {
            int c = (i == 13) ? MAX_STACK - 1 : MAX_STACK;
            items.add(entry(ByteTag.valueOf((byte) i), "minecraft:cobblestone", IntTag.valueOf(c)));
        }
        assertAgrees("oneShort", items, 27);
    }

    @Test
    @DisplayName("Slot 為負小數 Double/Float:floor 與向零截斷的分歧(曾經的真 bug)")
    void negativeFractionalSlot() {
        // 迴歸守門:曾用 NumericTag.byteValue()(對 Double 是 Mth.floor)導致落格與 vanilla 差一格,
        // 讓有物品的格子被誤證為空。正解是 box().byteValue()(向零截斷)。
        double[] probes = {-0.5, -1.5, -255.5, -256.5, -0.1, -246.5, 3.7, 300.5, -1e9};
        for (double d : probes) {
            assertAgrees("Slot=" + d + "d", list(entry(DoubleTag.valueOf(d), "minecraft:diamond", IntTag.valueOf(1))), 27);
            assertAgrees("Slot=" + d + "f", list(entry(FloatTag.valueOf((float) d), "minecraft:diamond", IntTag.valueOf(1))), 27);
        }
    }

    @Test
    @DisplayName("Slot 各種數值型別與越界迴繞")
    void slotTypesAndWrap() {
        Tag[] slots = {
            ByteTag.valueOf((byte) 0), ByteTag.valueOf((byte) 26), ByteTag.valueOf((byte) -1),
            ShortTag.valueOf((short) 300), IntTag.valueOf(260), IntTag.valueOf(-260),
            LongTag.valueOf(1L << 40), IntTag.valueOf(Integer.MIN_VALUE), IntTag.valueOf(Integer.MAX_VALUE),
        };
        for (Tag s : slots) {
            assertAgrees("slot=" + s, list(entry(s, "minecraft:diamond", IntTag.valueOf(1))), 27);
        }
        // 非數值 Slot / 缺 Slot(預設 0)
        assertAgrees("slot=string", list(entry(StringTag.valueOf("x"), "minecraft:diamond", IntTag.valueOf(1))), 27);
        assertAgrees("slot=absent", list(entry(null, "minecraft:diamond", IntTag.valueOf(1))), 27);
    }

    @Test
    @DisplayName("count 缺席/邊界/型別/超界")
    void countEdges() {
        Tag[] counts = {
            null, IntTag.valueOf(1), IntTag.valueOf(0), IntTag.valueOf(-5),
            IntTag.valueOf(99), IntTag.valueOf(100), IntTag.valueOf(MAX_STACK),
            DoubleTag.valueOf(64.9), DoubleTag.valueOf(-0.5), LongTag.valueOf((1L << 32) + 50L),
            StringTag.valueOf("64"),
        };
        for (Tag c : counts) {
            assertAgrees("count=" + c, list(entry(ByteTag.valueOf((byte) 0), "minecraft:diamond", c)), 27);
        }
    }

    @Test
    @DisplayName("id 未知/畸形/air/缺席")
    void idEdges() {
        String[] ids = {"minecraft:definitely_not_a_real_item", "minecraft:air", "not a valid id", "", "MINECRAFT:DIAMOND"};
        for (String id : ids) {
            assertAgrees("id=" + id, list(entry(ByteTag.valueOf((byte) 0), id, IntTag.valueOf(1))), 27);
        }
        assertAgrees("id=absent", list(entry(ByteTag.valueOf((byte) 0), null, IntTag.valueOf(1))), 27);
    }

    @Test
    @DisplayName("重複 Slot:後寫者勝、且先成功後失敗時不可誤判")
    void duplicateSlots() {
        // vanilla loadAllItems 對每個成功解碼的 entry 做 items.set,後者覆蓋前者;
        // 但「後者解碼失敗」時 vanilla 保留前者 —— 摘要必須不因此答錯。
        assertAgrees("dup:good-then-bad", list(
                entry(ByteTag.valueOf((byte) 5), "minecraft:diamond", IntTag.valueOf(MAX_STACK)),
                entry(ByteTag.valueOf((byte) 5), "minecraft:nonexistent_item", IntTag.valueOf(1))), 27);
        assertAgrees("dup:bad-then-good", list(
                entry(ByteTag.valueOf((byte) 5), "minecraft:nonexistent_item", IntTag.valueOf(1)),
                entry(ByteTag.valueOf((byte) 5), "minecraft:diamond", IntTag.valueOf(MAX_STACK))), 27);
        assertAgrees("dup:full-then-partial", list(
                entry(ByteTag.valueOf((byte) 5), "minecraft:diamond", IntTag.valueOf(MAX_STACK)),
                entry(ByteTag.valueOf((byte) 5), "minecraft:diamond", IntTag.valueOf(1))), 27);
    }

    @Test
    @DisplayName("components:空/合法 max_stack_size/未知元件/移除語法")
    void componentsHandling() {
        // 全滿 27 格,其中一格帶各種 components,檢查「證明全滿」不會被錯誤地給出
        for (String variant : new String[]{"empty", "max1", "max64", "unknown", "removal", "badtype"}) {
            ListTag items = new ListTag();
            for (int i = 0; i < 27; i++) {
                CompoundTag e = entry(ByteTag.valueOf((byte) i), "minecraft:cobblestone", IntTag.valueOf(MAX_STACK));
                if (i == 7) {
                    CompoundTag comps = new CompoundTag();
                    switch (variant) {
                        case "empty" -> { }
                        case "max1" -> comps.putInt("minecraft:max_stack_size", 1);
                        case "max64" -> comps.putInt("minecraft:max_stack_size", MAX_STACK);
                        case "unknown" -> comps.putString("minecraft:custom_name", "\"hi\"");
                        case "removal" -> comps.putInt("!minecraft:max_stack_size", 1);
                        case "badtype" -> comps.putString("minecraft:max_stack_size", "64");
                        default -> throw new IllegalStateException(variant);
                    }
                    e.put("components", comps);
                }
                items.add(e);
            }
            assertAgrees("components:" + variant, items, 27);
        }
    }

    @Test
    @DisplayName("結構性垃圾:非 compound entry、巢狀清單、空 entry")
    void structuralGarbage() {
        assertAgrees("garbage:string-entry", list(StringTag.valueOf("junk")), 27);
        assertAgrees("garbage:nested-list", list(new ListTag()), 27);
        assertAgrees("garbage:empty-compound", list(new CompoundTag()), 27);
        assertAgrees("garbage:mixed", list(
                StringTag.valueOf("junk"),
                entry(ByteTag.valueOf((byte) 2), "minecraft:diamond", IntTag.valueOf(1)),
                new CompoundTag()), 27);
    }

    @Test
    @DisplayName("容器尺寸:1 / 27(箱) / 5(高爐類) / 越界尺寸棄答")
    void containerSizes() {
        for (int size : new int[]{1, 3, 5, 9, 27}) {
            ListTag items = new ListTag();
            for (int i = 0; i < size; i++) {
                items.add(entry(ByteTag.valueOf((byte) i), "minecraft:cobblestone", IntTag.valueOf(MAX_STACK)));
            }
            assertAgrees("size=" + size, items, size);
        }
        assertEquals(LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP,
                LazyContainerTemplate.lazycontainer$computeSummary(new ListTag(), 54),
                "size>32 必須棄答(bitmap 放不下)");
        assertEquals(LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP,
                LazyContainerTemplate.lazycontainer$computeSummary(new ListTag(), 0),
                "size<=0 必須棄答");
        assertEquals(LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP,
                LazyContainerTemplate.lazycontainer$computeSummary(StringTag.valueOf("not a list"), 27),
                "raw 非 ListTag 必須棄答");
    }

    // ───────────────────────── raw bytes 往返(方案 A′ 的資料不變鐵則)─────────────────────────

    @Test
    @DisplayName("raw bytes 往返:decode(encode(t))≡t、encode(decode(b)) 逐位元組=b、null 進出")
    void rawBytesRoundTrip() throws Exception {
        Random r = new Random(5566L);
        for (int iter = 0; iter < 3000; iter++) {
            ListTag items = new ListTag();
            int n = r.nextInt(30);
            for (int i = 0; i < n; i++) {
                items.add(randomEntry(r));
            }
            byte[] b = LazyContainerTemplate.lazycontainer$encodeRaw(items);
            Tag back = LazyContainerTemplate.lazycontainer$decodeRaw(b);
            assertEquals(items, back, "decode(encode(t)) 必須等於 t(掉物=資料毀損)");
            byte[] b2 = LazyContainerTemplate.lazycontainer$encodeRaw(back);
            assertArrayEquals(b, b2, "encode(decode(b)) 必須逐位元組等於 b(存檔輸出穩定性)");
        }
        assertNull(LazyContainerTemplate.lazycontainer$encodeRaw(null));
        assertNull(LazyContainerTemplate.lazycontainer$decodeRaw(null));
    }

    @Test
    @DisplayName("raw bytes 往返:深巢 shulker-in-chest(材料站形態)+ 明確 count:1 保留")
    void rawBytesRoundTripNested() throws Exception {
        // 模擬 s18 材料站的資料形態:箱子的 Items 裡是界伏盒,界伏盒的 container component 裡還有物品,
        // 且內層有「明確寫出的 count:1」(codec 正規化會省略它——往返絕不能弄掉)。
        ListTag inner = new ListTag();
        for (int i = 0; i < 27; i++) {
            CompoundTag slotEntry = new CompoundTag();
            slotEntry.putInt("slot", i);
            CompoundTag item = new CompoundTag();
            item.putString("id", "minecraft:diamond");
            item.putInt("count", 1);            // 明確預設值
            slotEntry.put("item", item);
            inner.add(slotEntry);
        }
        ListTag items = new ListTag();
        for (int s = 0; s < 27; s++) {
            CompoundTag box = new CompoundTag();
            box.putByte("Slot", (byte) s);
            box.putString("id", "minecraft:shulker_box");
            box.putInt("count", 1);
            CompoundTag comps = new CompoundTag();
            comps.put("minecraft:container", inner.copy());
            box.put("components", comps);
            items.add(box);
        }
        byte[] b = LazyContainerTemplate.lazycontainer$encodeRaw(items);
        Tag back = LazyContainerTemplate.lazycontainer$decodeRaw(b);
        assertEquals(items, back, "深巢結構往返必須恆等");
        assertArrayEquals(b, LazyContainerTemplate.lazycontainer$encodeRaw(back), "深巢 byte 穩定");
        // 內層 count:1 逐一還在
        ListTag backL = (ListTag) back;
        CompoundTag c0 = (CompoundTag) backL.get(0);
        ListTag innerBack = (ListTag) ((CompoundTag) c0.get("components")).get("minecraft:container");
        CompoundTag innerItem = (CompoundTag) ((CompoundTag) innerBack.get(0)).get("item");
        assertTrue(innerItem.get("count") != null, "內層明確 count:1 不得被往返弄掉");
    }

    // ───────────────────────── 隨機模糊測試 ─────────────────────────

    @Test
    @DisplayName("隨機模糊:20000 份亂數 Items,摘要開口就必須與 vanilla 一致")
    void randomizedFuzz() {
        Random r = new Random(20260814L);   // 固定種子,失敗可重現
        int answered = 0;
        for (int iter = 0; iter < 20000; iter++) {
            int size = 27;
            int n = r.nextInt(30);
            ListTag items = new ListTag();
            for (int i = 0; i < n; i++) {
                items.add(randomEntry(r));
            }
            assertAgrees("fuzz#" + iter, items, size);
            if (LazyContainerTemplate.lazycontainer$computeSummary(items, size)
                    != LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP) {
                answered++;
            }
        }
        assertTrue(answered > 0, "模糊測試應至少有部分案例被摘要回答,否則等於沒測到");
    }

    @Test
    @DisplayName("隨機模糊(偏向合法):容易形成滿/接近滿的容器")
    void randomizedFuzzNearFull() {
        Random r = new Random(776699L);
        for (int iter = 0; iter < 8000; iter++) {
            ListTag items = new ListTag();
            for (int i = 0; i < 27; i++) {
                if (r.nextInt(20) == 0) {
                    continue;                                   // 偶爾漏一格
                }
                int c = r.nextInt(10) == 0 ? r.nextInt(MAX_STACK) + 1 : MAX_STACK;
                String id = itemIds.get(r.nextInt(itemIds.size()));
                CompoundTag e = entry(ByteTag.valueOf((byte) i), id, IntTag.valueOf(c));
                if (r.nextInt(12) == 0) {
                    CompoundTag comps = new CompoundTag();
                    comps.putInt("minecraft:max_stack_size", r.nextInt(99) + 1);
                    e.put("components", comps);
                }
                items.add(e);
            }
            assertAgrees("nearFull#" + iter, items, 27);
        }
    }

    private static CompoundTag randomEntry(Random r) {
        Tag slot = switch (r.nextInt(8)) {
            case 0 -> null;
            case 1 -> ByteTag.valueOf((byte) r.nextInt(40));
            case 2 -> IntTag.valueOf(r.nextInt(600) - 300);
            case 3 -> ShortTag.valueOf((short) (r.nextInt(600) - 300));
            case 4 -> DoubleTag.valueOf((r.nextDouble() - 0.5) * 600.0);
            case 5 -> FloatTag.valueOf((r.nextFloat() - 0.5f) * 600.0f);
            case 6 -> LongTag.valueOf(r.nextLong());
            default -> StringTag.valueOf("bogus");
        };
        String id = switch (r.nextInt(6)) {
            case 0 -> null;
            case 1 -> "minecraft:definitely_not_real";
            case 2 -> "minecraft:air";
            case 3 -> "!!!bad id!!!";
            default -> itemIds.get(r.nextInt(itemIds.size()));
        };
        Tag count = switch (r.nextInt(7)) {
            case 0 -> null;
            case 1 -> IntTag.valueOf(r.nextInt(120) - 10);
            case 2 -> DoubleTag.valueOf((r.nextDouble() - 0.3) * 120.0);
            case 3 -> LongTag.valueOf(r.nextLong());
            case 4 -> StringTag.valueOf("nope");
            default -> IntTag.valueOf(MAX_STACK);
        };
        CompoundTag e = entry(slot, id, count);
        if (r.nextInt(6) == 0) {
            CompoundTag comps = new CompoundTag();
            switch (r.nextInt(4)) {
                case 0 -> comps.putInt("minecraft:max_stack_size", r.nextInt(120) - 10);
                case 1 -> comps.putString("minecraft:custom_name", "\"x\"");
                case 2 -> comps.putInt("!minecraft:max_stack_size", 1);
                default -> { }
            }
            e.put("components", comps);
        }
        return e;
    }
}
