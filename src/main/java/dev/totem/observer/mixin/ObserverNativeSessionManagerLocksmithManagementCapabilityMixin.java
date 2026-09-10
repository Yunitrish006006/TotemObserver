package dev.totem.observer.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.totem.observer.network.ObserverLocksmithManagementPayloads;
import dev.totem.observer.network.ObserverNativeScreenPayloads;
import dev.totem.observer.network.ObserverOwnedScreenPayloads;
import dev.totem.observer.network.ObserverOwnedScreenProtocols;
import dev.totem.observer.runtime.ObserverLocksmithManagementRelayManager;
import dev.totem.observer.runtime.ObserverNativeSessionManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Adds TotemLocksmith management semantics to Observer negotiation and cleanup. */
@Mixin(value = ObserverNativeSessionManager.class, remap = false)
public abstract class ObserverNativeSessionManagerLocksmithManagementCapabilityMixin {
    @ModifyReturnValue(method = "negotiatedScreenCapabilities", at = @At("RETURN"))
    private static long totem$includeLocksmithManagement(long original, ServerPlayer observer) {
        if (!ServerPlayNetworking.canSend(observer, ObserverOwnedScreenPayloads.Relay.TYPE)
                || !ObserverNativeSessionManager.ownedProviderAdvertises(observer,
                ObserverLocksmithManagementPayloads.FAMILY_ID,
                ObserverOwnedScreenProtocols.expected(ObserverLocksmithManagementPayloads.FAMILY_ID))) return original;
        return ObserverNativeScreenPayloads.sanitizeCapabilities(original | ObserverLocksmithManagementPayloads.CAPABILITY);
    }

    @Inject(method = "clearTargetSequences", at = @At("TAIL"))
    private static void totem$clearLocksmithManagementSequence(UUID targetId, CallbackInfo ci) {
        ObserverLocksmithManagementRelayManager.clearTarget(targetId);
    }
}
