package dev.totem.observer.bridge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Server-thread hotbar selection; clients never provide item data. */
final class ObserverHotbar {
    record Stack(String item, int count) {
        Stack {
            if (item == null || item.length() > 256 || !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || count < 0 || count > 999) throw new IllegalArgumentException("Invalid hotbar stack");
        }
    }
    record Snapshot(int selected, List<Stack> slots) {
        Snapshot {
            if (selected < 0 || selected > 8 || slots == null || slots.size() != 9) {
                throw new IllegalArgumentException("Invalid hotbar snapshot");
            }
            slots = List.copyOf(slots);
        }
    }
    private ObserverHotbar() {}

    static Snapshot select(ServerPlayer player, int slot, BooleanSupplier authorized) {
        if (slot < 0 || slot > 8 || !allowed(player, authorized)) return null;
        // Validate bounded inventory projection before applying any selection.
        capture(player);
        if (!authorized.getAsBoolean()) return null;
        player.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot));
        return new Snapshot(player.getInventory().getSelectedSlot(), capture(player));
    }

    static Snapshot snapshot(ServerPlayer player, BooleanSupplier authorized) {
        if (!allowed(player, authorized)) return null;
        var result = new Snapshot(player.getInventory().getSelectedSlot(), capture(player));
        return authorized.getAsBoolean() ? result : null;
    }

    private static boolean allowed(ServerPlayer player, BooleanSupplier authorized) {
        var server = player.level().getServer();
        if (!server.isSameThread()) throw new IllegalStateException("Hotbar requires server thread");
        if (!authorized.getAsBoolean()
                || server.getPlayerList().getPlayer(player.getUUID()) != player
                || player.connection == null || !player.connection.hasClientLoaded()
                || !player.isAlive() || player.isSpectator() || player.isSleeping()
                || player.containerMenu != player.inventoryMenu) return false;
        return true;
    }

    private static List<Stack> capture(ServerPlayer player) {
        var slots = new ArrayList<Stack>(9);
        for (int i = 0; i < 9; i++) {
            var stack = player.getInventory().getItem(i);
            slots.add(stack.isEmpty() ? new Stack("minecraft:air", 0)
                    : new Stack(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
        }
        return slots;
    }
}
