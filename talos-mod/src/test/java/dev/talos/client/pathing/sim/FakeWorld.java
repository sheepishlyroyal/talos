package dev.talos.client.pathing.sim;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Minimal in-memory world for exercising PlayerMotion/SimPathfinder without a client. */
final class FakeWorld implements CollisionGetter {
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();

    void set(int x, int y, int z, BlockState state) {
        blocks.put(new BlockPos(x, y, z), state);
    }

    /** Fills the inclusive cuboid with a state (floors, walls). */
    void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    set(x, y, z, state);
                }
            }
        }
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }

    @Override public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public int getHeight() { return 384; }
    @Override public int getMinY() { return -64; }
    @Override public WorldBorder getWorldBorder() { return new WorldBorder(); }
    @Override public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) { return this; }
    @Override public List<VoxelShape> getEntityCollisions(Entity entity, AABB box) {
        return List.of();
    }
}
