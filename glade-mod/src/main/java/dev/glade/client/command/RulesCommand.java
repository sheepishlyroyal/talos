package dev.glade.client.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.glade.client.rules.EventRuleEngine;
import dev.glade.client.rules.EventRuleEngine.Trigger;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/**
 * {@code /glade on <event> ... run <command>}, {@code /glade rules ...},
 * {@code /glade every|after <seconds> run <command>}, {@code /glade schedule ...}.
 */
public final class RulesCommand {
    private RulesCommand() {}

    public static LiteralArgumentBuilder<FabricClientCommandSource> onNode() {
        LiteralArgumentBuilder<FabricClientCommandSource> on = ClientCommandManager.literal("on");
        on.then(ClientCommandManager.literal("list").executes(context -> {
            StringBuilder names = new StringBuilder();
            for (Trigger trigger : Trigger.values()) {
                names.append(names.isEmpty() ? "" : ", ").append(trigger.id());
                if (trigger.kind == EventRuleEngine.Kind.NUMBER) names.append(" <n>");
            }
            context.getSource().sendFeedback(Text.literal("Events: " + names));
            context.getSource().sendFeedback(Text.literal(
                    "Placeholders in commands: {value} {health} {hunger} {air} {x} {y} {z}; "
                            + "prefix 'chat ' to send chat instead of a command"));
            return 1;
        }));
        for (Trigger trigger : Trigger.values()) {
            LiteralArgumentBuilder<FabricClientCommandSource> node =
                    ClientCommandManager.literal(trigger.id());
            switch (trigger.kind) {
                case NUMBER -> node.then(
                        ClientCommandManager.argument("value", DoubleArgumentType.doubleArg())
                                .then(run(trigger, true)));
                case TEXT -> {
                    node.then(run(trigger, false));
                    node.then(ClientCommandManager.literal("matching")
                            .then(ClientCommandManager.argument("filter", StringArgumentType.string())
                                    .then(run(trigger, false))));
                }
                case NONE -> node.then(run(trigger, false));
            }
            on.then(node);
        }
        return on;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> run(Trigger trigger,
            boolean numeric) {
        return ClientCommandManager.literal("run")
                .then(ClientCommandManager.argument("command", StringArgumentType.greedyString())
                        .executes(context -> {
                            String command = StringArgumentType.getString(context, "command");
                            double threshold = numeric
                                    ? DoubleArgumentType.getDouble(context, "value") : 0.0;
                            String filter = null;
                            try {
                                filter = StringArgumentType.getString(context, "filter");
                            } catch (IllegalArgumentException ignored) {
                                // no matching-clause on this branch
                            }
                            int id = EventRuleEngine.addRule(trigger, filter, threshold, command);
                            context.getSource().sendFeedback(Text.literal(
                                    "Rule #" + id + " armed: on " + trigger.id()
                                            + (numeric ? " " + threshold : "")
                                            + (filter != null ? " matching \"" + filter + "\"" : "")
                                            + " run " + command));
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
