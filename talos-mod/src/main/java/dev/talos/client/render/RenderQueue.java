package dev.talos.client.render;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Keyed queue of in-world wireframe highlights.
 *
 * <p>Boxes are keyed so callers can replace or cancel their own highlight without
 * touching anyone else's (re-adding under the same key overwrites). Expiry is
 * driven by the client tick counter; drawing happens camera-relative during
 * {@link LevelRenderEvents#BEFORE_GIZMOS}.
 */
public final class RenderQueue {
    /** A single world-space line segment with an RGB color and expiry tick. */
    public record WorldLine(Vec3 from, Vec3 to, int colorRgb, int deathTick) {
    }

    private static final Map<Object, WireframeBox> BOXES = new ConcurrentHashMap<>();
    private static final Map<Object, WorldLine> LINES = new ConcurrentHashMap<>();
    private static final float MIN_LINE_WIDTH = 3.0F;

    private static volatile int currentTick;

    private RenderQueue() {
    }

    /** Wires the tick and draw hooks. Call once from client mod init. */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        LevelRenderEvents.BEFORE_GIZMOS.register(RenderQueue::render);
    }

    /**
     * Adds (or replaces) a highlight under {@code key}.
     *
     * @param key       caller-chosen identity; re-adding under the same key overwrites
     * @param box       world-space box to outline
     * @param colorRgb  0xRRGGBB
     * @param lifeTicks lifetime in client ticks (20 per second)
     */
    public static void add(Object key, AABB box, int colorRgb, int lifeTicks) {
        BOXES.put(key, new WireframeBox(box, colorRgb, currentTick + Math.max(1, lifeTicks)));
    }

    /** Adds (or replaces) a line segment under {@code key}; same keying rules as boxes. */
    public static void addLine(Object key, Vec3 from, Vec3 to, int colorRgb, int lifeTicks) {
        LINES.put(key, new WorldLine(from, to, colorRgb, currentTick + Math.max(1, lifeTicks)));
    }

    public static void remove(Object key) {
        BOXES.remove(key);
        LINES.remove(key);
    }

    public static void clear() {
        BOXES.clear();
        LINES.clear();
    }

    /** Total live shapes (boxes + lines) — used to cap scripted drawing. */
    public static int size() {
        return BOXES.size() + LINES.size();
    }

    private static void tick() {
        int now = ++currentTick;
        BOXES.values().removeIf(box -> box.deathTick() <= now);
        LINES.values().removeIf(line -> line.deathTick() <= now);
    }

    private static void render(LevelRenderContext context) {
        if (BOXES.isEmpty() && LINES.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        float lineWidth = Math.max(MIN_LINE_WIDTH, client.getWindow().getAppropriateLineWidth());
        for (WireframeBox box : BOXES.values()) {
            Gizmos.cuboid(box.box(), GizmoStyle.stroke(0xFF000000 | box.colorRgb(), lineWidth));
        }
        for (WorldLine line : LINES.values()) {
            Gizmos.line(line.from(), line.to(), 0xFF000000 | line.colorRgb(), lineWidth);
        }
    }
}
