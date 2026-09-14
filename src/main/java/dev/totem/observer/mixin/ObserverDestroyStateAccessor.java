package dev.totem.observer.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Observer reads vanilla progress and can unconditionally cancel its admitted player's operation. */
@Mixin(ServerPlayerGameMode.class)
public interface ObserverDestroyStateAccessor {
    @Accessor("isDestroyingBlock") boolean observer$isDestroying();
    @Accessor("isDestroyingBlock") void observer$setDestroying(boolean value);
    @Accessor("hasDelayedDestroy") boolean observer$hasDelayedDestroy();
    @Accessor("hasDelayedDestroy") void observer$setDelayedDestroy(boolean value);
    @Accessor("destroyPos") BlockPos observer$destroyPos();
    @Accessor("destroyProgressStart") int observer$destroyStart();
    @Accessor("gameTicks") int observer$gameTicks();
    @Accessor("lastSentState") void observer$setLastSentState(int value);
}
