package dev.talos.client.script;

import dev.talos.client.TalosClient;
import dev.talos.client.action.ActionResult;
import dev.talos.client.action.BreakBlockAction;
import dev.talos.client.action.KillEntityAction;
import dev.talos.client.action.PlaceBlockAction;
import dev.talos.client.hud.TalosHud;
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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.RecipeFinder;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AbstractCraftingScreenHandler;
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
    private final java.util.Queue<CommandInvocation> pendingCommands =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
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

    /**
     * Every other player within {@code radius} blocks, nearest first, EXCLUDING the local
     * player. Positions are exact doubles (never floored); distances are measured
     * feet-to-feet (entity origin to entity origin), matching {@link #playerFeet}.
     */
    @HostAccess.Export public PlayerInfo[] players(double radius) {
        return await(game.submit(() -> {
            MinecraftClient client = requireWorld();
            checkListRadius(radius);
            double limit = radius * radius;
            return client.world.getPlayers().stream()
                    .filter(p -> p != client.player && p.isAlive()
                            && client.player.squaredDistanceTo(p) <= limit)
                    .sorted(Comparator.comparingDouble(client.player::squaredDistanceTo))
                    .map(p -> new PlayerInfo(p.getGameProfile().name(), p.getUuidAsString(),
                            new Pos(p.getX(), p.getY(), p.getZ()),
                            Math.sqrt(client.player.squaredDistanceTo(p))))
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
            MinecraftClient client = requireWorld();
            checkListRadius(radius);
            String wanted = type == null || type.isEmpty() ? null : type.toLowerCase(Locale.ROOT);
            Box box = client.player.getBoundingBox().expand(radius);
            double limit = radius * radius;
            return client.world.getEntitiesByClass(Entity.class, box, entity ->
                            entity.isAlive() && entity != client.player
                                    && client.player.squaredDistanceTo(entity) <= limit
                                    && (wanted == null || Registries.ENTITY_TYPE.getId(entity.getType())
                                            .toString().equals(wanted)))
                    .stream().sorted(Comparator.comparingDouble(client.player::squaredDistanceTo))
                    .map(entity -> new EntityInfo(entity.getUuidAsString(),
                            Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                            new Pos(entity.getX(), entity.getY(), entity.getZ()),
                            Math.sqrt(client.player.squaredDistanceTo(entity))))
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

    /**
     * Non-empty stacks in the player's 36 main inventory slots. Slot numbers are
     * PlayerInventory indices (0-8 hotbar, 9-35 main grid) — the space
     * {@link #hotbarSelect} uses — NOT the raw screen-handler indices that
     * {@link #clickSlot}/{@link #moveStack} take, which shift with the open screen.
     */
    @HostAccess.Export public SlotStack[] inventoryItems() {
        return await(game.submit(() -> {
            MinecraftClient client = requireWorld();
            List<SlotStack> stacks = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                ItemStack stack = client.player.getInventory().getStack(i);
                if (!stack.isEmpty()) stacks.add(new SlotStack(i,
                        Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount()));
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
            MinecraftClient client = requireWorld();
            List<SlotStack> stacks = new ArrayList<>();
            for (Slot slot : client.player.currentScreenHandler.slots) {
                if (slot.inventory == client.player.getInventory() || !slot.hasStack()) continue;
                ItemStack stack = slot.getStack();
                stacks.add(new SlotStack(slot.id,
                        Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount()));
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
        net.minecraft.item.Item item = parseItem(itemId);
        if (amount < 0) throw new IllegalArgumentException("amount must be >= 0");
        TransferTask task = new TransferTask(item, amount, toContainer);
        return await(game.submit(() -> {
            requireWorld();
            addTaskWhenFree("script-transfer", task, task.future);
            return task.future;
        }).thenCompose(f -> f));
    }

    private static net.minecraft.item.Item parseItem(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !Registries.ITEM.containsId(id))
            throw new IllegalArgumentException("Unknown item: " + itemId);
        return Registries.ITEM.get(id);
    }

    /**
     * Craft {@code count} results of an OUTPUT item via the recipe book. 1.21.11 no longer
     * syncs recipe resource ids to the client (recipes arrive as anonymous
     * {@link NetworkRecipeId}s plus displays), so lookup matches the recipe's RESULT item
     * instead; a recipe whose ingredients are currently available is preferred. Requires a
     * crafting screen (player inventory 2x2 or crafting table) to be open.
     */
    @HostAccess.Export public String craft(String output, int count) {
        if (count < 1 || count > 1024) throw new IllegalArgumentException("count must be 1..1024");
        net.minecraft.item.Item wanted = parseItem(output);
        return await(game.submit(() -> {
            MinecraftClient client = requireWorld();
            if (!(client.player.currentScreenHandler instanceof AbstractCraftingScreenHandler crafting))
                throw new IllegalStateException(
                        "No crafting screen: open the inventory (2x2 recipes) or a crafting table first");
            RecipeFinder finder = new RecipeFinder();
            crafting.populateRecipeFinder(finder);
            var parameters = SlotDisplayContexts.createParameters(client.world);
            NetworkRecipeId match = null;
            NetworkRecipeId fallback = null;
            outer:
            for (var collection : client.player.getRecipeBook().getOrderedResults()) {
                for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                    if (entry.getStacks(parameters).stream().noneMatch(stack -> stack.isOf(wanted))) continue;
                    if (fallback == null) fallback = entry.id();
                    if (entry.isCraftable(finder)) { match = entry.id(); break outer; }
                }
            }
            NetworkRecipeId recipe = match != null ? match : fallback;
            if (recipe == null) throw new IllegalStateException("No recipe book entry produces " + output);
            CraftTask task = new CraftTask(recipe, wanted, count, crafting,
                    crafting.getOutputSlot().id, output);
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
            MinecraftClient client = requireWorld();
            if (client.currentScreen == null) return null;
            if (client.currentScreen instanceof HandledScreen<?> handled) {
                try {
                    return Registries.SCREEN_HANDLER.getId(handled.getScreenHandler().getType()).toString();
                } catch (UnsupportedOperationException noType) {
                    return "minecraft:inventory"; // PlayerScreenHandler has no registered type
                }
            }
            String title = client.currentScreen.getTitle().getString();
            return title.isEmpty() ? client.currentScreen.getClass().getSimpleName() : title;
        }));
    }

    /** Close whatever screen is open (container, inventory, ...); safe when none is. */
    @HostAccess.Export public void closeScreen() {
        await(game.submit(() -> {
            MinecraftClient client = requireWorld();
            if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
            else if (client.currentScreen != null) client.setScreen(null);
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
            addTaskWhenFree("script-kill", action, action.future());
            return action.future();
        }).thenCompose(f -> f).thenApply(TalosNativeBridge::requireSuccess);
    }

    /**
     * Hold or release one of the player's logical {@link KeyBinding}s (never raw key
     * codes, so rebound controls keep working — the same contract /talos key honors).
     * The key stays pressed until released; {@link #releaseKeys()} clears everything.
     */
    @HostAccess.Export public void setKey(String name, boolean pressed) {
        await(game.submit(() -> {
            keyBinding(requireWorld().options, name).setPressed(pressed);
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
            KeyBinding key = keyBinding(requireWorld().options, name);
            TapKeyTask task = new TapKeyTask(key, name);
            // Empty mutex set: a tap never conflicts, so bypass the conflict scan.
            TalosClient.taskScheduler().forceAddTask("script-tap-" + name, task);
            return task.future;
        }).thenCompose(f -> f));
    }

    /** Releases every key {@link #setKey} can press; safe to call unconditionally. */
    @HostAccess.Export public void releaseKeys() {
        await(game.submit(() -> {
            GameOptions options = requireWorld().options;
            for (KeyBinding key : scriptKeys(options)) key.setPressed(false);
            return null;
        }));
    }

    /** Set the view rotation to absolute yaw/pitch degrees (yaw also turns head and body). */
    @HostAccess.Export public void setLook(float yaw, float pitch) {
        await(game.submit(() -> {
            MinecraftClient client = requireWorld();
            float clamped = net.minecraft.util.math.MathHelper.clamp(pitch, -90.0f, 90.0f);
            client.player.setYaw(yaw);
            client.player.setHeadYaw(yaw);
            client.player.setBodyYaw(yaw);
            client.player.setPitch(clamped);
            return null;
        }));
    }

    private static KeyBinding keyBinding(GameOptions options, String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "forward" -> options.forwardKey;
            case "back", "backward" -> options.backKey;
            case "left" -> options.leftKey;
            case "right" -> options.rightKey;
            case "jump" -> options.jumpKey;
            case "sneak" -> options.sneakKey;
            case "sprint" -> options.sprintKey;
            case "attack" -> options.attackKey;
            case "use" -> options.useKey;
            default -> throw new IllegalArgumentException(
                    "key must be forward/back/left/right/jump/sneak/sprint/attack/use");
        };
    }

    private static KeyBinding[] scriptKeys(GameOptions options) {
        return new KeyBinding[]{options.forwardKey, options.backKey, options.leftKey,
                options.rightKey, options.jumpKey, options.sneakKey, options.sprintKey,
                options.attackKey, options.useKey};
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
    /** Feet/bottom-center position — the coordinate space blocks and hitboxes live in. */
    @HostAccess.Export public Pos playerFeet() {
        return await(game.submit(() -> {
            MinecraftClient client = requireWorld();
            return new Pos(client.player.getX(), client.player.getY(), client.player.getZ());
        }));
    }
    /** Current view rotation as [yaw, pitch] in degrees (yaw wrapped to -180..180). */
    @HostAccess.Export public double[] lookAngle() {
        return await(game.submit(() -> {
            MinecraftClient client = requireWorld();
            return new double[]{
                    net.minecraft.util.math.MathHelper.wrapDegrees(client.player.getYaw()),
                    client.player.getPitch()};
        }));
    }
    /** Block cell the crosshair targets right now, or null (air, out of reach, entity). */
    @HostAccess.Export public Pos lookingAtBlock() {
        return await(game.submit(() -> {
            MinecraftClient client = requireWorld();
            if (client.crosshairTarget instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = hit.getBlockPos();
                return new Pos(pos.getX(), pos.getY(), pos.getZ());
            }
            return null;
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
            MinecraftClient client = requireWorld();
            client.player.sendMessage(net.minecraft.text.Text.literal(
                    "§bTalos §7» §f" + prompt
                            + " §7(answer in chat — the message stays local)"), false);
            return null;
        });
        return future;
    }

    /** Registry id of the block at a cell, e.g. "minecraft:stone" ("minecraft:air" if empty). */
    @HostAccess.Export public String blockAt(int x, int y, int z) {
        return await(game.submit(() -> {
            MinecraftClient client = requireWorld();
            return Registries.BLOCK.getId(
                    client.world.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
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
        MinecraftClient client = requireWorld();
        if (radius < 1 || radius > 64) throw new IllegalArgumentException("radius must be 1..64");
        Identifier id = Identifier.tryParse(text);
        if (id == null || !Registries.BLOCK.containsId(id)) throw new IllegalArgumentException("Unknown block: " + text);
        ScriptBlockScanTask scan = new ScriptBlockScanTask(
                client.player.getBlockPos(), radius, Registries.BLOCK.get(id));
        addTaskWhenFree("script-find-block", scan, scan.future);
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
                        new Pos(entity.getX(), entity.getY(), entity.getZ()),
                        Math.sqrt(client.player.squaredDistanceTo(entity)))).orElse(null);
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
    /** One non-empty stack: its slot number, item registry id, and count. */
    public record SlotStack(int slot, String id, int count) {
        @HostAccess.Export public int slot() { return slot; }
        @HostAccess.Export public String id() { return id; }
        @HostAccess.Export public int count() { return count; }
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

    /**
     * One-tick key press: presses on its first scheduler pass and releases on the next,
     * so the binding reads pressed for exactly one full client tick. No mutex keys —
     * taps coexist with any running action, mirroring the queued-retry task in
     * {@link #addTaskWhenFree}.
     */
    private static final class TapKeyTask extends SimpleTask {
        private final KeyBinding key;
        private final String name;
        private final CompletableFuture<String> future = new CompletableFuture<>();
        private boolean pressed;
        private TapKeyTask(KeyBinding key, String name) { this.key = key; this.name = name; }
        @Override public boolean condition() { return !future.isDone(); }
        @Override protected void onTick() {
            if (!pressed) { key.setPressed(true); pressed = true; return; }
            key.setPressed(false);
            future.complete("Tapped " + name);
            _break();
        }
        @Override public void onCompleted() {
            if (pressed) key.setPressed(false); // a cancelled tap must never leave the key held
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

    /**
     * Best-effort exact-amount item transfer between the player's main inventory and the
     * open container, in one tick. Whole stacks ride QUICK_MOVE; a partial tail is placed
     * one item at a time with cursor right-clicks (vanilla single-item drop), so
     * deposit(t, count(t)) and small exact amounts both work. Completes with the number
     * of items that actually moved (client-predicted stack deltas, 0 when nothing fit).
     */
    private static final class TransferTask extends SimpleTask {
        private final net.minecraft.item.Item item;
        private final int amount;
        private final boolean toContainer;
        final CompletableFuture<Integer> future = new CompletableFuture<>();

        private TransferTask(net.minecraft.item.Item item, int amount, boolean toContainer) {
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
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.interactionManager == null) {
                future.completeExceptionally(new IllegalStateException("No active player"));
                _break();
                return;
            }
            var handler = client.player.currentScreenHandler;
            List<Slot> containerSlots = new ArrayList<>();
            List<Slot> playerSlots = new ArrayList<>();
            for (Slot slot : handler.slots) {
                if (slot.inventory != client.player.getInventory()) containerSlots.add(slot);
                else if (slot.getIndex() < 36) playerSlots.add(slot); // main 36 only; never armor/offhand
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
                ItemStack stack = source.getStack();
                if (stack.isEmpty() || !stack.isOf(item)) continue;
                int want = amount - moved;
                if (stack.getCount() <= want) {
                    int before = stack.getCount();
                    client.interactionManager.clickSlot(handler.syncId, source.id, 0,
                            SlotActionType.QUICK_MOVE, client.player);
                    int after = source.getStack().isOf(item) ? source.getStack().getCount() : 0;
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
        private int placePartial(MinecraftClient client, net.minecraft.screen.ScreenHandler handler,
                Slot source, List<Slot> destinations, int want) {
            client.interactionManager.clickSlot(handler.syncId, source.id, 0,
                    SlotActionType.PICKUP, client.player);
            int placed = 0;
            while (placed < want && !handler.getCursorStack().isEmpty()) {
                Slot dest = pickDestination(destinations);
                if (dest == null) break;
                client.interactionManager.clickSlot(handler.syncId, dest.id, 1,
                        SlotActionType.PICKUP, client.player);
                placed++;
            }
            if (!handler.getCursorStack().isEmpty())
                client.interactionManager.clickSlot(handler.syncId, source.id, 0,
                        SlotActionType.PICKUP, client.player);
            return placed;
        }

        private Slot pickDestination(List<Slot> destinations) {
            Slot empty = null;
            for (Slot slot : destinations) {
                ItemStack stack = slot.getStack();
                if (stack.isEmpty()) {
                    if (empty == null) empty = slot;
                } else if (stack.isOf(item) && stack.getCount() < slot.getMaxItemCount(stack)) {
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
        private final NetworkRecipeId recipe;
        private final net.minecraft.item.Item wanted;
        private final AbstractCraftingScreenHandler handler;
        private final int resultSlot;
        private final String outputName;
        private int remaining;
        private int crafted;
        private int waited;
        private boolean sent;

        private CraftTask(NetworkRecipeId recipe, net.minecraft.item.Item wanted, int count,
                AbstractCraftingScreenHandler handler, int resultSlot, String outputName) {
            this.recipe = recipe;
            this.wanted = wanted;
            this.remaining = count;
            this.handler = handler;
            this.resultSlot = resultSlot;
            this.outputName = outputName;
        }

        @Override protected void onTick() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.interactionManager == null) { fail("No active player"); return; }
            if (client.player.currentScreenHandler != handler) { fail("The crafting screen was closed"); return; }
            if (!sent) {
                client.interactionManager.clickRecipe(handler.syncId, recipe, false);
                sent = true;
                waited = 0;
                return;
            }
            ItemStack out = handler.slots.get(resultSlot).getStack();
            if (!out.isEmpty() && out.isOf(wanted)) {
                int produced = out.getCount();
                client.interactionManager.clickSlot(handler.syncId, resultSlot, 0,
                        SlotActionType.QUICK_MOVE, client.player);
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
