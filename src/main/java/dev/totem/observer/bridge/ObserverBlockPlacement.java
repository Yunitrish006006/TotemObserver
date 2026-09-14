package dev.totem.observer.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;
import java.util.function.BooleanSupplier;

/** Internal placement driver; no new transport capability is enabled by this slice. */
final class ObserverBlockPlacement {
    enum Outcome { APPLIED, NO_TARGET, UNSUPPORTED, DENIED }
    record Result(Outcome outcome, BlockPos destination, boolean worldMayHaveChanged) {}

    // Capability scope, never renderer geometry or client-name classification.
    private static final Set<Block> SUPPORTS = Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK,
            Blocks.STONE, Blocks.COBBLESTONE, Blocks.BEDROCK, Blocks.OAK_PLANKS);

    private ObserverBlockPlacement() {}

    static Result place(ServerPlayer player, BooleanSupplier authorized) {
        var level = player.level();
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Placement requires server thread");
        if (!authorized.getAsBoolean() || player.connection == null || !player.connection.hasClientLoaded()
                || player.containerMenu != player.inventoryMenu || !player.getOffhandItem().isEmpty()) return denied();
        var stack = player.getMainHandItem();
        if (stack.isEmpty() || !(stack.is(Items.DIRT) || stack.is(Items.STONE)
                || stack.is(Items.COBBLESTONE) || stack.is(Items.OAK_PLANKS))) {
            return new Result(Outcome.UNSUPPORTED, null, false);
        }
        if (stack.getItem().getClass() != BlockItem.class || player.getCooldowns().isOnCooldown(stack)) return denied();
        var beforeStack = stack.copy();
        int slot = player.getInventory().getSelectedSlot();
        var gameMode = player.gameMode;
        var mode = gameMode.getGameModeForPlayer();
        var hit = ObserverBlockTargeting.pick(player, authorized);
        if (hit == null) return new Result(Outcome.NO_TARGET, null, false);
        var support = hit.getBlockPos();
        var destination = support.relative(hit.getDirection());
        if (!allowedPosition(level, player, support) || !allowedPosition(level, player, destination)) return denied();
        var beforeSupport = level.getChunkSource().getChunkNow(support.getX() >> 4, support.getZ() >> 4).getBlockState(support);
        if (!SUPPORTS.contains(beforeSupport.getBlock())) return new Result(Outcome.UNSUPPORTED, null, false);
        var beforeDestination = level.getChunkSource().getChunkNow(destination.getX() >> 4, destination.getZ() >> 4)
                .getBlockState(destination);
        if (!beforeDestination.isAir()) return denied();
        // Constructor reads clicked state. Both possible positions were checked before constructing it.
        var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
        if (context.replacingClickedOnBlock() || !destination.equals(context.getClickedPos()) || !context.canPlace()) return denied();
        if (!authorized.getAsBoolean() || player.level() != level || player.gameMode != gameMode
                || gameMode.getGameModeForPlayer() != mode || player.containerMenu != player.inventoryMenu
                || player.getInventory().getSelectedSlot() != slot || player.getMainHandItem() != stack
                || !ItemStack.matches(stack, beforeStack) || !player.getOffhandItem().isEmpty()
                || player.getCooldowns().isOnCooldown(stack)
                || !allowedPosition(level, player, support) || !allowedPosition(level, player, destination)
                || level.getBlockState(support) != beforeSupport || level.getBlockState(destination) != beforeDestination) return denied();
        var block = ((BlockItem) stack.getItem()).getBlock();
        // Normal vanilla path owns collision, adventure predicates, callbacks and item consumption.
        // Once entered, even a non-consuming outcome must be synchronized; callbacks may have effects.
        gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit);
        var chunk = level.getChunkSource().getChunkNow(destination.getX() >> 4, destination.getZ() >> 4);
        boolean placed = chunk != null && chunk.getBlockState(destination).is(block);
        return new Result(placed ? Outcome.APPLIED : Outcome.DENIED, destination.immutable(), true);
    }

    private static boolean allowedPosition(ServerLevel level, ServerPlayer player, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos)
                && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null
                && !level.getServer().isUnderSpawnProtection(level, pos, player)
                && level.mayInteract(player, pos) && player.mayInteract(level, pos);
    }

    private static Result denied() { return new Result(Outcome.DENIED, null, false); }
}
