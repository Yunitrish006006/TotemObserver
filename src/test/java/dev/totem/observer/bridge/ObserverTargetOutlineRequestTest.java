package dev.totem.observer.bridge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObserverTargetOutlineRequestTest {
    private static final String REQUEST = """
            {"type":"world_target_outline","protocol":1,"seq":7,"sessionEpoch":42,
             "subscriptionId":1,"revision":1,"dimension":"minecraft:overworld","registryFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
            """;

    @Test void intentHasNoClientTarget() throws Exception {
        var request = ObserverTargetOutlineRequest.from(ObserverAuthenticatedExchange.parse(REQUEST));
        assertEquals(7, request.seq());
        assertEquals(42, request.sessionEpoch());
        assertEquals("minecraft:overworld", request.dimension());
    }

    @Test void malformedAndUntrustedInputFailClosed() {
        for (String changed : new String[] {
                REQUEST.replace("\"seq\":7", "\"seq\":7,\"x\":20"),
                REQUEST.replace("\"seq\":7", "\"seq\":7,\"seq\":8"),
                REQUEST.replace("\"seq\":7", "\"seq\":\"7\""),
                REQUEST.replace("\"seq\":7", "\"seq\":7.0"),
                REQUEST.replace("\"seq\":7", "\"seq\":7e0"),
                REQUEST.replace("\"seq\":7", "\"seq\":-0"),
                REQUEST.replace("\"protocol\":1", "\"protocol\":2"),
                REQUEST.replace("\"sessionEpoch\":42", "\"sessionEpoch\":9007199254740992"),
                REQUEST.replace("minecraft:overworld", "minecraft:over world"),
                REQUEST.replace("\"revision\":1", "\"revision\":0"),
                REQUEST.replace("\"subscriptionId\":1", "\"subscriptionId\":0"),
                REQUEST.replace("\"revision\":1,", ""),
                REQUEST.replace("\"dimension\":\"minecraft:overworld\"", "\"dimension\":{}"),
                REQUEST.replace("a".repeat(64), "A".repeat(64)), REQUEST.replace("a".repeat(64), "a".repeat(63)),
                REQUEST + "{}", " ".repeat(1025) + REQUEST,
        }) assertThrows(Exception.class,
                () -> ObserverTargetOutlineRequest.from(ObserverAuthenticatedExchange.parse(changed)), changed);
    }
}
