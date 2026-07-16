package dev.talos.client.rules;

import dev.talos.client.log.TalosLog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.talos.client.command.EntitySelector;
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
import java.util.StringJoiner;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Customizable client-side event rules: {@code /talos on <event> ... run <command>} plus the
 * {@code /talos every|after} scheduler. Rules survive restarts (~/.talos/rules.json).
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
        // Generic projectile lifecycle (covers every throwable via matching) + named sugar.
        PROJECTILE_HIT(Kind.TEXT), PROJECTILE_STOPPED(Kind.TEXT),
        SNOWBALL_THROWN(Kind.TEXT), SNOWBALL_HIT(Kind.TEXT),
        EGG_THROWN(Kind.TEXT), EGG_HIT(Kind.TEXT),
        // Block-entity content under the crosshair, and item-frame contents anywhere.
        SIGN_SEEN(Kind.TEXT), ITEM_FRAME_CHANGED(Kind.TEXT),
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
        public double[] point;     // optional x/y/z evaluation origin for spatial queries
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
    private static volatile double bossbarPercent = -1.0;
    private static String sidebarTitle;
    private static Map<String, Integer> sidebarScores = Map.of();
    /** Item-entity ids whose disappearance the pickup packet already explained. */
    private static final Set<Integer> explainedPickups = new HashSet<>();
    /** Latest occurrence of every edge/event trigger, retained for /talos get and talos.get(). */
    private static final Map<Trigger, ObservedEvent> LAST_EVENTS =
            java.util.Collections.synchronizedMap(new java.util.EnumMap<>(Trigger.class));

    public record ObservedEvent(int tick, String value, Integer entityId, String entityUuid,
            String entityType, double x, double y, double z) {
        public String describe(int now) {
            StringBuilder result = new StringBuilder(value == null || value.isEmpty()
                    ? "occurred" : value);
            if (entityId != null) {
                result.append(" [entity_id=").append(entityId)
                        .append(", uuid=").append(entityUuid)
                        .append(", type=").append(entityType)
                        .append(String.format(Locale.ROOT, ", pos=%.3f %.3f %.3f", x, y, z))
                        .append(']');
            }
            result.append(String.format(Locale.ROOT, " [%.2fs ago]", Math.max(0, now - tick) / 20.0));
            return result.toString();
        }
    }

    private EventRuleEngine() {}

    public static void register() {
        load();
        ClientTickEvents.END_CLIENT_TICK.register(EventRuleEngine::pollTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            fireText(overlay ? Trigger.ACTIONBAR : Trigger.CHAT, message.getString());
            if (!overlay) {
                fireMention(message.getString());
                dev.talos.client.script.ScriptGameEvents.onChatMessage(message.getString(), null);
            }
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> {
            fireText(Trigger.CHAT, message.getString());
            fireMention(message.getString());
            dev.talos.client.script.ScriptGameEvents.onChatMessage(
                    message.getString(), sender == null ? null : sender.name());
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide()) fireText(Trigger.ATTACK_BLOCK, pos.toShortString());
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide()) fireText(Trigger.USE_BLOCK, hit.getBlockPos().toShortString());
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide()) fireEntity(Trigger.ATTACK_ENTITY, entity, entityLabel(entity));
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide()) fireEntity(Trigger.USE_ENTITY, entity, entityLabel(entity));
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide()) fireText(Trigger.USE_ITEM,
                    BuiltInRegistries.ITEM.getKey(player.getItemInHand(hand).getItem()).toString());
            return InteractionResult.PASS;
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
    public static void onTitle(Component title) {
        if (title != null) fireText(Trigger.TITLE, title.getString());
    }

    public static void onSubtitle(Component subtitle) {
        if (subtitle != null) fireText(Trigger.SUBTITLE, subtitle.getString());
    }

    private static final java.util.ArrayDeque<Object[]> RECENT_SOUNDS = new java.util.ArrayDeque<>();

    public static void onSound(String soundId) {
        synchronized (RECENT_SOUNDS) {
            RECENT_SOUNDS.addLast(new Object[] {tick, soundId});
            while (RECENT_SOUNDS.size() > 256) RECENT_SOUNDS.removeFirst();
        }
        fireText(Trigger.SOUND, soundId);
    }

    /** Distinct sound ids the client played within the last {@code seconds}. */
    public static java.util.List<String> recentSounds(double seconds) {
        int horizon = tick - (int) (seconds * 20.0);
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        synchronized (RECENT_SOUNDS) {
            for (Object[] entry : RECENT_SOUNDS) {
                if ((Integer) entry[0] >= horizon) ids.add((String) entry[1]);
            }
        }
        return java.util.List.copyOf(ids);
    }

    /** Generic S2C packet trigger (also retained for the matching getter catalog). */
    public static void onPacket(net.minecraft.network.protocol.Packet<?> packet) {
        if (packet == null) return;
        fireText(Trigger.PACKET_RECEIVED, packet.type().id().toString());
    }

    public static void onExplosion(net.minecraft.world.phys.Vec3 center) {
        fireText(Trigger.EXPLOSION, String.format(Locale.ROOT, "%.3f %.3f %.3f",
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
        bossbarPercent = -1.0;
        fireText(Trigger.BOSSBAR_REMOVED, "removed");
    }

    /** Entity status bytes: 35 is a totem pop; everything else is exposed generically. */
    public static void onEntityStatus(Entity entity, byte status) {
        String label = entity == null ? "unknown" : entityLabel(entity);
        if (status == 35) fireEntity(Trigger.TOTEM_POPPED, entity, label);
        fireEntity(Trigger.ENTITY_STATUS, entity, label + ": " + status);
    }

    private static final java.util.ArrayDeque<Object[]> RECENT_PARTICLES = new java.util.ArrayDeque<>();

    public static void onParticle(String particleId, double x, double y, double z) {
        synchronized (RECENT_PARTICLES) {
            RECENT_PARTICLES.addLast(new Object[] {tick, particleId, x, y, z});
            while (RECENT_PARTICLES.size() > 512) RECENT_PARTICLES.removeFirst();
        }
        fireText(Trigger.PARTICLE_SEEN, particleId + " @ "
                + String.format(Locale.ROOT, "%.3f %.3f %.3f", x, y, z));
    }

    /** Distinct particle ids shown within the last {@code seconds}. */
    public static java.util.List<String> recentParticles(double seconds) {
        int horizon = tick - (int) (seconds * 20.0);
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        synchronized (RECENT_PARTICLES) {
            for (Object[] entry : RECENT_PARTICLES) {
                if ((Integer) entry[0] >= horizon) ids.add((String) entry[1]);
            }
        }
        return java.util.List.copyOf(ids);
    }

    /** Recent particles whose position lies within {@code maxMiss} meters of the look ray. */
    public static java.util.List<String> particlesOnCrosshair(LocalPlayer player,
            double seconds, double maxMiss) {
        int horizon = tick - (int) (seconds * 20.0);
        var eye = player.getEyePosition();
        var look = player.getForward();
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        synchronized (RECENT_PARTICLES) {
            for (Object[] entry : RECENT_PARTICLES) {
                if ((Integer) entry[0] < horizon) continue;
                var point = new net.minecraft.world.phys.Vec3(
                        (Double) entry[2], (Double) entry[3], (Double) entry[4]);
                var toPoint = point.subtract(eye);
                double along = toPoint.dot(look);
                if (along < 0.0 || along > 64.0) continue;
                double miss = toPoint.subtract(look.scale(along)).length();
                if (miss <= maxMiss) ids.add((String) entry[1]);
            }
        }
        return java.util.List.copyOf(ids);
    }

    /**
     * Authoritative pickup attribution: the server's ItemPickupAnimation packet names the
     * exact collector entity — no proximity guessing, no ambiguity between nearby mobs.
     * Runs on the client thread at the HEAD of the vanilla handler, before the item entity
     * is discarded, so the stack is still readable.
     */
    public static void onItemPickup(int itemEntityId, int collectorEntityId, int amount) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        Entity itemEntity = client.level.getEntity(itemEntityId);
        Entity collector = client.level.getEntity(collectorEntityId);
        String itemId = itemEntity instanceof net.minecraft.world.entity.item.ItemEntity item
                ? BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString() : "unknown";
        double pickupX = itemEntity == null ? (collector == null ? -1.0 : collector.getX())
                : itemEntity.getX();
        double pickupY = itemEntity == null ? (collector == null ? -1.0 : collector.getY())
                : itemEntity.getY();
        double pickupZ = itemEntity == null ? (collector == null ? -1.0 : collector.getZ())
                : itemEntity.getZ();
        String who = collector == null ? "entity#" + collectorEntityId : entityLabel(collector);
        explainedPickups.add(itemEntityId);
        // Additive script hook: only the LOCAL player's pickups become "item_pickup"
        // script events; ScriptGameEvents guards on a running session before posting.
        if (collector != null && collector == client.player) {
            dev.talos.client.script.ScriptGameEvents.onLocalItemPickup(itemId, amount);
        }
        // The packet amount remains exact when the item merges into an existing stack.
        fireEntity(Trigger.ITEM_PICKED_UP, collector, String.format(Locale.ROOT,
                "%s x%d @ %.3f %.3f %.3f by %s", itemId, amount,
                pickupX, pickupY, pickupZ, who));
    }

    /** MENTION fires only when a message contains the local player's own name. */
    private static void fireMention(String message) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        String name = player.getName().getString();
        if (!name.isEmpty() && message.toLowerCase(Locale.ROOT)
                .contains(name.toLowerCase(Locale.ROOT))) {
            fireText(Trigger.MENTION, message);
        }
    }

    /* ------------------------------------------------------------------ firing */

    private static void fireText(Trigger trigger, String value) {
        fireFiltered(trigger, null, value);
    }

    /** Entity-valued events also accept @e/@a/@p/@s selector rules against the subject. */
    private static void fireEntity(Trigger trigger, Entity subject, String value) {
        fireFiltered(trigger, subject, value);
    }

    private static void fireFiltered(Trigger trigger, Entity subject, String value) {
        observe(trigger, subject, value);
        for (Rule rule : RULES) {
            if (rule.triggerType() != trigger) continue;
            if (rule.selector != null && !rule.selector.isEmpty()
                    && (subject == null || !selectorMatches(rule, subject))) {
                continue;
            }
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

    private static void observe(Trigger trigger, Entity subject, String value) {
        LAST_EVENTS.put(trigger, subject == null
                ? new ObservedEvent(tick, value, null, null, null, 0, 0, 0)
                : new ObservedEvent(tick, value, subject.getId(), subject.getStringUUID(),
                        BuiltInRegistries.ENTITY_TYPE.getKey(subject.getType()).toString(),
                        subject.getX(), subject.getY(), subject.getZ()));
    }

    /** Latest payload for edge/event triggers; returns a stable sentinel before the first event. */
    public static String latestValue(Trigger trigger) {
        ObservedEvent event = LAST_EVENTS.get(trigger);
        return event == null ? "never observed" : event.describe(tick);
    }

    public static int currentTick() { return tick; }

    private static void fireState(Trigger trigger, boolean was, boolean is, String value) {
        if (was || !is) return; // fire on entering the state only
        fireText(trigger, value);
    }

    private static String playerEvent(LocalPlayer player, String detail) {
        String prefix = detail == null || detail.isEmpty() ? "occurred" : detail;
        return String.format(Locale.ROOT, "%s @ %.3f %.3f %.3f",
                prefix, player.getX(), player.getY(), player.getZ());
    }

    private static String incomingProjectile(Minecraft client, LocalPlayer player) {
        Entity best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow)
                    || entity.getDeltaMovement().lengthSqr() <= 0.01) continue;
            double distance = entity.distanceToSqr(player);
            if (distance >= 144.0 || distance >= bestDistance) continue;
            Vec3 toPlayer = player.getEyePosition().subtract(entity.position());
            if (entity.getDeltaMovement().dot(toPlayer) <= 0.0) continue;
            best = entity;
            bestDistance = distance;
        }
        if (best == null) return playerEvent(player, "projectile_incoming");
        return String.format(Locale.ROOT,
                "%s @ %.3f %.3f %.3f velocity=%.3f %.3f %.3f",
                entityLabel(best), best.getX(), best.getY(), best.getZ(),
                best.getDeltaMovement().x, best.getDeltaMovement().y,
                best.getDeltaMovement().z);
    }

    private static void fire(Rule rule, String value) {
        if (tick - rule.lastFiredTick < RULE_COOLDOWN_TICKS) return;
        rule.lastFiredTick = tick;
        String resolved = substitute(rule.command, value);
        TalosLog.trace("rules", "rule " + rule.id + " fired trigger=" + rule.triggerType().id()
                + " value=" + String.valueOf(value) + " resolved=" + resolved);
        runCommand(resolved);
    }

    private static String substitute(String command, String value) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        String result = command.replace("{value}", value == null ? "" : value);
        if (player != null) {
            result = result
                    .replace("{health}", String.format(Locale.ROOT, "%.1f", player.getHealth()))
                    .replace("{hunger}", Integer.toString(player.getFoodData().getFoodLevel()))
                    .replace("{air}", Integer.toString(player.getAirSupply()))
                    .replace("{x}", Integer.toString(player.getBlockX()))
                    .replace("{y}", Integer.toString(player.getBlockY()))
                    .replace("{z}", Integer.toString(player.getBlockZ()));
        }
        return result;
    }

    /** Client commands (e.g. talos ...) run locally; anything unhandled goes to the server. */
    public static void runCommand(String command) {
        Minecraft client = Minecraft.getInstance();
        String stripped = command.startsWith("/") ? command.substring(1) : command;
        client.execute(() -> {
            if (client.player == null) return;
            if (stripped.startsWith("chat ")) {
                client.player.connection.sendChat(stripped.substring(5));
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
            if (!handled) client.player.connection.sendCommand(stripped);
        });
    }

    /* ------------------------------------------------------------------ per-tick polling */

    private static void pollTick(Minecraft client) {
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
        if (client.player != null && client.level != null) {
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
        fireState(Trigger.WORLD_LOADED, before.worldPresent, now.worldPresent,
                now.dimension.isEmpty() ? "loaded" : now.dimension);
        fireState(Trigger.WORLD_UNLOADED, !before.worldPresent, !now.worldPresent,
                before.dimension.isEmpty() ? "unloaded" : before.dimension);
        if (!now.worldPresent || !before.worldPresent) return;

        if (!before.dimension.equals(now.dimension)) {
            fireText(Trigger.DIMENSION_CHANGED, now.dimension);
        } else {
            // A same-dimension jump no legitimate tick of movement can produce: pearls,
            // chorus fruit, /tp, respawn anchors. {value} = distance.
            double jump = Math.sqrt(before.position.distSqr(now.position));
            if (jump > 12.0) fireText(Trigger.TELEPORTED,
                    String.format(Locale.ROOT, "%.0f", jump));
        }

        if (now.health < before.health - 0.01) fireText(Trigger.DAMAGE_TAKEN,
                playerEvent(client.player, String.format(Locale.ROOT, "amount=%.3f health=%.3f",
                        before.health - now.health, now.health)));
        if (now.health > before.health + 0.01) fireText(Trigger.HEALED,
                playerEvent(client.player, String.format(Locale.ROOT, "amount=%.3f health=%.3f",
                        now.health - before.health, now.health)));
        LocalPlayer eventPlayer = client.player;
        fireState(Trigger.DEATH, before.dead, now.dead,
                playerEvent(eventPlayer, "health=" + now.health));
        fireState(Trigger.RESPAWN, !before.dead, !now.dead,
                playerEvent(eventPlayer, "health=" + now.health));
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

        fireState(Trigger.ON_FIRE, before.onFire, now.onFire,
                playerEvent(eventPlayer, "fire_ticks=" + eventPlayer.getRemainingFireTicks()));
        fireState(Trigger.FALLING, before.falling, now.falling,
                playerEvent(eventPlayer, String.format(Locale.ROOT,
                        "fall_distance=%.3f velocity_y=%.3f", now.fallDistance, now.velocityY)));
        fireState(Trigger.SNEAKING, before.sneaking, now.sneaking, playerEvent(eventPlayer, "sneaking"));
        fireState(Trigger.SPRINTING, before.sprinting, now.sprinting, playerEvent(eventPlayer, "sprinting"));
        fireState(Trigger.SWIMMING, before.swimming, now.swimming, playerEvent(eventPlayer, "swimming"));
        fireState(Trigger.GLIDING, before.gliding, now.gliding, playerEvent(eventPlayer, "gliding"));
        fireState(Trigger.UNDERWATER, before.underwater, now.underwater, playerEvent(eventPlayer, "underwater"));
        fireState(Trigger.SLEEPING, before.sleeping, now.sleeping, playerEvent(eventPlayer, "sleeping"));
        fireState(Trigger.WOKE_UP, !before.sleeping, !now.sleeping, playerEvent(eventPlayer, "woke_up"));
        fireState(Trigger.MOVING, before.moving, now.moving,
                playerEvent(eventPlayer, String.format(Locale.ROOT, "speed=%.3f",
                        eventPlayer.getDeltaMovement().horizontalDistance() * 20.0)));
        fireState(Trigger.STOPPED, !before.moving, !now.moving, playerEvent(eventPlayer, "stopped"));
        fireState(Trigger.CLIMBING, before.climbing, now.climbing, playerEvent(eventPlayer, "climbing"));
        fireState(Trigger.BLOCKING, before.blocking, now.blocking,
                playerEvent(eventPlayer, "item=" + now.heldItem));
        fireState(Trigger.USING_ITEM, before.usingItem, now.usingItem,
                playerEvent(eventPlayer, "item=" + now.heldItem));
        fireState(Trigger.COLLIDED, before.collided, now.collided, playerEvent(eventPlayer, "collided"));
        fireState(Trigger.HURT, before.hurt, now.hurt,
                playerEvent(eventPlayer, "hurt_time=" + eventPlayer.hurtTime));
        fireState(Trigger.FREEZING, before.freezing, now.freezing,
                playerEvent(eventPlayer, "frozen_ticks=" + eventPlayer.getTicksFrozen()));
        fireState(Trigger.PROJECTILE_INCOMING, before.projectileIncoming,
                now.projectileIncoming, incomingProjectile(client, eventPlayer));
        fireState(Trigger.WINDOW_FOCUSED, before.windowFocused, now.windowFocused, "focused");
        fireState(Trigger.WINDOW_UNFOCUSED, !before.windowFocused, !now.windowFocused, "unfocused");
        fireState(Trigger.HOTBAR_EMPTY, before.hotbarEmpty, now.hotbarEmpty,
                playerEvent(eventPlayer, "hotbar_empty"));
        fireState(Trigger.ARMOR_MISSING, before.armorMissing, now.armorMissing,
                playerEvent(eventPlayer, "armor_missing"));
        fireState(Trigger.CONTAINER_FULL, before.containerFull, now.containerFull,
                playerEvent(eventPlayer, "container_full"));
        fireState(Trigger.CONTAINER_EMPTY, before.containerEmpty, now.containerEmpty,
                playerEvent(eventPlayer, "container_empty"));
        if (before.onGround && !now.onGround && now.velocityY > 0.2) {
            fireText(Trigger.JUMPED, playerEvent(eventPlayer,
                    String.format(Locale.ROOT, "velocity_y=%.3f", now.velocityY)));
        }
        if (!before.onGround && now.onGround) {
            fireText(Trigger.LANDED, playerEvent(eventPlayer,
                    String.format(Locale.ROOT, "fall_distance=%.3f", before.fallDistance)));
        }
        if (!before.screenName.equals(now.screenName)) {
            if (!now.screenName.isEmpty()) fireText(Trigger.SCREEN_OPENED,
                    playerEvent(eventPlayer, "screen=" + now.screenName));
            if (!before.screenName.isEmpty()) fireText(Trigger.SCREEN_CLOSED,
                    playerEvent(eventPlayer, "screen=" + before.screenName));
        }
        if (!before.offhandItem.equals(now.offhandItem)) {
            fireText(Trigger.OFFHAND_CHANGED,
                    playerEvent(eventPlayer, "item=" + now.offhandItem));
        }
        fireState(Trigger.HELD_ENCHANTED, before.heldEnchanted, now.heldEnchanted,
                playerEvent(eventPlayer, "item=" + now.heldItem));
        fireState(Trigger.HELD_HAS_NAME, !before.heldName.isEmpty(),
                !now.heldName.isEmpty(), now.heldName);
        if (!before.heldName.equals(now.heldName) && !now.heldName.isEmpty()) {
            fireText(Trigger.HELD_NAME_CHANGED, now.heldName);
        }
        if (!before.crosshairSign.equals(now.crosshairSign) && !now.crosshairSign.isEmpty()) {
            fireText(Trigger.SIGN_SEEN, now.crosshairSign);
        }
        if (!before.lookingAtEntity.equals(now.lookingAtEntity)
                && !now.lookingAtEntity.isEmpty()) {
            fireText(Trigger.LOOKING_AT_ENTITY, now.lookingAtEntity);
        }
        // entity_spawned/removed/unloaded now fire from the per-entity tracker, which knows
        // each entity's last position and can apply the chunk-loaded disambiguation.
        if (!before.vehicle.equals(now.vehicle)) {
            if (!now.vehicle.isEmpty()) fireText(Trigger.MOUNTED,
                    playerEvent(eventPlayer, "vehicle=" + now.vehicle));
            if (!before.vehicle.isEmpty()) fireText(Trigger.DISMOUNTED,
                    playerEvent(eventPlayer, "vehicle=" + before.vehicle));
        }

        if (!before.heldItem.equals(now.heldItem)) {
            fireText(Trigger.HELD_CHANGED, playerEvent(eventPlayer, "item=" + now.heldItem));
            if (now.heldItem.equals("minecraft:air") && before.heldDamageable) {
                fireText(Trigger.TOOL_BROKEN,
                        playerEvent(eventPlayer, "item=" + before.heldItem));
            }
        }
        if (before.selectedSlot != now.selectedSlot) {
            fireText(Trigger.SLOT_CHANGED,
                    playerEvent(eventPlayer, "slot=" + (now.selectedSlot + 1)));
        }
        fireState(Trigger.INVENTORY_FULL, before.inventoryFull, now.inventoryFull,
                playerEvent(eventPlayer, "empty_slots=0"));
        fireState(Trigger.CONTAINER_OPENED, before.containerOpen, now.containerOpen,
                playerEvent(eventPlayer, "screen=" + now.screenName));
        fireState(Trigger.CONTAINER_CLOSED, !before.containerOpen, !now.containerOpen,
                playerEvent(eventPlayer, "screen=" + before.screenName));

        for (Map.Entry<String, Integer> entry : now.itemCounts.entrySet()) {
            int delta = entry.getValue() - before.itemCounts.getOrDefault(entry.getKey(), 0);
            if (delta > 0) fireText(Trigger.ITEM_GAINED,
                    playerEvent(eventPlayer, entry.getKey() + " x" + delta));
        }
        for (Map.Entry<String, Integer> entry : before.itemCounts.entrySet()) {
            int delta = entry.getValue() - now.itemCounts.getOrDefault(entry.getKey(), 0);
            if (delta > 0) fireText(Trigger.ITEM_LOST,
                    playerEvent(eventPlayer, entry.getKey() + " x" + delta));
        }

        for (String effect : now.effects) {
            if (!before.effects.contains(effect)) fireText(Trigger.EFFECT_ADDED,
                    playerEvent(eventPlayer, "effect=" + effect));
        }
        for (String effect : before.effects) {
            if (!now.effects.contains(effect)) fireText(Trigger.EFFECT_REMOVED,
                    playerEvent(eventPlayer, "effect=" + effect));
        }

        if (!before.lookingAt.equals(now.lookingAt) && !now.lookingAt.isEmpty()) {
            BlockPos hitPos = client.hitResult instanceof BlockHitResult hit
                    ? hit.getBlockPos() : eventPlayer.blockPosition();
            fireText(Trigger.LOOKING_AT_BLOCK, now.lookingAt + " @ " + hitPos.toShortString());
        }
        if (!before.standingOn.equals(now.standingOn)) {
            fireText(Trigger.STANDING_ON, now.standingOn + " @ "
                    + eventPlayer.blockPosition().below().toShortString());
        }
        if (!before.feetBlock.equals(now.feetBlock)) {
            fireText(Trigger.BLOCK_AT_FEET, now.feetBlock + " @ "
                    + eventPlayer.blockPosition().toShortString());
        }
        if (!before.headBlock.equals(now.headBlock)) {
            fireText(Trigger.BLOCK_ABOVE_HEAD, now.headBlock + " @ "
                    + eventPlayer.blockPosition().above(2).toShortString());
        }
        if (!before.biome.equals(now.biome)) fireText(Trigger.BIOME_CHANGED,
                now.biome + " @ " + eventPlayer.blockPosition().toShortString());
        if (before.chunkX != now.chunkX || before.chunkZ != now.chunkZ) {
            fireText(Trigger.CHUNK_CHANGED, now.chunkX + " " + now.chunkZ);
        }

        long timeOfDay = client.level.getOverworldClockTime() % 24000L;
        fireState(Trigger.TIME_DAY, before.day, now.day, "time_ticks=" + timeOfDay);
        fireState(Trigger.TIME_NIGHT, !before.day, !now.day, "time_ticks=" + timeOfDay);
        fireState(Trigger.WEATHER_RAIN, before.raining, now.raining, "rain");
        fireState(Trigger.WEATHER_CLEAR, !before.raining, !now.raining, "clear");

        for (String name : now.players) {
            if (!before.players.contains(name)) fireText(Trigger.PLAYER_JOINED, name);
        }
        for (String name : before.players) {
            if (!now.players.contains(name)) fireText(Trigger.PLAYER_LEFT, name);
        }
    }

    /* ------------------------------------------------------- parameterized condition rules */

    private static void evaluateConditionRules(Minecraft client, LocalPlayer player) {
        for (Rule rule : RULES) {
            Trigger trigger = rule.triggerType();
            switch (trigger.kind) {
                case COMPARE -> evaluateComparable(rule, rule.point == null
                        ? metric(trigger, client, player)
                        : metricValue(trigger, client, player,
                                new net.minecraft.world.phys.Vec3(
                                        rule.point[0], rule.point[1], rule.point[2]),
                                rule.radius));
                case ENTITY_COUNT, ENTITY_PRESENCE -> {
                    int count = entityCount(rule, client, player);
                    if (count < 0) continue; // broken selector; skip silently
                    switch (trigger) {
                        case ENTITY_NEAR -> conditionEdge(rule, count > 0,
                                entityPresenceValue(rule, client, player, count));
                        case ENTITY_GONE -> conditionEdge(rule, count == 0,
                                "count=0 selector=" + rule.selector);
                        default -> evaluateComparable(rule, count);
                    }
                }
                case BLOCK_COUNT, BLOCK_PRESENCE -> {
                    // Cube scans are heavy; stagger rules across the scan period.
                    if ((tick + rule.id) % BLOCK_SCAN_PERIOD != 0) continue;
                    int count = blockCount(rule, client, player);
                    if (count < 0) continue;
                    if (trigger == Trigger.BLOCK_NEAR) conditionEdge(rule, count > 0,
                            "count=" + count + " block=" + rule.block
                                    + pointText(rule));
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
                    conditionEdge(rule, in, playerEvent(player,
                            (trigger == Trigger.LEFT_REGION ? "left_region" : "entered_region")));
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

    /** Tests one rule's @-selector against the event's subject entity. */
    private static boolean selectorMatches(Rule rule, Entity subject) {
        if (rule.selectorBroken) return false;
        if (rule.parsedSelector == null) {
            String[] error = new String[1];
            rule.parsedSelector = EntitySelector.parse(rule.selector, error);
            if (rule.parsedSelector == null) {
                rule.selectorBroken = true;
                LOGGER.warn("Rule #{} has an invalid selector {}: {}", rule.id, rule.selector, error[0]);
                return false;
            }
        }
        EntitySelector selector = rule.parsedSelector;
        LocalPlayer self = Minecraft.getInstance().player;
        return self != null && selector.matches(subject, self);
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

    private static void conditionEdge(Rule rule, boolean in, String value) {
        if (in && rule.armed) {
            rule.armed = false;
            fire(rule, value);
        } else if (!in) {
            rule.armed = true;
        }
    }

    private static String entityPresenceValue(Rule rule, Minecraft client,
            LocalPlayer player, int count) {
        if (rule.parsedSelector == null) return "count=" + count;
        Vec3 center = rule.point == null ? player.position()
                : new Vec3(rule.point[0], rule.point[1], rule.point[2]);
        double limit = rule.radius < 0.0 ? Double.MAX_VALUE : rule.radius * rule.radius;
        StringJoiner entities = new StringJoiner(", ");
        for (Entity entity : rule.parsedSelector.select(client, false)) {
            if (entity.position().distanceToSqr(center) > limit) continue;
            entities.add(String.format(Locale.ROOT, "%s @ %.3f %.3f %.3f",
                    entityLabel(entity), entity.getX(), entity.getY(), entity.getZ()));
        }
        return "count=" + count + " entities=[" + entities + "]";
    }

    private static double metric(Trigger trigger, Minecraft client,
            LocalPlayer player) {
        return switch (trigger) {
            case HEALTH -> player.getHealth();
            case HUNGER -> player.getFoodData().getFoodLevel();
            case AIR -> player.getAirSupply();
            case XP_LEVEL -> player.experienceLevel;
            case XP_PROGRESS -> player.experienceProgress;
            case ARMOR_DURABILITY -> {
                double worst = 100.0;
                boolean found = false;
                for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[] {
                        net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                        net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
                    ItemStack stack = player.getItemBySlot(slot);
                    if (stack.isEmpty() || !stack.isDamageableItem()) continue;
                    found = true;
                    worst = Math.min(worst,
                            (stack.getMaxDamage() - stack.getDamageValue()) * 100.0 / stack.getMaxDamage());
                }
                yield found ? worst : -1.0;
            }
            case FPS -> client.getFps();
            case PING -> {
                PlayerInfo entry = player.connection == null ? null
                        : player.connection.getPlayerInfo(player.getUUID());
                yield entry == null ? -1 : entry.getLatency();
            }
            case CHUNKS_LOADED -> client.level.getChunkSource().getLoadedChunksCount();
            case LIGHT_LEVEL -> client.level.getMaxLocalRawBrightness(player.blockPosition());
            case Y_POSITION -> player.getY();
            case X_POSITION -> player.getX();
            case Z_POSITION -> player.getZ();
            case SPEED -> player.getDeltaMovement().horizontalDistance() * 20.0;
            case SATURATION -> player.getFoodData().getSaturationLevel();
            case ABSORPTION -> player.getAbsorptionAmount();
            case ARMOR_POINTS -> player.getArmorValue();
            case HELD_DURABILITY -> {
                ItemStack held = player.getMainHandItem();
                yield !held.isDamageableItem() ? -1.0
                        : (held.getMaxDamage() - held.getDamageValue()) * 100.0 / held.getMaxDamage();
            }
            case FALL_DISTANCE -> player.fallDistance;
            case TIME_TICKS -> client.level.getOverworldClockTime() % 24000L;
            case ENTITY_TOTAL -> {
                int total = 0;
                for (Entity ignored : client.level.entitiesForRendering()) total++;
                yield total;
            }
            case PLAYERS_ONLINE -> player.connection == null ? -1
                    : player.connection.getOnlinePlayers().size();
            case IDLE_SECONDS -> (tick - lastMovedTick) / 20.0;
            case VELOCITY_Y -> player.getDeltaMovement().y * 20.0;
            case MOON_PHASE -> (client.level.getOverworldClockTime() / 24000L) % 8L;
            case DAY_COUNT -> client.level.getOverworldClockTime() / 24000L;
            case EMPTY_SLOTS -> {
                int empty = 0;
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    if (player.getInventory().getItem(i).isEmpty()) empty++;
                }
                yield empty;
            }
            case OCCUPIED_SLOTS -> {
                int occupied = 0;
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    if (!player.getInventory().getItem(i).isEmpty()) occupied++;
                }
                yield occupied;
            }
            case CONTAINER_ITEMS -> player.containerMenu.slots.stream()
                    .filter(slot -> slot.container != player.getInventory())
                    .filter(slot -> !slot.getItem().isEmpty()).count();
            case MEMORY_USED_PERCENT -> {
                Runtime runtime = Runtime.getRuntime();
                yield (runtime.totalMemory() - runtime.freeMemory()) * 100.0 / runtime.maxMemory();
            }
            case NEAREST_PLAYER_DISTANCE -> nearestDistance(client, player,
                    entity -> entity instanceof Player);
            case NEAREST_HOSTILE_DISTANCE -> nearestDistance(client, player,
                    entity -> entity instanceof net.minecraft.world.entity.monster.Monster);
            case NEAREST_ANIMAL_DISTANCE -> nearestDistance(client, player,
                    entity -> entity instanceof net.minecraft.world.entity.animal.Animal);
            case NEAREST_ITEM_DISTANCE -> nearestDistance(client, player,
                    entity -> entity instanceof net.minecraft.world.entity.item.ItemEntity);
            case DROPPED_ITEMS_NEAR -> countNear(client, player, 16.0,
                    entity -> entity instanceof net.minecraft.world.entity.item.ItemEntity);
            case XP_ORBS_NEAR -> countNear(client, player, 16.0,
                    entity -> entity instanceof net.minecraft.world.entity.ExperienceOrb);
            case ARROWS_NEAR -> countNear(client, player, 16.0,
                    entity -> entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow);
            case CROSSHAIR_DISTANCE -> client.hitResult == null
                    || client.hitResult.getType() == HitResult.Type.MISS ? -1.0
                    : client.hitResult.getLocation().distanceTo(player.getEyePosition());
            case SPAWN_DISTANCE -> Math.sqrt(player.blockPosition().distSqr(
                    client.level.getRespawnData().pos()));
            case FIRE_TICKS -> player.getRemainingFireTicks();
            case FROZEN_TICKS -> player.getTicksFrozen();
            case HURT_TIME -> player.hurtTime;
            case STUCK_ARROWS -> player.getArrowCount();
            case VEHICLE_SPEED -> player.getVehicle() == null ? -1.0
                    : player.getVehicle().getDeltaMovement().horizontalDistance() * 20.0;
            case EFFECT_COUNT -> player.getActiveEffects().size();
            case WORLD_BORDER_DISTANCE -> client.level.getWorldBorder()
                    .getDistanceToBorder(player);
            case SERVER_TPS -> serverTps;
            case YAW -> net.minecraft.util.Mth.wrapDegrees(player.getYRot());
            case PITCH -> player.getXRot();
            case HELD_COUNT -> player.getMainHandItem().getCount();
            case MAX_HEALTH -> player.getMaxHealth();
            case WORLD_AGE -> client.level.getGameTime();
            case BOSSBAR_PERCENT -> bossbarPercent;
            default -> 0.0;
        };
    }

    /** Public readout used by /talos get; identical to what rules compare against. */
    public static double metricValue(Trigger trigger, Minecraft client,
            LocalPlayer player) {
        return metric(trigger, client, player);
    }

    /** Whether a metric has a meaningful alternate world-position form. */
    public static boolean acceptsPoint(Trigger trigger) {
        return switch (trigger) {
            case LIGHT_LEVEL, ENTITY_TOTAL, NEAREST_PLAYER_DISTANCE,
                    NEAREST_HOSTILE_DISTANCE, NEAREST_ANIMAL_DISTANCE,
                    NEAREST_ITEM_DISTANCE, DROPPED_ITEMS_NEAR, XP_ORBS_NEAR,
                    ARROWS_NEAR, SPAWN_DISTANCE, WORLD_BORDER_DISTANCE,
                    ENTITY_COUNT, ENTITY_NEAR, ENTITY_GONE, BLOCK_COUNT, BLOCK_NEAR -> true;
            default -> false;
        };
    }

    public static boolean acceptsSpatialRadius(Trigger trigger) {
        return switch (trigger) {
            case ENTITY_TOTAL, NEAREST_PLAYER_DISTANCE, NEAREST_HOSTILE_DISTANCE,
                    NEAREST_ANIMAL_DISTANCE, NEAREST_ITEM_DISTANCE,
                    DROPPED_ITEMS_NEAR, XP_ORBS_NEAR, ARROWS_NEAR -> true;
            default -> false;
        };
    }

    /** Spatial metric evaluated at an explicit point; radius defaults to 16 where applicable. */
    public static double metricValue(Trigger trigger, Minecraft client, LocalPlayer player,
            net.minecraft.world.phys.Vec3 point, double radius) {
        double effectiveRadius = radius < 0.0 ? 16.0 : radius;
        return switch (trigger) {
            case LIGHT_LEVEL -> client.level.getMaxLocalRawBrightness(
                    net.minecraft.core.BlockPos.containing(point));
            case ENTITY_TOTAL -> countNear(client, player, point, effectiveRadius, entity -> true);
            case NEAREST_PLAYER_DISTANCE -> nearestDistance(
                    client, player, point, radius, entity -> entity instanceof Player);
            case NEAREST_HOSTILE_DISTANCE -> nearestDistance(client, player, point,
                    radius, entity -> entity instanceof net.minecraft.world.entity.monster.Monster);
            case NEAREST_ANIMAL_DISTANCE -> nearestDistance(client, player, point,
                    radius, entity -> entity instanceof net.minecraft.world.entity.animal.Animal);
            case NEAREST_ITEM_DISTANCE -> nearestDistance(client, player, point,
                    radius, entity -> entity instanceof net.minecraft.world.entity.item.ItemEntity);
            case DROPPED_ITEMS_NEAR -> countNear(client, player, point, effectiveRadius,
                    entity -> entity instanceof net.minecraft.world.entity.item.ItemEntity);
            case XP_ORBS_NEAR -> countNear(client, player, point, effectiveRadius,
                    entity -> entity instanceof net.minecraft.world.entity.ExperienceOrb);
            case ARROWS_NEAR -> countNear(client, player, point, effectiveRadius,
                    entity -> entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow);
            case SPAWN_DISTANCE -> point.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(
                    client.level.getRespawnData().pos()));
            case WORLD_BORDER_DISTANCE -> {
                var border = client.level.getWorldBorder();
                yield Math.max(0.0, Math.min(Math.min(point.x - border.getMinX(),
                        border.getMaxX() - point.x), Math.min(point.z - border.getMinZ(),
                        border.getMaxZ() - point.z)));
            }
            default -> metric(trigger, client, player);
        };
    }

    /**
     * Live value for a parameterized trigger family. This deliberately calls the same private
     * calculators used by rule evaluation, keeping /talos get and talos.get numerically exact.
     */
    public static double parameterValue(Trigger trigger, Minecraft client, LocalPlayer player,
            String subject, double radius, double[] region) {
        return parameterValue(trigger, client, player, subject, radius, region, null);
    }

    public static double parameterValue(Trigger trigger, Minecraft client, LocalPlayer player,
            String subject, double radius, double[] region, net.minecraft.world.phys.Vec3 point) {
        Rule rule = new Rule();
        rule.trigger = trigger.id();
        rule.selector = subject;
        rule.block = subject;
        rule.radius = radius;
        rule.region = region;
        if (point != null) rule.point = new double[] {point.x, point.y, point.z};
        return switch (trigger.kind) {
            case ENTITY_COUNT, ENTITY_PRESENCE -> entityCount(rule, client, player);
            case BLOCK_COUNT, BLOCK_PRESENCE -> blockCount(rule, client, player);
            case ITEM_COUNT -> trigger == Trigger.HELD_ENCHANT
                    ? heldEnchantLevel(rule, player)
                    : itemCount(rule, player, trigger == Trigger.HOTBAR_ITEM_COUNT);
            case REGION -> {
                if (region == null || region.length != 6) yield -1;
                yield player.getX() >= Math.min(region[0], region[3])
                        && player.getX() <= Math.max(region[0], region[3]) + 1
                        && player.getY() >= Math.min(region[1], region[4])
                        && player.getY() <= Math.max(region[1], region[4]) + 1
                        && player.getZ() >= Math.min(region[2], region[5])
                        && player.getZ() <= Math.max(region[2], region[5]) + 1 ? 1 : 0;
            }
            default -> throw new IllegalArgumentException(trigger.id() + " is not parameterized");
        };
    }

    private static double nearestDistance(Minecraft client, LocalPlayer player,
            java.util.function.Predicate<Entity> filter) {
        return nearestDistance(client, player, player.position(), filter);
    }

    private static double nearestDistance(Minecraft client, LocalPlayer player,
            net.minecraft.world.phys.Vec3 point, java.util.function.Predicate<Entity> filter) {
        return nearestDistance(client, player, point, -1.0, filter);
    }

    private static double nearestDistance(Minecraft client, LocalPlayer player,
            net.minecraft.world.phys.Vec3 point, double radius,
            java.util.function.Predicate<Entity> filter) {
        double best = Double.POSITIVE_INFINITY;
        double limit = radius < 0.0 ? Double.MAX_VALUE : radius * radius;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == player || !filter.test(entity)) continue;
            double distanceSquared = entity.position().distanceToSqr(point);
            if (distanceSquared > limit) continue;
            best = Math.min(best, Math.sqrt(distanceSquared));
        }
        return Double.isFinite(best) ? best : -1.0;
    }

    private static int countNear(Minecraft client, LocalPlayer player,
            double radius, java.util.function.Predicate<Entity> filter) {
        return countNear(client, player, player.position(), radius, filter);
    }

    private static int countNear(Minecraft client, LocalPlayer player,
            net.minecraft.world.phys.Vec3 point, double radius,
            java.util.function.Predicate<Entity> filter) {
        int count = 0;
        double radiusSquared = radius * radius;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == player || !filter.test(entity)) continue;
            if (entity.position().distanceToSqr(point) <= radiusSquared) count++;
        }
        return count;
    }

    private static int entityCount(Rule rule, Minecraft client, LocalPlayer player) {
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
        net.minecraft.world.phys.Vec3 center = rule.point == null ? player.position()
                : new net.minecraft.world.phys.Vec3(rule.point[0], rule.point[1], rule.point[2]);
        int count = 0;
        for (Entity entity : selector.select(client, false)) {
            if (entity.position().distanceToSqr(center) > radiusSquared) continue;
            count++;
        }
        return count;
    }

    private static int blockCount(Rule rule, Minecraft client, LocalPlayer player) {
        if (rule.cachedBlock == null) {
            Identifier id = Identifier.tryParse(rule.block.contains(":")
                    ? rule.block : "minecraft:" + rule.block);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return -1;
            rule.cachedBlock = BuiltInRegistries.BLOCK.getValue(id);
        }
        int radius = (int) Math.max(1, Math.min(MAX_BLOCK_RADIUS, rule.radius));
        BlockPos center = rule.point == null ? player.blockPosition()
                : BlockPos.containing(rule.point[0], rule.point[1], rule.point[2]);
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            if (client.level.getBlockState(pos).getBlock() == rule.cachedBlock) count++;
        }
        return count;
    }

    private static int itemCount(Rule rule, LocalPlayer player, boolean hotbarOnly) {
        if (rule.cachedItem == null) {
            Identifier id = Identifier.tryParse(rule.block.contains(":")
                    ? rule.block : "minecraft:" + rule.block);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return -1;
            rule.cachedItem = BuiltInRegistries.ITEM.getValue(id);
        }
        int total = 0;
        int size = hotbarOnly ? 9 : player.getInventory().getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() == rule.cachedItem) total += stack.getCount();
        }
        return total;
    }

    /* ------------------------------------------------------- per-entity deep tracking */

    /** Everything the client actually syncs about a foreign entity, one record per tick. */
    private record TrackedEntity(Entity handle, Entity ownerHandle, String type, String label,
            boolean living, boolean isItem,
            boolean isProjectile, boolean isPlayer, boolean isPearl, boolean isPotion,
            boolean isSnowball, boolean isEgg, double speed, String frameItem,
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

    private static void evaluateEntityEvents(Minecraft client, LocalPlayer player) {
        Map<Integer, TrackedEntity> now = new HashMap<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            now.put(entity.getId(), track(entity));
        }
        Map<Integer, TrackedEntity> before = trackedEntities;
        trackedEntities = now;
        if (!before.isEmpty()) {
            for (Map.Entry<Integer, TrackedEntity> entry : now.entrySet()) {
                TrackedEntity current = entry.getValue();
                TrackedEntity past = before.get(entry.getKey());
                if (past == null) {
                    fireEntity(Trigger.ENTITY_SPAWNED, current.handle, current.label);
                    if (current.isItem) fireEntity(Trigger.ITEM_SPAWNED,
                            current.handle, current.itemStack);
                    if (current.isProjectile) {
                        String by = current.ownerLabel.isEmpty() ? ""
                                : " by " + current.ownerLabel;
                        // Selector rules test the THROWER, so '@s'/'@a' means whose pearl.
                        Entity attribution = current.ownerHandle != null
                                ? current.ownerHandle : current.handle;
                        fireEntity(Trigger.PROJECTILE_LAUNCHED, attribution, current.label + by);
                        if (current.isPearl) fireEntity(Trigger.PEARL_THROWN, attribution,
                                current.label + by);
                        if (current.isSnowball) fireEntity(Trigger.SNOWBALL_THROWN, attribution,
                                current.label + by);
                        if (current.isEgg) fireEntity(Trigger.EGG_THROWN, attribution,
                                current.label + by);
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
                boolean chunkStillLoaded = client.level.hasChunk(
                        ((int) Math.floor(gone.x)) >> 4, ((int) Math.floor(gone.z)) >> 4);
                if (gone.isItem) {
                    fireEntity(chunkStillLoaded ? Trigger.ITEM_DESPAWNED : Trigger.ITEM_UNLOADED,
                            gone.handle, String.format(Locale.ROOT, "%s @ %.3f %.3f %.3f",
                                    gone.itemStack, gone.x, gone.y, gone.z));
                    continue;
                }
                if (chunkStillLoaded && gone.isProjectile) {
                    String by = gone.ownerLabel.isEmpty() ? "" : " by " + gone.ownerLabel;
                    String where = String.format(Locale.ROOT, "%.3f %.3f %.3f",
                            gone.x, gone.y, gone.z);
                    Entity attribution = gone.ownerHandle != null ? gone.ownerHandle : gone.handle;
                    // A throwable vanishing in a loaded chunk IS its impact.
                    fireEntity(Trigger.PROJECTILE_HIT, attribution,
                            gone.label + " @ " + where + by);
                    if (gone.isPearl) fireEntity(Trigger.PEARL_LANDED, attribution, where + by);
                    if (gone.isPotion) fireEntity(Trigger.POTION_SPLASHED, attribution, where + by);
                    if (gone.isSnowball) fireEntity(Trigger.SNOWBALL_HIT, attribution, where + by);
                    if (gone.isEgg) fireEntity(Trigger.EGG_HIT, attribution, where + by);
                }
                if (!chunkStillLoaded) {
                    fireEntity(Trigger.ENTITY_UNLOADED, gone.handle, gone.label);
                } else if (gone.living && gone.health <= 0.0F) {
                    fireEntity(Trigger.ENTITY_DIED, gone.handle, gone.label);
                } else {
                    fireEntity(Trigger.ENTITY_REMOVED, gone.handle, gone.label);
                }
            }
        }
        explainedPickups.clear();

        // Gamemodes come from the tab list, not the entity, and cover the local player too.
        Map<String, String> modes = new HashMap<>();
        if (player.connection != null) {
            for (PlayerInfo entry : player.connection.getOnlinePlayers()) {
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
        Entity subject = now.handle;
        if (!past.hand.equals(now.hand)) {
            fireEntity(Trigger.ENTITY_HELD_CHANGED, subject, label + ": " + now.hand);
            if (now.isPlayer) fireEntity(Trigger.PLAYER_HELD_CHANGED, subject, label + ": " + now.hand);
        }
        if (!past.offhand.equals(now.offhand)) {
            fireEntity(Trigger.ENTITY_OFFHAND_CHANGED, subject, label + ": " + now.offhand);
            if (now.isPlayer) fireEntity(Trigger.PLAYER_OFFHAND_CHANGED, subject, label + ": " + now.offhand);
        }
        if (!past.armor.equals(now.armor)) {
            fireEntity(Trigger.ENTITY_ARMOR_CHANGED, subject, label + ": " + now.armor);
            if (now.isPlayer) fireEntity(Trigger.PLAYER_ARMOR_CHANGED, subject, label + ": " + now.armor);
        }
        if (!past.hurt && now.hurt) fireEntity(Trigger.ENTITY_HURT, subject, label);
        if (now.living && past.health > 0.0F && now.health <= 0.0F) {
            fireEntity(Trigger.ENTITY_DIED, subject, label);
        }
        if (now.living && now.health < past.health - 0.01F) {
            fireEntity(Trigger.ENTITY_DAMAGED, subject, label + ": -" + String.format(Locale.ROOT,
                    "%.1f", past.health - now.health));
        }
        if (now.living && now.health > past.health + 0.01F && past.health > 0.0F) {
            fireEntity(Trigger.ENTITY_HEALED, subject, label + ": +" + String.format(Locale.ROOT,
                    "%.1f", now.health - past.health));
        }
        if (!past.burning && now.burning) fireEntity(Trigger.ENTITY_STARTED_BURNING, subject, label);
        if (past.vehicleId != now.vehicleId) {
            if (now.vehicleId != -1) fireEntity(Trigger.ENTITY_MOUNTED, subject,
                    label + " -> " + now.vehicleType);
            if (past.vehicleId != -1) fireEntity(Trigger.ENTITY_DISMOUNTED, subject,
                    label + " <- " + past.vehicleType);
        }
        if (!past.sneaking && now.sneaking) fireEntity(Trigger.ENTITY_SNEAKING, subject, label);
        if (!past.sprinting && now.sprinting) fireEntity(Trigger.ENTITY_SPRINTING, subject, label);
        if (!past.usingItem && now.usingItem) fireEntity(Trigger.ENTITY_USING_ITEM, subject,
                label + ": " + now.hand);
        if (!past.blocking && now.blocking) fireEntity(Trigger.ENTITY_BLOCKING, subject, label);
        if (!past.gliding && now.gliding) fireEntity(Trigger.ENTITY_GLIDING, subject, label);
        if (!past.swimming && now.swimming) fireEntity(Trigger.ENTITY_SWIMMING, subject, label);
        if (!past.sleeping && now.sleeping) fireEntity(Trigger.ENTITY_SLEEPING, subject, label);
        if (past.baby && !now.baby) fireEntity(Trigger.ENTITY_BABY_GROWN, subject, label);
        if (!past.frameItem.equals(now.frameItem) && !now.frameItem.isEmpty()) {
            fireEntity(Trigger.ITEM_FRAME_CHANGED, subject, label + ": " + now.frameItem);
        }
        if (past.isProjectile && past.speed > 0.2 && now.speed < 0.05) {
            fireEntity(Trigger.PROJECTILE_STOPPED, subject, label + String.format(Locale.ROOT,
                    " @ %.3f %.3f %.3f", now.x, now.y, now.z));
        }
        // Finishing a use-cycle while holding a potion is a completed drink (self included).
        if (past.usingItem && !now.usingItem && past.hand.contains("potion")) {
            fireEntity(Trigger.POTION_DRANK, subject, label + ": " + past.hand);
        }
        if (!past.profession.equals(now.profession) && !now.profession.isEmpty()) {
            fireEntity(Trigger.VILLAGER_PROFESSION_CHANGED, subject,
                    label + ": " + (past.profession.isEmpty() ? "none" : past.profession)
                            + " -> " + now.profession);
        }
        if (past.level != now.level && now.level > 0) {
            fireEntity(Trigger.VILLAGER_LEVEL_CHANGED, subject,
                    label + ": " + past.level + " -> " + now.level);
        }
    }

    /**
     * Stable identity string: {@code type#networkId[/name]}. The network id is unique for
     * the session and is how follow-up commands can target the exact same entity; players
     * and named mobs also carry their name.
     */
    public static String entityLabel(Entity entity) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        String label = type + "#" + entity.getId();
        return entity instanceof Player || entity.hasCustomName()
                ? label + "/" + entity.getName().getString() : label;
    }

    private static TrackedEntity track(Entity entity) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        String label = entityLabel(entity);
        boolean living = entity instanceof net.minecraft.world.entity.LivingEntity;
        String hand = "", offhand = "", armor = "";
        boolean usingItem = false, blocking = false, sleeping = false, baby = false;
        float health = 0.0F;
        String profession = "";
        int level = 0;
        if (living) {
            net.minecraft.world.entity.LivingEntity livingEntity =
                    (net.minecraft.world.entity.LivingEntity) entity;
            hand = BuiltInRegistries.ITEM.getKey(livingEntity.getMainHandItem().getItem()).toString();
            offhand = BuiltInRegistries.ITEM.getKey(livingEntity.getOffhandItem().getItem()).toString();
            StringBuilder armorBuilder = new StringBuilder();
            for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[] {
                    net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                    net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
                if (!armorBuilder.isEmpty()) armorBuilder.append(',');
                armorBuilder.append(BuiltInRegistries.ITEM.getKey(
                        livingEntity.getItemBySlot(slot).getItem()).getPath());
            }
            armor = armorBuilder.toString();
            usingItem = livingEntity.isUsingItem();
            blocking = livingEntity.isBlocking();
            sleeping = livingEntity.isSleeping();
            baby = livingEntity.isBaby();
            health = livingEntity.getHealth();
            if (entity instanceof net.minecraft.world.entity.npc.villager.Villager villager) {
                profession = villager.getVillagerData().profession().getRegisteredName();
                level = villager.getVillagerData().level();
            }
        }
        String itemStack = "";
        boolean isItem = entity instanceof net.minecraft.world.entity.item.ItemEntity;
        if (isItem) {
            ItemStack stack = ((net.minecraft.world.entity.item.ItemEntity) entity).getItem();
            itemStack = BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
        }
        boolean isProjectile = entity instanceof net.minecraft.world.entity.projectile.Projectile;
        String ownerLabel = "";
        Entity ownerHandle = null;
        if (isProjectile) {
            ownerHandle = ((net.minecraft.world.entity.projectile.Projectile) entity).getOwner();
            if (ownerHandle != null) ownerLabel = entityLabel(ownerHandle);
        }
        return new TrackedEntity(entity, ownerHandle, type, label, living, isItem, isProjectile,
                entity instanceof Player,
                entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl,
                entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion,
                entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball,
                entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg,
                isProjectile ? entity.getDeltaMovement().length() : 0.0,
                entity instanceof net.minecraft.world.entity.decoration.ItemFrame frame
                        ? BuiltInRegistries.ITEM.getKey(frame.getItem().getItem()).toString() : "",
                ownerLabel, hand, offhand, armor,
                living && ((net.minecraft.world.entity.LivingEntity) entity).hurtTime > 0,
                entity.getRemainingFireTicks() > 0,
                entity.getVehicle() == null ? -1 : entity.getVehicle().getId(),
                entity.getVehicle() == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(
                        entity.getVehicle().getType()).toString(),
                entity.isShiftKeyDown(), entity.isSprinting(), usingItem, blocking,
                living && ((net.minecraft.world.entity.LivingEntity) entity).isFallFlying(),
                entity.isSwimming(), sleeping, baby, health, profession, level,
                entity.getX(), entity.getY(), entity.getZ(), itemStack);
    }

    /** Open-container content diffs plus the container's title on open. */
    private static void evaluateContainerEvents(Minecraft client,
            LocalPlayer player, boolean wasOpen, boolean isOpen) {
        if (!isOpen) { containerCounts = Map.of(); return; }
        if (!wasOpen && client.screen != null) {
            fireText(Trigger.CONTAINER_TITLE, client.screen.getTitle().getString());
        }
        Map<String, Integer> now = new HashMap<>();
        for (net.minecraft.world.inventory.Slot slot : player.containerMenu.slots) {
            if (slot.container == player.getInventory() || slot.getItem().isEmpty()) continue;
            now.merge(BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString(),
                    slot.getItem().getCount(), Integer::sum);
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
    private static void evaluateSidebar(Minecraft client) {
        net.minecraft.world.scores.Objective objective = client.level.getScoreboard()
                .getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
        if (objective == null) {
            if (sidebarTitle != null) fireText(Trigger.SIDEBAR_REMOVED,
                    "removed: " + sidebarTitle);
            sidebarTitle = null;
            sidebarScores = Map.of();
            return;
        }
        String title = objective.getDisplayName().getString();
        Map<String, Integer> scores = new HashMap<>();
        for (net.minecraft.world.scores.PlayerScoreEntry entry
                : client.level.getScoreboard().listPlayerScores(objective)) {
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
    private static int heldEnchantLevel(Rule rule, LocalPlayer player) {
        var enchantments = player.getMainHandItem().getEnchantments();
        if (enchantments.isEmpty()) return 0;
        String wanted = rule.block.contains(":") ? rule.block : "minecraft:" + rule.block;
        for (var entry : enchantments.keySet()) {
            if (entry.getRegisteredName().equals(wanted)) return enchantments.getLevel(entry);
        }
        return 0;
    }

    /** Server TPS estimated from world-time advancement over a rolling five-second window. */
    private static void updateServerTps(Minecraft client) {
        long worldTime = client.level.getGameTime();
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
            boolean projectileIncoming, boolean heldEnchanted, String heldName,
            String crosshairSign) {

        static Snapshot capture(Minecraft client) {
            LocalPlayer player = client.player;
            if (player == null || client.level == null) {
                return new Snapshot(false, "", 0, false, 0, 0, 0, false, false, false, false,
                        false, false, false, false, "", "", false, 0, false, false, Set.of(),
                        Map.of(), "", "", "", "", "", 0, 0, false, false, Set.of(), BlockPos.ZERO,
                        false, false, false, false, false, false, false, false, 0.0F, 0.0,
                        false, "", "", "", false, false, false, false, false, false, "", "");
            }
            ItemStack held = player.getMainHandItem();
            Set<String> effects = new HashSet<>();
            for (MobEffectInstance instance : player.getActiveEffects()) {
                effects.add(instance.getEffect().getRegisteredName());
            }
            Set<String> players = new HashSet<>();
            if (player.connection != null) {
                for (PlayerInfo entry : player.connection.getOnlinePlayers()) {
                    players.add(entry.getProfile().name());
                }
            }
            Map<String, Integer> itemCounts = new HashMap<>();
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.isEmpty()) continue;
                itemCounts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        stack.getCount(), Integer::sum);
            }
            boolean containerOpen = player.containerMenu.slots.stream()
                    .anyMatch(slot -> slot.container != player.getInventory());
            String lookingAt = client.hitResult instanceof BlockHitResult hit
                    && client.hitResult.getType() == HitResult.Type.BLOCK
                    ? blockId(client, hit.getBlockPos()) : "";
            BlockPos feet = player.blockPosition();
            long timeOfDay = client.level.getOverworldClockTime() % 24000L;

            boolean hotbarEmpty = true;
            for (int i = 0; i < 9 && hotbarEmpty; i++) {
                hotbarEmpty = player.getInventory().getItem(i).isEmpty();
            }
            boolean armorMissing = false;
            for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[] {
                    net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                    net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
                if (player.getItemBySlot(slot).isEmpty()) armorMissing = true;
            }
            int containerSlots = 0;
            int containerFilled = 0;
            for (net.minecraft.world.inventory.Slot slot : player.containerMenu.slots) {
                if (slot.container == player.getInventory()) continue;
                containerSlots++;
                if (!slot.getItem().isEmpty()) containerFilled++;
            }
            String crosshairSign = "";
            if (client.hitResult instanceof BlockHitResult signHit
                    && client.hitResult.getType() == HitResult.Type.BLOCK
                    && client.level.getBlockEntity(signHit.getBlockPos())
                            instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                StringBuilder text = new StringBuilder();
                for (Component line : sign.getFrontText().getMessages(false)) {
                    String raw = line.getString();
                    if (!raw.isEmpty()) text.append(text.isEmpty() ? "" : " / ").append(raw);
                }
                crosshairSign = text.toString();
            }
            String lookingAtEntity = client.hitResult
                    instanceof net.minecraft.world.phys.EntityHitResult entityHit
                    ? BuiltInRegistries.ENTITY_TYPE.getKey(entityHit.getEntity().getType()).toString() : "";
            boolean projectileIncoming = false;
            for (Entity entity : client.level.entitiesForRendering()) {
                if (!projectileIncoming
                        && entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow
                        && entity.distanceToSqr(player) < 144.0
                        && entity.getDeltaMovement().lengthSqr() > 0.01) {
                    var toPlayer = player.getEyePosition().subtract(
                            entity.getX(), entity.getY(), entity.getZ());
                    projectileIncoming = entity.getDeltaMovement().dot(toPlayer) > 0.0;
                }
            }
            return new Snapshot(true,
                    client.level.dimension().identifier().toString(),
                    player.getHealth(), player.getHealth() <= 0.0F,
                    player.getFoodData().getFoodLevel(), player.getAirSupply(),
                    player.experienceLevel, player.isOnFire(), player.fallDistance > 3.0,
                    player.isShiftKeyDown(), player.isSprinting(), player.isSwimming(),
                    player.isFallFlying(), player.isUnderWater(), player.isSleeping(),
                    player.getVehicle() == null ? ""
                            : BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()).toString(),
                    BuiltInRegistries.ITEM.getKey(held.getItem()).toString(),
                    held.isDamageableItem(), player.getInventory().getSelectedSlot(),
                    player.getInventory().getFreeSlot() == -1, containerOpen,
                    effects, itemCounts, lookingAt,
                    blockId(client, feet.below()), blockId(client, feet), blockId(client, feet.above(2)),
                    client.level.getBiome(feet).getRegisteredName(),
                    feet.getX() >> 4, feet.getZ() >> 4,
                    timeOfDay < 12000L, client.level.isRaining(), players, feet,
                    player.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4,
                    player.onClimbable(), player.isBlocking(), player.isUsingItem(),
                    player.horizontalCollision, player.hurtTime > 0, player.isFullyFrozen(),
                    player.onGround(), (float) player.fallDistance, player.getDeltaMovement().y,
                    client.isWindowActive(),
                    client.screen == null ? "" : client.screen.getClass().getSimpleName(),
                    BuiltInRegistries.ITEM.getKey(player.getOffhandItem().getItem()).toString(),
                    lookingAtEntity, hotbarEmpty, armorMissing,
                    containerSlots > 0 && containerFilled == containerSlots,
                    containerSlots > 0 && containerFilled == 0,
                    projectileIncoming, held.isEnchanted(),
                    held.getCustomName() == null ? "" : held.getCustomName().getString(),
                    crosshairSign);
        }

        private static String blockId(Minecraft client, BlockPos pos) {
            return BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).toString();
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
            LOGGER.warn("Could not save Talos rules", exception);
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
            LOGGER.warn("Could not load Talos rules", exception);
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
                appendPoint(text, rule);
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
                    .append(pointText(rule))
                    .append(' ').append(rule.compare).append(' ').append((int) rule.amount);
            case ENTITY_PRESENCE -> text.append(' ').append(rule.selector)
                    .append(" radius ").append(rule.radius).append(pointText(rule));
            case BLOCK_COUNT -> text.append(' ').append(rule.block)
                    .append(" radius ").append((int) rule.radius)
                    .append(pointText(rule))
                    .append(' ').append(rule.compare).append(' ').append((int) rule.amount);
            case BLOCK_PRESENCE -> text.append(' ').append(rule.block)
                    .append(" radius ").append((int) rule.radius).append(pointText(rule));
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

    private static void appendPoint(StringBuilder text, Rule rule) {
        text.append(pointText(rule));
    }

    private static String pointText(Rule rule) {
        if (rule.point == null || rule.point.length != 3) return "";
        return String.format(Locale.ROOT, " at %.3f %.3f %.3f",
                rule.point[0], rule.point[1], rule.point[2]);
    }

    public static String describe(Schedule schedule) {
        return "#" + schedule.id + (schedule.intervalTicks > 0
                ? " every " + (schedule.intervalTicks / 20.0) + "s run "
                : " once run ") + schedule.command;
    }
}
