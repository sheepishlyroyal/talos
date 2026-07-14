package dev.talos.client.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Registry-id argument that actually accepts what it suggests. The stock
 * {@code StringArgumentType.word()}/{@code string()} charset has no {@code ':'}, so a
 * tab-completed {@code minecraft:stone} failed to parse and only bare ids worked. This
 * type reads the full identifier charset (namespace, path, {@code /}) and suggests both
 * the namespaced and the bare form of every id, so either spelling round-trips.
 *
 * <p>Values are plain (possibly un-namespaced) strings: call sites keep their existing
 * {@code StringArgumentType.getString(...)} reads and their own {@code minecraft:}
 * defaulting, which stays the single normalization rule shared with the Python API.</p>
 */
public final class IdArgumentType implements ArgumentType<String> {
    private static final List<String> EXAMPLES =
            List.of("stone", "minecraft:diamond_ore", "mod:block/thing");

    private final Collection<Identifier> ids;

    private IdArgumentType(Collection<Identifier> ids) {
        this.ids = ids;
    }

    /** Block-id argument: parses namespaced or bare ids, suggests every block. */
    public static IdArgumentType blockId() {
        return new IdArgumentType(BuiltInRegistries.BLOCK.keySet());
    }

    /** Item-id argument: parses namespaced or bare ids, suggests every item. */
    public static IdArgumentType itemId() {
        return new IdArgumentType(BuiltInRegistries.ITEM.keySet());
    }

    @Override
    public String parse(StringReader reader) {
        int start = reader.getCursor();
        while (reader.canRead() && isIdChar(reader.peek())) {
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    private static boolean isIdChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.' || c == '/' || c == ':';
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(
            CommandContext<S> context, SuggestionsBuilder builder) {
        // Suggest bare names for vanilla ids alongside the namespaced form — typing
        // "sto" should surface both "stone" and "minecraft:stone".
        SharedSuggestionProvider.suggest(ids.stream()
                .filter(id -> id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE))
                .map(Identifier::getPath), builder);
        return SharedSuggestionProvider.suggestResource(ids, builder);
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
