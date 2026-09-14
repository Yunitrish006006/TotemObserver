package dev.totem.observer.mixin;

import dev.totem.observer.bridge.ObserverDestroyOperation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ObserverDestroyTickMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;tick()V"), cancellable = true)
    private void observer$validateMiningBeforeVanilla(CallbackInfo ci) {
        if (!ObserverDestroyOperation.beforeVanillaTick((ServerPlayer) (Object) this)) ci.cancel();
    }
}
