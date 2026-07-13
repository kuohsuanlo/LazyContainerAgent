package io.github.kuohsuanlo.lazycontainer.mixin;

import io.github.kuohsuanlo.lazycontainer.LazyContainerLogic;
import io.github.kuohsuanlo.lazycontainer.LazyContainerState;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
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
 * Shulker 與 chest/barrel 的差異(對齊原版 FINDINGS):
 * <ul>
 *   <li>load 在 {@code loadFromTag}(loadAdditional 會委派進來,redirect 一處即涵蓋兩者);</li>
 *   <li>save 走 {@code saveAllItems(..., false)}(allowEmpty=false:空清單時 vanilla 會 discard "Items",
 *       故空清單不可寫 raw,由 Logic 內 canWriteRaw 檢查把關)。</li>
 * </ul>
 */
@Mixin(ShulkerBoxBlockEntity.class)
public abstract class ShulkerBoxBlockEntityMixin {

    //? if >=1.21.6 {
    @Redirect(method = "loadFromTag", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/ContainerHelper;loadAllItems(Lnet/minecraft/world/level/storage/ValueInput;Lnet/minecraft/core/NonNullList;)V"))
    private void lazycontainer$redirectLoad(ValueInput input, NonNullList<ItemStack> items) {
        LazyContainerLogic.load((BlockEntity) (Object) this, input, items);
    }

    @Redirect(method = "saveAdditional", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/ContainerHelper;saveAllItems(Lnet/minecraft/world/level/storage/ValueOutput;Lnet/minecraft/core/NonNullList;Z)V"))
    private void lazycontainer$redirectSave(ValueOutput output, NonNullList<ItemStack> items, boolean allowEmpty) {
        LazyContainerLogic.save((BlockEntity) (Object) this, output, items, allowEmpty);
    }
    //?} else {
    /*@Redirect(method = "loadFromTag", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/ContainerHelper;loadAllItems(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/NonNullList;Lnet/minecraft/core/HolderLookup$Provider;)V"))
    private void lazycontainer$redirectLoad(CompoundTag tag, NonNullList<ItemStack> items, HolderLookup.Provider regs) {
        LazyContainerLogic.load((BlockEntity) (Object) this, tag, items, regs);
    }

    @Redirect(method = "saveAdditional", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/ContainerHelper;saveAllItems(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/NonNullList;ZLnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag lazycontainer$redirectSave(CompoundTag tag, NonNullList<ItemStack> items,
                                                   boolean allowEmpty, HolderLookup.Provider regs) {
        return LazyContainerLogic.save((BlockEntity) (Object) this, tag, items, allowEmpty, regs);
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
