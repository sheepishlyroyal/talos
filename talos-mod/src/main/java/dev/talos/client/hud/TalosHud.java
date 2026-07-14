package dev.talos.client.hud;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * Script-controlled text overlay in the top-left corner of the screen: a chat-free
 * status console. Lines are keyed by id — setting an existing id updates the line in
 * place, new ids append in first-set order.
 *
 * <p>Bounds, enforced at {@link #set}: at most {@value #MAX_LINES} lines; text longer
 * than {@value #MAX_TEXT} chars and ids longer than {@value #MAX_ID} chars are
 * truncated with an ellipsis rather than rejected. Legacy {@code §} formatting codes
 * are honored (the vanilla font renderer interprets them in raw strings).
 *
 * <p>The store is thread-safe: script workers write directly, the render thread reads
 * a snapshot. Nothing is drawn while the overlay is empty or the debug screen (F3) is
 * open. {@code ScriptEngine} clears the overlay when the primary script session stops,
 * so a finished script never leaves stale text behind.
 */
public final class TalosHud {
    private static final int MAX_LINES = 20;
    private static final int MAX_TEXT = 256;
    private static final int MAX_ID = 64;
    private static final int MARGIN = 4;
    private static final int PADDING = 2;
    private static final int BACKGROUND = 0x90101010;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    /** Insertion-ordered id -> text; every access synchronizes on the map itself. */
    private static final Map<String, String> LINES = new LinkedHashMap<>();

    private TalosHud() {
    }

    /** Registers the overlay layer; call once from client init. */
    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.of("talos", "script_hud"), (context, tickCounter) -> render(context));
    }

    /** Sets (or updates in place) one line. Throws once {@value #MAX_LINES} distinct ids exist. */
    public static void set(String id, String text) {
        if (id == null || text == null) throw new IllegalArgumentException("HUD id and text are required");
        String key = truncate(id, MAX_ID);
        String value = truncate(text, MAX_TEXT);
        synchronized (LINES) {
            if (LINES.size() >= MAX_LINES && !LINES.containsKey(key))
                throw new IllegalStateException("HUD supports at most " + MAX_LINES + " lines");
            LINES.put(key, value);
        }
    }

    /** Removes one line; safe when the id is absent. */
    public static void remove(String id) {
        if (id == null) return;
        synchronized (LINES) {
            LINES.remove(truncate(id, MAX_ID));
        }
    }

    /** Removes every line. */
    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static void render(DrawContext context) {
        List<String> lines;
        synchronized (LINES) {
            if (LINES.isEmpty()) return;
            lines = List.copyOf(LINES.values());
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getDebugHud().shouldShowDebugHud()) return;
        TextRenderer font = client.textRenderer;
        int step = font.fontHeight + 2;
        int width = 0;
        for (String line : lines) width = Math.max(width, font.getWidth(line));
        context.fill(MARGIN - PADDING, MARGIN - PADDING,
                MARGIN + width + PADDING, MARGIN + lines.size() * step - 2 + PADDING, BACKGROUND);
        int y = MARGIN;
        for (String line : lines) {
            context.drawTextWithShadow(font, line, MARGIN, y, TEXT_COLOR);
            y += step;
        }
    }
}
