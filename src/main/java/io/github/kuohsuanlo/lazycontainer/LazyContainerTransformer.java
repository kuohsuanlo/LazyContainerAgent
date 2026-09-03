package io.github.kuohsuanlo.lazycontainer;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * 容器延遲反序列化 / 跳過乾淨重存 的 bytecode 注入。
 *
 * <ul>
 *   <li><b>BaseContainerBlockEntity</b>:把 {@code LazyContainerTemplate} 編好的
 *       {@code lazycontainer$*} 欄位與方法 splice 進來(owner remap)。</li>
 *   <li><b>Chest/Barrel/ShulkerBox</b>:(1) 在 {@code getItems()/getContents()} 入口插
 *       ensure-guard、{@code setItems()} 入口清旗標;(2) 把 load/save 內的
 *       {@code ContainerHelper.loadAllItems/saveAllItems} 呼叫 redirect 成 base 的 lazy 方法。</li>
 *   <li><b>HopperBlockEntity</b>:在 {@code isFullContainer}/{@code tryTakeInItemFromSlot} 入口插
 *       「先問摘要」(ensure 快取):pending 容器的滿/空檢查可由 raw NBT 摘要直接回答,免整箱解碼;
 *       答不出就照走原路,行為與 vanilla 一致。</li>
 * </ul>
 *
 * <p><b>安全:</b>base 是 leaf 的 superclass,必先載入並 splice;splice 成功才把
 * {@link LazyContainerRuntime#injected} 設 true。leaf transform 一律先檢查 injected,
 * base 沒成功就「完全不動 leaf」→ 退回純 vanilla,絕不產生 NoSuchMethodError。任何例外 → 回傳
 * 原 bytes(該類別維持 vanilla 行為)。</p>
 */
public final class LazyContainerTransformer implements ClassFileTransformer {

    private static final String P = "net/minecraft/world/level/block/entity/";
    static final String BASE = P + "BaseContainerBlockEntity";
    static final String CHEST = P + "ChestBlockEntity";
    static final String BARREL = P + "BarrelBlockEntity";
    static final String SHULKER = P + "ShulkerBoxBlockEntity";
    static final String HOPPER = P + "HopperBlockEntity";

    static final String CH = "net/minecraft/world/ContainerHelper";
    static final String NNL = "net/minecraft/core/NonNullList";
    static final String VIN = "net/minecraft/world/level/storage/ValueInput";
    static final String VOUT = "net/minecraft/world/level/storage/ValueOutput";
    static final String CONTAINER = "net/minecraft/world/Container";
    // raw passthrough(#261)的兩個改寫目標
    static final String RUNTIME = "io/github/kuohsuanlo/lazycontainer/LazyContainerRuntime";
    /** 與 LazyContainerRuntime.passthrough() 同源(同一個 -D),但在 transformer 自己讀:transform() 不可碰 Runtime。 */
    static final boolean PASSTHROUGH = !"false".equalsIgnoreCase(System.getProperty("lazycontainer.passthrough"));   // bootstrap loader,注入碼可直接 INVOKESTATIC
    static final String COMPOUND_TAG = "net/minecraft/nbt/CompoundTag";
    static final String LEVEL_CHUNK = "net/minecraft/world/level/chunk/LevelChunk";
    static final String D_BE_NBT_FOR_SAVING = "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;";

    static final String D_LOAD = "(L" + VIN + ";L" + NNL + ";)V";   // loadAllItems / lazycontainer$load
    static final String D_SAVE2 = "(L" + VOUT + ";L" + NNL + ";)V"; // saveAllItems(2) / lazycontainer$save(NoEmpty)
    static final String D_SAVE3 = "(L" + VOUT + ";L" + NNL + ";Z)V"; // saveAllItems(3)

    // ── 摘要(ensure 快取)hopper hook:方法簽章比對必須一字不差(Paper 26.2 與 Folia 系同形,已核對)──
    static final String D_ISFULL = "(L" + CONTAINER + ";Lnet/minecraft/core/Direction;)Z";               // isFullContainer
    static final String D_TRYTAKE = "(L" + P + "Hopper;L" + CONTAINER + ";ILnet/minecraft/core/Direction;Lnet/minecraft/world/level/Level;)Z"; // tryTakeInItemFromSlot
    static final String D_FULLSTATE = "(L" + CONTAINER + ";)I";     // lazycontainer$containerFullState
    static final String D_SLOTEMPTY = "(L" + CONTAINER + ";I)Z";    // lazycontainer$slotProvenEmpty

    static final String TEMPLATE = "io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate";
    static final String TEMPLATE_RES = "/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.class";
    static final String PREFIX = "lazycontainer$";

    // splice 用:由 template remap 到 BASE 後抽出的成員(premain 時 prepare() 先建好)
    private volatile List<FieldNode> spliceFields;
    private volatile List<MethodNode> spliceMethods;

    /**
     * premain 時呼叫:預先載入 template 成員,回傳是否就緒。
     * <p><b>為什麼 leaf/hopper 的閘門必須看「template 就緒」而不是「base 已 splice」</b>:
     * JVM 定義 leaf 類時,transform 回呼跑在 defineClass <b>之前</b>,而 superclass(base)的載入發生在
     * defineClass <b>之內</b>——所以 leaf 的 transform 永遠比 base 的先執行。舊閘門
     * {@code !injected ⟹ skip} 賭的是「總有別的類先去載 base」;EndRod r134 恰好如此,
     * <b>r149 改了初始化順序,整場 boot 沒人先碰 base ⟹ 三個 leaf 全被跳過 ⟹ agent 靜默失效</b>
     * (stash=0 但 active=true,2026-08-14 生產實測)。
     * 正確的不變式是:<b>base 的「定義」保證在 leaf 的「定義完成」之前</b>(superclass 解析),
     * 因此只要 template 讀得起來、base 的 splice 就必然趕在任何 leaf 程式碼執行之前完成——
     * leaf 可以無條件改寫,只需確保 template 可用。</p>
     */
    // 必須 public:premain 的 AgentMain 在 app loader,本類別由 bootstrap loader 載入,
    // 同套件名不同 loader=不同 runtime package,package-private 會 IllegalAccessError(整台 server 起不來)。
    public boolean prepare() {
        loadSpliceMembers();
        boolean ready = spliceMethods != null && !spliceMethods.isEmpty();
        if (!ready) {
            System.err.println("[LazyContainer] FATAL: template unavailable at premain — agent stays fully inactive");
        }
        return ready;
    }

    private boolean templateReady() {
        return spliceMethods != null && !spliceMethods.isEmpty();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }
        try {
            if (BASE.equals(className)) {
                return spliceBase(classfileBuffer);
            }
            if (CHEST.equals(className) || BARREL.equals(className) || SHULKER.equals(className)) {
                if (!templateReady()) {
                    System.err.println("[LazyContainer] template unavailable; skip leaf " + className);
                    return null;
                }
                return transformLeaf(classfileBuffer, className);
            }
            if (HOPPER.equals(className)) {
                if (!templateReady()) {
                    System.err.println("[LazyContainer] template unavailable; skip hopper hook");
                    return null;
                }
                return transformHopper(classfileBuffer);
            }
            if (PASSTHROUGH) {   // 注意:transform() 內不得參照 LazyContainerRuntime(它會在此刻透過 transformer 被載入
                                 // ⟹ ClassCircularityError;rig 實測噴滿整個開機 log)。開關由本類自己讀 property。
                if (COMPOUND_TAG.equals(className)) {
                    return transformCompoundTag(classfileBuffer);
                }
                if (LEVEL_CHUNK.equals(className)) {
                    return transformLevelChunk(classfileBuffer);
                }
            }
        } catch (Throwable t) {
            System.err.println("[LazyContainer] transform failed for " + className + " — leaving vanilla: " + t);
            t.printStackTrace();
        }
        return null;
    }

    // ───────────────────────────── base splice ─────────────────────────────

    private byte[] spliceBase(byte[] buffer) {
        loadSpliceMembers();
        if (spliceFields == null || spliceMethods == null || spliceMethods.isEmpty()) {
            System.err.println("[LazyContainer] FATAL: template members unavailable; base not spliced");
            return null;
        }
        ClassReader cr = new ClassReader(buffer);
        ClassWriter cw = new ClassWriter(cr, 0); // 只新增成員,原方法逐位元組複製;splice 方法自帶 frame/maxs
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public void visitEnd() {
                for (FieldNode f : spliceFields) {
                    f.accept(this);
                }
                for (MethodNode m : spliceMethods) {
                    m.accept(this);
                }
                super.visitEnd();
            }
        };
        cr.accept(cv, 0);
        LazyContainerRuntime.injected = true;
        System.out.println("[LazyContainer] spliced " + spliceFields.size() + " fields + "
                + spliceMethods.size() + " methods into BaseContainerBlockEntity");
        return cw.toByteArray();
    }

    /** 讀 template bytes,整體 remap(LazyContainerTemplate → BaseContainerBlockEntity),抽出 lazycontainer$ 成員。 */
    private synchronized void loadSpliceMembers() {
        if (spliceMethods != null) {
            return;
        }
        try (InputStream in = LazyContainerTransformer.class.getResourceAsStream(TEMPLATE_RES)) {
            if (in == null) {
                System.err.println("[LazyContainer] FATAL: template resource not found: " + TEMPLATE_RES);
                return;
            }
            ClassReader tcr = new ClassReader(in.readAllBytes());
            ClassNode remapped = new ClassNode();
            tcr.accept(new ClassRemapper(remapped, new SimpleRemapper(TEMPLATE, BASE)), 0);

            List<FieldNode> fs = new ArrayList<>();
            for (FieldNode f : remapped.fields) {
                if (f.name.startsWith(PREFIX)) {
                    fs.add(f);
                }
            }
            List<MethodNode> ms = new ArrayList<>();
            for (MethodNode m : remapped.methods) {
                if (m.name.startsWith(PREFIX)) {
                    ms.add(m);
                }
            }
            spliceFields = fs;
            spliceMethods = ms;
        } catch (Throwable t) {
            System.err.println("[LazyContainer] FATAL: reading template failed: " + t);
            t.printStackTrace();
        }
    }

    // ───────────────────────────── leaf transform ─────────────────────────────

    private byte[] transformLeaf(byte[] buffer, String className) {
        boolean shulker = SHULKER.equals(className);
        ClassReader cr = new ClassReader(buffer);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
                // 一律套 redirect;其上再視情況套 guard。注入碼以 leaf 自身為 owner 參照繼承來的
                // lazycontainer$ 成員(public、合法),receiver 型別精確相符 → 免跨類 assignability。
                MethodVisitor red = new RedirectMethodVisitor(mv, className, shulker);
                int guard = guardKind(name, desc);
                if (guard != GUARD_NONE) {
                    return new GuardMethodVisitor(red, className, guard);
                }
                return red;
            }
        };
        cr.accept(cv, 0);
        System.out.println("[LazyContainer] transformed leaf " + className);
        return cw.toByteArray();
    }

    // ───────────────────────────── hopper hook(ensure 快取)─────────────────────────────

    /**
     * 在 HopperBlockEntity 的兩個靜態檢查方法入口插「先問摘要」:
     * <ul>
     *   <li>{@code isFullContainer(Container,Direction)}:{@code int r = BCE.lazycontainer$containerFullState(c);
     *       if (r >= 0) return r != 0;}(r 恆為 -1/0/1,0/1 可直接當 boolean 回傳)</li>
     *   <li>{@code tryTakeInItemFromSlot(Hopper,Container,int,Direction,Level)}:
     *       {@code if (BCE.lazycontainer$slotProvenEmpty(c, slot)) return false;}</li>
     * </ul>
     * 摘要答不出(-1/false)⟹ 原方法照舊執行,行為與 vanilla 完全一致。
     * 名稱+descriptor 一字不差才會插;整類掃完一個都沒中(fork 改了形狀)⟹ 原樣返回、印警告。
     */
    private byte[] transformHopper(byte[] buffer) {
        ClassReader cr = new ClassReader(buffer);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        final int[] hooked = {0};
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
                if ("isFullContainer".equals(name) && D_ISFULL.equals(desc)) {
                    hooked[0]++;
                    return new HopperHookVisitor(mv, HOOK_FULL);
                }
                if ("tryTakeInItemFromSlot".equals(name) && D_TRYTAKE.equals(desc)) {
                    hooked[0]++;
                    return new HopperHookVisitor(mv, HOOK_SLOT);
                }
                return mv;
            }
        };
        cr.accept(cv, 0);
        if (hooked[0] == 0) {
            System.err.println("[LazyContainer] hopper hook: no matching method (fork changed shape?) — leaving vanilla");
            return null;
        }
        System.out.println("[LazyContainer] hooked " + hooked[0] + " hopper check(s) (summary fast-path)");
        return cw.toByteArray();
    }

    private static final int HOOK_FULL = 1;
    private static final int HOOK_SLOT = 2;

    /** 方法入口插摘要查詢;兩個目標方法皆為 static,locals 僅參數,frame 用 F_SAME/F_SAME1 相對入口。 */
    private static final class HopperHookVisitor extends MethodVisitor {
        private final int kind;

        HopperHookVisitor(MethodVisitor mv, int kind) {
            super(Opcodes.ASM9, mv);
            this.kind = kind;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (kind == HOOK_FULL) {
                // args: 0=container, 1=direction
                Label unknown = new Label();
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BASE, "lazycontainer$containerFullState", D_FULLSTATE, false);
                super.visitInsn(Opcodes.DUP);
                super.visitJumpInsn(Opcodes.IFLT, unknown);
                super.visitInsn(Opcodes.IRETURN);            // r∈{0,1} 直接作為 boolean 回傳
                super.visitLabel(unknown);
                super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{Opcodes.INTEGER});
                super.visitInsn(Opcodes.POP);                // 丟掉 -1,落回原方法
            } else { // HOOK_SLOT
                // args: 0=hopper, 1=container, 2=slot(int), 3=direction, 4=level
                Label cont = new Label();
                super.visitVarInsn(Opcodes.ALOAD, 1);
                super.visitVarInsn(Opcodes.ILOAD, 2);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BASE, "lazycontainer$slotProvenEmpty", D_SLOTEMPTY, false);
                super.visitJumpInsn(Opcodes.IFEQ, cont);
                super.visitInsn(Opcodes.ICONST_0);
                super.visitInsn(Opcodes.IRETURN);            // 此格證明為空 → 與 vanilla 對空格的結果相同:false
                super.visitLabel(cont);
                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            }
        }
    }

    private static final int GUARD_NONE = 0;
    private static final int GUARD_ENSURE = 1;
    private static final int GUARD_CLEAR = 2;

    private static int guardKind(String name, String desc) {
        if (desc.equals("()L" + NNL + ";") && name.equals("getItems")) {
            return GUARD_ENSURE;
        }
        if (desc.equals("()Ljava/util/List;") && name.equals("getContents")) {
            return GUARD_ENSURE;
        }
        if (desc.equals("(L" + NNL + ";)V") && name.equals("setItems")) {
            return GUARD_CLEAR;
        }
        // leaf 的載入入口(Chest/Barrel/Shulker 的 loadAdditional、Shulker 的 loadFromTag)。
        // 它們的方法體是「① putfield items = 全新空清單(裸寫、鎖外)② 才呼叫被 redirect 的
        // lazycontainer$load(synchronized)」。①②之間若有另一條執行緒的 ensure() 在途,ensure 會把
        // **舊** raw 填進①剛換上的新清單,之後新 raw 只覆蓋自己有列到的格 ⟹ 被覆蓋掉的物品原地復活
        // (= 複製)。觸發端是外掛最常見的 Chest s=(Chest)block.getState(); …; s.update();
        // (CraftBlockEntityState.update → applyTo(活體 BE) → copyData → loadWithComponents → loadAdditional)。
        // 在方法入口插 clear():S 必須先取得 monitor 才能往下走 ⟹ 在途的 ensure 只能整個發生在①之前
        // (填的是即將被丟棄的舊清單,無害),不可能跨在①②中間。實測:無此 guard 時 26/26 格復活。
        if (desc.equals("(L" + VIN + ";)V")
                && (name.equals("loadAdditional") || name.equals("loadFromTag"))) {
            return GUARD_CLEAR;
        }
        return GUARD_NONE;
    }

    /**
     * 方法入口插:ENSURE = {@code if(pending) ensure();};CLEAR = {@code lazycontainer$clear();}(26.2-2:進 monitor)。
     * CLEAR 用於 setItems(換清單)與 loadAdditional/loadFromTag(重新載入)——兩者都會讓既有的 lazy 狀態失效。
     */
    private static final class GuardMethodVisitor extends MethodVisitor {
        private final String owner;
        private final int kind;

        GuardMethodVisitor(MethodVisitor mv, String owner, int kind) {
            super(Opcodes.ASM9, mv);
            this.owner = owner;
            this.kind = kind;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (kind == GUARD_ENSURE) {
                Label skip = new Label();
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitFieldInsn(Opcodes.GETFIELD, owner, "lazycontainer$pending", "Z");
                super.visitJumpInsn(Opcodes.IFEQ, skip);
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "lazycontainer$ensure", "()V", false);
                super.visitLabel(skip);
                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            } else { // GUARD_CLEAR
                // 26.2-2:必須呼叫 base 的 synchronized lazycontainer$clear(),不可就地 PUTFIELD。
                // 就地寫兩個欄位是「鎖外改 pending/raw」——與在途的 ensure() 交錯時,可能發生
                // 「setItems 清了旗標、leaf 換上新清單,而 ensure 接著呼叫 getItems() 拿到新清單、
                // 把舊 raw 解進去」→ 新清單被舊內容覆蓋。進 monitor 後 clear 只能整個發生在 ensure
                // 之前或之後,最終狀態恆為「新清單、pending=false、raw=null」= vanilla 的 setItems 結果。
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "lazycontainer$clear", "()V", false);
            }
        }
    }

    /**
     * 把 leaf load/save 內的 {@code ContainerHelper.loadAllItems/saveAllItems} 呼叫,改成呼叫 base 的
     * lazy 方法。用 {@code ALOAD0;DUP_X2;POP} 把 {@code this} 插到原本兩個引數底下。
     */
    private static final class RedirectMethodVisitor extends MethodVisitor {
        private final String self;   // leaf 自身 internal name(作為 redirect 目標方法 owner)
        private final boolean shulker;

        RedirectMethodVisitor(MethodVisitor mv, String self, boolean shulker) {
            super(Opcodes.ASM9, mv);
            this.self = self;
            this.shulker = shulker;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            if (opcode == Opcodes.INVOKESTATIC && CH.equals(owner)) {
                if ("loadAllItems".equals(name) && D_LOAD.equals(desc)) {
                    thisUnderTwo();
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "lazycontainer$load", D_LOAD, false);
                    return;
                }
                if ("saveAllItems".equals(name) && D_SAVE2.equals(desc)) {
                    thisUnderTwo();
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "lazycontainer$save", D_SAVE2, false);
                    return;
                }
                if ("saveAllItems".equals(name) && D_SAVE3.equals(desc) && shulker) {
                    // [output, items, allowEmpty=false] → 丟掉 bool,改呼叫 saveNoEmpty(output, items)
                    super.visitInsn(Opcodes.POP);
                    thisUnderTwo();
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "lazycontainer$saveNoEmpty", D_SAVE2, false);
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        /** 堆疊 [a, b] → [this, a, b]。 */
        private void thisUnderTwo() {
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitInsn(Opcodes.DUP_X2);
            super.visitInsn(Opcodes.POP);
        }
    }

    // ───────────────────────── raw passthrough(#261)─────────────────────────

    /**
     * CompoundTag:加 {@code lazycontainer$rawKey}/{@code lazycontainer$rawBytes} 兩欄位;
     * {@code write(DataOutput)} 開頭注入「rawBytes != null ⟹ LazyContainerRuntime.writeRawEntry(key, bytes, out)」
     * (格式與 writeNamedTag 逐位元組相同,見 Runtime);{@code copy()} 回傳前把兩欄位帶到新物件。
     * 這兩個欄位只會在 chunk 存檔路徑(LevelChunk.getBlockEntityNbtForSaving 內)被 template 設定。
     */
    private static byte[] transformCompoundTag(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        final boolean[] hit = new boolean[2];
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
                if (name.equals("write") && desc.equals("(Ljava/io/DataOutput;)V")) {
                    hit[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            Label skip = new Label();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, COMPOUND_TAG, "lazycontainer$rawBytes", "[B");
                            super.visitJumpInsn(Opcodes.IFNULL, skip);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, COMPOUND_TAG, "lazycontainer$rawKey", "Ljava/lang/String;");
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, COMPOUND_TAG, "lazycontainer$rawBytes", "[B");
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "writeRawEntry",
                                    "(Ljava/lang/String;[BLjava/io/DataOutput;)V", false);
                            super.visitLabel(skip);
                            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        }
                    };
                }
                if (name.equals("copy") && desc.equals("()L" + COMPOUND_TAG + ";")) {
                    hit[1] = true;
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.ARETURN) {
                                // stack: [newTag] → 把兩欄位帶過去
                                super.visitInsn(Opcodes.DUP);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitFieldInsn(Opcodes.GETFIELD, COMPOUND_TAG, "lazycontainer$rawKey", "Ljava/lang/String;");
                                super.visitFieldInsn(Opcodes.PUTFIELD, COMPOUND_TAG, "lazycontainer$rawKey", "Ljava/lang/String;");
                                super.visitInsn(Opcodes.DUP);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitFieldInsn(Opcodes.GETFIELD, COMPOUND_TAG, "lazycontainer$rawBytes", "[B");
                                super.visitFieldInsn(Opcodes.PUTFIELD, COMPOUND_TAG, "lazycontainer$rawBytes", "[B");
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                return mv;
            }

            @Override
            public void visitEnd() {
                super.visitField(Opcodes.ACC_PUBLIC, "lazycontainer$rawKey", "Ljava/lang/String;", null, null).visitEnd();
                super.visitField(Opcodes.ACC_PUBLIC, "lazycontainer$rawBytes", "[B", null, null).visitEnd();
                super.visitEnd();
            }
        };
        cr.accept(cv, 0);
        if (!hit[0] || !hit[1]) {
            System.err.println("[LazyContainer] passthrough: CompoundTag shape mismatch (write=" + hit[0] + " copy=" + hit[1] + ") — leaving vanilla");
            return null;
        }
        System.out.println("[LazyContainer] passthrough armed: CompoundTag.write/copy carry raw Items");
        return cw.toByteArray();
    }

    /**
     * LevelChunk:把 {@code getBlockEntityNbtForSaving} 改名成 {@code lazycontainer$orig$…},再放一支同名同描述子的
     * 包裝:{@code enterChunkSave(); try { return orig(...); } finally { exitChunkSave(); }}。
     * 這是唯一會掛 raw 的窗口(它的唯一呼叫者是 SerializableChunkData.copyOf = chunk 存檔)。
     */
    private static byte[] transformLevelChunk(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        MethodNode orig = null;
        for (MethodNode m : cn.methods) {
            if (m.name.equals("getBlockEntityNbtForSaving") && m.desc.equals(D_BE_NBT_FOR_SAVING)) {
                orig = m;
                break;
            }
        }
        if (orig == null) {
            System.err.println("[LazyContainer] passthrough: LevelChunk.getBlockEntityNbtForSaving not found — leaving vanilla");
            return null;
        }
        String origName = "lazycontainer$orig$getBlockEntityNbtForSaving";
        orig.name = origName;
        MethodNode w = new MethodNode(Opcodes.ASM9, orig.access, "getBlockEntityNbtForSaving", D_BE_NBT_FOR_SAVING, null, null);
        LabelNode l0 = new LabelNode();
        LabelNode l1 = new LabelNode();
        LabelNode l2 = new LabelNode();
        InsnList il = w.instructions;
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "enterChunkSave", "()V", false));
        il.add(l0);
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LEVEL_CHUNK, origName, D_BE_NBT_FOR_SAVING, false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 3));
        il.add(l1);
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exitChunkSave", "()V", false));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new InsnNode(Opcodes.ARETURN));
        il.add(l2);
        il.add(new FrameNode(Opcodes.F_FULL, 3,
                new Object[] {LEVEL_CHUNK, "net/minecraft/core/BlockPos", "net/minecraft/core/HolderLookup$Provider"},
                1, new Object[] {"java/lang/Throwable"}));
        il.add(new VarInsnNode(Opcodes.ASTORE, 4));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exitChunkSave", "()V", false));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new InsnNode(Opcodes.ATHROW));
        w.tryCatchBlocks.add(new TryCatchBlockNode(l0, l1, l2, null));
        w.maxLocals = 5;
        w.maxStack = 3;
        cn.methods.add(w);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        System.out.println("[LazyContainer] passthrough armed: LevelChunk.getBlockEntityNbtForSaving wrapped (chunk-save window)");
        return cw.toByteArray();
    }
}
