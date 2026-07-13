package dev.talos.client.script;

import dev.talos.client.TalosClient;
import dev.talos.client.action.ActionResult;
import dev.talos.client.action.BreakBlockAction;
import dev.talos.client.action.KillEntityAction;
import dev.talos.client.action.PlaceBlockAction;
import dev.talos.client.humanize.HumanizationProfile;
import dev.talos.client.humanize.RotationHumanizer;
import dev.talos.client.pathing.Goal;
import dev.talos.client.pathing.GoalBlock;
import dev.talos.client.pathing.GoalNear;
import dev.talos.client.pathing.GoalXZ;
import dev.talos.client.pathing.PathResult;
import dev.talos.client.pathing.PathingOptions;
import dev.talos.client.scan.ScanTask;
import dev.talos.client.task.SimpleTask;
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
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.EquipmentSlot;
import dev.talos.client.render.RenderQueue;
import org.graalvm.polyglot.HostAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default-deny host capability object exposed to Python. */
public final class TalosNativeBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Talos Script");
    private final GameThreadExecutor game;
    private final EventDispatcher events;
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final java.util.Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();
    private Random random = new Random();

    public TalosNativeBridge(GameThreadExecutor game, EventDispatcher events) {
        this.game = game;
        this.events = events;
    }

    @HostAccess.Export public String gotoBlock(int x, int y, int z) {
        return await(pathFuture(new GoalBlock(x, y, z)));
    }
    @HostAccess.Export public String gotoNear(int x, int y, int z, int range) {
        return await(pathFuture(new GoalNear(x, y, z, range)));
    }
    @HostAccess.Export public String gotoXZ(int x, int z) {
        return await(pathFuture(new GoalXZ(x, z)));
    }

    @HostAccess.Export public FutureHandle submitGoto(int x, int y, int z) {
        return handle(pathFuture(new GoalBlock(x, y, z)));
    }
    @HostAccess.Export public FutureHandle submitGotoNear(int x, int y, int z, int range) {
        return handle(pathFuture(new GoalNear(x, y, z, range)));
    }
    @HostAccess.Export public FutureHandle submitGotoXZ(int x, int z) {
        return handle(pathFuture(new GoalXZ(x, z)));
    }
    @HostAccess.Export public FutureHandle submitFindBlock(String predicate, int radius) {
        return handle(game.submit(() -> scheduleBlockScan(predicate, radius)).thenCompose(f -> f));
    }
    @HostAccess.Export public FutureHandle submitPlaceBlock(int x, int y, int z) {
        return handle(actionFuture(new PlaceBlockAction(new BlockPos(x, y, z)), "place"));
    }
    @HostAccess.Export public FutureHandle submitPlaceBlockAs(int x, int y, int z, String blockId) {
        return handle(actionFuture(placeAsAction(x, y, z, blockId), "place"));
    }
    @HostAccess.Export public FutureHandle submitBreakBlock(int x, int y, int z) {
        return handle(actionFuture(new BreakBlockAction(new BlockPos(x, y, z)), "break"));
    }
    @HostAccess.Export public FutureHandle submitMineLookingAt() {
        return handle(bridgeTaskFuture("script-mine-looking", new MineLookingTask()));
    }
    @HostAccess.Export public FutureHandle submitKillNearest(double radius) {
        return handle(killFuture(radius));
    }

    private CompletableFuture<String> pathFuture(Goal goal) {
        return game.submit(() -> TalosClient.pathingEngine().goTo(goal, PathingOptions.DEFAULT))
                .thenCompose(f -> f)
                .thenApply(TalosNativeBridge::requirePath);
    }
    private static String requirePath(PathResult result) {
        if (!result.successful()) throw new IllegalStateException("Path failed: " + result.detail());
        return result.detail();
    }

    /** Registers a not-yet-awaited future for invalidation and hands Python a pollable handle. */
    private FutureHandle handle(CompletableFuture<?> future) {
        checkValid();
        inFlight.add(future);
        future.whenComplete((result, error) -> inFlight.remove(future));
        return new FutureHandle(future);
    }

    @HostAccess.Export public void setNodeCount(int count) {
        await(game.submit(() -> { TalosClient.pathingEngine().setNodeCount(count); return null; }));
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

    /**
     * Place a hotbar block at whatever block the player is currently looking at
     * (the crosshair target), mimicking a normal right-click. No re-aim is performed:
     * the current view/crosshair is used as-is.
     */
    @HostAccess.Export public String placeLook() {
        return await(game.submit(() -> {
            MinecraftClient client = requireWorld();
            HitResult hit = client.crosshairTarget;
            if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
                throw new IllegalStateException("Not looking at a block");
            }
            net.minecraft.util.ActionResult interaction =
                    client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, blockHit);
            client.player.swingHand(Hand.MAIN_HAND);
            if (!interaction.isAccepted()) {
                throw new IllegalStateException("Placement interaction was rejected");
            }
            return "Placed block at " + blockHit.getBlockPos().offset(blockHit.getSide()).toShortString();
        }));
    }

    @HostAccess.Export public String placeBlockAs(int x, int y, int z, String blockId) {
        return action(placeAsAction(x, y, z, blockId), "place");
    }

    private static PlaceBlockAction placeAsAction(int x, int y, int z, String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !Registries.BLOCK.containsId(id)) throw new IllegalArgumentException("Unknown block: " + blockId);
        net.minecraft.block.Block wanted = Registries.BLOCK.get(id);
        java.util.function.Predicate<net.minecraft.item.ItemStack> selector = stack ->
                stack.getItem() instanceof net.minecraft.item.BlockItem blockItem && blockItem.getBlock() == wanted;
        return new PlaceBlockAction(new BlockPos(x, y, z), selector, net.minecraft.util.math.Direction.UP, null);
    }
    @HostAccess.Export public String breakBlock(int x, int y, int z) {
        return action(new BreakBlockAction(new BlockPos(x, y, z)), "break");
    }

    @HostAccess.Export public String mineLookingAt() {
        return awaitBridgeTask("script-mine-looking", new MineLookingTask());
    }

    @HostAccess.Export public String leftClick() {
        return awaitBridgeTask("script-left-click", new ClickTask(false));
    }

    @HostAccess.Export public String rightClick() {
        return awaitBridgeTask("script-right-click", new ClickTask(true));
    }

    @HostAccess.Export public void hotbarSelect(int slot) {
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("hotbar slot must be 0..8");
        await(game.submit(() -> { requireWorld().player.getInventory().setSelectedSlot(slot); return null; }));
    }

    @HostAccess.Export public String clickSlot(int slot, boolean isRight) {
        return awaitBridgeTask("script-click-slot", new SlotClickTask(slot, isRight ? 1 : 0));
    }

    @HostAccess.Export public int containerSlotCount() {
        return await(game.submit(() -> {
            MinecraftClient client = requireWorld();
            return (int) client.player.currentScreenHandler.slots.stream()
                    .filter(slot -> slot.inventory != client.player.getInventory()).count();
        }));
    }

    @HostAccess.Export public String moveStack(int fromSlot, int toSlot) {
        return awaitBridgeTask("script-move-stack", new MoveStackTask(fromSlot, toSlot));
    }

    @HostAccess.Export public String takeStack(int containerSlot, int playerSlot) {
        return moveStack(containerSlot, playerSlot);
    }

    @HostAccess.Export public String armorItem(String armorSlot) {
        return await(game.submit(() -> {
            MinecraftClient client = requireWorld();
            return Registries.ITEM.getId(client.player.getEquippedStack(parseArmorSlot(armorSlot)).getItem()).toString();
        }));
    }

    @HostAccess.Export public String equipArmor(int fromSlot, String armorSlot) {
        return awaitBridgeTask("script-equip-armor", new EquipArmorTask(fromSlot, parseArmorSlot(armorSlot)));
    }
    @HostAccess.Export public String killNearest(double radius) {
        return await(killFuture(radius));
    }

    private CompletableFuture<String> killFuture(double radius) {
        return game.submit(() -> {
            MinecraftClient client = requireWorld();
            Entity nearest = client.world.getEntitiesByClass(HostileEntity.class,
                            client.player.getBoundingBox().expand(radius), Entity::isAlive)
                    .stream().min(Comparator.comparingDouble(client.player::squaredDistanceTo)).orElse(null);
            if (nearest == null) throw new IllegalStateException("No hostile entity within " + radius + " blocks");
            KillEntityAction action = new KillEntityAction(nearest);
            TalosClient.taskScheduler().addTask("script-kill", action);
            return action.future();
        }).thenCompose(f -> f).thenApply(TalosNativeBridge::requireSuccess);
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
        await(game.submit(() -> { TalosClient.humanizer().setDefaultProfile(HumanizationProfile.byName(profile)); return null; }));
    }
    @HostAccess.Export public void setSeed(long seed) { checkValid(); random = new Random(seed); }
    @HostAccess.Export public double randomBetween(double a, double b) {
        checkValid();
        if (!Double.isFinite(a) || !Double.isFinite(b) || a < 0 || b < a)
            throw new IllegalArgumentException("Expected 0 <= a <= b");
        return a + random.nextDouble() * (b - a);
    }
    @HostAccess.Export public void on(String event, org.graalvm.polyglot.Value handler) { events.register(event, handler); }

    void invalidate() {
        valid.set(false);
        IllegalStateException error = new IllegalStateException("Script session invalidated");
        for (CompletableFuture<?> future : inFlight) future.completeExceptionally(error);
    }

    private String action(dev.talos.client.task.TalosTask task, String name) {
        return await(actionFuture(task, name));
    }

    private CompletableFuture<String> actionFuture(dev.talos.client.task.TalosTask task, String name) {
        return game.submit(() -> {
            CompletableFuture<ActionResult> f = task instanceof PlaceBlockAction p ? p.future()
                    : ((BreakBlockAction) task).future();
            TalosClient.taskScheduler().addTask("script-" + name, task);
            return f;
        }).thenCompose(f -> f).thenApply(TalosNativeBridge::requireSuccess);
    }

    private String awaitBridgeTask(String name, BridgeTask task) {
        return await(bridgeTaskFuture(name, task));
    }

    private CompletableFuture<String> bridgeTaskFuture(String name, BridgeTask task) {
        return game.submit(() -> {
            requireWorld();
            TalosClient.taskScheduler().addTask(name, task);
            return task.future;
        }).thenCompose(f -> f);
    }

    private static EquipmentSlot parseArmorSlot(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "helmet", "head" -> EquipmentSlot.HEAD;
            case "chestplate", "chest" -> EquipmentSlot.CHEST;
            case "leggings", "legs" -> EquipmentSlot.LEGS;
            case "boots", "feet" -> EquipmentSlot.FEET;
            default -> throw new IllegalArgumentException("armor slot must be helmet/chestplate/leggings/boots");
        };
    }

    private CompletableFuture<Pos> scheduleBlockScan(String text, int radius) {
        MinecraftClient client = requireWorld();
        if (radius < 1 || radius > 64) throw new IllegalArgumentException("radius must be 1..64");
        Identifier id = Identifier.tryParse(text);
        if (id == null || !Registries.BLOCK.containsId(id)) throw new IllegalArgumentException("Unknown block: " + text);
        ScriptBlockScanTask scan = new ScriptBlockScanTask(
                client.player.getBlockPos(), radius, Registries.BLOCK.get(id));
        TalosClient.taskScheduler().addTask("script-find-block", scan);
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

    /** Pollable host future backing the awaitable Python actions (talos.aio). */
    public static final class FutureHandle {
        private final CompletableFuture<?> future;
        FutureHandle(CompletableFuture<?> future) { this.future = future; }
        @HostAccess.Export public boolean done() { return future.isDone(); }
        @HostAccess.Export public Object result() {
            try { return future.join(); }
            catch (CompletionException error) { throw error.getCause() instanceof RuntimeException r ? r : error; }
        }
        @HostAccess.Export public void cancel() { future.cancel(true); }
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
            while (positions.hasNext() && TalosClient.tickBudget().hasBudgetRemaining()) {
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

    private abstract static class BridgeTask extends SimpleTask {
        final CompletableFuture<String> future = new CompletableFuture<>();
        void finish(String value) { future.complete(value); _break(); }
        void fail(String value) { future.completeExceptionally(new IllegalStateException(value)); _break(); }
        @Override public void onCompleted() { if (!future.isDone()) fail("Action cancelled"); }
        @Override public java.util.Set<Object> getMutexKeys() { return java.util.Set.of("talos-player-action"); }
        @Override public boolean condition() { return !future.isDone(); }
    }

    private static final class ClickTask extends BridgeTask {
        private final boolean right;
        private ClickTask(boolean right) { this.right = right; }
        @Override protected void onTick() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.interactionManager == null) { fail("No active player"); return; }
            HitResult hit = client.crosshairTarget;
            if (hit == null || hit.getType() == HitResult.Type.MISS) { fail("Crosshair did not hit anything"); return; }
            if (hit instanceof EntityHitResult entityHit) {
                if (right) client.interactionManager.interactEntity(client.player, entityHit.getEntity(), Hand.MAIN_HAND);
                else client.interactionManager.attackEntity(client.player, entityHit.getEntity());
            } else if (hit instanceof BlockHitResult blockHit) {
                if (right) client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, blockHit);
                else client.interactionManager.attackBlock(blockHit.getBlockPos(), blockHit.getSide());
            }
            client.player.swingHand(Hand.MAIN_HAND);
            finish(right ? "Right-clicked crosshair target" : "Left-clicked crosshair target");
        }
    }

    private static final class MineLookingTask extends BridgeTask {
        private BlockPos target;
        private Direction side;
        private net.minecraft.block.BlockState original;
        @Override protected void onTick() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null || client.interactionManager == null) { fail("No active player"); return; }
            if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
                fail(target == null ? "Not looking at a block" : "Crosshair left the mining target"); return;
            }
            if (target == null) {
                target = hit.getBlockPos().toImmutable(); side = hit.getSide(); original = client.world.getBlockState(target);
                int best = client.player.getInventory().getSelectedSlot();
                float speed = client.player.getInventory().getStack(best).getMiningSpeedMultiplier(original);
                for (int i = 0; i < 9; i++) {
                    float candidate = client.player.getInventory().getStack(i).getMiningSpeedMultiplier(original);
                    if (candidate > speed) { best = i; speed = candidate; }
                }
                client.player.getInventory().setSelectedSlot(best);
                client.interactionManager.attackBlock(target, side);
            } else if (!target.equals(hit.getBlockPos())) { fail("Crosshair target changed while mining"); return; }
            var state = client.world.getBlockState(target);
            if (state.isAir()) { finish("Broke " + target.toShortString()); return; }
            if (!state.equals(original)) { fail("Mining target changed unexpectedly"); return; }
            if (TalosClient.tickBudget().hasBudgetRemaining()) {
                client.interactionManager.updateBlockBreakingProgress(target, side);
                client.player.swingHand(Hand.MAIN_HAND);
            }
        }
    }

    private static final class SlotClickTask extends BridgeTask {
        private final int slot, button;
        private SlotClickTask(int slot, int button) { this.slot = slot; this.button = button; }
        @Override protected void onTick() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.interactionManager == null) { fail("No active player"); return; }
            var handler = client.player.currentScreenHandler;
            if (slot < 0 || slot >= handler.slots.size()) { fail("screen slot out of range: " + slot); return; }
            client.interactionManager.clickSlot(handler.syncId, slot, button, SlotActionType.PICKUP, client.player);
            finish("Clicked screen slot " + slot);
        }
    }

    private static class MoveStackTask extends BridgeTask {
        final int from, to;
        private MoveStackTask(int from, int to) { this.from = from; this.to = to; }
        @Override protected void onTick() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.interactionManager == null) { fail("No active player"); return; }
            var handler = client.player.currentScreenHandler;
            if (from < 0 || to < 0 || from >= handler.slots.size() || to >= handler.slots.size()) { fail("screen slot out of range"); return; }
            client.interactionManager.clickSlot(handler.syncId, from, 0, SlotActionType.PICKUP, client.player);
            client.interactionManager.clickSlot(handler.syncId, to, 0, SlotActionType.PICKUP, client.player);
            if (!handler.getCursorStack().isEmpty()) client.interactionManager.clickSlot(handler.syncId, from, 0, SlotActionType.PICKUP, client.player);
            finish("Moved stack from " + from + " to " + to);
        }
    }

    private static final class EquipArmorTask extends BridgeTask {
        private final int from;
        private final EquipmentSlot armor;
        private EquipArmorTask(int from, EquipmentSlot armor) { this.from = from; this.armor = armor; }
        @Override protected void onTick() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.interactionManager == null) { fail("No active player"); return; }
            var handler = client.player.currentScreenHandler;
            int inventoryIndex = switch (armor) { case FEET -> 36; case LEGS -> 37; case CHEST -> 38; case HEAD -> 39; default -> -1; };
            int destination = -1;
            for (int i = 0; i < handler.slots.size(); i++) {
                Slot slot = handler.slots.get(i);
                if (slot.inventory == client.player.getInventory() && slot.getIndex() == inventoryIndex) { destination = i; break; }
            }
            if (destination < 0) { fail("The open screen does not expose armor slots"); return; }
            if (from < 0 || from >= handler.slots.size()) { fail("screen slot out of range: " + from); return; }
            client.interactionManager.clickSlot(handler.syncId, from, 0, SlotActionType.PICKUP, client.player);
            client.interactionManager.clickSlot(handler.syncId, destination, 0, SlotActionType.PICKUP, client.player);
            if (!handler.getCursorStack().isEmpty()) client.interactionManager.clickSlot(handler.syncId, from, 0, SlotActionType.PICKUP, client.player);
            finish("Equipped " + armor.getName());
        }
    }
}
