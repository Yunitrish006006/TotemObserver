package dev.totem.observer.bridge;

import dev.totem.observer.TotemObserver;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Dedicated-server lifecycle; opt-in transport experiment, independent of /observeui. */
public final class ObserverBridgeLifecycle {
    private static ObserverBridgeServer bridge;
    private ObserverBridgeLifecycle() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!server.isDedicatedServer() || !Boolean.getBoolean("totem.observer.bridge.enabled")) return;
            try {
                int port = Integer.parseInt(System.getProperty("totem.observer.bridge.port", "25580"));
                if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
                var file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                        .resolve("totem-observer/accounts-v1.properties");
                var accounts = new ObserverAccountService(new ObserverAccountStore(file),
                        Boolean.getBoolean("totem.observer.bridge.registration"));
                bridge = new ObserverBridgeServer(accounts);
                int bound = bridge.start(port, System.getProperty("totem.observer.bridge.origin", "http://localhost:8080"));
                TotemObserver.LOGGER.info("Observer bootstrap bridge listening on loopback port {}; gameplay unavailable", bound);
            } catch (Exception failure) {
                if (bridge != null) bridge.close();
                bridge = null;
                TotemObserver.LOGGER.error("Observer bootstrap bridge could not start; check its local configuration and port availability");
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (bridge != null) bridge.close();
            bridge = null;
        });
    }
}
