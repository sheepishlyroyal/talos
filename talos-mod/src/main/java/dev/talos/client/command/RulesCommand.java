package dev.talos.client.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.talos.client.rules.EventRuleEngine;
import dev.talos.client.rules.EventRuleEngine.Rule;
import dev.talos.client.rules.EventRuleEngine.Trigger;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * {@code /talos on <event> ... run <command>}, {@code /talos rules ...},
 * {@code /talos every|after <seconds> run <command>}, {@code /talos schedule ...}.
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
            Trigger.ITEM_SPAWNED, Trigger.ITEM_PICKED_UP, Trigger.ITEM_DESPAWNED,
            Trigger.ITEM_UNLOADED, Trigger.PROJECTILE_LAUNCHED, Trigger.PEARL_THROWN,
            Trigger.PEARL_LANDED, Trigger.POTION_SPLASHED, Trigger.POTION_DRANK,
            Trigger.TOTEM_POPPED, Trigger.ENTITY_STATUS, Trigger.LOOKING_AT_ENTITY,
            Trigger.ATTACK_ENTITY, Trigger.USE_ENTITY, Trigger.PROJECTILE_HIT,
            Trigger.PROJECTILE_STOPPED, Trigger.SNOWBALL_THROWN, Trigger.SNOWBALL_HIT,
            Trigger.EGG_THROWN, Trigger.EGG_HIT, Trigger.ITEM_FRAME_CHANGED);

    public static LiteralArgumentBuilder<FabricClientCommandSource> onNode() {
        LiteralArgumentBuilder<FabricClientCommandSource> on = ClientCommands.literal("on");
        on.then(ClientCommands.literal("list").executes(context -> {
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
                        })
                        .append(EventRuleEngine.acceptsPoint(trigger)
                                ? " [at <x> <y> <z>]" : "");
            }
            context.getSource().sendFeedback(Component.literal("Events: " + names));
            context.getSource().sendFeedback(Component.literal(
                    "Placeholders: {value} {health} {hunger} {air} {x} {y} {z}; "
                            + "coordinates accept absolute, ~ feet-relative, and ^ look-relative; "
                            + "prefix 'chat ' to send chat instead of a command"));
            return 1;
        }));

        for (Trigger trigger : Trigger.values()) {
            LiteralArgumentBuilder<FabricClientCommandSource> node =
                    ClientCommands.literal(trigger.id());
            switch (trigger.kind) {
                case NONE -> node.then(run(trigger, (context, rule) -> { }));
                case NUMBER -> node.then(
                        ClientCommands.argument("value", DoubleArgumentType.doubleArg())
                                .then(run(trigger, (context, rule) ->
                                        rule.threshold = DoubleArgumentType.getDouble(context, "value"))));
                case TEXT -> {
                    node.then(run(trigger, (context, rule) -> { }));
                    node.then(countTail(trigger, (context, rule) -> { }));
                    node.then(ClientCommands.literal("matching")
                            .then(ClientCommands.argument("filter", StringArgumentType.string())
                                    .then(run(trigger, (context, rule) ->
                                            rule.filter = StringArgumentType.getString(context, "filter")))
                                    .then(countTail(trigger, (context, rule) ->
                                            rule.filter = StringArgumentType.getString(context, "filter")))));
                }
                case COMPARE -> {
                    attachComparisons(node, trigger, (context, rule) -> { });
                    if (EventRuleEngine.acceptsPoint(trigger)) {
                        node.then(atComparisons(trigger, (context, rule) -> { }));
                    }
                }
                case ENTITY_COUNT -> {
                    var radius = ClientCommands.argument("radius",
                            DoubleArgumentType.doubleArg(-1.0, 512.0));
                    attachComparisons(radius, trigger, RulesCommand::readEntityArgs);
                    radius.then(atComparisons(trigger, RulesCommand::readEntityArgs));
                    node.then(ClientCommands.argument("selector", SelectorArgumentType.selector())
                            .then(ClientCommands.literal("radius").then(radius)));
                }
                case ENTITY_PRESENCE -> {
                    var radius = ClientCommands.argument("radius",
                            DoubleArgumentType.doubleArg(-1.0, 512.0));
                    radius.then(run(trigger, RulesCommand::readEntityArgs));
                    radius.then(atRun(trigger, RulesCommand::readEntityArgs));
                    node.then(ClientCommands.argument("selector", SelectorArgumentType.selector())
                            .then(ClientCommands.literal("radius").then(radius)));
                }
                case BLOCK_COUNT -> {
                    var radius = ClientCommands.argument("radius",
                            IntegerArgumentType.integer(1, EventRuleEngine.MAX_BLOCK_RADIUS));
                    attachComparisons(radius, trigger, RulesCommand::readBlockArgs);
                    radius.then(atComparisons(trigger, RulesCommand::readBlockArgs));
                    node.then(ClientCommands.argument("block", IdArgumentType.blockId())
                            .then(ClientCommands.literal("radius").then(radius)));
                }
                case BLOCK_PRESENCE -> {
                    var radius = ClientCommands.argument("radius",
                            IntegerArgumentType.integer(1, EventRuleEngine.MAX_BLOCK_RADIUS));
                    radius.then(run(trigger, RulesCommand::readBlockArgs));
                    radius.then(atRun(trigger, RulesCommand::readBlockArgs));
                    node.then(ClientCommands.argument("block", IdArgumentType.blockId())
                            .then(ClientCommands.literal("radius").then(radius)));
                }
                case ITEM_COUNT -> node.then(attachComparisons(
                        ClientCommands.argument("item", IdArgumentType.itemId()),
                        trigger, (context, rule) ->
                                rule.block = StringArgumentType.getString(context, "item")));
                case REGION -> {
                    var z2 = localCoordinate("z2");
                    z2.then(run(trigger, (context, rule) -> {
                        var first = resolvePoint(context, "1");
                        var second = resolvePoint(context, "2");
                        rule.region = new double[] {first.x, first.y, first.z,
                                second.x, second.y, second.z};
                    }));
                    var y2 = localCoordinate("y2").then(z2);
                    var x2 = localCoordinate("x2").then(y2);
                    var z1 = localCoordinate("z1").then(x2);
                    var y1 = localCoordinate("y1").then(z1);
                    node.then(localCoordinate("x1").then(y1));
                }
            }
            // Subject-entity triggers: 'on potion_drank @e[type=player] run ...' — the
            // selector is tested against the event's entity, composable with 'matching'.
            if (ENTITY_SUBJECT.contains(trigger)) {
                node.then(ClientCommands.argument("selector", SelectorArgumentType.selector())
                        .then(run(trigger, (context, rule) ->
                                rule.selector = StringArgumentType.getString(context, "selector")))
                        .then(ClientCommands.literal("matching")
                                .then(ClientCommands.argument("filter", StringArgumentType.string())
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

    private static LiteralArgumentBuilder<FabricClientCommandSource> atComparisons(Trigger trigger,
            BiConsumer<CommandContext<FabricClientCommandSource>, Rule> reader) {
        var at = ClientCommands.literal("at");
        BiConsumer<CommandContext<FabricClientCommandSource>, Rule> pointReader = (context, rule) -> {
            reader.accept(context, rule);
            var point = resolvePoint(context, "at_");
            rule.point = new double[] {point.x, point.y, point.z};
        };
        var z = localCoordinate("at_z");
        attachComparisons(z, trigger, pointReader);
        if (EventRuleEngine.acceptsSpatialRadius(trigger)) {
            z.then(ClientCommands.literal("radius").then(attachComparisons(
                    ClientCommands.argument("at_radius", DoubleArgumentType.doubleArg(0.0, 512.0)),
                    trigger, (context, rule) -> {
                        pointReader.accept(context, rule);
                        rule.radius = DoubleArgumentType.getDouble(context, "at_radius");
                    })));
        }
        at.then(localCoordinate("at_x").then(localCoordinate("at_y").then(z)));
        return at;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> atRun(Trigger trigger,
            BiConsumer<CommandContext<FabricClientCommandSource>, Rule> reader) {
        var at = ClientCommands.literal("at");
        at.then(localCoordinate("at_x").then(localCoordinate("at_y").then(
                localCoordinate("at_z").then(run(trigger, (context, rule) -> {
                    reader.accept(context, rule);
                    var point = resolvePoint(context, "at_");
                    rule.point = new double[] {point.x, point.y, point.z};
                })))));
        return at;
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
            FabricClientCommandSource, LocalCoordinateArgumentType.Axis> localCoordinate(String name) {
        return ClientCommands.argument(name, LocalCoordinateArgumentType.localCoordinate());
    }

    private static net.minecraft.world.phys.Vec3 resolvePoint(
            CommandContext<FabricClientCommandSource> context, String marker) {
        String xName = marker.equals("1") || marker.equals("2") ? "x" + marker : marker + "x";
        String yName = marker.equals("1") || marker.equals("2") ? "y" + marker : marker + "y";
        String zName = marker.equals("1") || marker.equals("2") ? "z" + marker : marker + "z";
        var x = context.getArgument(xName, LocalCoordinateArgumentType.Axis.class);
        var y = context.getArgument(yName, LocalCoordinateArgumentType.Axis.class);
        var z = context.getArgument(zName, LocalCoordinateArgumentType.Axis.class);
        boolean local = x.type() == LocalCoordinateArgumentType.Type.LOCAL
                || y.type() == LocalCoordinateArgumentType.Type.LOCAL
                || z.type() == LocalCoordinateArgumentType.Type.LOCAL;
        boolean relative = x.type() == LocalCoordinateArgumentType.Type.RELATIVE
                || y.type() == LocalCoordinateArgumentType.Type.RELATIVE
                || z.type() == LocalCoordinateArgumentType.Type.RELATIVE;
        if (local && relative) throw new IllegalArgumentException(
                "Cannot mix ^ local and ~ relative coordinates");
        var player = context.getSource().getPlayer();
        if (local) return RaycastMath.local(player.getEyePosition(), player.getYRot(),
                player.getXRot(), x.value(), y.value(), z.value());
        var feet = player.position();
        return new net.minecraft.world.phys.Vec3(
                x.type() == LocalCoordinateArgumentType.Type.RELATIVE ? feet.x + x.value() : x.value(),
                y.type() == LocalCoordinateArgumentType.Type.RELATIVE ? feet.y + y.value() : y.value(),
                z.type() == LocalCoordinateArgumentType.Type.RELATIVE ? feet.z + z.value() : z.value());
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
            parent.then(ClientCommands.literal(op)
                    .then(ClientCommands.argument("amount", DoubleArgumentType.doubleArg())
                            .then(run(trigger, base))
                            // sustained: the comparison must hold for the whole window
                            .then(ClientCommands.literal("for")
                                    .then(ClientCommands.argument("seconds",
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
                    ClientCommands.literal("changes");
            for (String op : new String[] {"above", "below", "equals"}) {
                changes.then(ClientCommands.literal(op)
                        .then(ClientCommands.argument("amount", DoubleArgumentType.doubleArg())
                                .then(ClientCommands.literal("within")
                                        .then(ClientCommands.argument("seconds",
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
        return ClientCommands.literal("count")
                .then(ClientCommands.literal("above")
                        .then(ClientCommands.argument("n", IntegerArgumentType.integer(1))
                                .then(ClientCommands.literal("within")
                                        .then(ClientCommands.argument("seconds",
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
        return ClientCommands.literal("run")
                .then(ClientCommands.argument("command", StringArgumentType.greedyString())
                        .executes(context -> {
                            Rule rule = new Rule();
                            rule.trigger = trigger.id();
                            rule.command = StringArgumentType.getString(context, "command");
                            reader.accept(context, rule);
                            int id = EventRuleEngine.addRule(rule);
                            context.getSource().sendFeedback(Component.literal(
                                    "Rule armed: " + EventRuleEngine.describe(
                                            EventRuleEngine.rules().stream()
                                                    .filter(saved -> saved.id == id)
                                                    .findFirst().orElse(rule))));
                            return 1;
                        }));
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> rulesNode() {
        return ClientCommands.literal("rules")
                .then(ClientCommands.literal("list").executes(context -> {
                    var rules = EventRuleEngine.rules();
                    var schedules = EventRuleEngine.schedules();
                    if (rules.isEmpty() && schedules.isEmpty()) {
                        context.getSource().sendFeedback(Component.literal("No rules or schedules"));
                        return 1;
                    }
                    for (var rule : rules) {
                        context.getSource().sendFeedback(
                                Component.literal(EventRuleEngine.describe(rule)));
                    }
                    for (var schedule : schedules) {
                        context.getSource().sendFeedback(
                                Component.literal(EventRuleEngine.describe(schedule)));
                    }
                    return 1;
                }))
                .then(ClientCommands.literal("remove")
                        .then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                                .executes(context -> {
                                    int id = IntegerArgumentType.getInteger(context, "id");
                                    boolean removed = EventRuleEngine.remove(id);
                                    if (removed) context.getSource().sendFeedback(
                                            Component.literal("Removed #" + id));
                                    else context.getSource().sendError(
                                            Component.literal("No rule or schedule #" + id));
                                    return removed ? 1 : 0;
                                })))
                .then(ClientCommands.literal("clear").executes(context -> {
                    EventRuleEngine.clear();
                    context.getSource().sendFeedback(Component.literal("Cleared all rules and schedules"));
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
        return ClientCommands.literal(literal)
                .then(ClientCommands.argument("seconds", DoubleArgumentType.doubleArg(0.05, 86_400.0))
                        .then(ClientCommands.literal("run")
                                .then(ClientCommands.argument("command", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            double seconds = DoubleArgumentType.getDouble(context, "seconds");
                                            String command = StringArgumentType.getString(context, "command");
                                            int ticks = Math.max(1, (int) Math.round(seconds * 20.0));
                                            int id = EventRuleEngine.addSchedule(command,
                                                    repeating ? ticks : 0, ticks);
                                            context.getSource().sendFeedback(Component.literal(
                                                    "Schedule #" + id + ": " + (repeating ? "every " : "after ")
                                                            + seconds + "s run " + command
                                                            + (repeating ? " (persists)" : " (this session)")));
                                            return 1;
                                        }))));
    }
}
