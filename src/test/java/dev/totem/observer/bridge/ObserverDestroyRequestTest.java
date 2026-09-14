package dev.totem.observer.bridge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObserverDestroyRequestTest {
    private static final String REQUEST = """
            {"type":"world_block_destroy","protocol":1,"seq":7,"sessionEpoch":42,
             "subscriptionId":1,"revision":1,"dimension":"minecraft:overworld","operation":7,"action":"start"}
            """;

    @Test void startIdentityAndHeldIntentAreBounded() throws Exception {
        var start = ObserverDestroyRequest.from(ObserverAuthenticatedExchange.parse(REQUEST));
        assertEquals(7, start.operation());
        assertEquals(ObserverDestroyOperation.Action.START, start.action());
        for (String action : new String[]{"hold", "cancel"}) {
            var later = REQUEST.replace("\"seq\":7", "\"seq\":8").replace("\"start\"", "\"" + action + "\"");
            assertEquals(7, ObserverDestroyRequest.from(ObserverAuthenticatedExchange.parse(later)).operation());
        }
    }

    @Test void hostileSchemaAndOperationClaimsFailClosed() {
        for (String changed : new String[] {
                REQUEST.replace("\"seq\":7", "\"seq\":7,\"x\":20"),
                REQUEST.replace("\"operation\":7", "\"operation\":7,\"operation\":8"),
                REQUEST.replace("\"operation\":7", "\"operation\":\"7\""),
                REQUEST.replace("\"operation\":7", "\"operation\":7.0"),
                REQUEST.replace("\"operation\":7", "\"operation\":7e0"),
                REQUEST.replace("\"operation\":7", "\"operation\":-0"),
                REQUEST.replace("\"operation\":7", "\"operation\":8"),
                REQUEST.replace("\"operation\":7", "\"operation\":6"),
                REQUEST.replace("\"action\":\"start\"", "\"action\":1"),
                REQUEST.replace("\"start\"", "\"finish\""),
                REQUEST.replace("\"start\"", "\"hold\""),
                REQUEST.replace("\"protocol\":1", "\"protocol\":2"),
                REQUEST.replace("\"sessionEpoch\":42", "\"sessionEpoch\":9007199254740992"),
                REQUEST.replace("minecraft:overworld", "minecraft:over world"),
                REQUEST.replace("\"revision\":1", "\"revision\":0"),
                REQUEST.replace("\"subscriptionId\":1", "\"subscriptionId\":0"),
                REQUEST.replace("\"revision\":1,", ""),
                REQUEST.replace("\"dimension\":\"minecraft:overworld\"", "\"dimension\":{}"),
                REQUEST + "{}", " ".repeat(1025) + REQUEST,
        }) assertThrows(Exception.class,
                () -> ObserverDestroyRequest.from(ObserverAuthenticatedExchange.parse(changed)), changed);
    }
}
