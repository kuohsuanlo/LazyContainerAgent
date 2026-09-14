package io.github.kuohsuanlo.lazycontainer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Random;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分段 raw 緩衝(26.2-10)的對拍測試。
 *
 * <p>要證明的只有一件事:<b>換成分段之後,寫出去的位元組序列跟舊版一模一樣</b>。
 * 舊版 = {@code ByteArrayOutputStream(256)} 容量倍增再 {@code toByteArray()} 複製一次
 * (那個做法在大容器上會配出一串 G1 humongous 物件,是 s18 每小時數次 Full GC 的來源之一)。</p>
 *
 * <p>分段本身是純資料搬運、與 Minecraft 語意無關,所以這裡把邊界情況窮舉掉:
 * 剛好填滿一段、差一個、跨兩段、單位元組寫入與整塊寫入混合、讀取端分次讀。</p>
 */
class RawSegmentsTest {

    @BeforeAll
    static void boot() {
        NmsTestSupport.bootstrap();
    }

    /** 舊版(單一連續緩衝)的 encode,當作神諭。 */
    private static byte[] referenceEncode(Tag tag) throws Exception {
        if (tag == null) {
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
        DataOutputStream dos = new DataOutputStream(bos);
        NbtIo.writeAnyTag(tag, dos);
        return bos.toByteArray();
    }

    private static byte[] flat(byte[][] segs) {
        if (segs == null) {
            return null;
        }
        int n = 0;
        for (byte[] s : segs) {
            n += s.length;
        }
        byte[] out = new byte[n];
        int p = 0;
        for (byte[] s : segs) {
            System.arraycopy(s, 0, out, p, s.length);
            p += s.length;
        }
        return out;
    }

    /** 造一個序列化後大約 targetBytes 大小的 Items 清單(用字串長度撐)。 */
    private static ListTag bulkyItems(Random r, int targetBytes) {
        ListTag items = new ListTag();
        int written = 0;
        int slot = 0;
        while (written < targetBytes) {
            CompoundTag e = new CompoundTag();
            e.putByte("Slot", (byte) (slot++ & 0x7F));
            e.putString("id", "minecraft:diamond");
            e.putInt("count", 1 + r.nextInt(64));
            int len = 64 + r.nextInt(4096);
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                sb.append((char) ('a' + r.nextInt(26)));
            }
            CompoundTag comps = new CompoundTag();
            comps.putString("minecraft:custom_name", sb.toString());
            e.put("components", comps);
            items.add(e);
            written += len + 40;
        }
        return items;
    }

    @Test
    @DisplayName("分段 encode 的位元組序列 = 舊版單一緩衝 encode(含跨段、剛好填滿、超大)")
    void bytesIdenticalToReference() throws Exception {
        Random r = new Random(20260914L);
        // 涵蓋:單段內、剛好一段、跨兩段、跨多段、遠大於單段上限
        int[] targets = {0, 16, 1024, 8 * 1024 - 64, 8 * 1024, 8 * 1024 + 64,
                64 * 1024, 72 * 1024 + 7, 256 * 1024, 300 * 1024, 1024 * 1024, 3 * 1024 * 1024};
        for (int target : targets) {
            ListTag items = bulkyItems(r, target);
            byte[][] segs = LazyContainerTemplate.lazycontainer$encodeRaw(items);
            byte[] ref = referenceEncode(items);
            assertArrayEquals(ref, flat(segs),
                    "分段 encode 必須與舊版逐位元組相同(target≈" + target + " bytes)");
            // 每一段都必須小於 humongous 門檻的安全值,否則這個改動就白做了
            for (byte[] s : segs) {
                assertTrue(s.length <= LazyContainerRuntime.SEG_MAX,
                        "單段不得超過 SEG_MAX(" + s.length + ")");
            }
            // 讀回來必須結構相等
            Tag back = LazyContainerTemplate.lazycontainer$decodeRaw(segs);
            assertEquals(items, back, "decode(encode(t)) 必須等於 t");
            // 分段總長 = 舊版長度
            assertEquals(ref.length, LazyContainerRuntime.rawLength(segs), "rawLength 必須等於真實長度");
        }
        assertNull(LazyContainerTemplate.lazycontainer$encodeRaw(null));
        assertNull(LazyContainerTemplate.lazycontainer$decodeRaw(null));
    }

    @Test
    @DisplayName("RawOut:單位元組與整塊寫入混合,結果與參考緩衝相同")
    void outputStreamMixedWrites() throws Exception {
        Random r = new Random(99L);
        for (int iter = 0; iter < 200; iter++) {
            int n = r.nextInt(600 * 1024);
            byte[] src = new byte[n];
            r.nextBytes(src);
            LazyContainerRuntime.RawOut out = new LazyContainerRuntime.RawOut();
            int p = 0;
            while (p < n) {
                if (r.nextInt(4) == 0) {
                    out.write(src[p++] & 0xFF);
                } else {
                    int len = Math.min(n - p, 1 + r.nextInt(200 * 1024));
                    out.write(src, p, len);
                    p += len;
                }
            }
            byte[][] segs = out.toSegments();
            assertArrayEquals(src, flat(segs), "混合寫入的位元組必須完全一致");
            assertEquals(n, out.size(), "size() 必須等於寫入量");
            assertEquals(n, LazyContainerRuntime.rawLength(segs), "rawLength 必須等於寫入量");
        }
    }

    @Test
    @DisplayName("RawIn:單位元組與整塊讀取混合、跨段邊界,讀回的位元組完全一致")
    void inputStreamMixedReads() throws Exception {
        Random r = new Random(1234L);
        for (int iter = 0; iter < 200; iter++) {
            int n = r.nextInt(600 * 1024);
            byte[] src = new byte[n];
            r.nextBytes(src);
            LazyContainerRuntime.RawOut out = new LazyContainerRuntime.RawOut();
            out.write(src, 0, n);
            byte[][] segs = out.toSegments();

            LazyContainerRuntime.RawIn in = new LazyContainerRuntime.RawIn(segs);
            byte[] got = new byte[n];
            int p = 0;
            while (p < n) {
                if (r.nextInt(4) == 0) {
                    int b = in.read();
                    assertTrue(b >= 0, "資料還沒讀完不該回 -1");
                    got[p++] = (byte) b;
                } else {
                    int len = Math.min(n - p, 1 + r.nextInt(70 * 1024));
                    int c = in.read(got, p, len);
                    assertTrue(c > 0, "資料還沒讀完不該回 " + c);
                    p += c;
                }
            }
            assertArrayEquals(src, got, "讀回的位元組必須完全一致");
            assertEquals(-1, in.read(), "讀完之後必須回 -1");
            assertEquals(-1, in.read(new byte[8], 0, 8), "讀完之後整塊讀也必須回 -1");
        }
    }

    @Test
    @DisplayName("DataInput/DataOutput 往返:大字串跨段不得被截斷")
    void dataStreamRoundTripAcrossSegments() throws Exception {
        Random r = new Random(7L);
        for (int len : new int[] {1, 255, 8 * 1024, 64 * 1024 - 1, 65535}) {
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                sb.append((char) ('a' + r.nextInt(26)));
            }
            String str = sb.toString();
            LazyContainerRuntime.RawOut out = new LazyContainerRuntime.RawOut();
            DataOutputStream dos = new DataOutputStream(out);
            dos.writeUTF(str);
            dos.writeLong(0x0123456789ABCDEFL);
            dos.writeInt(-42);
            dos.flush();
            DataInputStream dis = new DataInputStream(new LazyContainerRuntime.RawIn(out.toSegments()));
            assertEquals(str, dis.readUTF(), "跨段的字串不得被截斷");
            assertEquals(0x0123456789ABCDEFL, dis.readLong());
            assertEquals(-42, dis.readInt());
        }
    }

    @Test
    @DisplayName("NbtIo 直接吃分段流:深巢結構往返恆等")
    void nbtIoOverSegments() throws Exception {
        Random r = new Random(31337L);
        ListTag items = bulkyItems(r, 900 * 1024);
        byte[][] segs = LazyContainerTemplate.lazycontainer$encodeRaw(items);
        assertTrue(segs.length > 3, "這個大小應該要跨多段(實際 " + segs.length + " 段)");
        DataInputStream dis = new DataInputStream(new LazyContainerRuntime.RawIn(segs));
        Tag back = NbtIo.readAnyTag(dis, NbtAccounter.unlimitedHeap());
        assertEquals(items, back, "跨多段的 NBT 必須完整讀回");
    }
}
