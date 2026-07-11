package dev.glade.client.blockeditor.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Block graph plus bounded whole-graph snapshot undo/redo. */
public final class Workspace {
    private static final int HISTORY_LIMIT = 64;
    private final Map<String, BlockNode> nodes = new LinkedHashMap<>();
    private final List<String> topLevel = new ArrayList<>();
    private final Deque<State> undo = new ArrayDeque<>();
    private final Deque<State> redo = new ArrayDeque<>();

    public Collection<BlockNode> nodes() { return nodes.values(); }
    public List<String> topLevel() { return topLevel; }
    public BlockNode get(String id) { return id == null ? null : nodes.get(id); }
    public void add(BlockNode node, boolean asTopLevel) {
        nodes.put(node.id(), node);
        if (asTopLevel && !topLevel.contains(node.id())) topLevel.add(node.id());
    }
    public void clear() { nodes.clear(); topLevel.clear(); undo.clear(); redo.clear(); }

    public void checkpoint() {
        undo.push(capture());
        while (undo.size() > HISTORY_LIMIT) undo.removeLast();
        redo.clear();
    }
    public boolean undo() {
        if (undo.isEmpty()) return false;
        redo.push(capture()); restore(undo.pop()); return true;
    }
    public boolean redo() {
        if (redo.isEmpty()) return false;
        undo.push(capture()); restore(redo.pop()); return true;
    }

    /** Every variable name declared by a "set" block in the graph, in first-seen order. */
    public java.util.Set<String> variableNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (BlockNode node : nodes.values()) {
            if (!"set_variable".equals(node.definition().id())) continue;
            String name = node.fields().get("name");
            if (name != null && !name.isBlank()) names.add(name);
        }
        return names;
    }

    /** Removes all inbound relationships so a node can be dragged as a detached subtree. */
    public void detach(String id) {
        topLevel.remove(id);
        for (BlockNode node : nodes.values()) {
            if (id.equals(node.next())) node.next(null);
            node.valueInputs().values().removeIf(id::equals);
            node.statementInputs().values().forEach(list -> list.remove(id));
        }
    }
    public void makeTopLevel(String id) { detach(id); if (!topLevel.contains(id)) topLevel.add(id); }

    private State capture() {
        List<NodeState> copies = nodes.values().stream().map(NodeState::new).toList();
        return new State(copies, List.copyOf(topLevel));
    }
    private void restore(State state) {
        nodes.clear(); topLevel.clear();
        for (NodeState copy : state.nodes) {
            BlockNode node = new BlockNode(copy.id, copy.definition, copy.x, copy.y);
            node.fields().clear(); node.fields().putAll(copy.fields);
            node.valueInputs().putAll(copy.values);
            node.statementInputs().clear();
            copy.statements.forEach((key, value) -> node.statementInputs().put(key, new ArrayList<>(value)));
            node.next(copy.next); nodes.put(node.id(), node);
        }
        topLevel.addAll(state.topLevel);
    }
    private record State(List<NodeState> nodes, List<String> topLevel) {}
    private record NodeState(String id, BlockDef definition, double x, double y, Map<String, String> fields,
            Map<String, String> values, Map<String, List<String>> statements, String next) {
        NodeState(BlockNode node) {
            this(node.id(), node.definition(), node.x(), node.y(), Map.copyOf(node.fields()),
                    Map.copyOf(node.valueInputs()), copyLists(node.statementInputs()), node.next());
        }
        private static Map<String, List<String>> copyLists(Map<String, List<String>> source) {
            Map<String, List<String>> result = new LinkedHashMap<>();
            source.forEach((key, value) -> result.put(key, List.copyOf(value)));
            return result;
        }
    }
}
