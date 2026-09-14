package dev.totem.observer.bridge;

import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Isolated test mod: real account bridge and vanilla player, never included in the production JAR. */
public final class ObserverBrowserFixture implements ModInitializer {
    private ObserverBridgeServer bridge;
    private Path results;
    private int ticks;
    private java.util.UUID preparedPlayer;
    private static final BlockPos LEVER = new BlockPos(10, 65, 10);
    private static final BlockPos DIRT = new BlockPos(8, 64, 11);

    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("totem.observer.browserFixture.enabled")) return;
        results = Path.of(System.getProperty("totem.observer.browserFixture.results"));
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (bridge != null) bridge.close();
        });
    }

    private void start(MinecraftServer server) {
        try {
            Files.createDirectories(results);
            var level = server.overworld();
            level.getGameRules().set(GameRules.RESPAWN_RADIUS, 0, server);
            level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
            // Only test setup loads these nine chunks. Production render requests still never load chunks.
            // Flat test world supplies empty space above this bounded floor and two-block-high wall.
            for (int x = -16; x < 32; x++) {
                for (int z = 0; z < 48; z++) {
                    // Nonzero registry page exercises persistent canonical-name reuse.
                    level.setBlock(new BlockPos(x, 63, z), Blocks.COBBLESTONE.defaultBlockState(), 3);
                }
            }
            for (int x = 0; x < 16; x++) {
                for (int y = 64; y < 66; y++) {
                    level.setBlock(new BlockPos(x, y, 14), Blocks.STONE.defaultBlockState(), 3);
                }
            }
            level.setRespawnData(LevelData.RespawnData.of(level.dimension(), new BlockPos(8, 64, 8), 0, 25));
            level.setBlock(LEVER.south(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(DIRT, Blocks.DIRT.defaultBlockState(), 3);
            level.setBlock(LEVER, Blocks.LEVER.defaultBlockState()
                    .setValue(LeverBlock.FACE, AttachFace.WALL)
                    .setValue(LeverBlock.FACING, Direction.NORTH)
                    .setValue(LeverBlock.POWERED, false), 3);
            write("placement-protection-result.json", ObserverPlacementProtectionProbe.verify(server, DIRT));
            var accounts = new ObserverAccountService(new ObserverAccountStore(results.resolve("accounts.properties")), true);
            bridge = new ObserverBridgeServer(accounts, new ObserverPlaySessionService(),
                    new ObserverPlayerAdmissionService(server));
            int port = bridge.start(0, System.getProperty("totem.observer.browserFixture.origin"));
            var ready = new JsonObject();
            ready.addProperty("port", port);
            ready.addProperty("fixture", "real Minecraft ServerPlayer and production Observer bridge");
            write("ready.json", ready);
        } catch (IOException failure) {
            throw new IllegalStateException("Browser fixture setup failed", failure);
        }
    }

    private void tick(MinecraftServer server) {
        if (bridge == null) return;
        if (++ticks > 20 * 180) {
            server.halt(false);
            throw new IllegalStateException("Browser fixture exceeded bounded lifetime");
        }
        if (ticks % 2 != 0) return;
        try {
            var state = new JsonObject();
            state.addProperty("tick", server.getTickCount());
            state.addProperty("players", server.getPlayerList().getPlayerCount());
            state.addProperty("leverPowered", server.overworld().getBlockState(LEVER).getValue(LeverBlock.POWERED));
            state.addProperty("miningTargetRemoved", server.overworld().getBlockState(DIRT).isAir());
            if (server.getPlayerList().getPlayerCount() == 1) {
                var player = server.getPlayerList().getPlayers().getFirst();
                if (!player.getUUID().equals(preparedPlayer)) {
                    // Test-only inventory: the browser must select the existing empty slot.
                    // Production never creates/removes items to enable interaction.
                    player.getInventory().setItem(0, new ItemStack(net.minecraft.world.item.Items.STICK, 7));
                    player.getInventory().setItem(1, ItemStack.EMPTY);
                    player.getInventory().setSelectedSlot(0);
                    player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                    preparedPlayer = player.getUUID();
                }
                state.addProperty("selectedSlot", player.getInventory().getSelectedSlot());
                state.addProperty("slotZeroCount", player.getInventory().getItem(0).getCount());
                state.addProperty("mainHandEmpty", player.getMainHandItem().isEmpty());
                state.addProperty("offHandEmpty", player.getOffhandItem().isEmpty());
                state.addProperty("x", player.getX());
                state.addProperty("y", player.getY());
                state.addProperty("z", player.getZ());
                state.addProperty("yaw", player.getYRot());
                state.addProperty("pitch", player.getXRot());
                state.addProperty("onGround", player.onGround());
            }
            write("state.json", state);
            if (Files.exists(results.resolve("stop"))) server.halt(false);
        } catch (IOException failure) {
            throw new IllegalStateException("Browser fixture evidence write failed", failure);
        }
    }

    private void write(String name, JsonObject value) throws IOException {
        Path staging = results.resolve(name + ".tmp");
        Files.writeString(staging, value.toString());
        Files.move(staging, results.resolve(name), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
