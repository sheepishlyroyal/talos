package dev.talos.client.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.talos.client.TalosClient;
import dev.talos.client.task.TalosTask;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Direct input automation: {@code /talos walk} and {@code /talos key}. Everything presses the
 * player's logical {@link KeyMapping}s (never raw key codes), so rebound controls keep working —
 * the same contract the pathing followers already honor.
 */
public final class InputCommand {
    private static final String[] KEY_NAMES = {
            "w", "a", "s", "d", "jump", "sneak", "sprint", "attack", "use",
            "drop", "swap", "inventory", "1", "2", "3", "4", "5", "6", "7", "8", "9"
    };

    private InputCommand() {}

    public static LiteralArgumentBuilder<FabricClientCommandSource> walkNode() {
        LiteralArgumentBuilder<FabricClientCommandSource> walk = ClientCommands.literal("walk");
        for (String direction : new String[] {"w", "a", "s", "d"}) {
            walk.then(ClientCommands.literal(direction)
                    .then(ClientCommands.literal("tap")
                            .executes(context -> startKeyTask(context.getSource(), direction, 1)))
                    .then(ClientCommands.literal("hold")
                            .then(ClientCommands.argument("seconds", DoubleArgumentType.doubleArg(0.05, 600.0))
                                    .executes(context -> startKeyTask(context.getSource(), direction,
                                            (int) Math.ceil(DoubleArgumentType.getDouble(context, "seconds") * 20.0)))))
                    .then(ClientCommands.literal("blocks")
                            .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 1000))
                                    .executes(context -> startWalkBlocks(context.getSource(), direction,
                                            IntegerArgumentType.getInteger(context, "count"), WalkTask.Align.CENTER))
                                    .then(ClientCommands.literal("center")
                                            .executes(context -> startWalkBlocks(context.getSource(), direction,
                                                    IntegerArgumentType.getInteger(context, "count"), WalkTask.Align.CENTER)))
                                    .then(ClientCommands.literal("touch")
                                            .executes(context -> startWalkBlocks(context.getSource(), direction,
                                                    IntegerArgumentType.getInteger(context, "count"), WalkTask.Align.TOUCH))))));
        }
        return walk;
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> keyNode() {
        LiteralArgumentBuilder<FabricClientCommandSource> key = ClientCommands.literal("key");
        key.then(ClientCommands.literal("list").executes(context -> {
            context.getSource().sendFeedback(Component.literal(
                    "Keys: " + String.join(", ", KEY_NAMES) + " (w/a/s/d aliases: forward/left/back/right)"));
            return 1;
        }));
        for (String name : KEY_NAMES) {
            key.then(ClientCommands.literal(name)
                    .then(ClientCommands.literal("tap")
                            .executes(context -> startKeyTask(context.getSource(), name, 1)))
                    .then(ClientCommands.literal("hold")
                            .then(ClientCommands.argument("seconds", DoubleArgumentType.doubleArg(0.05, 600.0))
                                    .executes(context -> startKeyTask(context.getSource(), name,
                                            (int) Math.ceil(DoubleArgumentType.getDouble(context, "seconds") * 20.0))))));
        }
        return key;
    }

    private static int startKeyTask(FabricClientCommandSource source, String name, int ticks) {
        Minecraft client = source.getClient();
        KeyMapping binding = resolve(client.options, name);
        if (binding == null) {
            source.sendError(Component.literal("Unknown key: " + name));
            return 0;
        }
        try {
            TalosClient.taskScheduler().addTask("talos-key-" + name, new KeyHoldTask(client, binding, name, ticks));
        } catch (RuntimeException exception) {
            source.sendError(Component.literal(exception.getMessage()));
            return 0;
        }
        source.sendFeedback(Component.literal(ticks <= 1
                ? "Tapped " + name
                : "Holding " + name + " for " + String.format(Locale.ROOT, "%.2f", ticks / 20.0) + "s"));
        return 1;
    }

    private static int startWalkBlocks(FabricClientCommandSource source, String direction, int blocks,
            WalkTask.Align align) {
        Minecraft client = source.getClient();
        if (client.player == null) {
            source.sendError(Component.literal("No player is loaded"));
            return 0;
        }
        KeyMapping binding = resolve(client.options, direction);
        try {
            TalosClient.taskScheduler().addTask("talos-walk",
                    new WalkTask(client, binding, direction, blocks, align));
        } catch (RuntimeException exception) {
            source.sendError(Component.literal(exception.getMessage()));
            return 0;
        }
        source.sendFeedback(Component.literal("Walking " + direction.toUpperCase(Locale.ROOT) + " for "
                + blocks + " block" + (blocks == 1 ? "" : "s") + " (" + align.name().toLowerCase(Locale.ROOT) + ")"));
        return 1;
    }

    /** Resolves a friendly name to the player's live binding; remaps are honored implicitly. */
    static KeyMapping resolve(Options options, String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "w", "forward" -> options.keyUp;
            case "s", "back" -> options.keyDown;
            case "a", "left" -> options.keyLeft;
            case "d", "right" -> options.keyRight;
            case "jump", "space" -> options.keyJump;
            case "sneak", "shift" -> options.keyShift;
            case "sprint", "ctrl" -> options.keySprint;
            case "attack" -> options.keyAttack;
            case "use" -> options.keyUse;
            case "drop" -> options.keyDrop;
            case "swap", "offhand" -> options.keySwapOffhand;
            case "inventory" -> options.keyInventory;
            case "1", "2", "3", "4", "5", "6", "7", "8", "9" ->
                    options.keyHotbarSlots[Integer.parseInt(name) - 1];
            default -> null;
        };
    }

    private static boolean movementKey(Options options, KeyMapping binding) {
        return binding == options.keyUp || binding == options.keyDown
                || binding == options.keyLeft || binding == options.keyRight
                || binding == options.keyJump || binding == options.keyShift
                || binding == options.keySprint;
    }

    /** Presses one binding for a fixed number of ticks. A 1-tick hold is a tap. */
    private static final class KeyHoldTask extends TalosTask {
        private final Minecraft client;
        private final KeyMapping binding;
        private final String name;
        private final int holdTicks;
        private int ticks;
        private boolean done;

        KeyHoldTask(Minecraft client, KeyMapping binding, String name, int holdTicks) {
            this.client = client;
            this.binding = binding;
            this.name = name;
            this.holdTicks = holdTicks;
        }

        @Override public void initialize() {
            // Registering an actual press event (not just held state) makes single taps of
            // attack/use/drop/hotbar behave exactly like a physical key stroke.
            KeyMapping.click(KeyMappingHelper.getBoundKeyOf(binding));
        }
        @Override public boolean condition() { return !done && client.player != null; }
        @Override public void increment() { ticks++; }
        @Override public void body() {
            if (ticks >= holdTicks) {
                binding.setDown(false);
                done = true;
                return;
            }
            binding.setDown(true);
            scheduleDelay();
        }
        @Override public void onCompleted() { binding.setDown(false); }
        @Override public Set<Object> getMutexKeys() {
            return movementKey(client.options, binding)
                    ? Set.of("talos-player-movement") : Set.of("talos-key-" + name);
        }
    }

    /**
     * Walks a WASD direction a fixed number of blocks with momentum-aware braking.
     * CENTER stops the player on the destination block's center; TOUCH stops as soon as the
     * hitbox (radius 0.3) touches the destination block's near face.
     */
    private static final class WalkTask extends TalosTask {
        enum Align { CENTER, TOUCH }

        private static final double GROUND_DRAG = 0.6 * 0.91;
        private static final int STALL_TICKS = 40;
        private static final int MAX_TICKS = 20 * 120;

        private final Minecraft client;
        private final KeyMapping binding;
        private final String directionName;
        private final int blocks;
        private final Align align;
        private Vec3 start;
        private Vec3 direction;
        private double targetDistance;
        private double bestDisplacement;
        private int lastProgressTick;
        private int ticks;
        private boolean released;
        private boolean done;

        WalkTask(Minecraft client, KeyMapping binding, String directionName, int blocks, Align align) {
            this.client = client;
            this.binding = binding;
            this.directionName = directionName;
            this.blocks = blocks;
            this.align = align;
        }

        @Override public void initialize() {
            var player = client.player;
            start = new Vec3(player.getX(), player.getY(), player.getZ());
            double radians = Math.toRadians(player.getYRot());
            Vec3 forward = new Vec3(-Math.sin(radians), 0.0, Math.cos(radians));
            direction = switch (directionName) {
                case "s" -> forward.scale(-1.0);
                case "a" -> new Vec3(forward.z, 0.0, -forward.x);
                case "d" -> new Vec3(-forward.z, 0.0, forward.x);
                default -> forward;
            };
            if (align == Align.CENTER) {
                // Destination is the center of the block column n blocks along the walk line.
                Vec3 raw = start.add(direction.scale(blocks));
                Vec3 snapped = new Vec3(Math.floor(raw.x) + 0.5, raw.y, Math.floor(raw.z) + 0.5);
                targetDistance = project(snapped.subtract(start));
            } else {
                // Stop when the hitbox touches the near face of the destination block.
                Vec3 raw = start.add(direction.scale(blocks));
                Vec3 center = new Vec3(Math.floor(raw.x) + 0.5, raw.y, Math.floor(raw.z) + 0.5);
                Vec3 face = center.subtract(direction.scale(0.5));
                targetDistance = project(face.subtract(start)) - 0.3;
            }
            targetDistance = Math.max(0.0, targetDistance);
        }

        @Override public boolean condition() { return !done; }
        @Override public void increment() { ticks++; }

        @Override public void body() {
            var player = client.player;
            if (player == null || client.level == null) { finish("world unloaded"); return; }
            Vec3 position = new Vec3(player.getX(), player.getY(), player.getZ());
            double displacement = project(position.subtract(start));
            double speed = project(player.getDeltaMovement());
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
            binding.setDown(!released);
            scheduleDelay();
        }

        private double project(Vec3 delta) {
            return delta.x * direction.x + delta.z * direction.z;
        }

        private void finish(String detail) {
            done = true;
            binding.setDown(false);
            if (client.player != null) client.player.sendOverlayMessage(
                    Component.literal("§bTalos §7» §fwalk " + directionName + ": " + detail));
        }

        @Override public void onCompleted() { binding.setDown(false); }
        @Override public Set<Object> getMutexKeys() { return Set.of("talos-player-movement"); }
    }
}
