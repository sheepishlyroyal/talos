package dev.glade.client.scan;

import dev.glade.client.GladeClient;
import dev.glade.client.task.SimpleTask;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import java.util.Set;
import java.util.function.Consumer;
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

/** Cooperatively scans loaded chunks for the closest matching block state. */
public final class ScanTask extends SimpleTask {
    public static final Object INTENSIVE_MUTEX = new Object();
    private static final Set<Object> MUTEX_KEYS = Set.of(INTENSIVE_MUTEX);

    private final BlockStatePredicate predicate;
    private final int chunkRadius;
    private final Consumer<BlockPos> resultCallback;
    private final LongArrayFIFOQueue remainingChunks = new LongArrayFIFOQueue();

    private Vec3d origin;
    private long currentChunk;
    private boolean scanningChunk;
    private int sectionY;
    private int localIndex;
    private long closestPos;
    private boolean found;
    private double closestDistanceSquared = Double.POSITIVE_INFINITY;

    public ScanTask(BlockStatePredicate predicate, int chunkRadius, Consumer<BlockPos> resultCallback) {
        this.predicate = predicate;
        this.chunkRadius = chunkRadius;
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
                if (!columnCanBeatBest(chunkPos)) {
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
        if (localIndex == 0 && !predicate.canEverMatch(section)) {
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
            if (distanceSquared < closestDistanceSquared) {
                closestDistanceSquared = distanceSquared;
                closestPos = BlockPos.asLong(worldX, worldY, worldZ);
                found = true;
            }
        }

        if (++localIndex == 4096) {
            localIndex = 0;
            sectionY++;
        }
    }

    private boolean columnCanBeatBest(ChunkPos chunkPos) {
        if (!found) {
            return true;
        }
        double closestX = MathHelper.clamp(origin.x, chunkPos.getStartX(), chunkPos.getStartX() + 16);
        double closestZ = MathHelper.clamp(origin.z, chunkPos.getStartZ(), chunkPos.getStartZ() + 16);
        double dx = origin.x - closestX;
        double dz = origin.z - closestZ;
        return dx * dx + dz * dz < closestDistanceSquared;
    }

    private static WorldChunk getLoadedChunk(ClientWorld world, ChunkPos pos) {
        return world.getChunk(pos.x, pos.z, ChunkStatus.FULL, false) instanceof WorldChunk chunk ? chunk : null;
    }

    @Override
    public void onCompleted() {
        resultCallback.accept(found ? BlockPos.fromLong(closestPos) : null);
    }

    @Override
    public Set<Object> getMutexKeys() {
        return MUTEX_KEYS;
    }
}
