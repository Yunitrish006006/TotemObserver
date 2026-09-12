package dev.totem.observer.bridge;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Server-derived external player identity. The browser never chooses UUID or profile name. */
public record ObserverPlayerIdentity(String account, UUID uuid, String profileName) {
    private static final String UUID_NAMESPACE = "totem-observer:external-player:";

    public ObserverPlayerIdentity {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(profileName, "profileName");
        if (!ObserverAccountStore.validName(account)) throw new IllegalArgumentException("Invalid account");
        if (!profileName.matches("[a-z0-9_]{1,16}")) throw new IllegalArgumentException("Invalid profile name");
    }

    public static ObserverPlayerIdentity forAccount(String account) {
        if (!ObserverAccountStore.validName(account)) throw new IllegalArgumentException("Invalid account");
        UUID uuid = UUID.nameUUIDFromBytes((UUID_NAMESPACE + account).getBytes(StandardCharsets.UTF_8));
        String compact = uuid.toString().replace("-", "");
        return new ObserverPlayerIdentity(account, uuid, "obs_" + compact.substring(0, 12));
    }
}
