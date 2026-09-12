package dev.totem.observer.runtime;

import dev.totem.core.api.v1.client.observer.ObserverScreenSnapshot;
import dev.totem.observer.network.ObserverOwnedProviderPolicy;
import dev.totem.observer.network.ObserverOwnedScreenCapability;
import dev.totem.observer.network.ObserverOwnedScreenPayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Session- and provider-identity-bound relay for owning-module semantic envelopes. */
public final class ObserverOwnedScreenRelayManager {
    private static final Map<UUID, Map<String, Long>> LAST = new HashMap<>();
    private static final Map<UUID, ScreenIdentity> OPEN = new HashMap<>();

    private ObserverOwnedScreenRelayManager() { }

    public static void accept(ServerPlayer target, ObserverOwnedScreenPayloads.State payload) {
        ObserverScreenSnapshot snapshot = payload.snapshot();
        if (!validState(payload)
                || !ObserverNativeSessionManager.ownedProviderAdvertises(
                        target, snapshot.familyId(), snapshot.protocolVersion())) {
            return;
        }
        UUID targetId = target.getUUID();
        long previous = LAST.computeIfAbsent(targetId, ignored -> new HashMap<>())
                .getOrDefault(snapshot.familyId(), -1L);
        if (snapshot.sequence() <= previous) return;

        var observerIds = ObserverNativeSessionManager.observerIdsForTarget(
                targetId, ObserverOwnedScreenCapability.CAPABILITY).stream()
                .filter(observerId -> {
                    ServerPlayer observer = target.level().getServer().getPlayerList().getPlayer(observerId);
                    return observer != null && ObserverNativeSessionManager.ownedProviderAdvertises(
                            observer, snapshot.familyId(), snapshot.protocolVersion());
                })
                .toList();
        if (observerIds.isEmpty()) return;

        LAST.get(targetId).put(snapshot.familyId(), snapshot.sequence());
        if (payload.open()) {
            OPEN.put(targetId, new ScreenIdentity(
                    snapshot.familyId(), snapshot.variant(), snapshot.protocolVersion()));
        } else {
            OPEN.remove(targetId);
        }
        var relay = new ObserverOwnedScreenPayloads.Relay(targetId, payload.open(), snapshot);
        for (UUID observerId : observerIds) {
            ServerPlayer observer = target.level().getServer().getPlayerList().getPlayer(observerId);
            if (observer != null && ObserverAccessPolicy.allows(observer, target)
                    && ServerPlayNetworking.canSend(observer, ObserverOwnedScreenPayloads.Relay.TYPE)) {
                if (payload.open()) ObserverNexusTerrainRelay.enqueue(target,observer,snapshot);
                ServerPlayNetworking.send(observer, relay);
            }
        }
    }

    public static void clearTarget(UUID targetId) {
        LAST.remove(targetId);
        OPEN.remove(targetId);
    }

    public static boolean matchesOpen(UUID targetId, String family, String variant, int protocol) {
        return new ScreenIdentity(family, variant, protocol).equals(OPEN.get(targetId));
    }

    static boolean validState(ObserverOwnedScreenPayloads.State payload) {
        if (payload == null || payload.snapshot() == null) return false;
        ObserverScreenSnapshot snapshot = payload.snapshot();
        var identity = new ObserverOwnedScreenPayloads.ProviderIdentity(
                snapshot.familyId(), snapshot.protocolVersion());
        if (!ObserverOwnedProviderPolicy.validIdentity(identity)) return false;
        return payload.open() || (snapshot.slots().isEmpty() && snapshot.data().length == 0
                && snapshot.metadata().isEmpty() && snapshot.ownerPayload().length == 0);
    }

    private record ScreenIdentity(String family, String variant, int protocol) { }
}
