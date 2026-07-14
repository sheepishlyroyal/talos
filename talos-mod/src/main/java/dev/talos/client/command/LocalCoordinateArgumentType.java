package dev.talos.client.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.List;
import net.minecraft.text.Text;

/**
 * Brigadier argument type for one coordinate axis that accepts all three Minecraft coordinate
 * modes: absolute ({@code 12}, {@code -3.5}), tilde-relative ({@code ~}, {@code ~5} — offset from
 * the player's position on that axis), and caret-local ({@code ^}, {@code ^5} — an axis of the
 * look-relative {@code ^left ^up ^forward} frame). The caret/tilde base is resolved at execute
 * time; caret axes must be combined by {@link RaycastMath#local} as a set of three.
 */
final class LocalCoordinateArgumentType implements ArgumentType<LocalCoordinateArgumentType.Axis> {
    private static final LocalCoordinateArgumentType INSTANCE = new LocalCoordinateArgumentType();

    private static final SimpleCommandExceptionType MISSING_VALUE = new SimpleCommandExceptionType(
            Text.literal("Expected a coordinate, e.g. 12, ~5 (relative) or ^5 (forward/local)"));
    private static final DynamicCommandExceptionType INVALID_NUMBER = new DynamicCommandExceptionType(
            value -> Text.literal("Invalid coordinate value: " + value));

    private LocalCoordinateArgumentType() {
    }

    static LocalCoordinateArgumentType localCoordinate() {
        return INSTANCE;
    }

    /** Which coordinate space this axis is expressed in. */
    enum Type { ABSOLUTE, RELATIVE, LOCAL }

    /** One parsed axis: its {@link Type} and the numeric value (offset for relative/local). */
    record Axis(Type type, double value) {
    }

    @Override
    public Axis parse(StringReader reader) throws CommandSyntaxException {
        Type type = Type.ABSOLUTE;
        if (reader.canRead()) {
            char c = reader.peek();
            if (c == '~') {
                type = Type.RELATIVE;
                reader.skip();
            } else if (c == '^') {
                type = Type.LOCAL;
                reader.skip();
            }
        }

        int numberStart = reader.getCursor();
        while (reader.canRead() && isAllowedNumberChar(reader.peek())) {
            reader.skip();
        }
        String number = reader.getString().substring(numberStart, reader.getCursor());

        if (number.isEmpty()) {
            if (type == Type.ABSOLUTE) {
                throw MISSING_VALUE.createWithContext(reader);
            }
            return new Axis(type, 0.0); // bare ~ or ^ means "no offset on this axis"
        }
        try {
            return new Axis(type, Double.parseDouble(number));
        } catch (NumberFormatException exception) {
            throw INVALID_NUMBER.createWithContext(reader, number);
        }
    }

    private static boolean isAllowedNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == '-';
    }

    @Override
    public java.util.Collection<String> getExamples() {
        return List.of("0", "~", "~5", "^", "^5", "^-3", "12.5");
    }
}
