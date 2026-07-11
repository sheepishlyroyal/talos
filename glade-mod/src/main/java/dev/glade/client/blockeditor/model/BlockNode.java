package dev.glade.client.blockeditor.model;

import dev.glade.client.blockeditor.model.BlockDef.SocketKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mutable graph node. Relationships contain stable ids rather than object pointers. */
public final class BlockNode {
    private final String id;
    private final BlockDef definition;
    private double x;
    private double y;
    private final Map<String, String> fields = new LinkedHashMap<>();
    private final Map<String, String> valueInputs = new LinkedHashMap<>();
    private final Map<String, List<String>> statementInputs = new LinkedHashMap<>();
    private String next;

    public BlockNode(BlockDef definition, double x, double y) {
        this(UUID.randomUUID().toString(), definition, x, y);
    }

    public BlockNode(String id, BlockDef definition, double x, double y) {
        this.id = id;
        this.definition = definition;
        this.x = x;
        this.y = y;
        definition.sockets().forEach(socket -> {
            if (socket.kind() == SocketKind.FIELD) fields.put(socket.name(), socket.defaultValue());
            if (socket.kind() == SocketKind.STATEMENT) statementInputs.put(socket.name(), new ArrayList<>());
        });
    }

    public String id() { return id; }
    public BlockDef definition() { return definition; }
    public double x() { return x; }
    public double y() { return y; }
    public void position(double x, double y) { this.x = x; this.y = y; }
    public Map<String, String> fields() { return fields; }
    public Map<String, String> valueInputs() { return valueInputs; }
    public Map<String, List<String>> statementInputs() { return statementInputs; }
    public String next() { return next; }
    public void next(String next) { this.next = next; }
}
