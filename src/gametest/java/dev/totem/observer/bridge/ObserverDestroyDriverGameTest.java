package dev.totem.observer.bridge;

import com.mojang.authlib.GameProfile;
import dev.totem.observer.mixin.ObserverDestroyStateAccessor;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real vanilla game-mode state and ticks; no browser coordinates or synthetic progress. */
public final class ObserverDestroyDriverGameTest {
    @GameTest(maxTicks = 80)
    public void survivalProgressCompletesThroughVanilla(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        try {
            var started = fixture.driver.start();
            require(started.outcome() == ObserverDestroyDriver.Outcome.ACTIVE, "Dirt did not start: " + started);
            require(fixture.isDirt(), "Survival START instantly removed dirt");
            require(fixture.driver.tick(() -> true).outcome() == ObserverDestroyDriver.Outcome.ACTIVE,
                    "Same-tick input completed mining");
        } catch (Throwable failure) { fixture.close(); throw failure; }
        var completed = new AtomicBoolean();
        for (int tick = 1; tick <= 50; tick++) {
            final int step = tick;
            helper.runAtTickTime(tick, () -> {
                if (completed.get()) return;
                try {
                    // ServerPlayer.tick advances its real gameMode once per server tick.
                    float vanillaProgress = fixture.player.level().getBlockState(fixture.pos)
                            .getDestroyProgress(fixture.player, fixture.player.level(), fixture.pos)
                            * (fixture.state.observer$gameTicks() - fixture.state.observer$destroyStart() + 1);
                    var result = fixture.driver.tick(() -> true);
                    if (result.outcome() == ObserverDestroyDriver.Outcome.CHANGED) {
                        require(vanillaProgress >= 1, "Completed before vanilla progress reached one");
                        require(!fixture.isDirt() && result.worldMayHaveChanged(), "Completion omitted mutation");
                        require(!fixture.state.observer$isDestroying() && !fixture.state.observer$hasDelayedDestroy(),
                                "Completion retained vanilla mining state");
                        completed.set(true);
                        fixture.close();
                        helper.succeed();
                    } else {
                        require(result.outcome() == ObserverDestroyDriver.Outcome.ACTIVE && fixture.isDirt(),
                                "Mining stopped unexpectedly: " + result);
                        require(step < 50, "Vanilla progress never completed");
                    }
                } catch (Throwable failure) { fixture.close(); throw failure; }
            });
        }
    }

    @GameTest
    public void cancellationPermissionsAndInstantBreak(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            require(f.driver.start().outcome() == ObserverDestroyDriver.Outcome.ACTIVE, "Start failed");
            require(f.driver.tick(() -> false).outcome() == ObserverDestroyDriver.Outcome.CANCELLED,
                    "Missing held lease did not cancel");
            f.assertCancelled();
            require(f.driver.start().outcome() == ObserverDestroyDriver.Outcome.ACTIVE, "Restart failed");
            f.authorized.set(false);
            require(f.driver.tick(() -> true).outcome() == ObserverDestroyDriver.Outcome.CANCELLED,
                    "Revoked mining continued");
            f.assertCancelled();
            require(f.driver.start().outcome() == ObserverDestroyDriver.Outcome.DENIED, "Revoked START accepted");
            f.authorized.set(true);
            f.driver.start();
            f.player.setYRot(180);
            f.player.setYHeadRot(180);
            require(f.driver.tick(() -> true).outcome() == ObserverDestroyDriver.Outcome.CANCELLED,
                    "Mining continued after look-away");
            f.assertCancelled();
            f.player.setYRot(0);
            f.player.setYHeadRot(0);
            f.driver.start();
            f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
            require(f.driver.tick(() -> true).outcome() == ObserverDestroyDriver.Outcome.CANCELLED,
                    "Changed held stack retained old progress");
            f.assertCancelled();
            f.driver.start();
            f.player.getMainHandItem().set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
            require(f.driver.tick(() -> true).outcome() == ObserverDestroyDriver.Outcome.CANCELLED,
                    "Same item with changed components retained progress");
            f.assertCancelled();
            f.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            f.driver.start();
            f.player.getInventory().setSelectedSlot(1);
            require(f.driver.tick(() -> true).outcome() == ObserverDestroyDriver.Outcome.CANCELLED,
                    "Selected-slot change retained progress");
            f.assertCancelled();
            f.player.getInventory().setSelectedSlot(0);
            f.driver.start();
            double oldZ = f.player.getZ();
            f.player.snapTo(f.player.getX(), f.player.getY(), oldZ - 20, 0, 0);
            require(f.driver.tick(() -> true).outcome() == ObserverDestroyDriver.Outcome.CANCELLED,
                    "Out-of-reach operation retained progress");
            f.assertCancelled();
            f.player.snapTo(f.player.getX(), f.player.getY(), oldZ, 0, 0);
            f.driver.start();
            helper.setBlock(1, 2, 1, Blocks.STONE);
            require(f.driver.tick(() -> true).outcome() == ObserverDestroyDriver.Outcome.CANCELLED
                    && f.player.level().getBlockState(f.pos).is(Blocks.STONE),
                    "Replacement block inherited mining progress");
            helper.setBlock(1, 2, 1, Blocks.DIRT);
            f.driver.start();
            // Deliberately create vanilla's dangerous early STOP state, then exercise unconditional cleanup.
            f.player.gameMode.handleBlockBreakAction(f.pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    Direction.NORTH, f.player.level().getMaxY(), 0);
            require(f.state.observer$hasDelayedDestroy(), "Fixture did not enter vanilla delayed state");
            f.driver.cancel();
            f.assertCancelled();
            for (int i = 0; i < 30; i++) f.player.gameMode.tick();
            require(f.isDirt(), "Cancelled delayed state destroyed block later");
            f.player.setGameMode(GameType.ADVENTURE);
            require(f.driver.start().outcome() == ObserverDestroyDriver.Outcome.DENIED && f.isDirt(),
                    "Adventure restriction bypassed");
            f.player.setGameMode(GameType.SURVIVAL);
            helper.setBlock(1, 2, 1, Blocks.BEDROCK);
            require(f.driver.start().outcome() == ObserverDestroyDriver.Outcome.DENIED, "Unbreakable block started");
            helper.setBlock(1, 2, 1, Blocks.DIRT);
            f.player.setGameMode(GameType.CREATIVE);
            var instant = f.driver.start();
            require(instant.outcome() == ObserverDestroyDriver.Outcome.CHANGED && instant.worldMayHaveChanged()
                    && !f.isDirt(), "Creative START mutation not reported: " + instant);
            helper.succeed();
        }
    }

    static final class Fixture implements AutoCloseable {
        final ServerPlayer player;
        final EmbeddedChannel channel;
        final BlockPos pos;
        final AtomicBoolean authorized = new AtomicBoolean(true);
        final ObserverDestroyDriver driver;
        final ObserverDestroyStateAccessor state;
        private boolean closed;

        Fixture(GameTestHelper helper) {
            var level = helper.getLevel();
            var server = level.getServer();
            var profile = new GameProfile(UUID.randomUUID(), "obs-dig");
            var cookie = CommonListenerCookie.createInitial(profile, false);
            player = new ServerPlayer(server, level, profile, cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel(connection);
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setGameMode(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            helper.setBlock(1, 1, 0, Blocks.AIR);
            helper.setBlock(1, 2, 0, Blocks.AIR);
            helper.setBlock(1, 2, 1, Blocks.DIRT);
            pos = helper.absolutePos(new BlockPos(1, 2, 1));
            // Fixture placement only; driver derives its own ray from the resulting player eye.
            player.snapTo(pos.getX() + 0.5, pos.getY() - 1.12, pos.getZ() - 0.5, 0, 0);
            player.setOnGround(true);
            driver = new ObserverDestroyDriver(player, authorized::get);
            state = (ObserverDestroyStateAccessor) player.gameMode;
        }
        boolean isDirt() { return player.level().getBlockState(pos).is(Blocks.DIRT); }
        void assertCancelled() {
            require(isDirt() && !state.observer$isDestroying() && !state.observer$hasDelayedDestroy(),
                    "Cancellation changed dirt or retained vanilla state");
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            driver.cancel();
            player.level().getServer().getPlayerList().remove(player);
            channel.finishAndReleaseAll();
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
