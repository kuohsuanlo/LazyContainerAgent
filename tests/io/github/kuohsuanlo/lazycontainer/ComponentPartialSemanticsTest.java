package io.github.kuohsuanlo.lazycontainer;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import org.junit.jupiter.api.Test;

/**
 * 釘住 26.2 + DFU 10.0.21 的 partial 語意(摘要放寬的前提;版本升級若改變此語意,這裡會先紅):
 * 壞 component 逐個被丟、好的留著、物品永遠在;max 只由合法的 max_stack_size 決定,壞的退回物品預設。
 */
class ComponentPartialSemanticsTest {
    private static CompoundTag entry(int slot, CompoundTag comps) {
        CompoundTag e = new CompoundTag();
        e.putByte("Slot", (byte) slot);
        e.putString("id", "minecraft:stone");
        e.putInt("count", 64);
        if (comps != null) e.put("components", comps);
        return e;
    }
    @Test
    void partialSemanticsPinned() {
        NmsTestSupport.bootstrap();
        ListTag items = new ListTag();
        CompoundTag cd = new CompoundTag(); CompoundTag inner = new CompoundTag(); inner.putString("shop", "x"); cd.put("minecraft:custom_data", inner);
        items.add(entry(0, cd));                                            // 0 合法 custom_data
        CompoundTag badLore = new CompoundTag(); badLore.putString("minecraft:lore", "garbage");
        items.add(entry(1, badLore));                                       // 1 lore 型別錯(要 list 給 string)
        CompoundTag unknown = new CompoundTag(); unknown.putInt("minecraft:does_not_exist", 1);
        items.add(entry(2, unknown));                                       // 2 不存在的 component key
        CompoundTag badMax = new CompoundTag(); badMax.putString("minecraft:max_stack_size", "bad");
        items.add(entry(3, badMax));                                        // 3 max_stack_size 非數值
        CompoundTag okMax = new CompoundTag(); okMax.putInt("minecraft:max_stack_size", 16);
        items.add(entry(4, okMax));                                         // 4 max=16 但 count=64(超過上限)
        CompoundTag badEnch = new CompoundTag(); badEnch.putString("minecraft:enchantments", "nope");
        items.add(entry(5, badEnch));                                       // 5 enchantments 型別錯
        CompoundTag container = new CompoundTag(); ListTag cl = new ListTag(); CompoundTag ce = new CompoundTag(); ce.putInt("slot", 0); CompoundTag ci = new CompoundTag(); ci.putString("id","minecraft:dirt"); ci.putInt("count",1); ce.put("item", ci); cl.add(ce); container.put("minecraft:container", cl);
        items.add(entry(6, container));                                     // 6 合法 container(箱中界伏盒形)
        CompoundTag sib = new CompoundTag(); sib.putInt("minecraft:max_stack_size", 16); sib.putString("minecraft:lore", "garbage");
        CompoundTag e7 = entry(7, sib); e7.putInt("count", 16); items.add(e7);              // 7 合法 max=16 + 壞 lore,count=16
        CompoundTag sib2 = new CompoundTag(); sib2.putInt("minecraft:max_stack_size", 16); sib2.putInt("minecraft:does_not_exist", 1);
        CompoundTag e8 = entry(8, sib2); e8.putInt("count", 16); items.add(e8);             // 8 合法 max=16 + 不存在 key
        CompoundTag e9 = entry(9, null); e9.putString("components", "not-a-compound"); items.add(e9);   // 9 components 不是 compound
        CompoundTag oor = new CompoundTag(); oor.putInt("minecraft:max_stack_size", 200); items.add(entry(10, oor));   // 10 max 超範圍
        CompoundTag zero = new CompoundTag(); zero.putInt("minecraft:max_stack_size", 0); items.add(entry(11, zero));  // 11 max=0
        CompoundTag b12 = new CompoundTag(); b12.putInt("max_stack_size", 16); CompoundTag e12 = entry(12, b12); e12.putInt("count", 16); items.add(e12);   // 12 裸命名空間 ⟹ 生效
        CompoundTag b13 = new CompoundTag(); b13.putInt("!minecraft:max_stack_size", 1); CompoundTag e13 = entry(13, b13); e13.putInt("count", 16); items.add(e13);  // 13 移除記號值=Int ⟹ 被丟
        CompoundTag b14 = new CompoundTag(); b14.put("!minecraft:max_stack_size", new CompoundTag()); CompoundTag e14 = entry(14, b14); e14.putInt("count", 16); items.add(e14); // 14 移除記號值={} ⟹ max=1
        CompoundTag b15 = new CompoundTag(); b15.putInt("minecraft:max_stack_size", 16); b15.putInt("max_stack_size", 99); items.add(entry(15, b15));  // 15 重複拼法 ⟹ 迭代序決定(實測 99)
        CompoundTag root = new CompoundTag(); root.put("Items", items);
        NonNullList<ItemStack> list = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(TagValueInput.create(ProblemReporter.DISCARDING, NmsTestSupport.registries(), root), list);
        String[] label = {"custom_data 合法","lore 型別錯","不存在的 key","max_stack_size 非數值","max=16 count=64","enchantments 型別錯","container 合法","max=16+壞lore count=16","max=16+不存在key count=16","components 非 compound","max=200 超範圍","max=0","裸 max_stack_size=16","移除記號值=Int","移除記號值={}","重複拼法 ns16+裸99"};
        int[] expectCount = {64, 64, 64, 64, 64, 64, 64, 16, 16, 64, 64, 64, 16, 16, 16, 64};
        int[] expectMax   = {64, 64, 64, 64, 16, 64, 64, 16, 16, 64, 64, 64, 16, 64,  1, 99};
        for (int i = 0; i < 16; i++) {
            ItemStack s = list.get(i);
            org.junit.jupiter.api.Assertions.assertFalse(s.isEmpty(), "slot " + i + " (" + label[i] + ") 不得空掉——壞 component 只該被丟,物品要留");
            org.junit.jupiter.api.Assertions.assertEquals(expectCount[i], s.getCount(), "slot " + i + " count");
            org.junit.jupiter.api.Assertions.assertEquals(expectMax[i], s.getMaxStackSize(), "slot " + i + " (" + label[i] + ") max");
        }
    }
}
