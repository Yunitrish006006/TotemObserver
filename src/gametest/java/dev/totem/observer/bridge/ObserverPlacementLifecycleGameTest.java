package dev.totem.observer.bridge;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ObserverPlacementLifecycleGameTest {
    @GameTest(maxTicks = 100_000)
    public void admissionRejectsStaleInputConsumesMiningAndRetiresAfterMutationFailure(GameTestHelper helper) {
        verifyLifecycle(helper, false);
    }

    @GameTest(maxTicks = 100_000)
    public void admissionRetiresWhenAuthorizationRevokedAfterVanillaPlacement(GameTestHelper helper) {
        verifyLifecycle(helper, true);
    }

    private static void verifyLifecycle(GameTestHelper helper, boolean revokeAfterPlacement) {
        var clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
        var failAfterPlacement = new AtomicBoolean();
        var sessionAuthorized = new AtomicBoolean(true);
        var sessions = new ObserverPlaySessionService();
        var server = helper.getLevel().getServer();
        var admissions = new ObserverPlayerAdmissionService(server, clock::get, (player, authorized) -> {
            var placed = ObserverBlockPlacement.place(player, authorized);
            if (failAfterPlacement.get()) {
                require(placed.outcome() == ObserverBlockPlacement.Outcome.APPLIED, "Fault probe did not first mutate world");
                if (revokeAfterPlacement) sessionAuthorized.set(false);
                else throw new IllegalStateException("Test failure after real vanilla placement");
            }
            return placed;
        });
        var auth = new ObserverAccountService.Session("place_" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        var result = admissions.open(sessions.open(auth)).thenAccept(admission -> {
            require(admission != null, "Missing placement admission");
            var player = admission.player();
            var level = player.level();
            var support = helper.absolutePos(new BlockPos(1, 0, 1));
            var destination = support.above();
            helper.setBlock(1, 0, 1, Blocks.DIRT);
            helper.setBlock(1, 1, 1, Blocks.AIR);
            helper.setBlock(1, 2, 1, Blocks.AIR);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().setSelectedSlot(0);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 3));
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.snapTo(support.getX() + .5, support.getY() + 1, support.getZ() - .5, 0, 70);
            player.setYHeadRot(0);
            player.setOnGround(true);
            String dimension = level.dimension().identifier().toString();
            require(admissions.placeBlock(admission, dimension, clock.get() - TimeUnit.MILLISECONDS.toNanos(251), () -> true).join() == null,
                    "Expired placement input accepted");
            require(admissions.placeBlock(admission, dimension, clock.get() + 1, () -> true).join() == null,
                    "Future placement timestamp accepted");
            require(admissions.placeBlock(admission, dimension, clock.get(), () -> false).join() == null,
                    "Revoked placement accepted");
            require(admissions.placeBlock(admission, "minecraft:the_nether", clock.get(), () -> true).join() == null,
                    "Wrong-dimension placement accepted");
            require(level.getBlockState(destination).isAir() && player.getMainHandItem().getCount() == 3,
                    "Rejected input changed actual world/inventory");
            var placed = admissions.placeBlock(admission, dimension, clock.get(), () -> true).join();
            require(placed.state().outcome() == ObserverBlockPlacement.Outcome.APPLIED && placed.refreshRequired()
                    && player.getMainHandItem().getCount() == 2, "Admission did not preserve vanilla result");
            level.setBlockAndUpdate(destination, Blocks.AIR.defaultBlockState());
            var mining = admissions.destroy(admission, dimension, 7, ObserverDestroyOperation.Action.START, clock.get(), () -> true).join();
            require(mining.result().outcome() == ObserverDestroyDriver.Outcome.ACTIVE, "Mining fixture not active");
            var cancelled = admissions.placeBlock(admission, dimension, clock.get(), () -> true).join();
            require(cancelled.state().outcome() == ObserverBlockPlacement.Outcome.DENIED && cancelled.refreshRequired()
                    && level.getBlockState(destination).isAir() && level.getBlockState(support).is(Blocks.DIRT),
                    "Placement crossed unconsumed mining owner");
            require(admissions.placeBlock(admission, dimension, clock.get(), () -> true).join().refreshRequired(),
                    "Repeated placement consumed terminal without bootstrap");
            require(admissions.bootstrap(admission).join() != null, "Bootstrap did not consume cancelled mining");
            failAfterPlacement.set(true);
            var failed = admissions.placeBlock(admission, dimension, clock.get(), sessionAuthorized::get);
            require(failed.isCompletedExceptionally() && !admissions.valid(admission.playSession(), admission)
                    && server.getPlayerList().getPlayer(player.getUUID()) == null,
                    "Post-mutation failure retained admission or player");
            require(level.getBlockState(destination).is(Blocks.DIRT) && player.getMainHandItem().getCount() == 1,
                    "Cleanup test did not follow actual placement/inventory consumption");
        });
        result.whenComplete((ignored, failure) -> { admissions.close(); sessions.close(); });
        helper.startSequence().thenWaitUntil(() -> {
            if (!result.isDone()) helper.fail("Waiting for placement admission");
            result.join();
        }).thenSucceed();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
