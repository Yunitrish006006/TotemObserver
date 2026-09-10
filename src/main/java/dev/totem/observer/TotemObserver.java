package dev.totem.observer;

import dev.totem.observer.runtime.ObserverServerRuntime;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TotemObserver implements ModInitializer {
    public static final String MOD_ID = "totem-observer";
    /** Existing Observer v4 packet namespace; retained during repository extraction. */
    public static final String PROTOCOL_NAMESPACE = "totem-vanilla-tweaks";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ObserverServerRuntime.register();
        LOGGER.info("TotemObserver initialized");
    }
}
