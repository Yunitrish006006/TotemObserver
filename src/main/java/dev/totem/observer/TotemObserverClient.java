package dev.totem.observer;

import dev.totem.observer.observer.ObserverClientRuntime;
import net.fabricmc.api.ClientModInitializer;

public final class TotemObserverClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ObserverClientRuntime.register();
    }
}
