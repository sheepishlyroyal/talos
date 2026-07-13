package dev.glade.client.command;

import dev.glade.client.GladeClient;
import dev.glade.client.scan.ScanTask;
import dev.glade.client.task.SimpleTask;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

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

    private Vec3d origin;
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
        MinecraftClient client = MinecraftClient.getInstance();
        Entity camera = client.getCameraEntity();
        if (client.world == null || camera == null) {
            _break();
            return;
        }
        origin = camera.getCameraPosVec(0.0F);
        ChunkPos center = new ChunkPos(BlockPos.ofFloored(origin));
        ChunkPos.stream(center, chunkRadius).forEach(pos -> remainingChunks.enqueue(pos.toLong()));
    }

    @Override
    public boolean condition() {
        return scanningChunk || !remainingChunks.isEmpty();
    }

    @Override
    protected void onTick() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null || !client.isOnThread()) {
            _break();
            return;
        }

        while (GladeClient.tickBudget().hasBudgetRemaining() && (scanningChunk || !remainingChunks.isEmpty())) {
            if (!scanningChunk) {
                currentChunk = remainingChunks.dequeueLong();
                ChunkPos chunkPos = new ChunkPos(currentChunk);
                if (!columnCanBeatWorst(chunkPos)) {
                    continue;
                }
                WorldChunk chunk = getLoadedChunk(world, chunkPos);
                sectionY = world.getBottomSectionCoord();
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

    private void scanNext(ClientWorld world) {
        ChunkPos chunkPos = new ChunkPos(currentChunk);
        WorldChunk chunk = getLoadedChunk(world, chunkPos);
        if (chunk == null) {
            scanningChunk = false;
            return;
        }
        if (sectionY >= world.getTopSectionCoord()) {
            scanningChunk = false;
            return;
        }

        ChunkSection section = chunk.getSection(chunk.sectionCoordToIndex(sectionY));
        if (localIndex == 0 && !section.hasAny(predicate)) {
            sectionY++;
            return;
        }

        int x = localIndex & 15;
        int z = localIndex >> 4 & 15;
        int y = localIndex >> 8 & 15;
        BlockState state = section.getBlockState(x, y, z);
        if (predicate.test(state)) {
            int worldX = chunkPos.getStartX() + x;
            int worldY = (sectionY << 4) + y;
            int worldZ = chunkPos.getStartZ() + z;
            double distanceSquared = BlockPos.ofFloored(worldX, worldY, worldZ).getSquaredDistance(origin);
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
        double closestX = MathHelper.clamp(origin.x, chunkPos.getStartX(), chunkPos.getStartX() + 16);
        double closestZ = MathHelper.clamp(origin.z, chunkPos.getStartZ(), chunkPos.getStartZ() + 16);
        double dx = origin.x - closestX;
        double dz = origin.z - closestZ;
        return dx * dx + dz * dz < nearest.peek().distSq;
    }

    private static WorldChunk getLoadedChunk(ClientWorld world, ChunkPos pos) {
        return world.getChunk(pos.x, pos.z, ChunkStatus.FULL, false) instanceof WorldChunk chunk ? chunk : null;
    }

    @Override
    public void onCompleted() {
        BlockPos result = nearest.size() >= keepCount && nearest.peek() != null ? BlockPos.fromLong(nearest.peek().pos) : null;
        resultCallback.accept(nearest.size(), result);
    }

    @Override
    public Set<Object> getMutexKeys() {
        return Set.of(ScanTask.INTENSIVE_MUTEX);
    }
}
