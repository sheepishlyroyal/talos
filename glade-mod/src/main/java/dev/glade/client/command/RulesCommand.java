package dev.glade.client.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.glade.client.rules.EventRuleEngine;
import dev.glade.client.rules.EventRuleEngine.Rule;
import dev.glade.client.rules.EventRuleEngine.Trigger;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/**
 * {@code /glade on <event> ... run <command>}, {@code /glade rules ...},
 * {@code /glade every|after <seconds> run <command>}, {@code /glade schedule ...}.
 *
 * <p>Each trigger family contributes its own grammar (selector+radius+comparison for entities,
 * block+radius+comparison for blocks, comparisons for metrics, ...), so the concrete trigger
 * space is combinatorial rather than a fixed list.</p>
 */
public final class RulesCommand {
    private RulesCommand() {}

    /** Triggers whose event has a subject entity, so @e/@a/@p/@s rules can filter them. */
    private static final java.util.Set<Trigger> ENTITY_SUBJECT = java.util.Set.of(
            Trigger.ENTITY_HELD_CHANGED, Trigger.ENTITY_OFFHAND_CHANGED,
            Trigger.ENTITY_ARMOR_CHANGED, Trigger.ENTITY_HURT, Trigger.ENTITY_DIED,
            Trigger.ENTITY_DAMAGED, Trigger.ENTITY_HEALED, Trigger.ENTITY_STARTED_BURNING,
            Trigger.ENTITY_MOUNTED, Trigger.ENTITY_DISMOUNTED, Trigger.ENTITY_SNEAKING,
            Trigger.ENTITY_SPRINTING, Trigger.ENTITY_USING_ITEM, Trigger.ENTITY_BLOCKING,
            Trigger.ENTITY_GLIDING, Trigger.ENTITY_SWIMMING, Trigger.ENTITY_SLEEPING,
            Trigger.ENTITY_BABY_GROWN, Trigger.VILLAGER_PROFESSION_CHANGED,
            Trigger.VILLAGER_LEVEL_CHANGED, Trigger.PLAYER_HELD_CHANGED,
            Trigger.PLAYER_OFFHAND_CHANGED, Trigger.PLAYER_ARMOR_CHANGED,
            Trigger.ENTITY_SPAWNED, Trigger.ENTITY_REMOVED, Trigger.ENTITY_UNLOADED,
            Trigger.ITEM_PICKED_UP, Trigger.PROJECTILE_LAUNCHED, Trigger.PEARL_THROWN,
            Trigger.PEARL_LANDED, Trigger.POTION_SPLASHED, Trigger.POTION_DRANK,
            Trigger.TOTEM_POPPED, Trigger.ENTITY_STATUS, Trigger.LOOKING_AT_ENTITY,
            Trigger.ATTACK_ENTITY, Trigger.USE_ENTITY, Trigger.PROJECTILE_HIT,
            Trigger.PROJECTILE_STOPPED, Trigger.SNOWBALL_THROWN, Trigger.SNOWBALL_HIT,
            Trigger.EGG_THROWN, Trigger.EGG_HIT);

    public static LiteralArgumentBuilder<FabricClientCommandSource> onNode() {
        LiteralArgumentBuilder<FabricClientCommandSource> on = ClientCommandManager.literal("on");
        on.then(ClientCommandManager.literal("list").executes(context -> {
            StringBuilder names = new StringBuilder();
            for (Trigger trigger : Trigger.values()) {
                names.append(names.isEmpty() ? "" : ", ").append(trigger.id())
                        .append(switch (trigger.kind) {
                            case NUMBER -> " <n>";
                            case COMPARE -> " above|below|equals <n>";
                            case TEXT -> " [matching <text>]";
                            case ENTITY_COUNT -> " <@e[..]|@a|@p|@s> radius <r|-1> above|below|equals <n>";
                            case ENTITY_PRESENCE -> " <selector> radius <r|-1>";
                            case BLOCK_COUNT -> " <block> radius <r> above|below|equals <n>";
                            case BLOCK_PRESENCE -> " <block> radius <r>";
                            case ITEM_COUNT -> " <item> above|below|equals <n>";
                            case REGION -> " <x1> <y1> <z1> <x2> <y2> <z2>";
                            case NONE -> "";
                        });
            }
            context.getSource().sendFeedback(Text.literal("Events: " + names));
            context.getSource().sendFeedback(Text.literal(
                    "Placeholders: {value} {health} {hunger} {air} {x} {y} {z}; "
                            + "prefix 'chat ' to send chat instead of a command"));
            return 1;
        }));

        for (Trigger trigger : Trigger.values()) {
            LiteralArgumentBuilder<FabricClientCommandSource> node =
                    ClientCommandManager.literal(trigger.id());
            switch (trigger.kind) {
                case NONE -> node.then(run(trigger, (context, rule) -> { }));
                case NUMBER -> node.then(
                        ClientCommandManager.argument("value", DoubleArgumentType.doubleArg())
                                .then(run(trigger, (context, rule) ->
                                        rule.threshold = DoubleArgumentType.getDouble(context, "value"))));
                case TEXT -> {
                    node.then(run(trigger, (context, rule) -> { }));
                    node.then(countTail(trigger, (context, rule) -> { }));
                    node.then(ClientCommandManager.literal("matching")
                            .then(ClientCommandManager.argument("filter", StringArgumentType.string())
                                    .then(run(trigger, (context, rule) ->
                                            rule.filter = StringArgumentType.getString(context, "filter")))
                                    .then(countTail(trigger, (context, rule) ->
                                            rule.filter = StringArgumentType.getString(context, "filter")))));
                }
                case COMPARE -> attachComparisons(node, trigger, (context, rule) -> { });
                case ENTITY_COUNT -> node.then(
                        ClientCommandManager.argument("selector", SelectorArgumentType.selector())
                                .then(ClientCommandManager.literal("radius")
                                        .then(attachComparisons(
                                                ClientCommandManager.argument("radius",
                                                        DoubleArgumentType.doubleArg(-1.0, 512.0)),
                                                trigger, RulesCommand::readEntityArgs))));
                case ENTITY_PRESENCE -> node.then(
                        ClientCommandManager.argument("selector", SelectorArgumentType.selector())
                                .then(ClientCommandManager.literal("radius")
                                        .then(ClientCommandManager.argument("radius",
                                                        DoubleArgumentType.doubleArg(-1.0, 512.0))
                                                .then(run(trigger, RulesCommand::readEntityArgs)))));
                case BLOCK_COUNT -> node.then(
                        ClientCommandManager.argument("block", StringArgumentType.string())
                                .then(ClientCommandManager.literal("radius")
                                        .then(attachComparisons(
                                                ClientCommandManager.argument("radius",
                                                        IntegerArgumentType.integer(1, EventRuleEngine.MAX_BLOCK_RADIUS)),
                                                trigger, RulesCommand::readBlockArgs))));
                case BLOCK_PRESENCE -> node.then(
                        ClientCommandManager.argument("block", StringArgumentType.string())
                                .then(ClientCommandManager.literal("radius")
                                        .then(ClientCommandManager.argument("radius",
                                                        IntegerArgumentType.integer(1, EventRuleEngine.MAX_BLOCK_RADIUS))
                                                .then(run(trigger, RulesCommand::readBlockArgs)))));
                case ITEM_COUNT -> node.then(attachComparisons(
                        ClientCommandManager.argument("item", StringArgumentType.string()),
                        trigger, (context, rule) ->
                                rule.block = StringArgumentType.getString(context, "item")));
                case REGION -> node.then(
                        ClientCommandManager.argument("x1", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("y1", IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("z1", IntegerArgumentType.integer())
                                                .then(ClientCommandManager.argument("x2", IntegerArgumentType.integer())
                                                        .then(ClientCommandManager.argument("y2", IntegerArgumentType.integer())
                                                                .then(ClientCommandManager.argument("z2", IntegerArgumentType.integer())
                                                                        .then(run(trigger, (context, rule) ->
                                                                                rule.region = new double[] {
                                                                                        IntegerArgumentType.getInteger(context, "x1"),
                                                                                        IntegerArgumentType.getInteger(context, "y1"),
                                                                                        IntegerArgumentType.getInteger(context, "z1"),
                                                                                        IntegerArgumentType.getInteger(context, "x2"),
                                                                                        IntegerArgumentType.getInteger(context, "y2"),
                                                                                        IntegerArgumentType.getInteger(context, "z2")}))))))));
            }
            // Subject-entity triggers: 'on potion_drank @e[type=player] run ...' — the
            // selector is tested against the event's entity, composable with 'matching'.
            if (ENTITY_SUBJECT.contains(trigger)) {
                node.then(ClientCommandManager.argument("selector", SelectorArgumentType.selector())
                        .then(run(trigger, (context, rule) ->
                                rule.selector = StringArgumentType.getString(context, "selector")))
                        .then(ClientCommandManager.literal("matching")
                                .then(ClientCommandManager.argument("filter", StringArgumentType.string())
                                        .then(run(trigger, (context, rule) -> {
                                            rule.selector = StringArgumentType.getString(context, "selector");
                                            rule.filter = StringArgumentType.getString(context, "filter");
                                        })))));
            }
            on.then(node);
        }
        return on;
    }

    private static void readEntityArgs(CommandContext<FabricClientCommandSource> context, Rule rule) {
        rule.selector = StringArgumentType.getString(context, "selector");
        rule.radius = DoubleArgumentType.getDouble(context, "radius");
    }

    private static void readBlockArgs(CommandContext<FabricClientCommandSource> context, Rule rule) {
        rule.block = StringArgumentType.getString(context, "block");
        rule.radius = IntegerArgumentType.getInteger(context, "radius");
    }

    /**
     * Attaches above|below|equals <amount> [for <seconds>] run ... children directly to
     * {@code parent}, plus (for metrics) changes above|below <delta> within <seconds> run.
     */
    private static <T extends ArgumentBuilder<FabricClientCommandSource, T>> T attachComparisons(
            T parent, Trigger trigger,
            BiConsumer<CommandContext<FabricClientCommandSource>, Rule> reader) {
        for (String op : new String[] {"above", "below", "equals"}) {
            BiConsumer<CommandContext<FabricClientCommandSource>, Rule> base = (context, rule) -> {
                reader.accept(context, rule);
                rule.compare = op;
                rule.amount = DoubleArgumentType.getDouble(context, "amount");
            };
            parent.then(ClientCommandManager.literal(op)
                    .then(ClientCommandManager.argument("amount", DoubleArgumentType.doubleArg())
                            .then(run(trigger, base))
                            // sustained: the comparison must hold for the whole window
                            .then(ClientCommandManager.literal("for")
                                    .then(ClientCommandManager.argument("seconds",
                                                    DoubleArgumentType.doubleArg(0.05, 3600.0))
                                            .then(run(trigger, (context, rule) -> {
                                                base.accept(context, rule);
                                                rule.mode = "sustained";
                                                rule.window = DoubleArgumentType.getDouble(context, "seconds");
                                            }))))));
        }
        if (trigger.kind == EventRuleEngine.Kind.COMPARE) {
            // windowed net change: 'health changes below -4 within 2' = burst damage
            LiteralArgumentBuilder<FabricClientCommandSource> changes =
                    ClientCommandManager.literal("changes");
            for (String op : new String[] {"above", "below", "equals"}) {
                changes.then(ClientCommandManager.literal(op)
                        .then(ClientCommandManager.argument("amount", DoubleArgumentType.doubleArg())
                                .then(ClientCommandManager.literal("within")
                                        .then(ClientCommandManager.argument("seconds",
                                                        DoubleArgumentType.doubleArg(0.05, 3600.0))
                                                .then(run(trigger, (context, rule) -> {
                                                    reader.accept(context, rule);
                                                    rule.compare = op;
                                                    rule.amount = DoubleArgumentType.getDouble(context, "amount");
                                                    rule.mode = "changes";
                                                    rule.window = DoubleArgumentType.getDouble(context, "seconds");
                                                }))))));
            }
            parent.then(changes);
        }
        return parent;
    }

    /** count above <n> within <seconds> run — event-frequency tail for TEXT triggers. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> countTail(Trigger trigger,
            BiConsumer<CommandContext<FabricClientCommandSource>, Rule> reader) {
        return ClientCommandManager.literal("count")
                .then(ClientCommandManager.literal("above")
                        .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1))
                                .then(ClientCommandManager.literal("within")
                                        .then(ClientCommandManager.argument("seconds",
                                                        DoubleArgumentType.doubleArg(0.05, 3600.0))
                                                .then(run(trigger, (context, rule) -> {
                                                    reader.accept(context, rule);
                                                    rule.mode = "count";
                                                    rule.amount = IntegerArgumentType.getInteger(context, "n");
                                                    rule.window = DoubleArgumentType.getDouble(context, "seconds");
                                                }))))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> run(Trigger trigger,
            BiConsumer<CommandContext<FabricClientCommandSource>, Rule> reader) {
        return ClientCommandManager.literal("run")
                .then(ClientCommandManager.argument("command", StringArgumentType.greedyString())
                        .executes(context -> {
                            Rule rule = new Rule();
                            rule.trigger = trigger.id();
                            rule.command = StringArgumentType.getString(context, "command");
                            reader.accept(context, rule);
                            int id = EventRuleEngine.addRule(rule);
                            context.getSource().sendFeedback(Text.literal(
                                    "Rule armed: " + EventRuleEngine.describe(
                                            EventRuleEngine.rules().stream()
                                                    .filter(saved -> saved.id == id)
                                                    .findFirst().orElse(rule))));
                            return 1;
                        }));
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> rulesNode() {
        return ClientCommandManager.literal("rules")
                .then(ClientCommandManager.literal("list").executes(context -> {
                    var rules = EventRuleEngine.rules();
                    var schedules = EventRuleEngine.schedules();
                    if (rules.isEmpty() && schedules.isEmpty()) {
                        context.getSource().sendFeedback(Text.literal("No rules or schedules"));
                        return 1;
                    }
                    for (var rule : rules) {
                        context.getSource().sendFeedback(
                                Text.literal(EventRuleEngine.describe(rule)));
                    }
                    for (var schedule : schedules) {
                        context.getSource().sendFeedback(
                                Text.literal(EventRuleEngine.describe(schedule)));
                    }
                    return 1;
                }))
                .then(ClientCommandManager.literal("remove")
                        .then(ClientCommandManager.argument("id", IntegerArgumentType.integer(1))
                                .executes(context -> {
                                    int id = IntegerArgumentType.getInteger(context, "id");
                                    boolean removed = EventRuleEngine.remove(id);
                                    if (removed) context.getSource().sendFeedback(
                                            Text.literal("Removed #" + id));
                                    else context.getSource().sendError(
                                            Text.literal("No rule or schedule #" + id));
                                    return removed ? 1 : 0;
                                })))
                .then(ClientCommandManager.literal("clear").executes(context -> {
                    EventRuleEngine.clear();
                    context.getSource().sendFeedback(Text.literal("Cleared all rules and schedules"));
                    return 1;
                }));
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> everyNode() {
        return schedule("every", true);
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> afterNode() {
        return schedule("after", false);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> schedule(String literal,
            boolean repeating) {
        return ClientCommandManager.literal(literal)
                .then(ClientCommandManager.argument("seconds", DoubleArgumentType.doubleArg(0.05, 86_400.0))
                        .then(ClientCommandManager.literal("run")
                                .then(ClientCommandManager.argument("command", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            double seconds = DoubleArgumentType.getDouble(context, "seconds");
                                            String command = StringArgumentType.getString(context, "command");
                                            int ticks = Math.max(1, (int) Math.round(seconds * 20.0));
                                            int id = EventRuleEngine.addSchedule(command,
                                                    repeating ? ticks : 0, ticks);
                                            context.getSource().sendFeedback(Text.literal(
                                                    "Schedule #" + id + ": " + (repeating ? "every " : "after ")
                                                            + seconds + "s run " + command
                                                            + (repeating ? " (persists)" : " (this session)")));
                                            return 1;
                                        }))));
    }
}
