package dev.totem.observer.bridge;

import java.util.Map;
import java.util.Set;

/** No slot means read current hotbar; a slot selects only an existing position. */
record ObserverHotbarRequest(long seq, long sessionEpoch, int subscriptionId, int revision,
                             String dimension, Integer slot) {
    static final int PROTOCOL = 1;
    static final Set<String> READ_KEYS = Set.of("type", "protocol", "seq", "sessionEpoch", "subscriptionId", "revision", "dimension");
    static final Set<String> SELECT_KEYS = Set.of("type", "protocol", "seq", "sessionEpoch", "subscriptionId", "revision", "dimension", "slot");
    static ObserverHotbarRequest from(Map<String, String> message) {
        if (!(message.keySet().equals(READ_KEYS) || message.keySet().equals(SELECT_KEYS))
                || !"world_hotbar".equals(message.get("type")) || !"1".equals(message.get("protocol"))) {
            throw new IllegalArgumentException("Hotbar schema");
        }
        long seq = Long.parseLong(message.get("seq")), epoch = Long.parseLong(message.get("sessionEpoch"));
        int subscription = Integer.parseInt(message.get("subscriptionId")), revision = Integer.parseInt(message.get("revision"));
        String dimension = message.get("dimension");
        Integer slot = message.containsKey("slot") ? Integer.valueOf(message.get("slot")) : null;
        if (seq < 0 || seq > 999_999_999L || epoch <= 0 || epoch > ObserverPlaySessionService.MAX_JSON_SAFE_EPOCH
                || subscription <= 0 || revision <= 0 || dimension.length() > 128
                || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || (slot != null && (slot < 0 || slot > 8))) {
            throw new IllegalArgumentException("Hotbar range");
        }
        return new ObserverHotbarRequest(seq, epoch, subscription, revision, dimension, slot);
    }
}
