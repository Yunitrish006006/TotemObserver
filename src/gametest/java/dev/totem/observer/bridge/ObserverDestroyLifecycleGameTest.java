package dev.totem.observer.bridge;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ObserverDestroyLifecycleGameTest {
    @GameTest(maxTicks = 100_000)
    public void admissionOwnsExpirySelectionBootstrapAndRelease(GameTestHelper helper) {
        var clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
        var sessions = new ObserverPlaySessionService();
        var admissions = new ObserverPlayerAdmissionService(helper.getLevel().getServer(), clock::get);
        var auth = new ObserverAccountService.Session("dig_" + java.util.UUID.randomUUID().toString().substring(0, 8),
                java.util.UUID.randomUUID());
        var result = admissions.open(sessions.open(auth)).thenAccept(admission -> {
            require(admission != null, "Missing mining admission");
            var player = admission.player();
            var pos = helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
            helper.setBlock(1, 1, 0, net.minecraft.world.level.block.Blocks.AIR);
            helper.setBlock(1, 2, 0, net.minecraft.world.level.block.Blocks.AIR);
            helper.setBlock(1, 2, 1, net.minecraft.world.level.block.Blocks.DIRT);
            player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            player.getInventory().setSelectedSlot(0);
            player.getInventory().setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
            player.getInventory().setItem(1, net.minecraft.world.item.ItemStack.EMPTY);
            player.snapTo(pos.getX() + 0.5, pos.getY() - 1.12, pos.getZ() - 0.5, 0, 0);
            player.setOnGround(true);
            String dimension = player.level().dimension().identifier().toString();
            var start = ObserverDestroyOperation.Action.START;
            var hold = ObserverDestroyOperation.Action.HOLD;
            require(admissions.destroy(admission, dimension, 1, start,
                    clock.get() - TimeUnit.MILLISECONDS.toNanos(251), () -> true).join() == null,
                    "Stale admission request started mining");
            require(admissions.destroy(admission, dimension, 2, start, clock.get(), () -> true).join()
                    .result().outcome() == ObserverDestroyDriver.Outcome.ACTIVE, "Admission START failed");
            require(admissions.hotbar(admission, dimension, 1, clock.get(), () -> true).join() != null,
                    "Slot switch failed");
            var terminal = admissions.destroy(admission, dimension, 2, hold, clock.get(), () -> true).join();
            require(terminal.result().outcome() == ObserverDestroyDriver.Outcome.CANCELLED
                    && terminal.result().worldMayHaveChanged(), "Slot switch lost cancellation/dirty state");
            require(admissions.destroy(admission, dimension, 3, start, clock.get(), () -> true).join() == null,
                    "New START overwrote unconsumed terminal");
            require(admissions.bootstrap(admission).join() != null, "Bootstrap did not consume terminal");
            var authorized = new AtomicBoolean(true);
            require(admissions.destroy(admission, dimension, 3, start, clock.get(), authorized::get).join()
                    .result().outcome() == ObserverDestroyDriver.Outcome.ACTIVE, "Restart after bootstrap failed");
            authorized.set(false);
            player.tick();
            var state = (dev.totem.observer.mixin.ObserverDestroyStateAccessor) player.gameMode;
            require(!state.observer$isDestroying() && !state.observer$hasDelayedDestroy(),
                    "Admission authorization loss survived player hook");
            admissions.release(admission);
            require(!admissions.valid(admission.playSession(), admission)
                    && player.level().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.DIRT),
                    "Release retained admission or destroyed block");
        });
        result.whenComplete((ignored, failure) -> { admissions.close(); sessions.close(); });
        helper.startSequence().thenWaitUntil(() -> {
            if (!result.isDone()) helper.fail("Waiting for mining admission");
            result.join();
        }).thenSucceed();
    }

    @GameTest
    public void expiryBeforeVanillaAndOperationIdentity(GameTestHelper helper) {
        var clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
        try (var f = new ObserverDestroyDriverGameTest.Fixture(helper);
             var owner = new ObserverDestroyOperation(f.player, 10, clock.get(), f.authorized::get, clock::get)) {
            require(owner.start().result().outcome() == ObserverDestroyDriver.Outcome.ACTIVE, "Owner did not start");
            boolean rejected = false;
            try { owner.hold(9, clock.get()); } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected && owner.snapshot().result().outcome() == ObserverDestroyDriver.Outcome.ACTIVE,
                    "Old operation modified current mining");
            f.player.gameMode.handleBlockBreakAction(f.pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    Direction.NORTH, f.player.level().getMaxY(), 0);
            require(f.state.observer$hasDelayedDestroy(), "Fixture did not enter delayed state");
            float speed = f.player.level().getBlockState(f.pos).getDestroyProgress(f.player, f.player.level(), f.pos);
            require(Float.isFinite(speed) && speed > 0, "Invalid fixture mining speed");
            int primingTicks = 0;
            while (speed * (f.state.observer$gameTicks() - f.state.observer$destroyStart() + 2) < 1) {
                require(++primingTicks <= 100, "Fixture never reached critical progress");
                f.player.gameMode.tick();
                require(f.isDirt(), "Fixture completed before critical tick");
            }
            require(f.state.observer$hasDelayedDestroy() && f.isDirt(), "Missing imminent delayed destruction");
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(501));
            // Exercise the production injection, not a direct call to beforeVanillaTick.
            f.player.tick();
            f.assertCancelled();
            require(owner.snapshot().result().outcome() == ObserverDestroyDriver.Outcome.CANCELLED
                    && owner.snapshot().result().worldMayHaveChanged(), "Expiry lost terminal or dirty state");
            for (int i = 0; i < 30; i++) f.player.tick();
            require(f.isDirt(), "Vanilla continued delayed destruction after expiry");
            require(owner.hold(10, clock.get()).result().outcome() == ObserverDestroyDriver.Outcome.CANCELLED,
                    "New heartbeat revived expired operation");
            rejected = false;
            try (var unused = new ObserverDestroyOperation(f.player, 11, clock.get(), () -> true, clock::get)) {
                throw new AssertionError("Unconsumed terminal overwritten");
            } catch (IllegalStateException expected) { rejected = true; }
            require(rejected, "Second owner accepted");
            owner.close();
            try (var replacement = new ObserverDestroyOperation(f.player, 11, clock.get(), () -> true, clock::get)) {
                require(replacement.start().result().outcome() == ObserverDestroyDriver.Outcome.ACTIVE,
                        "Closed owner leaked lookup");
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(501));
                require(replacement.hold(11, clock.get()).result().outcome() == ObserverDestroyDriver.Outcome.CANCELLED,
                        "Late hold revived operation before hook");
            }
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 80)
    public void actualHookCompletesOnceAndPreservesResult(GameTestHelper helper) {
        var f = new ObserverDestroyDriverGameTest.Fixture(helper);
        var owner = new ObserverDestroyOperation(f.player, 20, 1, f.authorized::get, () -> 1);
        owner.start();
        var completed = new AtomicBoolean();
        var done = new AtomicBoolean();
        Runnable cleanup = () -> { try { owner.close(); } finally { f.close(); } };
        for (int tick = 1; tick <= 55; tick++) {
            final int step = tick;
            helper.runAtTickTime(tick, () -> {
                if (done.get()) return;
                try {
                    var snapshot = owner.snapshot();
                    if (snapshot.result().outcome() == ObserverDestroyDriver.Outcome.CHANGED) {
                        require(!f.isDirt() && snapshot.result().worldMayHaveChanged(), "Missing world change");
                        require(!f.state.observer$isDestroying() && !f.state.observer$hasDelayedDestroy(),
                                "Completed hook left vanilla destruction running");
                        completed.set(true);
                    } else {
                        require(!completed.get() && snapshot.result().outcome() == ObserverDestroyDriver.Outcome.ACTIVE,
                                "Lost terminal result or unexpected cancellation");
                    }
                    if (step == 55) {
                        require(completed.get(), "Production hook never completed survival mining");
                        require(owner.cancel(20).result().outcome() == ObserverDestroyDriver.Outcome.CHANGED,
                                "Cancel erased completion before synchronization");
                        done.set(true);
                        cleanup.run();
                        helper.succeed();
                    }
                } catch (Throwable failure) { done.set(true); cleanup.run(); throw failure; }
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
