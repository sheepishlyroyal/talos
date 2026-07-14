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
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;

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
                return CommandSource.suggestMatching(names, builder);
            };

    /** Tab-completes {@code /talos cmd <name>} with the currently registered script commands. */
    private static final SuggestionProvider<FabricClientCommandSource> SCRIPT_COMMAND_NAMES =
            (context, builder) -> CommandSource.suggestMatching(ScriptCommandRegistry.names(), builder);

    /** Tab-completes {@code /talos example <name>} with the embedded reference scripts. */
    private static final SuggestionProvider<FabricClientCommandSource> EXAMPLE_NAMES =
            (context, builder) -> CommandSource.suggestMatching(TalosCommands.EXAMPLES.keySet(), builder);

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(TalosCommands::registerCommands);
    }

    private static void registerCommands(
            com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> dispatcher,
            CommandRegistryAccess registryAccess) {
        var talosRoot = dispatcher.register(ClientCommandManager.literal("talos")
                .then(ClientCommandManager.literal("find")
                        .then(ClientCommandManager.literal("block")
                                .then(ClientCommandManager.argument(
                                                "blockPredicate", BlockStatePredicate.argument(registryAccess))
                                        .executes(context -> FindCommand.execute(
                                                context,
                                                context.getSource().getClient().options.getViewDistance().getValue()))
                                        .then(ClientCommandManager.argument(
                                                        "radius", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> FindCommand.execute(
                                                        context,
                                                        IntegerArgumentType.getInteger(context, "radius")))))))
                .then(ClientCommandManager.literal("goto")
                        .then(ClientCommandManager.literal("near")
                                .then(coordinate("x")
                                        .then(coordinate("y")
                                                .then(coordinate("z")
                                                        .then(ClientCommandManager.argument(
                                                                        "range", IntegerArgumentType.integer(0))
                                                                .executes(context -> scriptOverride(context, "goto") ? 1
                                                                        : GotoCommand.execute(
                                                                        context,
                                                                        new GoalNear(
                                                                                coordValue(context, "x", eyePos(context).x),
                                                                                coordValue(context, "y", eyePos(context).y),
                                                                                coordValue(context, "z", eyePos(context).z),
                                                                                value(context, "range")))))))))
                        // `/talos goto <x> <z>` — pathfind to X,Z at the player's current Y.
                        // `/talos goto <x> <y> <z>` — pathfind to an exact block. These are two
                        // distinct sibling argument nodes under "x" (named "z" and "y"
                        // respectively) so the 2nd token's meaning is unambiguous per branch —
                        // chaining a shared node here would silently swap Y and Z.
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
                                                                coordValue(context, "z", eyePos(context).z))))))))
                // Back-compat alias: `/talos xz <x> <z>` delegates to the same XZ handler as
                // `/talos goto <x> <z>`.
                .then(ClientCommandManager.literal("xz")
                        .then(coordinate("x")
                                .then(coordinate("z")
                                        .executes(context -> GotoCommand.execute(
                                                context, xzGoal(context))))))
                .then(lookNode(registryAccess))
                .then(ClientCommandManager.literal("glow")
                        .then(coordinate("x")
                                .then(coordinate("y")
                                        .then(coordinate("z")
                                                .executes(context -> GlowCommand.execute(
                                                        context, blockPos(context), GlowCommand.DEFAULT_SECONDS))
                                                .then(ClientCommandManager.argument(
                                                                "seconds", IntegerArgumentType.integer(1, 3600))
                                                        .executes(context -> GlowCommand.execute(
                                                                context,
                                                                blockPos(context),
                                                                value(context, "seconds"))))))))
                .then(ClientCommandManager.literal("mine")
                        .then(coordinates((context, pos) -> scriptOverride(context, "mine") ? 1
                                : ActionCommand.mine(context, pos)))
                        .then(ClientCommandManager.literal("direction")
                                .then(directionNode((context, pos) -> scriptOverride(context, "mine") ? 1
                                        : ActionCommand.mine(context, pos))))
                        .then(ClientCommandManager.literal("block")
                                .then(ClientCommandManager.argument(
                                                "blockPredicate", BlockStatePredicate.argument(registryAccess))
                                        .executes(context -> scriptOverride(context, "mine") ? 1
                                                : ActionCommand.mineBlock(context, 0))
                                        .then(indexArgument(n -> scriptOverride(n.context(), "mine") ? 1
                                                : ActionCommand.mineBlock(n.context(), n.n()))))))
                .then(ClientCommandManager.literal("place")
                        .then(coordinates((context, pos) -> scriptOverride(context, "place") ? 1
                                : ActionCommand.place(context, pos))))
                .then(ClientCommandManager.literal("coords")
                        .then(ClientCommandManager.literal("direction")
                                .then(directionNode(CoordsCommand::executeDirection))))
                .then(ClientCommandManager.literal("ui")
                        .executes(UiCommand::execute))
                .then(ClientCommandManager.literal("script")
                        .then(ClientCommandManager.literal("run")
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .suggests(SCRIPT_NAMES)
                                        .executes(context -> {
                                            var source = context.getSource();
                                            String name = StringArgumentType.getString(context, "name");
                                            ScriptEngine.instance().run(name).whenComplete((ignored, error) ->
                                                    source.getClient().execute(() -> {
                                                        if (error == null) source.sendFeedback(Text.literal("Script finished: " + name));
                                                        else source.sendError(Text.literal("Script failed: " + error.getMessage()));
                                                    }));
                                            source.sendFeedback(Text.literal("Started script: " + name));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("stop")
                                .executes(context -> {
                                    ScriptEngine.instance().stop();
                                    context.getSource().sendFeedback(Text.literal("Stopped script engine"));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("bridge")
                        .then(ClientCommandManager.literal("allow")
                                .executes(context -> {
                                    int result = TalosBridge.allowSession();
                                    if (result == 0) context.getSource().sendError(Text.literal("Talos bridge is not running"));
                                    else context.getSource().sendFeedback(Text.literal("VS Code bridge allowed for this session"));
                                    return result;
                                }))
                        .then(ClientCommandManager.literal("status")
                                .executes(context -> {
                                    context.getSource().sendFeedback(Text.literal(TalosBridge.statusText()));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("stop")
                        .executes(StopCommand::execute)
                        .then(ClientCommandManager.literal("all")
                                .executes(StopCommand::execute)))
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
                .then(ClientCommandManager.literal("get")
                        .then(ClientCommandManager.literal("block")
                                .then(coordinates(GetCommand::blockAtPos))
                                .then(ClientCommandManager.literal("direction")
                                        .then(directionNode(GetCommand::blockAtPos)))))
                .then(TrackCommand.node())
                .then(ClientCommandManager.literal("kill")
                        .then(ClientCommandManager.literal("nearest")
                                .executes(context -> scriptOverride(context, "kill") ? 1
                                        : ActionCommand.killNearest(context, 6.0))
                                .then(ClientCommandManager.argument(
                                                "radius", DoubleArgumentType.doubleArg(1.0, 64.0))
                                        .executes(context -> scriptOverride(context, "kill") ? 1
                                                : ActionCommand.killNearest(context,
                                                DoubleArgumentType.getDouble(context, "radius"))))))
                // `/talos cmd <name> [args]` — invoke a script-registered command whose name
                // doesn't collide with (or shadow) a built-in subcommand's argument shape.
                .then(ClientCommandManager.literal("cmd")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .suggests(SCRIPT_COMMAND_NAMES)
                                .executes(context -> runScriptCommand(context, ""))
                                .then(ClientCommandManager.argument("args", StringArgumentType.greedyString())
                                        .executes(context -> runScriptCommand(
                                                context, StringArgumentType.getString(context, "args"))))))
                // `/talos example <name>` — write a commented reference script to
                // talos/scripts/example_<name>.py (overwriting: they are reference material).
                .then(ClientCommandManager.literal("example")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .suggests(EXAMPLE_NAMES)
                                .executes(TalosCommands::writeExample))));
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

    /** Executes {@code /talos cmd <name> [args]} against the script command registry. */
    private static int runScriptCommand(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context, String args) {
        String name = StringArgumentType.getString(context, "name");
        if (!ScriptCommandRegistry.dispatch(name, args)) {
            context.getSource().sendError(Text.literal("No script command '" + name
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
            #         /talos goto <x> <y> <z>       (now routed through goto_override)
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
                # Replaces /talos goto while this script runs. The built-in stays reachable
                # as talos.goto / talos.aio.goto, so the override can prep, then delegate.
                if len(args) != 3:
                    talos.log("this override only handles /talos goto <x> <y> <z>")
                    return
                x, y, z = (int(float(a)) for a in args)

                async def wrapped():
                    # Custom prep goes here (speedbridging, scaffolding, logging, ...).
                    result = await talos.aio.goto(x, y, z)  # the ORIGINAL goto, unchanged
                    talos.log(f"goto override: built-in finished -- {result}")

                return wrapped()


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

    /** Embedded reference scripts served by {@code /talos example <name>}. */
    private static final Map<String, String> EXAMPLES =
            Map.of("goto", EXAMPLE_GOTO, "farm", EXAMPLE_FARM);

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
            context.getSource().sendError(Text.literal("No example '" + name + "' — available: "
                    + String.join(", ", EXAMPLES.keySet().stream().sorted().toList())));
            return 0;
        }
        Path scripts = FabricLoader.getInstance().getGameDir().resolve("talos").resolve("scripts");
        Path file = scripts.resolve("example_" + name + ".py");
        try {
            Files.createDirectories(scripts);
            Files.writeString(file, body);
        } catch (IOException error) {
            context.getSource().sendError(Text.literal("Could not write example: " + error.getMessage()));
            return 0;
        }
        context.getSource().sendFeedback(Text.literal("Wrote talos/scripts/example_" + name
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
    private static LiteralArgumentBuilder<FabricClientCommandSource> lookNode(CommandRegistryAccess registryAccess) {
        return ClientCommandManager.literal("look")
                .then(ClientCommandManager.argument("yaw", RelativeAngleArgumentType.angle())
                        .then(ClientCommandManager.argument("pitch", RelativeAngleArgumentType.angle())
                                .executes(LookCommand::execute)))
                .then(ClientCommandManager.literal("block")
                        .then(ClientCommandManager.argument(
                                        "blockPredicate", BlockStatePredicate.argument(registryAccess))
                                .executes(context -> LookCommand.executeBlock(context, 0))
                                .then(indexArgument(n -> LookCommand.executeBlock(n.context(), n.n())))))
                .then(ClientCommandManager.literal("coords")
                        .then(coordinates((context, pos) -> LookCommand.executeCoords(context, pos))))
                .then(ClientCommandManager.literal("direction")
                        .then(directionNode((context, pos) -> LookCommand.executeDirection(context, pos))))
                .then(ClientCommandManager.argument("selector", SelectorArgumentType.selector())
                        .executes(context -> LookCommand.executeSelector(context, selectorArg(context), 0))
                        .then(indexArgument(n -> LookCommand.executeSelector(
                                n.context(), selectorArg(n.context()), n.n()))))
                .then(ClientCommandManager.literal("entity")
                        .then(ClientCommandManager.literal("type")
                                .then(ClientCommandManager.argument("entityType", IdentifierArgumentType.identifier())
                                        .executes(context -> LookCommand.executeEntity(context, entityTypeArg(context), null, 0))
                                        .then(indexArgument(n -> LookCommand.executeEntity(
                                                n.context(), entityTypeArg(n.context()), null, n.n())))
                                        .then(ClientCommandManager.literal("tag")
                                                .then(ClientCommandManager.argument("tag", StringArgumentType.word())
                                                        .executes(context -> LookCommand.executeEntity(
                                                                context, entityTypeArg(context), tagArg(context), 0))
                                                        .then(indexArgument(n -> LookCommand.executeEntity(
                                                                n.context(), entityTypeArg(n.context()), tagArg(n.context()), n.n())))))))
                        .then(ClientCommandManager.literal("tag")
                                .then(ClientCommandManager.argument("tag", StringArgumentType.word())
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
        return ClientCommandManager.argument("index", IntegerArgumentType.integer())
                .executes(context -> executor.execute(new NArg(context, value(context, "index"))));
    }

    private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> nArgument(
            NArgExecutor executor) {
        return ClientCommandManager.argument("n", IntegerArgumentType.integer(1))
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
     * net.minecraft.util.math.BlockPos} via {@link DirectionRaycast}.
     */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource,
            RelativeAngleArgumentType.Angle> directionNode(DirectionExecutor executor) {
        return ClientCommandManager.argument("yaw", RelativeAngleArgumentType.angle())
                .then(ClientCommandManager.argument("pitch", RelativeAngleArgumentType.angle())
                        .executes(context -> {
                            var source = context.getSource();
                            var player = source.getPlayer();
                            float yaw = RelativeAngleArgumentType.resolve(context, "yaw", player.getYaw());
                            float pitch = RelativeAngleArgumentType.resolve(context, "pitch", player.getPitch());
                            var pos = DirectionRaycast.blockAt(player, yaw, pitch);
                            if (pos == null) {
                                source.sendError(Text.literal("No block in range along yaw %.2f, pitch %.2f"
                                        .formatted(yaw, pitch)));
                                return 0;
                            }
                            return executor.execute(context, pos);
                        }));
    }

    @FunctionalInterface
    private interface DirectionExecutor {
        int execute(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context,
                    net.minecraft.util.math.BlockPos pos);
    }

    private static GoalXZ xzGoal(
            com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context) {
        Vec3d eye = eyePos(context);
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
                    net.minecraft.util.math.BlockPos pos);
    }

    /**
     * Builds a coordinate argument node ({@code x}, {@code y} or {@code z}) that accepts either an
     * absolute number or Minecraft-style {@code ~}-relative syntax (e.g. {@code ~}, {@code ~5}, {@code ~-3}).
     */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource,
            RelativeCoordinateArgumentType.Coordinate> coordinate(String name) {
        return ClientCommandManager.argument(name, RelativeCoordinateArgumentType.coordinate());
    }

    /** The player's current eye position, used as the base for {@code ~}-relative coordinate args. */
    private static Vec3d eyePos(
            com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context) {
        return context.getSource().getPlayer().getEyePos();
    }

    private static net.minecraft.util.math.BlockPos blockPos(
            com.mojang.brigadier.context.CommandContext<
                            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context) {
        Vec3d eye = eyePos(context);
        return new net.minecraft.util.math.BlockPos(
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
