package dev.totem.observer.bridge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Immutable view of vanilla/Fabric's synchronized block-state id table.
 *
 * <p>The block-state registry is frozen before a dedicated server accepts players. Observer therefore snapshots it
 * lazily once, then serves small deterministic pages without touching world/chunk state from the Netty thread.</p>
 *
 * <p>Visual flags are deliberately minimal, independent and context-free. Bit 0 means the authoritative BlockState
 * reports air; bit 1 means it reports that it can occlude. Neither flag is a promise that a state is a full cube or
 * that it can be rendered using a cube model.</p>
 */
final class ObserverBlockStateRegistry {
    static final int PROTOCOL = 1;
    static final int PAGE_SIZE = 8;
    static final int VISUAL_AIR = 1;
    static final int VISUAL_CAN_OCCLUDE = 2;
    private static final String STATE_PATTERN = "[a-z0-9_.-]+:[a-z0-9_./-]+(?:\\[[a-z0-9_.,=:/-]+])?";
    private static final Snapshot SNAPSHOT = buildSnapshot();

    record Page(String fingerprint, int offset, int total, List<String> states, List<Integer> visualFlags) {
        Page {
            Objects.requireNonNull(fingerprint, "fingerprint");
            states = List.copyOf(states);
            visualFlags = List.copyOf(visualFlags);
            if (!fingerprint.matches("[0-9a-f]{64}") || offset < 0 || total <= 0 || offset >= total
                    || states.isEmpty() || states.size() > PAGE_SIZE || offset + states.size() > total
                    || visualFlags.size() != states.size()) {
                throw new IllegalArgumentException("Invalid block-state registry page");
            }
            for (String state : states) {
                if (state == null || state.length() > 512 || !state.matches(STATE_PATTERN)) {
                    throw new IllegalArgumentException("Invalid canonical block state");
                }
            }
            for (Integer flags : visualFlags) {
                if (flags == null || flags < 0 || flags > (VISUAL_AIR | VISUAL_CAN_OCCLUDE)) {
                    throw new IllegalArgumentException("Invalid block-state visual flags");
                }
            }
        }
    }

    private record Snapshot(String fingerprint, List<String> states, List<Integer> visualFlags) {}

    private ObserverBlockStateRegistry() {}

    static Page page(int offset) {
        var states = SNAPSHOT.states();
        if (offset < 0 || offset >= states.size()) throw new IllegalArgumentException("Registry offset out of range");
        int end = Math.min(states.size(), offset + PAGE_SIZE);
        return new Page(SNAPSHOT.fingerprint(), offset, states.size(), states.subList(offset, end),
                SNAPSHOT.visualFlags().subList(offset, end));
    }

    private static Snapshot buildSnapshot() {
        int total = Block.BLOCK_STATE_REGISTRY.size();
        if (total <= 0 || total > 1_000_000) throw new IllegalStateException("Unexpected block-state registry size: " + total);
        var states = new ArrayList<String>(total);
        var visualFlags = new ArrayList<Integer>(total);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        for (int id = 0; id < total; id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state == null) throw new IllegalStateException("Missing block state id " + id);
            String canonical = canonical(state);
            if (canonical.length() > 512 || !canonical.matches(STATE_PATTERN)) {
                throw new IllegalStateException("Unsafe block state representation: " + canonical);
            }
            states.add(canonical);
            int flags = 0;
            if (state.isAir()) flags |= VISUAL_AIR;
            if (state.canOcclude()) flags |= VISUAL_CAN_OCCLUDE;
            visualFlags.add(flags);
            digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return new Snapshot(HexFormat.of().formatHex(digest.digest()), List.copyOf(states), List.copyOf(visualFlags));
    }

    private static String canonical(BlockState state) {
        var identifier = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (identifier == null) throw new IllegalStateException("Unregistered block state: " + state);
        var value = new StringBuilder(identifier.toString());
        var properties = new ArrayList<>(state.getProperties());
        properties.sort(Comparator.comparing(Property::getName));
        if (!properties.isEmpty()) {
            value.append('[');
            for (int i = 0; i < properties.size(); i++) {
                if (i > 0) value.append(',');
                var property = properties.get(i);
                value.append(property.getName()).append('=').append(propertyValue(state, property));
            }
            value.append(']');
        }
        return value.toString();
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
