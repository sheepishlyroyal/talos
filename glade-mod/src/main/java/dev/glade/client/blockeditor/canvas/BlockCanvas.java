package dev.glade.client.blockeditor.canvas;

import dev.glade.client.blockeditor.model.BlockDef;
import dev.glade.client.blockeditor.model.BlockDef.Category;
import dev.glade.client.blockeditor.model.BlockDef.Shape;
import dev.glade.client.blockeditor.model.BlockDef.Socket;
import dev.glade.client.blockeditor.model.BlockDef.SocketKind;
import dev.glade.client.blockeditor.model.BlockDef.ValueType;
import dev.glade.client.blockeditor.model.BlockNode;
import dev.glade.client.blockeditor.model.Workspace;
import dev.glade.client.blockeditor.registry.BlockRegistry;
import dev.glade.client.ui.draw.GladeUi;
import dev.glade.client.ui.theme.Theme;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/** Immediate-mode Blockly-like canvas: palette, grid, graph rendering, dragging, panning and snapping. */
public final class BlockCanvas {
    public static final int PALETTE_WIDTH = 184;
    private static final int ROW_HEIGHT = 25;
    private static final float BLOCK_HEIGHT = 30;
    private static final float BLOCK_WIDTH = 200;
    private static final float REPORTER_WIDTH = 160;
    private static final double SNAP_RADIUS = 28.0;
    private static final float FIELD_CHIP_HEIGHT = 18;

    private Workspace workspace;
    private int x, y, width, height;
    private double panX = 40, panY = 35, zoom = 1.0;
    private String dragging;
    private boolean spawnDrag;
    private String editingLiteral;
    private String editingSocket;
    private boolean replaceLiteral;
    private boolean panning;
    private double lastMouseX, lastMouseY;
    private Candidate previewSnap;

    public BlockCanvas(Workspace workspace) { this.workspace = workspace; }
    public Workspace workspace() { return workspace; }
    public void workspace(Workspace value) {
        workspace = value; dragging = null; spawnDrag = false;
        editingLiteral = null; editingSocket = null; previewSnap = null;
    }
    public void bounds(int x, int y, int width, int height) { this.x = x; this.y = y; this.width = width; this.height = height; }

    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        var palette = Theme.palette();
        GladeUi.roundedRect(context, x, y, width, height, 12, palette.rectDark());
        drawGrid(context);
        context.enableScissor(x + PALETTE_WIDTH, y, x + width, y + height);
        context.getMatrices().pushMatrix();
        context.getMatrices().translate((float) (x + PALETTE_WIDTH + panX), (float) (y + panY));
        context.getMatrices().scale((float) zoom);
        layoutConnections();
        Set<String> drawn = new HashSet<>();
        for (String id : workspace.topLevel()) drawTree(context, workspace.get(id), drawn);
        for (BlockNode node : workspace.nodes()) drawTree(context, node, drawn);
        if (previewSnap != null) drawSnapHighlight(context, previewSnap);
        context.getMatrices().popMatrix();
        context.disableScissor();
        drawPalette(context, mouseX, mouseY);
        GladeUi.roundedRectBorder(context, x, y, width, height, 12, 1, palette.outline());
    }

    private void drawGrid(DrawContext context) {
        int left = x + PALETTE_WIDTH;
        int spacing = Math.max(12, (int) Math.round(24 * zoom));
        int color = (Theme.palette().outline() & 0x00FFFFFF) | 0x50000000;
        int ox = Math.floorMod((int) Math.round(panX), spacing);
        int oy = Math.floorMod((int) Math.round(panY), spacing);
        for (int gx = left + ox; gx < x + width; gx += spacing) context.fill(gx, y, gx + 1, y + height, color);
        for (int gy = y + oy; gy < y + height; gy += spacing) context.fill(left, gy, x + width, gy + 1, color);
    }

    private void drawPalette(DrawContext context, int mouseX, int mouseY) {
        var theme = Theme.palette();
        GladeUi.glassPanel(context, x, y, PALETTE_WIDTH, height, 12, theme.panel());
        context.drawText(MinecraftClient.getInstance().textRenderer, "BLOCKS", x + 12, y + 9, theme.description(), false);
        List<BlockDef> defs = BlockRegistry.all();
        for (int i = 0; i < defs.size(); i++) {
            BlockDef def = defs.get(i);
            int by = y + 25 + i * ROW_HEIGHT;
            if (by + 21 > y + height) break;
            int color = categoryColor(def.category());
            if (mouseX >= x + 7 && mouseX < x + PALETTE_WIDTH - 7 && mouseY >= by && mouseY < by + 21)
                color = brighten(color);
            GladeUi.roundedRect(context, x + 7, by, PALETTE_WIDTH - 14, 21, def.shape() == Shape.REPORTER ? 10 : 6, color);
            context.drawText(MinecraftClient.getInstance().textRenderer, def.label(), x + 14, by + 6, 0xFFFFFFFF, false);
        }
    }

    private void drawTree(DrawContext context, BlockNode node, Set<String> drawn) {
        if (node == null || !drawn.add(node.id())) return;
        float w = node.definition().shape() == Shape.REPORTER ? REPORTER_WIDTH : BLOCK_WIDTH;
        float h = blockHeight(node);
        int color = categoryColor(node.definition().category());
        GladeUi.glassPanel(context, (float) node.x(), (float) node.y(), w, h,
                node.definition().shape() == Shape.REPORTER ? 14 : 7, color);
        GladeUi.roundedRectBorder(context, (float) node.x(), (float) node.y(), w, h,
                node.definition().shape() == Shape.REPORTER ? 14 : 7, 1, (dragging != null && dragging.equals(node.id()))
                        || node.id().equals(editingLiteral)
                        ? 0xFFFFFFFF : 0x55FFFFFF);
        FieldChip chip = fieldChip(node);
        String label = displayLabel(node);
        if (chip != null) label = clipToWidth(label, (int) (chip.x() - node.x()) - 12);
        context.drawText(MinecraftClient.getInstance().textRenderer, label,
                (int) node.x() + 9, (int) node.y() + 10, 0xFFFFFFFF, false);
        if (node.definition().shape() == Shape.C_BLOCK) {
            context.drawText(MinecraftClient.getInstance().textRenderer, "do", (int) node.x() + 10,
                    (int) node.y() + 34, 0xCCFFFFFF, false);
        }
        if (chip != null) drawFieldChip(context, node, chip);
        for (String child : node.valueInputs().values()) drawTree(context, workspace.get(child), drawn);
        for (List<String> roots : node.statementInputs().values()) for (String child : roots) drawTree(context, workspace.get(child), drawn);
        drawTree(context, workspace.get(node.next()), drawn);
    }

    private String displayLabel(BlockNode node) {
        StringBuilder label = new StringBuilder(node.definition().label());
        for (Socket socket : node.definition().sockets()) {
            if (socket.kind() == SocketKind.STATEMENT || socket.kind() == SocketKind.FIELD) continue;
            String child = node.valueInputs().get(socket.name());
            String value = child == null ? node.fields().getOrDefault(socket.name(), socket.defaultValue()) : "◆";
            label.append(' ').append(socket.name()).append('=').append(value);
        }
        return label.toString();
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button, boolean doubled) {
        lastMouseX = mouseX; lastMouseY = mouseY;
        if (!contains(mouseX, mouseY)) return false;
        if (button == 1 || button == 2) { panning = true; return true; }
        if (button != 0) return false;
        if (mouseX < x + PALETTE_WIDTH) {
            int index = (int) ((mouseY - y - 25) / ROW_HEIGHT);
            if (index >= 0 && index < BlockRegistry.all().size()) {
                workspace.checkpoint();
                BlockDef definition = BlockRegistry.all().get(index);
                // Spawn under the cursor immediately: clamp the sample point onto the canvas so the
                // world-space math stays sane even while the pointer is still over the palette.
                Point world = toWorld(Math.max(mouseX, x + PALETTE_WIDTH + 4), mouseY);
                BlockNode node = new BlockNode(definition, world.x - 8, world.y - 10);
                workspace.add(node, true);
                dragging = node.id();
                spawnDrag = true;
                editingLiteral = null;
                editingSocket = null;
                return true;
            }
        }
        Point world = toWorld(mouseX, mouseY);
        BlockNode hit = hitNode(world.x, world.y);
        if (hit != null) {
            FieldChip chip = fieldChip(hit);
            if (chip != null && chip.contains(world.x, world.y)) {
                editingLiteral = hit.id(); editingSocket = chip.socket(); replaceLiteral = true; return true;
            }
            editingLiteral = null; editingSocket = null;
            workspace.checkpoint(); workspace.makeTopLevel(hit.id()); dragging = hit.id(); spawnDrag = false;
            return true;
        }
        panning = true;
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (dragging != null) {
            BlockNode node = workspace.get(dragging);
            if (node != null) {
                if (spawnDrag) {
                    Point world = toWorld(mouseX, mouseY);
                    node.position(world.x - 8, world.y - 10);
                } else {
                    node.position(node.x() + deltaX / zoom, node.y() + deltaY / zoom);
                }
                previewSnap = bestCandidate(node);
            }
            return true;
        }
        if (panning) { panX += deltaX; panY += deltaY; return true; }
        return false;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging != null) {
            snap(workspace.get(dragging));
            dragging = null; spawnDrag = false; previewSnap = null;
            return true;
        }
        if (panning) { panning = false; return true; }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!contains(mouseX, mouseY) || mouseX < x + PALETTE_WIDTH) return false;
        Point before = toWorld(mouseX, mouseY);
        zoom = Math.clamp(zoom * Math.pow(1.1, amount), 0.55, 1.8);
        Point after = toWorld(mouseX, mouseY);
        panX += (after.x - before.x) * zoom; panY += (after.y - before.y) * zoom;
        return true;
    }

    /** Click a field chip (number/text/identifier), then type. Backspace is forwarded by the screen. */
    public boolean charTyped(String text) {
        BlockNode node = workspace.get(editingLiteral);
        if (node == null || editingSocket == null || text == null || text.isEmpty()) return false;
        Socket socket = fieldSocket(node, editingSocket);
        if (socket == null) return false;
        String old = node.fields().getOrDefault(editingSocket, "");
        if (replaceLiteral) { workspace.checkpoint(); old = ""; replaceLiteral = false; }
        String next = old + text;
        if (socket.type() == ValueType.NUMBER && !next.matches("-?[0-9]*([.][0-9]*)?")) return true;
        node.fields().put(editingSocket, next); return true;
    }

    public boolean backspace() {
        BlockNode node = workspace.get(editingLiteral);
        if (node == null || editingSocket == null) return false;
        String value = node.fields().getOrDefault(editingSocket, "");
        if (!value.isEmpty()) node.fields().put(editingSocket, value.substring(0, value.length() - 1));
        replaceLiteral = false; return true;
    }

    private static Socket fieldSocket(BlockNode node, String name) {
        for (Socket socket : node.definition().sockets())
            if (socket.kind() == SocketKind.FIELD && socket.name().equals(name)) return socket;
        return null;
    }

    private void snap(BlockNode dragged) {
        if (dragged == null) return;
        Candidate best = bestCandidate(dragged);
        if (best == null) { workspace.makeTopLevel(dragged.id()); return; }
        workspace.detach(dragged.id());
        switch (best.kind) {
            case NEXT -> best.target.next(dragged.id());
            case BODY -> best.target.statementInputs().get(best.socket.name()).add(dragged.id());
            case VALUE -> best.target.valueInputs().put(best.socket.name(), dragged.id());
        }
        layoutConnections();
    }

    /** Ranks every legal connection point against the dragged block and returns the nearest one in range. */
    private Candidate bestCandidate(BlockNode dragged) {
        if (dragged == null) return null;
        Candidate best = null;
        for (BlockNode target : workspace.nodes()) {
            if (target == dragged) continue;
            if (dragged.definition().acceptsPrevious() && target.definition().acceptsNext() && target.next() == null)
                best = nearer(best, new Candidate(target, null, Kind.NEXT,
                        distance(dragged.x(), dragged.y(), target.x(), target.y() + blockHeight(target))));
            if (dragged.definition().acceptsPrevious()) {
                boolean draggedIsCBlock = dragged.definition().shape() == Shape.C_BLOCK;
                boolean targetIsCBlock = target.definition().shape() == Shape.C_BLOCK;
                if (!(draggedIsCBlock && targetIsCBlock)) { // v1: no C-block inside another C-block.
                    for (Socket socket : target.definition().sockets())
                        if (socket.kind() == SocketKind.STATEMENT
                                && target.statementInputs().getOrDefault(socket.name(), List.of()).isEmpty())
                            best = nearer(best, new Candidate(target, socket, Kind.BODY,
                                    distance(dragged.x(), dragged.y(), target.x() + 22, target.y() + BLOCK_HEIGHT)));
                }
            }
            if (dragged.definition().shape() == Shape.REPORTER) {
                int index = 0;
                for (Socket socket : target.definition().sockets()) if (socket.kind() == SocketKind.VALUE) {
                    if (!target.valueInputs().containsKey(socket.name()) && compatible(dragged, socket))
                        best = nearer(best, new Candidate(target, socket, Kind.VALUE,
                                distance(dragged.x(), dragged.y(), target.x() + 62 + index * 32, target.y())));
                    index++;
                }
            }
        }
        return best != null && best.distance <= SNAP_RADIUS ? best : null;
    }

    private void drawSnapHighlight(DrawContext context, Candidate candidate) {
        BlockNode target = candidate.target;
        float w = target.definition().shape() == Shape.REPORTER ? REPORTER_WIDTH : BLOCK_WIDTH;
        float h = blockHeight(target);
        float radius = target.definition().shape() == Shape.REPORTER ? 14 : 7;
        GladeUi.roundedRectBorder(context, (float) target.x() - 2, (float) target.y() - 2, w + 4, h + 4,
                radius, 2, 0xFFFFD34D);
    }

    private FieldChip fieldChip(BlockNode node) {
        Socket only = null;
        for (Socket socket : node.definition().sockets()) {
            if (socket.kind() != SocketKind.FIELD) continue;
            if (only != null) return null; // more than one field: v1 keeps chip editing to single-field blocks.
            only = socket;
        }
        if (only == null) return null;
        float w = node.definition().shape() == Shape.REPORTER ? REPORTER_WIDTH : BLOCK_WIDTH;
        double chipW = Math.min(96, w - 40);
        double chipX = node.x() + w - chipW - 6;
        double chipY = node.y() + 6;
        return new FieldChip(chipX, chipY, chipW, FIELD_CHIP_HEIGHT, only.name());
    }

    private void drawFieldChip(DrawContext context, BlockNode node, FieldChip chip) {
        boolean editingThis = node.id().equals(editingLiteral) && chip.socket().equals(editingSocket);
        GladeUi.roundedRect(context, (float) chip.x(), (float) chip.y(), (float) chip.w(), (float) chip.h(), 4,
                editingThis ? 0xFF2C2C2C : 0x99101010);
        GladeUi.roundedRectBorder(context, (float) chip.x(), (float) chip.y(), (float) chip.w(), (float) chip.h(), 4, 1,
                editingThis ? 0xFFFFD34D : 0xAAFFFFFF);
        String value = node.fields().getOrDefault(chip.socket(), "");
        String shown = editingThis ? value + "_" : (value.isEmpty() ? "(empty)" : value);
        String clipped = clipToWidth(shown, (int) chip.w() - 6);
        context.drawText(MinecraftClient.getInstance().textRenderer, clipped,
                (int) chip.x() + 3, (int) chip.y() + 5, 0xFFFFFFFF, false);
    }

    private static String clipToWidth(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        var renderer = MinecraftClient.getInstance().textRenderer;
        if (renderer.getWidth(text) <= maxWidth) return text;
        String ellipsis = "...";
        int ellipsisWidth = renderer.getWidth(ellipsis);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (renderer.getWidth(result.toString() + text.charAt(i)) + ellipsisWidth > maxWidth) break;
            result.append(text.charAt(i));
        }
        return result + ellipsis;
    }

    private void layoutConnections() {
        for (BlockNode node : workspace.nodes()) {
            BlockNode next = workspace.get(node.next());
            if (next != null) next.position(node.x(), node.y() + blockHeight(node));
            for (Socket socket : node.definition().sockets()) {
                if (socket.kind() == SocketKind.STATEMENT) {
                    double sy = node.y() + BLOCK_HEIGHT;
                    for (String id : node.statementInputs().getOrDefault(socket.name(), List.of())) {
                        BlockNode child = workspace.get(id); if (child != null) { child.position(node.x() + 22, sy); sy += blockHeight(child); }
                    }
                } else if (socket.kind() == SocketKind.VALUE) {
                    BlockNode child = workspace.get(node.valueInputs().get(socket.name()));
                    if (child != null) child.position(node.x() + 55, node.y() - BLOCK_HEIGHT + 4);
                }
            }
        }
    }

    private float blockHeight(BlockNode node) { return node.definition().shape() == Shape.C_BLOCK ? 58 : BLOCK_HEIGHT; }
    private BlockNode hitNode(double wx, double wy) {
        BlockNode result = null;
        for (BlockNode node : workspace.nodes()) {
            float w = node.definition().shape() == Shape.REPORTER ? REPORTER_WIDTH : BLOCK_WIDTH;
            if (wx >= node.x() && wx < node.x() + w && wy >= node.y() && wy < node.y() + blockHeight(node)) result = node;
        }
        return result;
    }
    private Point toWorld(double sx, double sy) {
        return new Point((sx - x - PALETTE_WIDTH - panX) / zoom, (sy - y - panY) / zoom);
    }
    private boolean contains(double mx, double my) { return mx >= x && mx < x + width && my >= y && my < y + height; }
    private static boolean compatible(BlockNode reporter, Socket socket) {
        return socket.type() == ValueType.ANY || reporter.definition().outputType() == ValueType.ANY
                || socket.type() == reporter.definition().outputType();
    }
    private static Candidate nearer(Candidate old, Candidate candidate) {
        return old == null || candidate.distance < old.distance ? candidate : old;
    }
    private static double distance(double ax, double ay, double bx, double by) { return Math.hypot(ax - bx, ay - by); }
    private static int brighten(int color) {
        int r = Math.min(255, ((color >> 16) & 255) + 25), g = Math.min(255, ((color >> 8) & 255) + 25), b = Math.min(255, (color & 255) + 25);
        return (color & 0xFF000000) | r << 16 | g << 8 | b;
    }
    private static int categoryColor(Category category) {
        return switch (category) {
            case EVENT -> 0xE09B51E0; case ACTION -> 0xE0367BD9; case CONTROL -> 0xE0D08A24;
            case VALUE -> 0xE02C9B70; case VARIABLE -> 0xE0A23B72;
        };
    }
    private enum Kind { NEXT, BODY, VALUE }
    private record Candidate(BlockNode target, Socket socket, Kind kind, double distance) {}
    private record Point(double x, double y) {}
    private record FieldChip(double x, double y, double w, double h, String socket) {
        boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
    }
}
