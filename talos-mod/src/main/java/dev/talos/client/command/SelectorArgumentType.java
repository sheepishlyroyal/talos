package dev.talos.client.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * Brigadier argument type that reads a raw Minecraft-style target selector token, e.g.
 * {@code @e[type=cow,tag=friendly]}, {@code @a}, {@code @p} or {@code @s}. Only the token's
 * boundaries are validated here (leading {@code @}, balanced {@code [ ]}); the selector's
 * contents are parsed and applied by {@link EntitySelector}.
 */
final class SelectorArgumentType implements ArgumentType<String> {
    private static final SelectorArgumentType INSTANCE = new SelectorArgumentType();

    private static final SimpleCommandExceptionType EXPECTED_SELECTOR = new SimpleCommandExceptionType(
            Component.literal("Expected a selector, e.g. @e[type=cow], @a, @p or @s"));
    private static final SimpleCommandExceptionType UNBALANCED_BRACKETS = new SimpleCommandExceptionType(
            Component.literal("Unbalanced [ ] in selector"));

    private SelectorArgumentType() {
    }

    public static SelectorArgumentType selector() {
        return INSTANCE;
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        if (!reader.canRead() || reader.peek() != '@') {
            throw EXPECTED_SELECTOR.createWithContext(reader);
        }
        int start = reader.getCursor();
        reader.skip(); // '@'
        if (!reader.canRead()) {
            throw EXPECTED_SELECTOR.createWithContext(reader);
        }
        reader.skip(); // selector letter (e/a/p/s)

        int depth = 0;
        while (reader.canRead()) {
            char c = reader.peek();
            if (c == '[') {
                depth++;
                reader.skip();
            } else if (c == ']') {
                if (depth == 0) {
                    break;
                }
                depth--;
                reader.skip();
            } else if (depth == 0 && Character.isWhitespace(c)) {
                break;
            } else {
                reader.skip();
            }
        }
        if (depth != 0) {
            throw UNBALANCED_BRACKETS.createWithContext(reader);
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public java.util.Collection<String> getExamples() {
        return List.of("@e", "@e[type=cow]", "@a", "@p", "@s");
    }
}
