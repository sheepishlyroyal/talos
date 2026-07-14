package dev.talos.client.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * Brigadier argument type for a look angle (yaw or pitch) that supports Minecraft's
 * caret-relative syntax: a plain number ({@code 90}, {@code -45.5}) is an absolute angle in
 * degrees, while a value prefixed with {@code ^} ({@code ^}, {@code ^10}, {@code ^-15}) is
 * relative to the player's current angle on that axis at execution time.
 */
final class RelativeAngleArgumentType implements ArgumentType<RelativeAngleArgumentType.Angle> {
    private static final RelativeAngleArgumentType INSTANCE = new RelativeAngleArgumentType();

    private static final SimpleCommandExceptionType MISSING_VALUE = new SimpleCommandExceptionType(
            Component.literal("Expected an angle, e.g. 90, -45.5, ^ or ^10"));
    private static final DynamicCommandExceptionType INVALID_NUMBER = new DynamicCommandExceptionType(
            value -> Component.literal("Invalid angle value: " + value));

    private RelativeAngleArgumentType() {
    }

    static RelativeAngleArgumentType angle() {
        return INSTANCE;
    }

    /** A single-axis angle: either absolute, or relative to a base resolved at execution time. */
    record Angle(boolean relative, float offset) {
        float resolve(float base) {
            return relative ? base + offset : offset;
        }
    }

    @Override
    public Angle parse(StringReader reader) throws CommandSyntaxException {
        boolean relative = false;
        if (reader.canRead() && reader.peek() == '^') {
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
            return new Angle(true, 0.0F);
        }
        try {
            return new Angle(relative, Float.parseFloat(number));
        } catch (NumberFormatException exception) {
            throw INVALID_NUMBER.createWithContext(reader, number);
        }
    }

    private static boolean isAllowedNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == '-';
    }

    @Override
    public java.util.Collection<String> getExamples() {
        return List.of("0", "^", "^10", "^-15", "90");
    }

    /** Resolves the named angle argument against {@code base} (the player's current angle). */
    static float resolve(CommandContext<?> context, String name, float base) {
        Angle angle = context.getArgument(name, Angle.class);
        return angle.resolve(base);
    }
}
