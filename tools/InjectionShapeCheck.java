import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/**
 * 注入形狀閘門(換版第一道機械檢查)。
 *
 * <p>對「目標版本的真實 NMS jar」掃一遍,把 agent 所有注入假設濃縮成一份可 diff 的指紋:
 * 目標類別/方法是否存在且簽章一字不差、leaf 的 items 欄位被誰直接碰、存檔視窗
 * {@code LevelChunk.getBlockEntityNbtForSaving} 有哪些呼叫者、那些呼叫者對 CompoundTag 用了哪些方法、
 * 漏斗兩個 hook 的呼叫者是誰。</p>
 *
 * <p><b>為什麼是 diff 而不是硬編碼斷言</b>:換版真正危險的不是「方法不見了」(那會編譯失敗或開機大聲炸),
 * 而是「多了一段新程式」——例如存檔鏈中間新增一個用 entrySet 重建 tag 的步驟,會把直寫的側車靜默丟掉;
 * 或 leaf 新增一個直接讀 {@code items} 欄位、繞過 {@code getItems()} 咽喉的方法。這兩種都是「新增」,
 * 任何「檢查我想得到的東西還在不在」的斷言都抓不到。所以這裡輸出完整指紋、與上一版的基準逐行比對,
 * <b>多出來的行一樣要人看過</b>。</p>
 *
 * <p>用法:{@code java InjectionShapeCheck <nms.jar> [--baseline shape/<ver>.txt] [--emit]}</p>
 */
public final class InjectionShapeCheck {

    static final String P = "net/minecraft/world/level/block/entity/";
    static final String BASE = P + "BaseContainerBlockEntity";
    static final String CHEST = P + "ChestBlockEntity";
    static final String BARREL = P + "BarrelBlockEntity";
    static final String SHULKER = P + "ShulkerBoxBlockEntity";
    static final String HOPPER = P + "HopperBlockEntity";
    static final String CH = "net/minecraft/world/ContainerHelper";
    static final String COMPOUND = "net/minecraft/nbt/CompoundTag";
    static final String LEVEL_CHUNK = "net/minecraft/world/level/chunk/LevelChunk";
    static final String TVI = "net/minecraft/world/level/storage/TagValueInput";
    static final String TVO = "net/minecraft/world/level/storage/TagValueOutput";
    static final String NNL = "Lnet/minecraft/core/NonNullList;";
    static final String BE_NBT = "getBlockEntityNbtForSaving";

    /** 必須存在且簽章一字不差的注入目標(名稱 + descriptor)。 */
    static final String[][] REQUIRED = {
        {CHEST, "getItems", "()Lnet/minecraft/core/NonNullList;"},
        {CHEST, "setItems", "(Lnet/minecraft/core/NonNullList;)V"},
        {BARREL, "getItems", "()Lnet/minecraft/core/NonNullList;"},
        {BARREL, "setItems", "(Lnet/minecraft/core/NonNullList;)V"},
        {SHULKER, "getItems", "()Lnet/minecraft/core/NonNullList;"},
        {SHULKER, "setItems", "(Lnet/minecraft/core/NonNullList;)V"},
        {CH, "loadAllItems", "(Lnet/minecraft/world/level/storage/ValueInput;Lnet/minecraft/core/NonNullList;)V"},
        {CH, "saveAllItems", "(Lnet/minecraft/world/level/storage/ValueOutput;Lnet/minecraft/core/NonNullList;)V"},
        {CH, "saveAllItems", "(Lnet/minecraft/world/level/storage/ValueOutput;Lnet/minecraft/core/NonNullList;Z)V"},
        {HOPPER, "isFullContainer", "(Lnet/minecraft/world/Container;Lnet/minecraft/core/Direction;)Z"},
        {HOPPER, "tryTakeInItemFromSlot", "(L" + P + "Hopper;Lnet/minecraft/world/Container;ILnet/minecraft/core/Direction;Lnet/minecraft/world/level/Level;)Z"},
        {COMPOUND, "write", "(Ljava/io/DataOutput;)V"},
        {COMPOUND, "copy", "()Lnet/minecraft/nbt/CompoundTag;"},
        {LEVEL_CHUNK, BE_NBT, "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;"},
        {TVO, "buildResult", "()Lnet/minecraft/nbt/CompoundTag;"},
    };

    static final Set<String> WATCH = new HashSet<>(Arrays.asList(BASE, CHEST, BARREL, SHULKER, HOPPER));

    final TreeSet<String> out = new TreeSet<>();
    final Set<String> seenMethods = new HashSet<>();
    final Set<String> seenClasses = new HashSet<>();
    /** 存檔視窗呼叫者 → 該方法內用到的 CompoundTag 方法名 */
    final TreeMap<String, TreeSet<String>> nbtUse = new TreeMap<>();
    final Set<String> beNbtCallers = new HashSet<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: InjectionShapeCheck <nms.jar> [--baseline <file>]");
            System.exit(2);
        }
        String baseline = null;
        for (int i = 1; i < args.length; i++) {
            if ("--baseline".equals(args[i]) && i + 1 < args.length) {
                baseline = args[++i];
            }
        }
        InjectionShapeCheck c = new InjectionShapeCheck();
        c.scan(args[0]);
        c.finish();
        List<String> lines = new ArrayList<>(c.out);
        if (baseline == null) {
            for (String l : lines) {
                System.out.println(l);
            }
            System.err.println("(未指定 --baseline:只輸出指紋)");
            return;
        }
        File bf = new File(baseline);
        if (!bf.exists()) {
            for (String l : lines) {
                System.out.println(l);
            }
            System.err.println("G2 FAIL: 找不到基準 " + baseline
                    + " —— 這是新版本的第一次跑。請把上面的輸出存成該檔、逐行看過(特別是 ITEMSREF / CALLER / NBTUSE 的新增行),再重跑。");
            System.exit(1);
        }
        List<String> base = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(bf), "UTF-8"));
        for (String s; (s = r.readLine()) != null; ) {
            s = s.trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                base.add(s);
            }
        }
        r.close();
        TreeSet<String> b = new TreeSet<>(base);
        TreeSet<String> n = new TreeSet<>(lines);
        List<String> gone = new ArrayList<>(b);
        gone.removeAll(n);
        List<String> added = new ArrayList<>(n);
        added.removeAll(b);
        for (String g : gone) {
            System.out.println("- " + g);
        }
        for (String a : added) {
            System.out.println("+ " + a);
        }
        System.out.println("形狀行數 基準=" + b.size() + " 實際=" + n.size() + " 消失=" + gone.size() + " 新增=" + added.size());
        if (gone.isEmpty() && added.isEmpty()) {
            System.out.println("G2 PASS:注入形狀與基準完全一致");
        } else {
            System.out.println("G2 FAIL:注入形狀與基準不同(上面每一行都要人看過;確認安全後才更新基準檔)");
            System.exit(1);
        }
    }

    void scan(String jar) throws IOException {
        ZipFile z = new ZipFile(jar);
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (!e.getName().endsWith(".class")) {
                continue;
            }
            InputStream in = z.getInputStream(e);
            byte[] buf = readAll(in);
            in.close();
            try {
                new ClassReader(buf).accept(new Visitor(), ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            } catch (RuntimeException ignored) {
                // 少數非標準 classfile(shaded 依賴)略過:我們只在意 net/minecraft 與 ca/spottedleaf
            }
        }
        z.close();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int k; (k = in.read(b)) > 0; ) {
            bo.write(b, 0, k);
        }
        return bo.toByteArray();
    }

    void finish() {
        for (String[] r : REQUIRED) {
            String key = r[0] + "#" + r[1] + r[2];
            out.add((seenMethods.contains(key) ? "METHOD " : "MISSING-METHOD ") + key);
        }
        for (String c : new String[]{BASE, CHEST, BARREL, SHULKER, HOPPER, CH, COMPOUND, LEVEL_CHUNK, TVI, TVO}) {
            out.add((seenClasses.contains(c) ? "CLASS " : "MISSING-CLASS ") + c);
        }
        // 每個「存檔視窗呼叫者」都要有一行,沒碰 NBT 也要寫成 (none)——
        // 這樣未來版本一旦開始在那裡動 NBT,diff 就會顯形。
        for (String caller : new TreeSet<>(beNbtCallers)) {
            TreeSet<String> u = nbtUse.get(caller);
            out.add("NBTUSE " + caller + " " + (u == null || u.isEmpty() ? "(none)" : String.join(",", u)));
        }
    }

    final class Visitor extends ClassVisitor {
        String cls;
        String superName;
        Set<String> nnlFields = new HashSet<>();

        Visitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int v, int acc, String name, String sig, String sup, String[] itf) {
            cls = name;
            superName = sup;
            seenClasses.add(name);
            if (WATCH.contains(name)) {
                out.add("SUPER " + name + "=" + sup);
            }
        }

        @Override
        public FieldVisitor visitField(int acc, String name, String desc, String sig, Object val) {
            if (WATCH.contains(cls) && NNL.equals(desc)) {
                nnlFields.add(name);
                out.add("FIELD " + cls + "#" + name + ":" + desc + " acc=" + acc);
            }
            // template 直接讀 ((TagValueInput) input).input —— 欄位名/型別/存取權任一變都要知道
            if (TVI.equals(cls) || TVO.equals(cls)) {
                out.add("FIELD " + cls + "#" + name + ":" + desc + " acc=" + acc);
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
            seenMethods.add(cls + "#" + name + desc);
            final String me = cls + "#" + name + desc;
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitFieldInsn(int op, String owner, String fname, String fdesc) {
                    // leaf/base 內直接碰 items 欄位的方法(繞過 getItems 咽喉的候選)
                    if (WATCH.contains(owner) && NNL.equals(fdesc)) {
                        out.add("ITEMSREF " + me + " "
                                + (op == Opcodes.GETFIELD || op == Opcodes.GETSTATIC ? "GET" : "PUT")
                                + " " + owner + "#" + fname);
                    }
                }

                @Override
                public void visitMethodInsn(int op, String owner, String mname, String mdesc, boolean itf) {
                    if (CH.equals(owner) && (mname.equals("loadAllItems") || mname.equals("saveAllItems"))) {
                        out.add("CHCALL " + me + " -> " + mname + mdesc);
                    }
                    if (BE_NBT.equals(mname)) {
                        out.add("CALLER-BE-NBT " + me + " (owner " + owner + ")");
                        beNbtCallers.add(me);
                    }
                    if (HOPPER.equals(owner) && (mname.equals("isFullContainer") || mname.equals("tryTakeInItemFromSlot"))) {
                        out.add("HOPPERCALL " + mname + " <- " + me);
                    }
                    if (mname.equals("write") && mdesc.equals("(Ljava/io/DataOutput;)V")
                            && (COMPOUND.equals(owner) || "net/minecraft/nbt/Tag".equals(owner))) {
                        out.add("WRITECALL " + me + " (owner " + owner + ")");
                    }
                    // 存檔視窗呼叫者對「NBT 家族」用了哪些方法:直寫的側車只在原封不動被傳到 write() 時才活著,
                    // 中間任何「重建 compound」的新步驟都會在這一行顯形(例如出現 putAll / entrySet / merge)。
                    if (owner.startsWith("net/minecraft/nbt/")) {
                        TreeSet<String> s = nbtUse.get(me);
                        if (s == null) {
                            s = new TreeSet<>();
                            nbtUse.put(me, s);
                        }
                        s.add(owner.substring(owner.lastIndexOf('/') + 1) + "." + mname);
                    }
                }
            };
        }
    }
}
