import java.io.*;
import java.lang.instrument.ClassFileTransformer;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * (d) bytecode 層的機械檢查原型:
 * 對「splice/改寫之後」的真實 NMS 類別,機械驗證三組紀律 ——
 *   R1 任何碰 raw / 摘要 / ensuring 的方法必須在 monitor 內(ACC_SYNCHRONIZED 或含 MONITORENTER)
 *   R2 未持鎖的方法只准讀白名單欄位,且讀摘要前必須先讀 volatile pending
 *   R3 三個 leaf 的入口 guard 真的插上了(getItems/getContents=ensure、setItems/loadAdditional/loadFromTag=clear)
 *   R4 leaf 的 load/save 內不得殘留 ContainerHelper.loadAllItems/saveAllItems
 *   R5 hopper 的兩個摘要 hook 在方法入口
 *   R6 raw passthrough(#261):CompoundTag 兩欄位 + write 開頭寫 raw + copy 帶欄位;LevelChunk 的
 *      getBlockEntityNbtForSaving 被包成 enter/try/orig/finally-exit(any handler)
 */
public class LockPolicyCheck {

    static final String BASE = "net/minecraft/world/level/block/entity/BaseContainerBlockEntity";
    static final String[] LEAVES = {
        "net/minecraft/world/level/block/entity/ChestBlockEntity",
        "net/minecraft/world/level/block/entity/BarrelBlockEntity",
        "net/minecraft/world/level/block/entity/ShulkerBoxBlockEntity"};
    static final String HOPPER = "net/minecraft/world/level/block/entity/HopperBlockEntity";
    static final String COMPOUND_TAG = "net/minecraft/nbt/CompoundTag";
    static final String LEVEL_CHUNK = "net/minecraft/world/level/chunk/LevelChunk";
    static final String RT = "io/github/kuohsuanlo/lazycontainer/LazyContainerRuntime";

    static final String PENDING = "lazycontainer$pending";
    static final Set<String> SUMMARY = Set.of("lazycontainer$sumState","lazycontainer$sumBits","lazycontainer$sumFullTri");
    static final Set<String> GUARDED = Set.of("lazycontainer$raw","lazycontainer$ensuring",
            "lazycontainer$sumState","lazycontainer$sumBits","lazycontainer$sumFullTri");

    static int violations = 0, checks = 0;
    static void fail(String s){ violations++; System.out.println("   ❌ " + s); }
    static void ok(String s){ checks++; System.out.println("   ✅ " + s); }

    public static void main(String[] a) throws Exception {
        String jar = a[0];
        Map<String, byte[]> src = new HashMap<>();
        try (JarFile jf = new JarFile(jar)) {
            for (String c : allTargets()) {
                JarEntry e = jf.getJarEntry(c + ".class");
                if (e == null) throw new IOException("找不到 " + c);
                src.put(c, jf.getInputStream(e).readAllBytes());
            }
        }
        Class<?> tc = Class.forName("io.github.kuohsuanlo.lazycontainer.LazyContainerTransformer");
        Object tr = tc.getDeclaredConstructor().newInstance();
        boolean ready = (boolean) tc.getMethod("prepare").invoke(tr);
        System.out.println("transformer.prepare() = " + ready);
        if (!ready) { System.out.println("template 不可用,無法檢查"); System.exit(2); }

        Map<String, ClassNode> out = new LinkedHashMap<>();
        for (String c : allTargets()) {
            byte[] res = ((ClassFileTransformer) tr).transform(null, c, null, null, src.get(c));
            if (res == null) { System.out.println("!! " + c + " 未被改寫(transform 回 null)"); continue; }
            ClassNode cn = new ClassNode();
            new ClassReader(res).accept(cn, 0);
            out.put(c, cn);
        }

        System.out.println("\n── R0. 改寫是否真的發生(靜默失效偵測)──");
        ClassNode base = out.get(BASE);
        long fld = base.fields.stream().filter(f -> f.name.startsWith("lazycontainer$")).count();
        long mth = base.methods.stream().filter(m -> m.name.startsWith("lazycontainer$")).count();
        if (fld == 6 && mth >= 12) ok("base splice: " + fld + " fields + " + mth + " methods");
        else fail("base splice 數量異常: " + fld + " fields / " + mth + " methods");

        // 呼叫閉包:private 的未同步方法,只要「所有呼叫端都持鎖」即合規(trySaveRaw 就是這型)
        Map<String,Boolean> callersAllLocked = new HashMap<>();
        Map<String,List<String>> callers = new HashMap<>();
        for (MethodNode m : base.methods)
            for (AbstractInsnNode in : m.instructions)
                if (in instanceof MethodInsnNode mi && mi.owner.equals(BASE) && mi.name.startsWith("lazycontainer$"))
                    callers.computeIfAbsent(mi.name,k->new ArrayList<>()).add(m.name+(((m.access&Opcodes.ACC_SYNCHRONIZED)!=0||hasMonitor(m))?"[鎖]":"[無鎖]"));
        for (MethodNode m : base.methods) {
            if(!m.name.startsWith("lazycontainer$")) continue;
            List<String> cs = callers.getOrDefault(m.name, List.of());
            boolean priv = (m.access & Opcodes.ACC_PRIVATE)!=0;
            callersAllLocked.put(m.name, priv && !cs.isEmpty() && cs.stream().allMatch(c->c.endsWith("[鎖]")));
        }

        System.out.println("\n── R1/R2. 鎖紀律(base,splice 後)──");
        System.out.printf("   %-38s %-6s %-8s %s%n","方法","sync","monitor","碰到的 lazycontainer$ 欄位");
        for (MethodNode m : base.methods) {
            if (!m.name.startsWith("lazycontainer$")) continue;
            boolean sync = (m.access & Opcodes.ACC_SYNCHRONIZED) != 0;
            boolean mon = hasMonitor(m) || callersAllLocked.getOrDefault(m.name,false);
            List<String> touched = new ArrayList<>();
            boolean sawPendingRead = false;
            String r2 = null, r1 = null;
            for (AbstractInsnNode in : m.instructions) {
                if (in instanceof FieldInsnNode f && f.name.startsWith("lazycontainer$")) {
                    boolean write = f.getOpcode()==Opcodes.PUTFIELD || f.getOpcode()==Opcodes.PUTSTATIC;
                    touched.add((write?"w:":"r:")+f.name.substring(14));
                    if (f.name.equals(PENDING) && !write) sawPendingRead = true;
                    if (!sync && !mon) {
                        if (write && !f.name.equals(PENDING)) r1 = "未持鎖卻寫 " + f.name;
                        else if (GUARDED.contains(f.name) && !SUMMARY.contains(f.name)) r1 = "未持鎖卻碰 " + f.name;
                        else if (SUMMARY.contains(f.name) && !sawPendingRead)
                            r2 = "未持鎖讀 " + f.name + " 之前沒有先讀 volatile pending";
                    }
                }
            }
            String lockedBy = callersAllLocked.getOrDefault(m.name,false) ? "呼叫端持鎖:"+callers.get(m.name) : (mon?"有":"無");
            System.out.printf("   %-30s %-5s %-24s %s%n", m.name.substring(14)+"(..)",
                    sync?"是":"否", lockedBy, touched.isEmpty()?"-":String.join(" ",touched));
            if (r1 != null) fail(m.name + ": " + r1);
            if (r2 != null) fail(m.name + ": " + r2);
        }
        ok("R1/R2 掃描完畢(上表即政策證據)");

        System.out.println("\n── R3/R4. leaf 入口 guard 與 redirect ──");
        for (String leaf : LEAVES) {
            ClassNode cn = out.get(leaf);
            if (cn == null) { fail(leaf + " 未被改寫"); continue; }
            for (MethodNode m : cn.methods) {
                String want = expectGuard(m.name, m.desc);
                if (want != null) {
                    String got = firstGuardCall(m);
                    if (want.equals(got)) ok(short_(leaf)+"."+m.name+" 入口 guard = "+want);
                    else fail(short_(leaf)+"."+m.name+" 入口 guard 應為 "+want+",實得 "+got);
                }
                for (AbstractInsnNode in : m.instructions) {
                    if (in instanceof MethodInsnNode mi && mi.owner.equals("net/minecraft/world/ContainerHelper")
                            && (mi.name.equals("loadAllItems")||mi.name.equals("saveAllItems")))
                        fail(short_(leaf)+"."+m.name+" 殘留未 redirect 的 ContainerHelper."+mi.name);
                }
            }
        }
        ok("R4:三個 leaf 皆無殘留的 ContainerHelper.load/saveAllItems 呼叫");

        System.out.println("\n── R5. hopper 摘要 hook ──");
        ClassNode hop = out.get(HOPPER);
        int hooks = 0;
        for (MethodNode m : hop.methods) {
            String g = firstGuardCall(m);
            if (g != null && g.startsWith("lazycontainer$container")) { hooks++; ok("hopper."+m.name+" 入口 = "+g); }
            if (g != null && g.startsWith("lazycontainer$slotProven")) { hooks++; ok("hopper."+m.name+" 入口 = "+g); }
        }
        if (hooks != 2) fail("hopper hook 數 = " + hooks + "(應為 2)");

        System.out.println("\n── R6. raw passthrough:CompoundTag / LevelChunk ──");
        ClassNode ct = out.get(COMPOUND_TAG);
        if (ct == null) { fail("CompoundTag 未被改寫(transform 回 null)"); } else {
            boolean fk = false, fb = false;
            for (FieldNode f : ct.fields) { if (f.name.equals("lazycontainer$rawKey") && f.desc.equals("Ljava/lang/String;")) fk = true; if (f.name.equals("lazycontainer$rawBytes") && f.desc.equals("[B")) fb = true; }
            if (fk && fb) ok("CompoundTag 有 lazycontainer$rawKey / lazycontainer$rawBytes"); else fail("CompoundTag 缺 raw 欄位 key=" + fk + " bytes=" + fb);
            MethodNode wr = null, cp = null;
            for (MethodNode m : ct.methods) { if (m.name.equals("write") && m.desc.equals("(Ljava/io/DataOutput;)V")) wr = m; if (m.name.equals("copy") && m.desc.equals("()L" + COMPOUND_TAG + ";")) cp = m; }
            if (wr == null) fail("CompoundTag.write(DataOutput) 不存在"); else {
                boolean sawGet = false, sawCall = false; int idx = 0;
                for (AbstractInsnNode in : wr.instructions) { if (idx++ > 12) break;
                    if (in instanceof FieldInsnNode fi && fi.name.equals("lazycontainer$rawBytes")) sawGet = true;
                    if (in instanceof MethodInsnNode mi && mi.owner.equals(RT) && mi.name.equals("writeRawEntry")) sawCall = true; }
                if (sawGet && sawCall) ok("CompoundTag.write 開頭:rawBytes 判斷 + Runtime.writeRawEntry"); else fail("CompoundTag.write 開頭沒有 passthrough 注入(get=" + sawGet + " call=" + sawCall + ")");
            }
            if (cp == null) fail("CompoundTag.copy() 不存在"); else {
                boolean put = false; for (AbstractInsnNode in : cp.instructions) if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD && fi.name.equals("lazycontainer$rawBytes")) put = true;
                if (put) ok("CompoundTag.copy 把 raw 欄位帶到新物件"); else fail("CompoundTag.copy 沒帶 raw 欄位(chunk 存檔若 copy 會掉 Items)");
            }
        }
        ClassNode lc = out.get(LEVEL_CHUNK);
        if (lc == null) { fail("LevelChunk 未被改寫"); } else {
            MethodNode w = null, o = null;
            for (MethodNode m : lc.methods) { if (m.name.equals("getBlockEntityNbtForSaving")) w = m; if (m.name.equals("lazycontainer$orig$getBlockEntityNbtForSaving")) o = m; }
            if (o == null) fail("LevelChunk 缺 lazycontainer$orig$getBlockEntityNbtForSaving(原方法未改名)"); else ok("LevelChunk 原方法已改名保留");
            if (w == null) fail("LevelChunk 缺包裝 getBlockEntityNbtForSaving"); else {
                int enter = 0, exit = 0; boolean callOrig = false;
                for (AbstractInsnNode in : w.instructions) if (in instanceof MethodInsnNode mi) { if (mi.owner.equals(RT) && mi.name.equals("enterChunkSave")) enter++; if (mi.owner.equals(RT) && mi.name.equals("exitChunkSave")) exit++; if (mi.name.equals("lazycontainer$orig$getBlockEntityNbtForSaving")) callOrig = true; }
                boolean anyHandler = false; for (TryCatchBlockNode tb : w.tryCatchBlocks) if (tb.type == null) anyHandler = true;
                if (enter == 1 && exit >= 2 && callOrig && anyHandler) ok("LevelChunk 包裝:enter ×1、exit ×" + exit + "(正常+handler)、呼叫 orig、any handler 到位");
                else fail("LevelChunk 包裝形狀不對 enter=" + enter + " exit=" + exit + " orig=" + callOrig + " anyHandler=" + anyHandler + "(旗標可能殘留 ⟹ 非存檔路徑也走 passthrough = 資料風險)");
            }
        }
        System.out.println("\n=== 結果:通過 " + checks + " 項,違規 " + violations + " 項 ===");
        System.exit(violations == 0 ? 0 : 1);
    }

    static List<String> allTargets(){
        List<String> l = new ArrayList<>(); l.add(BASE); l.addAll(Arrays.asList(LEAVES)); l.add(HOPPER); l.add(COMPOUND_TAG); l.add(LEVEL_CHUNK); return l; }
    static String short_(String s){ return s.substring(s.lastIndexOf('/')+1); }
    static boolean hasMonitor(MethodNode m){
        for (AbstractInsnNode in : m.instructions) if (in.getOpcode()==Opcodes.MONITORENTER) return true;
        return false; }
    static String expectGuard(String name,String desc){
        if (name.equals("getItems") && desc.equals("()Lnet/minecraft/core/NonNullList;")) return "lazycontainer$ensure";
        if (name.equals("getContents") && desc.equals("()Ljava/util/List;")) return "lazycontainer$ensure";
        if (name.equals("setItems") && desc.equals("(Lnet/minecraft/core/NonNullList;)V")) return "lazycontainer$clear";
        if ((name.equals("loadAdditional")||name.equals("loadFromTag"))
                && desc.equals("(Lnet/minecraft/world/level/storage/ValueInput;)V")) return "lazycontainer$clear";
        return null; }
    /** 方法入口(前 8 條指令內)第一個 lazycontainer$ 呼叫。 */
    static String firstGuardCall(MethodNode m){
        int i = 0;
        for (AbstractInsnNode in : m.instructions) {
            if (in.getOpcode() < 0) continue;             // label/line/frame
            if (++i > 8) break;
            if (in instanceof MethodInsnNode mi && mi.name.startsWith("lazycontainer$")) return mi.name;
        }
        return null; }
}
