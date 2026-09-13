package dev.totem.observer.bridge;

import java.util.Map;
import java.util.Set;

/** Intent only: the server selects the target from its current player state. */
record ObserverBlockUseRequest(long seq, long sessionEpoch, int subscriptionId, int revision, String dimension) {
    static final int PROTOCOL = 1;
    static final Set<String> KEYS = Set.of("type", "protocol", "seq", "sessionEpoch",
            "subscriptionId", "revision", "dimension");

    static ObserverBlockUseRequest from(Map<String, String> message) {
        if (!message.keySet().equals(KEYS) || !"world_block_use".equals(message.get("type"))
                || !"1".equals(message.get("protocol"))) throw new IllegalArgumentException("Block use schema");
        long seq = Long.parseLong(message.get("seq"));
        long epoch = Long.parseLong(message.get("sessionEpoch"));
        int subscription = Integer.parseInt(message.get("subscriptionId"));
        int revision = Integer.parseInt(message.get("revision"));
        String dimension = message.get("dimension");
        if (seq < 0 || seq > 999_999_999L || epoch <= 0 || epoch > ObserverPlaySessionService.MAX_JSON_SAFE_EPOCH
                || subscription <= 0 || revision <= 0 || dimension.length() > 128
                || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Block use range");
        }
        return new ObserverBlockUseRequest(seq, epoch, subscription, revision, dimension);
    }
}
