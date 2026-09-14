package io.github.kuohsuanlo.lazycontainer;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
//? if <1.21.6 {
/*import net.minecraft.core.HolderLookup;
*///?}

/**
 * Duck interface —— 由 {@code BaseContainerBlockEntityMixin} 實作,對應原版 splice 進
 * BaseContainerBlockEntity 的 {@code lazycontainer$pending} / {@code lazycontainer$raw} 欄位。
 *
 * <h3>不變式(資料安全鐵律,與原版相同)</h3>
 * <ul>
 *   <li>{@code pending == true} ⟺ items 清單「尚未物化」,真正內容以未解碼的原始 "Items" Tag 暫存於 raw。</li>
 *   <li>任何存取點呼叫 getItems() 時,guard 先呼叫 ensure() 把 raw 解碼進清單,並將 pending=false、raw=null。</li>
 *   <li>存檔時:pending 且 raw 可安全回寫 ⟹ 逐位元組寫回(跳過 encode);否則先物化再正常 encode。
 *       最壞只是少省一次,絕不掉資料。</li>
 * </ul>
 */
public interface LazyContainerState {

    boolean lazycontainer$isPending();

    void lazycontainer$setPending(boolean pending);

    Tag lazycontainer$getRaw();

    void lazycontainer$setRaw(Tag raw);

    /** 取得容器 items 清單(轉呼叫 protected 的 getItems(),經 leaf guard)。 */
    NonNullList<ItemStack> lazycontainer$items();

    /** 首次被任何存取點觸發時,把暫存的 raw 解碼進真正的 items 清單。 */
    void lazycontainer$ensure();

    // 1.21.1~1.21.5 專用:loadAdditional 傳入的 registry provider,ensure/shadow 重解碼時需要。
    //? if <1.21.6 {
    /*HolderLookup.Provider lazycontainer$getRegs();

    void lazycontainer$setRegs(HolderLookup.Provider regs);
    *///?}
}
