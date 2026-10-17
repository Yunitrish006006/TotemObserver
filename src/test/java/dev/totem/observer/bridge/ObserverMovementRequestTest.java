package dev.totem.observer.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObserverMovementRequestTest {
    private static final String REQUEST = """
            {"type":"world_movement","protocol":1,"seq":7,"sessionEpoch":9007199254740991,
            "subscriptionId":1,"revision":1,"dimension":"minecraft:overworld",
            "strafe":1,"forward":1,"yaw":27000,"pitch":12000,"jump":1}
            """;

    @Test void exactBoundedIntentSchema() throws Exception {
        var request = ObserverMovementRequest.from(ObserverAuthenticatedExchange.parse(REQUEST));
        assertEquals(7, request.seq());
        assertEquals(9007199254740991L, request.sessionEpoch());
        assertEquals(-90, request.intent().yaw());
        assertEquals(90, request.intent().pitch());
        assertEquals(1, Math.hypot(request.intent().strafe(), request.intent().forward()), 0.00001);
    }

    @Test void malformedAndUntrustedInputFailClosed() {
        for (String changed : new String[] {
                REQUEST.replace("\"seq\":7", "\"seq\":7,\"x\":20"),
                REQUEST.replace("\"seq\":7", "\"seq\":7,\"seq\":8"),
                REQUEST.replace("\"seq\":7", "\"seq\":\"7\""),
                REQUEST.replace("\"seq\":7", "\"seq\":7.0"),
                REQUEST.replace("\"seq\":7", "\"seq\":7e0"),
                REQUEST.replace("\"protocol\":1", "\"protocol\":2"),
                REQUEST.replace("9007199254740991", "9007199254740992"),
                REQUEST.replace("\"strafe\":1", "\"strafe\":2"),
                REQUEST.replace("\"jump\":1", "\"jump\":true"),
                REQUEST.replace("\"yaw\":27000", "\"yaw\":NaN"),
                REQUEST.replace("\"yaw\":27000", "\"yaw\":36001"),
                REQUEST.replace("\"pitch\":12000", "\"pitch\":18001"),
                REQUEST.replace("minecraft:overworld", "minecraft:over world"),
                REQUEST.replace("\"revision\":1", "\"revision\":0"),
                REQUEST.replace("\"strafe\":1,", ""),
                REQUEST + "{}",
                " ".repeat(1025) + REQUEST,
        }) {
            assertThrows(Exception.class, () -> ObserverMovementRequest.from(ObserverAuthenticatedExchange.parse(changed)), changed);
        }
    }

    @Test void legacyParserStillRejectsRecursiveOrNoncanonicalValues() {
        for (String input : new String[] {
                "{\"type\":\"ping\",\"seq\":-0}", "{\"type\":\"ping\",\"seq\":01}",
                "{\"type\":\"ping\",\"seq\":[]}", "{\"type\":\"ping\",\"seq\":{}}",
        }) assertThrows(Exception.class, () -> ObserverAuthenticatedExchange.parse(input));
    }
}
