package dev.glade.client.script;

import dev.glade.client.GladeClient;
import dev.glade.client.action.ActionResult;
import dev.glade.client.action.BreakBlockAction;
import dev.glade.client.action.KillEntityAction;
import dev.glade.client.action.PlaceBlockAction;
import dev.glade.client.humanize.HumanizationProfile;
import dev.glade.client.humanize.RotationHumanizer;
import dev.glade.client.pathing.GoalBlock;
import dev.glade.client.pathing.GoalNear;
import dev.glade.client.pathing.GoalXZ;
import dev.glade.client.pathing.PathingOptions;
import dev.glade.client.scan.ScanTask;
import dev.glade.client.task.SimpleTask;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import dev.glade.client.render.RenderQueue;
import org.graalvm.polyglot.HostAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default-deny host capability object exposed to Python. */
public final class GladeNativeBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Glade Script");
    private final GameThreadExecutor game;
    private final EventDispatcher events;
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final java.util.Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();
    private Random random = new Random();

    public GladeNativeBridge(GameThreadExecutor game, EventDispatcher events) {
        this.game = game;
        this.events = events;
    }

    @HostAccess.Export public String gotoBlock(int x, int y, int z) {
        return await(await(game.submit(() -> GladeClient.pathingEngine()
                .goTo(new GoalBlock(x, y, z), PathingOptions.DEFAULT)))).toString();
    }
    @HostAccess.Export public String gotoNear(int x, int y, int z, int range) {
        return await(await(game.submit(() -> GladeClient.pathingEngine()
                .goTo(new GoalNear(x, y, z, range), PathingOptions.DEFAULT)))).toString();
    }
    @HostAccess.Export public String gotoXZ(int x, int z) {
        return await(await(game.submit(() -> GladeClient.pathingEngine()
                .goTo(new GoalXZ(x, z), PathingOptions.DEFAULT)))).toString();
    }

    @HostAccess.Export public Pos findBlock(String predicate, int radius) {
        return await(await(game.submit(() -> scheduleBlockScan(predicate, radius))));
    }

    @HostAccess.Export public EntityInfo findEntity(String type, double radius) {
        return await(game.submit(() -> findEntityOnGameThread(type, radius, false)));
    }

    @HostAccess.Export public EntityInfo findItem(String item, double radius) {
        return await(game.submit(() -> findEntityOnGameThread(item, radius, true)));
    }

    @HostAccess.Export public String placeBlock(int x, int y, int z) {
        return action(new PlaceBlockAction(new BlockPos(x, y, z)), "place");
    }
    @HostAccess.Export public String placeBlockAs(int x, int y, int z, String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !Registries.BLOCK.containsId(id)) throw new IllegalArgumentException("Unknown block: " + blockId);
        net.minecraft.block.Block wanted = Registries.BLOCK.get(id);
        java.util.function.Predicate<net.minecraft.item.ItemStack> selector = stack ->
                stack.getItem() instanceof net.minecraft.item.BlockItem blockItem && blockItem.getBlock() == wanted;
        return action(new PlaceBlockAction(new BlockPos(x, y, z), selector, Direction.UP, null), "place");
    }
    @HostAccess.Export public String breakBlock(int x, int y, int z) {
        return action(new BreakBlockAction(new BlockPos(x, y, z)), "break");
    }
    @HostAccess.Export public String killNearest(double radius) {
        CompletableFuture<ActionResult> result = await(game.submit(() -> {
            MinecraftClient client = requireWorld();
            Entity nearest = client.world.getEntitiesByClass(HostileEntity.class,
                            client.player.getBoundingBox().expand(radius), Entity::isAlive)
                    .stream().min(Comparator.comparingDouble(client.player::squaredDistanceTo)).orElse(null);
            if (nearest == null) throw new IllegalStateException("No hostile entity within " + radius + " blocks");
            KillEntityAction action = new KillEntityAction(nearest);
            GladeClient.taskScheduler().addTask("script-kill", action);
            return action.future();
        }));
        return requireSuccess(await(result));
    }

    @HostAccess.Export public void lookAt(double x, double y, double z) {
        await(game.submit(() -> {
            MinecraftClient client = requireWorld();
            float[] angles = RotationHumanizer.yawPitchTo(client.player.getEyePos(), new Vec3d(x, y, z));
            client.player.setYaw(angles[0]);
            client.player.setPitch(angles[1]);
            return null;
        }));
    }
    @HostAccess.Export public Pos playerPos() {
        return await(game.submit(() -> {
            MinecraftClient client = requireWorld();
            Vec3d p = client.player.getEyePos();
            return new Pos(p.x, p.y, p.z);
        }));
    }
    @HostAccess.Export public void log(String message) { LOGGER.info("[Python] {}", message); }
    @HostAccess.Export public void waitBetween(double a, double b) throws InterruptedException {
        checkValid();
        if (!Double.isFinite(a) || !Double.isFinite(b) || a < 0 || b < a)
            throw new IllegalArgumentException("Expected 0 <= a <= b");
        Thread.sleep((long) ((a + random.nextDouble() * (b - a)) * 1000.0));
    }
    @HostAccess.Export public void setProfile(String profile) {
        await(game.submit(() -> { GladeClient.humanizer().setDefaultProfile(HumanizationProfile.byName(profile)); return null; }));
    }
    @HostAccess.Export public void setSeed(long seed) { checkValid(); random = new Random(seed); }
    @HostAccess.Export public void on(String event, org.graalvm.polyglot.Value handler) { events.register(event, handler); }

    void invalidate() {
        valid.set(false);
        IllegalStateException error = new IllegalStateException("Script session invalidated");
        for (CompletableFuture<?> future : inFlight) future.completeExceptionally(error);
    }

    private String action(dev.glade.client.task.GladeTask task, String name) {
        CompletableFuture<ActionResult> future = await(game.submit(() -> {
            CompletableFuture<ActionResult> f = task instanceof PlaceBlockAction p ? p.future()
                    : ((BreakBlockAction) task).future();
            GladeClient.taskScheduler().addTask("script-" + name, task);
            return f;
        }));
        return requireSuccess(await(future));
    }

    private CompletableFuture<Pos> scheduleBlockScan(String text, int radius) {
        MinecraftClient client = requireWorld();
        if (radius < 1 || radius > 64) throw new IllegalArgumentException("radius must be 1..64");
        Identifier id = Identifier.tryParse(text);
        if (id == null || !Registries.BLOCK.containsId(id)) throw new IllegalArgumentException("Unknown block: " + text);
        ScriptBlockScanTask scan = new ScriptBlockScanTask(
                client.player.getBlockPos(), radius, Registries.BLOCK.get(id));
        GladeClient.taskScheduler().addTask("script-find-block", scan);
        return scan.future;
    }

    private EntityInfo findEntityOnGameThread(String name, double radius, boolean itemsOnly) {
        MinecraftClient client = requireWorld();
        if (!Double.isFinite(radius) || radius <= 0 || radius > 128) throw new IllegalArgumentException("radius must be in (0,128]");
        String wanted = name.toLowerCase(Locale.ROOT);
        Box box = client.player.getBoundingBox().expand(radius);
        return client.world.getEntitiesByClass(Entity.class, box, entity -> {
                    if (!entity.isAlive() || entity == client.player) return false;
                    if (itemsOnly) return entity instanceof ItemEntity item
                            && Registries.ITEM.getId(item.getStack().getItem()).toString().equals(wanted);
                    return Registries.ENTITY_TYPE.getId(entity.getType()).toString().equals(wanted);
                }).stream().min(Comparator.comparingDouble(client.player::squaredDistanceTo))
                .map(entity -> new EntityInfo(entity.getUuidAsString(),
                        Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                        new Pos(entity.getX(), entity.getY(), entity.getZ()))).orElse(null);
    }

    private MinecraftClient requireWorld() {
        checkValid();
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread() || client.player == null || client.world == null)
            throw new IllegalStateException("No active client world/player");
        return client;
    }
    private void checkValid() { if (!valid.get()) throw new IllegalStateException("Script world handles were invalidated"); }
    private <T> T await(CompletableFuture<T> future) {
        checkValid();
        inFlight.add(future);
        try { return future.join(); }
        catch (CompletionException error) { throw error.getCause() instanceof RuntimeException r ? r : error; }
        finally { inFlight.remove(future); }
    }
    private static String requireSuccess(ActionResult result) {
        if (!result.success()) throw new IllegalStateException(result.message());
        return result.message();
    }

    public record Pos(double x, double y, double z) {
        @HostAccess.Export public double x() { return x; }
        @HostAccess.Export public double y() { return y; }
        @HostAccess.Export public double z() { return z; }
    }
    public record EntityInfo(String uuid, String type, Pos pos) {
        @HostAccess.Export public String uuid() { return uuid; }
        @HostAccess.Export public String type() { return type; }
        @HostAccess.Export public Pos pos() { return pos; }
    }

    private static final class ScriptBlockScanTask extends SimpleTask {
        private final Iterator<BlockPos> positions;
        private final net.minecraft.block.Block block;
        private final CompletableFuture<Pos> future = new CompletableFuture<>();

        private ScriptBlockScanTask(BlockPos center, int radius, net.minecraft.block.Block block) {
            this.positions = BlockPos.iterateOutwards(center, radius, radius, radius).iterator();
            this.block = block;
        }
        @Override public void initialize() {}
        @Override public boolean condition() { return positions.hasNext() && !future.isDone(); }
        @Override protected void onTick() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null || !client.isOnThread()) { _break(); return; }
            while (positions.hasNext() && GladeClient.tickBudget().hasBudgetRemaining()) {
                BlockPos pos = positions.next();
                if (client.world.getBlockState(pos).isOf(block)) {
                    RenderQueue.add("glow:" + pos.asLong(), new Box(pos).expand(0.002), 0x33FF66, 10 * 20);
                    future.complete(new Pos(pos.getX(), pos.getY(), pos.getZ()));
                    _break();
                    return;
                }
            }
        }
        @Override public void onCompleted() { if (!future.isDone()) future.complete(null); }
        @Override public java.util.Set<Object> getMutexKeys() { return java.util.Set.of(ScanTask.INTENSIVE_MUTEX); }
    }
}
