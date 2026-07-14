package dev.talos.client.scan;

import dev.talos.client.TalosClient;
import dev.talos.client.task.SimpleTask;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import java.util.Set;
import java.util.function.Consumer;
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

/** Cooperatively scans loaded chunks for the closest matching block state. */
public final class ScanTask extends SimpleTask {
    public static final Object INTENSIVE_MUTEX = new Object();
    private static final Set<Object> MUTEX_KEYS = Set.of(INTENSIVE_MUTEX);

    private final BlockStatePredicate predicate;
    private final int chunkRadius;
    private final Consumer<BlockPos> resultCallback;
    private final LongArrayFIFOQueue remainingChunks = new LongArrayFIFOQueue();

    private Vec3 origin;
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
                if (!columnCanBeatBest(chunkPos)) {
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
        if (localIndex == 0 && !predicate.canEverMatch(section)) {
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
        double closestX = Mth.clamp(origin.x, chunkPos.getMinBlockX(), chunkPos.getMinBlockX() + 16);
        double closestZ = Mth.clamp(origin.z, chunkPos.getMinBlockZ(), chunkPos.getMinBlockZ() + 16);
        double dx = origin.x - closestX;
        double dz = origin.z - closestZ;
        return dx * dx + dz * dz < closestDistanceSquared;
    }

    private static LevelChunk getLoadedChunk(ClientLevel world, ChunkPos pos) {
        return world.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) instanceof LevelChunk chunk ? chunk : null;
    }

    @Override
    public void onCompleted() {
        resultCallback.accept(found ? BlockPos.of(closestPos) : null);
    }

    @Override
    public Set<Object> getMutexKeys() {
        return MUTEX_KEYS;
    }
}
