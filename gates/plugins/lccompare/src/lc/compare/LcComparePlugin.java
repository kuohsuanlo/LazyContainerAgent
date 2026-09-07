package lc.compare;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.InflaterInputStream;
import java.util.zip.GZIPInputStream;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 裁判 v3:在真伺服器裡(完整 registry / 資料包)把兩份 .mca 的每個帶 Items 的方塊實體逐格 ItemStack.matches。
 * - 逐 chunk 串流(整個 region 一次載入會 OOM);非同步跑(主執行緒會被 watchdog 砍)。
 * - 容器尺寸依 id:箱/木桶/界伏盒 27、漏斗 5、投擲器/發射器/合成器 9、熔爐類 3(與遊戲一致,Slot 超出者被丟)。
 * - 每個差異輸出兩邊完整解碼後的格子(id×count);expected 名單內的 key 就算相同也輸出(供逐格斷言)。
 * - 整個 region 的物品多重集合(id|components 雜湊 → 總數),供「漏斗只搬不造不毀」的守恆檢查。
 * - nearHopper 只認 6 鄰(漏斗實際只能與正上方/正對面互動)。
 * - untouched(不在 expected、不鄰漏斗)的容器另計 Items Tag 結構是否原封不動(直寫保真)。
 * - JSON 用 Gson,不手拼(控制字元會炸判定)。
 * jobs.txt 每行:label \t input.mca \t output.mca [\t expectedKeysFile];報告 plugins/LcCompare/report-<label>.json。
 */
public final class LcComparePlugin extends JavaPlugin {
    private static final Map<String, Integer> SIZE = new HashMap<>();
    static {
        for (String c : new String[]{"chest", "trapped_chest", "barrel", "shulker_box"}) SIZE.put("minecraft:" + c, 27);
        for (String c : new String[]{"white","orange","magenta","light_blue","yellow","lime","pink","gray",
                "light_gray","cyan","purple","blue","brown","green","red","black"}) SIZE.put("minecraft:" + c + "_shulker_box", 27);
        SIZE.put("minecraft:hopper", 5); SIZE.put("minecraft:dispenser", 9); SIZE.put("minecraft:dropper", 9); SIZE.put("minecraft:crafter", 9);
        SIZE.put("minecraft:furnace", 3); SIZE.put("minecraft:blast_furnace", 3); SIZE.put("minecraft:smoker", 3);
    }
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @Override public void onEnable() { Bukkit.getScheduler().runTaskLaterAsynchronously(this, this::run, 40L); }

    private void run() {
        File dir = getDataFolder(); dir.mkdirs();
        int fails = 0;
        try {
            for (String line : Files.readAllLines(new File(dir, "jobs.txt").toPath())) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\t");
                try { compare(p[0], p[1], p[2], p.length > 3 && !p[3].isBlank() ? p[3] : null, p.length > 4 ? p[4] : null); }
                catch (Throwable t) { getLogger().severe("JOB " + p[0] + " 炸了: " + t); t.printStackTrace(); fails++; }
            }
        } catch (IOException e) { getLogger().severe("jobs.txt 讀不到: " + e); fails++; }
        getLogger().info("LCCOMPARE ALL DONE fails=" + fails);
        Bukkit.getScheduler().runTask(this, () -> Bukkit.shutdown());
    }

    private static CompoundTag readChunk(byte[] raw, int i) throws IOException {
        int off = ((raw[i*4] & 0xFF) << 16) | ((raw[i*4+1] & 0xFF) << 8) | (raw[i*4+2] & 0xFF);
        if (off == 0) return null;
        int s = off * 4096;
        int len = ((raw[s] & 0xFF) << 24) | ((raw[s+1] & 0xFF) << 16) | ((raw[s+2] & 0xFF) << 8) | (raw[s+3] & 0xFF);
        int comp = raw[s+4] & 0x7F;
        InputStream in = new ByteArrayInputStream(raw, s + 5, len - 1);
        if (comp == 2) in = new InflaterInputStream(in); else if (comp == 1) in = new GZIPInputStream(in);
        else if (comp != 3) throw new IOException("compression " + comp + " unsupported at chunk idx " + i);
        return NbtIo.read(new DataInputStream(new BufferedInputStream(in, 1 << 16)), NbtAccounter.unlimitedHeap());
    }

    private static Map<String, CompoundTag> bes(CompoundTag chunk, Set<String> hoppers) {
        Map<String, CompoundTag> out = new LinkedHashMap<>();
        if (chunk == null) return out;
        ListTag list = chunk.getListOrEmpty("block_entities");
        for (int k = 0; k < list.size(); k++) {
            CompoundTag be = list.getCompoundOrEmpty(k);
            String id = be.getStringOr("id", "");
            String key = be.getIntOr("x", 0) + "," + be.getIntOr("y", 0) + "," + be.getIntOr("z", 0);
            if (id.equals("minecraft:hopper") && hoppers != null) hoppers.add(key);
            if (SIZE.containsKey(id)) out.put(key, be);
        }
        return out;
    }

    private NonNullList<ItemStack> decode(CompoundTag be) {
        int n = SIZE.getOrDefault(be.getStringOr("id", ""), 27);
        NonNullList<ItemStack> list = NonNullList.withSize(n, ItemStack.EMPTY);
        ValueInput vi = TagValueInput.create(ProblemReporter.DISCARDING, MinecraftServer.getServer().registryAccess(), be);
        ContainerHelper.loadAllItems(vi, list);
        return list;
    }

    private static CompoundTag others(CompoundTag be) { CompoundTag c = be.copy(); c.remove("Items"); c.remove("keepPacked"); return c; }

    private static boolean nearHopper6(String key, Set<String> hoppers) {
        String[] p = key.split(","); int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
        int[][] d = {{0,1,0},{0,-1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}};
        for (int[] o : d) if (hoppers.contains((x + o[0]) + "," + (y + o[1]) + "," + (z + o[2]))) return true;
        return false;
    }

    private static String idOf(ItemStack s) { return s.getItem().builtInRegistryHolder().key().identifier().toString(); }

    private static List<String> slotsOf(NonNullList<ItemStack> l) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) { ItemStack s = l.get(i); out.add(s.isEmpty() ? "" : idOf(s) + "x" + s.getCount()); }
        return out;
    }

    /**
     * 物品多重集合的鍵。
     * <p>⚠ 不能直接用 {@code getComponentsPatch().toString()}:那是個雜湊表,同一份資料解碼兩次的
     * 迭代順序可能不同,會讓「完全相同的兩份 region」算出上萬筆假差異(實測 msDelta=9554 而
     * 逐格 ItemStack.matches 是 0)。先把每個 component 的字串排序再串起來,才是穩定的身分。</p>
     */
    /**
     * 把一個 ItemStack 攤平成多重集合的貢獻,**包含巢狀容器內容**(界伏盒裡的東西)。
     *
     * <p>⚠ 不攤平會誤判:機器把一個裝滿的界伏盒倒空時,裡面的東西「變成」頂層物品,
     * 帳面上看起來是憑空多出來(實測 s45 +1728 = 27 格 × 64 個紅石燈)。攤平之後,
     * 倒空只是同一批東西換了層級,總數不變——這才是「只搬不造不毀」該有的口徑。</p>
     */
    private void addToMultiset(Map<String, Long> ms, ItemStack s) throws Exception {
        ms.merge(msKey(s), (long) s.getCount(), Long::sum);
        net.minecraft.world.item.component.ItemContainerContents cc =
                s.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (cc != null) {
            NonNullList<ItemStack> inner = NonNullList.withSize(net.minecraft.world.item.component.ItemContainerContents.MAX_SIZE, ItemStack.EMPTY);
            cc.copyInto(inner);
            for (ItemStack in : inner) if (!in.isEmpty()) addToMultiset(ms, in);
        }
    }

    private static String msKey(ItemStack s) throws Exception {
        List<String> parts = new ArrayList<>();
        // ⚠ 身分**不含** minecraft:container:巢狀內容已經被 addToMultiset 分開計算了,
        // 身分再包含它就是重複計算——一個裝滿的界伏盒被倒空時,身分會從「裝著 X 的界伏盒」
        // 變成「空界伏盒」,帳面上看起來是一種消失、另一種出現,但實際只是內容換了層級。
        s.getComponentsPatch().entrySet().forEach(en -> {
            String k = String.valueOf(en.getKey());
            if (!k.contains("container")) parts.add(k + "=" + String.valueOf(en.getValue()));
        });
        Collections.sort(parts);
        String comps = String.join(",", parts);
        byte[] h = MessageDigest.getInstance("MD5").digest(comps.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(String.format("%02x", h[i]));
        return idOf(s) + "|" + sb;
    }

    private void compare(String label, String inPath, String outPath, String expectedKeysFile, String excludeKeysFile) throws Exception {
        byte[] A = Files.readAllBytes(Paths.get(inPath)), B = Files.readAllBytes(Paths.get(outPath));
        Set<String> expectedKeys = new HashSet<>();
        if (expectedKeysFile != null && Files.exists(Paths.get(expectedKeysFile)))
            for (String k : Files.readAllLines(Paths.get(expectedKeysFile))) if (!k.isBlank()) expectedKeys.add(k.trim());
        // 機械碰過的位置(有事件證據):不列入 untouched。沒給就退回舊的「六鄰有漏斗」幾何近似。
        Set<String> excluded = new HashSet<>();
        if (excludeKeysFile != null && Files.exists(Paths.get(excludeKeysFile)))
            for (String k : Files.readAllLines(Paths.get(excludeKeysFile))) if (!k.isBlank()) excluded.add(k.trim());
        Set<String> hoppers = new HashSet<>();
        for (int i = 0; i < 1024; i++) bes(readChunk(A, i), hoppers);
        Map<String, Object> rep = new LinkedHashMap<>();
        List<Map<String, Object>> diffs = new ArrayList<>();
        Map<String, Object> expectedOut = new LinkedHashMap<>();
        Map<String, Long> msIn = new HashMap<>(), msOut = new HashMap<>();
        int inN = 0, outN = 0, same = 0, diff = 0, missing = 0, extra = 0, structSame = 0, othersSame = 0, nonEmptyIn = 0, nonEmptyOut = 0, chunksIn = 0, chunksOut = 0, untouchedStructSame = 0, untouchedN = 0;
        List<String> untouchedStructDiff = new ArrayList<>();
        for (int i = 0; i < 1024; i++) {
            CompoundTag ca = readChunk(A, i), cb = readChunk(B, i);
            if (ca != null) chunksIn++; if (cb != null) chunksOut++;
            Map<String, CompoundTag> a = bes(ca, null), b = bes(cb, null);
            inN += a.size(); outN += b.size();
            for (Map.Entry<String, CompoundTag> e : b.entrySet()) { for (ItemStack s : decode(e.getValue())) if (!s.isEmpty()) { nonEmptyOut++; addToMultiset(msOut, s); } }
            for (Map.Entry<String, CompoundTag> e : a.entrySet()) {
                String key = e.getKey(); CompoundTag bb = b.get(key);
                NonNullList<ItemStack> la = decode(e.getValue());
                for (ItemStack s : la) if (!s.isEmpty()) { nonEmptyIn++; addToMultiset(msIn, s); }
                boolean expected = expectedKeys.contains(key);
                boolean near = excluded.isEmpty() ? nearHopper6(key, hoppers) : excluded.contains(key);
                if (bb == null) {
                    missing++;
                    Map<String, Object> d = new LinkedHashMap<>(); d.put("key", key); d.put("kind", "MISSING"); d.put("id", e.getValue().getStringOr("id", "")); d.put("nearHopper", near); d.put("in", slotsOf(la)); diffs.add(d);
                    if (expected) { Map<String, Object> x = new LinkedHashMap<>(); x.put("in", slotsOf(la)); x.put("out", null); x.put("missing", true); expectedOut.put(key, x); }
                    continue;
                }
                Tag ia = e.getValue().get("Items"), ib = bb.get("Items");
                boolean st = Objects.equals(ia, ib); if (st) structSame++;
                boolean ot = Objects.equals(others(e.getValue()), others(bb)); if (ot) othersSame++;
                NonNullList<ItemStack> lb = decode(bb);
                List<Integer> bad = new ArrayList<>();
                for (int s = 0; s < la.size(); s++) if (!ItemStack.matches(la.get(s), lb.get(s))) bad.add(s);
                if (!expected && !near) { untouchedN++; if (st) untouchedStructSame++; else if (untouchedStructDiff.size() < 5000) untouchedStructDiff.add(key); }
                if (expected) { Map<String, Object> x = new LinkedHashMap<>(); x.put("in", slotsOf(la)); x.put("out", slotsOf(lb)); x.put("othersSame", ot); x.put("structSame", st); x.put("outItemsTag", String.valueOf(ib)); x.put("outOthers", String.valueOf(others(bb))); expectedOut.put(key, x); }
                if (bad.isEmpty() && ot) { same++; continue; }
                diff++;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("key", key); d.put("kind", "DIFF"); d.put("id", e.getValue().getStringOr("id", "")); d.put("slots", bad); d.put("othersSame", ot); d.put("structSame", st); d.put("nearHopper", near);
                d.put("in", slotsOf(la)); d.put("out", slotsOf(lb));
                if (!ot) {
                    d.put("inOthers", String.valueOf(others(e.getValue()))); d.put("outOthers", String.valueOf(others(bb)));
                    CompoundTag oa = others(e.getValue()), ob = others(bb); Set<String> dk = new TreeSet<>();
                    for (String kk : oa.keySet()) if (!Objects.equals(oa.get(kk), ob.get(kk))) dk.add(kk);
                    for (String kk : ob.keySet()) if (!oa.contains(kk)) dk.add(kk);
                    d.put("otherKeys", new ArrayList<>(dk));
                }
                diffs.add(d);
            }
            for (Map.Entry<String, CompoundTag> e : b.entrySet()) if (!a.containsKey(e.getKey())) {
                extra++;
                Map<String, Object> d = new LinkedHashMap<>(); d.put("key", e.getKey()); d.put("kind", "EXTRA"); d.put("id", e.getValue().getStringOr("id", "")); d.put("out", slotsOf(decode(e.getValue()))); d.put("nearHopper", nearHopper6(e.getKey(), hoppers)); diffs.add(d);
                if (expectedKeys.contains(e.getKey())) { Map<String, Object> x = new LinkedHashMap<>(); x.put("in", null); x.put("out", slotsOf(decode(e.getValue()))); x.put("extra", true); expectedOut.put(e.getKey(), x); }
            }
        }
        Map<String, long[]> msDelta = new TreeMap<>();
        for (String k : msIn.keySet()) { long o = msOut.getOrDefault(k, 0L); if (o != msIn.get(k)) msDelta.put(k, new long[]{msIn.get(k), o}); }
        for (String k : msOut.keySet()) if (!msIn.containsKey(k)) msDelta.put(k, new long[]{0, msOut.get(k)});
        long totalIn = msIn.values().stream().mapToLong(Long::longValue).sum(), totalOut = msOut.values().stream().mapToLong(Long::longValue).sum();
        rep.put("label", label); rep.put("chunksIn", chunksIn); rep.put("chunksOut", chunksOut); rep.put("in", inN); rep.put("out", outN);
        rep.put("same", same); rep.put("diff", diff); rep.put("missing", missing); rep.put("extra", extra); rep.put("structSame", structSame); rep.put("othersSame", othersSame);
        rep.put("nonEmptySlotsIn", nonEmptyIn); rep.put("nonEmptySlotsOut", nonEmptyOut); rep.put("itemsTotalIn", totalIn); rep.put("itemsTotalOut", totalOut);
        rep.put("hoppers", hoppers.size()); rep.put("untouchedN", untouchedN); rep.put("untouchedStructSame", untouchedStructSame); rep.put("untouchedStructDiffSample", untouchedStructDiff);
        rep.put("multisetDeltaKinds", msDelta.size()); rep.put("multisetDelta", msDelta); rep.put("expected", expectedOut); rep.put("diffs", diffs);
        rep.put("md5In", md5(A)); rep.put("md5Out", md5(B));
        Files.writeString(new File(getDataFolder(), "report-" + label + ".json").toPath(), GSON.toJson(rep));
        getLogger().info("LCCOMPARE " + label + " chunks=" + chunksIn + "/" + chunksOut + " in=" + inN + " out=" + outN + " same=" + same + " diff=" + diff + " missing=" + missing + " extra=" + extra
                + " structSame=" + structSame + " othersSame=" + othersSame + " nonEmpty=" + nonEmptyIn + "/" + nonEmptyOut + " items=" + totalIn + "/" + totalOut + " msDelta=" + msDelta.size()
                + " untouched=" + untouchedStructSame + "/" + untouchedN + " hoppers=" + hoppers.size());
    }

    private static String md5(byte[] b) throws Exception {
        byte[] h = MessageDigest.getInstance("MD5").digest(b); StringBuilder sb = new StringBuilder(); for (byte x : h) sb.append(String.format("%02x", x)); return sb.toString();
    }
}
