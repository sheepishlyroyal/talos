package dev.glade.client.rules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Customizable client-side event rules: {@code /glade on <event> ... run <command>} plus the
 * {@code /glade every|after} scheduler. Rules survive restarts (~/.glade/rules.json). Fired
 * commands run through the client command dispatcher first, then fall back to the server.
 */
public final class EventRuleEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventRuleEngine.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path RULES_FILE =
            Path.of(System.getProperty("user.home"), ".glade", "rules.json");
    private static final int RULE_COOLDOWN_TICKS = 10; // re-entrancy / spam guard

    /** How a trigger interprets its argument. */
    public enum Kind { NONE, NUMBER, TEXT }

    /** Every observable the engine knows how to watch. */
    public enum Trigger {
        // Vitals — NUMBER triggers fire once when crossing the threshold and re-arm after.
        HEALTH_BELOW(Kind.NUMBER), HEALTH_ABOVE(Kind.NUMBER), DAMAGE_TAKEN(Kind.NONE),
        HEALED(Kind.NONE), DEATH(Kind.NONE), RESPAWN(Kind.NONE),
        HUNGER_BELOW(Kind.NUMBER), AIR_BELOW(Kind.NUMBER), XP_LEVEL_ABOVE(Kind.NUMBER),
        // Player state — fires when the state is entered.
        ON_FIRE(Kind.NONE), FALLING(Kind.NONE), SNEAKING(Kind.NONE), SPRINTING(Kind.NONE),
        SWIMMING(Kind.NONE), GLIDING(Kind.NONE), UNDERWATER(Kind.NONE),
        // Items and screens.
        HELD_CHANGED(Kind.NONE), TOOL_BROKEN(Kind.NONE), INVENTORY_FULL(Kind.NONE),
        SLOT_CHANGED(Kind.NONE), CONTAINER_OPENED(Kind.NONE), CONTAINER_CLOSED(Kind.NONE),
        EFFECT_ADDED(Kind.TEXT), EFFECT_REMOVED(Kind.TEXT),
        // World.
        DIMENSION_CHANGED(Kind.NONE), WORLD_LOADED(Kind.NONE), WORLD_UNLOADED(Kind.NONE),
        TIME_DAY(Kind.NONE), TIME_NIGHT(Kind.NONE), WEATHER_RAIN(Kind.NONE),
        WEATHER_CLEAR(Kind.NONE), PLAYER_JOINED(Kind.TEXT), PLAYER_LEFT(Kind.TEXT),
        // Text the server shows us. TEXT filter = case-insensitive contains.
        CHAT(Kind.TEXT), TITLE(Kind.TEXT), SUBTITLE(Kind.TEXT), ACTIONBAR(Kind.TEXT),
        // Interactions.
        ATTACK_BLOCK(Kind.NONE), USE_BLOCK(Kind.NONE), ATTACK_ENTITY(Kind.NONE),
        USE_ENTITY(Kind.NONE), USE_ITEM(Kind.NONE),
        // Clock.
        TICK_EVERY(Kind.NUMBER);

        public final Kind kind;
        Trigger(Kind kind) { this.kind = kind; }
        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    public static final class Rule {
        public int id;
        public String trigger;
        public String filter;      // TEXT triggers; null = match everything
        public double threshold;   // NUMBER triggers
        public String command;
        transient int lastFiredTick = Integer.MIN_VALUE;
        transient boolean armed = true;

        Trigger triggerType() { return Trigger.valueOf(trigger.toUpperCase(Locale.ROOT)); }
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
                    player.getStackInHand(hand).getItem().toString());
            return ActionResult.PASS;
        });
    }

    /* ------------------------------------------------------------------ rule management */

    public static synchronized int addRule(Trigger trigger, String filter, double threshold,
            String command) {
        Rule rule = new Rule();
        rule.id = nextId++;
        rule.trigger = trigger.id();
        rule.filter = filter;
        rule.threshold = threshold;
        rule.command = command;
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

    /* ------------------------------------------------------------------ firing */

    /** Mixin entry points (InGameHudTitleMixin). */
    public static void onTitle(Text title) {
        if (title != null) fireText(Trigger.TITLE, title.getString());
    }

    public static void onSubtitle(Text subtitle) {
        if (subtitle != null) fireText(Trigger.SUBTITLE, subtitle.getString());
    }

    private static void fireText(Trigger trigger, String value) {
        for (Rule rule : RULES) {
            if (rule.triggerType() != trigger) continue;
            if (rule.filter != null && !rule.filter.isEmpty()
                    && !value.toLowerCase(Locale.ROOT).contains(rule.filter.toLowerCase(Locale.ROOT))) {
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

        for (Rule rule : RULES) {
            if (rule.triggerType() == Trigger.TICK_EVERY && rule.threshold >= 1.0
                    && tick % (int) rule.threshold == 0) {
                fire(rule, Integer.toString(tick));
            }
        }

        Snapshot now = Snapshot.capture(client);
        Snapshot before = previous;
        previous = now;
        if (before == null) return;

        fireState(Trigger.WORLD_LOADED, before.worldPresent, now.worldPresent, "");
        fireState(Trigger.WORLD_UNLOADED, !before.worldPresent, !now.worldPresent, "");
        if (!now.worldPresent || !before.worldPresent) return;

        if (!before.dimension.equals(now.dimension)) {
            fireText(Trigger.DIMENSION_CHANGED, now.dimension);
        }

        // Vitals: change events plus armed threshold crossings.
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

        for (String effect : now.effects) {
            if (!before.effects.contains(effect)) fireText(Trigger.EFFECT_ADDED, effect);
        }
        for (String effect : before.effects) {
            if (!now.effects.contains(effect)) fireText(Trigger.EFFECT_REMOVED, effect);
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
            String heldItem, boolean heldDamageable, int selectedSlot, boolean inventoryFull,
            boolean containerOpen, Set<String> effects, boolean day, boolean raining,
            Set<String> players) {

        static Snapshot capture(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            if (player == null || client.world == null) {
                return new Snapshot(false, "", 0, false, 0, 0, 0, false, false, false, false,
                        false, false, false, "", false, 0, false, false, Set.of(), false, false,
                        Set.of());
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
            boolean containerOpen = player.currentScreenHandler.slots.stream()
                    .anyMatch(slot -> slot.inventory != player.getInventory());
            long timeOfDay = client.world.getTimeOfDay() % 24000L;
            return new Snapshot(true,
                    client.world.getRegistryKey().getValue().toString(),
                    player.getHealth(), player.getHealth() <= 0.0F,
                    player.getHungerManager().getFoodLevel(), player.getAir(),
                    player.experienceLevel, player.isOnFire(), player.fallDistance > 3.0,
                    player.isSneaking(), player.isSprinting(), player.isSwimming(),
                    player.isGliding(), player.isSubmergedInWater(),
                    net.minecraft.registry.Registries.ITEM.getId(held.getItem()).toString(),
                    held.isDamageable(), player.getInventory().getSelectedSlot(),
                    player.getInventory().getEmptySlot() == -1, containerOpen,
                    effects, timeOfDay < 12000L, client.world.isRaining(), players);
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
        if (trigger.kind == Kind.NUMBER) text.append(' ').append(rule.threshold);
        if (trigger.kind == Kind.TEXT && rule.filter != null && !rule.filter.isEmpty()) {
            text.append(" matching \"").append(rule.filter).append('"');
        }
        return text.append(" run ").append(rule.command).toString();
    }

    public static String describe(Schedule schedule) {
        return "#" + schedule.id + (schedule.intervalTicks > 0
                ? " every " + (schedule.intervalTicks / 20.0) + "s run "
                : " once run ") + schedule.command;
    }

    /** Placeholder documentation shown by the command. */
    public static Map<String, String> placeholders() {
        Map<String, String> map = new HashMap<>();
        map.put("{value}", "the triggering text/number (chat line, title, effect id, ...)");
        map.put("{health}/{hunger}/{air}", "current vitals");
        map.put("{x}/{y}/{z}", "current block position");
        return map;
    }
}
