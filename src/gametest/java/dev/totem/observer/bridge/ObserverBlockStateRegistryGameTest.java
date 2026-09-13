package dev.totem.observer.bridge;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;

/** Validates visual registry metadata after Minecraft registries have been bootstrapped. */
public final class ObserverBlockStateRegistryGameTest {
    @GameTest(maxTicks = 20)
    public void visualFlagsMatchAuthoritativeBlockStateMetadata(GameTestHelper helper) {
        int total = Block.BLOCK_STATE_REGISTRY.size();
        if (total <= 0) throw new AssertionError("Block-state registry was not initialized");

        int offset = 0;
        int airStates = 0;
        int occludingStates = 0;
        String fingerprint = null;
        while (offset < total) {
            var page = ObserverBlockStateRegistry.page(offset);
            if (fingerprint == null) fingerprint = page.fingerprint();
            if (!fingerprint.equals(page.fingerprint())) throw new AssertionError("Registry fingerprint changed between pages");
            if (page.total() != total || page.offset() != offset) throw new AssertionError("Registry page metadata mismatch");
            if (page.states().isEmpty() || page.states().size() > ObserverBlockStateRegistry.PAGE_SIZE
                    || page.states().size() != page.visualFlags().size()) {
                throw new AssertionError("Registry page bounds mismatch");
            }

            for (int index = 0; index < page.states().size(); index++) {
                int rawId = offset + index;
                var state = Block.BLOCK_STATE_REGISTRY.byId(rawId);
                if (state == null) throw new AssertionError("Missing block state " + rawId);
                int flags = page.visualFlags().get(index);
                boolean air = (flags & ObserverBlockStateRegistry.VISUAL_AIR) != 0;
                boolean canOcclude = (flags & ObserverBlockStateRegistry.VISUAL_CAN_OCCLUDE) != 0;
                if (air != state.isAir()) throw new AssertionError("Air flag mismatch for " + page.states().get(index));
                if (canOcclude != state.canOcclude()) {
                    throw new AssertionError("Occlusion flag mismatch for " + page.states().get(index));
                }
                if (air) airStates++;
                if (canOcclude) occludingStates++;
            }
            offset += page.states().size();
        }

        if (offset != total || airStates <= 0 || occludingStates <= 0) {
            throw new AssertionError("Registry visual metadata coverage was incomplete");
        }
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new AssertionError("Registry fingerprint was invalid");
        }
        helper.succeed();
    }
}
