package dev.talos.client.script;

import dev.talos.client.TalosClient;
import dev.talos.client.action.ActionResult;
import dev.talos.client.action.BreakBlockAction;
import dev.talos.client.action.KillEntityAction;
import dev.talos.client.action.PlaceBlockAction;
import dev.talos.client.hud.TalosHud;
import dev.talos.client.humanize.HumanizationProfile;
import dev.talos.client.humanize.RotationHumanizer;
import dev.talos.client.log.TalosLog;
import dev.talos.client.pathing.Goal;
import dev.talos.client.pathing.GoalBlock;
import dev.talos.client.pathing.GoalNear;
import dev.talos.client.pathing.GoalXZ;
import dev.talos.client.pathing.PathResult;
import dev.talos.client.pathing.PathingOptions;
import dev.talos.client.scan.ScanTask;
import dev.talos.client.task.SimpleTask;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import dev.talos.client.command.RaycastMath;
import dev.talos.client.command.GetCommand;
import dev.talos.client.render.RenderQueue;
import org.graalvm.polyglot.HostAccess;

/** Default-deny host capability object exposed to Python. */
public final class TalosNativeBridge {
    private final GameThreadExecutor game;
    private final EventDispatcher events;
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final java.util.Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();
    private final java.util.Queue<CommandInvocation> pendingCommands =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile String activePathProcess;
    private volatile long activePathStartedNanos = -1L;
    private volatile CompletableFuture<?> activePathFuture;
    private Random random = new Random();

    public TalosNativeBridge(GameThreadExecutor game, EventDispatcher events) {
        this.game = game;
        this.events = events;
    }

    @HostAccess.Export public String gotoBlock(int x, int y, int z) {
        return await(pathFuture("goto", new GoalBlock(x, y, z)));
    }
    @HostAccess.Export public String gotoNear(int x, int y, int z, int range) {
        return await(pathFuture("goto", new GoalNear(x, y, z, range)));
    }
    @HostAccess.Export public String gotoXZ(int x, int z) {
        return await(pathFuture("goto", new GoalXZ(x, z)));
    }

    @HostAccess.Export public FutureHandle submitGoto(int x, int y, int z) {
        return handle(pathFuture("goto", new GoalBlock(x, y, z)));
    }
    @HostAccess.Export public FutureHandle submitGotoNear(int x, int y, int z, int range) {
        return handle(pathFuture("goto", new GoalNear(x, y, z, range)));
    }
    @HostAccess.Export public FutureHandle submitGotoXZ(int x, int z) {
        return handle(pathFuture("goto", new GoalXZ(x, z)));
    }
    @HostAccess.Export public FutureHandle submitFindBlock(String predicate, int radius) {
        return handle(game.submit(() -> scheduleBlockScan(predicate, radius)).thenCompose(f -> f));
    }
    @HostAccess.Export public String gotoBlockType(String blockId, int radius) {
        return await(trackPath("goto_block", game.submit(() -> dev.talos.client.pathing.talos.BlockGoalNavigator
                        .navigate(Minecraft.getInstance(), blockId, radius))
                .thenCompose(f -> f).thenApply(TalosNativeBridge::requirePath)));
    }
    @HostAccess.Export public String follow(String selector, double distance) {
        return await(trackPath("follow", game.submit(() -> {
                    Minecraft client = requireWorld();
                    net.minecraft.world.entity.Entity target =
                            dev.talos.client.command.EntitySelectors.resolve(client, selector, true);
                    return dev.talos.client.pathing.talos.FollowTask.start(client, target, distance);
                })
                .thenCompose(f -> f).thenApply(TalosNativeBridge::requirePath)));
    }

    /** talos.aio.goto_block: nearest matching block, blacklist-and-retry on unreachable. */
    @HostAccess.Export public FutureHandle submitGotoBlockType(String blockId, int radius) {
        return handle(trackPath("goto_block", game.submit(() -> dev.talos.client.pathing.talos.BlockGoalNavigator
                        .navigate(Minecraft.getInstance(), blockId, radius))
                .thenCompose(f -> f).thenApply(TalosNativeBridge::requirePath)));
    }
    /** talos.aio.follow: selector/name/type resolved client-side; ends only when following ends. */
    @HostAccess.Export public FutureHandle submitFollow(String selector, double distance) {
        return handle(trackPath("follow", game.submit(() -> {
                    Minecraft client = requireWorld();
                    net.minecraft.world.entity.Entity target =
                            dev.talos.client.command.EntitySelectors.resolve(client, selector, true);
                    return dev.talos.client.pathing.talos.FollowTask.start(client, target, distance);
                })
                .thenCompose(f -> f).thenApply(TalosNativeBridge::requirePath)));
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

    private CompletableFuture<String> pathFuture(String name, Goal goal) {
        return trackPath(name, game.submit(() -> TalosClient.pathingEngine().goTo(goal, PathingOptions.DEFAULT))
                .thenCompose(f -> f)
                .thenApply(TalosNativeBridge::requirePath));
    }

    private <T> CompletableFuture<T> trackPath(String name, CompletableFuture<T> future) {
        activePathProcess = name;
        activePathStartedNanos = System.nanoTime();
        activePathFuture = future;
        future.whenComplete((result, error) -> {
            if (activePathFuture == future) {
                activePathFuture = null;
                activePathProcess = null;
                activePathStartedNanos = -1L;
            }
        });
        return future;
    }

    /** Cancel one named automation process without stopping the Python session. */
    @HostAccess.Export public boolean killProcess(String requested) {
        String name = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        return await(game.submit(() -> {
            String active = activePathProcess;
            boolean anyPath = name.equals("path") || name.equals("pathing");
            boolean gotoFamily = (name.equals("goto") || name.equals("goto_near")
                    || name.equals("goto_xz")) && active != null && active.startsWith("goto");
            boolean path = active != null && (anyPath || gotoFamily || name.equals(active));
            int cancelled = 0;
            if (path && TalosClient.pathingEngine().isPathing()) {
                TalosClient.pathingEngine().cancel();
                cancelled++;
            }
            cancelled += TalosClient.taskScheduler().cancel(name);
            return cancelled > 0;
        }));
    }

    /** Seconds the named path process has been active, or -1 when it is not running. */
    @HostAccess.Export public double processSeconds(String requested) {
        String name = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        String active = activePathProcess;
        boolean gotoFamily = name.equals("goto") && active != null && active.startsWith("goto");
        if (active == null || (!name.equals(active) && !gotoFamily
                && !name.equals("path") && !name.equals("pathing"))) return -1.0;
        return Math.max(0.0, (System.nanoTime() - activePathStartedNanos) / 1_000_000_000.0);
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

    /** Exact same observable/trigger catalog as /talos get, marshalled onto the game thread. */
    @HostAccess.Export public String getObservable(String name, String packedArgs) {
        String[] args = packedArgs == null || packedArgs.isEmpty()
                ? new String[0] : packedArgs.split("\u001f", -1);
        return await(game.submit(() -> GetCommand.value(requireWorld(), name, args)));
    }

    /**
     * Every other player within {@code radius} blocks, nearest first, EXCLUDING the local
     * player. Positions are exact doubles (never floored); distances are measured
     * feet-to-feet (entity origin to entity origin), matching {@link #playerFeet}.
     */
    @HostAccess.Export public PlayerInfo[] players(double radius) {
        return await(game.submit(() -> {
            Minecraft client = requireWorld();
            checkListRadius(radius);
            double limit = radius * radius;
            return client.level.players().stream()
                    .filter(p -> p != client.player && p.isAlive()
                            && client.player.distanceToSqr(p) <= limit)
                    .sorted(Comparator.comparingDouble(client.player::distanceToSqr))
                    .map(p -> new PlayerInfo(p.getGameProfile().name(), p.getStringUUID(),
                            new Pos(p.getX(), p.getY(), p.getZ()),
                            Math.sqrt(client.player.distanceToSqr(p))))
                    .toArray(PlayerInfo[]::new);
        }));
    }

    /**
     * Every entity within {@code radius} blocks, nearest first, excluding the local player.
     * {@code type} filters on the exact registry id ("minecraft:zombie"); null/empty
     * matches everything. Same exactness/distance conventions as {@link #players}.
     */
    @HostAccess.Export public EntityInfo[] entities(String type, double radius) {
        return await(game.submit(() -> {
            Minecraft client = requireWorld();
            checkListRadius(radius);
            String wanted = type == null || type.isEmpty() ? null : type.toLowerCase(Locale.ROOT);
            AABB box = client.player.getBoundingBox().inflate(radius);
            double limit = radius * radius;
            return client.level.getEntitiesOfClass(Entity.class, box, entity ->
                            entity.isAlive() && entity != client.player
                                    && client.player.distanceToSqr(entity) <= limit
                                    && (wanted == null || BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                                            .toString().equals(wanted)))
                    .stream().sorted(Comparator.comparingDouble(client.player::distanceToSqr))
                    .map(entity -> new EntityInfo(entity.getStringUUID(),
                            BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                            new Pos(entity.getX(), entity.getY(), entity.getZ()),
                            Math.sqrt(client.player.distanceToSqr(entity))))
                    .toArray(EntityInfo[]::new);
        }));
    }

    private static void checkListRadius(double radius) {
        if (!Double.isFinite(radius) || radius <= 0 || radius > 512)
            throw new IllegalArgumentException("radius must be in (0,512]");
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
            Minecraft client = requireWorld();
            HitResult hit = client.hitResult;
            if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
                throw new IllegalStateException("Not looking at a block");
            }
            net.minecraft.world.InteractionResult interaction =
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, blockHit);
            client.player.swing(InteractionHand.MAIN_HAND);
            if (!interaction.consumesAction()) {
                throw new IllegalStateException("Placement interaction was rejected");
            }
            return "Placed block at " + blockHit.getBlockPos().relative(blockHit.getDirection()).toShortString();
        }));
    }

    @HostAccess.Export public String placeBlockAs(int x, int y, int z, String blockId) {
        return action(placeAsAction(x, y, z, blockId), "place");
    }

    private static PlaceBlockAction placeAsAction(int x, int y, int z, String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("Unknown block: " + blockId);
        net.minecraft.world.level.block.Block wanted = BuiltInRegistries.BLOCK.getValue(id);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> selector = stack ->
                stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem && blockItem.getBlock() == wanted;
        return new PlaceBlockAction(new BlockPos(x, y, z), selector, net.minecraft.core.Direction.UP, null);
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
            Minecraft client = requireWorld();
            return (int) client.player.containerMenu.slots.stream()
                    .filter(slot -> slot.container != client.player.getInventory()).count();
        }));
    }

    @HostAccess.Export public String moveStack(int fromSlot, int toSlot) {
        return awaitBridgeTask("script-move-stack", new MoveStackTask(fromSlot, toSlot));
    }

    @HostAccess.Export public String takeStack(int containerSlot, int playerSlot) {
        return moveStack(containerSlot, playerSlot);
    }

    /**
     * Non-empty stacks in the player's 36 main inventory slots. Slot numbers are
     * PlayerInventory indices (0-8 hotbar, 9-35 main grid) — the space
     * {@link #hotbarSelect} uses — NOT the raw screen-handler indices that
     * {@link #clickSlot}/{@link #moveStack} take, which shift with the open screen.
     */
    @HostAccess.Export public SlotStack[] inventoryItems() {
        return await(game.submit(() -> {
            Minecraft client = requireWorld();
            List<SlotStack> stacks = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (!stack.isEmpty()) stacks.add(new SlotStack(i,
                        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
            }
            return stacks.toArray(SlotStack[]::new);
        }));
    }

    /** Currently selected hotbar slot, 0..8. */
    @HostAccess.Export public int selectedSlot() {
        return await(game.submit(() -> requireWorld().player.getInventory().getSelectedSlot()));
    }

    /**
     * Non-empty stacks in the open container's non-player slots. Slot numbers here ARE
     * the raw screen-handler indices, directly usable with {@link #clickSlot}/{@link #moveStack}.
     */
    @HostAccess.Export public SlotStack[] containerItems() {
        return await(game.submit(() -> {
            Minecraft client = requireWorld();
            List<SlotStack> stacks = new ArrayList<>();
            for (Slot slot : client.player.containerMenu.slots) {
                if (slot.container == client.player.getInventory() || !slot.hasItem()) continue;
                ItemStack stack = slot.getItem();
                stacks.add(new SlotStack(slot.index,
                        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
            }
            return stacks.toArray(SlotStack[]::new);
        }));
    }

    /** Move up to {@code amount} items of a type from the player into the open container. */
    @HostAccess.Export public int deposit(String itemId, int amount) {
        return transfer(itemId, amount, true);
    }

    /** Move up to {@code amount} items of a type from the open container into the player. */
    @HostAccess.Export public int withdraw(String itemId, int amount) {
        return transfer(itemId, amount, false);
    }

    private int transfer(String itemId, int amount, boolean toContainer) {
        net.minecraft.world.item.Item item = parseItem(itemId);
        if (amount < 0) throw new IllegalArgumentException("amount must be >= 0");
        TransferTask task = new TransferTask(item, amount, toContainer);
        return await(game.submit(() -> {
            requireWorld();
            addTaskWhenFree("script-transfer", task, task.future);
            return task.future;
        }).thenCompose(f -> f));
    }

    private static net.minecraft.world.item.Item parseItem(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id))
            throw new IllegalArgumentException("Unknown item: " + itemId);
        return BuiltInRegistries.ITEM.getValue(id);
    }

    /**
     * Craft {@code count} results of an OUTPUT item via the recipe book. 1.21.11 no longer
     * syncs recipe resource ids to the client (recipes arrive as anonymous
     * {@link RecipeDisplayId}s plus displays), so lookup matches the recipe's RESULT item
     * instead; a recipe whose ingredients are currently available is preferred. Requires a
     * crafting screen (player inventory 2x2 or crafting table) to be open.
     */
    @HostAccess.Export public String craft(String output, int count) {
        if (count < 1 || count > 1024) throw new IllegalArgumentException("count must be 1..1024");
        net.minecraft.world.item.Item wanted = parseItem(output);
        return await(game.submit(() -> {
            Minecraft client = requireWorld();
            if (!(client.player.containerMenu instanceof AbstractCraftingMenu crafting))
                throw new IllegalStateException(
                        "No crafting screen: open the inventory (2x2 recipes) or a crafting table first");
            StackedItemContents finder = new StackedItemContents();
            crafting.fillCraftSlotsStackedContents(finder);
            var parameters = SlotDisplayContext.fromLevel(client.level);
            RecipeDisplayId match = null;
            RecipeDisplayId fallback = null;
            outer:
            for (var collection : client.player.getRecipeBook().getCollections()) {
                for (RecipeDisplayEntry entry : collection.getRecipes()) {
                    if (entry.resultItems(parameters).stream().noneMatch(stack -> stack.is(wanted))) continue;
                    if (fallback == null) fallback = entry.id();
                    if (entry.canCraft(finder)) { match = entry.id(); break outer; }
                }
            }
            RecipeDisplayId recipe = match != null ? match : fallback;
            if (recipe == null) throw new IllegalStateException("No recipe book entry produces " + output);
            CraftTask task = new CraftTask(recipe, wanted, count, crafting,
                    crafting.getResultSlot().index, output);
            addTaskWhenFree("script-craft", task, task.future);
            return task.future;
        }).thenCompose(f -> f));
    }

    /**
     * Name of the open screen: the screen handler's registry id when it has one
     * (e.g. "minecraft:generic_9x3", "minecraft:crafting"), "minecraft:inventory" for the
     * player's own inventory, the screen title/class for non-handled screens, or null
     * when no screen is open.
     */
    @HostAccess.Export public String screenName() {
        return await(game.submit(() -> {
            Minecraft client = requireWorld();
            if (client.gui.screen() == null) return null;
            if (client.gui.screen() instanceof AbstractContainerScreen<?> handled) {
                try {
                    return BuiltInRegistries.MENU.getKey(handled.getMenu().getType()).toString();
                } catch (UnsupportedOperationException noType) {
                    return "minecraft:inventory"; // PlayerScreenHandler has no registered type
                }
            }
            String title = client.gui.screen().getTitle().getString();
            return title.isEmpty() ? client.gui.screen().getClass().getSimpleName() : title;
        }));
    }

    /** Close whatever screen is open (container, inventory, ...); safe when none is. */
    @HostAccess.Export public void closeScreen() {
        await(game.submit(() -> {
            Minecraft client = requireWorld();
            if (client.gui.screen() instanceof AbstractContainerScreen<?>) client.player.closeContainer();
            else if (client.gui.screen() != null) client.gui.setScreen(null);
            return null;
        }));
    }

    private static final int STATE_MAX_BYTES = 256 * 1024;

    /**
     * The ONLY file surface exposed to Python: JSON blobs pinned to
     * {@code <gameDir>/talos/state/<script>.json}. The script name is derived by the
     * embedded talos.state module (never user input), and re-validated here so no
     * path component can escape the state directory.
     */
    private static Path stateFile(String script) {
        if (script == null || !script.matches("[A-Za-z0-9_.-]{1,64}") || script.contains(".."))
            throw new IllegalArgumentException("Invalid script name for state storage");
        Path dir = FabricLoader.getInstance().getGameDir().resolve("talos").resolve("state").normalize();
        Path file = dir.resolve(script + ".json").normalize();
        if (!dir.equals(file.getParent()))
            throw new IllegalArgumentException("Invalid script name for state storage");
        return file;
    }

    /** Persisted talos.state JSON for a script, or null when nothing was saved yet. */
    @HostAccess.Export public String stateLoad(String script) {
        checkValid();
        Path file = stateFile(script);
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read talos.state: " + error.getMessage());
        }
    }

    /** Persist talos.state JSON for a script; rejects payloads over 256KB serialized. */
    @HostAccess.Export public void stateSave(String script, String json) {
        checkValid();
        Path file = stateFile(script);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > STATE_MAX_BYTES)
            throw new IllegalArgumentException(
                    "talos.state exceeds the 256KB limit (" + bytes.length + " bytes serialized)");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot write talos.state: " + error.getMessage());
        }
    }

    @HostAccess.Export public String armorItem(String armorSlot) {
        return await(game.submit(() -> {
            Minecraft client = requireWorld();
            return BuiltInRegistries.ITEM.getKey(client.player.getItemBySlot(parseArmorSlot(armorSlot)).getItem()).toString();
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
            Minecraft client = requireWorld();
            Entity nearest = client.level.getEntitiesOfClass(Monster.class,
                            client.player.getBoundingBox().inflate(radius), Entity::isAlive)
                    .stream().min(Comparator.comparingDouble(client.player::distanceToSqr)).orElse(null);
            if (nearest == null) throw new IllegalStateException("No hostile entity within " + radius + " blocks");
            KillEntityAction action = new KillEntityAction(nearest);
            addTaskWhenFree("script-kill", action, action.future());
            return action.future();
        }).thenCompose(f -> f).thenApply(TalosNativeBridge::requireSuccess);
    }

    /**
     * Hold or release one of the player's logical {@link KeyMapping}s (never raw key
     * codes, so rebound controls keep working — the same contract /talos key honors).
     * The key stays pressed until released; {@link #releaseKeys()} clears everything.
     */
    @HostAccess.Export public void setKey(String name, boolean pressed) {
        await(game.submit(() -> {
            keyBinding(requireWorld().options, name).setDown(pressed);
            return null;
        }));
    }

    /**
     * Press one logical key for exactly ONE game tick, then release it — the scripted
     * equivalent of a quick physical tap. Press and release land on consecutive client
     * ticks via a scheduler task (the tick that has already started never sees a
     * zero-length press); the script worker blocks until the release happened, and the
     * client thread never waits on anything.
     */
    @HostAccess.Export public String tapKey(String name) {
        return await(game.submit(() -> {
            KeyMapping key = keyBinding(requireWorld().options, name);
            TapKeyTask task = new TapKeyTask(key, name);
            // Empty mutex set: a tap never conflicts, so bypass the conflict scan.
            TalosClient.taskScheduler().forceAddTask("script-tap-" + name, task);
            return task.future;
        }).thenCompose(f -> f));
    }

    /** Releases every key {@link #setKey} can press; safe to call unconditionally. */
    @HostAccess.Export public void releaseKeys() {
        await(game.submit(() -> {
            Options options = requireWorld().options;
            for (KeyMapping key : scriptKeys(options)) key.setDown(false);
            return null;
        }));
    }

    /** Aim at absolute yaw/pitch through the Human-mode-controlled aim path. */
    @HostAccess.Export public void setLook(float yaw, float pitch) {
        await(game.submit(() -> {
            Minecraft client = requireWorld();
            float clamped = net.minecraft.util.Mth.clamp(pitch, -90.0f, 90.0f);
            dev.talos.client.action.AimController.startTask(
                    client, yaw, clamped, System.nanoTime());
            return null;
        }));
    }

    private static KeyMapping keyBinding(Options options, String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "forward" -> options.keyUp;
            case "back", "backward" -> options.keyDown;
            case "left" -> options.keyLeft;
            case "right" -> options.keyRight;
            case "jump" -> options.keyJump;
            case "sneak" -> options.keyShift;
            case "sprint" -> options.keySprint;
            case "attack" -> options.keyAttack;
            case "use" -> options.keyUse;
            default -> throw new IllegalArgumentException(
                    "key must be forward/back/left/right/jump/sneak/sprint/attack/use");
        };
    }

    private static KeyMapping[] scriptKeys(Options options) {
        return new KeyMapping[]{options.keyUp, options.keyDown, options.keyLeft,
                options.keyRight, options.keyJump, options.keyShift, options.keySprint,
                options.keyAttack, options.keyUse};
    }

    @HostAccess.Export public void lookAt(double x, double y, double z) {
        await(game.submit(() -> {
            Minecraft client = requireWorld();
            dev.talos.client.action.AimController.startTask(
                    client, new Vec3(x, y, z), System.nanoTime());
            return null;
        }));
    }
    @HostAccess.Export public Pos playerPos() {
        return await(game.submit(() -> {
            Minecraft client = requireWorld();
            Vec3 p = client.player.getEyePosition();
            return new Pos(p.x, p.y, p.z);
        }));
    }
    /** Feet/bottom-center position — the coordinate space blocks and hitboxes live in. */
    @HostAccess.Export public Pos playerFeet() {
        return await(game.submit(() -> {
            Minecraft client = requireWorld();
            return new Pos(client.player.getX(), client.player.getY(), client.player.getZ());
        }));
    }
    /** Current view rotation as [yaw, pitch] in degrees (yaw wrapped to -180..180). */
    @HostAccess.Export public double[] lookAngle() {
        return await(game.submit(() -> {
            Minecraft client = requireWorld();
            return new double[]{
                    net.minecraft.util.Mth.wrapDegrees(client.player.getYRot()),
                    client.player.getXRot()};
        }));
    }
    /** Block cell the crosshair targets right now, or null (air, out of reach, entity). */
    @HostAccess.Export public Pos lookingAtBlock() {
        return await(game.submit(() -> {
            Minecraft client = requireWorld();
            if (client.hitResult instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = hit.getBlockPos();
                return new Pos(pos.getX(), pos.getY(), pos.getZ());
            }
            return null;
        }));
    }
    /**
     * Resolves a caret local coordinate ({@code ^left ^up ^forward}) to a world point using the
     * player's current look. {@code localCoords(0, 0, 5)} is the point 5 blocks forward.
     */
    @HostAccess.Export public Pos localCoords(double left, double up, double forward) {
        return await(game.submit(() -> {
            Minecraft client = requireWorld();
            Vec3 p = RaycastMath.local(client.player.getEyePosition(),
                    client.player.getYRot(), client.player.getXRot(), left, up, forward);
            return new Pos(p.x, p.y, p.z);
        }));
    }

    /**
     * Casts a ray from the eyes along the current look up to {@code maxDist} blocks; returns the
     * nearer of the first block and first entity hit, or null on a miss.
     */
    @HostAccess.Export public HitInfo raycast(double maxDist) {
        return await(game.submit(() -> {
            Minecraft client = requireWorld();
            RaycastMath.Hit hit = RaycastMath.cast(client.player, client.level, maxDist);
            if (hit.isMiss()) {
                return null;
            }
            return new HitInfo(hit.type().name().toLowerCase(Locale.ROOT), hit.id(),
                    new Pos(hit.point().x, hit.point().y, hit.point().z), hit.distance());
        }));
    }

    /** Blocks the script until the user's next plain chat message, which never reaches chat. */
    @HostAccess.Export public String userInput(String prompt) {
        return await(inputFuture(prompt));
    }
    @HostAccess.Export public FutureHandle submitUserInput(String prompt) {
        checkValid();
        return handle(inputFuture(prompt));
    }
    private CompletableFuture<String> inputFuture(String prompt) {
        checkValid();
        CompletableFuture<String> future = ScriptInputGate.request(prompt);
        game.submit(() -> {
            Minecraft client = requireWorld();
            client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§bTalos §7» §f" + prompt
                            + " §7(answer in chat — the message stays local)"));
            return null;
        });
        return future;
    }

    /** Registry id of the block at a cell, e.g. "minecraft:stone" ("minecraft:air" if empty). */
    @HostAccess.Export public String blockAt(int x, int y, int z) {
        return await(game.submit(() -> {
            Minecraft client = requireWorld();
            return BuiltInRegistries.BLOCK.getKey(
                    client.level.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
        }));
    }
    @HostAccess.Export public void log(String message) { logLevel(message, "info"); }
    @HostAccess.Export public void logLevel(String message, String level) {
        TalosLog.Level parsed;
        try {
            parsed = TalosLog.Level.valueOf(level == null ? "INFO" : level.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            parsed = TalosLog.Level.INFO;
        }
        // chat=false: the Python wrapper print()s to the session's own sink already.
        TalosLog.log(parsed, "script", message, false);
    }
    @HostAccess.Export public void waitBetween(double a, double b) throws InterruptedException {
        checkValid();
        if (!Double.isFinite(a) || !Double.isFinite(b) || a < 0 || b < a)
            throw new IllegalArgumentException("Expected 0 <= a <= b");
        Thread.sleep((long) ((a + random.nextDouble() * (b - a)) * 1000.0));
    }
    @HostAccess.Export public void setProfile(String profile) {
        await(game.submit(() -> { TalosClient.humanizer().setDefaultProfile(HumanizationProfile.byName(profile)); return null; }));
    }
    @HostAccess.Export public void setHumanMode(boolean enabled) {
        await(game.submit(() -> { TalosClient.humanizer().setHumanMode(enabled); return null; }));
    }
    @HostAccess.Export public boolean humanMode() {
        return await(game.submit(TalosClient.humanizer()::humanMode));
    }
    @HostAccess.Export public void setDebugMode(boolean enabled) {
        await(game.submit(() -> { TalosLog.setDebug(enabled); return null; }));
    }
    @HostAccess.Export public boolean debugMode() {
        return await(game.submit(TalosLog::isDebug));
    }
    @HostAccess.Export public double humanFatigue() {
        return await(game.submit(() -> TalosClient.humanizer().sessionArc().fatigue()));
    }
    @HostAccess.Export public boolean humanOnBreak() {
        return await(game.submit(() -> TalosClient.humanizer().sessionArc().onBreak()));
    }
    @HostAccess.Export public void setSeed(long seed) { checkValid(); random = new Random(seed); }
    @HostAccess.Export public double randomBetween(double a, double b) {
        checkValid();
        if (!Double.isFinite(a) || !Double.isFinite(b) || a < 0 || b < a)
            throw new IllegalArgumentException("Expected 0 <= a <= b");
        return a + random.nextDouble() * (b - a);
    }
    @HostAccess.Export public void on(String event, org.graalvm.polyglot.Value handler) { events.register(event, handler); }
    /** Session-provided loader behind talos.require: validation + trust scan + read. */
    private volatile java.util.function.Function<String, String> requireSource;
    void setRequireSource(java.util.function.Function<String, String> loader) { requireSource = loader; }

    @HostAccess.Export public String readScriptSource(String name) {
        java.util.function.Function<String, String> loader = requireSource;
        if (loader == null) throw new IllegalStateException("talos.require is not available in this session");
        return loader.apply(name);
    }

    /** Arguments from `/talos script run <name> <args...>`; empty for VS Code/snippet runs. */
    private volatile String[] scriptArgs = new String[0];
    void setScriptArgs(String[] args) { scriptArgs = args == null ? new String[0] : args; }
    @HostAccess.Export public String[] scriptArgs() { return scriptArgs.clone(); }

    @HostAccess.Export public void hudSet(String id, String text) { TalosHud.set(id, text); }
    @HostAccess.Export public void hudRemove(String id) { TalosHud.remove(id); }
    @HostAccess.Export public void hudClear() { TalosHud.clear(); }

    /**
     * Claims {@code /talos <name>} for this session. Only the NAME crosses the boundary:
     * the handler stays in Python (talos.command keeps it), and the client thread merely
     * queues invocations that the worker-side pump drains via {@link #pollCommand()} —
     * the client tick thread never calls into the GraalPy Context.
     */
    @HostAccess.Export public void registerCommand(String name) {
        checkValid();
        if (name == null || !name.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Command names must match [A-Za-z0-9_-]+");
        ScriptCommandRegistry.register(name, this);
    }

    /** Drops every command this session registered (Python calls this on each fresh run). */
    @HostAccess.Export public void clearCommands() {
        ScriptCommandRegistry.unregisterAll(this);
        pendingCommands.clear();
    }

    /** Next queued command invocation for the worker-side pump, or null when drained. */
    @HostAccess.Export public CommandInvocation pollCommand() {
        return pendingCommands.poll();
    }

    /** Client-thread entry: queue one invocation; false once the session is invalidated. */
    boolean enqueueCommand(String name, String rawArgs) {
        if (!valid.get()) return false;
        pendingCommands.add(new CommandInvocation(name, rawArgs == null ? "" : rawArgs));
        return true;
    }

    void invalidate() {
        valid.set(false);
        ScriptCommandRegistry.unregisterAll(this);
        pendingCommands.clear();
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
            addTaskWhenFree("script-" + name, task, f);
            return f;
        }).thenCompose(f -> f).thenApply(TalosNativeBridge::requireSuccess);
    }

    /**
     * Concurrent script tasks share mutexes (a scan and a break both hold the intensive
     * key); a conflict is a SCHEDULING situation, not an error. Queue the task and start
     * it the tick the running action releases its keys, failing only on a long timeout.
     */
    private static void addTaskWhenFree(String name, dev.talos.client.task.TalosTask task,
            CompletableFuture<?> result) {
        try {
            TalosClient.taskScheduler().addTask(name, task);
            return;
        } catch (IllegalStateException conflict) {
            // fall through to the queued retry below
        }
        TalosClient.taskScheduler().forceAddTask(name + "-queued", new SimpleTask() {
            private int waited;
            @Override public void initialize() { }
            @Override public boolean condition() { return !result.isDone(); }
            @Override protected void onTick() {
                try {
                    TalosClient.taskScheduler().addTask(name, task);
                    _break();
                } catch (IllegalStateException conflict) {
                    if (++waited > 600) {
                        result.completeExceptionally(new IllegalStateException(
                                "Timed out waiting for a conflicting action to finish ("
                                        + conflict.getMessage() + ")"));
                        _break();
                    }
                }
            }
            @Override public java.util.Set<Object> getMutexKeys() { return java.util.Set.of(); }
        });
    }

    private String awaitBridgeTask(String name, BridgeTask task) {
        return await(bridgeTaskFuture(name, task));
    }

    private CompletableFuture<String> bridgeTaskFuture(String name, BridgeTask task) {
        return game.submit(() -> {
            requireWorld();
            addTaskWhenFree(name, task, task.future);
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
        Minecraft client = requireWorld();
        if (radius < 1 || radius > 64) throw new IllegalArgumentException("radius must be 1..64");
        Identifier id = Identifier.tryParse(text);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("Unknown block: " + text);
        ScriptBlockScanTask scan = new ScriptBlockScanTask(
                client.player.blockPosition(), radius, BuiltInRegistries.BLOCK.getValue(id));
        addTaskWhenFree("script-find-block", scan, scan.future);
        return scan.future;
    }

    private EntityInfo findEntityOnGameThread(String name, double radius, boolean itemsOnly) {
        Minecraft client = requireWorld();
        if (!Double.isFinite(radius) || radius <= 0 || radius > 128) throw new IllegalArgumentException("radius must be in (0,128]");
        String wanted = name.toLowerCase(Locale.ROOT);
        AABB box = client.player.getBoundingBox().inflate(radius);
        return client.level.getEntitiesOfClass(Entity.class, box, entity -> {
                    if (!entity.isAlive() || entity == client.player) return false;
                    if (itemsOnly) return entity instanceof ItemEntity item
                            && BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString().equals(wanted);
                    return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(wanted);
                }).stream().min(Comparator.comparingDouble(client.player::distanceToSqr))
                .map(entity -> new EntityInfo(entity.getStringUUID(),
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                        new Pos(entity.getX(), entity.getY(), entity.getZ()),
                        Math.sqrt(client.player.distanceToSqr(entity)))).orElse(null);
    }

    private Minecraft requireWorld() {
        checkValid();
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread() || client.player == null || client.level == null)
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

    /** One queued {@code /talos <name> <args>} call: the name plus its raw argument text. */
    public record CommandInvocation(String name, String args) {
        @HostAccess.Export public String name() { return name; }
        @HostAccess.Export public String args() { return args; }
    }

    public record Pos(double x, double y, double z) {
        @HostAccess.Export public double x() { return x; }
        @HostAccess.Export public double y() { return y; }
        @HostAccess.Export public double z() { return z; }
    }
    public record EntityInfo(String uuid, String type, Pos pos, double distance) {
        @HostAccess.Export public String uuid() { return uuid; }
        @HostAccess.Export public String type() { return type; }
        @HostAccess.Export public Pos pos() { return pos; }
        @HostAccess.Export public double distance() { return distance; }
    }
    /** One nearby player: gamertag, uuid, exact position, feet-to-feet distance. */
    public record PlayerInfo(String name, String uuid, Pos pos, double distance) {
        @HostAccess.Export public String name() { return name; }
        @HostAccess.Export public String uuid() { return uuid; }
        @HostAccess.Export public Pos pos() { return pos; }
        @HostAccess.Export public double distance() { return distance; }
    }
    /** One raycast hit: "block" or "entity", the struck id, the exact point, and eye distance. */
    public record HitInfo(String type, String id, Pos pos, double distance) {
        @HostAccess.Export public String type() { return type; }
        @HostAccess.Export public String id() { return id; }
        @HostAccess.Export public Pos pos() { return pos; }
        @HostAccess.Export public double distance() { return distance; }
    }
    /** One non-empty stack: its slot number, item registry id, and count. */
    public record SlotStack(int slot, String id, int count) {
        @HostAccess.Export public int slot() { return slot; }
        @HostAccess.Export public String id() { return id; }
        @HostAccess.Export public int count() { return count; }
    }

    private static final class ScriptBlockScanTask extends SimpleTask {
        private final Iterator<BlockPos> positions;
        private final net.minecraft.world.level.block.Block block;
        private final CompletableFuture<Pos> future = new CompletableFuture<>();

        private ScriptBlockScanTask(BlockPos center, int radius, net.minecraft.world.level.block.Block block) {
            this.positions = BlockPos.withinManhattan(center, radius, radius, radius).iterator();
            this.block = block;
        }
        @Override public void initialize() {}
        @Override public boolean condition() { return positions.hasNext() && !future.isDone(); }
        @Override protected void onTick() {
            Minecraft client = Minecraft.getInstance();
            if (client.level == null || !client.isSameThread()) { _break(); return; }
            while (positions.hasNext() && TalosClient.tickBudget().hasBudgetRemaining()) {
                BlockPos pos = positions.next();
                if (client.level.getBlockState(pos).is(block)) {
                    RenderQueue.add("glow:" + pos.asLong(), new AABB(pos).inflate(0.002), 0x33FF66, 10 * 20);
                    future.complete(new Pos(pos.getX(), pos.getY(), pos.getZ()));
                    _break();
                    return;
                }
            }
        }
        @Override public void onCompleted() { if (!future.isDone()) future.complete(null); }
        @Override public java.util.Set<Object> getMutexKeys() { return java.util.Set.of(ScanTask.INTENSIVE_MUTEX); }
    }

    /**
     * One-tick key press: presses on its first scheduler pass and releases on the next,
     * so the binding reads pressed for exactly one full client tick. No mutex keys —
     * taps coexist with any running action, mirroring the queued-retry task in
     * {@link #addTaskWhenFree}.
     */
    private static final class TapKeyTask extends SimpleTask {
        private final KeyMapping key;
        private final String name;
        private final CompletableFuture<String> future = new CompletableFuture<>();
        private boolean pressed;
        private TapKeyTask(KeyMapping key, String name) { this.key = key; this.name = name; }
        @Override public boolean condition() { return !future.isDone(); }
        @Override protected void onTick() {
            if (!pressed) { key.setDown(true); pressed = true; return; }
            key.setDown(false);
            future.complete("Tapped " + name);
            _break();
        }
        @Override public void onCompleted() {
            if (pressed) key.setDown(false); // a cancelled tap must never leave the key held
            if (!future.isDone()) future.completeExceptionally(new IllegalStateException("Tap cancelled"));
        }
        @Override public java.util.Set<Object> getMutexKeys() { return java.util.Set.of(); }
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
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || client.gameMode == null) { fail("No active player"); return; }
            HitResult hit = client.hitResult;
            if (hit == null || hit.getType() == HitResult.Type.MISS) { fail("Crosshair did not hit anything"); return; }
            if (hit instanceof EntityHitResult entityHit) {
                if (right) client.gameMode.interact(client.player, entityHit.getEntity(), entityHit, InteractionHand.MAIN_HAND);
                else client.gameMode.attack(client.player, entityHit.getEntity());
            } else if (hit instanceof BlockHitResult blockHit) {
                if (right) client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, blockHit);
                else client.gameMode.startDestroyBlock(blockHit.getBlockPos(), blockHit.getDirection());
            }
            client.player.swing(InteractionHand.MAIN_HAND);
            finish(right ? "Right-clicked crosshair target" : "Left-clicked crosshair target");
        }
    }

    private static final class MineLookingTask extends BridgeTask {
        private BlockPos target;
        private Direction side;
        private net.minecraft.world.level.block.state.BlockState original;
        @Override protected void onTick() {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || client.level == null || client.gameMode == null) { fail("No active player"); return; }
            if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
                fail(target == null ? "Not looking at a block" : "Crosshair left the mining target"); return;
            }
            if (target == null) {
                target = hit.getBlockPos().immutable(); side = hit.getDirection(); original = client.level.getBlockState(target);
                int best = client.player.getInventory().getSelectedSlot();
                float speed = client.player.getInventory().getItem(best).getDestroySpeed(original);
                for (int i = 0; i < 9; i++) {
                    float candidate = client.player.getInventory().getItem(i).getDestroySpeed(original);
                    if (candidate > speed) { best = i; speed = candidate; }
                }
                client.player.getInventory().setSelectedSlot(best);
                client.gameMode.startDestroyBlock(target, side);
            } else if (!target.equals(hit.getBlockPos())) { fail("Crosshair target changed while mining"); return; }
            var state = client.level.getBlockState(target);
            if (state.isAir()) { finish("Broke " + target.toShortString()); return; }
            if (!state.equals(original)) { fail("Mining target changed unexpectedly"); return; }
            if (TalosClient.tickBudget().hasBudgetRemaining()) {
                client.gameMode.continueDestroyBlock(target, side);
                client.player.swing(InteractionHand.MAIN_HAND);
            }
        }
    }

    private static final class SlotClickTask extends BridgeTask {
        private final int slot, button;
        private SlotClickTask(int slot, int button) { this.slot = slot; this.button = button; }
        @Override protected void onTick() {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || client.gameMode == null) { fail("No active player"); return; }
            var handler = client.player.containerMenu;
            if (slot < 0 || slot >= handler.slots.size()) { fail("screen slot out of range: " + slot); return; }
            client.gameMode.handleContainerInput(handler.containerId, slot, button, ContainerInput.PICKUP, client.player);
            finish("Clicked screen slot " + slot);
        }
    }

    private static class MoveStackTask extends BridgeTask {
        final int from, to;
        private MoveStackTask(int from, int to) { this.from = from; this.to = to; }
        @Override protected void onTick() {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || client.gameMode == null) { fail("No active player"); return; }
            var handler = client.player.containerMenu;
            if (from < 0 || to < 0 || from >= handler.slots.size() || to >= handler.slots.size()) { fail("screen slot out of range"); return; }
            client.gameMode.handleContainerInput(handler.containerId, from, 0, ContainerInput.PICKUP, client.player);
            client.gameMode.handleContainerInput(handler.containerId, to, 0, ContainerInput.PICKUP, client.player);
            if (!handler.getCarried().isEmpty()) client.gameMode.handleContainerInput(handler.containerId, from, 0, ContainerInput.PICKUP, client.player);
            finish("Moved stack from " + from + " to " + to);
        }
    }

    /**
     * Best-effort exact-amount item transfer between the player's main inventory and the
     * open container, in one tick. Whole stacks ride QUICK_MOVE; a partial tail is placed
     * one item at a time with cursor right-clicks (vanilla single-item drop), so
     * deposit(t, count(t)) and small exact amounts both work. Completes with the number
     * of items that actually moved (client-predicted stack deltas, 0 when nothing fit).
     */
    private static final class TransferTask extends SimpleTask {
        private final net.minecraft.world.item.Item item;
        private final int amount;
        private final boolean toContainer;
        final CompletableFuture<Integer> future = new CompletableFuture<>();

        private TransferTask(net.minecraft.world.item.Item item, int amount, boolean toContainer) {
            this.item = item;
            this.amount = amount;
            this.toContainer = toContainer;
        }
        @Override public boolean condition() { return !future.isDone(); }
        @Override public void onCompleted() {
            if (!future.isDone()) future.completeExceptionally(new IllegalStateException("Action cancelled"));
        }
        @Override public java.util.Set<Object> getMutexKeys() { return java.util.Set.of("talos-player-action"); }

        @Override protected void onTick() {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || client.gameMode == null) {
                future.completeExceptionally(new IllegalStateException("No active player"));
                _break();
                return;
            }
            var handler = client.player.containerMenu;
            List<Slot> containerSlots = new ArrayList<>();
            List<Slot> playerSlots = new ArrayList<>();
            for (Slot slot : handler.slots) {
                if (slot.container != client.player.getInventory()) containerSlots.add(slot);
                else if (slot.getContainerSlot() < 36) playerSlots.add(slot); // main 36 only; never armor/offhand
            }
            if (containerSlots.isEmpty()) {
                future.completeExceptionally(new IllegalStateException("No container screen is open"));
                _break();
                return;
            }
            List<Slot> sources = toContainer ? playerSlots : containerSlots;
            List<Slot> destinations = toContainer ? containerSlots : playerSlots;
            int moved = 0;
            for (Slot source : sources) {
                if (moved >= amount) break;
                ItemStack stack = source.getItem();
                if (stack.isEmpty() || !stack.is(item)) continue;
                int want = amount - moved;
                if (stack.getCount() <= want) {
                    int before = stack.getCount();
                    client.gameMode.handleContainerInput(handler.containerId, source.index, 0,
                            ContainerInput.QUICK_MOVE, client.player);
                    int after = source.getItem().is(item) ? source.getItem().getCount() : 0;
                    if (after >= before) break; // destination side is full
                    moved += before - after;
                } else {
                    moved += placePartial(client, handler, source, destinations, want);
                    break; // an exact tail always ends the transfer
                }
            }
            future.complete(moved);
            _break();
        }

        /** Cursor-carries the source stack and right-click drops single items until `want` landed. */
        private int placePartial(Minecraft client, net.minecraft.world.inventory.AbstractContainerMenu handler,
                Slot source, List<Slot> destinations, int want) {
            client.gameMode.handleContainerInput(handler.containerId, source.index, 0,
                    ContainerInput.PICKUP, client.player);
            int placed = 0;
            while (placed < want && !handler.getCarried().isEmpty()) {
                Slot dest = pickDestination(destinations);
                if (dest == null) break;
                client.gameMode.handleContainerInput(handler.containerId, dest.index, 1,
                        ContainerInput.PICKUP, client.player);
                placed++;
            }
            if (!handler.getCarried().isEmpty())
                client.gameMode.handleContainerInput(handler.containerId, source.index, 0,
                        ContainerInput.PICKUP, client.player);
            return placed;
        }

        private Slot pickDestination(List<Slot> destinations) {
            Slot empty = null;
            for (Slot slot : destinations) {
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) {
                    if (empty == null) empty = slot;
                } else if (stack.is(item) && stack.getCount() < slot.getMaxStackSize(stack)) {
                    return slot; // top up matching stacks before opening a fresh slot
                }
            }
            return empty;
        }
    }

    /**
     * Multi-tick recipe-book craft: ask the server to fill the grid (clickRecipe), wait
     * for the result slot to fill (the fill is a server round-trip, never same-tick),
     * quick-move the output, repeat. Fails, reporting partial progress, when the result
     * never appears — usually exhausted ingredients.
     */
    private static final class CraftTask extends BridgeTask {
        private final RecipeDisplayId recipe;
        private final net.minecraft.world.item.Item wanted;
        private final AbstractCraftingMenu handler;
        private final int resultSlot;
        private final String outputName;
        private int remaining;
        private int crafted;
        private int waited;
        private boolean sent;

        private CraftTask(RecipeDisplayId recipe, net.minecraft.world.item.Item wanted, int count,
                AbstractCraftingMenu handler, int resultSlot, String outputName) {
            this.recipe = recipe;
            this.wanted = wanted;
            this.remaining = count;
            this.handler = handler;
            this.resultSlot = resultSlot;
            this.outputName = outputName;
        }

        @Override protected void onTick() {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || client.gameMode == null) { fail("No active player"); return; }
            if (client.player.containerMenu != handler) { fail("The crafting screen was closed"); return; }
            if (!sent) {
                client.gameMode.handlePlaceRecipe(handler.containerId, recipe, false);
                sent = true;
                waited = 0;
                return;
            }
            ItemStack out = handler.slots.get(resultSlot).getItem();
            if (!out.isEmpty() && out.is(wanted)) {
                int produced = out.getCount();
                client.gameMode.handleContainerInput(handler.containerId, resultSlot, 0,
                        ContainerInput.QUICK_MOVE, client.player);
                crafted += produced;
                remaining--;
                if (remaining <= 0) { finish("Crafted " + crafted + " x " + outputName); return; }
                sent = false;
                return;
            }
            if (++waited > 100) {
                fail(crafted > 0
                        ? "Crafted only " + crafted + " x " + outputName + " before the recipe stopped producing (out of ingredients?)"
                        : "Crafting produced no output (missing ingredients, or the recipe does not fit this grid)");
            }
        }
    }

    private static final class EquipArmorTask extends BridgeTask {
        private final int from;
        private final EquipmentSlot armor;
        private EquipArmorTask(int from, EquipmentSlot armor) { this.from = from; this.armor = armor; }
        @Override protected void onTick() {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || client.gameMode == null) { fail("No active player"); return; }
            var handler = client.player.containerMenu;
            int inventoryIndex = switch (armor) { case FEET -> 36; case LEGS -> 37; case CHEST -> 38; case HEAD -> 39; default -> -1; };
            int destination = -1;
            for (int i = 0; i < handler.slots.size(); i++) {
                Slot slot = handler.slots.get(i);
                if (slot.container == client.player.getInventory() && slot.getContainerSlot() == inventoryIndex) { destination = i; break; }
            }
            if (destination < 0) { fail("The open screen does not expose armor slots"); return; }
            if (from < 0 || from >= handler.slots.size()) { fail("screen slot out of range: " + from); return; }
            client.gameMode.handleContainerInput(handler.containerId, from, 0, ContainerInput.PICKUP, client.player);
            client.gameMode.handleContainerInput(handler.containerId, destination, 0, ContainerInput.PICKUP, client.player);
            if (!handler.getCarried().isEmpty()) client.gameMode.handleContainerInput(handler.containerId, from, 0, ContainerInput.PICKUP, client.player);
            finish("Equipped " + armor.getName());
        }
    }
}
