package dev.totem.observer.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.totem.observer.network.ObserverAutomataCopperGolemPayloads;
import dev.totem.observer.network.ObserverNativeScreenPayloads;
import dev.totem.observer.network.ObserverOwnedScreenPayloads;
import dev.totem.observer.network.ObserverOwnedScreenProtocols;
import dev.totem.observer.runtime.ObserverAutomataCopperGolemRelayManager;
import dev.totem.observer.runtime.ObserverNativeSessionManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Adds optional TotemAutomata support to standard Observer negotiation and cleanup. */
@Mixin(value = ObserverNativeSessionManager.class, remap = false)
public abstract class ObserverNativeSessionManagerAutomataCapabilityMixin {
    @ModifyReturnValue(method = "negotiatedScreenCapabilities", at = @At("RETURN"))
    private static long totem$includeAutomataCapability(long original, ServerPlayer observer) {
        if (!ServerPlayNetworking.canSend(observer, ObserverOwnedScreenPayloads.Relay.TYPE)
                || !ObserverNativeSessionManager.ownedProviderAdvertises(observer,
                ObserverNativeScreenPayloads.FAMILY_AUTOMATA_COPPER_GOLEM,
                ObserverOwnedScreenProtocols.expected(ObserverNativeScreenPayloads.FAMILY_AUTOMATA_COPPER_GOLEM))) {
            return original;
        }
        return ObserverNativeScreenPayloads.sanitizeCapabilities(
                original | ObserverNativeScreenPayloads.CAPABILITY_AUTOMATA_COPPER_GOLEM);
    }

    @Inject(method = "clearTargetSequences", at = @At("TAIL"))
    private static void totem$clearAutomataSequence(UUID targetId, CallbackInfo ci) {
        ObserverAutomataCopperGolemRelayManager.clearTarget(targetId);
    }
}
