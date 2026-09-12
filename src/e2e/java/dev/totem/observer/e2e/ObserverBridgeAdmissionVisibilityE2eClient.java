package dev.totem.observer.e2e;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

/** Target-side proof that a normal Java client receives the account-v1 player's vanilla add/remove lifecycle. */
public final class ObserverBridgeAdmissionVisibilityE2eClient implements ClientModInitializer {
    private static final int VISIBILITY_TIMEOUT_TICKS = 20 * 60;
    private static Identity expected;
    private static int ticksSinceExpected;
    private static boolean visible;
    private static boolean finished;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("totem.observer.e2e.enabled")) return;
        if (!"target".equals(System.getProperty("totem.observer.e2e.role", "").trim())) return;
        ClientTickEvents.END_CLIENT_TICK.register(ObserverBridgeAdmissionVisibilityE2eClient::tick);
    }

    private static void tick(Minecraft minecraft) {
        if (finished || minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) return;
        try {
            if (expected == null) {
                Path marker = ObserverE2eCommon.resultsDir().resolve("server-bridge-player-admitted.txt");
                if (!Files.isRegularFile(marker)) return;
                expected = readIdentity(marker);
            }

            ticksSinceExpected++;
            if (ticksSinceExpected > VISIBILITY_TIMEOUT_TICKS) {
                throw new AssertionError("Timed out waiting for vanilla Java client bridge-player lifecycle");
            }

            boolean infoPresent = minecraft.getConnection().getPlayerInfo(expected.uuid()) != null;
            var remote = minecraft.level.players().stream()
                    .filter(player -> expected.uuid().equals(player.getUUID()))
                    .findFirst()
                    .orElse(null);

            if (!visible) {
                if (!infoPresent || remote == null) return;
                if (!expected.name().equals(remote.getGameProfile().name())) {
                    throw new AssertionError("ClientLevel bridge-player profile name mismatch");
                }
                visible = true;
                ObserverE2eCommon.marker(
                        "target-bridge-player-visible.txt",
                        "uuid=" + expected.uuid() + "\nname=" + expected.name()
                                + "\nproof=player-info+client-level-entity\n"
                );
                return;
            }

            if (!Files.isRegularFile(ObserverE2eCommon.resultsDir()
                    .resolve("server-bridge-player-release-requested.txt"))) {
                return;
            }
            if (infoPresent || remote != null) return;

            ObserverE2eCommon.marker(
                    "target-bridge-player-removed.txt",
                    "uuid=" + expected.uuid() + "\nproof=player-info+client-level-entity-removed\n"
            );
            finished = true;
        } catch (Throwable failure) {
            finished = true;
            ObserverE2eCommon.fail("target-bridge-admission", failure.toString());
        }
    }

    private static Identity readIdentity(Path marker) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(marker)) {
            properties.load(reader);
        }
        String uuidText = properties.getProperty("uuid");
        String name = properties.getProperty("name");
        if (uuidText == null || name == null || !name.matches("[a-z0-9_]{1,16}")) {
            throw new IOException("Invalid bridge admission identity marker");
        }
        return new Identity(UUID.fromString(uuidText), name);
    }

    private record Identity(UUID uuid, String name) {}
}
