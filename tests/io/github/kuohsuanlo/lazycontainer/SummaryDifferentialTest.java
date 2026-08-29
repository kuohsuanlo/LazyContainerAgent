package io.github.kuohsuanlo.lazycontainer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
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
 * headless 起不動。這裡改用公開的 {@code Holder.Reference#bindComponents} 自行綁一份合成 component map
 * (見 {@link NmsTestSupport}),<b>兩邊(摘要與 vanilla)吃的是同一份</b>,故差分依然成立;
 * 唯一不擬真的是「各物品真實的堆疊上限」,那是單純且穩定的查表輸入,已由真機 shadow 覆核涵蓋。
 */
@DisplayName("摘要 vs vanilla codec 差分")
class SummaryDifferentialTest {

    /** 測試用的堆疊上限(見 {@link NmsTestSupport#MAX_STACK})。 */
    private static final int MAX_STACK = NmsTestSupport.MAX_STACK;

    private static RegistryAccess.Frozen registries;
    private static List<String> itemIds;

    @BeforeAll
    static void bootstrapNms() {
        NmsTestSupport.bootstrap();          // 見 NmsTestSupport:註冊表 + 合成 component 綁定 + 假 server
        registries = NmsTestSupport.registries();
        itemIds = NmsTestSupport.itemIds();
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

    /**
     * 放寬證明規則(26.2 實測,真 codec 逐 component partial:壞的丟、好的留、物品永遠在):
     * 帶任何非 max_stack_size 的 component 都<b>不影響</b>格子有無物品、也不影響 max。
     * 舊規則對這種箱子一律「不知道」⟹ 漏斗 isFullContainer 被迫整箱解碼——s3 商場掃描:
     * 12,754 個全滿箱有 76.3% 因此無法證明滿。本測試要求:全滿且每格帶 custom_data/lore/name 的箱子,
     * 摘要必須<b>證明全滿</b>(tri==1),且與 vanilla 一致(雙向)。
     */
    @Test
    @DisplayName("放寬:全滿箱每格帶 custom_data/lore/custom_name ⟹ 必須證明全滿")
    void componentsMustNotBlockFullProof() {
        for (String kind : new String[]{"custom_data", "lore", "custom_name", "enchantments", "mixed"}) {
            ListTag items = new ListTag();
            for (int i = 0; i < 27; i++) {
                CompoundTag e = entry(ByteTag.valueOf((byte) i), "minecraft:cobblestone", IntTag.valueOf(MAX_STACK));
                CompoundTag comps = new CompoundTag();
                switch (kind) {
                    case "custom_data" -> { CompoundTag cd = new CompoundTag(); cd.putString("shop", "x"); comps.put("minecraft:custom_data", cd); }
                    case "lore" -> { ListTag l = new ListTag(); l.add(StringTag.valueOf("\"line\"")); comps.put("minecraft:lore", l); }
                    case "custom_name" -> comps.putString("minecraft:custom_name", "\"hi\"");
                    case "enchantments" -> { CompoundTag en = new CompoundTag(); en.putInt("minecraft:sharpness", 1); comps.put("minecraft:enchantments", en); }
                    default -> { CompoundTag cd = new CompoundTag(); cd.putString("a", "b"); comps.put("minecraft:custom_data", cd); comps.putString("minecraft:custom_name", "\"n\""); comps.putInt("minecraft:repair_cost", 3); }
                }
                e.put("components", comps);
                items.add(e);
            }
            assertAgrees("relax:" + kind, items, 27);
            Truth truth = vanilla(items, 27);
            long packed = LazyContainerTemplate.lazycontainer$computeSummary(items, 27);
            assertTrue(truth.full(), "前置:vanilla 必須判這箱全滿(kind=" + kind + ")");
            assertEquals(1, tri(packed), "放寬後摘要必須證明全滿,否則漏斗每次都整箱解碼(kind=" + kind + ")");
        }
    }

    /**
     * 壞 component 不會讓格子空掉(真 codec 實測:lore 給字串、不存在的 key、enchantments 給字串,
     * 物品都還在、count/max 不變)。摘要對這種全滿箱同樣必須證明滿——但要與 vanilla 雙向一致。
     */
    @Test
    @DisplayName("放寬:壞 component(型別錯/不存在 key)不清空格子 ⟹ 全滿箱仍證明全滿")
    void brokenComponentKeepsItemAndFullProof() {
        for (String kind : new String[]{"lore_string", "unknown_key", "enchant_string", "non_compound"}) {
            ListTag items = new ListTag();
            for (int i = 0; i < 27; i++) {
                CompoundTag e = entry(ByteTag.valueOf((byte) i), "minecraft:cobblestone", IntTag.valueOf(MAX_STACK));
                if (i == 7) {
                    switch (kind) {
                        case "lore_string" -> { CompoundTag c = new CompoundTag(); c.putString("minecraft:lore", "garbage"); e.put("components", c); }
                        case "unknown_key" -> { CompoundTag c = new CompoundTag(); c.putInt("minecraft:does_not_exist", 1); e.put("components", c); }
                        case "enchant_string" -> { CompoundTag c = new CompoundTag(); c.putString("minecraft:enchantments", "nope"); e.put("components", c); }
                        default -> e.putString("components", "not-a-compound");
                    }
                }
                items.add(e);
            }
            assertAgrees("broken:" + kind, items, 27);
            Truth truth = vanilla(items, 27);
            long packed = LazyContainerTemplate.lazycontainer$computeSummary(items, 27);
            assertEquals(truth.full(), tri(packed) == 1,
                    "壞 component 的全滿箱:摘要與 vanilla 的「滿」判定必須雙向一致(kind=" + kind + ")");
        }
    }

    /**
     * max_stack_size 是唯一影響「滿」的 component。三種情況都要與 vanilla 雙向一致:
     * 合法 max=16 + 壞 sibling(max 仍為 16)、max 非數值/超範圍(退回物品預設 max)、
     * max=16 但 count=64(載入不拒絕,count>=max 仍算滿)。
     */
    @Test
    @DisplayName("放寬:max_stack_size 的三種邊角與 vanilla 雙向一致")
    void maxStackSizeEdgesAgreeBothWays() {
        String[] kinds = {"max16_badsibling_count16", "max16_badsibling_count8", "max_string", "max_200", "max_0", "max16_count64"};
        for (String kind : kinds) {
            ListTag items = new ListTag();
            for (int i = 0; i < 27; i++) {
                int count = MAX_STACK;
                CompoundTag comps = new CompoundTag();
                switch (kind) {
                    case "max16_badsibling_count16" -> { comps.putInt("minecraft:max_stack_size", 16); comps.putString("minecraft:lore", "garbage"); count = 16; }
                    case "max16_badsibling_count8" -> { comps.putInt("minecraft:max_stack_size", 16); comps.putString("minecraft:lore", "garbage"); count = 8; }
                    case "max_string" -> comps.putString("minecraft:max_stack_size", "bad");
                    case "max_200" -> comps.putInt("minecraft:max_stack_size", 200);
                    case "max_0" -> comps.putInt("minecraft:max_stack_size", 0);
                    default -> { comps.putInt("minecraft:max_stack_size", 16); count = MAX_STACK; }
                }
                CompoundTag e = entry(ByteTag.valueOf((byte) i), "minecraft:cobblestone", IntTag.valueOf(count));
                e.put("components", comps);
                items.add(e);
            }
            assertAgrees("maxedge:" + kind, items, 27);
            Truth truth = vanilla(items, 27);
            long packed = LazyContainerTemplate.lazycontainer$computeSummary(items, 27);
            assertEquals(truth.full(), tri(packed) == 1,
                    "max_stack_size 邊角:摘要與 vanilla 的「滿」判定必須雙向一致(kind=" + kind + ")");
        }
    }

    /**
     * max_stack_size 的拼法(真 codec 實測,NsProbe):裸 max_stack_size / :max_stack_size 會被
     * DataComponentPatch.PatchKey.CODEC 的 Identifier.tryParse 正規化成 minecraft: ⟹ 生效;
     * 移除記號 "!…" 只在值為 compound 時生效(值為 Int 被丟);同一 entry 重複拼法時勝者由
     * fastutil map 迭代序決定(兩種插入序都取 99)⟹ 外部不可複現。
     * 規則:摘要開口就必須與 vanilla 雙向一致;命名空間三種拼法必須開口(不得棄答,否則放寬白做)。
     */
    @Test
    @DisplayName("放寬:max_stack_size 各種拼法與 vanilla 雙向一致;命名空間變體必須開口")
    void maxStackSizeSpellingsAgreeBothWays() {
        Object[][] cases = {
            {"bare16",        16, (java.util.function.Consumer<CompoundTag>) c -> c.putInt("max_stack_size", 16), true},
            {"colon16",       16, (java.util.function.Consumer<CompoundTag>) c -> c.putInt(":max_stack_size", 16), true},
            {"bare99_c64",    64, (java.util.function.Consumer<CompoundTag>) c -> c.putInt("max_stack_size", 99), true},
            {"long16",        16, (java.util.function.Consumer<CompoundTag>) c -> c.putLong("minecraft:max_stack_size", 16L), true},
            {"upperNs",       16, (java.util.function.Consumer<CompoundTag>) c -> c.putInt("MINECRAFT:max_stack_size", 16), false},
            {"trailingSpace", 16, (java.util.function.Consumer<CompoundTag>) c -> c.putInt("minecraft:max_stack_size ", 16), false},
            {"removalInt",    16, (java.util.function.Consumer<CompoundTag>) c -> c.putInt("!minecraft:max_stack_size", 1), false},
            {"removalEmpty",  16, (java.util.function.Consumer<CompoundTag>) c -> c.put("!minecraft:max_stack_size", new CompoundTag()), false},
            {"bareRemovalInt",16, (java.util.function.Consumer<CompoundTag>) c -> c.putInt("!max_stack_size", 1), false},
            {"dupNsBare",     64, (java.util.function.Consumer<CompoundTag>) c -> { c.putInt("minecraft:max_stack_size", 16); c.putInt("max_stack_size", 99); }, false},
            {"dupBareNs",     64, (java.util.function.Consumer<CompoundTag>) c -> { c.putInt("max_stack_size", 99); c.putInt("minecraft:max_stack_size", 16); }, false},
            {"setAndRemove",  16, (java.util.function.Consumer<CompoundTag>) c -> { c.putInt("minecraft:max_stack_size", 16); c.put("!minecraft:max_stack_size", new CompoundTag()); }, false},
        };
        for (Object[] cs : cases) {
            String kind = (String) cs[0]; int count = (Integer) cs[1];
            @SuppressWarnings("unchecked") java.util.function.Consumer<CompoundTag> fill = (java.util.function.Consumer<CompoundTag>) cs[2];
            boolean mustAnswer = (Boolean) cs[3];
            ListTag items = new ListTag();
            for (int i = 0; i < 27; i++) {
                CompoundTag comps = new CompoundTag();
                fill.accept(comps);
                CompoundTag e = entry(ByteTag.valueOf((byte) i), "minecraft:cobblestone", IntTag.valueOf(count));
                e.put("components", comps);
                items.add(e);
            }
            assertAgrees("spelling:" + kind, items, 27);
            Truth truth = vanilla(items, 27);
            long packed = LazyContainerTemplate.lazycontainer$computeSummary(items, 27);
            int tri = (packed == LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP) ? -1 : tri(packed);
            if (tri != -1) {
                assertEquals(truth.full(), tri == 1, "拼法 " + kind + ":摘要開口就必須與 vanilla 雙向一致");
            }
            if (mustAnswer) {
                assertTrue(tri != -1, "拼法 " + kind + ":vanilla 會生效的命名空間變體,摘要必須開口(否則放寬白做)");
            }
        }
    }

    /**
     * 審查抓到的結構性缺口:randomizedFuzz 20,000 份裡 tri==1 為 0 份(從沒全佔用),
     * randomizedFuzzNearFull 的 component 產生器只塞合法 max ⟹ 放寬後唯一有害的方向
     * (摘要開口答「滿」)fuzz 零覆蓋。這支把 27 格全部填上、count 多半在 max 附近,
     * 再把 randomEntry 的整套 component 家族(壞法 + 拼法陷阱)接上去——同一份審查用它在
     * 修正前的程式碼抓到 167 份「摘要說滿、vanilla 說沒滿」。修正後必須為 0,且要真的有開口。
     */
    @Test
    @DisplayName("隨機模糊(全佔用):逼摘要開口答滿 —— 錯答「滿」次數必須為 0")
    void randomizedFuzzFullContainers() {
        Random r = new Random(31337L);
        int answeredFull = 0;
        int falseFull = 0;
        int falseNotFull = 0;
        for (int iter = 0; iter < 60000; iter++) {
            // 語料:27 格全是「乾淨滿堆」(count = 該物品的預設 max),再在 1~3 格灑 component 家族——
            // 這樣多數箱子摘要會開口答滿,而每個陷阱(別名/移除記號值型別/重複拼法/壞 component)都落在
            // 「答滿」的判斷上;隨機語料辦不到這點(任一格帶亂數 max 就整箱變「不滿」)。
            ListTag items = new ListTag();
            for (int i = 0; i < 27; i++) {
                String id = itemIds.get(r.nextInt(itemIds.size()));
                CompoundTag e = new CompoundTag();
                e.putByte("Slot", (byte) i);
                e.putString("id", id);
                e.putInt("count", MAX_STACK);                                   // MAX_STACK 已綁給所有物品(見 bootstrap)
                items.add(e);
            }
            int traps = 1 + r.nextInt(3);
            for (int k = 0; k < traps; k++) {
                CompoundTag victim = (CompoundTag) items.get(r.nextInt(27));
                CompoundTag junk = randomEntry(r);                              // 借它的 component 產生器
                Tag comps = junk.get("components");
                if (comps != null) {
                    victim.put("components", comps);
                }
                if (r.nextInt(3) == 0) {
                    victim.putInt("count", 1 + r.nextInt(99));                  // 偶爾把 count 拉離 max
                }
            }
            long packed = LazyContainerTemplate.lazycontainer$computeSummary(items, 27);
            if (packed == LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP) {
                continue;
            }
            int tri = tri(packed);
            if (tri == -1) {
                continue;
            }
            boolean vf = vanilla(items, 27).full();
            if (tri == 1) {
                answeredFull++;
                if (!vf) {
                    falseFull++;
                    if (falseFull <= 3) {
                        System.out.println("[FULL-FUZZ] 假滿 iter=" + iter + " " + items);
                    }
                }
            } else if (vf) {
                falseNotFull++;
            }
        }
        System.out.println("[FULL-FUZZ] answeredFull=" + answeredFull + " falseFull=" + falseFull + " falseNotFull=" + falseNotFull);
        assertEquals(0, falseFull, "摘要錯答「滿」⟹ 漏斗永久停搬,一次都不行");
        assertEquals(0, falseNotFull, "摘要開口答「不滿」時必須與 vanilla 一致(雙向鐵律)");
        assertTrue(answeredFull > 5000, "全佔用語料下摘要必須大量開口答滿(否則這支測試沒測到目標方向)");
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

    // ───────────────────────── A2(26.2-2):Slot 欄位型別的雙向斷言 ─────────────────────────
    //
    // 上面的 assertAgrees 只驗「摘要開口就不能錯」;下面這組把容器做成「26 格滿 + 1 格由受測 entry 決定」,
    // 讓摘要若誤把受測 entry 當成「乾淨且滿」就會答出「證明全滿」,而 vanilla 真值若丟棄該 entry 就是不滿
    // ——兩邊必然對撞,測試才有鑑別力(不再是單 entry 那種怎麼答都「不滿」的弱斷言)。

    /** 26 格(slot 1..26)全滿,slot 0 交給受測 entry。 */
    private static ListTag twentySixFullPlus(CompoundTag probe) {
        ListTag items = new ListTag();
        for (int i = 1; i < 27; i++) {
            items.add(entry(ByteTag.valueOf((byte) i), "minecraft:cobblestone", IntTag.valueOf(MAX_STACK)));
        }
        items.add(probe);
        return items;
    }

    private static int tri(long packed) {
        return ((int) (packed & 0xFFFFFFFFL)) - 1;
    }

    @Test
    @DisplayName("A2:Slot 為字串(\"0\")的滿堆 + 26 格滿 —— 摘要不得標 clean/證明全滿")
    void nonNumericSlotMustNotProveFull() {
        ListTag items = twentySixFullPlus(entry(StringTag.valueOf("0"), "minecraft:cobblestone", IntTag.valueOf(MAX_STACK)));
        long packed = LazyContainerTemplate.lazycontainer$computeSummary(items, 27);
        Truth t = vanilla(items, 27);
        System.out.println("[SummaryDifferentialTest] Slot=\"0\" 字串:vanilla slot0Empty=" + t.empty()[0]
                + " vanillaFull=" + t.full() + " summary=" + (packed == LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP
                        ? "GIVEUP" : ("bits=0x" + Integer.toHexString((int) (packed >>> 32)) + " tri=" + tri(packed))));
        assertAgrees("A2:slot-string-0", items, 27);
        // 無論 DFU 對 partial 的語意為何,非數值 Slot 都不得被標成 clean:摘要要嘛棄答、要嘛不得說「證明全滿」
        if (packed != LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP) {
            assertTrue(tri(packed) != 1, "非數值 Slot 的 entry 被當成乾淨滿堆 ⟹ 假「證明全滿」⟹ 漏斗永不推入");
        }
        // 其他非數值型別同樣處理
        Tag[] junkSlots = {StringTag.valueOf("x"), new ListTag(), new CompoundTag(), new net.minecraft.nbt.ByteArrayTag(new byte[]{0})};
        for (Tag js : junkSlots) {
            ListTag l = twentySixFullPlus(entry(js, "minecraft:cobblestone", IntTag.valueOf(MAX_STACK)));
            assertAgrees("A2:slot=" + js.getClass().getSimpleName(), l, 27);
            long p = LazyContainerTemplate.lazycontainer$computeSummary(l, 27);
            if (p != LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP) {
                assertTrue(tri(p) != 1, "非數值 Slot(" + js.getClass().getSimpleName() + ")不得證明全滿");
            }
        }
    }

    @Test
    @DisplayName("A2:缺 Slot(預設 0)的滿堆 + 26 格滿 —— 與 vanilla 雙向一致")
    void absentSlotDefaultsToZero() {
        ListTag items = twentySixFullPlus(entry(null, "minecraft:cobblestone", IntTag.valueOf(MAX_STACK)));
        Truth t = vanilla(items, 27);
        long packed = LazyContainerTemplate.lazycontainer$computeSummary(items, 27);
        assertAgrees("A2:slot-absent", items, 27);
        // 雙向:vanilla 若真的把它放進 slot 0(全滿),摘要答「證明全滿」才是最有價值的;答不出也允許但要印出來
        System.out.println("[SummaryDifferentialTest] Slot 缺席:vanilla slot0Empty=" + t.empty()[0]
                + " vanillaFull=" + t.full() + " summaryTri=" + (packed == LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP ? "GIVEUP" : tri(packed)));
        if (packed != LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP) {
            assertEquals(t.full(), tri(packed) == 1, "缺 Slot 的滿判定必須與 vanilla 完全一致(雙向)");
        }
    }

    @Test
    @DisplayName("A2:count 為 ByteTag 的 27 格滿 —— 摘要應證明全滿且與 vanilla 一致")
    void byteTagCount() {
        ListTag items = new ListTag();
        for (int i = 0; i < 27; i++) {
            items.add(entry(ByteTag.valueOf((byte) i), "minecraft:cobblestone", ByteTag.valueOf((byte) MAX_STACK)));
        }
        Truth t = vanilla(items, 27);
        long packed = LazyContainerTemplate.lazycontainer$computeSummary(items, 27);
        assertAgrees("A2:count-byte", items, 27);
        assertTrue(t.full(), "前置:vanilla 對 ByteTag count=64 應判全滿");
        assertTrue(packed != LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP && tri(packed) == 1,
                "ByteTag count 的全滿箱應被摘要證明全滿(否則漏斗白掃)");
    }

    @Test
    @DisplayName("A2:Slot 超出 size —— 26 格滿 + 越界 entry ⟹ 不滿;27 格滿 + 越界 entry ⟹ 全滿")
    void slotBeyondSize() {
        ListTag notFull = twentySixFullPlus(entry(ByteTag.valueOf((byte) 27), "minecraft:cobblestone", IntTag.valueOf(MAX_STACK)));
        Truth t1 = vanilla(notFull, 27);
        long p1 = LazyContainerTemplate.lazycontainer$computeSummary(notFull, 27);
        assertAgrees("A2:beyond-notfull", notFull, 27);
        assertTrue(!t1.full(), "前置:越界 entry 被 vanilla 丟棄,slot 0 空 ⟹ 不滿");
        assertTrue(p1 != LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP && tri(p1) == 0, "摘要應證明不滿");

        ListTag full = new ListTag();
        for (int i = 0; i < 27; i++) {
            full.add(entry(ByteTag.valueOf((byte) i), "minecraft:cobblestone", IntTag.valueOf(MAX_STACK)));
        }
        full.add(entry(IntTag.valueOf(40), "minecraft:diamond", IntTag.valueOf(1)));
        Truth t2 = vanilla(full, 27);
        long p2 = LazyContainerTemplate.lazycontainer$computeSummary(full, 27);
        assertAgrees("A2:beyond-full", full, 27);
        assertTrue(t2.full(), "前置:越界 entry 不影響 27 格全滿");
        assertTrue(p2 != LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP && tri(p2) == 1, "摘要應證明全滿");
    }

    @Test
    @DisplayName("A2:重複 Slot —— 27 格滿 + 同格再一個少量 entry ⟹ 後寫者勝 ⟹ 不滿;反序 ⟹ 全滿")
    void duplicateSlotLastWriterWins() {
        ListTag a = new ListTag();
        for (int i = 0; i < 27; i++) {
            a.add(entry(ByteTag.valueOf((byte) i), "minecraft:cobblestone", IntTag.valueOf(MAX_STACK)));
        }
        a.add(entry(ByteTag.valueOf((byte) 5), "minecraft:cobblestone", IntTag.valueOf(1)));
        Truth ta = vanilla(a, 27);
        long pa = LazyContainerTemplate.lazycontainer$computeSummary(a, 27);
        assertAgrees("A2:dup-last-partial", a, 27);
        assertTrue(!ta.full(), "前置:後寫者(count=1)勝 ⟹ 不滿");
        assertTrue(pa != LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP && tri(pa) == 0, "摘要應證明不滿");

        ListTag b = new ListTag();
        b.add(entry(ByteTag.valueOf((byte) 5), "minecraft:cobblestone", IntTag.valueOf(1)));
        for (int i = 0; i < 27; i++) {
            b.add(entry(ByteTag.valueOf((byte) i), "minecraft:cobblestone", IntTag.valueOf(MAX_STACK)));
        }
        Truth tb = vanilla(b, 27);
        long pb = LazyContainerTemplate.lazycontainer$computeSummary(b, 27);
        assertAgrees("A2:dup-last-full", b, 27);
        assertTrue(tb.full(), "前置:後寫者(count=64)勝 ⟹ 全滿");
        assertTrue(pb != LazyContainerTemplate.LAZYCONTAINER$SUMMARY_GIVEUP && tri(pb) == 1, "摘要應證明全滿");
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
            // 位元組層面:Paper 把 CompoundTag 換成 fastutil Object2ObjectOpenHashMap(8, 0.8f),key 迭代順序
            // 不是插入順序——compound 內 ≥2 個 key 時 decode→encode 後順序可能調換(3000 份中有少數樣本;
            // 多數翻轉呈週期 2,但不是全部)。這是 Paper 既有性質、vanilla 重存也改順序,不是資料變更。
            // 三條斷言各守一件事:
            byte[] b2 = LazyContainerTemplate.lazycontainer$encodeRaw(back);
            // (1) 結構不變:重排不得改變任何內容(CompoundTag.equals 是 map 相等,順序無關)
            assertEquals(back, LazyContainerTemplate.lazycontainer$decodeRaw(b2), "再解一次必須結構相等(資料不變)");
            // (2) 決定性:存檔永遠從同一份 raw 出發,同一份 bytes 解兩次 encode 必須相同(每次存檔寫出同樣的東西)
            assertArrayEquals(b2, LazyContainerTemplate.lazycontainer$encodeRaw(LazyContainerTemplate.lazycontainer$decodeRaw(b)),
                    "同一份 raw 解兩次、各自 encode 必須逐位元組相同(存檔決定性)");
            // (3) 有鑑別力的強斷言:每個 compound 的 key 數 ≤1 的樣本(無順序可言)仍必須逐位元組穩定——
            //     哪天 encode 真的掉東西/改型別,這條會紅,不會被「順序」這個理由掩蓋
            if (maxCompoundKeys(items) <= 1) {
                assertArrayEquals(b, b2, "單 key compound 樣本必須逐位元組穩定(encode 不得掉東西或改型別)");
            }
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

    /** 遞迴找出整棵樹裡 compound 的最大 key 數(=0/1 的樣本沒有「順序」可言,可要求逐位元組穩定)。 */
    private static int maxCompoundKeys(Tag t) {
        int m = 0;
        if (t instanceof CompoundTag c) {
            m = c.size();
            for (String k : c.keySet()) {
                m = Math.max(m, maxCompoundKeys(c.get(k)));
            }
        } else if (t instanceof ListTag l) {
            for (Tag x : l) {
                m = Math.max(m, maxCompoundKeys(x));
            }
        }
        return m;
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
        if (r.nextInt(4) == 0) {
            // component 家族:合法/壞型別/不存在的 key/超範圍/移除語法/非 compound 全混進來——
            // 真 codec 是 oracle,摘要只要開口就必須一致;放寬「乾淨」規則後這裡是主要閘門
            CompoundTag comps = new CompoundTag();
            int kinds = 1 + r.nextInt(3);
            for (int k = 0; k < kinds; k++) {
                switch (r.nextInt(13)) {
                    case 0 -> comps.putInt("minecraft:max_stack_size", r.nextInt(120) - 10);
                    case 1 -> comps.putString("minecraft:custom_name", "\"x\"");
                    case 2 -> comps.putInt("!minecraft:max_stack_size", 1);
                    case 3 -> comps.putString("minecraft:lore", "garbage");            // 型別錯(要 list)
                    case 4 -> comps.putString("minecraft:enchantments", "nope");       // 型別錯
                    case 5 -> comps.putInt("minecraft:does_not_exist", 1);             // 不存在的 key
                    case 6 -> { CompoundTag cd = new CompoundTag(); cd.putString("shop", "x"); comps.put("minecraft:custom_data", cd); }
                    case 7 -> comps.putString("minecraft:max_stack_size", "bad");      // max 非數值
                    case 8 -> comps.putInt("minecraft:max_stack_size", 200);           // max 超範圍
                    case 9 -> comps.putInt("minecraft:repair_cost", r.nextInt(50));    // 合法整數 component
                    case 10 -> comps.putInt("minecraft:damage", r.nextInt(50));        // 合法但物品可能不可損
                    case 11 -> comps.putInt("max_stack_size", r.nextInt(120) - 10);     // 裸命名空間(vanilla 會正規化生效)
                    default -> { }
                }
                switch (r.nextInt(10)) {                                                 // 第二層:拼法陷阱
                    case 0 -> comps.putInt(":max_stack_size", r.nextInt(120) - 10);
                    case 1 -> comps.put("!minecraft:max_stack_size", new CompoundTag()); // 移除記號(值={} 才生效)
                    case 2 -> comps.putInt("!max_stack_size", 1);                       // 裸移除、值型別錯
                    case 3 -> comps.putInt("MINECRAFT:max_stack_size", 16);             // 大寫:tryParse 失敗被丟
                    case 4 -> comps.putLong("minecraft:max_stack_size", r.nextInt(120) - 10);
                    default -> { }
                }
            }
            e.put("components", comps);
        } else if (r.nextInt(40) == 0) {
            e.putString("components", "not-a-compound");                                // components 非 compound
        }
        return e;
    }
}
