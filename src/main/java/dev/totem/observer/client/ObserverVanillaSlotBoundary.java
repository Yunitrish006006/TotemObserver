package dev.totem.observer.client;
import net.minecraft.world.inventory.*;
/** Legacy native projections carry native slots only, excluding appended module sidecars. */
public final class ObserverVanillaSlotBoundary {
    private ObserverVanillaSlotBoundary() { }
    public static int count(AbstractContainerMenu menu) {
        int nativeCount=menu.slots.size();
        if(menu.getClass()==InventoryMenu.class || menu.getClass()==CraftingMenu.class) nativeCount=46;
        else if(menu.getClass()==EnchantmentMenu.class) nativeCount=38;
        else if(menu.getClass()==ChestMenu.class) nativeCount=((ChestMenu)menu).getRowCount()*9+36;
        else if(menu.getClass()==FurnaceMenu.class || menu.getClass()==SmokerMenu.class || menu.getClass()==BlastFurnaceMenu.class
                || menu.getClass()==AnvilMenu.class || menu.getClass()==GrindstoneMenu.class
                || menu.getClass()==CartographyTableMenu.class || menu.getClass()==MerchantMenu.class) nativeCount=39;
        else if(menu.getClass()==StonecutterMenu.class) nativeCount=38;
        else if(menu.getClass()==SmithingMenu.class || menu.getClass()==LoomMenu.class) nativeCount=40;
        else if(menu.getClass()==BrewingStandMenu.class || menu.getClass()==HopperMenu.class) nativeCount=41;
        return Math.min(nativeCount,menu.slots.size());
    }
}
