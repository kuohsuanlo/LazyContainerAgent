package io.github.kuohsuanlo.lazycontainer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NMS 名稱註冊表 — 每個 MC 版本需要知道的 NMS 類別/方法/欄位名稱。
 *
 * <p>Transformer 與 Interceptor 透過此 registry 取得 per-version 的 mapping,
 * 避免在程式碼中 hardcode 1.21.11 的 mojmap 名稱。</p>
 *
 * <pre>
 * REVIEW(D3): mapping 維護策略。
 *   每個 major MC 版本需提供以下資訊:
 *   1. ContainerHelper 的 internal class name (obf/mojmap)
 *   2. loadAllItems / saveAllItems 的方法名 + desc
 *   3. TagValueInput / TagValueOutput 的 internal name
 *   4. container BE 的欄位名 (items / itemStacks / ...)
 *   5. container BE 的 class name 白名單 (for leaf transform)
 *   目前只實作了 1.21.11 mojmap。新增版本只需加一個 enum entry + mapping。
 * </pre>
 */
public final class NmsRegistry {

    private NmsRegistry() {}

    private static final Map<VersionDetector.McVersion, NmsMapping> MAPPINGS = new HashMap<>();

    // Base mojmap names for 1.17+ (ContainerHelper, NBT, ValueIO classes are stable)
    private static NmsMapping baseMojmap() {
        return new NmsMapping()
                .containerHelper("net/minecraft/world/ContainerHelper")
                .compoundTag("net/minecraft/nbt/CompoundTag")
                .tag("net/minecraft/nbt/Tag")
                .listTag("net/minecraft/nbt/ListTag")
                .nonNullList("net/minecraft/core/NonNullList")
                .problemReporter("net/minecraft/util/ProblemReporter")
                .itemStack("net/minecraft/world/item/ItemStack")
                .minecraftServer("net/minecraft/server/MinecraftServer")
                .loadAllItems("loadAllItems", "(Lnet/minecraft/world/level/storage/ValueInput;Lnet/minecraft/core/NonNullList;)V")
                .saveAllItems2("saveAllItems", "(Lnet/minecraft/world/level/storage/ValueOutput;Lnet/minecraft/core/NonNullList;)V")
                .saveAllItems3("saveAllItems", "(Lnet/minecraft/world/level/storage/ValueOutput;Lnet/minecraft/core/NonNullList;Z)V")
                .leafClasses(Arrays.asList(
                        "net/minecraft/world/level/block/entity/ChestBlockEntity",
                        "net/minecraft/world/level/block/entity/BarrelBlockEntity",
                        "net/minecraft/world/level/block/entity/ShulkerBoxBlockEntity",
                        "net/minecraft/world/level/block/entity/HopperBlockEntity",
                        "net/minecraft/world/level/block/entity/DispenserBlockEntity",
                        "net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity"))
                .containerFieldNames(Arrays.asList("items", "itemStacks"));
    }

    static {
        // 26.2 — mojmap, verified (classfile major 69)
        // ContainerHelper/TagValueInput/TagValueOutput/ValueInput/ValueOutput 與 1.21.11 相容
        register(VersionDetector.McVersion.V26_2, baseMojmap()
                .tagValueInput("net/minecraft/world/level/storage/TagValueInput")
                .tagValueOutput("net/minecraft/world/level/storage/TagValueOutput")
                .valueInput("net/minecraft/world/level/storage/ValueInput")
                .valueOutput("net/minecraft/world/level/storage/ValueOutput")
                .registryAccess("net/minecraft/core/RegistryAccess"));

        // 1.21.11 — mojmap, verified (classfile major 65)
        register(VersionDetector.McVersion.V1_21_11, baseMojmap()
                .tagValueInput("net/minecraft/world/level/storage/TagValueInput")
                .tagValueOutput("net/minecraft/world/level/storage/TagValueOutput")
                .valueInput("net/minecraft/world/level/storage/ValueInput")
                .valueOutput("net/minecraft/world/level/storage/ValueOutput")
                .registryAccess("net/minecraft/core/RegistryAccess"));

        // 1.20.4 — mojmap (classfile major 63)
        register(VersionDetector.McVersion.V1_20_4, baseMojmap()
                .tagValueInput("net/minecraft/world/level/storage/TagValueInput")
                .tagValueOutput("net/minecraft/world/level/storage/TagValueOutput")
                .valueInput("net/minecraft/world/level/storage/ValueInput")
                .valueOutput("net/minecraft/world/level/storage/ValueOutput")
                .registryAccess("net/minecraft/core/RegistryAccess"));

        // 1.19.4 — mojmap (classfile major 61, same as 1.18)
        register(VersionDetector.McVersion.V1_19_4, baseMojmap()
                .tagValueInput("net/minecraft/world/level/storage/TagValueInput")
                .tagValueOutput("net/minecraft/world/level/storage/TagValueOutput")
                .valueInput("net/minecraft/world/level/storage/ValueInput")
                .valueOutput("net/minecraft/world/level/storage/ValueOutput"));

        // 1.18.2 — mojmap (classfile major 61)
        register(VersionDetector.McVersion.V1_18_2, baseMojmap()
                .tagValueInput("net/minecraft/world/level/storage/TagValueInput")
                .tagValueOutput("net/minecraft/world/level/storage/TagValueOutput")
                .valueInput("net/minecraft/world/level/storage/ValueInput")
                .valueOutput("net/minecraft/world/level/storage/ValueOutput"));

        // 1.17.1 — mojmap (classfile major 60)
        register(VersionDetector.McVersion.V1_17_1, baseMojmap()
                .tagValueInput("net/minecraft/world/level/storage/TagValueInput")
                .tagValueOutput("net/minecraft/world/level/storage/TagValueOutput")
                .valueInput("net/minecraft/world/level/storage/ValueInput")
                .valueOutput("net/minecraft/world/level/storage/ValueOutput"));

        // 1.16.5 (classfile major 52) — NOT SUPPORTED by v2 architecture.
        // v2 intercepts ContainerHelper.loadAllItems(ValueInput, NonNullList),
        // but 1.16.5 has NO TagValueInput/ValueInput/ValueOutput classes
        // (those were added in 1.17).  ContainerHelper.loadAllItems in 1.16.5
        // takes (CompoundTag, NonNullList) instead — a completely different
        // signature that the current interceptor does not handle.
        // To support 1.16.5, a separate injection path for the older signature
        // would need to be added.  For now, agent will print "not supported".
        // V1_16_5 remains in VersionDetector to avoid "unknown major" warnings.

        // 1.12.2 — NO ContainerHelper class (ContainerHelper was introduced in 1.13).
        // Agent architecture depends on ContainerHelper interception → NOT SUPPORTED.
        // No register() call for V1_12_2 — AgentMain will print "version not supported".
    }

    private static void register(VersionDetector.McVersion version, NmsMapping mapping) {
        MAPPINGS.put(version, mapping);
    }

    /**
     * 取得對應版本的 NMS mapping。若版本不支援 (UNKNOWN 或無 mapping) → 回傳 null。
     */
    public static NmsMapping forVersion(VersionDetector.McVersion version) {
        return MAPPINGS.get(version);
    }

    /** 目前版本 (VersionDetector 偵測) 的 mapping。 */
    public static NmsMapping current() {
        return forVersion(VersionDetector.current());
    }

    // ── Mapping data class ──

    public static final class NmsMapping {
        private String containerHelper;
        private String tagValueInput;
        private String tagValueOutput;
        private String valueInput;
        private String valueOutput;
        private String compoundTag;
        private String tag;
        private String listTag;
        private String nonNullList;
        private String problemReporter;
        private String itemStack;
        private String minecraftServer;
        private String registryAccess;
        private String loadAllItemsName;
        private String loadAllItemsDesc;
        private String saveAllItems2Name;
        private String saveAllItems2Desc;
        private String saveAllItems3Name;
        private String saveAllItems3Desc;
        private List<String> leafClasses;
        private List<String> containerFieldNames;

        // Builder-style setters
        public NmsMapping containerHelper(String n) { this.containerHelper = n; return this; }
        public NmsMapping tagValueInput(String n) { this.tagValueInput = n; return this; }
        public NmsMapping tagValueOutput(String n) { this.tagValueOutput = n; return this; }
        public NmsMapping valueInput(String n) { this.valueInput = n; return this; }
        public NmsMapping valueOutput(String n) { this.valueOutput = n; return this; }
        public NmsMapping compoundTag(String n) { this.compoundTag = n; return this; }
        public NmsMapping tag(String n) { this.tag = n; return this; }
        public NmsMapping listTag(String n) { this.listTag = n; return this; }
        public NmsMapping nonNullList(String n) { this.nonNullList = n; return this; }
        public NmsMapping problemReporter(String n) { this.problemReporter = n; return this; }
        public NmsMapping itemStack(String n) { this.itemStack = n; return this; }
        public NmsMapping minecraftServer(String n) { this.minecraftServer = n; return this; }
        public NmsMapping registryAccess(String n) { this.registryAccess = n; return this; }
        public NmsMapping loadAllItems(String n, String d) { this.loadAllItemsName = n; this.loadAllItemsDesc = d; return this; }
        public NmsMapping saveAllItems2(String n, String d) { this.saveAllItems2Name = n; this.saveAllItems2Desc = d; return this; }
        public NmsMapping saveAllItems3(String n, String d) { this.saveAllItems3Name = n; this.saveAllItems3Desc = d; return this; }
        public NmsMapping leafClasses(List<String> l) { this.leafClasses = l; return this; }
        public NmsMapping containerFieldNames(List<String> l) { this.containerFieldNames = l; return this; }

        // Getters
        public String containerHelper() { return containerHelper; }
        public String tagValueInput() { return tagValueInput; }
        public String tagValueOutput() { return tagValueOutput; }
        public String valueInput() { return valueInput; }
        public String valueOutput() { return valueOutput; }
        public String compoundTag() { return compoundTag; }
        public String tag() { return tag; }
        public String listTag() { return listTag; }
        public String nonNullList() { return nonNullList; }
        public String problemReporter() { return problemReporter; }
        public String itemStack() { return itemStack; }
        public String minecraftServer() { return minecraftServer; }
        public String registryAccess() { return registryAccess; }
        public String loadAllItemsName() { return loadAllItemsName; }
        public String loadAllItemsDesc() { return loadAllItemsDesc; }
        public String saveAllItems2Name() { return saveAllItems2Name; }
        public String saveAllItems2Desc() { return saveAllItems2Desc; }
        public String saveAllItems3Name() { return saveAllItems3Name; }
        public String saveAllItems3Desc() { return saveAllItems3Desc; }
        public List<String> leafClasses() { return leafClasses; }
        public List<String> containerFieldNames() { return containerFieldNames; }
    }
}
