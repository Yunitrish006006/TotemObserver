package dev.totem.observer.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.BooleanSupplier;

/** Current-player vanilla outline targeting, without chunk loading or client hit claims. */
final class ObserverBlockTargeting {
    static final double MAX_REACH = 6;
    static final int MAX_READS = 256;

    private ObserverBlockTargeting() {}

    static BlockHitResult pick(ServerPlayer player, BooleanSupplier authorized) {
        var level = player.level();
        var server = level.getServer();
        if (!server.isSameThread()) throw new IllegalStateException("Block targeting requires server thread");
        if (!authorized.getAsBoolean() || server.getPlayerList().getPlayer(player.getUUID()) != player
                || !player.isAlive() || player.isRemoved() || player.isSpectator()
                || player.isSleeping() || player.isPassenger() || player.isChangingDimension()) return null;
        double reach = player.blockInteractionRange();
        Vec3 eye = player.getEyePosition();
        Vec3 direction = player.getViewVector(1);
        if (!Double.isFinite(reach) || reach <= 0 || !finite(eye) || !finite(direction)
                || direction.lengthSqr() < 0.99 || direction.lengthSqr() > 1.01) return null;
        reach = Math.min(reach, MAX_REACH);
        var view = new LoadedView(level, BlockPos.containing(eye));
        var hit = view.clip(new ClipContext(eye, eye.add(direction.scale(reach)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (view.failed || hit.getType() != HitResult.Type.BLOCK || hit.isInside()
                || eye.distanceToSqr(hit.getLocation()) > reach * reach
                || !level.getWorldBorder().isWithinBounds(hit.getBlockPos())
                || !player.mayInteract(level, hit.getBlockPos()) || !authorized.getAsBoolean()) return null;
        return hit;
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z)
                && Math.abs(value.x) <= 30000000 && Math.abs(value.y) <= 30000000
                && Math.abs(value.z) <= 30000000;
    }

    /** Vanilla shape queries receive this view too, so neighbor reads cannot load chunks. */
    static final class LoadedView implements BlockGetter {
        private final ServerLevel level;
        private final BlockPos origin;
        private int reads;
        private boolean failed;

        LoadedView(ServerLevel level, BlockPos origin) {
            this.level = level;
            this.origin = origin;
        }

        private LevelChunk chunk(BlockPos pos) {
            if (failed || ++reads > MAX_READS || level.isOutsideBuildHeight(pos)
                    || Math.abs((long) pos.getX() - origin.getX()) > 8
                    || Math.abs((long) pos.getY() - origin.getY()) > 8
                    || Math.abs((long) pos.getZ() - origin.getZ()) > 8) {
                failed = true;
                return null;
            }
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) failed = true;
            return chunk;
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            var chunk = chunk(pos);
            return chunk == null ? Blocks.BARRIER.defaultBlockState() : chunk.getBlockState(pos);
        }

        @Override public FluidState getFluidState(BlockPos pos) {
            var chunk = chunk(pos);
            return chunk == null ? Fluids.EMPTY.defaultFluidState() : chunk.getFluidState(pos);
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            var chunk = chunk(pos);
            if (chunk == null) return null;
            // Even EntityCreationType.CHECK promotes pending NBT in 26.2. Read
            // only existing live instances; unavailable shape data invalidates the ray.
            var entity = chunk.getBlockEntities().get(pos);
            if (entity == null || entity.isRemoved()) {
                failed = true;
                return null;
            }
            return entity;
        }

        boolean failed() { return failed; }

        @Override public int getHeight() { return level.getHeight(); }
        @Override public int getMinY() { return level.getMinY(); }
    }
}
