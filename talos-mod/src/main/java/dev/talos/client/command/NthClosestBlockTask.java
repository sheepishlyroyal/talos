package dev.talos.client.command;

import dev.talos.client.TalosClient;
import dev.talos.client.scan.ScanTask;
import dev.talos.client.task.SimpleTask;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/**
 * Cooperatively scans loaded chunks for the Nth-closest block matching a predicate, keeping only
 * the {@code n} nearest matches seen so far in a bounded max-heap. Mirrors {@link ScanTask}'s
 * chunk-column pruning, adapted to prune against the current worst-of-top-N distance instead of
 * a single closest distance.
 */
final class NthClosestBlockTask extends SimpleTask {
    private final Predicate<BlockState> predicate;
    private final int chunkRadius;
    /** Matches to retain; the heap root is exactly the requested match once full. */
    private final int keepCount;
    /** Python-style negative index: -1 = furthest match in radius (disables pruning). */
    private final boolean furthestMode;
    /** Called with (matches retained) and the selected position, or null when out of range. */
    private final BiConsumer<Integer, BlockPos> resultCallback;
    private final LongArrayFIFOQueue remainingChunks = new LongArrayFIFOQueue();
    private final PriorityQueue<Match> nearest;

    private Vec3 origin;
    private long currentChunk;
    private boolean scanningChunk;
    private int sectionY;
    private int localIndex;

    private record Match(long pos, double distSq) {
    }

    /** {@code index} is 0-based; negative values count from the furthest match. */
    NthClosestBlockTask(Predicate<BlockState> predicate, int chunkRadius, int index, BiConsumer<Integer, BlockPos> resultCallback) {
        this.predicate = predicate;
        this.chunkRadius = chunkRadius;
        this.furthestMode = index < 0;
        this.keepCount = furthestMode ? -index : index + 1;
        // Nearest mode keeps the keepCount nearest (max-heap: root = requested match).
        // Furthest mode keeps the keepCount furthest (min-heap: root = requested match).
        this.nearest = new PriorityQueue<>(furthestMode
                ? (a, b) -> Double.compare(a.distSq, b.distSq)
                : (a, b) -> Double.compare(b.distSq, a.distSq));
        this.resultCallback = resultCallback;
    }

    @Override
    public void initialize() {
        Minecraft client = Minecraft.getInstance();
        Entity camera = client.getCameraEntity();
        if (client.level == null || camera == null) {
            _break();
            return;
        }
        origin = camera.getEyePosition(0.0F);
        ChunkPos center = ChunkPos.containing(BlockPos.containing(origin));
        ChunkPos.rangeClosed(center, chunkRadius).forEach(pos -> remainingChunks.enqueue(pos.pack()));
    }

    @Override
    public boolean condition() {
        return scanningChunk || !remainingChunks.isEmpty();
    }

    @Override
    protected void onTick() {
        Minecraft client = Minecraft.getInstance();
        ClientLevel world = client.level;
        if (world == null || !client.isSameThread()) {
            _break();
            return;
        }

        while (TalosClient.tickBudget().hasBudgetRemaining() && (scanningChunk || !remainingChunks.isEmpty())) {
            if (!scanningChunk) {
                currentChunk = remainingChunks.dequeueLong();
                ChunkPos chunkPos = ChunkPos.unpack(currentChunk);
                if (!columnCanBeatWorst(chunkPos)) {
                    continue;
                }
                LevelChunk chunk = getLoadedChunk(world, chunkPos);
                sectionY = world.getMinSectionY();
                localIndex = 0;
                scanningChunk = true;
                if (chunk == null) {
                    scanningChunk = false;
                }
                continue;
            }

            scanNext(world);
        }
    }

    private void scanNext(ClientLevel world) {
        ChunkPos chunkPos = ChunkPos.unpack(currentChunk);
        LevelChunk chunk = getLoadedChunk(world, chunkPos);
        if (chunk == null) {
            scanningChunk = false;
            return;
        }
        if (sectionY >= world.getMaxSectionY()) {
            scanningChunk = false;
            return;
        }

        LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
        if (localIndex == 0 && !section.maybeHas(predicate)) {
            sectionY++;
            return;
        }

        int x = localIndex & 15;
        int z = localIndex >> 4 & 15;
        int y = localIndex >> 8 & 15;
        BlockState state = section.getBlockState(x, y, z);
        if (predicate.test(state)) {
            int worldX = chunkPos.getMinBlockX() + x;
            int worldY = (sectionY << 4) + y;
            int worldZ = chunkPos.getMinBlockZ() + z;
            double distanceSquared = BlockPos.containing(worldX, worldY, worldZ).distToCenterSqr(origin);
            offer(new Match(BlockPos.asLong(worldX, worldY, worldZ), distanceSquared));
        }

        if (++localIndex == 4096) {
            localIndex = 0;
            sectionY++;
        }
    }

    private void offer(Match match) {
        nearest.add(match);
        if (nearest.size() > keepCount) {
            nearest.poll();
        }
    }

    private boolean columnCanBeatWorst(ChunkPos chunkPos) {
        // Furthest-mode needs the complete scan: distance pruning only helps nearest-mode.
        if (furthestMode || nearest.size() < keepCount) {
            return true;
        }
        double closestX = Mth.clamp(origin.x, chunkPos.getMinBlockX(), chunkPos.getMinBlockX() + 16);
        double closestZ = Mth.clamp(origin.z, chunkPos.getMinBlockZ(), chunkPos.getMinBlockZ() + 16);
        double dx = origin.x - closestX;
        double dz = origin.z - closestZ;
        return dx * dx + dz * dz < nearest.peek().distSq;
    }

    private static LevelChunk getLoadedChunk(ClientLevel world, ChunkPos pos) {
        return world.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) instanceof LevelChunk chunk ? chunk : null;
    }

    @Override
    public void onCompleted() {
        BlockPos result = nearest.size() >= keepCount && nearest.peek() != null ? BlockPos.of(nearest.peek().pos) : null;
        resultCallback.accept(nearest.size(), result);
    }

    @Override
    public Set<Object> getMutexKeys() {
        return Set.of(ScanTask.INTENSIVE_MUTEX);
    }
}
