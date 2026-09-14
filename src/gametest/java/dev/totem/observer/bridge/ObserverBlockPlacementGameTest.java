package dev.totem.observer.bridge;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicInteger;

public final class ObserverBlockPlacementGameTest {
    @GameTest
    public void placementRejectsBorderHeightAndMissingWorldWithoutMutation(GameTestHelper helper) {
        try (var f = new ObserverDestroyDriverGameTest.Fixture(helper)) {
            var player = f.player;
            var level = player.level();
            player.setYHeadRot(0);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 3));
            var border = level.getWorldBorder();
            double centerX = border.getCenterX(), centerZ = border.getCenterZ(), size = border.getSize();
            require(border.getLerpTime() == 0, "Test requires a stationary border to restore exactly");
            try {
                // Synchronous server-thread scope: restore before any other test/server tick can run.
                border.setCenter(f.pos.getX() + .5, f.pos.getZ() + .5);
                border.setSize(1);
                require(border.isWithinBounds(f.pos) && !border.isWithinBounds(f.pos.north()),
                        "Border fixture does not separate support and destination");
                var result = ObserverBlockPlacement.place(player, () -> true);
                require(result.outcome() == ObserverBlockPlacement.Outcome.DENIED && !result.worldMayHaveChanged(),
                        "Destination outside border entered vanilla placement");
            } finally {
                border.setCenter(centerX, centerZ);
                border.setSize(size);
            }
            player.snapTo(f.pos.getX() + .5, level.getMaxY() + 1, f.pos.getZ() + .5, 0, 90);
            require(ObserverBlockPlacement.place(player, () -> true).outcome() == ObserverBlockPlacement.Outcome.NO_TARGET,
                    "Out-of-height eye produced a placement target");
            var missing = new BlockPos(f.pos.getX() + 8192, f.pos.getY(), f.pos.getZ() + 8192);
            require(level.getChunkSource().getChunkNow(missing.getX() >> 4, missing.getZ() >> 4) == null,
                    "Expected isolated missing chunk fixture");
            player.snapTo(missing.getX() + .5, missing.getY(), missing.getZ() + .5, 0, 0);
            var unavailable = ObserverBlockPlacement.place(player, () -> true);
            require(unavailable.outcome() == ObserverBlockPlacement.Outcome.NO_TARGET && !unavailable.worldMayHaveChanged()
                    && level.getChunkSource().getChunkNow(missing.getX() >> 4, missing.getZ() >> 4) == null,
                    "Missing world ray caused placement or loaded a chunk");
            require(player.getMainHandItem().getCount() == 3 && level.getBlockState(f.pos.north()).isAir(),
                    "Rejected preflight changed inventory or original destination");
            helper.succeed();
        }
    }

    @GameTest
    public void vanillaPlacementConservesInventoryAndRejectsUnsafeActions(GameTestHelper helper) {
        try (var f = new ObserverDestroyDriverGameTest.Fixture(helper)) {
            var player = f.player;
            var level = player.level();
            var support = f.pos.below(2);
            var destination = support.above();
            level.setBlockAndUpdate(f.pos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(support, Blocks.DIRT.defaultBlockState());
            // Back away from the candidate placement so ordinary vanilla collision can succeed.
            player.snapTo(support.getX() + .5, support.getY() + 1, support.getZ() - .5, 0, 70);
            player.setYHeadRot(0);
            for (var mode : new GameType[] {GameType.SURVIVAL, GameType.CREATIVE}) {
                level.setBlockAndUpdate(destination, Blocks.AIR.defaultBlockState());
                player.setGameMode(mode);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 3));
                var result = ObserverBlockPlacement.place(player, () -> true);
                require(result.outcome() == ObserverBlockPlacement.Outcome.APPLIED
                        && destination.equals(result.destination()) && result.worldMayHaveChanged(),
                        "Vanilla placement not applied: " + result);
                require(level.getBlockState(destination).is(Blocks.COBBLESTONE), "Actual destination did not change");
                require(player.getMainHandItem().getCount() == (mode == GameType.SURVIVAL ? 2 : 3),
                        "Vanilla inventory consumption differs from game mode");
            }
            level.setBlockAndUpdate(destination, Blocks.AIR.defaultBlockState());
            player.setGameMode(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 3));
            require(ObserverBlockPlacement.place(player, () -> false).outcome() == ObserverBlockPlacement.Outcome.DENIED,
                    "Revoked placement accepted");
            var calls = new AtomicInteger();
            var changedHeld = ObserverBlockPlacement.place(player, () -> {
                if (calls.incrementAndGet() == 4) player.getMainHandItem().setCount(2);
                return true;
            });
            require(calls.get() == 4 && changedHeld.outcome() == ObserverBlockPlacement.Outcome.DENIED,
                    "Last-moment stack change not rejected");
            require(level.getBlockState(destination).isAir(), "Denied placement changed world");
            for (boolean componentChange : new boolean[] {true, false}) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 3));
                int slot = player.getInventory().getSelectedSlot();
                calls.set(0);
                var changed = ObserverBlockPlacement.place(player, () -> {
                    if (calls.incrementAndGet() == 4) {
                        if (componentChange) player.getMainHandItem().set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
                        else player.getInventory().setSelectedSlot((slot + 1) % 9);
                    }
                    return true;
                });
                player.getInventory().setSelectedSlot(slot);
                require(changed.outcome() == ObserverBlockPlacement.Outcome.DENIED
                        && !changed.worldMayHaveChanged() && level.getBlockState(destination).isAir(),
                        "Changed components/selected slot entered vanilla action");
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 3));
            var cooldowns = player.getCooldowns();
            var group = cooldowns.getCooldownGroup(player.getMainHandItem());
            cooldowns.addCooldown(player.getMainHandItem(), 20);
            require(ObserverBlockPlacement.place(player, () -> true).outcome() == ObserverBlockPlacement.Outcome.DENIED,
                    "Cooldown was bypassed");
            cooldowns.removeCooldown(group);
            player.setGameMode(GameType.ADVENTURE);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 3));
            var adventure = ObserverBlockPlacement.place(player, () -> true);
            require(adventure.outcome() == ObserverBlockPlacement.Outcome.DENIED
                    && level.getBlockState(destination).isAir() && player.getMainHandItem().getCount() == 3,
                    "Adventure placement bypassed vanilla item permissions");
            player.setGameMode(GameType.SURVIVAL);
            player.snapTo(support.getX() + .5, support.getY() + 1, support.getZ() + .5, 0, 90);
            var collision = ObserverBlockPlacement.place(player, () -> true);
            require(collision.outcome() == ObserverBlockPlacement.Outcome.DENIED
                    && level.getBlockState(destination).isAir() && player.getMainHandItem().getCount() == 3,
                    "Placement intersected player or consumed item on collision");
            player.snapTo(support.getX() + .5, support.getY() + 1, support.getZ() - .5, 0, 70);
            level.setBlockAndUpdate(support, Blocks.CHEST.defaultBlockState());
            require(ObserverBlockPlacement.place(player, () -> true).outcome() == ObserverBlockPlacement.Outcome.UNSUPPORTED,
                    "Container target entered placement path");
            require(player.containerMenu == player.inventoryMenu, "Placement opened a container");
            level.setBlockAndUpdate(support, Blocks.DIRT.defaultBlockState());
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHEST));
            require(ObserverBlockPlacement.place(player, () -> true).outcome() == ObserverBlockPlacement.Outcome.UNSUPPORTED,
                    "Unsupported block item accepted");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 3));
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.STICK));
            require(ObserverBlockPlacement.place(player, () -> true).outcome() == ObserverBlockPlacement.Outcome.DENIED,
                    "Offhand crossed placement capability boundary");
            require(level.getBlockState(destination).isAir() && player.getMainHandItem().getCount() == 3,
                    "Rejected actions mutated inventory/world");
            helper.succeed();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
