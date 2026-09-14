package io.github.kuohsuanlo.lazycontainer.mixin;

import io.github.kuohsuanlo.lazycontainer.LazyContainerLogic;
import io.github.kuohsuanlo.lazycontainer.LazyContainerState;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
//? if <1.21.6 {
/*import net.minecraft.core.HolderLookup;
*///?}

/**
 * 對應原版 agent 把 {@code lazycontainer$pending} / {@code lazycontainer$raw} splice 進
 * BaseContainerBlockEntity 的部分:所有繼承容器(chest/barrel/shulker/trapped chest)
 * 都獲得延遲狀態欄位與 ensure 入口。
 *
 * <p>pending 預設 false ⟹ 非目標容器(未被 leaf mixin redirect 的型別)行為完全不變。</p>
 */
@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerBlockEntityMixin implements LazyContainerState {

    /** true = items 尚未物化,內容在 {@link #lazycontainer$raw}。 */
    @Unique
    private boolean lazycontainer$pending;

    /** 載入時暫存的未解碼原始 "Items" ListTag(可能為 null = 原本就沒有 Items)。 */
    @Unique
    private Tag lazycontainer$raw;

    //? if <1.21.6 {
    /*@Unique
    private HolderLookup.Provider lazycontainer$regs;
    *///?}

    @Shadow
    protected abstract NonNullList<ItemStack> getItems();

    @Override
    public boolean lazycontainer$isPending() {
        return this.lazycontainer$pending;
    }

    @Override
    public void lazycontainer$setPending(boolean pending) {
        this.lazycontainer$pending = pending;
    }

    @Override
    public Tag lazycontainer$getRaw() {
        return this.lazycontainer$raw;
    }

    @Override
    public void lazycontainer$setRaw(Tag raw) {
        this.lazycontainer$raw = raw;
    }

    @Override
    public NonNullList<ItemStack> lazycontainer$items() {
        return this.getItems();
    }

    @Override
    public void lazycontainer$ensure() {
        LazyContainerLogic.ensure(this);
    }

    //? if <1.21.6 {
    /*@Override
    public HolderLookup.Provider lazycontainer$getRegs() {
        return this.lazycontainer$regs;
    }

    @Override
    public void lazycontainer$setRegs(HolderLookup.Provider regs) {
        this.lazycontainer$regs = regs;
    }
    *///?}
}
