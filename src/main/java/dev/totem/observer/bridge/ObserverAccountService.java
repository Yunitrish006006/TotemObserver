package dev.totem.observer.bridge;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One bounded password worker and one live connection per account; no bearer/resume tokens. */
public final class ObserverAccountService implements AutoCloseable {
    public record Session(String account, UUID id) {}
    private record Active(Session session, Runnable revoke) {}
    private final ObserverAccountStore store;
    private final boolean registration;
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                var thread = new Thread(runnable, "observer-account-worker"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, Active> active = new HashMap<>();
    private long window = System.nanoTime();
    private int attempts;
    private boolean closed;

    public ObserverAccountService(ObserverAccountStore store, boolean registration) { this.store = store; this.registration = registration; }
    public boolean registrationAllowed() { return registration; }

    /** Callback runs on worker; caller must marshal responses onto its channel event loop. */
    public synchronized boolean authenticate(String account, char[] password, boolean create, Consumer<Boolean> result) {
        long now = System.nanoTime();
        if (now - window >= TimeUnit.MINUTES.toNanos(1)) { window = now; attempts = 0; }
        if (closed || ++attempts > 12 || (create && !registration)) { Arrays.fill(password, '\0'); return false; }
        try {
            worker.execute(() -> {
                boolean valid = false;
                try {
                    boolean stopped;
                    synchronized (ObserverAccountService.this) { stopped = closed; }
                    if (!stopped) valid = create ? store.register(account, password) : store.verify(account, password);
                }
                catch (Exception ignored) { /* Fail closed without logging credentials or records. */ }
                finally { Arrays.fill(password, '\0'); }
                result.accept(valid);
            });
            return true;
        } catch (RejectedExecutionException failure) { Arrays.fill(password, '\0'); return false; }
    }

    public synchronized Session open(String account, Runnable revoke) {
        if (closed) return null;
        var session = new Session(account, UUID.randomUUID());
        var previous = active.put(account, new Active(session, revoke));
        if (previous != null) previous.revoke.run();
        return session;
    }
    public synchronized boolean valid(Session session) {
        var value = active.get(session.account());
        return !closed && value != null && value.session.equals(session);
    }
    public synchronized void release(Session session) {
        var value = active.get(session.account());
        if (value != null && value.session.equals(session)) active.remove(session.account());
    }
    @Override public void close() {
        synchronized (this) {
            closed = true;
            active.values().forEach(value -> value.revoke.run());
            active.clear();
            // Queued tasks skip store access, but still clear passwords in finally.
            worker.shutdown();
        }
        // Do not allow a restart to load a store while the previous writer is still active.
        boolean interrupted = false;
        try {
            while (!worker.isTerminated()) {
                try { worker.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
