package dev.totem.observer.bridge;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Verifies section misses stay read-only and never force-load a distant chunk. */
public final class ObserverWorldSectionUnavailableGameTest {
    @GameTest(maxTicks = 100_000)
    public void missingWorldSectionDoesNotLoadChunk(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String account = "missing_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        var playSessions = new ObserverPlaySessionService();
        var admissions = new ObserverPlayerAdmissionService(server);
        var auth = new ObserverAccountService.Session(account, UUID.randomUUID());
        var play = playSessions.open(auth);

        CompletableFuture<Void> result = admissions.open(play).thenCompose(admission -> {
            if (admission == null) throw new AssertionError("Observer player admission returned null");
            return admissions.bootstrap(admission).thenCompose(bootstrap -> {
                if (bootstrap == null) throw new AssertionError("Observer bootstrap returned null");
                int chunkX = bootstrap.centerChunkX() + 10_000;
                int chunkZ = bootstrap.centerChunkZ() + 10_000;
                int sectionY = Math.floorDiv(bootstrap.minY(), 16);
                if (admission.player().level().getChunkSource().getChunkNow(chunkX, chunkZ) != null) {
                    throw new AssertionError("Chosen distant chunk was unexpectedly loaded before snapshot");
                }
                return admissions.section(admission, chunkX, chunkZ, sectionY).thenAccept(snapshot -> {
                    if (snapshot != null) {
                        throw new AssertionError("Missing loaded-chunk lookup returned a section snapshot");
                    }
                    if (admission.player().level().getChunkSource().getChunkNow(chunkX, chunkZ) != null) {
                        throw new AssertionError("Observer section lookup force-loaded a missing chunk");
                    }
                    admissions.release(admission);
                });
            });
        });
        result.whenComplete((ignored, failure) -> {
            playSessions.release(play);
            admissions.close();
        });

        helper.startSequence().thenWaitUntil(() -> {
            if (!result.isDone()) helper.fail("Waiting for missing Observer world section lookup");
            result.join();
        }).thenSucceed();
    }
}
