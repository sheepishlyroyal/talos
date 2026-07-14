package dev.talos.client.pathing.sim;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.CollisionView;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.PalettedContainer;

/**
 * An immutable copy of a region of the live world, captured on the client thread and then
 * read freely from background planner threads. This is what lets deep path searches run at
 * FULL speed off-thread for their whole wall-clock budget instead of being tick-sliced on
 * the client thread — the client pays one cheap capture (per-section {@code memcpy} via
 * {@link PalettedContainer#copy()}, no per-block reads) instead of 15ms of every tick.
 *
 * <p>Reads outside the captured region — and inside chunks the server has not streamed —
 * return air, exactly what a live read of an unloaded chunk produced before. The capture
 * region is inflated well past the search's funneled sub-goal, so honest searches never
 * reach the edge; the corridor's loaded-chunk clamping remains the fiction guard.</p>
 */
public final class SnapshotView implements CollisionView {
    private final Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sections;
    private final int bottomY;
    private final int height;
    private final WorldBorder border = new WorldBorder();

    private SnapshotView(Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sections,
                         int bottomY, int height) {
        this.sections = sections;
        this.bottomY = bottomY;
        this.height = height;
    }

    /** Captures {@code [min, max]} (inclusive, clamped to world Y). Client thread only. */
    public static SnapshotView capture(World world, BlockPos min, BlockPos max) {
        int minSectionY = Math.max(ChunkSectionPos.getSectionCoord(min.getY()),
                ChunkSectionPos.getSectionCoord(world.getBottomY()));
        int maxSectionY = Math.min(ChunkSectionPos.getSectionCoord(max.getY()),
                ChunkSectionPos.getSectionCoord(world.getBottomY() + world.getHeight() - 1));
        int minChunkX = min.getX() >> 4, maxChunkX = max.getX() >> 4;
        int minChunkZ = min.getZ() >> 4, maxChunkZ = max.getZ() >> 4;
        Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sections =
                new Long2ObjectOpenHashMap<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                Chunk chunk = world.getChunkManager()
                        .getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) continue; // not streamed: reads stay air, as live reads did
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    int index = chunk.sectionCoordToIndex(sectionY);
                    ChunkSection[] array = chunk.getSectionArray();
                    if (index < 0 || index >= array.length || array[index] == null) continue;
                    if (array[index].isEmpty()) continue; // pure air: the default answer
                    sections.put(ChunkSectionPos.asLong(chunkX, sectionY, chunkZ),
                            array[index].getBlockStateContainer().copy());
                }
            }
        }
        return new SnapshotView(sections, world.getBottomY(), world.getHeight());
    }

    /** Number of copied sections — surfaced for capture-cost logging. */
    public int sectionCount() { return sections.size(); }

    @Override public BlockState getBlockState(BlockPos pos) {
        PalettedContainer<BlockState> section = sections.get(ChunkSectionPos.asLong(
                pos.getX() >> 4, ChunkSectionPos.getSectionCoord(pos.getY()), pos.getZ() >> 4));
        if (section == null) return Blocks.AIR.getDefaultState();
        return section.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public int getHeight() { return height; }
    @Override public int getBottomY() { return bottomY; }
    @Override public WorldBorder getWorldBorder() { return border; }
    @Override public BlockView getChunkAsView(int chunkX, int chunkZ) { return this; }
    /** Entity boxes are live-only concerns; the planner ignores them like FakeWorld does. */
    @Override public List<VoxelShape> getEntityCollisions(Entity entity, Box box) {
        return List.of();
    }
}
