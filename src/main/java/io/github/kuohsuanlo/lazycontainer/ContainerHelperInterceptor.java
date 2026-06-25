package io.github.kuohsuanlo.lazycontainer;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 純 JDK 攔截器核心 — 掛在 bootstrap classloader,不參照任何 {@code net.minecraft.*}。
 *
 * <p>狀態管理 (pending map) + shadow mode eager roundtrip 皆透過 context classloader 反射。</p>
 *
 * <pre>
 * REVIEW(D5): Shadow mode 實作方式。
 *   onSaveItem 中當 shadow flag 開啟、canWriteRaw 成立時,
 *   先做 eager roundtrip (parse→encode) 並與 raw 比對。
 *   一致 → 寫 raw (safe, 省 encode)。
 *   不一致但 benign reorder (同一組物品不同順序) → 仍寫 raw (safe)。
 *   真不一致 → 改寫 eager (safe)。
 *   所有 NMS 操作皆透過 context classloader 反射。
 * </pre>
 */
public final class ContainerHelperInterceptor {

    private ContainerHelperInterceptor() {}

    public static volatile boolean active;

    // ── State ──
    static final Map<Object, Object> pendingByItems =
            Collections.synchronizedMap(new WeakHashMap<>());
    static final Map<Object, WeakReference<Object>> containerByItems = new ConcurrentHashMap<>();

    // ── Reflection cache ──
    private static Method buildResultMethod;
    private static Method putMethod;
    private static Method loadAllItemsMethod;
    private static Method saveAllItems2Method;   // (VOUT, NNL)
    private static Method saveAllItems3Method;   // (VOUT, NNL, Z)
    private static Method createGlobalMethod;
    private static Method createWithContextMethod;
    private static Method getServerMethod;
    private static Method registryAccessMethod;
    private static Object discardingSingleton;
    private static Class<?> compoundTagClass;
    private static Class<?> nonNullListClass;
    private static Class<?> tagValueOutputClass;
    private static volatile boolean reflectOk = false;
    private static final Object REFLOCK = new Object();

    private static void ensureReflectReady() {
        if (reflectOk) return;
        synchronized (REFLOCK) {
            if (reflectOk) return;
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) cl = ContainerHelperInterceptor.class.getClassLoader();

                // TagValueOutput
                tagValueOutputClass = Class.forName(
                        "net.minecraft.world.level.storage.TagValueOutput", false, cl);
                buildResultMethod = tagValueOutputClass.getMethod("buildResult");
                createWithContextMethod = tagValueOutputClass.getMethod("createWithContext",
                        Class.forName("net.minecraft.util.ProblemReporter", false, cl),
                        Class.forName("net.minecraft.core.RegistryAccess", false, cl));

                // CompoundTag
                compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag", false, cl);
                putMethod = compoundTagClass.getMethod("put", String.class,
                        Class.forName("net.minecraft.nbt.Tag", false, cl));

                // NonNullList
                nonNullListClass = Class.forName("net.minecraft.core.NonNullList", false, cl);

                // ContainerHelper
                Class<?> ch = Class.forName("net.minecraft.world.ContainerHelper", false, cl);
                Class<?> viClass = Class.forName("net.minecraft.world.level.storage.ValueInput", false, cl);
                loadAllItemsMethod = ch.getMethod("loadAllItems", viClass, nonNullListClass);
                Class<?> voClass = Class.forName("net.minecraft.world.level.storage.ValueOutput", false, cl);
                saveAllItems2Method = ch.getMethod("saveAllItems", voClass, nonNullListClass);
                saveAllItems3Method = ch.getMethod("saveAllItems", voClass, nonNullListClass, boolean.class);

                // TagValueInput + ProblemReporter
                Class<?> tvi = Class.forName("net.minecraft.world.level.storage.TagValueInput", false, cl);
                Class<?> pr = Class.forName("net.minecraft.util.ProblemReporter", false, cl);
                createGlobalMethod = tvi.getMethod("createGlobal", pr, compoundTagClass);
                discardingSingleton = pr.getField("DISCARDING").get(null);

                // MinecraftServer (for registryAccess)
                Class<?> ms = Class.forName("net.minecraft.server.MinecraftServer", false, cl);
                getServerMethod = ms.getMethod("getServer");
                registryAccessMethod = ms.getMethod("registryAccess");

                // NonNullList.withSize (for shadow temp list)
                // ponytail: 若找不到 withSize,改用 ArrayList—功能相同但型別不符時會炸
                // 但 ContainerHelper.loadAllItems 要求 NonNullList,所以必須找到
                try {
                    nonNullListClass.getMethod("withSize", int.class, Object.class);
                } catch (NoSuchMethodException e) {
                    // 1.12 可能不同,留 fallback
                }

                reflectOk = true;
            } catch (Exception e) {
                System.err.println("[LazyContainer] reflect init failed — "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    // ── ASM stub 呼叫點 ──

    /** @return true → stub 應跳過原始 decode */
    public static boolean onLoadItem(Object rawTag, Object items, Object container) {
        if (!active || rawTag == null) return false;
        pendingByItems.put(items, rawTag);
        if (container != null) {
            containerByItems.put(items, new WeakReference<>(container));
        }
        LazyContainerRuntime.onStash();
        return true;
    }

    /**
     * @return 0=normal encode, 1=raw written (skip encode), 2=ensure+normal encode
     *
     * <pre>
     * Shadow mode 流程 (onSaveItem 内部):
     *   1. raw 存在且 canWriteRaw → 進入 shadow 或直接寫 raw
     *   2. shadow flag on → eager roundtrip → 與 raw 比對
     *      a. 一致 → 寫 raw (利)
     *      b. benign reorder → 寫 raw (安全,僅計數)
     *      c. 真不一致 → 寫 eager (安全)
     *   3. shadow flag off → 直接寫 raw
     *   4. 無法寫 raw → ensureItems → 回 2 (caller 正常 encode)
     * </pre>
     */
    public static int onSaveItem(Object output, Object items, boolean allowEmpty) {
        if (!active) return 0;
        Object raw = pendingByItems.get(items);
        if (raw == null) return 0;

        ensureReflectReady();

        if (!isListTag(raw)) { ensureItems(items); return 2; }
        if (!allowEmpty && isEmptyListTag(raw)) { ensureItems(items); return 2; }
        if (!reflectOk) { ensureItems(items); return 2; }

        // mustCheckEager: shadow mode + 需要比對或直接寫 raw
        boolean shadow = LazyContainerRuntime.shadow();
        if (!shadow) {
            return writeRaw(output, items, raw);
        }

        // Shadow mode: compute eager and compare
        try {
            Object eager = shadowEagerRoundtrip(raw, allowEmpty, items);
            boolean safe = compareRawEager(raw, eager, items);
            if (safe) {
                return writeRaw(output, items, raw);
            } else {
                // real mismatch → write eager instead
                LazyContainerRuntime.onShadowMismatch();
                String pos = resolvePos(items);
                LazyContainerRuntime.dumpMismatch(pos,
                        String.valueOf(raw), eager == null ? "<discard>" : String.valueOf(eager));
                System.err.println("[LazyContainer] SHADOW mismatch @ " + pos
                        + " — writing eager (safe). rawType=" + raw.getClass().getSimpleName());
                if (eager != null) {
                    Object outTag = buildResultMethod.invoke(output);
                    putMethod.invoke(outTag, "Items", eager);
                }
                pendingByItems.remove(items);
                containerByItems.remove(items);
                return 1;
            }
        } catch (Exception e) {
            // Shadow check failed → fall back to raw write (safer than corrupting data)
            System.err.println("[LazyContainer] shadow check failed — " + e.getMessage()
                    + " — falling back to raw write");
            return writeRaw(output, items, raw);
        }
    }

    /** 直接寫 raw 進 output (無 shadow check)。 */
    private static int writeRaw(Object output, Object items, Object raw) {
        try {
            Object outTag = buildResultMethod.invoke(output);
            putMethod.invoke(outTag, "Items", raw);
            pendingByItems.remove(items);
            containerByItems.remove(items);
            LazyContainerRuntime.onRawSave();
            return 1;
        } catch (Exception e) {
            ensureItems(items);
            return 2;
        }
    }

    // ── Shadow roundtrip ──

    /**
     * 將 raw Tag parse 後重新 encode,回傳 eager 版本的 "Items" Tag。
     * @param raw 原始 Items Tag (ListTag)
     * @param allowEmpty false for shulker
     * @param items 原始 items list (用於取得 container size)
     * @return eager 版本的 Items Tag (可能是 null,表示 vanilla 會 discard)
     */
    private static Object shadowEagerRoundtrip(Object raw, boolean allowEmpty, Object items) throws Exception {
        // 1. Create CompoundTag with raw Items
        Object tmp = compoundTagClass.getDeclaredConstructor().newInstance();
        putMethod.invoke(tmp, "Items", raw);

        // 2. Create TagValueInput
        Object vi = createGlobalMethod.invoke(null, discardingSingleton, tmp);

        // 3. Create temp NonNullList with correct size
        int size = (int) items.getClass().getMethod("size").invoke(items);
        // ponytail: 用原始 items list 的 class 建立新 instance,確保型別正確
        Object tmpList = nonNullListClass.getMethod("withSize", int.class, Object.class)
                .invoke(null, size, getItemStackEmpty());

        // 4. loadAllItems (parse raw into temp list)
        loadAllItemsMethod.invoke(null, vi, tmpList);

        // 5. Create TagValueOutput for re-encode
        Object server = getServerMethod.invoke(null);
        Object registryAccess = registryAccessMethod.invoke(server);
        Object eagerOut = createWithContextMethod.invoke(null, discardingSingleton, registryAccess);

        // 6. saveAllItems (encode temp list back)
        if (allowEmpty) {
            saveAllItems2Method.invoke(null, eagerOut, tmpList);
        } else {
            saveAllItems3Method.invoke(null, eagerOut, tmpList, false);
        }

        // 7. Extract "Items" tag from output
        Object outTag = buildResultMethod.invoke(eagerOut);
        return outTag.getClass().getMethod("get", String.class).invoke(outTag, "Items");
    }

    /**
     * 比較 raw 與 eager 是否一致。
     * @return true → safe to write raw (一致或 benign reorder)
     *         false → real mismatch, 應寫 eager
     */
    private static boolean compareRawEager(Object raw, Object eager, Object items) {
        if (Objects.equals(raw, eager)) return true;

        // Not byte-identical → check if benign reorder
        if (isBenignReorder(raw, eager)) {
            String pos = resolvePos(items);
            LazyContainerRuntime.onBenignReorder(pos,
                    String.valueOf(raw), String.valueOf(eager));
            return true; // benign → safe to write raw
        }
        return false;
    }

    /**
     * raw 與 eager 是否為「同一組物品、只是 Items 清單順序不同」。
     * 用 multiset 比對 (每個 entry 自帶 Slot,清單順序不影響槽位)。
     */
    private static boolean isBenignReorder(Object rawTag, Object eagerTag) {
        if (rawTag == null || eagerTag == null) return false;
        String rawCls = rawTag.getClass().getName();
        String eagerCls = eagerTag.getClass().getName();
        if (!rawCls.endsWith(".ListTag") || !eagerCls.endsWith(".ListTag")) return false;

        try {
            int n = (int) rawTag.getClass().getMethod("size").invoke(rawTag);
            if (n != (int) eagerTag.getClass().getMethod("size").invoke(eagerTag)) return false;

            // Multiset: for each element in raw, find a matching unused element in eager
            boolean[] used = new boolean[n];
            Method getMethod = rawTag.getClass().getMethod("get", int.class);
            for (int i = 0; i < n; i++) {
                Object rawElem = getMethod.invoke(rawTag, i);
                boolean found = false;
                for (int j = 0; j < n; j++) {
                    if (!used[j] && rawElem.equals(getMethod.invoke(eagerTag, j))) {
                        used[j] = true;
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Ensure (物化) ──

    public static void ensure(Object container) {
        try {
            Object items = findItemsField(container);
            if (items != null) ensureItems(items);
        } catch (Exception ignored) {
        }
    }

    public static void ensureItems(Object items) {
        if (!active) return;
        Object raw = pendingByItems.remove(items);
        if (raw == null) return;
        containerByItems.remove(items);
        ensureReflectReady();
        if (!reflectOk) return;

        try {
            Object tmp = compoundTagClass.getDeclaredConstructor().newInstance();
            putMethod.invoke(tmp, "Items", raw);
            Object vi = createGlobalMethod.invoke(null, discardingSingleton, tmp);
            loadAllItemsMethod.invoke(null, vi, items);
            LazyContainerRuntime.onEnsure();
        } catch (Exception e) {
            System.err.println("[LazyContainer] ensureItems failed — "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            LazyContainerRuntime.onEagerLoad();
        }
    }

    public static int pendingCount() { return pendingByItems.size(); }

    // ── 內部輔助 ──

    private static boolean isListTag(Object raw) {
        if (raw == null) return false;
        String name = raw.getClass().getName();
        return name.endsWith(".ListTag") || name.endsWith(".NBTTagList");
    }

    private static boolean isEmptyListTag(Object raw) {
        try {
            return (int) raw.getClass().getMethod("size").invoke(raw) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 從 containerByItems map 解析 BlockPos 字串。 */
    private static String resolvePos(Object items) {
        WeakReference<Object> ref = containerByItems.get(items);
        if (ref == null) return "?";
        Object container = ref.get();
        if (container == null) return "?";
        try {
            Object pos = container.getClass().getMethod("getBlockPos").invoke(container);
            return String.valueOf(pos);
        } catch (Exception e) {
            return container.getClass().getSimpleName() + "@" + System.identityHashCode(container);
        }
    }

    /** 反射取得 ItemStack.EMPTY (singleton)。 */
    private static Object getItemStackEmpty() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return Class.forName("net.minecraft.world.item.ItemStack", false, cl)
                .getField("EMPTY").get(null);
    }

    private static Object findItemsField(Object container) throws Exception {
        Class<?> scan = container.getClass();
        while (scan != null) {
            for (String name : new String[]{"items", "itemStacks"}) {
                try {
                    var field = scan.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(container);
                } catch (NoSuchFieldException ignored) {}
            }
            scan = scan.getSuperclass();
        }
        return null;
    }
}
