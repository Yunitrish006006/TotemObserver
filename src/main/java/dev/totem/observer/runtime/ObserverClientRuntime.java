package dev.totem.observer.runtime;

import dev.totem.observer.client.ObserverAdvancementsScreenClient;
import dev.totem.observer.client.ObserverBeaconScreenClient;
import dev.totem.observer.client.ObserverBrewingScreenClient;
import dev.totem.observer.client.ObserverCartographyScreenClient;
import dev.totem.observer.client.ObserverCrafterScreenClient;
import dev.totem.observer.client.ObserverGrindstoneScreenClient;
import dev.totem.observer.client.ObserverHorseScreenClient;
import dev.totem.observer.client.ObserverLoomScreenClient;
import dev.totem.observer.client.ObserverNativeAnvilScreenClient;
import dev.totem.observer.client.ObserverNativeBookScreenClient;
import dev.totem.observer.client.ObserverNativeClient;
import dev.totem.observer.client.ObserverNativeCraftingScreenClient;
import dev.totem.observer.client.ObserverNativeEnchantingScreenClient;
import dev.totem.observer.client.ObserverNativeHud;
import dev.totem.observer.client.ObserverNativeMerchantScreenClient;
import dev.totem.observer.client.ObserverNativeScreenClient;
import dev.totem.observer.client.ObserverOwnedScreenTransportClient;
import dev.totem.observer.client.ObserverSignScreenClient;
import dev.totem.observer.client.ObserverSmithingScreenClient;
import dev.totem.observer.client.ObserverStatsScreenClient;
import dev.totem.observer.client.ObserverStonecutterScreenClient;
import dev.totem.observer.client.ObserverUiClient;

/** Single client bootstrap seam for the Observer subsystem. */
public final class ObserverClientRuntime {
    private ObserverClientRuntime() {}

    public static void register() {
        ObserverUiClient.register();
        ObserverNativeClient.register();
        ObserverOwnedScreenTransportClient.register();
        ObserverNativeScreenClient.register();
        ObserverNativeBookScreenClient.register();
        ObserverNativeCraftingScreenClient.register();
        ObserverNativeMerchantScreenClient.register();
        ObserverNativeAnvilScreenClient.register();
        ObserverNativeEnchantingScreenClient.register();
        ObserverBrewingScreenClient.register();
        ObserverSmithingScreenClient.register();
        ObserverStonecutterScreenClient.register();
        ObserverGrindstoneScreenClient.register();
        ObserverLoomScreenClient.register();
        ObserverCartographyScreenClient.register();
        ObserverBeaconScreenClient.register();
        ObserverSignScreenClient.register();
        ObserverCrafterScreenClient.register();
        ObserverAdvancementsScreenClient.register();
        ObserverStatsScreenClient.register();
        ObserverHorseScreenClient.register();
        ObserverNativeHud.register();
    }
}
