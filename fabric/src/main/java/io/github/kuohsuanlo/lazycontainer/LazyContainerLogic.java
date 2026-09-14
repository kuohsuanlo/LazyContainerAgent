package io.github.kuohsuanlo.lazycontainer;

import java.util.Objects;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
//? if >=1.21.6 {
import io.github.kuohsuanlo.lazycontainer.mixin.TagValueInputAccessor;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?} else {
/*import net.minecraft.core.HolderLookup;
*///?}

/**
 * 延遲反序列化核心邏輯 —— 移植自 LazyContainerAgent 的 {@code LazyContainerTemplate}。
 *
 * <p>原版是把這份邏輯用 ASM splice 進 NMS {@code BaseContainerBlockEntity};Fabric 版改由
 * leaf mixin 的 {@code @Redirect} 呼叫進來,狀態(pending/raw)存在
 * {@code BaseContainerBlockEntityMixin} 的 @Unique 欄位({@link LazyContainerState})。</p>
 *
 * <p>兩條 API 路徑:</p>
 * <ul>
 *   <li><b>1.21.6+</b>:{@code ValueInput}/{@code ValueOutput}(TagValueInput/TagValueOutput)。</li>
 *   <li><b>1.21.1~1.21.5</b>:{@code CompoundTag} + {@code HolderLookup.Provider} 直接進出。</li>
 * </ul>
 */
public final class LazyContainerLogic {

    private LazyContainerLogic() {
    }

    //? if >=1.21.6 {
    // ───────────────────────── 1.21.6+ (ValueInput/ValueOutput) ─────────────────────────

    /**
     * 取代 {@code ContainerHelper.loadAllItems(input, items)}。
     * 若 input 是 TagValueInput(chunk 載入恆是),擷取未解碼的 "Items" tag 暫存、標記 pending,
     * <b>跳過昂貴的 decode</b>;否則退回 eager(安全)。
     */
    public static void load(BlockEntity be, ValueInput input, NonNullList<ItemStack> items) {
        LazyContainerState st = (LazyContainerState) be;
        if (input instanceof TagValueInput) {
            st.lazycontainer$setRaw(((TagValueInputAccessor) input).lazycontainer$input().get("Items"));
            st.lazycontainer$setPending(true);
            LazyContainerRuntime.onStash();
            return;
        }
        ContainerHelper.loadAllItems(input, items);
        st.lazycontainer$setPending(false);
        LazyContainerRuntime.onEagerLoad();
    }

    /** 取代 {@code ContainerHelper.saveAllItems(...)};allowEmpty=true 對應 chest/barrel、false 對應 shulker。 */
    public static void save(BlockEntity be, ValueOutput output, NonNullList<ItemStack> items, boolean allowEmpty) {
        if (trySaveRaw(be, output, allowEmpty)) {
            return;
        }
        if (allowEmpty) {
            ContainerHelper.saveAllItems(output, items);
        } else {
            ContainerHelper.saveAllItems(output, items, false);
        }
    }

    /**
     * 若容器自載入後從未物化(pending)且可安全回寫,把原始 "Items" tag 逐位元組塞回 output,回傳 true
     * (呼叫端跳過 encode)。否則先物化(ensure)再回傳 false(呼叫端走正常 encode)。
     */
    private static boolean trySaveRaw(BlockEntity be, ValueOutput output, boolean allowEmpty) {
        LazyContainerState st = (LazyContainerState) be;
        if (!st.lazycontainer$isPending()) {
            return false;
        }
        Tag raw = st.lazycontainer$getRaw();
        // 只在 raw 為「真正的 ListTag」時走快路徑;且 allowEmpty==false(shulker)遇空清單不可寫 raw
        // (vanilla 對空清單會 discard "Items")。其餘一律退回 ensure+正常 save,逐位元組對齊 vanilla。
        boolean canWriteRaw = (raw instanceof ListTag)
                && !(!allowEmpty && ((ListTag) raw).isEmpty());
        if (canWriteRaw && output instanceof TagValueOutput) {
            CompoundTag out = ((TagValueOutput) output).buildResult();
            if (LazyContainerRuntime.shadow()) {
                if (be.getLevel() == null) {
                    // 沒有 registry 上下文可重建 eager 結果(理論上不會發生)→ 保守走正常 encode
                    st.lazycontainer$ensure();
                    return false;
                }
                Tag eager = eagerItems(be, raw, allowEmpty);
                if (!Objects.equals(eager, raw)) {
                    if (sameItems(raw, eager)) {
                        // 只是 Items 清單順序不同、物品與槽位完全相同 → 良性:回報(NO IMPACT)、仍寫 raw(安全)
                        LazyContainerRuntime.onBenignReorder(String.valueOf(be.getBlockPos()),
                                String.valueOf(raw), String.valueOf(eager));
                    } else {
                        LazyContainerRuntime.onShadowMismatch();
                        LazyContainerRuntime.dumpMismatch(String.valueOf(be.getBlockPos()),
                                String.valueOf(raw), eager == null ? "<discard>" : String.valueOf(eager));
                        System.err.println("[LazyContainer] SHADOW mismatch @ " + be.getBlockPos()
                                + " — writing eager (safe). rawType=" + raw.getClass().getSimpleName());
                        if (eager != null) {
                            out.put("Items", eager);
                        } else {
                            out.remove("Items");
                        }
                        return true;
                    }
                }
            }
            // 必須寫 raw 的深拷貝:raw 是活體 BE 欄位,寫回後 pending 不清、raw 保留。
            // 直接放本體會讓存檔輸出與活容器別名(非同步序列化撕裂 / /clone 與 structure 快照共享同一 ListTag)。
            // copy 樹複製仍遠比 codec encode 便宜。
            out.put("Items", raw.copy());
            LazyContainerRuntime.onRawSave();
            return true;
        }
        st.lazycontainer$ensure();
        return false;
    }

    /** shadow 用:把 raw 完整 parse→encode 一次,回傳 eager 會寫出的 "Items" tag(可能 null = 被 discard)。 */
    private static Tag eagerItems(BlockEntity be, Tag raw, boolean allowEmpty) {
        CompoundTag reIn = new CompoundTag();
        reIn.put("Items", raw);
        int size = ((BaseContainerBlockEntity) be).getContainerSize();
        NonNullList<ItemStack> tmp = NonNullList.withSize(size, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(
                TagValueInput.create(ProblemReporter.DISCARDING, be.getLevel().registryAccess(), reIn), tmp);
        TagValueOutput eagerOut = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, be.getLevel().registryAccess());
        if (allowEmpty) {
            ContainerHelper.saveAllItems(eagerOut, tmp);
        } else {
            ContainerHelper.saveAllItems(eagerOut, tmp, false);
        }
        return eagerOut.buildResult().get("Items");
    }

    /** 首次被任何存取點觸發時,把暫存的 raw 解碼進真正的 items 清單。set-flag-first 保證 reentrancy 安全。 */
    public static void ensure(LazyContainerState st) {
        if (!st.lazycontainer$isPending()) {
            return;
        }
        st.lazycontainer$setPending(false);     // 先清旗標 → 下面 items() 觸發的 guard 不會 reenter
        Tag raw = st.lazycontainer$getRaw();
        if (raw == null) {
            return;
        }
        try {
            BlockEntity be = (BlockEntity) st;
            if (be.getLevel() == null) {
                // 理論上不可達:vanilla 在 BE 進世界前不會存取容器內容。拋出 → catch 還原 pending/raw,之後重試
                throw new IllegalStateException("cannot materialize container items before level is set");
            }
            CompoundTag tmp = new CompoundTag();
            tmp.put("Items", raw);
            ValueInput vi = TagValueInput.create(ProblemReporter.DISCARDING, be.getLevel().registryAccess(), tmp);
            ContainerHelper.loadAllItems(vi, st.lazycontainer$items());  // 依 slot set,冪等 → 失敗可安全重試
            st.lazycontainer$setRaw(null);                               // 僅「成功物化後」才作廢 raw
            LazyContainerRuntime.onEnsure();
        } catch (Throwable t) {
            // 物化失敗:還原 pending、保留 raw,下次存取重試,絕不靜默丟失
            st.lazycontainer$setPending(true);
            throw t;
        }
    }
    //?} else {
    /*// ───────────────────────── 1.21.1~1.21.5 (CompoundTag + HolderLookup.Provider) ─────────────────────────

    // 取代 ContainerHelper.loadAllItems(tag, items, regs):擷取未解碼 "Items"、標記 pending、跳過 decode。
    public static void load(BlockEntity be, CompoundTag tag, NonNullList<ItemStack> items, HolderLookup.Provider regs) {
        LazyContainerState st = (LazyContainerState) be;
        st.lazycontainer$setRaw(tag.get("Items"));
        st.lazycontainer$setRegs(regs);
        st.lazycontainer$setPending(true);
        LazyContainerRuntime.onStash();
    }

    // 取代 ContainerHelper.saveAllItems(...);allowEmpty=true 對應 chest/barrel、false 對應 shulker。
    public static CompoundTag save(BlockEntity be, CompoundTag tag, NonNullList<ItemStack> items,
                                   boolean allowEmpty, HolderLookup.Provider regs) {
        if (trySaveRaw(be, tag, allowEmpty, regs)) {
            return tag;
        }
        return allowEmpty
                ? ContainerHelper.saveAllItems(tag, items, regs)
                : ContainerHelper.saveAllItems(tag, items, false, regs);
    }

    private static boolean trySaveRaw(BlockEntity be, CompoundTag out, boolean allowEmpty,
                                      HolderLookup.Provider regs) {
        LazyContainerState st = (LazyContainerState) be;
        if (!st.lazycontainer$isPending()) {
            return false;
        }
        Tag raw = st.lazycontainer$getRaw();
        boolean canWriteRaw = (raw instanceof ListTag)
                && !(!allowEmpty && ((ListTag) raw).isEmpty());
        if (canWriteRaw) {
            if (LazyContainerRuntime.shadow()) {
                Tag eager = eagerItems(be, raw, allowEmpty, regs);
                if (!Objects.equals(eager, raw)) {
                    if (sameItems(raw, eager)) {
                        LazyContainerRuntime.onBenignReorder(String.valueOf(be.getBlockPos()),
                                String.valueOf(raw), String.valueOf(eager));
                    } else {
                        LazyContainerRuntime.onShadowMismatch();
                        LazyContainerRuntime.dumpMismatch(String.valueOf(be.getBlockPos()),
                                String.valueOf(raw), eager == null ? "<discard>" : String.valueOf(eager));
                        System.err.println("[LazyContainer] SHADOW mismatch @ " + be.getBlockPos()
                                + " — writing eager (safe). rawType=" + raw.getClass().getSimpleName());
                        if (eager != null) {
                            out.put("Items", eager);
                        } else {
                            out.remove("Items");
                        }
                        return true;
                    }
                }
            }
            out.put("Items", raw.copy());
            LazyContainerRuntime.onRawSave();
            return true;
        }
        st.lazycontainer$ensure();
        return false;
    }

    private static Tag eagerItems(BlockEntity be, Tag raw, boolean allowEmpty, HolderLookup.Provider regs) {
        CompoundTag reIn = new CompoundTag();
        reIn.put("Items", raw);
        int size = ((BaseContainerBlockEntity) be).getContainerSize();
        NonNullList<ItemStack> tmp = NonNullList.withSize(size, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(reIn, tmp, regs);
        CompoundTag eagerOut = new CompoundTag();
        ContainerHelper.saveAllItems(eagerOut, tmp, allowEmpty, regs);
        return eagerOut.get("Items");
    }

    // 首次被任何存取點觸發時,把暫存的 raw 解碼進真正的 items 清單。set-flag-first 保證 reentrancy 安全。
    public static void ensure(LazyContainerState st) {
        if (!st.lazycontainer$isPending()) {
            return;
        }
        st.lazycontainer$setPending(false);
        Tag raw = st.lazycontainer$getRaw();
        if (raw == null) {
            return;
        }
        try {
            CompoundTag tmp = new CompoundTag();
            tmp.put("Items", raw);
            ContainerHelper.loadAllItems(tmp, st.lazycontainer$items(), st.lazycontainer$getRegs());
            st.lazycontainer$setRaw(null);
            LazyContainerRuntime.onEnsure();
        } catch (Throwable t) {
            st.lazycontainer$setPending(true);
            throw t;
        }
    }
    *///?}

    /**
     * raw 與 eager 是否「同一組物品、只是 Items 清單順序不同」。
     * 把兩邊的 Items 當 multiset 比(每個 entry 自帶 Slot,清單順序不影響槽位);
     * 元素用 NBT Tag.equals(CompoundTag 為 map 比對,與 key 順序無關)。
     */
    private static boolean sameItems(Tag rawTag, Tag eagerTag) {
        if (!(rawTag instanceof ListTag) || !(eagerTag instanceof ListTag)) {
            return false;
        }
        ListTag a = (ListTag) rawTag;
        ListTag e = (ListTag) eagerTag;
        int n = a.size();
        if (n != e.size()) {
            return false;
        }
        boolean[] used = new boolean[n];
        for (int i = 0; i < n; i++) {
            Tag ai = a.get(i);
            boolean found = false;
            for (int j = 0; j < n; j++) {
                if (!used[j] && ai.equals(e.get(j))) {
                    used[j] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
