package dev.glade.client.blockeditor.model;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, data-driven description of one editor block type.
 * TODO(Phase 8+): add mutator metadata without changing the v1 socket contract.
 */
public record BlockDef(String id, String label, Category category, Shape shape, List<Socket> sockets,
        boolean acceptsPrevious, boolean acceptsNext, ValueType outputType, String pythonTemplate) {
    public enum Category { EVENT, ACTION, CONTROL, VALUE, VARIABLE }
    public enum Shape { HAT, STACK, REPORTER, C_BLOCK }
    public enum SocketKind { FIELD, VALUE, STATEMENT }
    /** IDENTIFIER fields are emitted as raw (unquoted) Python identifiers, e.g. variable names. */
    public enum ValueType { ANY, NUMBER, TEXT, BOOLEAN, POSITION, IDENTIFIER }
    /** How an inline (unconnected) socket chip should be rendered/edited on the canvas. */
    public enum FieldWidget { TEXT, BLOCK_PICKER }

    public record Socket(String name, SocketKind kind, ValueType type, String defaultValue, FieldWidget widget) {
        public Socket {
            Objects.requireNonNull(name);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(type);
            defaultValue = defaultValue == null ? "" : defaultValue;
            widget = widget == null ? FieldWidget.TEXT : widget;
        }

        public Socket(String name, SocketKind kind, ValueType type, String defaultValue) {
            this(name, kind, type, defaultValue, FieldWidget.TEXT);
        }
    }

    public BlockDef {
        Objects.requireNonNull(id);
        Objects.requireNonNull(label);
        Objects.requireNonNull(category);
        Objects.requireNonNull(shape);
        sockets = List.copyOf(sockets);
        Objects.requireNonNull(outputType);
        Objects.requireNonNull(pythonTemplate);
    }

    public Socket socket(String name) {
        return sockets.stream().filter(socket -> socket.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown socket " + name + " on " + id));
    }
}
