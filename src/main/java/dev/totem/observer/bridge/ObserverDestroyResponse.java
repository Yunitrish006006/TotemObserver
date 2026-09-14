package dev.totem.observer.bridge;

import com.google.gson.JsonObject;
import java.util.Locale;

final class ObserverDestroyResponse {
    private ObserverDestroyResponse() {}
    static String encode(ObserverDestroyRequest request, ObserverDestroyOperation.Snapshot snapshot) {
        var result = snapshot.result();
        if (snapshot.operation() != request.operation() || result == null
                || result.outcome() == ObserverDestroyDriver.Outcome.IDLE
                || !Float.isFinite(result.progress()) || result.progress() < 0 || result.progress() > 1) {
            throw new IllegalArgumentException("Invalid mining result");
        }
        var json = new JsonObject();
        json.addProperty("type", "world_block_destroy");
        json.addProperty("protocol", 1);
        json.addProperty("seq", request.seq());
        json.addProperty("sessionEpoch", request.sessionEpoch());
        json.addProperty("subscriptionId", request.subscriptionId());
        json.addProperty("revision", request.revision());
        json.addProperty("dimension", request.dimension());
        json.addProperty("operation", request.operation());
        json.addProperty("action", request.action().name().toLowerCase(Locale.ROOT));
        json.addProperty("outcome", result.outcome().name().toLowerCase(Locale.ROOT));
        json.addProperty("progress", result.progress());
        json.addProperty("worldMayHaveChanged", result.worldMayHaveChanged());
        // Even a no-mutation terminal must consume its owner before another START.
        json.addProperty("refreshRequired", result.outcome() != ObserverDestroyDriver.Outcome.ACTIVE);
        String encoded = json.toString();
        if (encoded.length() > 1024) throw new IllegalStateException("Mining response too large");
        return encoded;
    }
}
