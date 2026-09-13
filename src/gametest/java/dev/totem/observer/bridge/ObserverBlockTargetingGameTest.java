package dev.totem.observer.bridge;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Real vanilla outline shapes and loaded-only server reads, not registry heuristics. */
public final class ObserverBlockTargetingGameTest {
    @GameTest
    public void currentPlayerOutlineAndAuthority(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var profile = new GameProfile(UUID.randomUUID(), "obs-target");
        var cookie = CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(server, level, profile, cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        try {
            player.setGameMode(GameType.SURVIVAL);
            var origin = helper.absolutePos(new BlockPos(0, 1, 0));
            for (int y = 1; y <= 3; y++) {
                for (int z = 0; z < 3; z++) helper.setBlock(1, y, z, Blocks.AIR);
            }
            helper.setBlock(1, 2, 1, Blocks.STONE);
            helper.setBlock(1, 2, 2, Blocks.STONE);
            // Fixture positioning only. Production targeting never accepts client XYZ.
            player.snapTo(origin.getX() + 1.5, origin.getY(), origin.getZ() + 0.5, 0, 0);
            var hit = ObserverBlockTargeting.pick(player, () -> true);
            require(hit != null && hit.getBlockPos().equals(helper.absolutePos(new BlockPos(1, 2, 1))),
                    "Did not select nearest server block");
            require(hit.getDirection() == Direction.NORTH, "Wrong server hit face");
            require(ObserverBlockTargeting.pick(player, () -> false) == null, "Revoked targeting accepted");
            var checks = new AtomicInteger();
            require(ObserverBlockTargeting.pick(player, () -> checks.incrementAndGet() == 1) == null,
                    "Authorization loss during raycast accepted");

            helper.setBlock(1, 2, 1, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
            hit = ObserverBlockTargeting.pick(player, () -> true);
            require(hit != null && hit.getBlockPos().equals(helper.absolutePos(new BlockPos(1, 2, 2))),
                    "Partial block was incorrectly treated as a full cube");

            var range = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
            double originalRange = range.getBaseValue();
            range.setBaseValue(0.25);
            require(ObserverBlockTargeting.pick(player, () -> true) == null, "Server reach attribute ignored");
            range.setBaseValue(originalRange);
            player.setGameMode(GameType.SPECTATOR);
            require(ObserverBlockTargeting.pick(player, () -> true) == null, "Spectator targeting accepted");
            player.setGameMode(GameType.SURVIVAL);

            var entityPos = helper.absolutePos(new BlockPos(0, 1, 0));
            helper.setBlock(0, 1, 0, Blocks.CHEST);
            var entityChunk = level.getChunkSource().getChunkNow(entityPos.getX() >> 4, entityPos.getZ() >> 4);
            var existingEntity = entityChunk.getBlockEntities().get(entityPos);
            require(existingEntity != null, "Chest fixture must have a live block entity");
            var view = new ObserverBlockTargeting.LoadedView(level, entityPos);
            require(view.getBlockEntity(entityPos) == existingEntity && !view.failed(),
                    "Existing shape data was not read directly");
            var pending = existingEntity.saveWithFullMetadata(level.registryAccess());
            entityChunk.removeBlockEntity(entityPos);
            entityChunk.setBlockEntityNbt(pending);
            view = new ObserverBlockTargeting.LoadedView(level, entityPos);
            require(view.getBlockEntity(entityPos) == null && view.failed(), "Pending shape data did not fail closed");
            require(entityChunk.getBlockEntityNbt(entityPos) == pending
                    && !entityChunk.getBlockEntities().containsKey(entityPos), "Targeting promoted pending block entity NBT");
            // Fixture cleanup: restore pending data while the block is still a
            // chest, before replacing it with air. This is outside the tested read path.
            require(entityChunk.getBlockEntity(entityPos) != null, "Could not restore chest fixture for cleanup");
            helper.setBlock(0, 1, 0, Blocks.AIR);
            view = new ObserverBlockTargeting.LoadedView(level, entityPos);
            for (int read = 0; read <= ObserverBlockTargeting.MAX_READS; read++) view.getBlockState(entityPos);
            require(view.failed(), "Read budget was not enforced");

            // Never create or force-load this distant chunk for the test or raycast.
            require(level.getChunkSource().getChunkNow(62500, 62500) == null, "Expected unloaded test chunk");
            player.snapTo(1000000.5, origin.getY(), 1000000.5, 0, 0);
            require(ObserverBlockTargeting.pick(player, () -> true) == null, "Unloaded world accepted");
            require(level.getChunkSource().getChunkNow(62500, 62500) == null, "Raycast loaded a missing chunk");
            helper.succeed();
        } finally {
            server.getPlayerList().remove(player);
            channel.finishAndReleaseAll();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
