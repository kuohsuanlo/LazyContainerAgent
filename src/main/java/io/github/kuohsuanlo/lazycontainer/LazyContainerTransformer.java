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
    /** lazycontainer$raw 的欄位描述子。方案 A′ 後是 byte[](原為 Lnet/minecraft/nbt/Tag;)——
     *  GUARD_CLEAR 的 PUTFIELD 用它,與 template 欄位型別不符會直接 VerifyError。 */
    static final String RAW_DESC = "[B";
    static final String CONTAINER = "net/minecraft/world/Container";

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
        return GUARD_NONE;
    }

    /** 方法入口插:ENSURE = {@code if(pending) ensure();};CLEAR = {@code pending=false; raw=null;}。 */
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
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitInsn(Opcodes.ICONST_0);
                super.visitFieldInsn(Opcodes.PUTFIELD, owner, "lazycontainer$pending", "Z");
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitInsn(Opcodes.ACONST_NULL);
                super.visitFieldInsn(Opcodes.PUTFIELD, owner, "lazycontainer$raw", RAW_DESC);
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
}
