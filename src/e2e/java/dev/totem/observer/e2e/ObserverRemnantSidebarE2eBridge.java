package dev.totem.observer.e2e;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import dev.totem.core.api.v1.client.observer.ObserverScreenProviders;
import dev.totem.observer.client.*;
import dev.totem.remnant.client.screen.*;
import dev.totem.remnant.inventory.BackpackPanelMenuAccess;
import dev.totem.remnant.registry.RemnantItemRegistration;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.input.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import java.nio.file.Files;
import java.util.*;

/** Real target Screens drive the production owner capture/relay, rather than synthetic state packets. */
public final class ObserverRemnantSidebarE2eBridge implements ClientModInitializer {
    private static final String[] VARIANTS={"inventory","crafting","enchanting","chest","furnace","smoker","blast_furnace","anvil","stonecutter","smithing","grindstone","loom","cartography","brewing","hopper","merchant"};
    static int caseCount() { return VARIANTS.length * 2; }
    private static String role;
    private static Boolean available;
    private static int phase;
    private static AbstractContainerScreen<?> source;
    private static boolean updated,closeSent;
    private static volatile boolean saved;
    private static boolean screenshotRequested;
    public static boolean enabled() { if(available==null)available=ObserverScreenProviders.discover().containsKey("remnant_sidebar");return available; }
    @Override public void onInitializeClient() {
        if(!Boolean.getBoolean("totem.observer.e2e.enabled"))return;
        role=System.getProperty("totem.observer.e2e.role","");
        ClientTickEvents.END_CLIENT_TICK.register(ObserverRemnantSidebarE2eBridge::tick);
    }
    private static String key() { return VARIANTS[phase/2]+(phase%2==1?"-death":"-ordinary"); }
    private static boolean marker(String name){return Files.exists(ObserverE2eCommon.resultsDir().resolve(name));}
    private static void mark(String name,String text){ObserverE2eCommon.marker(name,text+"\n");}
    private static void tick(Minecraft client) {
        if(phase>=VARIANTS.length*2||!marker("observer-native-remnant-death-closed.txt")||!enabled()||client.player==null||client.level==null)return;
        try {if(role.equals("target"))target(client);else if(role.equals("observer"))observer(client);}
        catch(Throwable error){mark("failure-remnant-sidebar.txt",error.toString());throw new AssertionError("Sidebar E2E "+key(),error);}
    }
    private static void target(Minecraft client) throws Exception {
        if(source==null) {
            var target=new RemotePlayer(client.level,new GameProfile(client.player.getUUID(),client.player.getName().getString()));
            target.setId(-271828);target.experienceLevel=client.player.experienceLevel;var inv=target.getInventory();
            var bag=new ItemStack(phase%2==1?RemnantItemRegistration.DEATH_BACKPACK:RemnantItemRegistration.BACKPACK_BASIC);
            setContents(bag,7);inv.setItem(40,bag);
            source=switch(VARIANTS[phase/2]) {
                case "inventory"->new InventoryScreen(target);
                case "crafting"->new CraftingScreen(new CraftingMenu(99,inv),inv,Component.literal("Sidebar Crafting"));
                case "enchanting"->new EnchantmentScreen(new EnchantmentMenu(99,inv),inv,Component.literal("Sidebar Enchanting"));
                case "furnace"->new FurnaceScreen(new FurnaceMenu(99,inv),inv,Component.literal("Sidebar furnace"));
                case "smoker"->new SmokerScreen(new SmokerMenu(99,inv),inv,Component.literal("Sidebar smoker"));
                case "blast_furnace"->new BlastFurnaceScreen(new BlastFurnaceMenu(99,inv),inv,Component.literal("Sidebar blast_furnace"));
                case "anvil"->new AnvilScreen(new AnvilMenu(99,inv),inv,Component.literal("Sidebar anvil"));
                case "stonecutter"->new StonecutterScreen(new StonecutterMenu(99,inv),inv,Component.literal("Sidebar stonecutter"));
                case "smithing"->new SmithingScreen(new SmithingMenu(99,inv),inv,Component.literal("Sidebar smithing"));
                case "grindstone"->new GrindstoneScreen(new GrindstoneMenu(99,inv),inv,Component.literal("Sidebar grindstone"));
                case "loom"->new LoomScreen(new LoomMenu(99,inv),inv,Component.literal("Sidebar loom"));
                case "cartography"->new CartographyTableScreen(new CartographyTableMenu(99,inv),inv,Component.literal("Sidebar cartography"));
                case "brewing"->new BrewingStandScreen(new BrewingStandMenu(99,inv),inv,Component.literal("Sidebar brewing"));
                case "hopper"->new HopperScreen(new HopperMenu(99,inv),inv,Component.literal("Sidebar hopper"));
                case "merchant"->new MerchantScreen(new MerchantMenu(99,inv),inv,Component.literal("Sidebar merchant"));
                default->new ContainerScreen(ChestMenu.threeRows(99,inv),inv,Component.literal("Sidebar Chest"));
            };
            ((BackpackPanelMenuAccess)source.getMenu()).totem$selectBackpackSlot(40);
            ((BackpackSidebarLayout)source).remnant$firstRow(phase%2==1?255:0);
            var carried=new ItemStack(Items.GOLD_INGOT,5);carried.set(DataComponents.CUSTOM_NAME,Component.literal("Sidebar Cursor"));
            source.getMenu().setCarried(carried);client.setScreenAndShow(source);
            mark("target-sidebar-"+key()+"-opened.txt","Real native target screen opened");
            return;
        }
        if(!closeSent) {
            // Initial local selection resets its viewport; apply deep scrolling after that render.
            if(phase%2==1)((BackpackSidebarLayout)source).remnant$firstRow(255);
            require(!flag(ObserverNativeCraftingScreenClient.class,"targetOpen")
                    &&!flag(ObserverNativeEnchantingScreenClient.class,"targetOpen")
                    &&!flag(ObserverNativeScreenClient.class,"targetContainerOpen"),"Legacy sender competed with owner capture");
        }
        if(!updated&&marker("observer-sidebar-"+key()+"-initial.txt")) {
            var panel=((BackpackPanelMenuAccess)source.getMenu()).totem$getBackpackPanel();setContents(panel.selectedBackpack(),13);
            panel.refreshAfterExternalCommit();updated=true;
        }
        if(!closeSent&&marker("observer-sidebar-"+key()+"-saved.txt")) {
            client.setScreenAndShow(null);closeSent=true;
        }
        if(closeSent&&marker("observer-sidebar-"+key()+"-closed.txt")) {
            phase++;source=null;updated=false;closeSent=false;
        }
    }
    private static void setContents(ItemStack bag,int count) {
        var items=new ArrayList<ItemStack>();for(int i=0;i<(phase%2==1?256:9);i++)items.add(ItemStack.EMPTY);
        items.set(phase%2==1?255:0,new ItemStack(Items.DIAMOND,count));bag.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(items));
    }
    private static void observer(Minecraft client) throws Exception {
        String variant=VARIANTS[phase/2];
        if(client.gui.screen() instanceof SidebarProjection && client.gui.screen() instanceof AbstractContainerScreen<?> screen
                &&ObserverOwnedScreenCoordinator.isActive("remnant_sidebar",variant,1)
                &&ObserverOwnedScreenCoordinator.hasRenderedActiveSnapshot()&&ObserverOwnedScreenCoordinator.hasRemoteCursor()) {
            var panel=((BackpackPanelMenuAccess)screen.getMenu()).totem$getBackpackPanel();
            int index=phase%2==1?255:0;
            if(panel.getItem(index).getCount()==7)mark("observer-sidebar-"+key()+"-initial.txt","Real target initial contents received");
            if(panel.getItem(index).getCount()!=13)return;
            require(!flag(ObserverNativeCraftingScreenClient.class,"remoteOpen")
                    &&!flag(ObserverNativeEnchantingScreenClient.class,"remoteOpen")
                    &&!flag(ObserverNativeScreenClient.class,"remoteContainerOpen"),"Legacy receiver competed with owner projection");
            require(screen.getMenu().getCarried().getCount()==5,"Remote carried stack missing");
            var slot=screen.getMenu().getSlot(((BackpackPanelMenuAccess)screen.getMenu()).totem$getBackpackPanelSlotStart()+index);
            require(slot.x>-1000&&slot.y>-1000,"Deep slot viewport missing");
            screen.mouseClicked(new MouseButtonEvent(1,1,new MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT,0)),false);
            screen.mouseScrolled(1,1,0,-1);screen.keyPressed(new KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_A,0,0));
            require(panel.getItem(index).getCount()==13,"Readonly input changed contents");
            if(!screenshotRequested) {
                screenshotRequested=true;String name=key();
                Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
                    if(image==null){mark("failure-remnant-sidebar.txt","Screenshot missing");return;}
                    try(NativeImage owned=image){owned.writeToFile(ObserverE2eCommon.resultsDir().resolve("observer-sidebar-"+name+".png"));
                        saved=true;mark("observer-sidebar-"+name+"-saved.txt","Updated native sidebar rendered");}
                    catch(Exception error){mark("failure-remnant-sidebar.txt",error.toString());}
                });
            }
        }
        if(saved&&!ObserverOwnedScreenCoordinator.isActive("remnant_sidebar",variant,1)&&client.gui.screen()==null) {
            mark("observer-sidebar-"+key()+"-closed.txt","Target close ended native owner view");phase++;saved=false;screenshotRequested=false;
            if(phase==VARIANTS.length*2) {
                mark("observer-native-remnant-backpack-closed.txt","Backpack variants and all32 real-capture sidebar variants passed");
                var field=ObserverE2eClient.class.getDeclaredField("observerStopRequested");field.setAccessible(true);field.setBoolean(null,false);
            }
        }
    }
    private static boolean flag(Class<?> owner,String name)throws Exception {var f=owner.getDeclaredField(name);f.setAccessible(true);return f.getBoolean(null);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
