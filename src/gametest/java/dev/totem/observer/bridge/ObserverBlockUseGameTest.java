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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class ObserverBlockUseGameTest {
    @GameTest
    public void vanillaLeverUseAndDeniedPaths(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var profile = new GameProfile(UUID.randomUUID(), "obs-use");
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
            var result = ObserverBlockUse.use(player, () -> true);
            require(result.outcome() == ObserverBlockUse.Outcome.APPLIED && pos.equals(result.target()),
                    "Vanilla lever use not applied: " + result + "; loaded=" + player.connection.hasClientLoaded()
                            + "; eye=" + player.getEyePosition() + "; block=" + level.getBlockState(pos));
            require(level.getBlockState(pos).getValue(LeverBlock.POWERED), "Lever state did not change");
            require(ObserverBlockUse.use(player, () -> false).outcome() == ObserverBlockUse.Outcome.DENIED,
                    "Revoked use accepted");
            require(level.getBlockState(pos).getValue(LeverBlock.POWERED), "Revoked use changed lever");
            var authorizationChecks = new AtomicInteger();
            require(ObserverBlockUse.use(player, () -> authorizationChecks.incrementAndGet() < 4).outcome()
                    == ObserverBlockUse.Outcome.DENIED && authorizationChecks.get() == 4,
                    "Last-moment authorization loss accepted");
            require(level.getBlockState(pos).getValue(LeverBlock.POWERED), "Last-moment revocation changed lever");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 3));
            require(ObserverBlockUse.use(player, () -> true).outcome() == ObserverBlockUse.Outcome.DENIED,
                    "Held-item use crossed empty-hand boundary");
            require(player.getMainHandItem().getCount() == 3 && level.getBlockState(pos).getValue(LeverBlock.POWERED),
                    "Denied use mutated item or world");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.STICK));
            require(ObserverBlockUse.use(player, () -> true).outcome() == ObserverBlockUse.Outcome.DENIED,
                    "Offhand item crossed empty-hand boundary");
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            helper.setBlock(1, 2, 1, Blocks.STONE_BUTTON.defaultBlockState()
                    .setValue(ButtonBlock.FACE, AttachFace.WALL).setValue(ButtonBlock.FACING, Direction.NORTH));
            require(ObserverBlockUse.use(player, () -> true).outcome() == ObserverBlockUse.Outcome.APPLIED
                    && level.getBlockState(pos).getValue(ButtonBlock.POWERED), "Vanilla stone-button use not applied");
            helper.setBlock(1, 2, 1, Blocks.CHEST);
            require(ObserverBlockUse.use(player, () -> true).outcome() == ObserverBlockUse.Outcome.UNSUPPORTED,
                    "Container entered first block-use capability");
            require(player.containerMenu == player.inventoryMenu, "Unsupported container opened a menu");
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
