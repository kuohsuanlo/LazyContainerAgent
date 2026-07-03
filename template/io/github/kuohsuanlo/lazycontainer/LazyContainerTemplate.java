package io.github.kuohsuanlo.lazycontainer;

import java.util.Objects;
import net.minecraft.core.NonNullList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * <b>編譯期樣板 — 永不在執行期被載入為類別。</b>
 *
 * <p>本類別對「真實的」1.21.11 mojmap NMS 編譯,目的只是讓 javac 產生「正確的、含 NMS 符號參照與
 * stackmap frame 的」bytecode。Agent 在啟動時讀取本類別的 {@code .class} bytes,把所有
 * {@code lazycontainer$...} 欄位與方法「splice(嫁接)」進真正的
 * {@link net.minecraft.world.level.block.entity.BaseContainerBlockEntity},並把 owner
 * 由本類別 remap 成 BaseContainerBlockEntity。因此這裡的 {@code this} 在執行期就是一個
 * BaseContainerBlockEntity(chest / barrel / shulker)。</p>
 *
 * <p>絕對不可在 agent 程式碼裡以「型別」參照本類別(會觸發 bootstrap classloader 載入 → 找不到 NMS
 * → NoClassDefFoundError);只能讀其 bytes。</p>
 *
 * <h3>不變式(資料安全鐵律)</h3>
 * <ul>
 *   <li>{@code lazycontainer$pending == true} ⟺ items 清單「尚未物化」(仍是載入時建立的全空清單),
 *       真正內容以未解碼的原始 "Items" {@link Tag} 暫存在 {@code lazycontainer$raw}。</li>
 *   <li>一旦任何存取點呼叫 {@code getItems()/getContents()},entry-guard 會先呼叫
 *       {@link #lazycontainer$ensure()} 把 raw 解碼進清單,並把 pending 設為 false、raw 設為 null
 *       (raw 立即作廢,永不再被寫回)。</li>
 *   <li>存檔時:pending 且 raw!=null 且 output 是 TagValueOutput ⟹ 逐位元組把 raw 寫回(跳過 encode);
 *       否則 ⟹ 先物化再正常 encode。最壞只是少省一次,絕不掉資料。</li>
 * </ul>
 */
public abstract class LazyContainerTemplate extends BaseContainerBlockEntity {

    /** true = items 尚未物化,內容在 {@link #lazycontainer$raw}。預設 false ⟹ 非目標容器行為完全不變。 */
    public boolean lazycontainer$pending;

    /** 載入時暫存的未解碼原始 "Items" ListTag(可能為 null = 原本就沒有 Items)。 */
    public Tag lazycontainer$raw;

    /** 永不被呼叫;僅為通過編譯。splice 時不會嫁接 {@code <init>}。 */
    protected LazyContainerTemplate(BlockEntityType<?> type, BlockPos pos, BlockState st) {
        super(type, pos, st);
    }

    // ── 載入 redirect 目標(取代 leaf loadAdditional/loadFromTag 內的 ContainerHelper.loadAllItems 呼叫)──

    /**
     * 取代 {@code ContainerHelper.loadAllItems(input, items)}。
     * 若 input 是 TagValueInput(chunk 載入恆是),擷取未解碼的 "Items" tag 暫存、標記 pending,
     * <b>跳過昂貴的 decode</b>;否則退回 eager(安全)。
     */
    public void lazycontainer$load(ValueInput input, NonNullList<ItemStack> items) {
        if (input instanceof TagValueInput) {
            this.lazycontainer$raw = ((TagValueInput) input).input.get("Items");
            this.lazycontainer$pending = true;
            LazyContainerRuntime.onStash();
            return;
        }
        ContainerHelper.loadAllItems(input, items);
        this.lazycontainer$pending = false;
        LazyContainerRuntime.onEagerLoad();
    }

    /** 首次被任何存取點觸發時,把暫存的 raw 解碼進真正的 items 清單。set-flag-first 保證 reentrancy 安全。 */
    public void lazycontainer$ensure() {
        if (!this.lazycontainer$pending) {
            return;
        }
        this.lazycontainer$pending = false;     // 先清旗標 → 下面 getItems() 不會再 reenter 本方法
        Tag raw = this.lazycontainer$raw;
        if (raw != null) {
            try {
                CompoundTag tmp = new CompoundTag();
                tmp.put("Items", raw);
                ValueInput vi = TagValueInput.createGlobal(ProblemReporter.DISCARDING, tmp);
                ContainerHelper.loadAllItems(vi, this.getItems());  // 依 slot set,冪等 → 失敗可安全重試
                this.lazycontainer$raw = null;                      // 僅「成功物化後」才作廢 raw
                LazyContainerRuntime.onEnsure();
            } catch (Throwable t) {
                // 物化失敗(理論上不可達,DISCARDING 吞解碼錯):還原 pending、保留 raw,下次存取重試,絕不靜默丟失
                this.lazycontainer$pending = true;
                throw t;
            }
        } else {
            this.lazycontainer$raw = null;
        }
    }

    // ── 存檔 redirect 目標(取代 leaf saveAdditional 內的 ContainerHelper.saveAllItems 呼叫)──

    /** 取代 {@code ContainerHelper.saveAllItems(output, items)}(allowEmpty=true:chest/barrel)。 */
    public void lazycontainer$save(ValueOutput output, NonNullList<ItemStack> items) {
        if (this.lazycontainer$trySaveRaw(output, true)) {
            return;
        }
        ContainerHelper.saveAllItems(output, items);
    }

    /** 取代 {@code ContainerHelper.saveAllItems(output, items, false)}(allowEmpty=false:shulker)。 */
    public void lazycontainer$saveNoEmpty(ValueOutput output, NonNullList<ItemStack> items) {
        if (this.lazycontainer$trySaveRaw(output, false)) {
            return;
        }
        ContainerHelper.saveAllItems(output, items, false);
    }

    /**
     * 若容器自載入後從未物化(pending)且可安全回寫,就把原始 "Items" tag 逐位元組塞回 output,回傳 true
     * (呼叫端跳過 encode)。否則先物化(ensure)再回傳 false(呼叫端走正常 encode)。
     *
     * @param allowEmpty 對應 vanilla saveAllItems 的 allowEmpty(shadow 模式用以算出 byte-identical 的 eager 結果)
     */
    private boolean lazycontainer$trySaveRaw(ValueOutput output, boolean allowEmpty) {
        if (!this.lazycontainer$pending) {
            return false;
        }
        Tag raw = this.lazycontainer$raw;
        // 只在 raw 為「真正的 ListTag」時走快路徑;且 allowEmpty==false(shulker)遇空清單不可寫 raw
        // (vanilla 對空清單會 discard "Items")。非 ListTag / 空-shulker 一律退回 ensure+正常 save,
        // 對所有輸入(含外部/損毀 NBT)逐位元組對齊 vanilla,且不掉物。
        boolean canWriteRaw = (raw instanceof ListTag)
                && !(!allowEmpty && ((ListTag) raw).isEmpty());
        if (canWriteRaw && output instanceof TagValueOutput) {
            CompoundTag out = ((TagValueOutput) output).buildResult();
            if (LazyContainerRuntime.shadow()) {
                Tag eager = this.lazycontainer$eagerItems(raw, allowEmpty);
                if (!Objects.equals(eager, raw)) {
                    if (this.lazycontainer$sameItems(raw, eager)) {
                        // 只是 Items 清單順序不同、物品與槽位完全相同 → 良性:仍偵測回報(標 NO IMPACT)、落到下方寫 raw(安全)
                        LazyContainerRuntime.onBenignReorder(String.valueOf(this.getBlockPos()),
                                String.valueOf(raw), String.valueOf(eager));
                    } else {
                        LazyContainerRuntime.onShadowMismatch();
                        LazyContainerRuntime.dumpMismatch(String.valueOf(this.getBlockPos()),
                                String.valueOf(raw), eager == null ? "<discard>" : String.valueOf(eager));
                        System.err.println("[LazyContainer] SHADOW mismatch @ " + this.getBlockPos()
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
            // 必須寫 raw 的深拷貝:raw 是活體 BE 欄位(lazycontainer$raw),寫回後 pending 不清、raw 保留。
            // 若直接把本體放進存檔 compound,存檔輸出會與活容器別名(vanilla 每次 fresh encode 天生隔離):
            // (a) 該 compound 被 Moonrise 交 IO 執行緒非同步序列化,主線程 /data modify 就地改 raw → 撕裂寫檔;
            // (b) /clone、structure SAVE、CraftBukkit getState() 快照沿 save→load 把同一 ListTag 塞進第二個 BE,
            //     事後對任一箱 /data modify|remove 會就地改到另一箱(掉物/複製)。copy 樹複製仍遠比 codec encode 便宜。
            out.put("Items", raw.copy());
            LazyContainerRuntime.onRawSave();
            return true;
        }
        // raw==null / 非 ListTag / 空清單-shulker / output 非 TagValueOutput:
        // 物化後讓呼叫端走正常 encode(語意逐位元組等同 vanilla)
        this.lazycontainer$ensure();
        return false;
    }

    /** shadow 用:把 raw 完整 parse→encode 一次,回傳 eager 會寫出的 "Items" tag(可能 null = 被 discard)。 */
    private Tag lazycontainer$eagerItems(Tag raw, boolean allowEmpty) {
        CompoundTag reIn = new CompoundTag();
        reIn.put("Items", raw);
        NonNullList<ItemStack> tmp = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(TagValueInput.createGlobal(ProblemReporter.DISCARDING, reIn), tmp);
        TagValueOutput eagerOut = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, MinecraftServer.getServer().registryAccess());
        ContainerHelper.saveAllItems(eagerOut, tmp, allowEmpty);
        return eagerOut.buildResult().get("Items");
    }

    /**
     * raw 與 eager 是否「同一組物品、只是 Items 清單順序不同」。
     * 把兩邊的 Items 當 multiset 比(每個 entry 自帶 Slot,清單順序不影響槽位);
     * 元素用 NBT Tag.equals(CompoundTag 為 map 比對,與 key 順序無關)。
     * 是 → 良性,寫 raw 安全(物品、槽位皆同,玩家驗證不出差異)。
     */
    private boolean lazycontainer$sameItems(Tag rawTag, Tag eagerTag) {
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
