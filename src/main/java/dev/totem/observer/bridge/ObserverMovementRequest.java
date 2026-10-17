package dev.totem.observer.bridge;

import java.util.Map;
import java.util.Set;

/** Version 1 uses bounded integer input axes and hundredths of a degree on the wire. */
record ObserverMovementRequest(long seq, long sessionEpoch, int subscriptionId, int revision,
                               String dimension, ObserverMovementIntent intent) {
    static final int PROTOCOL = 1;
    static final Set<String> KEYS = Set.of("type", "seq", "protocol", "sessionEpoch",
            "subscriptionId", "revision", "dimension", "strafe", "forward", "yaw", "pitch", "jump");

    static ObserverMovementRequest from(Map<String, String> message) {
        if (!message.keySet().equals(KEYS) || !"world_movement".equals(message.get("type"))
                || !"1".equals(message.get("protocol"))) throw new IllegalArgumentException("Movement schema");
        long seq = Long.parseLong(message.get("seq"));
        long epoch = Long.parseLong(message.get("sessionEpoch"));
        int subscription = Integer.parseInt(message.get("subscriptionId"));
        int revision = Integer.parseInt(message.get("revision"));
        int strafe = Integer.parseInt(message.get("strafe"));
        int forward = Integer.parseInt(message.get("forward"));
        int yaw = Integer.parseInt(message.get("yaw"));
        int pitch = Integer.parseInt(message.get("pitch"));
        int jump = Integer.parseInt(message.get("jump"));
        String dimension = message.get("dimension");
        if (seq < 0 || seq > 999_999_999L || epoch <= 0 || epoch > ObserverPlaySessionService.MAX_JSON_SAFE_EPOCH
                || subscription <= 0 || revision <= 0 || strafe < -1 || strafe > 1 || forward < -1 || forward > 1
                || yaw < -36_000 || yaw > 36_000 || pitch < -18_000 || pitch > 18_000 || jump < 0 || jump > 1
                || dimension.length() > 128 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Movement range");
        }
        return new ObserverMovementRequest(seq, epoch, subscription, revision, dimension,
                new ObserverMovementIntent(strafe, forward, yaw / 100f, pitch / 100f, jump == 1));
    }
}
