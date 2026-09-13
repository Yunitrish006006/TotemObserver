package dev.totem.observer.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import java.util.Locale;

/** Bounded wire projection of one atomic server-thread selection capture. */
final class ObserverTargetOutlineResponse {
    private ObserverTargetOutlineResponse() {}

    static String encode(ObserverTargetOutlineRequest request, ObserverPlayerAdmissionService.TargetOutlineResult result) {
        var json = new JsonObject();
        json.addProperty("type", "world_target_outline");
        json.addProperty("protocol", 1);
        json.addProperty("seq", request.seq());
        json.addProperty("sessionEpoch", request.sessionEpoch());
        json.addProperty("subscriptionId", request.subscriptionId());
        json.addProperty("revision", request.revision());
        json.addProperty("dimension", result.state().dimension());
        json.addProperty("registryFingerprint", request.registryFingerprint());
        json.addProperty("serverTick", result.serverTick());
        json.addProperty("x", result.state().x());
        json.addProperty("y", result.state().y());
        json.addProperty("z", result.state().z());
        json.addProperty("yaw", result.state().yaw());
        json.addProperty("pitch", result.state().pitch());
        json.addProperty("eyeY", result.eyeY());
        var target = result.target();
        if (target == null) json.add("target", JsonNull.INSTANCE);
        else {
            var hit = new JsonObject();
            hit.addProperty("x", target.pos().getX());
            hit.addProperty("y", target.pos().getY());
            hit.addProperty("z", target.pos().getZ());
            hit.addProperty("face", target.face().name().toLowerCase(Locale.ROOT));
            hit.addProperty("rawId", target.rawId());
            var boxes = new JsonArray();
            for (var box : target.boxes()) {
                var bounds = new JsonArray();
                bounds.add(box.minX()); bounds.add(box.minY()); bounds.add(box.minZ());
                bounds.add(box.maxX()); bounds.add(box.maxY()); bounds.add(box.maxZ());
                boxes.add(bounds);
            }
            hit.add("boxes", boxes); json.add("target", hit);
        }
        String encoded = json.toString();
        if (encoded.length() > 8192) throw new IllegalStateException("Outline response too large");
        return encoded;
    }
}
