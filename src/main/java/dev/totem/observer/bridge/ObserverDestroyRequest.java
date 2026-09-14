package dev.totem.observer.bridge;

import java.util.Map;
import java.util.Set;

/** An operation identity and held intent, never a client target or claimed progress. */
record ObserverDestroyRequest(long seq, long sessionEpoch, int subscriptionId, int revision,
                              String dimension, long operation, ObserverDestroyOperation.Action action) {
    static final int PROTOCOL = 1;
    static final Set<String> KEYS = Set.of("type", "protocol", "seq", "sessionEpoch", "subscriptionId", "revision",
            "dimension", "operation", "action");

    static ObserverDestroyRequest from(Map<String, String> message) {
        if (!message.keySet().equals(KEYS) || !"world_block_destroy".equals(message.get("type"))
                || !"1".equals(message.get("protocol"))) throw new IllegalArgumentException("Mining schema");
        long seq = Long.parseLong(message.get("seq")), epoch = Long.parseLong(message.get("sessionEpoch"));
        int subscription = Integer.parseInt(message.get("subscriptionId")), revision = Integer.parseInt(message.get("revision"));
        long operation = Long.parseLong(message.get("operation"));
        String dimension = message.get("dimension");
        var action = switch (message.get("action")) {
            case "start" -> ObserverDestroyOperation.Action.START;
            case "hold" -> ObserverDestroyOperation.Action.HOLD;
            case "cancel" -> ObserverDestroyOperation.Action.CANCEL;
            default -> throw new IllegalArgumentException("Unknown mining action");
        };
        if (seq < 0 || seq > 999_999_999L || epoch <= 0 || epoch > ObserverPlaySessionService.MAX_JSON_SAFE_EPOCH
                || subscription <= 0 || revision <= 0 || operation < 0 || operation > seq
                || (action == ObserverDestroyOperation.Action.START && operation != seq)
                || (action != ObserverDestroyOperation.Action.START && operation >= seq)
                || dimension.length() > 128 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Mining range");
        }
        return new ObserverDestroyRequest(seq, epoch, subscription, revision, dimension, operation, action);
    }
}
