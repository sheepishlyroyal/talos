package dev.talos.client.render;

import dev.talos.client.ui.pipeline.TalosRenderPipelines;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;

/**
 * Keyed queue of in-world wireframe highlights.
 *
 * <p>Boxes are keyed so callers can replace or cancel their own highlight without
 * touching anyone else's (re-adding under the same key overwrites). Expiry is
 * driven by the client tick counter; drawing happens camera-relative during
 * {@link WorldRenderEvents#AFTER_ENTITIES}.
 */
public final class RenderQueue {
    private static final Map<Object, WireframeBox> BOXES = new ConcurrentHashMap<>();
    private static final float MIN_LINE_WIDTH = 3.0F;

    private static volatile int currentTick;

    private RenderQueue() {
    }

    /** Wires the tick and draw hooks. Call once from client mod init. */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        WorldRenderEvents.AFTER_ENTITIES.register(RenderQueue::render);
    }

    /**
     * Adds (or replaces) a highlight under {@code key}.
     *
     * @param key       caller-chosen identity; re-adding under the same key overwrites
     * @param box       world-space box to outline
     * @param colorRgb  0xRRGGBB
     * @param lifeTicks lifetime in client ticks (20 per second)
     */
    public static void add(Object key, Box box, int colorRgb, int lifeTicks) {
        BOXES.put(key, new WireframeBox(box, colorRgb, currentTick + Math.max(1, lifeTicks)));
    }

    public static void remove(Object key) {
        BOXES.remove(key);
    }

    public static void clear() {
        BOXES.clear();
    }

    private static void tick() {
        int now = ++currentTick;
        BOXES.values().removeIf(box -> box.deathTick() <= now);
    }

    private static void render(WorldRenderContext context) {
        if (BOXES.isEmpty()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }
        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) {
            return;
        }
        Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
        VertexConsumer lines = consumers.getBuffer(TalosRenderPipelines.wireframeLines());
        float lineWidth = Math.max(MIN_LINE_WIDTH, client.getWindow().getMinimumLineWidth());
        for (WireframeBox box : BOXES.values()) {
            int argb = ColorHelper.fullAlpha(box.colorRgb());
            VertexRendering.drawOutline(
                    matrices, lines, VoxelShapes.cuboid(box.box()),
                    -camera.x, -camera.y, -camera.z, argb, lineWidth);
        }
    }
}
