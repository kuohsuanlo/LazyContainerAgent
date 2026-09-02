package io.github.kuohsuanlo.lazycontainer.mixin;

import io.github.kuohsuanlo.lazycontainer.LazyContainerLogic;
import io.github.kuohsuanlo.lazycontainer.LazyContainerState;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.21.6 {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?} else {
/*import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
*///?}

/**
 * 對應原版 agent 的 leaf transform:
 * (1) 把 load/save 內的 ContainerHelper 呼叫 redirect 成延遲版;
 * (2) getItems() 入口插 ensure-guard、setItems() 入口清旗標。
 *
 * <p>loot table 容器走 tryLoadLootTable/trySaveLootTable,不會呼叫 ContainerHelper
 * → 永不進 lazy 路徑(與原版相同,正交)。</p>
 */
@Mixin(ChestBlockEntity.class)
public abstract class ChestBlockEntityMixin {

    //? if >=1.21.6 {
    @Redirect(method = "loadAdditional", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/ContainerHelper;loadAllItems(Lnet/minecraft/world/level/storage/ValueInput;Lnet/minecraft/core/NonNullList;)V"))
    private void lazycontainer$redirectLoad(ValueInput input, NonNullList<ItemStack> items) {
        LazyContainerLogic.load((BlockEntity) (Object) this, input, items);
    }

    @Redirect(method = "saveAdditional", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/ContainerHelper;saveAllItems(Lnet/minecraft/world/level/storage/ValueOutput;Lnet/minecraft/core/NonNullList;)V"))
    private void lazycontainer$redirectSave(ValueOutput output, NonNullList<ItemStack> items) {
        LazyContainerLogic.save((BlockEntity) (Object) this, output, items, true);
    }
    //?} else {
    /*@Redirect(method = "loadAdditional", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/ContainerHelper;loadAllItems(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/NonNullList;Lnet/minecraft/core/HolderLookup$Provider;)V"))
    private void lazycontainer$redirectLoad(CompoundTag tag, NonNullList<ItemStack> items, HolderLookup.Provider regs) {
        LazyContainerLogic.load((BlockEntity) (Object) this, tag, items, regs);
    }

    @Redirect(method = "saveAdditional", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/ContainerHelper;saveAllItems(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/NonNullList;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag lazycontainer$redirectSave(CompoundTag tag, NonNullList<ItemStack> items, HolderLookup.Provider regs) {
        return LazyContainerLogic.save((BlockEntity) (Object) this, tag, items, true, regs);
    }
    *///?}

    /** 唯一咽喉:所有 vanilla 容器讀寫都經 getItems() → 首次存取時物化。 */
    @Inject(method = "getItems", at = @At("HEAD"))
    private void lazycontainer$ensureOnGetItems(CallbackInfoReturnable<NonNullList<ItemStack>> cir) {
        LazyContainerState st = (LazyContainerState) (Object) this;
        if (st.lazycontainer$isPending()) {
            st.lazycontainer$ensure();
        }
    }

    /** 整批替換 items → 清 lazy 態(raw 作廢)。 */
    @Inject(method = "setItems", at = @At("HEAD"))
    private void lazycontainer$clearOnSetItems(NonNullList<ItemStack> items, CallbackInfo ci) {
        LazyContainerState st = (LazyContainerState) (Object) this;
        st.lazycontainer$setPending(false);
        st.lazycontainer$setRaw(null);
    }
}
