package dev.totem.observer.bridge;

import java.nio.file.Path;

/** Fixed browser-test fixture. Never included in the production JAR. */
public final class ObserverAccountFixture {
    public static void main(String[] args) throws Exception {
        try (var bridge = new ObserverBridgeServer(new ObserverAccountService(
                new ObserverAccountStore(Path.of(args[0])), true))) {
            int port = bridge.start(0, args[1]);
            System.out.println("OBSERVER_ACCOUNT_FIXTURE_PORT=" + port);
            System.out.flush();
            System.in.read();
        }
    }
}
