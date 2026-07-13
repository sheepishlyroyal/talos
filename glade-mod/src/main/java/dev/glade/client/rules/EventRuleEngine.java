package dev.glade.client.rules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.glade.client.command.EntitySelector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Customizable client-side event rules: {@code /glade on <event> ... run <command>} plus the
 * {@code /glade every|after} scheduler. Rules survive restarts (~/.glade/rules.json).
 *
 * <p>Triggers are parameterized FAMILIES, not fixed switches: {@code entity_count} accepts any
 * {@code @e[type=...,tag=...]/@a/@p/@s} selector, any radius (-1 = whole loaded world) and an
 * above/below/equals comparison; {@code block_count} the same over block ids; metrics
 * (fps, ping, light, speed, chunks_loaded, ...) all take comparisons. The concrete trigger
 * space is therefore combinatorial — thousands of distinct, precise conditions.</p>
 */
public final class EventRuleEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventRuleEngine.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path RULES_FILE =
            Path.of(System.getProperty("user.home"), ".talos", "rules.json");
    private static final int RULE_COOLDOWN_TICKS = 10; // re-entrancy / spam guard
    private static final int BLOCK_SCAN_PERIOD = 20;   // block scans are 1 Hz per rule
    public static final int MAX_BLOCK_RADIUS = 16;

    /** Grammar shape of a trigger family. */
    public enum Kind {
        NONE,             // on <t> run
        NUMBER,           // on <t> <value> run                (legacy vitals thresholds)
        TEXT,             // on <t> [matching "s"] run
        COMPARE,          // on <t> above|below|equals <v> run  (metrics)
        ENTITY_COUNT,     // on <t> <selector> radius <r> above|below|equals <n> run
        ENTITY_PRESENCE,  // on <t> <selector> radius <r> run
        BLOCK_COUNT,      // on <t> <block> radius <r> above|below|equals <n> run
        BLOCK_PRESENCE,   // on <t> <block> radius <r> run
        ITEM_COUNT,       // on <t> <item> above|below|equals <n> run
        REGION            // on <t> <x1> <y1> <z1> <x2> <y2> <z2> run
    }

    /** Every trigger family the engine can watch. */
    public enum Trigger {
        // Vitals — NUMBER fires on crossing and re-arms after leaving the zone.
        HEALTH_BELOW(Kind.NUMBER), HEALTH_ABOVE(Kind.NUMBER), DAMAGE_TAKEN(Kind.NONE),
        HEALED(Kind.NONE), DEATH(Kind.NONE), RESPAWN(Kind.NONE),
        HUNGER_BELOW(Kind.NUMBER), AIR_BELOW(Kind.NUMBER), XP_LEVEL_ABOVE(Kind.NUMBER),
        // Player state — fires when the state is entered.
        ON_FIRE(Kind.NONE), FALLING(Kind.NONE), SNEAKING(Kind.NONE), SPRINTING(Kind.NONE),
        SWIMMING(Kind.NONE), GLIDING(Kind.NONE), UNDERWATER(Kind.NONE), SLEEPING(Kind.NONE),
        WOKE_UP(Kind.NONE), MOUNTED(Kind.TEXT), DISMOUNTED(Kind.TEXT),
        MOVING(Kind.NONE), STOPPED(Kind.NONE), CLIMBING(Kind.NONE), BLOCKING(Kind.NONE),
        USING_ITEM(Kind.NONE), COLLIDED(Kind.NONE), HURT(Kind.NONE), FREEZING(Kind.NONE),
        JUMPED(Kind.NONE), LANDED(Kind.NONE), PROJECTILE_INCOMING(Kind.NONE),
        WINDOW_FOCUSED(Kind.NONE), WINDOW_UNFOCUSED(Kind.NONE),
        SCREEN_OPENED(Kind.TEXT), SCREEN_CLOSED(Kind.TEXT), OFFHAND_CHANGED(Kind.TEXT),
        LOOKING_AT_ENTITY(Kind.TEXT), ENTITY_SPAWNED(Kind.TEXT), ENTITY_REMOVED(Kind.TEXT),
        MENTION(Kind.TEXT), HOTBAR_EMPTY(Kind.NONE), ARMOR_MISSING(Kind.NONE),
        CONTAINER_FULL(Kind.NONE), CONTAINER_EMPTY(Kind.NONE),
        // Items and screens.
        HELD_CHANGED(Kind.NONE), TOOL_BROKEN(Kind.NONE), INVENTORY_FULL(Kind.NONE),
        SLOT_CHANGED(Kind.NONE), CONTAINER_OPENED(Kind.NONE), CONTAINER_CLOSED(Kind.NONE),
        EFFECT_ADDED(Kind.TEXT), EFFECT_REMOVED(Kind.TEXT),
        ITEM_GAINED(Kind.TEXT), ITEM_LOST(Kind.TEXT),
        ITEM_COUNT(Kind.ITEM_COUNT), HOTBAR_ITEM_COUNT(Kind.ITEM_COUNT),
        // Entities — full selector support (@e[type=,tag=,name=]/@a/@p/@s), radius -1 = all.
        ENTITY_COUNT(Kind.ENTITY_COUNT), ENTITY_NEAR(Kind.ENTITY_PRESENCE),
        ENTITY_GONE(Kind.ENTITY_PRESENCE),
        // Blocks — scans a cube around the player once per second per rule.
        BLOCK_COUNT(Kind.BLOCK_COUNT), BLOCK_NEAR(Kind.BLOCK_PRESENCE),
        LOOKING_AT_BLOCK(Kind.TEXT), STANDING_ON(Kind.TEXT), BLOCK_AT_FEET(Kind.TEXT),
        BLOCK_ABOVE_HEAD(Kind.TEXT),
        // World.
        DIMENSION_CHANGED(Kind.NONE), WORLD_LOADED(Kind.NONE), WORLD_UNLOADED(Kind.NONE),
        TIME_DAY(Kind.NONE), TIME_NIGHT(Kind.NONE), WEATHER_RAIN(Kind.NONE),
        WEATHER_CLEAR(Kind.NONE), PLAYER_JOINED(Kind.TEXT), PLAYER_LEFT(Kind.TEXT),
        BIOME_CHANGED(Kind.TEXT), CHUNK_CHANGED(Kind.NONE),
        ENTERED_REGION(Kind.REGION), LEFT_REGION(Kind.REGION),
        // Text the client is shown; SOUND is every sound instance the client plays.
        CHAT(Kind.TEXT), TITLE(Kind.TEXT), SUBTITLE(Kind.TEXT), ACTIONBAR(Kind.TEXT),
        SOUND(Kind.TEXT),
        // Interactions.
        ATTACK_BLOCK(Kind.NONE), USE_BLOCK(Kind.NONE), ATTACK_ENTITY(Kind.NONE),
        USE_ENTITY(Kind.NONE), USE_ITEM(Kind.NONE),
        // Per-entity events — EVERY loaded entity is tracked; {value} is "type[/name]: detail"
        // so 'matching' can target a species, a name, or an item. (Villager INVENTORIES are
        // not synced to clients; profession/level/equipment are, and are covered below.)
        ENTITY_HELD_CHANGED(Kind.TEXT), ENTITY_OFFHAND_CHANGED(Kind.TEXT),
        ENTITY_ARMOR_CHANGED(Kind.TEXT), ENTITY_HURT(Kind.TEXT), ENTITY_DIED(Kind.TEXT),
        ENTITY_DAMAGED(Kind.TEXT), ENTITY_HEALED(Kind.TEXT),
        ENTITY_STARTED_BURNING(Kind.TEXT), ENTITY_MOUNTED(Kind.TEXT),
        ENTITY_DISMOUNTED(Kind.TEXT), ENTITY_SNEAKING(Kind.TEXT), ENTITY_SPRINTING(Kind.TEXT),
        ENTITY_USING_ITEM(Kind.TEXT), ENTITY_BLOCKING(Kind.TEXT), ENTITY_GLIDING(Kind.TEXT),
        ENTITY_SWIMMING(Kind.TEXT), ENTITY_SLEEPING(Kind.TEXT), ENTITY_BABY_GROWN(Kind.TEXT),
        VILLAGER_PROFESSION_CHANGED(Kind.TEXT), VILLAGER_LEVEL_CHANGED(Kind.TEXT),
        PLAYER_HELD_CHANGED(Kind.TEXT), PLAYER_OFFHAND_CHANGED(Kind.TEXT),
        PLAYER_ARMOR_CHANGED(Kind.TEXT), PLAYER_GAMEMODE_CHANGED(Kind.TEXT),
        ITEM_SPAWNED(Kind.TEXT), ITEM_PICKED_UP(Kind.TEXT), ITEM_DESPAWNED(Kind.TEXT),
        PROJECTILE_LAUNCHED(Kind.TEXT),
        // Open-container content diffs — the chest-indexing primitive.
        CONTAINER_ITEM_GAINED(Kind.TEXT), CONTAINER_ITEM_LOST(Kind.TEXT),
        CONTAINER_TITLE(Kind.TEXT),
        // Network wave: every S2C packet by id, plus decoded high-value packets.
        PACKET_RECEIVED(Kind.TEXT), EXPLOSION(Kind.TEXT),
        BOSSBAR_SHOWN(Kind.TEXT), BOSSBAR_UPDATED(Kind.TEXT), BOSSBAR_REMOVED(Kind.NONE),
        SIDEBAR_APPEARED(Kind.TEXT), SIDEBAR_REMOVED(Kind.NONE),
        SIDEBAR_TITLE_CHANGED(Kind.TEXT), SIDEBAR_SCORE_CHANGED(Kind.TEXT),
        SIDEBAR_LINE_ADDED(Kind.TEXT), SIDEBAR_LINE_REMOVED(Kind.TEXT),
        // Held-item detail: enchantments and custom names.
        HELD_ENCHANT(Kind.ITEM_COUNT), HELD_ENCHANTED(Kind.NONE), HELD_HAS_NAME(Kind.NONE),
        HELD_NAME_CHANGED(Kind.TEXT),
        // Unload-vs-gone disambiguation (no timers: the chunk-loaded test decides).
        ENTITY_UNLOADED(Kind.TEXT), ITEM_UNLOADED(Kind.TEXT),
        // Combat/consumable moments.
        TOTEM_POPPED(Kind.TEXT), ENTITY_STATUS(Kind.TEXT), PARTICLE_SEEN(Kind.TEXT),
        PEARL_THROWN(Kind.TEXT), PEARL_LANDED(Kind.TEXT), TELEPORTED(Kind.TEXT),
        POTION_SPLASHED(Kind.TEXT), POTION_DRANK(Kind.TEXT),
        // Metrics — every one supports instant compares, 'for <seconds>' sustained compares,
        // and 'changes above|below <delta> within <seconds>' windowed net change.
        HEALTH(Kind.COMPARE), HUNGER(Kind.COMPARE), AIR(Kind.COMPARE), XP_LEVEL(Kind.COMPARE),
        XP_PROGRESS(Kind.COMPARE), ARMOR_DURABILITY(Kind.COMPARE),
        FPS(Kind.COMPARE), PING(Kind.COMPARE), CHUNKS_LOADED(Kind.COMPARE),
        LIGHT_LEVEL(Kind.COMPARE), Y_POSITION(Kind.COMPARE), X_POSITION(Kind.COMPARE),
        Z_POSITION(Kind.COMPARE), SPEED(Kind.COMPARE), SATURATION(Kind.COMPARE),
        ABSORPTION(Kind.COMPARE), ARMOR_POINTS(Kind.COMPARE), HELD_DURABILITY(Kind.COMPARE),
        FALL_DISTANCE(Kind.COMPARE), TIME_TICKS(Kind.COMPARE), ENTITY_TOTAL(Kind.COMPARE),
        PLAYERS_ONLINE(Kind.COMPARE), IDLE_SECONDS(Kind.COMPARE),
        VELOCITY_Y(Kind.COMPARE), MOON_PHASE(Kind.COMPARE), DAY_COUNT(Kind.COMPARE),
        EMPTY_SLOTS(Kind.COMPARE), OCCUPIED_SLOTS(Kind.COMPARE), CONTAINER_ITEMS(Kind.COMPARE),
        MEMORY_USED_PERCENT(Kind.COMPARE), NEAREST_PLAYER_DISTANCE(Kind.COMPARE),
        NEAREST_HOSTILE_DISTANCE(Kind.COMPARE), NEAREST_ANIMAL_DISTANCE(Kind.COMPARE),
        NEAREST_ITEM_DISTANCE(Kind.COMPARE), DROPPED_ITEMS_NEAR(Kind.COMPARE),
        XP_ORBS_NEAR(Kind.COMPARE), ARROWS_NEAR(Kind.COMPARE),
        CROSSHAIR_DISTANCE(Kind.COMPARE), SPAWN_DISTANCE(Kind.COMPARE),
        FIRE_TICKS(Kind.COMPARE), FROZEN_TICKS(Kind.COMPARE), HURT_TIME(Kind.COMPARE),
        STUCK_ARROWS(Kind.COMPARE), VEHICLE_SPEED(Kind.COMPARE), EFFECT_COUNT(Kind.COMPARE),
        WORLD_BORDER_DISTANCE(Kind.COMPARE), SERVER_TPS(Kind.COMPARE), YAW(Kind.COMPARE),
        PITCH(Kind.COMPARE), HELD_COUNT(Kind.COMPARE), MAX_HEALTH(Kind.COMPARE),
        WORLD_AGE(Kind.COMPARE), BOSSBAR_PERCENT(Kind.COMPARE),
        // Clock.
        TICK_EVERY(Kind.NUMBER);

        public final Kind kind;
        Trigger(Kind kind) { this.kind = kind; }
        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    public static final class Rule {
        public int id;
        public String trigger;
        public String filter;      // TEXT contains-filter
        public double threshold;   // NUMBER value
        public String selector;    // ENTITY_* families
        public String block;       // BLOCK_*/ITEM_COUNT families (block or item id)
        public double radius = -1; // ENTITY_*/BLOCK_* families; -1 = unlimited
        public String compare;     // above|below|equals
        public double amount;      // COMPARE/COUNT comparison value
        public double[] region;    // REGION families: x1 y1 z1 x2 y2 z2
        public String mode;        // null/"instant" | "sustained" | "changes" | "count"
        public double window;      // seconds, for sustained/changes/count modes
        public String command;
        transient int lastFiredTick = Integer.MIN_VALUE;
        transient boolean armed = true;
        transient EntitySelector parsedSelector;
        transient boolean selectorBroken;
        transient Block cachedBlock;
        transient Item cachedItem;
        transient java.util.ArrayDeque<double[]> history; // (tick, value) for 'changes'
        transient java.util.ArrayDeque<Integer> eventTicks; // for 'count' frequency mode
        transient int sustainedSince = -1;

        public Trigger triggerType() { return Trigger.valueOf(trigger.toUpperCase(Locale.ROOT)); }
    }

    public static final class Schedule {
        public int id;
        public String command;
        public int intervalTicks;  // 0 = one-shot
        transient int remaining;
    }

    private static final List<Rule> RULES = new CopyOnWriteArrayList<>();
    private static final List<Schedule> SCHEDULES = new CopyOnWriteArrayList<>();
    private static int nextId = 1;
    private static int tick;
    private static Snapshot previous;
    private static int lastMovedTick;
    private static Map<Integer, TrackedEntity> trackedEntities = Map.of();
    private static Map<String, String> playerGamemodes = Map.of();
    private static Map<String, Integer> containerCounts = Map.of();
    private static double serverTps = 20.0;
    private static long tpsWorldTimeAnchor = -1L;
    private static int tpsClientTickAnchor;
    private static volatile double bossbarPercent;
    private static String sidebarTitle;
    private static Map<String, Integer> sidebarScores = Map.of();
    /** Item-entity ids whose disappearance the pickup packet already explained. */
    private static final Set<Integer> explainedPickups = new HashSet<>();

    private EventRuleEngine() {}

    public static void register() {
        load();
        ClientTickEvents.END_CLIENT_TICK.register(EventRuleEngine::pollTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            fireText(overlay ? Trigger.ACTIONBAR : Trigger.CHAT, message.getString());
            if (!overlay) fireMention(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> {
            fireText(Trigger.CHAT, message.getString());
            fireMention(message.getString());
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClient()) fireText(Trigger.ATTACK_BLOCK, pos.toShortString());
            return ActionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient()) fireText(Trigger.USE_BLOCK, hit.getBlockPos().toShortString());
            return ActionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient()) fireText(Trigger.ATTACK_ENTITY, entity.getName().getString());
            return ActionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient()) fireText(Trigger.USE_ENTITY, entity.getName().getString());
            return ActionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient()) fireText(Trigger.USE_ITEM,
                    Registries.ITEM.getId(player.getStackInHand(hand).getItem()).toString());
            return ActionResult.PASS;
        });
    }

    /* ------------------------------------------------------------------ rule management */

    public static synchronized int addRule(Rule rule) {
        rule.id = nextId++;
        RULES.add(rule);
        save();
        return rule.id;
    }

    public static synchronized int addSchedule(String command, int intervalTicks, int delayTicks) {
        Schedule schedule = new Schedule();
        schedule.id = nextId++;
        schedule.command = command;
        schedule.intervalTicks = intervalTicks;
        schedule.remaining = delayTicks;
        SCHEDULES.add(schedule);
        if (intervalTicks > 0) save(); // one-shots are session-only by design
        return schedule.id;
    }

    public static synchronized boolean remove(int id) {
        boolean removed = RULES.removeIf(rule -> rule.id == id)
                | SCHEDULES.removeIf(schedule -> schedule.id == id);
        if (removed) save();
        return removed;
    }

    public static synchronized void clear() {
        RULES.clear();
        SCHEDULES.clear();
        save();
    }

    public static List<Rule> rules() { return List.copyOf(RULES); }
    public static List<Schedule> schedules() { return List.copyOf(SCHEDULES); }

    /* ------------------------------------------------------------------ external entry points */

    /** Mixin entry points (InGameHudTitleMixin / SoundSystemMixin). */
    public static void onTitle(Text title) {
        if (title != null) fireText(Trigger.TITLE, title.getString());
    }

    public static void onSubtitle(Text subtitle) {
        if (subtitle != null) fireText(Trigger.SUBTITLE, subtitle.getString());
    }

    public static void onSound(String soundId) {
        fireText(Trigger.SOUND, soundId);
    }

    /** Generic S2C packet trigger (netty thread; skipped entirely unless a rule wants it). */
    public static void onPacket(net.minecraft.network.packet.Packet<?> packet) {
        boolean wanted = false;
        for (Rule rule : RULES) {
            if (rule.triggerType() == Trigger.PACKET_RECEIVED) { wanted = true; break; }
        }
        if (!wanted || packet == null) return;
        fireText(Trigger.PACKET_RECEIVED, packet.getPacketType().id().toString());
    }

    public static void onExplosion(net.minecraft.util.math.Vec3d center) {
        fireText(Trigger.EXPLOSION, String.format(Locale.ROOT, "%.0f %.0f %.0f",
                center.x, center.y, center.z));
    }

    public static void onBossBarAdd(String name, float percent) {
        bossbarPercent = percent * 100.0;
        fireText(Trigger.BOSSBAR_SHOWN, name);
    }

    public static void onBossBarProgress(float percent) {
        bossbarPercent = percent * 100.0;
        fireText(Trigger.BOSSBAR_UPDATED, String.format(Locale.ROOT, "%.0f%%", percent * 100.0));
    }

    public static void onBossBarName(String name) {
        fireText(Trigger.BOSSBAR_UPDATED, name);
    }

    public static void onBossBarRemove() {
        bossbarPercent = 0.0;
        fireText(Trigger.BOSSBAR_REMOVED, "");
    }

    /** Entity status bytes: 35 is a totem pop; everything else is exposed generically. */
    public static void onEntityStatus(Entity entity, byte status) {
        String label = entity == null ? "unknown" : entityLabel(entity);
        if (status == 35) fireText(Trigger.TOTEM_POPPED, label);
        fireText(Trigger.ENTITY_STATUS, label + ": " + status);
    }

    public static void onParticle(String particleId, double x, double y, double z) {
        boolean wanted = false;
        for (Rule rule : RULES) {
            if (rule.triggerType() == Trigger.PARTICLE_SEEN) { wanted = true; break; }
        }
        if (!wanted) return; // particle packets are frequent; skip the string work
        fireText(Trigger.PARTICLE_SEEN, particleId + " @ "
                + String.format(Locale.ROOT, "%.0f %.0f %.0f", x, y, z));
    }

    /**
     * Authoritative pickup attribution: the server's ItemPickupAnimation packet names the
     * exact collector entity — no proximity guessing, no ambiguity between nearby mobs.
     * Runs on the client thread at the HEAD of the vanilla handler, before the item entity
     * is discarded, so the stack is still readable.
     */
    public static void onItemPickup(int itemEntityId, int collectorEntityId, int amount) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        Entity itemEntity = client.world.getEntityById(itemEntityId);
        Entity collector = client.world.getEntityById(collectorEntityId);
        String stack = itemEntity instanceof net.minecraft.entity.ItemEntity item
                ? Registries.ITEM.getId(item.getStack().getItem()) + " x" + amount
                : "unknown x" + amount;
        String who = collector == null ? "entity#" + collectorEntityId : entityLabel(collector);
        explainedPickups.add(itemEntityId);
        fireText(Trigger.ITEM_PICKED_UP, stack + " by " + who);
    }

    /** MENTION fires only when a message contains the local player's own name. */
    private static void fireMention(String message) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return;
        String name = player.getName().getString();
        if (!name.isEmpty() && message.toLowerCase(Locale.ROOT)
                .contains(name.toLowerCase(Locale.ROOT))) {
            fireText(Trigger.MENTION, message);
        }
    }

    /* ------------------------------------------------------------------ firing */

    private static void fireText(Trigger trigger, String value) {
        for (Rule rule : RULES) {
            if (rule.triggerType() != trigger) continue;
            if (rule.filter != null && !rule.filter.isEmpty()
                    && !value.toLowerCase(Locale.ROOT).contains(rule.filter.toLowerCase(Locale.ROOT))) {
                continue;
            }
            // Frequency mode: fire only when N matching events land inside the window.
            if ("count".equals(rule.mode)) {
                if (rule.eventTicks == null) rule.eventTicks = new java.util.ArrayDeque<>();
                rule.eventTicks.addLast(tick);
                int horizon = tick - (int) (rule.window * 20.0);
                while (!rule.eventTicks.isEmpty() && rule.eventTicks.peekFirst() < horizon) {
                    rule.eventTicks.removeFirst();
                }
                if (rule.eventTicks.size() >= (int) rule.amount) {
                    rule.eventTicks.clear();
                    fire(rule, value);
                }
                continue;
            }
            fire(rule, value);
        }
    }

    private static void fireState(Trigger trigger, boolean was, boolean is, String value) {
        if (was || !is) return; // fire on entering the state only
        fireText(trigger, value);
    }

    private static void fire(Rule rule, String value) {
        if (tick - rule.lastFiredTick < RULE_COOLDOWN_TICKS) return;
        rule.lastFiredTick = tick;
        runCommand(substitute(rule.command, value));
    }

    private static String substitute(String command, String value) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        String result = command.replace("{value}", value == null ? "" : value);
        if (player != null) {
            result = result
                    .replace("{health}", String.format(Locale.ROOT, "%.1f", player.getHealth()))
                    .replace("{hunger}", Integer.toString(player.getHungerManager().getFoodLevel()))
                    .replace("{air}", Integer.toString(player.getAir()))
                    .replace("{x}", Integer.toString(player.getBlockX()))
                    .replace("{y}", Integer.toString(player.getBlockY()))
                    .replace("{z}", Integer.toString(player.getBlockZ()));
        }
        return result;
    }

    /** Client commands (e.g. glade ...) run locally; anything unhandled goes to the server. */
    public static void runCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        String stripped = command.startsWith("/") ? command.substring(1) : command;
        client.execute(() -> {
            if (client.player == null) return;
            if (stripped.startsWith("chat ")) {
                client.player.networkHandler.sendChatMessage(stripped.substring(5));
                return;
            }
            boolean handled = false;
            try {
                Class<?> internals = Class.forName(
                        "net.fabricmc.fabric.impl.command.client.ClientCommandInternals");
                handled = (Boolean) internals.getMethod("executeCommand", String.class)
                        .invoke(null, stripped);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                LOGGER.debug("Client command dispatch unavailable", exception);
            }
            if (!handled) client.player.networkHandler.sendChatCommand(stripped);
        });
    }

    /* ------------------------------------------------------------------ per-tick polling */

    private static void pollTick(MinecraftClient client) {
        tick++;

        for (Schedule schedule : SCHEDULES) {
            if (--schedule.remaining > 0) continue;
            runCommand(schedule.command);
            if (schedule.intervalTicks > 0) schedule.remaining = schedule.intervalTicks;
            else SCHEDULES.remove(schedule);
        }

        Snapshot now = Snapshot.capture(client);
        Snapshot before = previous;
        previous = now;

        // Condition families evaluate even without a prior snapshot; change families need one.
        if (client.player != null && client.world != null) {
            if (before != null && before.worldPresent
                    && !now.position.equals(before.position)) {
                lastMovedTick = tick;
            }
            updateServerTps(client);
            evaluateConditionRules(client, client.player);
            evaluateEntityEvents(client, client.player);
            evaluateContainerEvents(client, client.player,
                    before != null && before.containerOpen, now.containerOpen);
            evaluateSidebar(client);
        } else {
            trackedEntities = Map.of();
            playerGamemodes = Map.of();
            containerCounts = Map.of();
            tpsWorldTimeAnchor = -1L;
        }

        if (before == null) return;
        fireState(Trigger.WORLD_LOADED, before.worldPresent, now.worldPresent, "");
        fireState(Trigger.WORLD_UNLOADED, !before.worldPresent, !now.worldPresent, "");
        if (!now.worldPresent || !before.worldPresent) return;

        if (!before.dimension.equals(now.dimension)) {
            fireText(Trigger.DIMENSION_CHANGED, now.dimension);
        } else {
            // A same-dimension jump no legitimate tick of movement can produce: pearls,
            // chorus fruit, /tp, respawn anchors. {value} = distance.
            double jump = Math.sqrt(before.position.getSquaredDistance(now.position));
            if (jump > 12.0) fireText(Trigger.TELEPORTED,
                    String.format(Locale.ROOT, "%.0f", jump));
        }

        if (now.health < before.health - 0.01) fireText(Trigger.DAMAGE_TAKEN,
                String.format(Locale.ROOT, "%.1f", before.health - now.health));
        if (now.health > before.health + 0.01) fireText(Trigger.HEALED,
                String.format(Locale.ROOT, "%.1f", now.health - before.health));
        fireState(Trigger.DEATH, before.dead, now.dead, "");
        fireState(Trigger.RESPAWN, !before.dead, !now.dead, "");
        for (Rule rule : RULES) {
            switch (rule.triggerType()) {
                case HEALTH_BELOW -> threshold(rule, now.health < rule.threshold, now.health);
                case HEALTH_ABOVE -> threshold(rule, now.health > rule.threshold, now.health);
                case HUNGER_BELOW -> threshold(rule, now.hunger < rule.threshold, now.hunger);
                case AIR_BELOW -> threshold(rule, now.air < rule.threshold, now.air);
                case XP_LEVEL_ABOVE -> threshold(rule, now.xpLevel > rule.threshold, now.xpLevel);
                case TICK_EVERY -> {
                    if (rule.threshold >= 1.0 && tick % (int) rule.threshold == 0) {
                        fire(rule, Integer.toString(tick));
                    }
                }
                default -> { }
            }
        }

        fireState(Trigger.ON_FIRE, before.onFire, now.onFire, "");
        fireState(Trigger.FALLING, before.falling, now.falling, "");
        fireState(Trigger.SNEAKING, before.sneaking, now.sneaking, "");
        fireState(Trigger.SPRINTING, before.sprinting, now.sprinting, "");
        fireState(Trigger.SWIMMING, before.swimming, now.swimming, "");
        fireState(Trigger.GLIDING, before.gliding, now.gliding, "");
        fireState(Trigger.UNDERWATER, before.underwater, now.underwater, "");
        fireState(Trigger.SLEEPING, before.sleeping, now.sleeping, "");
        fireState(Trigger.WOKE_UP, !before.sleeping, !now.sleeping, "");
        fireState(Trigger.MOVING, before.moving, now.moving, "");
        fireState(Trigger.STOPPED, !before.moving, !now.moving, "");
        fireState(Trigger.CLIMBING, before.climbing, now.climbing, "");
        fireState(Trigger.BLOCKING, before.blocking, now.blocking, "");
        fireState(Trigger.USING_ITEM, before.usingItem, now.usingItem, "");
        fireState(Trigger.COLLIDED, before.collided, now.collided, "");
        fireState(Trigger.HURT, before.hurt, now.hurt, "");
        fireState(Trigger.FREEZING, before.freezing, now.freezing, "");
        fireState(Trigger.PROJECTILE_INCOMING, before.projectileIncoming,
                now.projectileIncoming, "");
        fireState(Trigger.WINDOW_FOCUSED, before.windowFocused, now.windowFocused, "");
        fireState(Trigger.WINDOW_UNFOCUSED, !before.windowFocused, !now.windowFocused, "");
        fireState(Trigger.HOTBAR_EMPTY, before.hotbarEmpty, now.hotbarEmpty, "");
        fireState(Trigger.ARMOR_MISSING, before.armorMissing, now.armorMissing, "");
        fireState(Trigger.CONTAINER_FULL, before.containerFull, now.containerFull, "");
        fireState(Trigger.CONTAINER_EMPTY, before.containerEmpty, now.containerEmpty, "");
        if (before.onGround && !now.onGround && now.velocityY > 0.2) {
            fireText(Trigger.JUMPED, "");
        }
        if (!before.onGround && now.onGround) {
            fireText(Trigger.LANDED, String.format(Locale.ROOT, "%.1f", before.fallDistance));
        }
        if (!before.screenName.equals(now.screenName)) {
            if (!now.screenName.isEmpty()) fireText(Trigger.SCREEN_OPENED, now.screenName);
            if (!before.screenName.isEmpty()) fireText(Trigger.SCREEN_CLOSED, before.screenName);
        }
        if (!before.offhandItem.equals(now.offhandItem)) {
            fireText(Trigger.OFFHAND_CHANGED, now.offhandItem);
        }
        fireState(Trigger.HELD_ENCHANTED, before.heldEnchanted, now.heldEnchanted, "");
        fireState(Trigger.HELD_HAS_NAME, !before.heldName.isEmpty(),
                !now.heldName.isEmpty(), now.heldName);
        if (!before.heldName.equals(now.heldName) && !now.heldName.isEmpty()) {
            fireText(Trigger.HELD_NAME_CHANGED, now.heldName);
        }
        if (!before.lookingAtEntity.equals(now.lookingAtEntity)
                && !now.lookingAtEntity.isEmpty()) {
            fireText(Trigger.LOOKING_AT_ENTITY, now.lookingAtEntity);
        }
        // entity_spawned/removed/unloaded now fire from the per-entity tracker, which knows
        // each entity's last position and can apply the chunk-loaded disambiguation.
        if (!before.vehicle.equals(now.vehicle)) {
            if (!now.vehicle.isEmpty()) fireText(Trigger.MOUNTED, now.vehicle);
            if (!before.vehicle.isEmpty()) fireText(Trigger.DISMOUNTED, before.vehicle);
        }

        if (!before.heldItem.equals(now.heldItem)) {
            fireText(Trigger.HELD_CHANGED, now.heldItem);
            if (now.heldItem.equals("minecraft:air") && before.heldDamageable) {
                fireText(Trigger.TOOL_BROKEN, before.heldItem);
            }
        }
        if (before.selectedSlot != now.selectedSlot) {
            fireText(Trigger.SLOT_CHANGED, Integer.toString(now.selectedSlot + 1));
        }
        fireState(Trigger.INVENTORY_FULL, before.inventoryFull, now.inventoryFull, "");
        fireState(Trigger.CONTAINER_OPENED, before.containerOpen, now.containerOpen, "");
        fireState(Trigger.CONTAINER_CLOSED, !before.containerOpen, !now.containerOpen, "");

        for (Map.Entry<String, Integer> entry : now.itemCounts.entrySet()) {
            int delta = entry.getValue() - before.itemCounts.getOrDefault(entry.getKey(), 0);
            if (delta > 0) fireText(Trigger.ITEM_GAINED, entry.getKey() + " x" + delta);
        }
        for (Map.Entry<String, Integer> entry : before.itemCounts.entrySet()) {
            int delta = entry.getValue() - now.itemCounts.getOrDefault(entry.getKey(), 0);
            if (delta > 0) fireText(Trigger.ITEM_LOST, entry.getKey() + " x" + delta);
        }

        for (String effect : now.effects) {
            if (!before.effects.contains(effect)) fireText(Trigger.EFFECT_ADDED, effect);
        }
        for (String effect : before.effects) {
            if (!now.effects.contains(effect)) fireText(Trigger.EFFECT_REMOVED, effect);
        }

        if (!before.lookingAt.equals(now.lookingAt) && !now.lookingAt.isEmpty()) {
            fireText(Trigger.LOOKING_AT_BLOCK, now.lookingAt);
        }
        if (!before.standingOn.equals(now.standingOn)) {
            fireText(Trigger.STANDING_ON, now.standingOn);
        }
        if (!before.feetBlock.equals(now.feetBlock)) {
            fireText(Trigger.BLOCK_AT_FEET, now.feetBlock);
        }
        if (!before.headBlock.equals(now.headBlock)) {
            fireText(Trigger.BLOCK_ABOVE_HEAD, now.headBlock);
        }
        if (!before.biome.equals(now.biome)) fireText(Trigger.BIOME_CHANGED, now.biome);
        if (before.chunkX != now.chunkX || before.chunkZ != now.chunkZ) {
            fireText(Trigger.CHUNK_CHANGED, now.chunkX + " " + now.chunkZ);
        }

        fireState(Trigger.TIME_DAY, before.day, now.day, "");
        fireState(Trigger.TIME_NIGHT, !before.day, !now.day, "");
        fireState(Trigger.WEATHER_RAIN, before.raining, now.raining, "");
        fireState(Trigger.WEATHER_CLEAR, !before.raining, !now.raining, "");

        for (String name : now.players) {
            if (!before.players.contains(name)) fireText(Trigger.PLAYER_JOINED, name);
        }
        for (String name : before.players) {
            if (!now.players.contains(name)) fireText(Trigger.PLAYER_LEFT, name);
        }
    }

    /* ------------------------------------------------------- parameterized condition rules */

    private static void evaluateConditionRules(MinecraftClient client, ClientPlayerEntity player) {
        for (Rule rule : RULES) {
            Trigger trigger = rule.triggerType();
            switch (trigger.kind) {
                case COMPARE -> evaluateComparable(rule, metric(trigger, client, player));
                case ENTITY_COUNT, ENTITY_PRESENCE -> {
                    int count = entityCount(rule, client, player);
                    if (count < 0) continue; // broken selector; skip silently
                    switch (trigger) {
                        case ENTITY_NEAR -> conditionEdge(rule, count > 0, count);
                        case ENTITY_GONE -> conditionEdge(rule, count == 0, count);
                        default -> evaluateComparable(rule, count);
                    }
                }
                case BLOCK_COUNT, BLOCK_PRESENCE -> {
                    // Cube scans are heavy; stagger rules across the scan period.
                    if ((tick + rule.id) % BLOCK_SCAN_PERIOD != 0) continue;
                    int count = blockCount(rule, client, player);
                    if (count < 0) continue;
                    if (trigger == Trigger.BLOCK_NEAR) conditionEdge(rule, count > 0, count);
                    else evaluateComparable(rule, count);
                }
                case ITEM_COUNT -> {
                    if (trigger == Trigger.HELD_ENCHANT) {
                        evaluateComparable(rule, heldEnchantLevel(rule, player));
                        continue;
                    }
                    int count = itemCount(rule, player, trigger == Trigger.HOTBAR_ITEM_COUNT);
                    if (count < 0) continue;
                    evaluateComparable(rule, count);
                }
                case REGION -> {
                    if (rule.region == null || rule.region.length != 6) continue;
                    boolean inside = player.getX() >= Math.min(rule.region[0], rule.region[3])
                            && player.getX() <= Math.max(rule.region[0], rule.region[3]) + 1
                            && player.getY() >= Math.min(rule.region[1], rule.region[4])
                            && player.getY() <= Math.max(rule.region[1], rule.region[4]) + 1
                            && player.getZ() >= Math.min(rule.region[2], rule.region[5])
                            && player.getZ() <= Math.max(rule.region[2], rule.region[5]) + 1;
                    boolean in = trigger == Trigger.LEFT_REGION ? !inside : inside;
                    conditionEdge(rule, in, 0);
                }
                default -> { }
            }
        }
    }

    /** Shared instant / sustained / windowed-net-change evaluation for any numeric observable. */
    private static void evaluateComparable(Rule rule, double value) {
        switch (rule.mode == null ? "instant" : rule.mode) {
            case "sustained" -> {
                // Condition must hold uninterrupted for the whole window to fire.
                if (!compare(rule, value)) {
                    rule.sustainedSince = -1;
                    rule.armed = true;
                } else {
                    if (rule.sustainedSince < 0) rule.sustainedSince = tick;
                    conditionEdge(rule,
                            tick - rule.sustainedSince >= (int) (rule.window * 20.0), value);
                }
            }
            case "changes" -> {
                // Net change across a sliding window: 'changes below -4 within 2' is a burst
                // of 4+ lost within two seconds, regardless of the absolute level.
                if (rule.history == null) rule.history = new java.util.ArrayDeque<>();
                rule.history.addLast(new double[] {tick, value});
                int horizon = tick - (int) (rule.window * 20.0);
                while (!rule.history.isEmpty() && rule.history.peekFirst()[0] < horizon) {
                    rule.history.removeFirst();
                }
                double net = value - rule.history.peekFirst()[1];
                conditionEdge(rule, compare(rule, net), net);
            }
            default -> conditionEdge(rule, compare(rule, value), value);
        }
    }

    private static boolean compare(Rule rule, double value) {
        if (rule.compare == null) return false;
        return switch (rule.compare) {
            case "above" -> value > rule.amount;
            case "below" -> value < rule.amount;
            default -> Math.abs(value - rule.amount) < 1.0E-3;
        };
    }

    /** Condition families fire once on becoming true and re-arm once false again. */
    private static void conditionEdge(Rule rule, boolean in, double value) {
        if (in && rule.armed) {
            rule.armed = false;
            fire(rule, value == Math.floor(value)
                    ? Long.toString((long) value)
                    : String.format(Locale.ROOT, "%.2f", value));
        } else if (!in) {
            rule.armed = true;
        }
    }

    private static double metric(Trigger trigger, MinecraftClient client,
            ClientPlayerEntity player) {
        return switch (trigger) {
            case HEALTH -> player.getHealth();
            case HUNGER -> player.getHungerManager().getFoodLevel();
            case AIR -> player.getAir();
            case XP_LEVEL -> player.experienceLevel;
            case XP_PROGRESS -> player.experienceProgress;
            case ARMOR_DURABILITY -> {
                double worst = 100.0;
                for (net.minecraft.entity.EquipmentSlot slot : new net.minecraft.entity.EquipmentSlot[] {
                        net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.entity.EquipmentSlot.CHEST,
                        net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET}) {
                    ItemStack stack = player.getEquippedStack(slot);
                    if (stack.isEmpty() || !stack.isDamageable()) continue;
                    worst = Math.min(worst,
                            (stack.getMaxDamage() - stack.getDamage()) * 100.0 / stack.getMaxDamage());
                }
                yield worst;
            }
            case FPS -> client.getCurrentFps();
            case PING -> {
                PlayerListEntry entry = player.networkHandler == null ? null
                        : player.networkHandler.getPlayerListEntry(player.getUuid());
                yield entry == null ? 0 : entry.getLatency();
            }
            case CHUNKS_LOADED -> client.world.getChunkManager().getLoadedChunkCount();
            case LIGHT_LEVEL -> client.world.getLightLevel(player.getBlockPos());
            case Y_POSITION -> player.getY();
            case X_POSITION -> player.getX();
            case Z_POSITION -> player.getZ();
            case SPEED -> player.getVelocity().horizontalLength() * 20.0;
            case SATURATION -> player.getHungerManager().getSaturationLevel();
            case ABSORPTION -> player.getAbsorptionAmount();
            case ARMOR_POINTS -> player.getArmor();
            case HELD_DURABILITY -> {
                ItemStack held = player.getMainHandStack();
                yield !held.isDamageable() ? 100.0
                        : (held.getMaxDamage() - held.getDamage()) * 100.0 / held.getMaxDamage();
            }
            case FALL_DISTANCE -> player.fallDistance;
            case TIME_TICKS -> client.world.getTimeOfDay() % 24000L;
            case ENTITY_TOTAL -> {
                int total = 0;
                for (Entity ignored : client.world.getEntities()) total++;
                yield total;
            }
            case PLAYERS_ONLINE -> player.networkHandler == null ? 0
                    : player.networkHandler.getPlayerList().size();
            case IDLE_SECONDS -> (tick - lastMovedTick) / 20.0;
            case VELOCITY_Y -> player.getVelocity().y * 20.0;
            case MOON_PHASE -> (client.world.getTimeOfDay() / 24000L) % 8L;
            case DAY_COUNT -> client.world.getTimeOfDay() / 24000L;
            case EMPTY_SLOTS -> {
                int empty = 0;
                for (int i = 0; i < player.getInventory().size(); i++) {
                    if (player.getInventory().getStack(i).isEmpty()) empty++;
                }
                yield empty;
            }
            case OCCUPIED_SLOTS -> {
                int occupied = 0;
                for (int i = 0; i < player.getInventory().size(); i++) {
                    if (!player.getInventory().getStack(i).isEmpty()) occupied++;
                }
                yield occupied;
            }
            case CONTAINER_ITEMS -> player.currentScreenHandler.slots.stream()
                    .filter(slot -> slot.inventory != player.getInventory())
                    .filter(slot -> !slot.getStack().isEmpty()).count();
            case MEMORY_USED_PERCENT -> {
                Runtime runtime = Runtime.getRuntime();
                yield (runtime.totalMemory() - runtime.freeMemory()) * 100.0 / runtime.maxMemory();
            }
            case NEAREST_PLAYER_DISTANCE -> nearestDistance(client, player,
                    entity -> entity instanceof PlayerEntity);
            case NEAREST_HOSTILE_DISTANCE -> nearestDistance(client, player,
                    entity -> entity instanceof net.minecraft.entity.mob.HostileEntity);
            case NEAREST_ANIMAL_DISTANCE -> nearestDistance(client, player,
                    entity -> entity instanceof net.minecraft.entity.passive.AnimalEntity);
            case NEAREST_ITEM_DISTANCE -> nearestDistance(client, player,
                    entity -> entity instanceof net.minecraft.entity.ItemEntity);
            case DROPPED_ITEMS_NEAR -> countNear(client, player, 16.0,
                    entity -> entity instanceof net.minecraft.entity.ItemEntity);
            case XP_ORBS_NEAR -> countNear(client, player, 16.0,
                    entity -> entity instanceof net.minecraft.entity.ExperienceOrbEntity);
            case ARROWS_NEAR -> countNear(client, player, 16.0,
                    entity -> entity instanceof net.minecraft.entity.projectile.PersistentProjectileEntity);
            case CROSSHAIR_DISTANCE -> client.crosshairTarget == null
                    || client.crosshairTarget.getType() == HitResult.Type.MISS ? 999.0
                    : client.crosshairTarget.getPos().distanceTo(player.getEyePos());
            case SPAWN_DISTANCE -> Math.sqrt(player.getBlockPos().getSquaredDistance(
                    client.world.getSpawnPoint().getPos()));
            case FIRE_TICKS -> player.getFireTicks();
            case FROZEN_TICKS -> player.getFrozenTicks();
            case HURT_TIME -> player.hurtTime;
            case STUCK_ARROWS -> player.getStuckArrowCount();
            case VEHICLE_SPEED -> player.getVehicle() == null ? 0.0
                    : player.getVehicle().getVelocity().horizontalLength() * 20.0;
            case EFFECT_COUNT -> player.getStatusEffects().size();
            case WORLD_BORDER_DISTANCE -> client.world.getWorldBorder()
                    .getDistanceInsideBorder(player);
            case SERVER_TPS -> serverTps;
            case YAW -> net.minecraft.util.math.MathHelper.wrapDegrees(player.getYaw());
            case PITCH -> player.getPitch();
            case HELD_COUNT -> player.getMainHandStack().getCount();
            case MAX_HEALTH -> player.getMaxHealth();
            case WORLD_AGE -> client.world.getTime();
            case BOSSBAR_PERCENT -> bossbarPercent;
            default -> 0.0;
        };
    }

    /** Public readout used by /glade get; identical to what rules compare against. */
    public static double metricValue(Trigger trigger, MinecraftClient client,
            ClientPlayerEntity player) {
        return metric(trigger, client, player);
    }

    private static double nearestDistance(MinecraftClient client, ClientPlayerEntity player,
            java.util.function.Predicate<Entity> filter) {
        double best = 999.0;
        for (Entity entity : client.world.getEntities()) {
            if (entity == player || !filter.test(entity)) continue;
            best = Math.min(best, Math.sqrt(entity.squaredDistanceTo(player)));
        }
        return best;
    }

    private static int countNear(MinecraftClient client, ClientPlayerEntity player,
            double radius, java.util.function.Predicate<Entity> filter) {
        int count = 0;
        double radiusSquared = radius * radius;
        for (Entity entity : client.world.getEntities()) {
            if (entity == player || !filter.test(entity)) continue;
            if (entity.squaredDistanceTo(player) <= radiusSquared) count++;
        }
        return count;
    }

    private static int entityCount(Rule rule, MinecraftClient client, ClientPlayerEntity player) {
        if (rule.selectorBroken) return -1;
        if (rule.parsedSelector == null) {
            String[] error = new String[1];
            rule.parsedSelector = EntitySelector.parse(rule.selector, error);
            if (rule.parsedSelector == null) {
                rule.selectorBroken = true;
                LOGGER.warn("Rule #{} has an invalid selector {}: {}", rule.id, rule.selector, error[0]);
                return -1;
            }
        }
        EntitySelector selector = rule.parsedSelector;
        double radiusSquared = rule.radius < 0 ? Double.MAX_VALUE : rule.radius * rule.radius;
        if (selector.kind() == EntitySelector.Kind.SELF) return 1;
        int count = 0;
        for (Entity entity : client.world.getEntities()) {
            if (entity == player) continue;
            boolean playersOnly = selector.kind() == EntitySelector.Kind.PLAYERS_ALL
                    || selector.kind() == EntitySelector.Kind.PLAYER_NEAREST;
            if (playersOnly && !(entity instanceof PlayerEntity)) continue;
            if (selector.kind() == EntitySelector.Kind.ENTITIES
                    && !selector.matchesFilters(entity)) continue;
            if (entity.squaredDistanceTo(player) > radiusSquared) continue;
            count++;
            if (selector.kind() == EntitySelector.Kind.PLAYER_NEAREST && count > 0) break;
        }
        return count;
    }

    private static int blockCount(Rule rule, MinecraftClient client, ClientPlayerEntity player) {
        if (rule.cachedBlock == null) {
            Identifier id = Identifier.tryParse(rule.block.contains(":")
                    ? rule.block : "minecraft:" + rule.block);
            if (id == null || !Registries.BLOCK.containsId(id)) return -1;
            rule.cachedBlock = Registries.BLOCK.get(id);
        }
        int radius = (int) Math.max(1, Math.min(MAX_BLOCK_RADIUS, rule.radius));
        BlockPos center = player.getBlockPos();
        int count = 0;
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -radius, -radius),
                center.add(radius, radius, radius))) {
            if (client.world.getBlockState(pos).getBlock() == rule.cachedBlock) count++;
        }
        return count;
    }

    private static int itemCount(Rule rule, ClientPlayerEntity player, boolean hotbarOnly) {
        if (rule.cachedItem == null) {
            Identifier id = Identifier.tryParse(rule.block.contains(":")
                    ? rule.block : "minecraft:" + rule.block);
            if (id == null || !Registries.ITEM.containsId(id)) return -1;
            rule.cachedItem = Registries.ITEM.get(id);
        }
        int total = 0;
        int size = hotbarOnly ? 9 : player.getInventory().size();
        for (int i = 0; i < size; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == rule.cachedItem) total += stack.getCount();
        }
        return total;
    }

    /* ------------------------------------------------------- per-entity deep tracking */

    /** Everything the client actually syncs about a foreign entity, one record per tick. */
    private record TrackedEntity(String type, String label, boolean living, boolean isItem,
            boolean isProjectile, boolean isPlayer, boolean isPearl, boolean isPotion,
            String ownerLabel, String hand, String offhand, String armor,
            boolean hurt, boolean burning, int vehicleId, String vehicleType, boolean sneaking,
            boolean sprinting, boolean usingItem, boolean blocking, boolean gliding,
            boolean swimming, boolean sleeping, boolean baby, float health, String profession,
            int level, double x, double y, double z, String itemStack) {}

    private static final Set<Trigger> ENTITY_EVENT_TRIGGERS = Set.of(
            Trigger.ENTITY_HELD_CHANGED, Trigger.ENTITY_OFFHAND_CHANGED,
            Trigger.ENTITY_ARMOR_CHANGED, Trigger.ENTITY_HURT, Trigger.ENTITY_DIED,
            Trigger.ENTITY_DAMAGED, Trigger.ENTITY_HEALED, Trigger.ENTITY_STARTED_BURNING,
            Trigger.ENTITY_MOUNTED, Trigger.ENTITY_DISMOUNTED, Trigger.ENTITY_SNEAKING,
            Trigger.ENTITY_SPRINTING, Trigger.ENTITY_USING_ITEM, Trigger.ENTITY_BLOCKING,
            Trigger.ENTITY_GLIDING, Trigger.ENTITY_SWIMMING, Trigger.ENTITY_SLEEPING,
            Trigger.ENTITY_BABY_GROWN, Trigger.VILLAGER_PROFESSION_CHANGED,
            Trigger.VILLAGER_LEVEL_CHANGED, Trigger.PLAYER_HELD_CHANGED,
            Trigger.PLAYER_OFFHAND_CHANGED, Trigger.PLAYER_ARMOR_CHANGED,
            Trigger.PLAYER_GAMEMODE_CHANGED, Trigger.ITEM_SPAWNED, Trigger.ITEM_PICKED_UP,
            Trigger.ITEM_DESPAWNED, Trigger.PROJECTILE_LAUNCHED, Trigger.ENTITY_SPAWNED,
            Trigger.ENTITY_REMOVED, Trigger.ENTITY_UNLOADED, Trigger.ITEM_UNLOADED,
            Trigger.PEARL_THROWN, Trigger.PEARL_LANDED, Trigger.POTION_SPLASHED,
            Trigger.POTION_DRANK);

    private static void evaluateEntityEvents(MinecraftClient client, ClientPlayerEntity player) {
        boolean anyRule = false;
        for (Rule rule : RULES) {
            if (ENTITY_EVENT_TRIGGERS.contains(rule.triggerType())) { anyRule = true; break; }
            if (rule.triggerType() == Trigger.PLAYER_GAMEMODE_CHANGED) { anyRule = true; break; }
        }
        if (!anyRule) { trackedEntities = Map.of(); playerGamemodes = Map.of(); return; }

        Map<Integer, TrackedEntity> now = new HashMap<>();
        for (Entity entity : client.world.getEntities()) {
            now.put(entity.getId(), track(entity));
        }
        Map<Integer, TrackedEntity> before = trackedEntities;
        trackedEntities = now;
        if (!before.isEmpty()) {
            for (Map.Entry<Integer, TrackedEntity> entry : now.entrySet()) {
                TrackedEntity current = entry.getValue();
                TrackedEntity past = before.get(entry.getKey());
                if (past == null) {
                    fireText(Trigger.ENTITY_SPAWNED, current.label);
                    if (current.isItem) fireText(Trigger.ITEM_SPAWNED, current.itemStack);
                    if (current.isProjectile) {
                        String by = current.ownerLabel.isEmpty() ? ""
                                : " by " + current.ownerLabel;
                        fireText(Trigger.PROJECTILE_LAUNCHED, current.label + by);
                        if (current.isPearl) fireText(Trigger.PEARL_THROWN, current.label + by);
                    }
                    continue;
                }
                diffEntity(past, current);
            }
            for (Map.Entry<Integer, TrackedEntity> entry : before.entrySet()) {
                if (now.containsKey(entry.getKey())) continue;
                TrackedEntity gone = entry.getValue();
                // The pickup packet is the authoritative explanation; anything it claimed
                // needs no further interpretation here.
                if (gone.isItem && explainedPickups.remove(entry.getKey())) continue;
                // No timers, no guessing: if the chunk at the entity's last position is
                // still loaded, it truly vanished there; otherwise it merely unloaded
                // with its chunk and may come back.
                boolean chunkStillLoaded = client.world.isChunkLoaded(
                        ((int) Math.floor(gone.x)) >> 4, ((int) Math.floor(gone.z)) >> 4);
                if (gone.isItem) {
                    fireText(chunkStillLoaded ? Trigger.ITEM_DESPAWNED : Trigger.ITEM_UNLOADED,
                            gone.itemStack);
                    continue;
                }
                if (chunkStillLoaded && (gone.isPearl || gone.isPotion)) {
                    String by = gone.ownerLabel.isEmpty() ? "" : " by " + gone.ownerLabel;
                    String where = String.format(Locale.ROOT, "%.0f %.0f %.0f",
                            gone.x, gone.y, gone.z);
                    fireText(gone.isPearl ? Trigger.PEARL_LANDED : Trigger.POTION_SPLASHED,
                            where + by);
                }
                if (!chunkStillLoaded) {
                    fireText(Trigger.ENTITY_UNLOADED, gone.label);
                } else if (gone.living && gone.health <= 0.0F) {
                    fireText(Trigger.ENTITY_DIED, gone.label);
                } else {
                    fireText(Trigger.ENTITY_REMOVED, gone.label);
                }
            }
        }
        explainedPickups.clear();

        // Gamemodes come from the tab list, not the entity, and cover the local player too.
        Map<String, String> modes = new HashMap<>();
        if (player.networkHandler != null) {
            for (PlayerListEntry entry : player.networkHandler.getPlayerList()) {
                modes.put(entry.getProfile().name(),
                        entry.getGameMode() == null ? "?" : entry.getGameMode().name());
            }
        }
        if (!playerGamemodes.isEmpty()) {
            for (Map.Entry<String, String> entry : modes.entrySet()) {
                String old = playerGamemodes.get(entry.getKey());
                if (old != null && !old.equals(entry.getValue())) {
                    fireText(Trigger.PLAYER_GAMEMODE_CHANGED,
                            entry.getKey() + ": " + entry.getValue().toLowerCase(Locale.ROOT));
                }
            }
        }
        playerGamemodes = modes;
    }

    private static void diffEntity(TrackedEntity past, TrackedEntity now) {
        String label = now.label;
        if (!past.hand.equals(now.hand)) {
            fireText(Trigger.ENTITY_HELD_CHANGED, label + ": " + now.hand);
            if (now.isPlayer) fireText(Trigger.PLAYER_HELD_CHANGED, label + ": " + now.hand);
        }
        if (!past.offhand.equals(now.offhand)) {
            fireText(Trigger.ENTITY_OFFHAND_CHANGED, label + ": " + now.offhand);
            if (now.isPlayer) fireText(Trigger.PLAYER_OFFHAND_CHANGED, label + ": " + now.offhand);
        }
        if (!past.armor.equals(now.armor)) {
            fireText(Trigger.ENTITY_ARMOR_CHANGED, label + ": " + now.armor);
            if (now.isPlayer) fireText(Trigger.PLAYER_ARMOR_CHANGED, label + ": " + now.armor);
        }
        if (!past.hurt && now.hurt) fireText(Trigger.ENTITY_HURT, label);
        if (now.living && past.health > 0.0F && now.health <= 0.0F) {
            fireText(Trigger.ENTITY_DIED, label);
        }
        if (now.living && now.health < past.health - 0.01F) {
            fireText(Trigger.ENTITY_DAMAGED, label + ": -" + String.format(Locale.ROOT,
                    "%.1f", past.health - now.health));
        }
        if (now.living && now.health > past.health + 0.01F && past.health > 0.0F) {
            fireText(Trigger.ENTITY_HEALED, label + ": +" + String.format(Locale.ROOT,
                    "%.1f", now.health - past.health));
        }
        if (!past.burning && now.burning) fireText(Trigger.ENTITY_STARTED_BURNING, label);
        if (past.vehicleId != now.vehicleId) {
            if (now.vehicleId != -1) fireText(Trigger.ENTITY_MOUNTED,
                    label + " -> " + now.vehicleType);
            if (past.vehicleId != -1) fireText(Trigger.ENTITY_DISMOUNTED,
                    label + " <- " + past.vehicleType);
        }
        if (!past.sneaking && now.sneaking) fireText(Trigger.ENTITY_SNEAKING, label);
        if (!past.sprinting && now.sprinting) fireText(Trigger.ENTITY_SPRINTING, label);
        if (!past.usingItem && now.usingItem) fireText(Trigger.ENTITY_USING_ITEM,
                label + ": " + now.hand);
        if (!past.blocking && now.blocking) fireText(Trigger.ENTITY_BLOCKING, label);
        if (!past.gliding && now.gliding) fireText(Trigger.ENTITY_GLIDING, label);
        if (!past.swimming && now.swimming) fireText(Trigger.ENTITY_SWIMMING, label);
        if (!past.sleeping && now.sleeping) fireText(Trigger.ENTITY_SLEEPING, label);
        if (past.baby && !now.baby) fireText(Trigger.ENTITY_BABY_GROWN, label);
        // Finishing a use-cycle while holding a potion is a completed drink (self included).
        if (past.usingItem && !now.usingItem && past.hand.contains("potion")) {
            fireText(Trigger.POTION_DRANK, label + ": " + past.hand);
        }
        if (!past.profession.equals(now.profession) && !now.profession.isEmpty()) {
            fireText(Trigger.VILLAGER_PROFESSION_CHANGED, label + ": " + now.profession);
        }
        if (past.level != now.level && now.level > 0) {
            fireText(Trigger.VILLAGER_LEVEL_CHANGED, label + ": " + now.level);
        }
    }

    /**
     * Stable identity string: {@code type#networkId[/name]}. The network id is unique for
     * the session and is how follow-up commands can target the exact same entity; players
     * and named mobs also carry their name.
     */
    public static String entityLabel(Entity entity) {
        String type = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
        String label = type + "#" + entity.getId();
        return entity instanceof PlayerEntity || entity.hasCustomName()
                ? label + "/" + entity.getName().getString() : label;
    }

    private static TrackedEntity track(Entity entity) {
        String type = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
        String label = entityLabel(entity);
        boolean living = entity instanceof net.minecraft.entity.LivingEntity;
        String hand = "", offhand = "", armor = "";
        boolean usingItem = false, blocking = false, sleeping = false, baby = false;
        float health = 0.0F;
        String profession = "";
        int level = 0;
        if (living) {
            net.minecraft.entity.LivingEntity livingEntity =
                    (net.minecraft.entity.LivingEntity) entity;
            hand = Registries.ITEM.getId(livingEntity.getMainHandStack().getItem()).toString();
            offhand = Registries.ITEM.getId(livingEntity.getOffHandStack().getItem()).toString();
            StringBuilder armorBuilder = new StringBuilder();
            for (net.minecraft.entity.EquipmentSlot slot : new net.minecraft.entity.EquipmentSlot[] {
                    net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.entity.EquipmentSlot.CHEST,
                    net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET}) {
                if (!armorBuilder.isEmpty()) armorBuilder.append(',');
                armorBuilder.append(Registries.ITEM.getId(
                        livingEntity.getEquippedStack(slot).getItem()).getPath());
            }
            armor = armorBuilder.toString();
            usingItem = livingEntity.isUsingItem();
            blocking = livingEntity.isBlocking();
            sleeping = livingEntity.isSleeping();
            baby = livingEntity.isBaby();
            health = livingEntity.getHealth();
            if (entity instanceof net.minecraft.entity.passive.VillagerEntity villager) {
                profession = villager.getVillagerData().profession().getIdAsString();
                level = villager.getVillagerData().level();
            }
        }
        String itemStack = "";
        boolean isItem = entity instanceof net.minecraft.entity.ItemEntity;
        if (isItem) {
            ItemStack stack = ((net.minecraft.entity.ItemEntity) entity).getStack();
            itemStack = Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount();
        }
        boolean isProjectile = entity instanceof net.minecraft.entity.projectile.ProjectileEntity;
        String ownerLabel = "";
        if (isProjectile) {
            Entity owner = ((net.minecraft.entity.projectile.ProjectileEntity) entity).getOwner();
            if (owner != null) ownerLabel = entityLabel(owner);
        }
        return new TrackedEntity(type, label, living, isItem, isProjectile,
                entity instanceof PlayerEntity,
                entity instanceof net.minecraft.entity.projectile.thrown.EnderPearlEntity,
                entity instanceof net.minecraft.entity.projectile.thrown.PotionEntity,
                ownerLabel, hand, offhand, armor,
                living && ((net.minecraft.entity.LivingEntity) entity).hurtTime > 0,
                entity.getFireTicks() > 0,
                entity.getVehicle() == null ? -1 : entity.getVehicle().getId(),
                entity.getVehicle() == null ? "" : Registries.ENTITY_TYPE.getId(
                        entity.getVehicle().getType()).toString(),
                entity.isSneaking(), entity.isSprinting(), usingItem, blocking,
                living && ((net.minecraft.entity.LivingEntity) entity).isGliding(),
                entity.isSwimming(), sleeping, baby, health, profession, level,
                entity.getX(), entity.getY(), entity.getZ(), itemStack);
    }

    /** Open-container content diffs plus the container's title on open. */
    private static void evaluateContainerEvents(MinecraftClient client,
            ClientPlayerEntity player, boolean wasOpen, boolean isOpen) {
        if (!isOpen) { containerCounts = Map.of(); return; }
        if (!wasOpen && client.currentScreen != null) {
            fireText(Trigger.CONTAINER_TITLE, client.currentScreen.getTitle().getString());
        }
        Map<String, Integer> now = new HashMap<>();
        for (net.minecraft.screen.slot.Slot slot : player.currentScreenHandler.slots) {
            if (slot.inventory == player.getInventory() || slot.getStack().isEmpty()) continue;
            now.merge(Registries.ITEM.getId(slot.getStack().getItem()).toString(),
                    slot.getStack().getCount(), Integer::sum);
        }
        Map<String, Integer> before = containerCounts;
        containerCounts = now;
        if (wasOpen && !before.isEmpty() || wasOpen && !now.isEmpty()) {
            for (Map.Entry<String, Integer> entry : now.entrySet()) {
                int delta = entry.getValue() - before.getOrDefault(entry.getKey(), 0);
                if (delta > 0) fireText(Trigger.CONTAINER_ITEM_GAINED,
                        entry.getKey() + " x" + delta);
            }
            for (Map.Entry<String, Integer> entry : before.entrySet()) {
                int delta = entry.getValue() - now.getOrDefault(entry.getKey(), 0);
                if (delta > 0) fireText(Trigger.CONTAINER_ITEM_LOST,
                        entry.getKey() + " x" + delta);
            }
        }
    }

    /** Scoreboard sidebar: appearance, title, and per-line score diffs. */
    private static void evaluateSidebar(MinecraftClient client) {
        boolean wanted = false;
        for (Rule rule : RULES) {
            switch (rule.triggerType()) {
                case SIDEBAR_APPEARED, SIDEBAR_REMOVED, SIDEBAR_TITLE_CHANGED,
                        SIDEBAR_SCORE_CHANGED, SIDEBAR_LINE_ADDED, SIDEBAR_LINE_REMOVED ->
                        wanted = true;
                default -> { }
            }
        }
        if (!wanted) { sidebarTitle = null; sidebarScores = Map.of(); return; }

        net.minecraft.scoreboard.ScoreboardObjective objective = client.world.getScoreboard()
                .getObjectiveForSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
        if (objective == null) {
            if (sidebarTitle != null) fireText(Trigger.SIDEBAR_REMOVED, "");
            sidebarTitle = null;
            sidebarScores = Map.of();
            return;
        }
        String title = objective.getDisplayName().getString();
        Map<String, Integer> scores = new HashMap<>();
        for (net.minecraft.scoreboard.ScoreboardEntry entry
                : client.world.getScoreboard().getScoreboardEntries(objective)) {
            scores.put(entry.owner(), entry.value());
        }
        if (sidebarTitle == null) {
            fireText(Trigger.SIDEBAR_APPEARED, title);
        } else {
            if (!sidebarTitle.equals(title)) fireText(Trigger.SIDEBAR_TITLE_CHANGED, title);
            for (Map.Entry<String, Integer> entry : scores.entrySet()) {
                Integer old = sidebarScores.get(entry.getKey());
                if (old == null) fireText(Trigger.SIDEBAR_LINE_ADDED, entry.getKey());
                else if (!old.equals(entry.getValue())) fireText(Trigger.SIDEBAR_SCORE_CHANGED,
                        entry.getKey() + ": " + entry.getValue());
            }
            for (String owner : sidebarScores.keySet()) {
                if (!scores.containsKey(owner)) fireText(Trigger.SIDEBAR_LINE_REMOVED, owner);
            }
        }
        sidebarTitle = title;
        sidebarScores = scores;
    }

    /** Held-item enchantment level for HELD_ENCHANT rules; 0 when absent. */
    private static int heldEnchantLevel(Rule rule, ClientPlayerEntity player) {
        var enchantments = player.getMainHandStack().getEnchantments();
        if (enchantments.isEmpty()) return 0;
        String wanted = rule.block.contains(":") ? rule.block : "minecraft:" + rule.block;
        for (var entry : enchantments.getEnchantments()) {
            if (entry.getIdAsString().equals(wanted)) return enchantments.getLevel(entry);
        }
        return 0;
    }

    /** Server TPS estimated from world-time advancement over a rolling five-second window. */
    private static void updateServerTps(MinecraftClient client) {
        long worldTime = client.world.getTime();
        if (tpsWorldTimeAnchor < 0L) {
            tpsWorldTimeAnchor = worldTime;
            tpsClientTickAnchor = tick;
            return;
        }
        int clientTicks = tick - tpsClientTickAnchor;
        if (clientTicks >= 100) {
            serverTps = Math.min(20.0, (worldTime - tpsWorldTimeAnchor) * 20.0 / clientTicks);
            tpsWorldTimeAnchor = worldTime;
            tpsClientTickAnchor = tick;
        }
    }

    /** Threshold rules fire once on crossing and re-arm only after leaving the zone. */
    private static void threshold(Rule rule, boolean inZone, double value) {
        if (inZone && rule.armed) {
            rule.armed = false;
            fire(rule, String.format(Locale.ROOT, "%.1f", value));
        } else if (!inZone) {
            rule.armed = true;
        }
    }

    private record Snapshot(boolean worldPresent, String dimension, float health, boolean dead,
            int hunger, int air, int xpLevel, boolean onFire, boolean falling, boolean sneaking,
            boolean sprinting, boolean swimming, boolean gliding, boolean underwater,
            boolean sleeping, String vehicle, String heldItem, boolean heldDamageable,
            int selectedSlot, boolean inventoryFull, boolean containerOpen, Set<String> effects,
            Map<String, Integer> itemCounts, String lookingAt, String standingOn,
            String feetBlock, String headBlock, String biome, int chunkX, int chunkZ,
            boolean day, boolean raining, Set<String> players, BlockPos position,
            boolean moving, boolean climbing, boolean blocking, boolean usingItem,
            boolean collided, boolean hurt, boolean freezing, boolean onGround,
            float fallDistance, double velocityY, boolean windowFocused, String screenName,
            String offhandItem, String lookingAtEntity, boolean hotbarEmpty,
            boolean armorMissing, boolean containerFull, boolean containerEmpty,
            boolean projectileIncoming, boolean heldEnchanted, String heldName) {

        static Snapshot capture(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            if (player == null || client.world == null) {
                return new Snapshot(false, "", 0, false, 0, 0, 0, false, false, false, false,
                        false, false, false, false, "", "", false, 0, false, false, Set.of(),
                        Map.of(), "", "", "", "", "", 0, 0, false, false, Set.of(), BlockPos.ORIGIN,
                        false, false, false, false, false, false, false, false, 0.0F, 0.0,
                        false, "", "", "", false, false, false, false, false, false, "");
            }
            ItemStack held = player.getMainHandStack();
            Set<String> effects = new HashSet<>();
            for (StatusEffectInstance instance : player.getStatusEffects()) {
                effects.add(instance.getEffectType().getIdAsString());
            }
            Set<String> players = new HashSet<>();
            if (player.networkHandler != null) {
                for (PlayerListEntry entry : player.networkHandler.getPlayerList()) {
                    players.add(entry.getProfile().name());
                }
            }
            Map<String, Integer> itemCounts = new HashMap<>();
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (stack.isEmpty()) continue;
                itemCounts.merge(Registries.ITEM.getId(stack.getItem()).toString(),
                        stack.getCount(), Integer::sum);
            }
            boolean containerOpen = player.currentScreenHandler.slots.stream()
                    .anyMatch(slot -> slot.inventory != player.getInventory());
            String lookingAt = client.crosshairTarget instanceof BlockHitResult hit
                    && client.crosshairTarget.getType() == HitResult.Type.BLOCK
                    ? blockId(client, hit.getBlockPos()) : "";
            BlockPos feet = player.getBlockPos();
            long timeOfDay = client.world.getTimeOfDay() % 24000L;

            boolean hotbarEmpty = true;
            for (int i = 0; i < 9 && hotbarEmpty; i++) {
                hotbarEmpty = player.getInventory().getStack(i).isEmpty();
            }
            boolean armorMissing = false;
            for (net.minecraft.entity.EquipmentSlot slot : new net.minecraft.entity.EquipmentSlot[] {
                    net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.entity.EquipmentSlot.CHEST,
                    net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET}) {
                if (player.getEquippedStack(slot).isEmpty()) armorMissing = true;
            }
            int containerSlots = 0;
            int containerFilled = 0;
            for (net.minecraft.screen.slot.Slot slot : player.currentScreenHandler.slots) {
                if (slot.inventory == player.getInventory()) continue;
                containerSlots++;
                if (!slot.getStack().isEmpty()) containerFilled++;
            }
            String lookingAtEntity = client.crosshairTarget
                    instanceof net.minecraft.util.hit.EntityHitResult entityHit
                    ? Registries.ENTITY_TYPE.getId(entityHit.getEntity().getType()).toString() : "";
            boolean projectileIncoming = false;
            for (Entity entity : client.world.getEntities()) {
                if (!projectileIncoming
                        && entity instanceof net.minecraft.entity.projectile.PersistentProjectileEntity
                        && entity.squaredDistanceTo(player) < 144.0
                        && entity.getVelocity().lengthSquared() > 0.01) {
                    var toPlayer = player.getEyePos().subtract(
                            entity.getX(), entity.getY(), entity.getZ());
                    projectileIncoming = entity.getVelocity().dotProduct(toPlayer) > 0.0;
                }
            }
            return new Snapshot(true,
                    client.world.getRegistryKey().getValue().toString(),
                    player.getHealth(), player.getHealth() <= 0.0F,
                    player.getHungerManager().getFoodLevel(), player.getAir(),
                    player.experienceLevel, player.isOnFire(), player.fallDistance > 3.0,
                    player.isSneaking(), player.isSprinting(), player.isSwimming(),
                    player.isGliding(), player.isSubmergedInWater(), player.isSleeping(),
                    player.getVehicle() == null ? ""
                            : Registries.ENTITY_TYPE.getId(player.getVehicle().getType()).toString(),
                    Registries.ITEM.getId(held.getItem()).toString(),
                    held.isDamageable(), player.getInventory().getSelectedSlot(),
                    player.getInventory().getEmptySlot() == -1, containerOpen,
                    effects, itemCounts, lookingAt,
                    blockId(client, feet.down()), blockId(client, feet), blockId(client, feet.up(2)),
                    client.world.getBiome(feet).getIdAsString(),
                    feet.getX() >> 4, feet.getZ() >> 4,
                    timeOfDay < 12000L, client.world.isRaining(), players, feet,
                    player.getVelocity().horizontalLengthSquared() > 1.0E-4,
                    player.isClimbing(), player.isBlocking(), player.isUsingItem(),
                    player.horizontalCollision, player.hurtTime > 0, player.isFrozen(),
                    player.isOnGround(), (float) player.fallDistance, player.getVelocity().y,
                    client.isWindowFocused(),
                    client.currentScreen == null ? "" : client.currentScreen.getClass().getSimpleName(),
                    Registries.ITEM.getId(player.getOffHandStack().getItem()).toString(),
                    lookingAtEntity, hotbarEmpty, armorMissing,
                    containerSlots > 0 && containerFilled == containerSlots,
                    containerSlots > 0 && containerFilled == 0,
                    projectileIncoming, held.hasEnchantments(),
                    held.getCustomName() == null ? "" : held.getCustomName().getString());
        }

        private static String blockId(MinecraftClient client, BlockPos pos) {
            return Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).toString();
        }
    }

    /* ------------------------------------------------------------------ persistence */

    private record Saved(List<Rule> rules, List<Schedule> schedules, int nextId) {}

    private static synchronized void save() {
        try {
            Files.createDirectories(RULES_FILE.getParent());
            List<Schedule> persistent = new ArrayList<>();
            for (Schedule schedule : SCHEDULES) {
                if (schedule.intervalTicks > 0) persistent.add(schedule);
            }
            Files.writeString(RULES_FILE,
                    GSON.toJson(new Saved(new ArrayList<>(RULES), persistent, nextId)));
        } catch (IOException exception) {
            LOGGER.warn("Could not save Glade rules", exception);
        }
    }

    private static synchronized void load() {
        if (!Files.isRegularFile(RULES_FILE)) return;
        try {
            Saved saved = GSON.fromJson(Files.readString(RULES_FILE),
                    TypeToken.get(Saved.class).getType());
            if (saved == null) return;
            if (saved.rules != null) RULES.addAll(saved.rules);
            if (saved.schedules != null) {
                for (Schedule schedule : saved.schedules) {
                    schedule.remaining = schedule.intervalTicks;
                    SCHEDULES.add(schedule);
                }
            }
            nextId = Math.max(1, saved.nextId);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not load Glade rules", exception);
        }
    }

    /** Human-readable one-liner for list output. */
    public static String describe(Rule rule) {
        Trigger trigger = rule.triggerType();
        StringBuilder text = new StringBuilder("#").append(rule.id).append(" on ")
                .append(trigger.id());
        switch (trigger.kind) {
            case NUMBER -> text.append(' ').append(rule.threshold);
            case TEXT -> {
                if (rule.filter != null && !rule.filter.isEmpty()) {
                    text.append(" matching \"").append(rule.filter).append('"');
                }
                if ("count".equals(rule.mode)) {
                    text.append(" count above ").append((int) rule.amount)
                            .append(" within ").append(rule.window).append('s');
                }
            }
            case COMPARE -> {
                if ("changes".equals(rule.mode)) {
                    text.append(" changes ").append(rule.compare).append(' ').append(rule.amount)
                            .append(" within ").append(rule.window).append('s');
                } else {
                    text.append(' ').append(rule.compare).append(' ').append(rule.amount);
                    if ("sustained".equals(rule.mode)) {
                        text.append(" for ").append(rule.window).append('s');
                    }
                }
            }
            case ENTITY_COUNT -> text.append(' ').append(rule.selector)
                    .append(" radius ").append(rule.radius)
                    .append(' ').append(rule.compare).append(' ').append((int) rule.amount);
            case ENTITY_PRESENCE -> text.append(' ').append(rule.selector)
                    .append(" radius ").append(rule.radius);
            case BLOCK_COUNT -> text.append(' ').append(rule.block)
                    .append(" radius ").append((int) rule.radius)
                    .append(' ').append(rule.compare).append(' ').append((int) rule.amount);
            case BLOCK_PRESENCE -> text.append(' ').append(rule.block)
                    .append(" radius ").append((int) rule.radius);
            case ITEM_COUNT -> text.append(' ').append(rule.block)
                    .append(' ').append(rule.compare).append(' ').append((int) rule.amount);
            case REGION -> {
                if (rule.region != null && rule.region.length == 6) {
                    for (double coordinate : rule.region) {
                        text.append(' ').append((int) coordinate);
                    }
                }
            }
            case NONE -> { }
        }
        return text.append(" run ").append(rule.command).toString();
    }

    public static String describe(Schedule schedule) {
        return "#" + schedule.id + (schedule.intervalTicks > 0
                ? " every " + (schedule.intervalTicks / 20.0) + "s run "
                : " once run ") + schedule.command;
    }
}
