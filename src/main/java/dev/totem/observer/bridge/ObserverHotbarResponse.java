package dev.totem.observer.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

final class ObserverHotbarResponse {
    private ObserverHotbarResponse() {}
    static String encode(ObserverHotbarRequest request, ObserverPlayerAdmissionService.HotbarResult result) {
        var json = new JsonObject();
        json.addProperty("type", "world_hotbar");
        json.addProperty("protocol", 1);
        json.addProperty("seq", request.seq());
        json.addProperty("sessionEpoch", request.sessionEpoch());
        json.addProperty("subscriptionId", request.subscriptionId());
        json.addProperty("revision", request.revision());
        json.addProperty("dimension", request.dimension());
        var state = result.state();
        json.addProperty("outcome", state == null ? "denied" : request.slot() == null ? "snapshot" : "selected");
        if (state == null) {
            json.add("selected", JsonNull.INSTANCE); json.add("slots", JsonNull.INSTANCE);
        } else {
            json.addProperty("selected", state.selected());
            var slots = new JsonArray();
            for (var stack : state.slots()) {
                var item = new JsonObject();
                item.addProperty("item", stack.item()); item.addProperty("count", stack.count()); slots.add(item);
            }
            json.add("slots", slots);
        }
        String encoded = json.toString();
        if (encoded.length() > 4096) throw new IllegalStateException("Hotbar response too large");
        return encoded;
    }
}
