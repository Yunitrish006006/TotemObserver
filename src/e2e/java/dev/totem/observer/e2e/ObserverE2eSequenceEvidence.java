package dev.totem.observer.e2e;

import dev.totem.observer.client.ObserverNativeClient;

/** Client-only E2E evidence access for the shared semantic sequence gate. */
final class ObserverE2eSequenceEvidence {
    private ObserverE2eSequenceEvidence() {
    }

    static long accepted(String familyId) {
        return ObserverNativeClient.acceptedSemanticSequence(familyId);
    }
}
