package dev.glade.client.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.glade.client.GladeClient;
import dev.glade.client.task.GladeTask;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * Direct input automation: {@code /glade walk} and {@code /glade key}. Everything presses the
 * player's logical {@link KeyBinding}s (never raw key codes), so rebound controls keep working —
 * the same contract the pathing followers already honor.
 */
public final class InputCommand {
    private static final String[] KEY_NAMES = {
            "w", "a", "s", "d", "jump", "sneak", "sprint", "attack", "use",
            "drop", "swap", "inventory", "1", "2", "3", "4", "5", "6", "7", "8", "9"
    };

    private InputCommand() {}

    public static LiteralArgumentBuilder<FabricClientCommandSource> walkNode() {
        LiteralArgumentBuilder<FabricClientCommandSource> walk = ClientCommandManager.literal("walk");
        for (String direction : new String[] {"w", "a", "s", "d"}) {
            walk.then(ClientCommandManager.literal(direction)
                    .then(ClientCommandManager.literal("tap")
                            .executes(context -> startKeyTask(context.getSource(), direction, 1)))
                    .then(ClientCommandManager.literal("hold")
                            .then(ClientCommandManager.argument("seconds", DoubleArgumentType.doubleArg(0.05, 600.0))
                                    .executes(context -> startKeyTask(context.getSource(), direction,
                                            (int) Math.ceil(DoubleArgumentType.getDouble(context, "seconds") * 20.0)))))
                    .then(ClientCommandManager.literal("blocks")
                            .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 1000))
                                    .executes(context -> startWalkBlocks(context.getSource(), direction,
                                            IntegerArgumentType.getInteger(context, "count"), WalkTask.Align.CENTER))
                                    .then(ClientCommandManager.literal("center")
                                            .executes(context -> startWalkBlocks(context.getSource(), direction,
                                                    IntegerArgumentType.getInteger(context, "count"), WalkTask.Align.CENTER)))
                                    .then(ClientCommandManager.literal("touch")
                                            .executes(context -> startWalkBlocks(context.getSource(), direction,
                                                    IntegerArgumentType.getInteger(context, "count"), WalkTask.Align.TOUCH))))));
        }
        return walk;
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> keyNode() {
        LiteralArgumentBuilder<FabricClientCommandSource> key = ClientCommandManager.literal("key");
        key.then(ClientCommandManager.literal("list").executes(context -> {
            context.getSource().sendFeedback(Text.literal(
                    "Keys: " + String.join(", ", KEY_NAMES) + " (w/a/s/d aliases: forward/left/back/right)"));
            return 1;
        }));
        for (String name : KEY_NAMES) {
            key.then(ClientCommandManager.literal(name)
                    .then(ClientCommandManager.literal("tap")
                            .executes(context -> startKeyTask(context.getSource(), name, 1)))
                    .then(ClientCommandManager.literal("hold")
                            .then(ClientCommandManager.argument("seconds", DoubleArgumentType.doubleArg(0.05, 600.0))
                                    .executes(context -> startKeyTask(context.getSource(), name,
                                            (int) Math.ceil(DoubleArgumentType.getDouble(context, "seconds") * 20.0))))));
        }
        return key;
    }

    private static int startKeyTask(FabricClientCommandSource source, String name, int ticks) {
        MinecraftClient client = source.getClient();
        KeyBinding binding = resolve(client.options, name);
        if (binding == null) {
            source.sendError(Text.literal("Unknown key: " + name));
            return 0;
        }
        try {
            GladeClient.taskScheduler().addTask("glade-key-" + name, new KeyHoldTask(client, binding, name, ticks));
        } catch (RuntimeException exception) {
            source.sendError(Text.literal(exception.getMessage()));
            return 0;
        }
        source.sendFeedback(Text.literal(ticks <= 1
                ? "Tapped " + name
                : "Holding " + name + " for " + String.format(Locale.ROOT, "%.2f", ticks / 20.0) + "s"));
        return 1;
    }

    private static int startWalkBlocks(FabricClientCommandSource source, String direction, int blocks,
            WalkTask.Align align) {
        MinecraftClient client = source.getClient();
        if (client.player == null) {
            source.sendError(Text.literal("No player is loaded"));
            return 0;
        }
        KeyBinding binding = resolve(client.options, direction);
        try {
            GladeClient.taskScheduler().addTask("glade-walk",
                    new WalkTask(client, binding, direction, blocks, align));
        } catch (RuntimeException exception) {
            source.sendError(Text.literal(exception.getMessage()));
            return 0;
        }
        source.sendFeedback(Text.literal("Walking " + direction.toUpperCase(Locale.ROOT) + " for "
                + blocks + " block" + (blocks == 1 ? "" : "s") + " (" + align.name().toLowerCase(Locale.ROOT) + ")"));
        return 1;
    }

    /** Resolves a friendly name to the player's live binding; remaps are honored implicitly. */
    static KeyBinding resolve(GameOptions options, String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "w", "forward" -> options.forwardKey;
            case "s", "back" -> options.backKey;
            case "a", "left" -> options.leftKey;
            case "d", "right" -> options.rightKey;
            case "jump", "space" -> options.jumpKey;
            case "sneak", "shift" -> options.sneakKey;
            case "sprint", "ctrl" -> options.sprintKey;
            case "attack" -> options.attackKey;
            case "use" -> options.useKey;
            case "drop" -> options.dropKey;
            case "swap", "offhand" -> options.swapHandsKey;
            case "inventory" -> options.inventoryKey;
            case "1", "2", "3", "4", "5", "6", "7", "8", "9" ->
                    options.hotbarKeys[Integer.parseInt(name) - 1];
            default -> null;
        };
    }

    private static boolean movementKey(GameOptions options, KeyBinding binding) {
        return binding == options.forwardKey || binding == options.backKey
                || binding == options.leftKey || binding == options.rightKey
                || binding == options.jumpKey || binding == options.sneakKey
                || binding == options.sprintKey;
    }

    /** Presses one binding for a fixed number of ticks. A 1-tick hold is a tap. */
    private static final class KeyHoldTask extends GladeTask {
        private final MinecraftClient client;
        private final KeyBinding binding;
        private final String name;
        private final int holdTicks;
        private int ticks;
        private boolean done;

        KeyHoldTask(MinecraftClient client, KeyBinding binding, String name, int holdTicks) {
            this.client = client;
            this.binding = binding;
            this.name = name;
            this.holdTicks = holdTicks;
        }

        @Override public void initialize() {
            // Registering an actual press event (not just held state) makes single taps of
            // attack/use/drop/hotbar behave exactly like a physical key stroke.
            KeyBinding.onKeyPressed(KeyBindingHelper.getBoundKeyOf(binding));
        }
        @Override public boolean condition() { return !done && client.player != null; }
        @Override public void increment() { ticks++; }
        @Override public void body() {
            if (ticks >= holdTicks) {
                binding.setPressed(false);
                done = true;
                return;
            }
            binding.setPressed(true);
            scheduleDelay();
        }
        @Override public void onCompleted() { binding.setPressed(false); }
        @Override public Set<Object> getMutexKeys() {
            return movementKey(client.options, binding)
                    ? Set.of("glade-player-movement") : Set.of("glade-key-" + name);
        }
    }

    /**
     * Walks a WASD direction a fixed number of blocks with momentum-aware braking.
     * CENTER stops the player on the destination block's center; TOUCH stops as soon as the
     * hitbox (radius 0.3) touches the destination block's near face.
     */
    private static final class WalkTask extends GladeTask {
        enum Align { CENTER, TOUCH }

        private static final double GROUND_DRAG = 0.6 * 0.91;
        private static final int STALL_TICKS = 40;
        private static final int MAX_TICKS = 20 * 120;

        private final MinecraftClient client;
        private final KeyBinding binding;
        private final String directionName;
        private final int blocks;
        private final Align align;
        private Vec3d start;
        private Vec3d direction;
        private double targetDistance;
        private double bestDisplacement;
        private int lastProgressTick;
        private int ticks;
        private boolean released;
        private boolean done;

        WalkTask(MinecraftClient client, KeyBinding binding, String directionName, int blocks, Align align) {
            this.client = client;
            this.binding = binding;
            this.directionName = directionName;
            this.blocks = blocks;
            this.align = align;
        }

        @Override public void initialize() {
            var player = client.player;
            start = new Vec3d(player.getX(), player.getY(), player.getZ());
            double radians = Math.toRadians(player.getYaw());
            Vec3d forward = new Vec3d(-Math.sin(radians), 0.0, Math.cos(radians));
            direction = switch (directionName) {
                case "s" -> forward.multiply(-1.0);
                case "a" -> new Vec3d(forward.z, 0.0, -forward.x);
                case "d" -> new Vec3d(-forward.z, 0.0, forward.x);
                default -> forward;
            };
            if (align == Align.CENTER) {
                // Destination is the center of the block column n blocks along the walk line.
                Vec3d raw = start.add(direction.multiply(blocks));
                Vec3d snapped = new Vec3d(Math.floor(raw.x) + 0.5, raw.y, Math.floor(raw.z) + 0.5);
                targetDistance = project(snapped.subtract(start));
            } else {
                // Stop when the hitbox touches the near face of the destination block.
                Vec3d raw = start.add(direction.multiply(blocks));
                Vec3d center = new Vec3d(Math.floor(raw.x) + 0.5, raw.y, Math.floor(raw.z) + 0.5);
                Vec3d face = center.subtract(direction.multiply(0.5));
                targetDistance = project(face.subtract(start)) - 0.3;
            }
            targetDistance = Math.max(0.0, targetDistance);
        }

        @Override public boolean condition() { return !done; }
        @Override public void increment() { ticks++; }

        @Override public void body() {
            var player = client.player;
            if (player == null || client.world == null) { finish("world unloaded"); return; }
            Vec3d position = new Vec3d(player.getX(), player.getY(), player.getZ());
            double displacement = project(position.subtract(start));
            double speed = project(player.getVelocity());
            double remaining = targetDistance - displacement;

            if (displacement > bestDisplacement + 0.01) {
                bestDisplacement = displacement;
                lastProgressTick = ticks;
            }
            if (remaining <= 0.05 || (released && Math.abs(speed) < 0.005)) {
                finish(String.format(Locale.ROOT, "walked %.2f blocks", displacement));
                return;
            }
            if (ticks - lastProgressTick > STALL_TICKS) { finish("blocked"); return; }
            if (ticks > MAX_TICKS) { finish("timed out"); return; }

            // Same stopping-distance envelope the route follower uses: v/(1-d) with d=.6*.91.
            double stoppingDistance = Math.max(0.0, speed) / (1.0 - GROUND_DRAG);
            released = remaining <= stoppingDistance + 0.02;
            binding.setPressed(!released);
            scheduleDelay();
        }

        private double project(Vec3d delta) {
            return delta.x * direction.x + delta.z * direction.z;
        }

        private void finish(String detail) {
            done = true;
            binding.setPressed(false);
            if (client.player != null) client.player.sendMessage(
                    Text.literal("§bTalos §7» §fwalk " + directionName + ": " + detail), true);
        }

        @Override public void onCompleted() { binding.setPressed(false); }
        @Override public Set<Object> getMutexKeys() { return Set.of("glade-player-movement"); }
    }
}
