package dev.totem.observer.bridge;

import java.util.Map;
import java.util.Set;

/** Read-only selection request; never accepts a client target or camera. */
record ObserverTargetOutlineRequest(long seq, long sessionEpoch, int subscriptionId, int revision,
                                    String dimension, String registryFingerprint) {
    static final int PROTOCOL = 1;
    static final Set<String> KEYS = Set.of("type", "protocol", "seq", "sessionEpoch",
            "subscriptionId", "revision", "dimension", "registryFingerprint");

    static ObserverTargetOutlineRequest from(Map<String, String> message) {
        if (!message.keySet().equals(KEYS) || !"world_target_outline".equals(message.get("type"))
                || !"1".equals(message.get("protocol"))) throw new IllegalArgumentException("Outline schema");
        long seq = Long.parseLong(message.get("seq"));
        long epoch = Long.parseLong(message.get("sessionEpoch"));
        int subscription = Integer.parseInt(message.get("subscriptionId"));
        int revision = Integer.parseInt(message.get("revision"));
        String dimension = message.get("dimension");
        String fingerprint = message.get("registryFingerprint");
        if (seq < 0 || seq > 999_999_999L || epoch <= 0 || epoch > ObserverPlaySessionService.MAX_JSON_SAFE_EPOCH
                || subscription <= 0 || revision <= 0 || dimension.length() > 128
                || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Outline range");
        }
        return new ObserverTargetOutlineRequest(seq, epoch, subscription, revision, dimension, fingerprint);
    }
}
