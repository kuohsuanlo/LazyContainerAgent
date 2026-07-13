package io.github.kuohsuanlo.lazycontainer.mixin;

// 1.21.6+ 專用:直接拿 TagValueInput 底層的 CompoundTag,擷取未解碼 "Items"(近乎零成本)。
// 1.21.1~1.21.5 走 CompoundTag 路徑,不需要本 accessor(mixin json 亦不會註冊它)。
//? if >=1.21.6 {
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.TagValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TagValueInput.class)
public interface TagValueInputAccessor {

    @Accessor("input")
    CompoundTag lazycontainer$input();
}
//?}
