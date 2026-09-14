package dev.totem.observer.bridge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Internal movement foundation, not yet connected to browser requests.
 * The admission owner must invoke at most once per server tick and supply a
 * current authorization check. Vanilla doTick owns gravity, collision and jump.
 */
final class ObserverMovementDriver {
    private final ServerPlayer player;
    private final BooleanSupplier authorized;
    private int lastTick = Integer.MIN_VALUE;

    ObserverMovementDriver(ServerPlayer player, BooleanSupplier authorized) {
        this.player = Objects.requireNonNull(player);
        this.authorized = Objects.requireNonNull(authorized);
    }

    boolean tick(ObserverMovementIntent intent) {
        var server = player.level().getServer();
        if (!server.isSameThread()) throw new IllegalStateException("Movement requires server thread");
        Objects.requireNonNull(intent);
        int tick = server.getTickCount();
        if (tick == lastTick || !authorized.getAsBoolean() || !player.isAlive()
                || player.isRemoved() || player.isPassenger() || player.isSleeping()
                || player.isSpectator() || player.getAbilities().flying
                || player.isFallFlying() || player.isChangingDimension()
                || player.touchingUnloadedChunk()) return false;
        lastTick = tick;
        player.setYRot(intent.yaw());
        player.setXRot(intent.pitch());
        player.setSpeed((float) player.getAttributeValue(Attributes.MOVEMENT_SPEED));
        player.xxa = intent.strafe();
        player.zza = intent.forward();
        player.setJumping(intent.jump());
        try {
            // Unlike the vanilla remote-client listener, do not snap back to
            // last client XYZ after doTick: this server owns movement results.
            var before = player.position();
            player.doTick();
            var movement = player.position().subtract(before);
            // Entity.move skips local fall accounting for client-authoritative
            // Player instances; reuse the listener's post-movement accounting
            // with server-derived displacement and ground contact.
            player.doCheckFallDamage(movement.x, movement.y, movement.z, player.onGround());
            player.checkMovementStatistics(movement.x, movement.y, movement.z);
            player.setKnownMovement(movement);
            player.level().getChunkSource().move(player);
            player.connection.resetPosition();
            return true;
        } finally {
            // No latched input survives a call. Future transport must implement
            // a bounded dead-man window while continuing idle gravity ticks.
            player.xxa = 0;
            player.zza = 0;
            player.setJumping(false);
        }
    }
}
