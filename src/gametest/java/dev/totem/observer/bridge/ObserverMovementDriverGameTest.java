package dev.totem.observer.bridge;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real placed ServerPlayer + vanilla doTick, not a hand-written physics mock. */
public final class ObserverMovementDriverGameTest {
    @GameTest(maxTicks = 100)
    public void inputMovesCollidesJumpsAndRevokes(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var profile = new GameProfile(UUID.randomUUID(), "obs-motion");
        var cookie = CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(server, helper.getLevel(), profile, cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        var cleaned = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (cleaned.compareAndSet(false, true)) {
                server.getPlayerList().remove(player);
                channel.finishAndReleaseAll();
            }
        };
        player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        player.setGameMode(GameType.SURVIVAL);
        final BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
                helper.setBlock(x, 1, z, Blocks.AIR);
                helper.setBlock(x, 2, z, Blocks.AIR);
                helper.setBlock(x, 3, z, Blocks.AIR);
            }
            helper.setBlock(x, 1, 2, Blocks.STONE);
            helper.setBlock(x, 2, 2, Blocks.STONE);
            helper.setBlock(x, 3, 2, Blocks.STONE);
        }
        // Fixture placement only; normal movement never accepts target XYZ.
        player.snapTo(origin.getX() + 1.5, origin.getY(), origin.getZ() + 0.5, 0, 0);
        player.setDeltaMovement(Vec3.ZERO);
        player.setOnGround(true);
        var authorized = new AtomicBoolean(true);
        var driver = new ObserverMovementDriver(player, authorized::get);
        double startZ = player.getZ();
        double startY = player.getY();
        for (int tick = 1; tick <= 30; tick++) {
            final int step = tick;
            helper.runAtTickTime(tick, () -> {
                try {
                    require(driver.tick(new ObserverMovementIntent(0, 1, 0, 0, step == 20)), "Tick rejected");
                    require(!driver.tick(new ObserverMovementIntent(0, 1, 0, 0, false)), "Duplicate tick applied");
                    require(player.getZ() <= origin.getZ() + 1.71, "Crossed solid wall");
                    require(player.getY() >= startY - 0.001, "Fell through floor");
                    if (step == 15) require(player.getZ() > startZ + 0.2, "ServerPlayer did not walk");
                    if (step == 21) require(player.getY() > startY + 0.2, "ServerPlayer did not jump");
                    if (step == 30) authorized.set(false);
                } catch (Throwable failure) {
                    cleanup.run();
                    throw failure;
                }
            });
        }

        helper.runAtTickTime(31, () -> {
          try {
            var before = player.position();
            float yaw = player.getYRot();
            require(!driver.tick(new ObserverMovementIntent(0, 1, 90, 0, true)), "Revoked movement applied");
            require(player.position().equals(before) && player.getYRot() == yaw, "Revoked tick changed state");
            authorized.set(true);
            // Fixture places a falling player; the driver must account for
            // server-simulated landing without client onGround claims.
            player.snapTo(origin.getX() + 1.5, startY + 6, origin.getZ() + 0.5, 0, 0);
            player.setDeltaMovement(Vec3.ZERO);
            player.setOnGround(false);
            player.setInvulnerableTime(0);
            player.setHealth(player.getMaxHealth());
            require(player.connection.hasClientLoaded(), "Fixture must finish player load before fall");
          } catch (Throwable failure) {
            cleanup.run();
            throw failure;
          }
        });
        for (int tick = 32; tick <= 65; tick++) {
            final int step = tick;
            helper.runAtTickTime(tick, () -> {
                try {
                    require(driver.tick(ObserverMovementIntent.idle(0, 0)), "Idle gravity rejected");
                    if (step == 65) {
                        require(player.onGround(), "Falling player never landed");
                        require(player.getHealth() < player.getMaxHealth(), "Landing did not apply fall damage");
                        helper.succeed();
                    }
                } catch (Throwable failure) {
                    cleanup.run();
                    throw failure;
                } finally {
                    if (step == 65) cleanup.run();
                }
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
