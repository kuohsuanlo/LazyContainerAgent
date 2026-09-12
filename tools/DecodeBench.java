package io.github.kuohsuanlo.lazycontainer;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

/**
 * 離線量測:26.2-2 存檔路徑每個 pending 容器付的「bytes → NBT 樹」parse 成本,序列 vs 多核心。
 * 素材 = 真實 region 檔(read-only)。不碰任何伺服器、不寫任何檔案。
 *
 * <pre>bash tools/decode_bench.sh &lt;r.X.Z.mca&gt; [more.mca ...]</pre>
 */
public final class DecodeBench {

    private DecodeBench() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("用法: DecodeBench <r.X.Z.mca> [...]");
            System.exit(2);
        }
        NmsTestSupport.bootstrap();
        List<byte[]> raws = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        long chunks = 0;
        long bes = 0;
        for (String path : args) {
            try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
                byte[] header = new byte[4096];
                raf.readFully(header);
                for (int i = 0; i < 1024; i++) {
                    int off = ((header[i * 4] & 0xff) << 16) | ((header[i * 4 + 1] & 0xff) << 8) | (header[i * 4 + 2] & 0xff);
                    if (off == 0) {
                        continue;
                    }
                    raf.seek(off * 4096L);
                    int len = raf.readInt();
                    int type = raf.readByte() & 0xff;
                    if ((type & 0x80) != 0 || len <= 1) {
                        continue;   // 外置 .mcc / 空 chunk:略過
                    }
                    byte[] data = new byte[len - 1];
                    raf.readFully(data);
                    InputStream in;
                    switch (type) {
                        case 1: in = new GZIPInputStream(new ByteArrayInputStream(data)); break;
                        case 2: in = new InflaterInputStream(new ByteArrayInputStream(data)); break;
                        case 3: in = new ByteArrayInputStream(data); break;
                        default: continue;   // lz4 等:略過
                    }
                    CompoundTag chunk = NbtIo.read(new DataInputStream(new BufferedInputStream(in)), NbtAccounter.unlimitedHeap());
                    chunks++;
                    Tag beList = chunk.get("block_entities");
                    if (!(beList instanceof ListTag)) {
                        continue;
                    }
                    for (Tag t : (ListTag) beList) {
                        if (!(t instanceof CompoundTag)) {
                            continue;
                        }
                        bes++;
                        Tag items = ((CompoundTag) t).get("Items");
                        if (!(items instanceof ListTag)) {
                            continue;
                        }
                        raws.add(LazyContainerTemplate.lazycontainer$encodeRaw(items));
                        int maxSlot = -1;
                        for (Tag it : (ListTag) items) {
                            if (it instanceof CompoundTag) {
                                maxSlot = Math.max(maxSlot, ((CompoundTag) it).getByteOr("Slot", (byte) 0));
                            }
                        }
                        sizes.add(Math.max(27, maxSlot + 1));
                    }
                }
            }
        }
        long bytes = 0;
        int nonEmpty = 0;
        for (byte[] r : raws) {
            bytes += r.length;
            if (r.length > 8) {
                nonEmpty++;
            }
        }
        System.out.printf("素材: chunk=%d 方塊實體=%d 有 Items 的容器=%d(非空 %d) raw 總量=%.1f MB 平均 %.0f bytes/容器%n",
                chunks, bes, raws.size(), nonEmpty, bytes / 1048576.0, raws.isEmpty() ? 0.0 : (double) bytes / raws.size());
        int cores = Runtime.getRuntime().availableProcessors();
        System.out.println("本機 CPU=" + cores);

        // ── A:26.2-2 存檔路徑的 parse(bytes → ListTag),序列 ──
        for (int w = 0; w < 2; w++) {
            serialParse(raws);
        }
        long a = 0;
        for (int r = 0; r < 3; r++) {
            a += serialParse(raws);
        }
        a /= 3;
        System.out.printf("A 序列 parse(=26.2-2 存這個 region 一次的解碼成本): %d ms  (%.1f µs/容器)%n", a / 1_000_000, a / 1000.0 / Math.max(1, raws.size()));

        // ── B:同一件事,多核心 ──
        for (int p : new int[] {2, 4, 8, cores}) {
            if (p > cores) {
                continue;
            }
            ForkJoinPool pool = new ForkJoinPool(p);
            try {
                for (int w = 0; w < 2; w++) {
                    parallelParse(raws, pool);
                }
                long b = 0;
                for (int r = 0; r < 3; r++) {
                    b += parallelParse(raws, pool);
                }
                b /= 3;
                System.out.printf("B 平行 parse ×%d 執行緒: %d ms  (加速 %.1fx)%n", p, b / 1_000_000, (double) a / Math.max(1, b));
            } finally {
                pool.shutdown();
            }
        }

        // ── C:物化(item codec)序列 vs 平行,並逐格比對結果 ──
        List<NonNullList<ItemStack>> serial = new ArrayList<>(raws.size());
        long c0 = System.nanoTime();
        int codecFail = 0;
        for (int i = 0; i < raws.size(); i++) {
            NonNullList<ItemStack> list = NonNullList.withSize(sizes.get(i), ItemStack.EMPTY);
            try {
                materialize(raws.get(i), list);
            } catch (Throwable t) {
                codecFail++;
            }
            serial.add(list);
        }
        long c = System.nanoTime() - c0;
        System.out.printf("C 序列物化(item codec,=ensure 全部容器一次): %d ms  (%.1f µs/容器;headless 解不開 %d 個)%n",
                c / 1_000_000, c / 1000.0 / Math.max(1, raws.size()), codecFail);
        ForkJoinPool pool = new ForkJoinPool(Math.min(8, cores));
        try {
            long d0 = System.nanoTime();
            List<NonNullList<ItemStack>> par = pool.submit(() -> {
                List<NonNullList<ItemStack>> out = new ArrayList<>(raws.size());
                for (int i = 0; i < raws.size(); i++) {
                    out.add(null);
                }
                java.util.stream.IntStream.range(0, raws.size()).parallel().forEach(i -> {
                    NonNullList<ItemStack> list = NonNullList.withSize(sizes.get(i), ItemStack.EMPTY);
                    try {
                        materialize(raws.get(i), list);
                    } catch (Throwable t) {
                        // 與序列同樣計為失敗
                    }
                    synchronized (out) {
                        out.set(i, list);
                    }
                });
                return out;
            }).get();
            long d = System.nanoTime() - d0;
            int mismatch = 0;
            for (int i = 0; i < raws.size(); i++) {
                NonNullList<ItemStack> x = serial.get(i);
                NonNullList<ItemStack> y = par.get(i);
                if (x.size() != y.size()) {
                    mismatch++;
                    continue;
                }
                for (int s = 0; s < x.size(); s++) {
                    if (!ItemStack.matches(x.get(s), y.get(s))) {
                        mismatch++;
                        break;
                    }
                }
            }
            System.out.printf("D 平行物化 ×%d: %d ms  (加速 %.1fx;與序列逐格比對不同的容器=%d)%n",
                    Math.min(8, cores), d / 1_000_000, (double) c / Math.max(1, d), mismatch);
        } finally {
            pool.shutdown();
        }
    }

    private static long serialParse(List<byte[]> raws) throws Exception {
        long t0 = System.nanoTime();
        long sink = 0;
        for (byte[] r : raws) {
            Tag t = LazyContainerTemplate.lazycontainer$decodeRaw(r);
            sink += (t instanceof ListTag) ? ((ListTag) t).size() : 0;
        }
        if (sink < 0) {
            System.out.println(sink);
        }
        return System.nanoTime() - t0;
    }

    private static long parallelParse(List<byte[]> raws, ForkJoinPool pool) throws Exception {
        long t0 = System.nanoTime();
        long sink = pool.submit(() -> raws.parallelStream().mapToLong(r -> {
            try {
                Tag t = LazyContainerTemplate.lazycontainer$decodeRaw(r);
                return (t instanceof ListTag) ? ((ListTag) t).size() : 0;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).sum()).get();
        if (sink < 0) {
            System.out.println(sink);
        }
        return System.nanoTime() - t0;
    }

    private static void materialize(byte[] raw, NonNullList<ItemStack> list) throws Exception {
        Tag t = LazyContainerTemplate.lazycontainer$decodeRaw(raw);
        CompoundTag tmp = new CompoundTag();
        tmp.put("Items", t);
        ValueInput vi = TagValueInput.createGlobal(ProblemReporter.DISCARDING, tmp);
        ContainerHelper.loadAllItems(vi, list);
    }
}
