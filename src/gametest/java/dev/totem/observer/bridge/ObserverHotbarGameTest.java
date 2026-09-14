package dev.totem.observer.bridge;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class ObserverHotbarGameTest {
    @GameTest
    public void vanillaSelectionPreservesInventoryAndRejectsInvalidRequests(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var profile = new GameProfile(UUID.randomUUID(), "obs-hotbar");
        var cookie = CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(server, level, profile, cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        try {
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setGameMode(GameType.SURVIVAL);
            var inventory = player.getInventory();
            inventory.setItem(0, new ItemStack(Items.APPLE, 3));
            inventory.setItem(2, new ItemStack(Items.STICK, 7));
            inventory.setSelectedSlot(0);
            var before = new java.util.ArrayList<ItemStack>();
            for (int i = 0; i < inventory.getContainerSize(); i++) before.add(inventory.getItem(i).copy());
            player.startUsingItem(InteractionHand.MAIN_HAND);
            require(player.isUsingItem(), "Fixture did not start item use");
            var selected = ObserverHotbar.select(player, 2, () -> true);
            require(selected != null && selected.selected() == 2 && !player.isUsingItem(), "Vanilla slot switch missing");
            require(selected.slots().size() == 9 && selected.slots().get(0).count() == 3
                    && selected.slots().get(2).item().equals("minecraft:stick") && selected.slots().get(2).count() == 7,
                    "Server hotbar projection incorrect");
            for (int i = 0; i < before.size(); i++) require(ItemStack.matches(before.get(i), inventory.getItem(i)),
                    "Slot selection mutated inventory " + i);
            require(ObserverHotbar.select(player, -1, () -> true) == null
                    && ObserverHotbar.select(player, 9, () -> true) == null
                    && ObserverHotbar.select(player, 1, () -> false) == null, "Invalid selection accepted");
            var checks = new AtomicInteger();
            require(ObserverHotbar.select(player, 1, () -> checks.incrementAndGet() < 2) == null
                    && inventory.getSelectedSlot() == 2, "Late revocation changed selection");
            player.setGameMode(GameType.SPECTATOR);
            require(ObserverHotbar.select(player, 1, () -> true) == null, "Spectator selection accepted");
            require(inventory.getSelectedSlot() == 2, "Denied selection changed slot");
            inventory.setItem(2, ItemStack.EMPTY);
            require(selected.slots().get(2).count() == 7, "Snapshot aliases mutable inventory");
            boolean immutable = false;
            try { selected.slots().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
            require(immutable, "Mutable snapshot slots");
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
