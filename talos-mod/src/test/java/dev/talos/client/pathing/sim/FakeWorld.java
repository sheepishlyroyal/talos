package dev.talos.client.pathing.sim;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.CollisionView;
import net.minecraft.world.border.WorldBorder;

/** Minimal in-memory world for exercising PlayerMotion/SimPathfinder without a client. */
final class FakeWorld implements CollisionView {
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
        return blocks.getOrDefault(pos, Blocks.AIR.getDefaultState());
    }

    @Override public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public int getHeight() { return 384; }
    @Override public int getBottomY() { return -64; }
    @Override public WorldBorder getWorldBorder() { return new WorldBorder(); }
    @Override public BlockView getChunkAsView(int chunkX, int chunkZ) { return this; }
    @Override public List<VoxelShape> getEntityCollisions(Entity entity, Box box) {
        return List.of();
    }
}
