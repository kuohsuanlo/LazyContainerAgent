package io.github.kuohsuanlo.lazycontainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 存檔守門(靜默清空偵測)。
 *
 * <h4>守的是哪一條不變式</h4>
 * <p>「這個容器<b>載入時有東西</b>、從載入到現在<b>沒有任何存取點碰過它</b>、存檔卻要寫出<b>空的</b>」
 * ——這在正常運作下不可能成立:沒被碰過的容器,agent 是把載入時收下的原始位元組原樣寫回去的,
 * 不會變空;而任何合法的取走(玩家、漏斗、比較器、外掛、指令)都必須先走 {@code getItems()/getContents()},
 * 那就會把 {@code accessed} 設起來。條件一旦成立就是資料事故,不是玩法。</p>
 *
 * <h4>為什麼要有這一關</h4>
 * <p>2026-09-08 s3 商場單一 chunk 內近乎所有容器同時被清空(面板 #121)。當時 agent 對「內容在沒人碰的
 * 情況下消失」完全沒有偵測能力:磁碟上寫出來的是一個結構完全合法的空清單,任何事後對帳都只能靠備份比對。
 * 這一關把它變成存檔當下就會叫的警報,並且在原始 bytes 還在時當場寫回去。</p>
 *
 * <p>四個負向案例(正常取走 / 載入時本來就空 / setItems 換清單 / 還有東西)必須全部安靜——
 * 誤報的代價是把玩家正當取走的東西寫回去,那是複製,比漏報更糟。</p>
 */
@DisplayName("存檔守門:靜默清空")
class SilentWipeGuardTest {

    private static final int SIZE = 27;
    private static final String[] IDS = {
        "minecraft:stone", "minecraft:dirt", "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:sand",
    };

    private static long wipesAtStart;

    @BeforeAll
    static void bootstrap() {
        NmsTestSupport.bootstrap();
        wipesAtStart = LazyContainerRuntime.silentWipe.sum();
    }

    /** 印一行證據:這一類真的跑過,而且真的有攔到東西(反空洞——gate 會 grep 這一行)。 */
    @AfterAll
    static void report() {
        System.out.println("[SilentWipeGuardTest] 攔截 " + (LazyContainerRuntime.silentWipe.sum() - wipesAtStart)
                + " 次(其中自救 " + LazyContainerRuntime.silentWipeHealed.sum() + " 次);負向案例全部安靜");
    }

    /** 報警會就地關掉直寫(safe mode)。那是全域旗標,不歸零會影響同一個 JVM 內其他測試類。 */
    @AfterEach
    void resetSafeMode() {
        LazyContainerRuntime.resetSafeModeForTests();
    }

    @Test
    @DisplayName("報警同時就地降級:直寫關閉,之後改走解析路徑")
    void alarmTripsSafeMode() {
        assertTrue(LazyContainerRuntime.passthrough(), "前提:直寫本來是開的");
        EnsureRaceTest.TestChest be = loaded(items(5));
        be.lazycontainer$pending = false;
        wipeListBehindTheAgentsBack(be);
        save(be);
        assertTrue(LazyContainerRuntime.safeMode(), "偵測到靜默清空就必須降級");
        assertTrue(!LazyContainerRuntime.passthrough(), "降級後直寫必須關閉");
    }

    // ───────────────────────── 工具 ─────────────────────────

    private static ListTag items(int n) {
        ListTag list = new ListTag();
        for (int i = 0; i < n; i++) {
            CompoundTag e = new CompoundTag();
            e.putByte("Slot", (byte) i);
            e.putString("id", IDS[i % IDS.length]);
            e.putInt("count", i + 1);
            list.add(e);
        }
        return list;
    }

    private static ValueInput input(ListTag list) {
        CompoundTag root = new CompoundTag();
        root.put("Items", list);
        return TagValueInput.create(ProblemReporter.DISCARDING, NmsTestSupport.registries(), root);
    }

    private static EnsureRaceTest.TestChest loaded(ListTag list) {
        EnsureRaceTest.TestChest be = new EnsureRaceTest.TestChest();
        be.lazycontainer$load(input(list), be.rawItems());
        return be;
    }

    /** 模擬「內容在沒人碰的情況下不見了」:直接把 leaf 的清單清乾淨,不經過任何存取點。 */
    private static void wipeListBehindTheAgentsBack(EnsureRaceTest.TestChest be) {
        NonNullList<ItemStack> list = be.rawItems();
        for (int i = 0; i < list.size(); i++) {
            list.set(i, ItemStack.EMPTY);
        }
    }

    private static Tag save(EnsureRaceTest.TestChest be) {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, NmsTestSupport.registries());
        be.lazycontainer$save(out, be.rawItems());
        return out.buildResult().get("Items");
    }

    private static long wipes() {
        return LazyContainerRuntime.silentWipe.sum();
    }

    private static long healed() {
        return LazyContainerRuntime.silentWipeHealed.sum();
    }

    // ───────────────────────── 正向:必須攔下 ─────────────────────────

    @Test
    @DisplayName("原始 bytes 還在 → 當場寫回去,資料零損失")
    void wipedButRawStillThere() {
        EnsureRaceTest.TestChest be = loaded(items(5));
        // 撕裂狀態:pending 被清掉、raw 還在(存檔的 trySaveRaw 因此不接手),清單是空的。
        be.lazycontainer$pending = false;
        wipeListBehindTheAgentsBack(be);

        long w0 = wipes();
        long h0 = healed();
        Tag saved = save(be);

        assertEquals(1, wipes() - w0, "應該記一次靜默清空");
        assertEquals(1, healed() - h0, "raw 還在就必須算自救成功");
        assertNotNull(saved, "自救後 Items 必須存在");
        assertTrue(saved instanceof ListTag, "Items 應為 ListTag");
        assertEquals(5, ((ListTag) saved).size(), "五格原封不動寫回去");
    }

    @Test
    @DisplayName("原始 bytes 已被物化吃掉 → 救不回來,但絕不寫出空清單")
    void wipedAfterMaterialization() {
        EnsureRaceTest.TestChest be = loaded(items(5));
        be.lazycontainer$ensure();                  // 存檔路徑自己的 fallback:物化但不算「被存取」
        assertTrue(be.rawItems().get(0).isEmpty() == false, "物化後應該有東西");
        wipeListBehindTheAgentsBack(be);

        long w0 = wipes();
        long h0 = healed();
        Tag saved = save(be);

        assertEquals(1, wipes() - w0, "應該記一次靜默清空");
        assertEquals(0, healed() - h0, "raw 已不在,不能宣稱自救");
        assertNull(saved, "救不回來時寧可不寫 Items,也不主動寫出空的");
    }

    // ───────────────────────── 負向:必須安靜 ─────────────────────────

    @Test
    @DisplayName("玩家正當取光 → 安靜,照常寫出空清單")
    void legitimateEmptyingIsSilent() {
        EnsureRaceTest.TestChest be = loaded(items(5));
        NonNullList<ItemStack> list = be.getItems();    // = leaf guard:這一下就是「被碰過」
        for (int i = 0; i < list.size(); i++) {
            list.set(i, ItemStack.EMPTY);
        }

        long w0 = wipes();
        Tag saved = save(be);

        assertEquals(0, wipes() - w0, "正當取走不得報警");
        assertNotNull(saved, "vanilla 對空箱仍會寫出空的 Items");
        assertEquals(0, ((ListTag) saved).size());
    }

    @Test
    @DisplayName("載入時本來就是空的 → 安靜")
    void loadedEmptyIsSilent() {
        EnsureRaceTest.TestChest be = loaded(items(0));
        be.lazycontainer$pending = false;

        long w0 = wipes();
        save(be);

        assertEquals(0, wipes() - w0, "沒東西可掉就沒有事故");
    }

    @Test
    @DisplayName("setItems 整批換清單 → 安靜(那也是一次正當存取)")
    void setItemsIsSilent() {
        EnsureRaceTest.TestChest be = loaded(items(5));
        be.lazycontainer$clear();                                          // = GUARD_CLEAR
        be.setItems(NonNullList.withSize(SIZE, ItemStack.EMPTY));

        long w0 = wipes();
        save(be);

        assertEquals(0, wipes() - w0, "外掛換清單不得報警");
    }

    @Test
    @DisplayName("還有東西 → 安靜(守門只管全空)")
    void partiallyEmptiedIsSilent() {
        EnsureRaceTest.TestChest be = loaded(items(5));
        be.lazycontainer$pending = false;
        NonNullList<ItemStack> list = be.rawItems();
        for (int i = 1; i < list.size(); i++) {
            list.set(i, ItemStack.EMPTY);
        }
        list.set(0, new ItemStack(net.minecraft.world.level.block.Blocks.STONE.asItem(), 1));

        long w0 = wipes();
        save(be);

        assertEquals(0, wipes() - w0, "只要還有一格有東西就不是靜默清空");
    }

    @Test
    @DisplayName("物化過程的重入 getItems 不算外部存取")
    void reentrantEnsureDoesNotCountAsAccess() {
        EnsureRaceTest.TestChest be = loaded(items(5));
        be.lazycontainer$ensure();      // ensure 內部會呼叫 this.getItems() → leaf guard → ensureAccessed
        assertTrue(be.lazycontainer$accessed == false,
                "ensure 自己的重入不得把 accessed 設起來,否則守門對每個物化過的容器都永久失效");
    }
}
