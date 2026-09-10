package dev.totem.observer.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.totem.observer.network.ObserverNativeScreenPayloads;
import dev.totem.observer.network.ObserverStatsScreenPayloads;
import dev.totem.observer.runtime.ObserverNativeSessionManager;
import dev.totem.observer.runtime.ObserverStatsRelayManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Adds vanilla Statistics semantics to Observer negotiation and cleanup. */
@Mixin(value = ObserverNativeSessionManager.class, remap = false)
public abstract class ObserverNativeSessionManagerStatsCapabilityMixin {
    @ModifyReturnValue(method = "negotiatedScreenCapabilities", at = @At("RETURN"))
    private static long totem$includeStats(long original, ServerPlayer observer) {
        if (!ServerPlayNetworking.canSend(observer, ObserverStatsScreenPayloads.StatsRelay.TYPE)) return original;
        return ObserverNativeScreenPayloads.sanitizeCapabilities(original | ObserverStatsScreenPayloads.CAPABILITY);
    }

    @Inject(method = "clearTargetSequences", at = @At("TAIL"))
    private static void totem$clearStatsSequence(UUID targetId, CallbackInfo ci) {
        ObserverStatsRelayManager.clearTarget(targetId);
    }
}
