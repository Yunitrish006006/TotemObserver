package dev.totem.observer.bridge;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.level.block.Block;

public final class ObserverTargetOutlineGameTest {
    @GameTest
    public void serverOutlineIsPartialImmutableAndBounded(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var profile = new GameProfile(UUID.randomUUID(), "obs-outline");
        var cookie = CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(server, level, profile, cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        try {
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setGameMode(GameType.SURVIVAL);
            // Admission hooks may grant onboarding items; explicitly construct
            // the empty-hand fixture required by this capability.
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            var pos = helper.absolutePos(new BlockPos(1, 2, 1));
            helper.setBlock(1, 1, 0, Blocks.AIR);
            helper.setBlock(1, 2, 0, Blocks.AIR);
            helper.setBlock(1, 2, 2, Blocks.STONE);
            helper.setBlock(1, 2, 1, Blocks.LEVER.defaultBlockState()
                    .setValue(LeverBlock.FACE, AttachFace.WALL)
                    .setValue(LeverBlock.FACING, Direction.NORTH)
                    .setValue(LeverBlock.POWERED, false));
            // Fixture placement, not a browser teleport or interaction claim.
            player.snapTo(pos.getX() + 0.5, pos.getY() - 1.12, pos.getZ() - 0.5, 0, 0);
            var outline = ObserverTargetOutline.capture(player, () -> true);
            require(outline != null && pos.equals(outline.pos()) && outline.face() == Direction.NORTH,
                    "Missing real lever outline");
            require(outline.rawId() == Block.getId(level.getBlockState(pos)), "Wrong outline raw state");
            require(outline.boxes().size() == 1, "Unexpected vanilla lever shape decomposition");
            var box = outline.boxes().getFirst();
            require(box.maxX() - box.minX() < 1 && box.maxY() - box.minY() < 1
                    && box.maxZ() - box.minZ() < 1, "Lever falsely became a full cube");
            try {
                outline.boxes().clear();
                throw new AssertionError("Mutable outline escaped");
            } catch (UnsupportedOperationException expected) { }
            require(ObserverTargetOutline.capture(player, () -> false) == null, "Revoked outline accepted");
            var checks = new AtomicInteger();
            require(ObserverTargetOutline.capture(player, () -> checks.incrementAndGet() < 3) == null
                    && checks.get() == 3, "Authorization loss after shape capture accepted");
            helper.setBlock(1, 2, 1, Blocks.STONE);
            var stone = ObserverTargetOutline.capture(player, () -> true);
            require(stone != null && stone.boxes().getFirst().maxX() - stone.boxes().getFirst().minX() == 1,
                    "Stone shape mismatch");
            require(outline.rawId() != stone.rawId() && outline.boxes().getFirst().equals(box),
                    "Captured shape changed after world mutation");
            require(ObserverTargetOutline.boundedBoxes(Shapes.empty()) == null, "Empty shape accepted");
            require(ObserverTargetOutline.boundedBoxes(Shapes.box(-2, 0, 0, 1, 1, 1)) == null,
                    "Out-of-bounds local coordinates accepted");
            var complex = Shapes.empty();
            for (int i = 0; i < 18; i++) {
                complex = Shapes.joinUnoptimized(complex,
                        Shapes.box(i / 40.0, 0, 0, (i + 0.5) / 40.0, 0.5, 0.5), BooleanOp.OR);
            }
            require(ObserverTargetOutline.boundedBoxes(complex) == null, "Complex grid accepted");
            var manyBoxes = Shapes.empty();
            for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 2; z++) {
                manyBoxes = Shapes.joinUnoptimized(manyBoxes,
                        Shapes.box(x / 4.0, y / 4.0, z / 4.0,
                                x / 4.0 + 0.125, y / 4.0 + 0.125, z / 4.0 + 0.125), BooleanOp.OR);
            }
            require(ObserverTargetOutline.boundedBoxes(manyBoxes) == null, "More than 16 boxes accepted");
            try {
                new ObserverTargetOutline.Box(Double.NaN, 0, 0, 1, 1, 1);
                throw new AssertionError("Invalid directly constructed box accepted");
            } catch (IllegalArgumentException expected) { }
            try {
                new ObserverTargetOutline.Snapshot(pos, Direction.NORTH, 0, java.util.List.of());
                throw new AssertionError("Empty directly constructed outline accepted");
            } catch (IllegalArgumentException expected) { }
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
