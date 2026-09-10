package dev.totem.observer;

import dev.totem.observer.runtime.ObserverClientRuntime;
import net.fabricmc.api.ClientModInitializer;

public final class TotemObserverClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ObserverClientRuntime.register();
    }
}
