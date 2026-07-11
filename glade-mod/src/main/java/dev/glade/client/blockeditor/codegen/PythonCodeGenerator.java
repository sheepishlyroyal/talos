package dev.glade.client.blockeditor.codegen;

import dev.glade.client.blockeditor.model.BlockDef.Socket;
import dev.glade.client.blockeditor.model.BlockDef.SocketKind;
import dev.glade.client.blockeditor.model.BlockDef.ValueType;
import dev.glade.client.blockeditor.model.BlockNode;
import dev.glade.client.blockeditor.model.Workspace;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles the block graph into the same Python source consumed by ScriptEngine.
 * TODO(Phase 8+): Python-to-block import and per-block runtime source maps.
 */
public final class PythonCodeGenerator {
    public String generate(Workspace workspace) {
        StringBuilder output = new StringBuilder("import glade\nimport random\n\n");
        Set<String> emitted = new HashSet<>();
        for (String id : workspace.topLevel()) {
            BlockNode node = workspace.get(id);
            if (node == null || node.definition().shape() != dev.glade.client.blockeditor.model.BlockDef.Shape.HAT) continue;
            String code = statement(workspace, node, 0, emitted, 0);
            if (!code.isBlank()) output.append(code).append("\n\n");
        }
        return output.toString().stripTrailing() + "\n";
    }

    private String statement(Workspace workspace, BlockNode node, int indent, Set<String> emitted, int cDepth) {
        if (!emitted.add(node.id())) return line(indent, "# skipped cyclic block link");
        String code = node.definition().pythonTemplate();
        String bodyMarker = "__GLADE_BODY__";
        String bodyCode = null;
        for (Socket socket : node.definition().sockets()) {
            String replacement;
            if (socket.kind() == SocketKind.STATEMENT) {
                int bodyDepth = cDepth + (node.definition().shape() == dev.glade.client.blockeditor.model.BlockDef.Shape.C_BLOCK ? 1 : 0);
                // Phase 8 deliberately caps authored C-block nesting at one; malformed/deeper files remain valid Python.
                int childIndent = node.definition().id().equals("on_script_start") ? indent : indent + 1;
                bodyCode = body(workspace, node.statementInputs().getOrDefault(socket.name(), List.of()),
                        childIndent, emitted, bodyDepth);
                replacement = bodyMarker;
            } else {
                replacement = expression(workspace, node, socket, new HashSet<>());
            }
            code = code.replace("{" + socket.name() + "}", replacement);
        }
        StringBuilder renderedBuilder = new StringBuilder();
        for (String line : code.split("\\n", -1)) {
            if (!renderedBuilder.isEmpty()) renderedBuilder.append('\n');
            if (line.trim().equals(bodyMarker)) renderedBuilder.append(bodyCode == null ? line(indent + 1, "pass") : bodyCode);
            else renderedBuilder.append(line(indent, line));
        }
        String rendered = renderedBuilder.toString();
        BlockNode next = workspace.get(node.next());
        if (next != null) rendered += "\n" + statement(workspace, next, indent, emitted, cDepth);
        return rendered;
    }

    private String body(Workspace workspace, List<String> roots, int indent, Set<String> emitted, int cDepth) {
        StringBuilder result = new StringBuilder();
        for (String id : roots) {
            BlockNode child = workspace.get(id);
            if (child == null) continue;
            if (!result.isEmpty()) result.append('\n');
            result.append(statement(workspace, child, indent, emitted, cDepth));
        }
        return result.isEmpty() ? line(indent, "pass") : result.toString();
    }

    private String expression(Workspace workspace, BlockNode owner, Socket socket, Set<String> path) {
        String childId = owner.valueInputs().get(socket.name());
        BlockNode child = workspace.get(childId);
        if (child == null) {
            String raw = socket.kind() == SocketKind.FIELD
                    ? owner.fields().getOrDefault(socket.name(), socket.defaultValue()) : socket.defaultValue();
            return literal(raw, socket.type());
        }
        if (!path.add(child.id())) return "None";
        String result = child.definition().pythonTemplate();
        for (Socket nested : child.definition().sockets()) {
            if (nested.kind() != SocketKind.STATEMENT)
                result = result.replace("{" + nested.name() + "}", expression(workspace, child, nested, path));
        }
        path.remove(child.id());
        return result;
    }

    private static String literal(String value, ValueType type) {
        if (type == ValueType.TEXT) return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
        if (type == ValueType.BOOLEAN) return Boolean.parseBoolean(value) ? "True" : "False";
        if (type == ValueType.NUMBER) {
            try { Double.parseDouble(value); return value; } catch (NumberFormatException ignored) { return "0"; }
        }
        if (type == ValueType.POSITION) return "None";
        if (value.equals("True") || value.equals("False") || value.equals("None")) return value;
        try { Double.parseDouble(value); return value; } catch (NumberFormatException ignored) { return literal(value, ValueType.TEXT); }
    }

    private static String line(int indent, String text) { return "    ".repeat(indent) + text; }
}
