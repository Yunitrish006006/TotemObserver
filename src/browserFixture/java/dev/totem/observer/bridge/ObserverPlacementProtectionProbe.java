package dev.totem.observer.bridge;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;

/** Test-only real DedicatedServer policy probe, before browser admission begins. */
final class ObserverPlacementProtectionProbe {
    static JsonObject verify(MinecraftServer server, BlockPos support) {
        if (!(server instanceof DedicatedServer) || !server.isSameThread()
                || !server.getPlayerList().getOps().isEmpty()) {
            throw new IllegalStateException("Protection probe requires fresh isolated dedicated server");
        }
        var profile = new GameProfile(UUID.randomUUID(), "obs-place-probe");
        var cookie = CommonListenerCookie.createInitial(profile, false);
        var level = server.overworld();
        var player = new ServerPlayer(server, level, profile, cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        var operator = new NameAndId(UUID.randomUUID(), "fixture-policy");
        var destination = support.north();
        try {
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().setSelectedSlot(0);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 3));
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.snapTo(8.5, 64, 8.5, 0, 20.5f);
            player.setYHeadRot(0);
            // Vanilla enables spawn protection only with a nonempty operator list.
            // The acting player remains unprivileged throughout the probe.
            server.getPlayerList().op(operator);
            require(server.isUnderSpawnProtection(level, support, player)
                    && server.isUnderSpawnProtection(level, destination, player), "Spawn protection fixture inactive");
            var denied = ObserverBlockPlacement.place(player, () -> true);
            require(denied.outcome() != ObserverBlockPlacement.Outcome.APPLIED && !denied.worldMayHaveChanged()
                    && level.getBlockState(destination).isAir() && player.getMainHandItem().getCount() == 3,
                    "Protected placement changed world or inventory");
            server.getPlayerList().deop(operator);
            require(!server.isUnderSpawnProtection(level, support, player), "Policy did not return to unprotected fixture");
            var allowed = ObserverBlockPlacement.place(player, () -> true);
            require(allowed.outcome() == ObserverBlockPlacement.Outcome.APPLIED
                    && destination.equals(allowed.destination()) && level.getBlockState(destination).is(Blocks.DIRT)
                    && player.getMainHandItem().getCount() == 2, "Same real ray did not place after protection removed");
            var evidence = new JsonObject();
            evidence.addProperty("passed", true);
            evidence.addProperty("protectedMutationRejected", true);
            evidence.addProperty("unprivilegedPlacementAfterPolicyChange", true);
            evidence.addProperty("survivalConsumed", 1);
            return evidence;
        } finally {
            try {
                level.setBlockAndUpdate(destination, Blocks.AIR.defaultBlockState());
            } finally {
                try {
                    server.getPlayerList().deop(operator);
                } finally {
                    try {
                        if (server.getPlayerList().getPlayer(player.getUUID()) == player) server.getPlayerList().remove(player);
                    } finally {
                        channel.finishAndReleaseAll();
                    }
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
