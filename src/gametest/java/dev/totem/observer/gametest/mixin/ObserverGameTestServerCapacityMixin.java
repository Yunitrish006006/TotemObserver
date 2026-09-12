package dev.totem.observer.gametest.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives the isolated server GameTest process enough player capacity for tests that intentionally
 * create several synthetic/Observer players concurrently. Production servers never load this mixin.
 */
@Mixin(GameTestServer.class)
abstract class ObserverGameTestServerCapacityMixin {
    private static final int OBSERVER_GAMETEST_MAX_PLAYERS = 32;

    @Inject(method = "getMaxPlayers", at = @At("HEAD"), cancellable = true)
    private void observer$raiseGameTestPlayerCapacity(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(OBSERVER_GAMETEST_MAX_PLAYERS);
    }
}
