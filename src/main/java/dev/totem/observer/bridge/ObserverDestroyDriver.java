package dev.totem.observer.bridge;

import dev.totem.observer.mixin.ObserverDestroyStateAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Internal only. Owner must validate/tick before vanilla player ticks and cancel on lifecycle loss. */
final class ObserverDestroyDriver {
    enum Outcome { IDLE, ACTIVE, CHANGED, DENIED, CANCELLED }
    record Result(Outcome outcome, BlockPos target, float progress, boolean worldMayHaveChanged) {}
    private static final int MAX_TICKS = 12_000;
    private final ServerPlayer player;
    private final ServerLevel level;
    private final BooleanSupplier authorized;
    private final ObserverDestroyStateAccessor vanilla;
    private final ServerPlayerGameMode gameMode;
    private BlockPos target;
    private Direction face;
    private BlockState initialState;
    private ItemStack initialItem;
    private int initialSlot, start, lastTick = Integer.MIN_VALUE;

    ObserverDestroyDriver(ServerPlayer player, BooleanSupplier authorized) {
        this.player = Objects.requireNonNull(player);
        this.level = player.level();
        this.authorized = Objects.requireNonNull(authorized);
        gameMode = player.gameMode;
        vanilla = (ObserverDestroyStateAccessor) gameMode;
    }

    Result start() {
        thread();
        cancel();
        if (!allowed()) return result(Outcome.DENIED, null, 0, false);
        var hit = ObserverBlockTargeting.pick(player, authorized);
        if (hit == null || !permission(hit.getBlockPos())) return result(Outcome.DENIED, null, 0, false);
        var pos = hit.getBlockPos();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return result(Outcome.DENIED, null, 0, false);
        target = pos.immutable(); face = hit.getDirection(); initialState = chunk.getBlockState(pos);
        initialSlot = player.getInventory().getSelectedSlot(); initialItem = player.getMainHandItem().copy();
        try {
            float speed = speed();
            if ((!player.isCreative() && speed <= 0) || !allowed()
                    || vanilla.observer$isDestroying() || vanilla.observer$hasDelayedDestroy()) {
                cancel(); return result(Outcome.DENIED, pos, 0, false);
            }
            // START can attack or instantly destroy a block. Report possible mutation even before STOP.
            action(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
            if (chunk.getBlockState(pos) != initialState) {
                cancel(); return result(Outcome.CHANGED, pos, 1, true);
            }
            if (!vanilla.observer$isDestroying() || vanilla.observer$hasDelayedDestroy()
                    || !target.equals(vanilla.observer$destroyPos())) {
                cancel(); return result(Outcome.DENIED, pos, 0, true);
            }
            start = vanilla.observer$destroyStart();
            lastTick = level.getServer().getTickCount();
            return result(Outcome.ACTIVE, target, Math.min(speed, 1), true);
        } catch (Throwable failure) { cancel(); throw failure; }
    }

    /** heldAuthorized includes the owner's bounded input heartbeat/deadman check. */
    Result tick(BooleanSupplier heldAuthorized) {
        thread();
        if (target == null) return result(Outcome.IDLE, null, 0, false);
        final BlockPos pos = target;
        try {
            if (!heldAuthorized.getAsBoolean() || !allowed() || !permission(pos)
                    || initialSlot != player.getInventory().getSelectedSlot()
                    || !ItemStack.matches(initialItem, player.getMainHandItem())) return cancel();
            var hit = ObserverBlockTargeting.pick(player, authorized);
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (hit == null || !pos.equals(hit.getBlockPos()) || chunk == null
                    || chunk.getBlockState(pos) != initialState || !vanilla.observer$isDestroying()
                    || vanilla.observer$hasDelayedDestroy() || !pos.equals(vanilla.observer$destroyPos())
                    || start != vanilla.observer$destroyStart()) return cancel();
            long elapsed = (long) vanilla.observer$gameTicks() - start;
            if (elapsed < 0 || elapsed > MAX_TICKS) return cancel();
            float progress = speed() * (elapsed + 1);
            if (!Float.isFinite(progress) || progress <= 0) return cancel();
            int now = level.getServer().getTickCount();
            if (lastTick == now) return result(Outcome.ACTIVE, pos, Math.min(progress, 1), false);
            lastTick = now;
            if (progress < 1) return result(Outcome.ACTIVE, pos, progress, false);
            // Never use STOP as a release, or enter vanilla's early-completion delayed branch.
            if (!authorized.getAsBoolean() || !heldAuthorized.getAsBoolean()) return cancel();
            action(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
            boolean changed = chunk.getBlockState(pos) != initialState;
            cancel();
            return result(changed ? Outcome.CHANGED : Outcome.DENIED, pos, changed ? 1 : 0, true);
        } catch (Throwable failure) { cancel(); throw failure; }
    }

    Result cancel() {
        thread();
        var pos = target;
        if (pos != null) {
            // Vanilla ABORT is reach-gated and leaves delayed state set; cleanup must not be.
            vanilla.observer$setDestroying(false);
            vanilla.observer$setDelayedDestroy(false);
            vanilla.observer$setLastSentState(-1);
            level.destroyBlockProgress(player.getId(), pos, -1);
        }
        target = null; initialState = null; initialItem = null; face = null;
        return result(pos == null ? Outcome.IDLE : Outcome.CANCELLED, pos, 0, false);
    }

    private boolean allowed() {
        return authorized.getAsBoolean() && player.level() == level && player.gameMode == gameMode && !player.isRemoved()
                && level.getServer().getPlayerList().getPlayer(player.getUUID()) == player
                && player.connection != null && player.connection.hasClientLoaded() && player.isAlive()
                && !player.isSpectator() && !player.isSleeping() && !player.isPassenger()
                && !player.isChangingDimension() && player.containerMenu == player.inventoryMenu;
    }

    private boolean permission(BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos)
                && !level.getServer().isUnderSpawnProtection(level, pos, player)
                && level.mayInteract(player, pos) && player.mayInteract(level, pos)
                && !player.blockActionRestricted(level, pos, player.gameMode.getGameModeForPlayer());
    }

    private float speed() {
        var view = new ObserverBlockTargeting.LoadedView(level, target);
        float value = initialState.getDestroyProgress(player, view, target);
        return view.failed() || !Float.isFinite(value) || value < 0 ? 0 : value;
    }

    private void action(ServerboundPlayerActionPacket.Action action) {
        gameMode.handleBlockBreakAction(target, action, face, level.getMaxY(), 0);
    }
    private void thread() {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Mining requires server thread");
    }
    private static Result result(Outcome outcome, BlockPos pos, float progress, boolean changed) {
        return new Result(outcome, pos, progress, changed);
    }
}
