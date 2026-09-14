package dev.totem.observer.bridge;

import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** One admission-owned operation. Public only for the immediate pre-game-mode mixin hook. */
public final class ObserverDestroyOperation implements AutoCloseable {
    private static final ConcurrentHashMap<ServerPlayer, ObserverDestroyOperation> OWNED = new ConcurrentHashMap<>();
    private static final long MAX_AGE = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long HELD_LEASE = TimeUnit.MILLISECONDS.toNanos(500);
    enum Action { START, HOLD, CANCEL }
    record Snapshot(long operation, ObserverDestroyDriver.Result result) {}

    private final ServerPlayer player;
    private final long operation;
    private final BooleanSupplier authorized;
    private final LongSupplier clock;
    private final ObserverDestroyDriver driver;
    private long heldAt;
    private boolean dirty, closed, failed;
    private ObserverDestroyDriver.Result result;

    ObserverDestroyOperation(ServerPlayer player, long operation, long receivedNanos,
                             BooleanSupplier authorized, LongSupplier clock) {
        this.player = Objects.requireNonNull(player);
        this.operation = operation;
        this.authorized = Objects.requireNonNull(authorized);
        this.clock = Objects.requireNonNull(clock);
        driver = new ObserverDestroyDriver(player, authorized);
        thread();
        if (operation < 0 || operation > 999_999_999L || !fresh(receivedNanos, MAX_AGE)
                || !authorized.getAsBoolean()) throw new IllegalArgumentException("Invalid mining operation");
        heldAt = receivedNanos;
        if (OWNED.putIfAbsent(player, this) != null) throw new IllegalStateException("Mining operation already owned");
    }

    /** Owner must retain this object before START can mutate vanilla state. */
    Snapshot start() {
        thread();
        if (closed || failed || result != null) throw new IllegalStateException("Mining operation already started");
        if (!fresh(heldAt, MAX_AGE)) {
            update(new ObserverDestroyDriver.Result(ObserverDestroyDriver.Outcome.DENIED, null, 0, false));
            return snapshot();
        }
        try { update(driver.start()); }
        catch (Throwable failure) {
            failed = true;
            dirty = true;
            try { update(driver.cancel()); }
            catch (Throwable cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
        return snapshot();
    }

    Snapshot hold(long expectedOperation, long receivedNanos) {
        check(expectedOperation);
        if (active()) {
            // A new heartbeat must not revive an already expired lease.
            if (!authorized.getAsBoolean() || !fresh(heldAt, HELD_LEASE) || !fresh(receivedNanos, MAX_AGE)) {
                update(driver.cancel());
            } else {
                heldAt = receivedNanos;
            }
        }
        return snapshot();
    }

    Snapshot cancel(long expectedOperation) {
        check(expectedOperation);
        if (active()) update(driver.cancel());
        return snapshot();
    }

    Snapshot snapshot() { thread(); return new Snapshot(operation, result); }

    void cancelCurrent() {
        thread();
        if (active()) update(driver.cancel());
    }

    void removed() {
        thread();
        if (player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player && !player.isRemoved()) {
            throw new IllegalStateException("Mining player still admitted");
        }
        closed = true;
        OWNED.remove(player, this);
    }

    /** Called immediately before vanilla gameMode.tick; false suppresses that player's tick on failure. */
    public static boolean beforeVanillaTick(ServerPlayer player) {
        var owner = OWNED.get(player);
        if (owner == null) return true;
        owner.thread();
        if (owner.failed) return false;
        if (!owner.active()) return true;
        try {
            owner.update(owner.driver.tick(() -> owner.fresh(owner.heldAt, HELD_LEASE)));
            return true;
        } catch (Throwable failure) {
            owner.failed = true;
            // The driver has already attempted cancellation. Never continue vanilla mining after an exception.
            try { owner.update(owner.driver.cancel()); }
            catch (Throwable cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            dev.totem.observer.TotemObserver.LOGGER.warn("Observer mining tick failed", failure);
            return false;
        }
    }

    private void update(ObserverDestroyDriver.Result next) {
        dirty |= next.worldMayHaveChanged();
        result = new ObserverDestroyDriver.Result(next.outcome(), next.target(), next.progress(), dirty);
    }
    private boolean active() { return !closed && result != null && result.outcome() == ObserverDestroyDriver.Outcome.ACTIVE; }
    private boolean fresh(long at, long maxAge) {
        long age = clock.getAsLong() - at;
        return age >= 0 && age <= maxAge;
    }
    private void check(long expectedOperation) {
        thread();
        if (closed || failed || operation != expectedOperation) throw new IllegalArgumentException("Stale mining operation");
    }
    private void thread() {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Mining owner requires server thread");
    }
    @Override public void close() {
        thread();
        if (closed) return;
        // Keep the lookup if cleanup throws; the hook must not expose uncleared vanilla state.
        failed = true;
        update(driver.cancel());
        closed = true;
        OWNED.remove(player, this);
    }
}
