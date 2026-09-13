package dev.totem.observer.bridge;

/** Bounded input only; coordinates, velocity and inventory are never client inputs. */
record ObserverMovementIntent(float strafe, float forward, float yaw, float pitch, boolean jump) {
    ObserverMovementIntent {
        if (!Float.isFinite(strafe) || !Float.isFinite(forward)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Non-finite movement input");
        }
        strafe = Math.clamp(strafe, -1f, 1f);
        forward = Math.clamp(forward, -1f, 1f);
        double length = Math.hypot(strafe, forward);
        if (length > 1) {
            strafe /= (float) length;
            forward /= (float) length;
        }
        yaw %= 360f;
        if (yaw >= 180) yaw -= 360;
        if (yaw < -180) yaw += 360;
        pitch = Math.clamp(pitch, -90f, 90f);
    }

    static ObserverMovementIntent idle(float yaw, float pitch) {
        return new ObserverMovementIntent(0, 0, yaw, pitch, false);
    }
}
