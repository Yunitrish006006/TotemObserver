package dev.totem.observer.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.totem.observer.network.ObserverNativeScreenPayloads;
import dev.totem.observer.network.ObserverNexusScreenPayloads;
import dev.totem.observer.network.ObserverOwnedScreenPayloads;
import dev.totem.observer.network.ObserverOwnedScreenProtocols;
import dev.totem.observer.runtime.ObserverNativeSessionManager;
import dev.totem.observer.runtime.ObserverNexusRelayManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Adds optional TotemNexus support to standard Observer capability negotiation and cleanup. */
@Mixin(value = ObserverNativeSessionManager.class, remap = false)
public abstract class ObserverNativeSessionManagerNexusCapabilityMixin {
    @ModifyReturnValue(method = "negotiatedScreenCapabilities", at = @At("RETURN"))
    private static long totem$includeNexusCapability(long original, ServerPlayer observer) {
        if (!ServerPlayNetworking.canSend(observer, ObserverOwnedScreenPayloads.Relay.TYPE)
                || !totem$advertisesCompatibleNexusProvider(observer)) {
            return original;
        }
        return ObserverNativeScreenPayloads.sanitizeCapabilities(
                original | ObserverNativeScreenPayloads.CAPABILITY_NEXUS);
    }

    private static boolean totem$advertisesCompatibleNexusProvider(ServerPlayer observer) {
        return ObserverOwnedScreenProtocols.supported(ObserverNativeScreenPayloads.FAMILY_NEXUS).stream()
                .anyMatch(protocol -> ObserverNativeSessionManager.ownedProviderAdvertises(
                        observer, ObserverNativeScreenPayloads.FAMILY_NEXUS, protocol));
    }

    @Inject(method = "clearTargetSequences", at = @At("TAIL"))
    private static void totem$clearNexusSequence(UUID targetId, CallbackInfo ci) {
        ObserverNexusRelayManager.clearTarget(targetId);
    }
}
