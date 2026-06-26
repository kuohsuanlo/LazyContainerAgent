package io.github.kuohsuanlo.lazycontainer;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * v2 ASM ClassFileTransformer — 不再 splice BaseContainerBlockEntity。
 *
 * <p>取代 v1.0 的三階段注入 (spliceBase + transformLeaf redirect + transformLeaf guard) 為:</p>
 * <ol>
 *   <li><b>ContainerHelper</b>:在 loadAllItems / saveAllItems 入口插入 interceptor stub</li>
 *   <li><b>Leaf container classes</b>:在 getItems() 入口插入 ensure guard</li>
 * </ol>
 *
 * <p>移除:</p>
 * <ul>
 *   <li>LazyContainerTemplate splice (不再需要編譯 template + splice 進 NMS)</li>
 *   <li>ContainerHelper 呼叫的 redirect (不再需要每種 desc 一個 redirect)</li>
 *   <li>Guard 的 pending flag check (v1.0 檢查 lazycontainer$pending field → v2 check 在 interceptor 端)</li>
 * </ul>
 *
 * <pre>
 * REVIEW(D1-A): 攔截目標選擇。
 *   方案 A(目前): ContainerHelper + leaf getItems → 2 class 操作,4 個 method 插入點。
 *   方案 B: BlockEntity.loadStatic + leaf proxy → 需動態生成 proxy class。
 *   方案 C: ChunkSerializer + NBT scan → 比 A 多 2x code,只在格式變動大的版本有利。
 *   目前選 A, 理由:最少 NMS 接觸面、v1.0 已驗證 ContainerHelper 簽名穩定。
 * </pre>
 *
 * <pre>
 * REVIEW(D5): Shadow mode 的插入點。
 *   目前 stub 只做 type guard (canWriteRaw),不做 eager roundtrip 比對。
 *   Shadow mode 需要完整解析→編碼→比對 (call ContainerHelper.loadAllItems + saveAllItems)。
 *   預計以 ShadowTemplate.java 方式實作:編譯對 real NMS,其 bytes 由 transformer 載入,
 *   方法 splice 進 ContainerHelper 當 private static helper。
 *   見 #spliceShadowHelpers()。
 * </pre>
 */
public final class LazyContainerTransformer implements ClassFileTransformer {

    // ── NMS class names ──
    // REVIEW(D3): 從 NmsRegistry 載入,支援多版本。
    // 若 registry 無對應版本 (UNKNOWN),則不注入 (transform 回傳 null → 純 vanilla)。
    private static final String RUNTIME = "io/github/kuohsuanlo/lazycontainer/LazyContainerRuntime";
    private static final String INTERCEPTOR = "io/github/kuohsuanlo/lazycontainer/ContainerHelperInterceptor";

    private String CHEST, BARREL, SHULKER, HOPPER, DISPENSER, ABSTRACT_FURNACE;
    private String CH, VIN, TVI, TAG, COMPOUND, NNL;
    private String D_LOAD, D_SAVE2, D_SAVE3;
    private boolean ready = false;

    public boolean isReady() { return ready; }

    /** 由 AgentMain 在 premain 中呼叫:根據版本初始化 mapping。 */
    public synchronized void init() {
        if (ready) return;
        NmsRegistry.NmsMapping m = NmsRegistry.current();
        if (m == null) {
            System.err.println("[LazyContainer] FATAL: no NMS mapping for detected version; agent DISABLED");
            return;
        }
        String P = "net/minecraft/world/level/block/entity/";
        CHEST = P + "ChestBlockEntity";
        BARREL = P + "BarrelBlockEntity";
        SHULKER = P + "ShulkerBoxBlockEntity";
        HOPPER = P + "HopperBlockEntity";
        DISPENSER = P + "DispenserBlockEntity";
        ABSTRACT_FURNACE = P + "AbstractFurnaceBlockEntity";

        CH = m.containerHelper();
        VIN = m.valueInput();
        TVI = m.tagValueInput();
        TAG = m.tag();
        COMPOUND = m.compoundTag();
        NNL = m.nonNullList();
        D_LOAD = "(" + "L" + VIN + ";" + "L" + NNL + ";" + ")V";
        D_SAVE2 = "(" + "L" + m.valueOutput() + ";" + "L" + NNL + ";" + ")V";
        D_SAVE3 = "(" + "L" + m.valueOutput() + ";" + "L" + NNL + ";Z)V";
        ready = true;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || !ready) return null;
        try {
            if (CH.equals(className)) {
                return transformContainerHelper(classfileBuffer);
            }
            if (CHEST.equals(className) || BARREL.equals(className) || SHULKER.equals(className)
                    || HOPPER.equals(className) || DISPENSER.equals(className) || ABSTRACT_FURNACE.equals(className)) {
                return transformLeaf(classfileBuffer, className);
            }
        } catch (Throwable t) {
            System.err.println("[LazyContainer] transform failed for " + className + " — leaving vanilla: " + t);
            t.printStackTrace();
        }
        return null;
    }

    // ─────────────────────── ContainerHelper ───────────────────────

    /**
     * 在 ContainerHelper 的三個方法入口插入 interceptor stub。
     * 使用 Tree API:讀取原方法指令清單,在最前面插入我們的邏輯。
     */
    private byte[] transformContainerHelper(byte[] buffer) {
        ClassNode cn = new ClassNode();
        new ClassReader(buffer).accept(cn, 0);

        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("loadAllItems") && mn.desc.equals(D_LOAD)) {
                injectLoadInterceptor(mn);
                System.out.println("[LazyContainer] patched ContainerHelper.loadAllItems");
            } else if (mn.name.equals("saveAllItems")) {
                if (mn.desc.equals(D_SAVE2)) {
                    injectSaveInterceptor(mn, true);
                    System.out.println("[LazyContainer] patched ContainerHelper.saveAllItems(VOUT,NNL)");
                } else if (mn.desc.equals(D_SAVE3)) {
                    injectSaveInterceptor(mn, false);
                    System.out.println("[LazyContainer] patched ContainerHelper.saveAllItems(VOUT,NNL,Z)");
                }
            }
        }

        ClassWriter cw = nmsFramesClassWriter();
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * 在 loadAllItems 入口插入:
     * <pre>
     * if (LazyContainerRuntime.isActive()) {
     *     Tag raw = null;
     *     if (input instanceof TagValueInput) {
     *         raw = ((TagValueInput)input).input.get("Items");
     *     }
     *     if (ContainerHelperInterceptor.onLoadItem(raw, items, null)) return;
     * }
     * </pre>
     *
     * <p>ContainerHelper 是 static utility class,所以 local 0 = 第一個參數 (input),
     * local 1 = 第二個參數 (items)。我們用 local 2 做 raw 暫存。</p>
     */
    private void injectLoadInterceptor(MethodNode mn) {
        InsnList pre = new InsnList();
        LabelNode skipAll = new LabelNode();
        LabelNode afterInstanceof = new LabelNode();

        // if (!LazyContainerRuntime.active) goto skipAll
        pre.add(new FieldInsnNode(Opcodes.GETSTATIC, RUNTIME, "active", "Z"));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, skipAll));

        // raw = null (local 2: static method, param0=input, param1=items, local2=raw)
        pre.add(new InsnNode(Opcodes.ACONST_NULL));
        pre.add(new VarInsnNode(Opcodes.ASTORE, 2));

        // if (!(input instanceof TagValueInput)) goto afterInstanceof
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0)); // input (first param)
        pre.add(new TypeInsnNode(Opcodes.INSTANCEOF, TVI));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, afterInstanceof));

        // raw = ((TagValueInput)input).input.get("Items")
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0)); // input
        pre.add(new TypeInsnNode(Opcodes.CHECKCAST, TVI));
        pre.add(new FieldInsnNode(Opcodes.GETFIELD, TVI, "input", "L" + COMPOUND + ";"));
        pre.add(new LdcInsnNode("Items"));
        pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, COMPOUND, "get",
                "(Ljava/lang/String;)L" + TAG + ";", false));
        pre.add(new VarInsnNode(Opcodes.ASTORE, 2)); // store raw

        pre.add(afterInstanceof);
        // if (ContainerHelperInterceptor.onLoadItem(raw, items, null)) return
        pre.add(new VarInsnNode(Opcodes.ALOAD, 2)); // raw
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1)); // items (second param)
        pre.add(new InsnNode(Opcodes.ACONST_NULL)); // container
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTERCEPTOR, "onLoadItem",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, skipAll));
        pre.add(new InsnNode(Opcodes.RETURN));

        pre.add(skipAll);
        mn.instructions.insert(pre);
    }

    /**
     * 在 saveAllItems 入口插入:
     * <pre>
     * if (LazyContainerRuntime.active) {
     *     int action = ContainerHelperInterceptor.onSaveItem(output, items, allowEmpty);
     *     if (action == 1) return;
     * }
     * </pre>
     *
     * <p>static method local layout:
     * D_SAVE2 (2 params): local 0=output, local 1=items, local 2=action
     * D_SAVE3 (3 params): local 0=output, local 1=items, local 2=allowEmpty, local 3=action</p>
     */
    private void injectSaveInterceptor(MethodNode mn, boolean alwaysAllowEmpty) {
        InsnList pre = new InsnList();
        LabelNode skipAll = new LabelNode();
        int actionLocal = alwaysAllowEmpty ? 2 : 3;

        pre.add(new FieldInsnNode(Opcodes.GETSTATIC, RUNTIME, "active", "Z"));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, skipAll));

        // int action = onSaveItem(output, items, allowEmpty)
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0)); // output (first param)
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1)); // items (second param)
        if (alwaysAllowEmpty) {
            // D_SAVE2: no allowEmpty param → push true
            pre.add(new InsnNode(Opcodes.ICONST_1));
        } else {
            // D_SAVE3: load allowEmpty from local 2
            pre.add(new VarInsnNode(Opcodes.ILOAD, 2));
        }
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTERCEPTOR, "onSaveItem",
                "(Ljava/lang/Object;Ljava/lang/Object;Z)I", false));
        pre.add(new VarInsnNode(Opcodes.ISTORE, actionLocal));

        // if (action == 1) return
        pre.add(new VarInsnNode(Opcodes.ILOAD, actionLocal));
        pre.add(new InsnNode(Opcodes.ICONST_1));
        LabelNode afterReturn = new LabelNode();
        pre.add(new JumpInsnNode(Opcodes.IF_ICMPNE, afterReturn));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(afterReturn);

        pre.add(skipAll);
        mn.instructions.insert(pre);
    }

    // ─────────────────────── Leaf getItems() guard ───────────────────────

    /**
     * 在 leaf class 的 getItems() 入口插入:
     * <pre>
     * ContainerHelperInterceptor.ensure(this);
     * </pre>
     *
     * <pre>
     * REVIEW(D6): 支援的容器:
     *   - Chest/Barrel/ShulkerBox (RandomizableContainer 子類)
     *   - Hopper/Dispenser (RandomizableContainer 子類,有自己的 getItems)
     *   - AbstractFurnace (有自己的 getItems,涵蓋 Furnace/Smoker/BlastFurnace)
     * Dropper 繼承 Dispenser → 已覆蓋。
     * </pre>
     */
    private byte[] transformLeaf(byte[] buffer, String className) {
        ClassNode cn = new ClassNode();
        new ClassReader(buffer).accept(cn, 0);

        for (MethodNode mn : cn.methods) {
            if ((mn.name.equals("getItems") && mn.desc.equals("()L" + NNL + ";"))
                    || (mn.name.equals("getContents") && mn.desc.equals("()Ljava/util/List;"))) {
                injectEnsureGuard(mn);
                System.out.println("[LazyContainer] added ensure guard to " + className + "." + mn.name);
            }
        }

        ClassWriter cw = nmsFramesClassWriter();
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * COMPUTE_FRAMES 專用 ClassWriter:遇到 NMS type 時回退 Object。
     * 避免 ASM 因看不到 NMS class hierarchy 而噴 RuntimeException。
     *
     * <p>注意:ASM 方法名為 {@code getCommonSuperClass} (大寫 C),不是 getCommonSuperclass。</p>
     */
    private static ClassWriter nmsFramesClassWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                if (type1.startsWith("net/minecraft/") || type2.startsWith("net/minecraft/")) {
                    return "java/lang/Object";
                }
                return super.getCommonSuperClass(type1, type2);
            }
        };
    }

    /**
     * 在方法入口插入:
     * <pre>
     * ContainerHelperInterceptor.ensure(this);
     * </pre>
     */
    private void injectEnsureGuard(MethodNode mn) {
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTERCEPTOR, "ensure",
                "(Ljava/lang/Object;)V", false));
        mn.instructions.insert(pre);
    }

    // ─────────────────────── Shadow mode (implemented in Interceptor) ───────────────────────

    // Shadow mode uses ContainerHelperInterceptor.shadowEagerRoundtrip() (reflection-based).
    // No ASM-generated helper methods needed — the interceptor handles the full
    // parse→encode roundtrip via context classloader reflection.
    // See ContainerHelperInterceptor.shadowEagerRoundtrip() and compareRawEager().
    //
    // When adding new MC versions, ensure the reflection cache in
    // ContainerHelperInterceptor.ensureReflectReady() can resolve all required NMS types.
}
