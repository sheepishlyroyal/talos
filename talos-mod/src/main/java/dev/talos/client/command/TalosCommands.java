package dev.talos.client.command;

import dev.talos.client.scan.BlockStatePredicate;
import dev.talos.client.bridge.TalosBridge;
import dev.talos.client.pathing.GoalBlock;
import dev.talos.client.pathing.GoalNear;
import dev.talos.client.pathing.GoalXZ;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.talos.client.script.ScriptCommandRegistry;
import dev.talos.client.script.ScriptEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

public final class TalosCommands {
    private TalosCommands() {
    }

    /**
     * Tab-completes {@code /talos script run <name>} with the {@code *.py} files in
     * {@code <gameDir>/talos/scripts} — the exact directory {@link ScriptEngine#run}
     * resolves. Names are suggested WITHOUT the extension (run() accepts both forms).
     * A missing directory just yields no suggestions, never an error.
     */
    private static final SuggestionProvider<FabricClientCommandSource> SCRIPT_NAMES =
            (context, builder) -> {
                List<String> names = new ArrayList<>();
                Path scripts = FabricLoader.getInstance().getGameDir()
                        .resolve("talos").resolve("scripts");
                try (var files = Files.list(scripts)) {
                    files.map(path -> path.getFileName().toString())
                            .filter(name -> name.endsWith(".py"))
                            .map(name -> name.substring(0, name.length() - 3))
                            .sorted()
                            .forEach(names::add);
                } catch (IOException ignored) {
                    // no scripts directory yet — suggest nothing
                }
                return SharedSuggestionProvider.suggest(names, builder);
            };

    /** Tab-completes {@code /talos cmd <name>} with the currently registered script commands. */
    private static final SuggestionProvider<FabricClientCommandSource> SCRIPT_COMMAND_NAMES =
            (context, builder) -> SharedSuggestionProvider.suggest(ScriptCommandRegistry.names(), builder);

    /** Tab-completes {@code /talos example <name>} with the embedded reference scripts. */
    private static final SuggestionProvider<FabricClientCommandSource> EXAMPLE_NAMES =
            (context, builder) -> SharedSuggestionProvider.suggest(TalosCommands.EXAMPLES.keySet(), builder);

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(TalosCommands::registerCommands);
    }

    private static void registerCommands(
            com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> dispatcher,
            CommandBuildContext registryAccess) {
        var talosRoot = dispatcher.register(ClientCommands.literal("talos")
                .then(ClientCommands.literal("find")
                        .then(ClientCommands.literal("block")
                                .then(ClientCommands.argument(
                                                "blockPredicate", BlockStatePredicate.argument(registryAccess))
                                        .executes(context -> FindCommand.execute(
                                                context,
                                                context.getSource().getClient().options.renderDistance().get()))
                                        .then(ClientCommands.argument(
                                                        "radius", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> FindCommand.execute(
                                                        context,
                                                        IntegerArgumentType.getInteger(context, "radius")))))))
                .then(ClientCommands.literal("goto")
                        // `/talos goto block <id> [radius]` — nearest matching block, with
                        // blacklist-and-retry when a specific candidate proves unreachable.
                        .then(ClientCommands.literal("block")
                                .then(ClientCommands.argument("blockId", IdArgumentType.blockId())
                                        .executes(context -> scriptOverride(context, "goto") ? 1
                                                : GotoBlockCommand.execute(context,
                                                StringArgumentType.getString(context, "blockId"), 64))
                                        .then(ClientCommands.argument(
                                                        "radius", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> scriptOverride(context, "goto") ? 1
                                                        : GotoBlockCommand.execute(context,
                                                        StringArgumentType.getString(context, "blockId"),
                                                        IntegerArgumentType.getInteger(context, "radius"))))))
                        .then(ClientCommands.literal("near")
                                .then(coordinate("x")
                                        .then(coordinate("y")
                                                .then(coordinate("z")
                                                        .then(ClientCommands.argument(
                                                                        "range", IntegerArgumentType.integer(0))
                                                                .executes(context -> scriptOverride(context, "goto") ? 1
                                                                        : GotoCommand.execute(
                                                                        context,
                                                                        new GoalNear(
                                                                                coordValue(context, "x", eyePos(context).x),
                                                                                coordValue(context, "y", eyePos(context).y),
                                                                                coordValue(context, "z", eyePos(context).z),
                                                                                value(context, "range")))))))))
                        // `/talos goto xyz <x> <z>` — pathfind to X,Z at the player's current Y.
                        // `/talos goto xyz <x> <y> <z>` — pathfind to an exact block. These are
                        // two distinct sibling argument nodes under "x" (named "z" and "y"
                        // respectively) so the 2nd token's meaning is unambiguous per branch —
                        // chaining a shared node here would silently swap Y and Z. The "xyz"
                        // literal keeps raw coordinates from colliding with the named modes.
                        .then(ClientCommands.literal("xyz")
                                .then(coordinate("x")
                                        .then(coordinate("z")
                                                .executes(context -> scriptOverride(context, "goto") ? 1
                                                        : GotoCommand.execute(context, xzGoal(context))))
                                        .then(coordinate("y")
                                                .then(coordinate("z")
                                                        .executes(context -> scriptOverride(context, "goto") ? 1
                                                                : GotoCommand.execute(
                                                                context,
                                                                new GoalBlock(
                                                                        coordValue(context, "x", eyePos(context).x),
                                                                        coordValue(context, "y", eyePos(context).y),
                                                                        coordValue(context, "z", eyePos(context).z)))))))))
                // Back-compat alias: `/talos xz <x> <z>` delegates to the same XZ handler as
                // `/talos goto <x> <z>`.
                .then(ClientCommands.literal("xz")
                        .then(coordinate("x")
                                .then(coordinate("z")
                                        .executes(context -> GotoCommand.execute(
                                                context, xzGoal(context))))))
                .then(lookNode(registryAccess))
                // `/talos human [on|off]` — toggle eased aim and session-arc fatigue.
                .then(ClientCommands.literal("human")
                        .executes(context -> setHuman(context,
                                !dev.talos.client.TalosClient.humanizer().humanMode()))
                        .then(ClientCommands.literal("on")
                                .executes(context -> setHuman(context, true)))
                        .then(ClientCommands.literal("off")
                                .executes(context -> setHuman(context, false))))
                .then(ClientCommands.literal("glow")
                        .then(coordinate("x")
                                .then(coordinate("y")
                                        .then(coordinate("z")
                                                .executes(context -> GlowCommand.execute(
                                                        context, blockPos(context), GlowCommand.DEFAULT_SECONDS))
                                                .then(ClientCommands.argument(
                                                                "seconds", IntegerArgumentType.integer(1, 3600))
                                                        .executes(context -> GlowCommand.execute(
                                                                context,
                                                                blockPos(context),
                                                                value(context, "seconds"))))))))
                .then(ClientCommands.literal("mine")
                        .then(coordinates((context, pos) -> scriptOverride(context, "mine") ? 1
                                : ActionCommand.mine(context, pos)))
                        .then(ClientCommands.literal("direction")
                                .then(directionNode((context, pos) -> scriptOverride(context, "mine") ? 1
                                        : ActionCommand.mine(context, pos))))
                        .then(ClientCommands.literal("block")
                                .then(ClientCommands.argument(
                                                "blockPredicate", BlockStatePredicate.argument(registryAccess))
                                        .executes(context -> scriptOverride(context, "mine") ? 1
                                                : ActionCommand.mineBlock(context, 0))
                                        .then(indexArgument(n -> scriptOverride(n.context(), "mine") ? 1
                                                : ActionCommand.mineBlock(n.context(), n.n()))))))
                .then(ClientCommands.literal("place")
                        .then(coordinates((context, pos) -> scriptOverride(context, "place") ? 1
                                : ActionCommand.place(context, pos))))
                .then(ClientCommands.literal("coords")
                        .then(ClientCommands.literal("direction")
                                .then(directionNode(CoordsCommand::executeDirection))))
                .then(raytraceNode())
                .then(ClientCommands.literal("ui")
                        .executes(UiCommand::execute))
                .then(ClientCommands.literal("script")
                        .then(ClientCommands.literal("run")
                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .suggests(SCRIPT_NAMES)
                                        .executes(context -> runScript(context, ""))
                                        // `/talos script run <name> <args...>` — whitespace-split
                                        // into the list the script reads as talos.args.
                                        .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                                                .executes(context -> runScript(context,
                                                        StringArgumentType.getString(context, "args"))))))
                        .then(ClientCommands.literal("stop")
                                .executes(context -> {
                                    ScriptEngine.instance().stop();
                                    context.getSource().sendFeedback(Component.literal("Stopped script engine"));
                                    return 1;
                                }))
                        // `/talos script profile` — toggle per-event dispatch profiling;
                        // toggling OFF prints the aggregated report.
                        .then(ClientCommands.literal("profile")
                                .executes(TalosCommands::toggleProfile)))
                // `/talos py <code>` — run a Python one-liner. Semicolons separate
                // statements; a trailing expression echoes its repr to chat. Shares the
                // running script's globals when a session is live, otherwise runs (and
                // discards) an ephemeral session. Python only ever runs on the worker.
                .then(ClientCommands.literal("py")
                        .then(ClientCommands.argument("code", StringArgumentType.greedyString())
                                .executes(TalosCommands::runPySnippet)))
                .then(ClientCommands.literal("bridge")
                        .then(ClientCommands.literal("allow")
                                .executes(context -> {
                                    int result = TalosBridge.allowSession();
                                    if (result == 0) context.getSource().sendError(Component.literal("Talos bridge is not running"));
                                    else context.getSource().sendFeedback(Component.literal("VS Code bridge allowed for this session"));
                                    return result;
                                }))
                        .then(ClientCommands.literal("status")
                                .executes(context -> {
                                    context.getSource().sendFeedback(Component.literal(TalosBridge.statusText()));
                                    return 1;
                                })))
                .then(ClientCommands.literal("stop")
                        .executes(StopCommand::execute)
                        .then(ClientCommands.literal("all")
                                .executes(StopCommand::execute)))
                // `/talos follow <target> [keeping <distance>]` — target is a player name,
                // an entity type, or a selector (@e[type=cow,distance=..20]); greedy so
                // selectors with spaces survive. Script-overridable like goto/mine/kill.
                .then(ClientCommands.literal("follow")
                        .then(ClientCommands.argument("target", StringArgumentType.greedyString())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        followSuggestions(context.getSource()), builder))
                                .executes(context -> scriptOverride(context, "follow") ? 1
                                        : FollowCommand.execute(context, followTarget(context),
                                        followDistance(context, 3.0)))))
                .then(InputCommand.walkNode())
                .then(InputCommand.keyNode())
                .then(InvCommand.node())
                .then(RulesCommand.onNode())
                .then(RulesCommand.rulesNode())
                .then(RulesCommand.everyNode())
                .then(RulesCommand.afterNode())
                .then(MacroCommand.node())
                .then(GetCommand.node())
                // Merged into the same /talos get literal by brigadier: block id at explicit
                // (~-relative) coordinates, or along a (^-relative) look direction.
                .then(ClientCommands.literal("get")
                        .then(ClientCommands.literal("block")
                                .then(coordinates(GetCommand::blockAtPos))
                                .then(ClientCommands.literal("direction")
                                        .then(directionNode(GetCommand::blockAtPos)))))
                .then(TrackCommand.node())
                .then(ClientCommands.literal("kill")
                        .then(ClientCommands.literal("nearest")
                                .executes(context -> scriptOverride(context, "kill") ? 1
                                        : ActionCommand.killNearest(context, 6.0))
                                .then(ClientCommands.argument(
                                                "radius", DoubleArgumentType.doubleArg(1.0, 64.0))
                                        .executes(context -> scriptOverride(context, "kill") ? 1
                                                : ActionCommand.killNearest(context,
                                                DoubleArgumentType.getDouble(context, "radius"))))))
                // `/talos cmd <name> [args]` — invoke a script-registered command whose name
                // doesn't collide with (or shadow) a built-in subcommand's argument shape.
                .then(ClientCommands.literal("cmd")
                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                .suggests(SCRIPT_COMMAND_NAMES)
                                .executes(context -> runScriptCommand(context, ""))
                                .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                                        .executes(context -> runScriptCommand(
                                                context, StringArgumentType.getString(context, "args"))))))
                // `/talos example <name>` — write a commented reference script to
                // talos/scripts/example_<name>.py (overwriting: they are reference material).
                .then(ClientCommands.literal("example")
                        // Bare `/talos example` lists everything available.
                        .executes(context -> {
                            context.getSource().sendFeedback(Component.literal("Available examples: "
                                    + String.join(", ", EXAMPLES.keySet().stream().sorted().toList())
                                    + " — write one with /talos example <name>"));
                            return 1;
                        })
                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                .suggests(EXAMPLE_NAMES)
                                .executes(TalosCommands::writeExample)))
                // Fallback: `/talos <name> [args]` for script-registered commands. Brigadier
                // prefers literal nodes, so every built-in above wins first; only an unknown
                // first token lands here and is dispatched to @talos.command handlers —
                // making `/talos pygoto 0 64 0` work exactly as the examples advertise
                // (`/talos cmd <name>` remains for names that shadow a built-in).
                .then(ClientCommands.argument("name", StringArgumentType.word())
                        .suggests(SCRIPT_COMMAND_NAMES)
                        .executes(context -> runScriptCommand(context, ""))
                        .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                                .executes(context -> runScriptCommand(
                                        context, StringArgumentType.getString(context, "args"))))));
    }

    /** Toggles eased aim plus session-arc "Human mode" and reports the new state. */
    private static int setHuman(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context,
            boolean enabled) {
        dev.talos.client.TalosClient.humanizer().setHumanMode(enabled);
        if (enabled) {
            context.getSource().sendFeedback(Component.literal(
                    "§bTalos §7» §fHuman mode §aON§f — aim is eased (never a direct snap); "
                    + "fatigue drifts reactions, aim and timing over the session, with idle "
                    + "micro-breaks. Best-effort, not undetectable."));
        } else {
            context.getSource().sendFeedback(Component.literal(
                    "§bTalos §7» §fHuman mode §cOFF§f — aim snaps directly; stationary "
                    + dev.talos.client.TalosClient.humanizer().baseProfile().name() + " profile."));
        }
        return 1;
    }

    /** Dispatches {@code /talos py <code>} to the script engine's snippet evaluator. */
    private static int runPySnippet(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context) {
        String code = StringArgumentType.getString(context, "code");
        ScriptEngine engine = ScriptEngine.instance();
        if (engine.snippetSharesSession()) {
            context.getSource().sendFeedback(Component.literal(
                    "py » evaluating in the running script's session (shared globals)"));
        }
        // Output and errors stream back through the default chat sink; the evaluator
        // itself reports failures, so no extra whenComplete handling is needed here.
        engine.evalSnippet(code, ScriptEngine.CHAT);
        return 1;
    }

    /** Toggles {@code /talos script profile}; prints the report when switching off. */
    private static int toggleProfile(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context) {
        var source = context.getSource();
        if (dev.talos.client.script.ScriptProfiler.toggle()) {
            source.sendFeedback(Component.literal(
                    "Script profiling ON — run /talos script profile again for the report"));
            return 1;
        }
        List<String> report = dev.talos.client.script.ScriptProfiler.report();
        source.sendFeedback(Component.literal("Script profiling OFF"
                + (report.isEmpty() ? " — nothing was dispatched while profiling" : ":")));
        for (String line : report) source.sendFeedback(Component.literal("  " + line));
        return 1;
    }

    /**
     * If a running script claimed this built-in via {@code @talos.command("<name>")},
     * forward the raw argument text to it and skip the built-in. Forwarding only QUEUES
     * the invocation (the script worker drains it on the next tick) — the client thread
     * never touches Python. The built-in stays reachable from Python as talos.goto(...)
     * etc., so an override can prepare, then delegate to the original.
     */
    private static boolean scriptOverride(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context, String name) {
        if (!ScriptCommandRegistry.has(name)) return false;
        return ScriptCommandRegistry.dispatch(name, rawArgs(context.getInput(), name));
    }

    /** Everything after the subcommand name in the typed command line, trimmed. */
    private static String rawArgs(String input, String name) {
        int at = input.indexOf(name);
        return at < 0 ? "" : input.substring(at + name.length()).trim();
    }

    /*
     * `/talos follow` takes ONE greedy argument so selectors survive brigadier's word
     * splitting; a trailing bare number is peeled off as the keep-distance
     * (`/talos follow Steve 5` keeps ~5 blocks, `/talos follow @e[type=cow]` defaults).
     */

    private static String followTarget(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context) {
        String[] parts = StringArgumentType.getString(context, "target").trim().split("\\s+");
        int keep = parts.length > 1 && isNumeric(parts[parts.length - 1])
                ? parts.length - 1 : parts.length;
        return String.join(" ", java.util.Arrays.copyOf(parts, keep));
    }

    private static double followDistance(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context,
            double fallback) {
        String[] parts = StringArgumentType.getString(context, "target").trim().split("\\s+");
        return parts.length > 1 && isNumeric(parts[parts.length - 1])
                ? Double.parseDouble(parts[parts.length - 1]) : fallback;
    }

    private static boolean isNumeric(String text) {
        try { Double.parseDouble(text); return true; } catch (NumberFormatException e) { return false; }
    }

    private static List<String> followSuggestions(FabricClientCommandSource source) {
        List<String> names = new java.util.ArrayList<>(List.of("@p", "@a", "@r", "@n", "@e[type="));
        var client = source.getClient();
        if (client.getConnection() != null) {
            for (var entry : client.getConnection().getOnlinePlayers()) {
                String name = entry.getProfile().name();
                if (client.player == null || !name.equals(client.player.getGameProfile().name()))
                    names.add(name);
            }
        }
        return names;
    }

    /** Starts {@code /talos script run <name> [args...]}; argv reaches the script as talos.args. */
    private static int runScript(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context, String argsLine) {
        var source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        List<String> args = argsLine.isBlank() ? List.of() : List.of(argsLine.trim().split("\\s+"));
        ScriptEngine.instance().run(name, args, ScriptEngine.CHAT).whenComplete((ignored, error) ->
                source.getClient().execute(() -> {
                    if (error == null) source.sendFeedback(Component.literal("Script finished: " + name));
                    else source.sendError(Component.literal("Script failed: " + error.getMessage()));
                }));
        source.sendFeedback(Component.literal("Started script: " + name
                + (args.isEmpty() ? "" : " " + String.join(" ", args))));
        return 1;
    }

    /** Executes {@code /talos cmd <name> [args]} against the script command registry. */
    private static int runScriptCommand(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context, String args) {
        String name = StringArgumentType.getString(context, "name");
        if (!ScriptCommandRegistry.dispatch(name, args)) {
            context.getSource().sendError(Component.literal("No script command '" + name
                    + "' is registered — a running script must declare @talos.command(\"" + name + "\")"));
            return 0;
        }
        return 1;
    }

    /**
     * Reference script for {@code /talos example goto}: a pure-Python goto on raw
     * primitives plus a {@code /talos goto} override that wraps the built-in — the
     * essence of {@code custom_goto.py}, updated for talos.tap / talos.release_keys.
     */
    private static final String EXAMPLE_GOTO = """
            # example_goto.py -- a pure-Python goto built on raw primitives, plus a
            # /talos goto override. Reference material: /talos example goto rewrites it.
            #
            # Run:    /talos script run example_goto
            # Then:   /talos pygoto <x> <y> <z>     (from-scratch goto, no pathfinder)
            #         /talos goto xyz <x> <y> <z>   (now routed through goto_override)
            # Stop:   /talos script stop            (handlers unregister automatically)

            import math
            import talos

            ARRIVE = 0.5        # horizontal distance (blocks) that counts as "arrived"
            TURN_STEP = 12.0    # max yaw correction per tick -- small = smooth turning
            STALL_TICKS = 8     # ticks without progress before we tap jump


            def yaw_toward(feet, x, z):
                # Minecraft yaw: 0 = south (+Z), 90 = west (-X) -- hence atan2(-dx, dz).
                return math.degrees(math.atan2(-(x - feet.x), z - feet.z))


            async def naive_goto(x, y, z):
                # Once per tick: measure the offset, ease the yaw toward it, hold forward,
                # and tap jump when the distance stops shrinking (a step or lip ahead).
                tx, tz = x + 0.5, z + 0.5   # aim for the center of the target cell
                best = None                 # closest distance reached so far
                stall = 0                   # ticks since best last improved
                try:
                    while True:
                        feet = talos.player_feet()
                        dx, dz = tx - feet.x, tz - feet.z
                        dist = (dx * dx + dz * dz) ** 0.5
                        if dist <= ARRIVE:
                            talos.log(f"pygoto: arrived ({dist:.2f} blocks from target)")
                            return

                        # Eased steering: shortest signed turn toward the target, clamped.
                        yaw, _pitch = talos.look_angle()
                        delta = (yaw_toward(feet, tx, tz) - yaw + 180.0) % 360.0 - 180.0
                        step = max(-TURN_STEP, min(TURN_STEP, delta))
                        talos.look(yaw + step, 15.0)  # slight downward pitch: watch our feet

                        talos.key("forward")          # HELD until released -- see finally

                        # Progress watchdog: a ~1-block step ahead stops us; tap jump.
                        if best is None or dist < best - 0.05:
                            best, stall = dist, 0
                        else:
                            stall += 1
                            if stall >= STALL_TICKS:
                                stall = 0
                                talos.tap("jump")     # one tick, releases itself

                        await talos.next_tick()       # one control decision per game tick
                finally:
                    talos.release_keys()              # never leave W held down


            @talos.command("pygoto")
            def pygoto(args):
                # /talos pygoto <x> <y> <z> -- returning the coroutine starts it as a task,
                # so chat (and other tasks) stay responsive while it walks.
                if len(args) != 3:
                    talos.log("usage: /talos pygoto <x> <y> <z>")
                    return
                x, y, z = (int(float(a)) for a in args)
                talos.log(f"pygoto: walking to {x} {y} {z} (raw inputs, no pathfinder)")
                return naive_goto(x, y, z)


            @talos.command("goto")
            def goto_override(args):
                # Replaces /talos goto while this script runs. The built-ins stay reachable
                # as talos.goto / talos.aio.goto / talos.aio.goto_block, so the override can
                # prep, then delegate. Handles both forms:
                #   /talos goto xyz <x> <y> <z>
                #   /talos goto block <blockId> [radius]
                async def wrapped_xyz(x, y, z):
                    # Custom prep goes here (speedbridging, scaffolding, logging, ...).
                    result = await talos.aio.goto(x, y, z)  # the ORIGINAL goto, unchanged
                    talos.log(f"goto override: built-in finished -- {result}")

                async def wrapped_block(block_id, radius):
                    result = await talos.aio.goto_block(block_id, radius)
                    talos.log(f"goto override: goto_block finished -- {result}")

                if args and args[0] == "block":
                    radius = int(args[2]) if len(args) > 2 else 64
                    return wrapped_block(args[1], radius)
                if args and args[0] == "xyz":
                    args = args[1:]
                if len(args) != 3:
                    talos.log("this override handles xyz <x> <y> <z> and block <id> [radius]")
                    return
                x, y, z = (int(float(a)) for a in args)
                return wrapped_xyz(x, y, z)


            talos.log("example_goto loaded: /talos goto overridden, /talos pygoto added")
            """;

    /**
     * Reference script for {@code /talos example farm}: a small find → goto → mine
     * harvesting loop with humanized waits and a replanting hook.
     */
    private static final String EXAMPLE_FARM = """
            # example_farm.py -- a tiny farming loop: find a crop, walk to it, harvest
            # it, and wait a humanized moment before scanning again. Reference material:
            # /talos example farm rewrites it.
            #
            # Run:    /talos script run example_farm
            # Stop:   /talos script stop

            import talos

            # Crops to harvest, in preference order. Add "minecraft:carrots" etc. here.
            CROPS = ["minecraft:sugar_cane", "minecraft:wheat"]


            @talos.task
            async def farm():
                while True:
                    target = None
                    for crop in CROPS:
                        target = await talos.aio.find_block(crop, 32)
                        if target:
                            break
                    if target is None:
                        talos.log("farm: nothing to harvest nearby, waiting...")
                        await talos.aio.wait(2.0, 4.0)   # humanized pause between scans
                        continue

                    await talos.aio.goto_near(int(target.x), int(target.y), int(target.z), 2)
                    await talos.aio.mine(target)

                    # Replanting: for wheat, select the hotbar slot holding seeds and
                    # place them back on the farmland, e.g.:
                    #   talos.select_slot(0)                          # slot with seeds
                    #   talos.place_block(target.x, target.y, target.z)
                    # Sugar cane regrows from the stump, so it needs no replant.

                    await talos.aio.wait(0.4, 0.9)       # human-ish pause per harvest
            """;

    /**
     * Reference script for {@code /talos example follow}: overrides {@code /talos follow}
     * with custom behavior layered on the built-in, plus a from-scratch follow loop on
     * raw primitives for full control.
     */
    private static final String EXAMPLE_FOLLOW = """
            # example_follow.py -- customize /talos follow. Reference material:
            # /talos example follow rewrites it.
            #
            # Run:    /talos script run example_follow
            # Then:   /talos follow <name|type|@e[...]> [distance]   (routed through here)
            #         /talos shadow <target>                          (from-scratch loop)
            # Stop:   /talos script stop
            #
            # Targets accept players AND any entity: "Steve", "zombie",
            # "@e[type=cow,distance=..20]", "@p", "@n". The built-in stays reachable as
            # talos.follow / talos.aio.follow.

            import talos


            @talos.command("follow")
            def follow_override(args):
                # Peel a trailing number off as the keep-distance, like the built-in does.
                if not args:
                    talos.log("usage: /talos follow <target> [distance]")
                    return
                distance = 3.0
                if len(args) > 1:
                    try:
                        distance = float(args[-1])
                        args = args[:-1]
                    except ValueError:
                        pass
                target = " ".join(args)

                async def wrapped():
                    talos.log(f"following {target!r}, keeping ~{distance} blocks")
                    # Custom behavior goes here: sprint-only follow, waypoint logging,
                    # auto-eat while following, breaking off when health drops, ...
                    try:
                        await talos.aio.follow(target, distance)  # the ORIGINAL follow
                    except talos.PathFailedError as error:
                        talos.log(f"follow ended: {error}")

                return wrapped()


            @talos.command("shadow")
            def shadow(args):
                # A from-scratch follow on raw primitives: re-goto the target's feet
                # whenever they stray, with everything (pace, distance, pathing options)
                # under your control.
                if not args:
                    talos.log("usage: /talos shadow <target>")
                    return
                target = " ".join(args)

                async def loop():
                    while True:
                        entity = talos.find_entity(target, 64) if ":" in target \\
                            else next((p for p in talos.players()
                                       if p.name.lower() == target.lower()), None)
                        if entity is None:
                            talos.log("shadow: target not in range, waiting...")
                            await talos.aio.wait(1.0, 2.0)
                            continue
                        if entity.distance > 4.0:
                            await talos.aio.goto_near(int(entity.pos.x()), int(entity.pos.y),
                                                      int(entity.pos.z()), 2)
                        await talos.aio.wait(0.3, 0.6)

                return loop()


            talos.log("example_follow loaded: /talos follow overridden, /talos shadow added")
            """;

    /** Embedded reference scripts served by {@code /talos example <name>}. */
    private static final Map<String, String> EXAMPLES =
            Map.of("goto", EXAMPLE_GOTO, "farm", EXAMPLE_FARM, "follow", EXAMPLE_FOLLOW);

    /**
     * Writes the named reference script to {@code <gameDir>/talos/scripts/example_<name>.py}
     * (the directory {@link ScriptEngine#run} loads from), overwriting any previous copy —
     * examples are reference material, not user state.
     */
    private static int writeExample(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        String body = EXAMPLES.get(name);
        if (body == null) {
            context.getSource().sendError(Component.literal("No example '" + name + "' — available: "
                    + String.join(", ", EXAMPLES.keySet().stream().sorted().toList())));
            return 0;
        }
        Path scripts = FabricLoader.getInstance().getGameDir().resolve("talos").resolve("scripts");
        Path file = scripts.resolve("example_" + name + ".py");
        try {
            Files.createDirectories(scripts);
            Files.writeString(file, body);
        } catch (IOException error) {
            context.getSource().sendError(Component.literal("Could not write example: " + error.getMessage()));
            return 0;
        }
        context.getSource().sendFeedback(Component.literal("Wrote talos/scripts/example_" + name
                + ".py — run it with /talos script run example_" + name));
        return 1;
    }

    /**
     * Builds the {@code /talos look} subtree:
     * <ul>
     *   <li>{@code /talos look <yaw> <pitch>} — absolute or {@code ^}-relative angles (existing behavior).</li>
     *   <li>{@code /talos look block <blockId> [n]} — aim at the Nth-closest matching block.</li>
     *   <li>{@code /talos look coords <x> <y> <z>} — aim at the center of an explicit block position
     *       ({@code ~}-relative coordinates supported).</li>
     *   <li>{@code /talos look direction <yaw> <pitch>} — aim at the block hit by a raycast along a
     *       ({@code ^}-relative) direction.</li>
     *   <li>{@code /talos look entity type <entityTypeId> [tag <tag>] [n]} — aim at the Nth-closest
     *       entity of a given type, optionally also filtered by scoreboard tag.</li>
     *   <li>{@code /talos look entity tag <tag> [n]} — aim at the Nth-closest entity with a tag,
     *       regardless of type.</li>
     *   <li>{@code /talos look <selector> [n]} — Minecraft-style target selector: {@code @e[...]},
     *       {@code @a}, {@code @p} (nearest player excluding yourself) or {@code @s} (self). See
     *       {@link EntitySelector} for the supported bracket arguments.</li>
     * </ul>
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> lookNode(CommandBuildContext registryAccess) {
        return ClientCommands.literal("look")
                .then(ClientCommands.argument("yaw", RelativeAngleArgumentType.angle())
                        .then(ClientCommands.argument("pitch", RelativeAngleArgumentType.angle())
                                .executes(LookCommand::execute)))
                .then(ClientCommands.literal("block")
                        .then(ClientCommands.argument(
                                        "blockPredicate", BlockStatePredicate.argument(registryAccess))
                                .executes(context -> LookCommand.executeBlock(context, 0))
                                .then(indexArgument(n -> LookCommand.executeBlock(n.context(), n.n())))))
                .then(ClientCommands.literal("coords")
                        .then(coordinates((context, pos) -> LookCommand.executeCoords(context, pos))))
                .then(ClientCommands.literal("direction")
                        .then(directionNode((context, pos) -> LookCommand.executeDirection(context, pos))))
                .then(ClientCommands.argument("selector", SelectorArgumentType.selector())
                        .executes(context -> LookCommand.executeSelector(context, selectorArg(context), 0))
                        .then(indexArgument(n -> LookCommand.executeSelector(
                                n.context(), selectorArg(n.context()), n.n()))))
                .then(ClientCommands.literal("entity")
                        .then(ClientCommands.literal("type")
                                .then(ClientCommands.argument("entityType", IdentifierArgument.id())
                                        .executes(context -> LookCommand.executeEntity(context, entityTypeArg(context), null, 0))
                                        .then(indexArgument(n -> LookCommand.executeEntity(
                                                n.context(), entityTypeArg(n.context()), null, n.n())))
                                        .then(ClientCommands.literal("tag")
                                                .then(ClientCommands.argument("tag", StringArgumentType.word())
                                                        .executes(context -> LookCommand.executeEntity(
                                                                context, entityTypeArg(context), tagArg(context), 0))
                                                        .then(indexArgument(n -> LookCommand.executeEntity(
                                                                n.context(), entityTypeArg(n.context()), tagArg(n.context()), n.n())))))))
                        .then(ClientCommands.literal("tag")
                                .then(ClientCommands.argument("tag", StringArgumentType.word())
                                        .executes(context -> LookCommand.executeEntity(context, null, tagArg(context), 0))
                                        .then(indexArgument(n -> LookCommand.executeEntity(
                                                n.context(), null, tagArg(n.context()), n.n()))))));
    }

    private record NArg(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context, int n) {
    }

    @FunctionalInterface
    private interface NArgExecutor {
        int execute(NArg n) throws com.mojang.brigadier.exceptions.CommandSyntaxException;
    }

    /** Builds the trailing optional {@code n} (rank, default 1) argument node for {@code /talos look} selectors. */
    /** 0-based, negative-friendly source index: 0 = nearest, -1 = furthest. */
    private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> indexArgument(
            NArgExecutor executor) {
        return ClientCommands.argument("index", IntegerArgumentType.integer())
                .executes(context -> executor.execute(new NArg(context, value(context, "index"))));
    }

    private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> nArgument(
            NArgExecutor executor) {
        return ClientCommands.argument("n", IntegerArgumentType.integer(1))
                .executes(context -> executor.execute(new NArg(context, value(context, "n"))));
    }

    private static Identifier entityTypeArg(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context) {
        return context.getArgument("entityType", Identifier.class);
    }

    private static String tagArg(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context) {
        return StringArgumentType.getString(context, "tag");
    }

    private static String selectorArg(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context) {
        return context.getArgument("selector", String.class);
    }

    /**
     * Builds the {@code direction <yaw> <pitch>} sub-node shared by {@code /talos mine} and
     * {@code /talos coords}: {@code ^}/{@code ^n}-relative angles (via
     * {@link RelativeAngleArgumentType}) that raycast from the player's eyes to a {@link
     * net.minecraft.core.BlockPos} via {@link DirectionRaycast}.
     */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource,
            RelativeAngleArgumentType.Angle> directionNode(DirectionExecutor executor) {
        return ClientCommands.argument("yaw", RelativeAngleArgumentType.angle())
                .then(ClientCommands.argument("pitch", RelativeAngleArgumentType.angle())
                        .executes(context -> {
                            var source = context.getSource();
                            var player = source.getPlayer();
                            float yaw = RelativeAngleArgumentType.resolve(context, "yaw", player.getYRot());
                            float pitch = RelativeAngleArgumentType.resolve(context, "pitch", player.getXRot());
                            var pos = DirectionRaycast.blockAt(player, yaw, pitch);
                            if (pos == null) {
                                source.sendError(Component.literal("No block in range along yaw %.2f, pitch %.2f"
                                        .formatted(yaw, pitch)));
                                return 0;
                            }
                            return executor.execute(context, pos);
                        }));
    }

    @FunctionalInterface
    private interface DirectionExecutor {
        int execute(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context,
                    net.minecraft.core.BlockPos pos);
    }

    private static GoalXZ xzGoal(
            com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context) {
        Vec3 eye = eyePos(context);
        return new GoalXZ(coordValue(context, "x", eye.x), coordValue(context, "z", eye.z));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource,
            RelativeCoordinateArgumentType.Coordinate> coordinates(
                    CoordinateExecutor executor) {
        return coordinate("x").then(coordinate("y").then(coordinate("z").executes(context ->
                executor.execute(context, blockPos(context)))));
    }

    @FunctionalInterface
    private interface CoordinateExecutor {
        int execute(com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context,
                    net.minecraft.core.BlockPos pos);
    }

    /**
     * Builds a coordinate argument node ({@code x}, {@code y} or {@code z}) that accepts either an
     * absolute number or Minecraft-style {@code ~}-relative syntax (e.g. {@code ~}, {@code ~5}, {@code ~-3}).
     */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource,
            RelativeCoordinateArgumentType.Coordinate> coordinate(String name) {
        return ClientCommands.argument(name, RelativeCoordinateArgumentType.coordinate());
    }

    /** Default look-ray reach for {@code /talos raytrace where|if} when no distance is given. */
    private static final double DEFAULT_RAY_DIST = 64.0;

    /** One caret-capable axis ({@code ^}/{@code ~}/absolute) for {@code /talos raytrace get}. */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource,
            LocalCoordinateArgumentType.Axis> localCoord(String name) {
        return ClientCommands.argument(name, LocalCoordinateArgumentType.localCoordinate());
    }

    /**
     * {@code /talos raytrace} — look-relative coordinate + raycast queries (see
     * {@link RaytraceCommand}). Bare {@code raytrace <x> <y> <z>} is shorthand for {@code get}.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource>
            raytraceNode() {
        com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> raytrace =
                ClientCommands.literal("raytrace");

        // get / bare form: resolve a coordinate triple to a world point. Bare and plain
        // `get` report the exact point (advanced); the `simple`/`advanced` mode literals
        // choose between the floored block cell (ints) and the exact 3dp point.
        raytrace.then(localCoord("x").then(localCoord("y").then(localCoord("z")
                .executes(context -> RaytraceCommand.get(context, false)))));
        raytrace.then(ClientCommands.literal("get")
                .then(localCoord("x").then(localCoord("y").then(localCoord("z")
                        .executes(context -> RaytraceCommand.get(context, false))))));
        raytrace.then(ClientCommands.literal("simple")
                .then(ClientCommands.literal("get")
                        .then(localCoord("x").then(localCoord("y").then(localCoord("z")
                                .executes(context -> RaytraceCommand.get(context, true)))))));
        raytrace.then(ClientCommands.literal("advanced")
                .then(ClientCommands.literal("get")
                        .then(localCoord("x").then(localCoord("y").then(localCoord("z")
                                .executes(context -> RaytraceCommand.get(context, false)))))));

        // where [maxDist]: first block/entity hit along the look.
        raytrace.then(ClientCommands.literal("where")
                .executes(context -> RaytraceCommand.where(context, DEFAULT_RAY_DIST))
                .then(ClientCommands.argument("maxDist",
                                DoubleArgumentType.doubleArg(0.1, 256.0))
                        .executes(context -> RaytraceCommand.where(context,
                                DoubleArgumentType.getDouble(context, "maxDist")))));

        // if block <id> [maxDist] | if entity <selector> [maxDist]: predicate on the first hit.
        raytrace.then(ClientCommands.literal("if")
                .then(ClientCommands.literal("block")
                        .then(ClientCommands.argument("blockId", IdArgumentType.blockId())
                                .executes(context -> RaytraceCommand.ifBlock(context,
                                        StringArgumentType.getString(context, "blockId"), DEFAULT_RAY_DIST))
                                .then(ClientCommands.argument("maxDist",
                                                DoubleArgumentType.doubleArg(0.1, 256.0))
                                        .executes(context -> RaytraceCommand.ifBlock(context,
                                                StringArgumentType.getString(context, "blockId"),
                                                DoubleArgumentType.getDouble(context, "maxDist"))))))
                .then(ClientCommands.literal("entity")
                        .then(ClientCommands.argument("selector", SelectorArgumentType.selector())
                                .executes(context -> RaytraceCommand.ifEntity(context,
                                        selectorArg(context), DEFAULT_RAY_DIST))
                                .then(ClientCommands.argument("maxDist",
                                                DoubleArgumentType.doubleArg(0.1, 256.0))
                                        .executes(context -> RaytraceCommand.ifEntity(context,
                                                selectorArg(context),
                                                DoubleArgumentType.getDouble(context, "maxDist")))))));
        return raytrace;
    }

    /** The player's current eye position, used as the base for {@code ~}-relative coordinate args. */
    private static Vec3 eyePos(
            com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context) {
        return context.getSource().getPlayer().getEyePosition();
    }

    private static net.minecraft.core.BlockPos blockPos(
            com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context) {
        Vec3 eye = eyePos(context);
        return new net.minecraft.core.BlockPos(
                coordValue(context, "x", eye.x),
                coordValue(context, "y", eye.y),
                coordValue(context, "z", eye.z));
    }

    /** Resolves a coordinate argument (absolute or {@code ~}-relative) to a block coordinate. */
    private static int coordValue(
            com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context,
            String name, double base) {
        return RelativeCoordinateArgumentType.resolveBlock(context, name, base);
    }

    private static int value(
            com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context,
            String name) {
        return IntegerArgumentType.getInteger(context, name);
    }
}
