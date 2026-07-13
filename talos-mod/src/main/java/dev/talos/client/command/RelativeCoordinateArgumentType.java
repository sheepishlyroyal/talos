package dev.talos.client.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.text.Text;

/**
 * Brigadier argument type for a single coordinate axis that mirrors vanilla Minecraft's
 * relative-coordinate syntax: a plain number ({@code 12}, {@code -3.5}) is absolute, while a
 * value prefixed with {@code ~} ({@code ~}, {@code ~5}, {@code ~-3}) is relative to the player's
 * current position on that axis at execution time.
 */
final class RelativeCoordinateArgumentType implements ArgumentType<RelativeCoordinateArgumentType.Coordinate> {
    private static final RelativeCoordinateArgumentType INSTANCE = new RelativeCoordinateArgumentType();

    private static final SimpleCommandExceptionType MISSING_VALUE = new SimpleCommandExceptionType(
            Text.literal("Expected a coordinate value, e.g. 12, -3.5, ~ or ~5"));
    private static final DynamicCommandExceptionType INVALID_NUMBER = new DynamicCommandExceptionType(
            value -> Text.literal("Invalid coordinate value: " + value));

    private RelativeCoordinateArgumentType() {
    }

    static RelativeCoordinateArgumentType coordinate() {
        return INSTANCE;
    }

    /** A single-axis coordinate: either absolute, or relative to a base resolved at execution time. */
    record Coordinate(boolean relative, double offset) {
        double resolve(double base) {
            return relative ? base + offset : offset;
        }
    }

    @Override
    public Coordinate parse(StringReader reader) throws CommandSyntaxException {
        boolean relative = false;
        if (reader.canRead() && reader.peek() == '~') {
            relative = true;
            reader.skip();
        }

        int numberStart = reader.getCursor();
        while (reader.canRead() && isAllowedNumberChar(reader.peek())) {
            reader.skip();
        }
        String number = reader.getString().substring(numberStart, reader.getCursor());

        if (number.isEmpty()) {
            if (!relative) {
                throw MISSING_VALUE.createWithContext(reader);
            }
            return new Coordinate(true, 0.0);
        }
        try {
            return new Coordinate(relative, Double.parseDouble(number));
        } catch (NumberFormatException exception) {
            throw INVALID_NUMBER.createWithContext(reader, number);
        }
    }

    private static boolean isAllowedNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == '-';
    }

    @Override
    public java.util.Collection<String> getExamples() {
        return java.util.List.of("0", "~", "~5", "~-3", "12.5");
    }

    /** Resolves the named coordinate argument to a block coordinate against {@code base}. */
    static int resolveBlock(CommandContext<?> context, String name, double base) {
        Coordinate coordinate = context.getArgument(name, Coordinate.class);
        return (int) Math.floor(coordinate.resolve(base));
    }
}
