package dev.totem.observer.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Immutable debug selection geometry, never a collision or interaction grant. */
final class ObserverTargetOutline {
    static final int MAX_BOXES = 16;
    static final int MAX_AXIS_COORDINATES = 17;
    record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        Box {
            if (!valid(minX, maxX) || !valid(minY, maxY) || !valid(minZ, maxZ)) {
                throw new IllegalArgumentException("Invalid outline bounds");
            }
        }
    }
    record Snapshot(BlockPos pos, Direction face, int rawId, List<Box> boxes) {
        Snapshot {
            if (pos == null || face == null || rawId < 0 || boxes == null || boxes.isEmpty() || boxes.size() > MAX_BOXES) {
                throw new IllegalArgumentException("Invalid outline snapshot");
            }
            pos = pos.immutable(); boxes = List.copyOf(boxes);
        }
    }

    private ObserverTargetOutline() {}

    static Snapshot capture(ServerPlayer player, BooleanSupplier authorized) {
        var hit = ObserverBlockTargeting.pick(player, authorized);
        if (hit == null) return null;
        var view = new ObserverBlockTargeting.LoadedView(player.level(), BlockPos.containing(player.getEyePosition()));
        var pos = hit.getBlockPos();
        var state = view.getBlockState(pos);
        var shape = state.getShape(view, pos, CollisionContext.of(player));
        var boxes = boundedBoxes(shape);
        if (view.failed() || boxes == null || !authorized.getAsBoolean()) return null;
        return new Snapshot(pos, hit.getDirection(), Block.getId(state), boxes);
    }

    /** Check grid complexity before enumerating boxes, not after allocating an unbounded list. */
    static List<Box> boundedBoxes(VoxelShape shape) {
        if (shape.isEmpty()) return null;
        for (var axis : Direction.Axis.values()) {
            if (shape.getCoords(axis).size() > MAX_AXIS_COORDINATES) return null;
        }
        var boxes = new ArrayList<Box>();
        try {
            shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
                if (boxes.size() == MAX_BOXES || !valid(minX, maxX) || !valid(minY, maxY) || !valid(minZ, maxZ)) {
                    throw new UnsupportedShape();
                }
                boxes.add(new Box(minX, minY, minZ, maxX, maxY, maxZ));
            });
        } catch (UnsupportedShape ignored) { return null; }
        return boxes.isEmpty() ? null : List.copyOf(boxes);
    }

    private static boolean valid(double min, double max) {
        return Double.isFinite(min) && Double.isFinite(max) && min >= -1 && max <= 2 && min < max;
    }

    private static final class UnsupportedShape extends RuntimeException {
        UnsupportedShape() { super(null, null, false, false); }
    }
}
