package dev.glade.client.blockeditor.registry;

import dev.glade.client.blockeditor.model.BlockDef;
import dev.glade.client.blockeditor.model.BlockDef.Category;
import dev.glade.client.blockeditor.model.BlockDef.Shape;
import dev.glade.client.blockeditor.model.BlockDef.Socket;
import dev.glade.client.blockeditor.model.BlockDef.SocketKind;
import dev.glade.client.blockeditor.model.BlockDef.ValueType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in Phase-8 block catalogue. Insertion order is palette order. */
public final class BlockRegistry {
    private static final Map<String, BlockDef> DEFINITIONS = new LinkedHashMap<>();

    static {
        add(def("on_script_start", "when script starts", Category.EVENT, Shape.HAT,
                List.of(stmt("body")), false, false, ValueType.ANY, "{body}"));
        add(def("on_tick", "on tick", Category.EVENT, Shape.HAT,
                List.of(stmt("body")), false, false, ValueType.ANY,
                "@glade.on(\"tick\")\ndef _handler(event):\n{body}"));

        add(stack("goto", "go to", List.of(value("x", ValueType.NUMBER, "0"), value("y", ValueType.NUMBER, "64"),
                value("z", ValueType.NUMBER, "0")), "glade.goto({x}, {y}, {z})"));
        add(stack("place_block", "place block", xyz(), "glade.place_block({x}, {y}, {z})"));
        add(stack("break_block", "break block", xyz(), "glade.break_block({x}, {y}, {z})"));
        add(stack("kill_nearest", "kill nearest", List.of(value("radius", ValueType.NUMBER, "6")),
                "glade.kill_nearest({radius})"));
        add(stack("wait_between", "wait between", List.of(value("a", ValueType.NUMBER, "0.2"),
                value("b", ValueType.NUMBER, "0.5")), "glade.wait_between({a}, {b})"));
        add(def("find_block", "find block", Category.ACTION, Shape.REPORTER,
                List.of(value("name", ValueType.TEXT, "stone"), value("radius", ValueType.NUMBER, "16")),
                false, false, ValueType.POSITION, "glade.find_block({name}, {radius})"));
        add(stack("log", "log", List.of(value("msg", ValueType.ANY, "hello")), "glade.log({msg})"));

        add(def("if", "if", Category.CONTROL, Shape.C_BLOCK,
                List.of(value("cond", ValueType.BOOLEAN, "True"), stmt("body")), true, true, ValueType.ANY,
                "if {cond}:\n{body}"));
        add(def("repeat", "repeat", Category.CONTROL, Shape.C_BLOCK,
                List.of(value("n", ValueType.NUMBER, "3"), stmt("body")), true, true, ValueType.ANY,
                "for _ in range(int({n})):\n{body}"));

        add(def("random_between", "random between", Category.VALUE, Shape.REPORTER,
                List.of(value("a", ValueType.NUMBER, "0"), value("b", ValueType.NUMBER, "1")),
                false, false, ValueType.NUMBER, "random.uniform({a}, {b})"));
        add(def("player_pos", "player position", Category.VALUE, Shape.REPORTER, List.of(),
                false, false, ValueType.POSITION, "glade.player_pos()"));
        add(def("number_literal", "number", Category.VALUE, Shape.REPORTER,
                List.of(field("value", ValueType.NUMBER, "0")), false, false, ValueType.NUMBER, "{value}"));
        add(def("text_literal", "text", Category.VALUE, Shape.REPORTER,
                List.of(field("value", ValueType.TEXT, "text")), false, false, ValueType.TEXT, "{value}"));
        add(def("comparison", "less than", Category.VALUE, Shape.REPORTER,
                List.of(value("a", ValueType.ANY, "0"), value("b", ValueType.ANY, "1")),
                false, false, ValueType.BOOLEAN, "({a} < {b})"));
    }

    private BlockRegistry() {}
    public static List<BlockDef> all() { return List.copyOf(DEFINITIONS.values()); }
    public static BlockDef get(String id) {
        BlockDef result = DEFINITIONS.get(id);
        if (result == null) throw new IllegalArgumentException("Unknown block type: " + id);
        return result;
    }

    private static void add(BlockDef definition) { DEFINITIONS.put(definition.id(), definition); }
    private static BlockDef stack(String id, String label, List<Socket> sockets, String template) {
        return def(id, label, Category.ACTION, Shape.STACK, sockets, true, true, ValueType.ANY, template);
    }
    private static BlockDef def(String id, String label, Category category, Shape shape, List<Socket> sockets,
            boolean previous, boolean next, ValueType output, String template) {
        return new BlockDef(id, label, category, shape, sockets, previous, next, output, template);
    }
    private static Socket field(String name, ValueType type, String value) {
        return new Socket(name, SocketKind.FIELD, type, value);
    }
    private static Socket value(String name, ValueType type, String value) {
        return new Socket(name, SocketKind.VALUE, type, value);
    }
    private static Socket stmt(String name) { return new Socket(name, SocketKind.STATEMENT, ValueType.ANY, ""); }
    private static List<Socket> xyz() {
        return List.of(value("x", ValueType.NUMBER, "0"), value("y", ValueType.NUMBER, "64"),
                value("z", ValueType.NUMBER, "0"));
    }
}
