package dev.totem.observer.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;

import java.util.function.BooleanSupplier;

/** Narrow first block-use capability. Session/request scheduling remains admission-owned. */
final class ObserverBlockUse {
    enum Outcome { APPLIED, NO_TARGET, UNSUPPORTED, DENIED }
    record Result(Outcome outcome, BlockPos target) {}

    private ObserverBlockUse() {}

    static Result use(ServerPlayer player, BooleanSupplier authorized) {
        var level = player.level();
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Block use requires server thread");
        if (!authorized.getAsBoolean() || player.connection == null || !player.connection.hasClientLoaded()
                || player.containerMenu != player.inventoryMenu
                || !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) {
            return new Result(Outcome.DENIED, null);
        }
        var hit = ObserverBlockTargeting.pick(player, authorized);
        if (hit == null) return new Result(Outcome.NO_TARGET, null);
        var pos = hit.getBlockPos();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return new Result(Outcome.DENIED, null);
        var block = chunk.getBlockState(pos).getBlock();
        // Exact server block identities define this capability's scope. They
        // do not imply cube geometry and are unrelated to client render hints.
        if (block != Blocks.LEVER && block != Blocks.STONE_BUTTON) return new Result(Outcome.UNSUPPORTED, pos);
        if (!authorized.getAsBoolean()) return new Result(Outcome.DENIED, null);
        var result = player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        return new Result(result.consumesAction() ? Outcome.APPLIED : Outcome.DENIED, pos);
    }
}
