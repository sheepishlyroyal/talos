package dev.talos.client.pathing.sim;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

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
public final class SnapshotView implements CollisionGetter {
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
    public static SnapshotView capture(Level world, BlockPos min, BlockPos max) {
        int minSectionY = Math.max(SectionPos.blockToSectionCoord(min.getY()),
                SectionPos.blockToSectionCoord(world.getMinY()));
        int maxSectionY = Math.min(SectionPos.blockToSectionCoord(max.getY()),
                SectionPos.blockToSectionCoord(world.getMinY() + world.getHeight() - 1));
        int minChunkX = min.getX() >> 4, maxChunkX = max.getX() >> 4;
        int minChunkZ = min.getZ() >> 4, maxChunkZ = max.getZ() >> 4;
        Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sections =
                new Long2ObjectOpenHashMap<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ChunkAccess chunk = world.getChunkSource()
                        .getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) continue; // not streamed: reads stay air, as live reads did
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    int index = chunk.getSectionIndexFromSectionY(sectionY);
                    LevelChunkSection[] array = chunk.getSections();
                    if (index < 0 || index >= array.length || array[index] == null) continue;
                    if (array[index].hasOnlyAir()) continue; // pure air: the default answer
                    sections.put(SectionPos.asLong(chunkX, sectionY, chunkZ),
                            array[index].getStates().copy());
                }
            }
        }
        return new SnapshotView(sections, world.getMinY(), world.getHeight());
    }

    /** Number of copied sections — surfaced for capture-cost logging. */
    public int sectionCount() { return sections.size(); }

    @Override public BlockState getBlockState(BlockPos pos) {
        PalettedContainer<BlockState> section = sections.get(SectionPos.asLong(
                pos.getX() >> 4, SectionPos.blockToSectionCoord(pos.getY()), pos.getZ() >> 4));
        if (section == null) return Blocks.AIR.defaultBlockState();
        return section.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public int getHeight() { return height; }
    @Override public int getMinY() { return bottomY; }
    @Override public WorldBorder getWorldBorder() { return border; }
    @Override public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) { return this; }
    /** Entity boxes are live-only concerns; the planner ignores them like FakeWorld does. */
    @Override public List<VoxelShape> getEntityCollisions(Entity entity, AABB box) {
        return List.of();
    }
}
