package dev.totem.observer.mixin.client;

import dev.totem.observer.client.ObserverNativeClient;
import dev.totem.observer.client.ObserverNativeScreenClient;
import dev.totem.observer.network.ObserverNativeScreenPayloads;
import dev.totem.observer.network.ObserverSmithingScreenPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives Smithing semantics priority over generic container/metadata adapters. */
@Mixin(value = ObserverNativeScreenClient.class, remap = false)
public abstract class ObserverNativeScreenClientSmithingPriorityMixin {
    @Shadow private static void closeTargetContainer(boolean canSend) { throw new AssertionError(); }
    @Shadow private static void closeTargetFurnace(boolean canSend) { throw new AssertionError(); }

    @Inject(method = "tickTarget", at = @At("HEAD"), cancellable = true)
    private static void totem$preferSmithingFamily(Minecraft minecraft, CallbackInfo ci) {
        if (!supportsSmithing(minecraft.gui.screen())) return;
        closeTargetFurnace(ObserverNativeClient.targetSupportsScreen(ObserverNativeScreenPayloads.CAPABILITY_FURNACE));
        closeTargetContainer(ObserverNativeClient.targetSupportsScreen(ObserverNativeScreenPayloads.CAPABILITY_CONTAINER_SLOTS));
        ci.cancel();
    }

    private static boolean supportsSmithing(Screen screen) {
        return ObserverNativeClient.targetSupportsScreen(ObserverSmithingScreenPayloads.CAPABILITY)
                && screen != null && ObserverSmithingScreenPayloads.SCREEN_CLASS.equals(screen.getClass().getName());
    }
}
