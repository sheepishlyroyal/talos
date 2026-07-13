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
            Path.of(System.getProperty("user.home"), ".glade", "rules.json");
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

    private EventRuleEngine() {}

    public static void register() {
        load();
        ClientTickEvents.END_CLIENT_TICK.register(EventRuleEngine::pollTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                fireText(overlay ? Trigger.ACTIONBAR : Trigger.CHAT, message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) ->
                fireText(Trigger.CHAT, message.getString()));
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
            evaluateConditionRules(client, client.player);
        }

        if (before == null) return;
        fireState(Trigger.WORLD_LOADED, before.worldPresent, now.worldPresent, "");
        fireState(Trigger.WORLD_UNLOADED, !before.worldPresent, !now.worldPresent, "");
        if (!now.worldPresent || !before.worldPresent) return;

        if (!before.dimension.equals(now.dimension)) {
            fireText(Trigger.DIMENSION_CHANGED, now.dimension);
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
            default -> 0.0;
        };
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
            boolean day, boolean raining, Set<String> players, BlockPos position) {

        static Snapshot capture(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            if (player == null || client.world == null) {
                return new Snapshot(false, "", 0, false, 0, 0, 0, false, false, false, false,
                        false, false, false, false, "", "", false, 0, false, false, Set.of(),
                        Map.of(), "", "", "", "", "", 0, 0, false, false, Set.of(), BlockPos.ORIGIN);
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
                    timeOfDay < 12000L, client.world.isRaining(), players, feet);
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
