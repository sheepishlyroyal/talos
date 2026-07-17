package dev.talos.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.talos.client.ui.pipeline.TalosRenderPipelines;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * Keyed queue of in-world wireframe highlights.
 *
 * <p>Boxes are keyed so callers can replace or cancel their own highlight without
 * touching anyone else's (re-adding under the same key overwrites). Expiry is
 * driven by the client tick counter; drawing happens camera-relative during
 * {@link LevelRenderEvents#AFTER_SOLID_FEATURES}.
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
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(RenderQueue::render);
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
        PoseStack matrices = context.poseStack();
        MultiBufferSource consumers = context.bufferSource();
        if (matrices == null || consumers == null) {
            return;
        }
        Vec3 camera = client.gameRenderer.getMainCamera().position();
        VertexConsumer lines = consumers.getBuffer(TalosRenderPipelines.wireframeLines());
        float lineWidth = Math.max(MIN_LINE_WIDTH, client.getWindow().getAppropriateLineWidth());
        for (WireframeBox box : BOXES.values()) {
            int argb = ARGB.opaque(box.colorRgb());
            ShapeRenderer.renderShape(
                    matrices, lines, Shapes.create(box.box()),
                    -camera.x, -camera.y, -camera.z, argb, lineWidth);
        }
        PoseStack.Pose pose = matrices.last();
        for (WorldLine line : LINES.values()) {
            int argb = ARGB.opaque(line.colorRgb());
            float x1 = (float) (line.from().x - camera.x);
            float y1 = (float) (line.from().y - camera.y);
            float z1 = (float) (line.from().z - camera.z);
            float x2 = (float) (line.to().x - camera.x);
            float y2 = (float) (line.to().y - camera.y);
            float z2 = (float) (line.to().z - camera.z);
            float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0E-4F) continue;
            float nx = dx / len, ny = dy / len, nz = dz / len;
            lines.addVertex(pose, x1, y1, z1).setColor(argb).setNormal(pose, nx, ny, nz);
            lines.addVertex(pose, x2, y2, z2).setColor(argb).setNormal(pose, nx, ny, nz);
        }
    }
}
