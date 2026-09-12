package dev.totem.observer.bridge;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated play-control session boundary for the future world admission path.
 * This service reserves a server-derived player identity and epoch only; it does not
 * create a Minecraft player or grant gameplay authority yet.
 */
public final class ObserverPlaySessionService implements AutoCloseable {
    public static final int PROTOCOL = 1;
    static final long MAX_JSON_SAFE_EPOCH = (1L << 53) - 1;

    public record Session(String account, UUID authenticationSessionId, long epoch,
                          ObserverPlayerIdentity identity) {
        public Session {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(authenticationSessionId, "authenticationSessionId");
            Objects.requireNonNull(identity, "identity");
            if (!account.equals(identity.account())) throw new IllegalArgumentException("Identity account mismatch");
            if (epoch <= 0 || epoch > MAX_JSON_SAFE_EPOCH) throw new IllegalArgumentException("Invalid epoch");
        }
    }

    private final Map<String, Session> active = new HashMap<>();
    private boolean closed;

    public synchronized Session open(ObserverAccountService.Session authentication) {
        Objects.requireNonNull(authentication, "authentication");
        if (closed) return null;
        var identity = ObserverPlayerIdentity.forAccount(authentication.account());
        long epoch = (authentication.id().getMostSignificantBits() ^ authentication.id().getLeastSignificantBits())
                & MAX_JSON_SAFE_EPOCH;
        if (epoch == 0) epoch = 1;
        var session = new Session(authentication.account(), authentication.id(), epoch, identity);
        active.put(authentication.account(), session);
        return session;
    }

    public synchronized boolean valid(ObserverAccountService.Session authentication, Session session) {
        if (closed || authentication == null || session == null) return false;
        var current = active.get(authentication.account());
        return current != null && current.equals(session)
                && session.authenticationSessionId().equals(authentication.id());
    }

    public synchronized void release(Session session) {
        if (session == null) return;
        var current = active.get(session.account());
        if (session.equals(current)) active.remove(session.account());
    }

    @Override public synchronized void close() {
        closed = true;
        active.clear();
    }
}
