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
        LazyContainerRuntime.resetMassEmptyForTests();
    }

    /** 各測試共用同一條執行緒與同一個 chunk key,前一個測試留下的彙總項目會污染門檻計數。 */
    @org.junit.jupiter.api.BeforeEach
    void resetMassEmpty() {
        LazyContainerRuntime.resetMassEmptyForTests();
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
    @DisplayName("物化過、沒人碰過 → raw 還留著,當場寫回去")
    void wipedAfterMaterializationIsHealed() {
        EnsureRaceTest.TestChest be = loaded(items(5));
        be.lazycontainer$ensure();                  // 存檔路徑自己的 fallback:物化但不算「被存取」
        assertTrue(!be.rawItems().get(0).isEmpty(), "物化後應該有東西");
        assertNotNull(be.lazycontainer$raw, "26.2-7:物化後 raw 要留著");
        assertTrue(be.lazycontainer$keptSince != 0L, "留著的 raw 要有起算時間");
        wipeListBehindTheAgentsBack(be);

        long w0 = wipes();
        long h0 = healed();
        Tag saved = save(be);

        assertEquals(1, wipes() - w0, "應該記一次靜默清空");
        assertEquals(1, healed() - h0, "raw 留著 ⟹ 必須救回來");
        assertNotNull(saved, "自救後 Items 必須存在");
        assertEquals(5, ((ListTag) saved).size(), "五格原封不動寫回去");
        assertNull(be.lazycontainer$raw, "物化後的第一次存檔 = 釋放點,存完 raw 要放掉");
        assertEquals(0L, be.lazycontainer$keptSince);
    }

    @Test
    @DisplayName("留著的 raw:存檔正常就釋放(沒報錯就釋放)")
    void keptRawReleasedAfterCleanSave() {
        EnsureRaceTest.TestChest be = loaded(items(5));
        be.lazycontainer$ensure();
        assertNotNull(be.lazycontainer$raw);
        long w0 = wipes();
        Tag saved = save(be);
        assertEquals(0, wipes() - w0, "正常存檔不得報警");
        assertEquals(5, ((ListTag) saved).size());
        assertNull(be.lazycontainer$raw, "存檔正常 ⟹ 留著的 raw 釋放");
    }

    @Test
    @DisplayName("留著的 raw:超過上限就釋放(到期兜底)")
    void keptRawExpires() {
        EnsureRaceTest.TestChest be = loaded(items(5));
        be.lazycontainer$ensure();
        long now = System.nanoTime();
        assertTrue(!be.lazycontainer$releaseIfExpired(now, 60_000_000_000L), "還沒到期不得釋放");
        assertNotNull(be.lazycontainer$raw);
        assertTrue(be.lazycontainer$releaseIfExpired(now + 120_000_000_000L, 60_000_000_000L), "到期要釋放並回報可移除");
        assertNull(be.lazycontainer$raw);
    }

    @Test
    @DisplayName("有人碰過之後才變空 → 不寫回(那可能是玩家拿光的;寫回 = 複製)")
    void emptiedAfterAccessIsNeverHealed() {
        EnsureRaceTest.TestChest be = loaded(items(5));
        NonNullList<ItemStack> list = be.getItems();    // = leaf guard:標記 accessed 並物化,raw 留著
        assertTrue(be.lazycontainer$accessed);
        assertNotNull(be.lazycontainer$raw, "物化後 raw 留著");
        for (int i = 0; i < list.size(); i++) {
            list.set(i, ItemStack.EMPTY);
        }
        long w0 = wipes();
        long h0 = healed();
        Tag saved = save(be);
        assertEquals(0, wipes() - w0, "碰過的容器變空不是靜默清空");
        assertEquals(0, healed() - h0, "絕不寫回");
        assertNotNull(saved);
        assertEquals(0, ((ListTag) saved).size(), "照常寫出空清單");
    }

    @Test
    @DisplayName("整個 chunk 大面積「碰過之後歸零」→ 落檔 + 報警 + 降級(不寫回)")
    void massEmptyDumpsAndAlarms() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("lc-massempty").toFile();
        String old = System.getProperty("lazycontainer.dump.dir");
        System.setProperty("lazycontainer.dump.dir", dir.getAbsolutePath());
        try {
            long m0 = LazyContainerRuntime.massEmpty.sum();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.io.PrintStream oldErr = System.err;
            System.setErr(new java.io.PrintStream(bo));
            try {
                for (int n = 0; n < LazyContainerRuntime.MASS_EMPTY_MIN; n++) {
                    EnsureRaceTest.TestChest be = loaded(items(3));
                    NonNullList<ItemStack> list = be.getItems();
                    for (int i = 0; i < list.size(); i++) {
                        list.set(i, ItemStack.EMPTY);       // 整個 chunk 全清 = 佔比 100%
                    }
                    save(be);                                   // 同一條執行緒、同一個 chunk key
                }
                // 最後一個 chunk 要靠閒置結算(沒有「下一個 chunk」來觸發);測試用反射叫一次 sweep 前先讓它閒置
                Thread.sleep(2100);
                java.lang.reflect.Method sweep = LazyContainerRuntime.class.getDeclaredMethod("sweepMassEmpty");
                sweep.setAccessible(true);
                sweep.invoke(null);
            } finally {
                System.setErr(oldErr);
            }
            String err = bo.toString();
            assertEquals(1, LazyContainerRuntime.massEmpty.sum() - m0, "達門檻要記一次 MASS EMPTY:" + err);
            assertTrue(err.contains("MASS EMPTY"), "要大聲報警:" + err);
            assertTrue(err.contains("SAFE MODE"), "要就地降級:" + err);
            java.io.File[] dumps = dir.listFiles();
            assertTrue(dumps != null && dumps.length == 1, "要落一個檔");
            assertTrue(dumps[0].getName().startsWith("lc-massempty-"), dumps[0].getName());
            // 落檔要是可讀的 NBT:root compound → entries 列表 → 每筆有 x/y/z/Items
            CompoundTag root;
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(dumps[0]));
            try {
                root = net.minecraft.nbt.NbtIo.read(in);
            } finally {
                in.close();
            }
            ListTag entries = root.getListOrEmpty("entries");
            assertEquals(LazyContainerRuntime.MASS_EMPTY_MIN, entries.size(), "每個容器一筆(全清=100% 佔比)");
            CompoundTag e0 = entries.getCompoundOrEmpty(0);
            assertEquals(3, e0.getListOrEmpty("Items").size(), "原始 Items 原封不動");
        } finally {
            if (old == null) System.clearProperty("lazycontainer.dump.dir"); else System.setProperty("lazycontainer.dump.dir", old);
        }
    }

    @Test
    @DisplayName("佔比低的「碰過之後歸零」→ 安靜(2026-09-10 s3/s100 誤報:一個 chunk 只清 2~7%)")
    void lowFractionEmptyIsSilent() throws Exception {
        long m0 = LazyContainerRuntime.massEmpty.sum();
        // 同一個 chunk:MASS_EMPTY_MIN 個被清空,但另外有 20×那麼多的滿箱照常寫出 ⟹ 佔比 <5%
        int emptied = LazyContainerRuntime.MASS_EMPTY_MIN;
        for (int n = 0; n < emptied; n++) {
            EnsureRaceTest.TestChest be = loaded(items(3));
            NonNullList<ItemStack> list = be.getItems();
            for (int i = 0; i < list.size(); i++) {
                list.set(i, ItemStack.EMPTY);
            }
            save(be);
        }
        for (int n = 0; n < emptied * 20; n++) {
            EnsureRaceTest.TestChest be = loaded(items(3));
            be.getItems();                              // 物化、保持有貨
            save(be);                                   // 照常寫出 ⟹ 進分母
        }
        Thread.sleep(2100);
        java.lang.reflect.Method sweep = LazyContainerRuntime.class.getDeclaredMethod("sweepMassEmpty");
        sweep.setAccessible(true);
        sweep.invoke(null);
        assertEquals(0, LazyContainerRuntime.massEmpty.sum() - m0, "只清了個位數 % 不得報警(那是玩家搬家)");
    }

    @Test
    @DisplayName("絕對數沒達門檻 → 安靜")
    void fewEmptiedAfterAccessIsSilent() throws Exception {
        long m0 = LazyContainerRuntime.massEmpty.sum();
        for (int n = 0; n < LazyContainerRuntime.MASS_EMPTY_MIN - 1; n++) {
            EnsureRaceTest.TestChest be = loaded(items(3));
            NonNullList<ItemStack> list = be.getItems();
            for (int i = 0; i < list.size(); i++) {
                list.set(i, ItemStack.EMPTY);
            }
            save(be);
        }
        Thread.sleep(2100);
        java.lang.reflect.Method sweep = LazyContainerRuntime.class.getDeclaredMethod("sweepMassEmpty");
        sweep.setAccessible(true);
        sweep.invoke(null);
        assertEquals(0, LazyContainerRuntime.massEmpty.sum() - m0, "門檻以下不得報警");
    }

    // ───────────────────────── 寫入保真:記憶體 vs 寫出去的 ─────────────────────────

    @Test
    @DisplayName("寫入保真:記憶體有東西卻寫出空的 → 用記憶體那份補寫回去")
    void badWriteIsRepairedFromMemory() {
        EnsureRaceTest.TestChest be = loaded(items(5));
        NonNullList<ItemStack> list = be.getItems();        // 物化 + 標記碰過(記憶體才是真相來源)
        assertEquals(5, countNonEmpty(list));

        long b0 = LazyContainerRuntime.badWrite.sum();
        long f0 = LazyContainerRuntime.badWriteFixed.sum();
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, NmsTestSupport.registries());
        be.lazycontainer$saveBrokenForTest(out);            // 編碼完把 Items 拔掉

        assertEquals(1, LazyContainerRuntime.badWrite.sum() - b0, "寫壞了要報一次");
        assertEquals(1, LazyContainerRuntime.badWriteFixed.sum() - f0, "而且要補寫成功");
        Tag saved = out.buildResult().get("Items");
        assertNotNull(saved, "補寫後 Items 必須存在");
        assertEquals(5, ((ListTag) saved).size(), "五格用記憶體那份補回來");
    }

    @Test
    @DisplayName("寫入保真:玩家拿光 → 記憶體是空的,寫空的就是對的,不得報警")
    void emptyMemoryWritesEmptyWithoutAlarm() {
        EnsureRaceTest.TestChest be = loaded(items(5));
        NonNullList<ItemStack> list = be.getItems();
        for (int i = 0; i < list.size(); i++) {
            list.set(i, ItemStack.EMPTY);
        }
        long b0 = LazyContainerRuntime.badWrite.sum();
        Tag saved = save(be);
        assertEquals(0, LazyContainerRuntime.badWrite.sum() - b0, "記憶體說空的,寫空的不是寫壞");
        assertNotNull(saved);
        assertEquals(0, ((ListTag) saved).size());
    }

    @Test
    @DisplayName("寫入保真:還沒物化的容器拿 raw 當真相(側車沒掛上 → 補寫)")
    void pendingContainerVerifiedAgainstRaw() {
        EnsureRaceTest.TestChest be = loaded(items(5));
        assertTrue(be.lazycontainer$pending, "前提:還沒物化");
        long b0 = LazyContainerRuntime.badWrite.sum();
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, NmsTestSupport.registries());
        be.lazycontainer$saveBrokenForTest(out);
        assertEquals(1, LazyContainerRuntime.badWrite.sum() - b0, "raw 有 5 筆卻寫出 0 筆 ⟹ 要報");
        Tag saved = out.buildResult().get("Items");
        assertNotNull(saved, "要用 raw 補寫回去");
        assertEquals(5, ((ListTag) saved).size());
    }

    private static int countNonEmpty(NonNullList<ItemStack> list) {
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).isEmpty()) {
                n++;
            }
        }
        return n;
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
        be.lazycontainer$ensure();      // ensure 內部會呼叫 this.getItems() → leaf guard 的 accessed 標記
        assertTrue(be.lazycontainer$accessed == false,
                "ensure 自己的重入不得把 accessed 設起來,否則守門對每個物化過的容器都永久失效");
    }
}
