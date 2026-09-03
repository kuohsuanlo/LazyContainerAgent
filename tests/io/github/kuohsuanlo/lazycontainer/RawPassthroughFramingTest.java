package io.github.kuohsuanlo.lazycontainer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.util.Random;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * raw passthrough(#261):chunk 存檔時未物化容器的 Items 不再 bytes→樹→bytes 繞一圈,
 * 而是由 CompoundTag.write 的注入碼把 raw bytes 原樣寫成一個具名 entry。
 * 這支測試釘住「框架正確性」:注入碼寫出的位元組流,用 vanilla NbtIo 讀回來必須與
 * 「正常 put 進去再 write」的結果結構相等——磁碟上的格式一個 bit 都不能錯。
 */
class RawPassthroughFramingTest {

    @BeforeAll
    static void bootstrap() {
        NmsTestSupport.bootstrap();
    }

    private static CompoundTag entry(Random r, int slot) {
        CompoundTag e = new CompoundTag();
        e.putByte("Slot", (byte) slot);
        e.putString("id", r.nextBoolean() ? "minecraft:stone" : "minecraft:diamond_sword");
        e.putInt("count", 1 + r.nextInt(64));
        if (r.nextInt(3) == 0) {
            CompoundTag comps = new CompoundTag();
            CompoundTag cd = new CompoundTag();
            cd.putString("shop", "x" + r.nextInt(1000));
            comps.put("minecraft:custom_data", cd);
            comps.putString("minecraft:custom_name", "\"n" + r.nextInt(9) + "\"");
            e.put("components", comps);
        }
        return e;
    }

    /** 模擬 transformer 注入後的 CompoundTag.write:先寫 raw entry,再寫其餘 entries(vanilla write)。 */
    private static byte[] emulateInjectedWrite(CompoundTag others, String key, byte[] raw) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(10);                                   // 外層:當成一個具名根 compound 寫(NbtIo.write 的框架)
        out.writeUTF("");
        LazyContainerRuntime.writeRawEntry(key, raw, out);   // ← 注入碼:prologue
        others.write(out);                                   // ← 原本的 write():其餘 entries + 結尾 0
        return bos.toByteArray();
    }

    private static byte[] vanillaWrite(CompoundTag full) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        NbtIo.write(full, new DataOutputStream(bos));
        return bos.toByteArray();
    }

    private static CompoundTag read(byte[] b) throws Exception {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(b)), NbtAccounter.unlimitedHeap());
    }

    @Test
    void injectedWriteReadsBackIdenticalToVanilla() throws Exception {
        Random r = new Random(261L);
        for (int iter = 0; iter < 3000; iter++) {
            ListTag items = new ListTag();
            int n = r.nextInt(28);
            for (int i = 0; i < n; i++) {
                items.add(entry(r, i));
            }
            byte[] raw = LazyContainerTemplate.lazycontainer$encodeRaw(items);
            // 存檔輸出的其他 entries(BE 的 id/x/y/z/keepPacked 等)
            CompoundTag others = new CompoundTag();
            others.putString("id", "minecraft:chest");
            others.putInt("x", r.nextInt(1000));
            others.putInt("y", r.nextInt(300));
            others.putInt("z", r.nextInt(1000));
            others.putBoolean("keepPacked", false);
            CompoundTag full = others.copy();
            full.put("Items", items);

            CompoundTag viaVanilla = read(vanillaWrite(full));
            CompoundTag viaInjected = read(emulateInjectedWrite(others, "Items", raw));
            assertEquals(viaVanilla, viaInjected, "注入碼寫出的流讀回來必須與 vanilla 結構相等(iter=" + iter + ")");
            assertEquals(items, viaInjected.get("Items"), "Items 內容必須逐格相同");
        }
    }

    @Test
    void rawEmptyListDetectionWithoutParsing() throws Exception {
        byte[] empty = LazyContainerTemplate.lazycontainer$encodeRaw(new ListTag());
        assertTrue(LazyContainerRuntime.rawIsListTag(empty));
        assertTrue(LazyContainerRuntime.rawListIsEmpty(empty), "空 ListTag 必須不解析就判得出來(shulker 的 allowEmpty=false)");
        ListTag one = new ListTag();
        one.add(entry(new Random(1), 0));
        byte[] b1 = LazyContainerTemplate.lazycontainer$encodeRaw(one);
        assertTrue(LazyContainerRuntime.rawIsListTag(b1));
        assertFalse(LazyContainerRuntime.rawListIsEmpty(b1));
        // 非 ListTag(例如壞資料是個 compound)⟹ 不得走 passthrough
        CompoundTag junk = new CompoundTag();
        junk.putInt("x", 1);
        byte[] bj = LazyContainerTemplate.lazycontainer$encodeRaw(junk);
        assertFalse(LazyContainerRuntime.rawIsListTag(bj));
    }

    @Test
    void chunkSaveDepthIsPerThreadAndBalanced() throws Exception {
        assertFalse(LazyContainerRuntime.inChunkSave());
        LazyContainerRuntime.enterChunkSave();
        assertTrue(LazyContainerRuntime.inChunkSave());
        LazyContainerRuntime.enterChunkSave();                 // 巢狀也要正確
        LazyContainerRuntime.exitChunkSave();
        assertTrue(LazyContainerRuntime.inChunkSave());
        LazyContainerRuntime.exitChunkSave();
        assertFalse(LazyContainerRuntime.inChunkSave());
        boolean[] other = new boolean[1];
        LazyContainerRuntime.enterChunkSave();
        Thread t = new Thread(() -> other[0] = LazyContainerRuntime.inChunkSave());
        t.start();
        t.join();
        LazyContainerRuntime.exitChunkSave();
        assertFalse(other[0], "旗標必須是 per-thread,別條執行緒不得看到");
    }
}
