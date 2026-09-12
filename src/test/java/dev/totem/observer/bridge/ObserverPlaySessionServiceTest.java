package dev.totem.observer.bridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ObserverPlaySessionServiceTest {
    @Test void identityIsStableNamespacedAndServerDerived() {
        var first = ObserverPlayerIdentity.forAccount("alice");
        var second = ObserverPlayerIdentity.forAccount("alice");
        var other = ObserverPlayerIdentity.forAccount("bob");

        assertEquals(first, second);
        assertNotEquals(first.uuid(), other.uuid());
        assertNotEquals(UUID.nameUUIDFromBytes("alice".getBytes(StandardCharsets.UTF_8)), first.uuid());
        assertTrue(first.profileName().matches("obs_[0-9a-f]{12}"));
        assertEquals(16, first.profileName().length());
        assertThrows(IllegalArgumentException.class, () -> ObserverPlayerIdentity.forAccount("Alice"));
    }

    @Test void playSessionIsBoundToTheCurrentAuthenticationSession() {
        try (var service = new ObserverPlaySessionService()) {
            var authenticationA = new ObserverAccountService.Session("alice",
                    UUID.fromString("11111111-1111-1111-2222-222222222222"));
            var authenticationB = new ObserverAccountService.Session("alice",
                    UUID.fromString("33333333-3333-3333-4444-444444444444"));

            var first = service.open(authenticationA);
            assertNotNull(first);
            assertTrue(service.valid(authenticationA, first));

            var replacement = service.open(authenticationB);
            assertNotNull(replacement);
            assertFalse(service.valid(authenticationA, first));
            assertTrue(service.valid(authenticationB, replacement));
            assertEquals(first.identity(), replacement.identity());
            assertNotEquals(first.epoch(), replacement.epoch());
            assertTrue(replacement.epoch() <= ObserverPlaySessionService.MAX_JSON_SAFE_EPOCH);

            service.release(first);
            assertTrue(service.valid(authenticationB, replacement));
            service.release(replacement);
            assertFalse(service.valid(authenticationB, replacement));
        }
    }

    @Test void closedServiceCannotReserveAnotherIdentitySession() {
        var service = new ObserverPlaySessionService();
        var authentication = new ObserverAccountService.Session("alice", UUID.randomUUID());
        var session = service.open(authentication);
        assertNotNull(session);
        service.close();
        assertFalse(service.valid(authentication, session));
        assertNull(service.open(authentication));
    }
}
