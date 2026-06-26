package io.github.kuohsuanlo.lazycontainer;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

/**
 * 安全 detach 管理 — flush pending + unregister transformer + 選擇性 retransform。
 *
 * <p>呼叫流程 (從 agentmain 或 JMX/自訂 API):</p>
 * <ol>
 *   <li>{@link #flushAndDeactivate()} — 物化所有 pending container,設 active=false</li>
 *   <li>{@link #unregister(Instrumentation, ClassFileTransformer)} — 移除 transformer</li>
 *   <li>{@link #retransformOriginals(Instrumentation)} — 把被改過的 class 還原</li>
 * </ol>
 *
 * <pre>
 * REVIEW(D3): retransform 還原。
 *   retransformClasses(class) 會重新觸發所有註冊的 ClassFileTransformer。
 *   若 transformer 已 unregister,則 class 會回歸原始 bytes (無 transform)。
 *   但前提是原始 bytes 仍被 JVM 保存 (retransform 能力依賴 Can-Retransform-Classes)。
 *   若 JVM 不支援 retransform → class 維持修改後的狀態 → 但 active=false 確保 stub 不干預。
 * </pre>
 */
public final class DetachManager {

    private DetachManager() {}

    /** 已註冊的 transformer 引用 (由 AgentMain 在 registerTransformer 時設定)。 */
    static volatile ClassFileTransformer registeredTransformer;

    /** 物化所有 pending container + 設 active=false。 */
    public static void flushAndDeactivate() {
        ContainerHelperInterceptor.active = false;
        LazyContainerRuntime.active = false;

        // ponytail: snapshot key set to avoid concurrent modification
        List<Object> allItems;
        synchronized (ContainerHelperInterceptor.pendingByItems) {
            allItems = new ArrayList<>(ContainerHelperInterceptor.pendingByItems.keySet());
        }
        for (Object items : allItems) {
            ContainerHelperInterceptor.ensureItems(items);
        }
        int flushed = allItems.size();
        if (flushed > 0) {
            System.out.println("[LazyContainer] flushed " + flushed + " pending containers during detach");
        }
    }

    /** 從 Instrumentation 移除 transformer。 */
    public static void unregister(Instrumentation inst, ClassFileTransformer tf) {
        if (tf != null) {
            inst.removeTransformer(tf);
            System.out.println("[LazyContainer] transformer unregistered");
        }
    }

    /**
     * 對 agent 修改過的 class 發起重定義 (還原為原始 bytecode)。
     * 需在 unregister transformer 後呼叫。
     */
    public static void retransformOriginals(Instrumentation inst) {
        if (!inst.isRetransformClassesSupported()) {
            System.out.println("[LazyContainer] retransform not supported; classes retain modified bytecode");
            return;
        }
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String n = c.getName();
            if (n.equals("net.minecraft.world.ContainerHelper")
                    || n.equals("net.minecraft.world.level.block.entity.ChestBlockEntity")
                    || n.equals("net.minecraft.world.level.block.entity.BarrelBlockEntity")
                    || n.equals("net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity")) {
                try {
                    inst.retransformClasses(c);
                    System.out.println("[LazyContainer] retransformed " + n + " to original");
                } catch (Throwable t) {
                    System.err.println("[LazyContainer] retransform failed for " + n + ": " + t);
                }
            }
        }
    }

    /** 一鍵 detach (flush + unregister + retransform)。 */
    public static void detach(Instrumentation inst) {
        ClassFileTransformer tf = registeredTransformer;
        flushAndDeactivate();
        unregister(inst, tf);
        retransformOriginals(inst);
        System.out.println("[LazyContainer] agent detached");
    }

    /** 淺 reload:只 flush + deactivate,不 unregister transformer。 */
    public static void deactivate() {
        flushAndDeactivate();
        System.out.println("[LazyContainer] agent deactivated");
    }
}
