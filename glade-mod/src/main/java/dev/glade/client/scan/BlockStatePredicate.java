package dev.glade.client.scan;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.datafixers.util.Either;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.world.chunk.ChunkSection;

/** A block-state predicate compiled into the global raw-state-id space. */
public final class BlockStatePredicate implements Predicate<BlockState> {
    private static final SimpleCommandExceptionType NBT_UNSUPPORTED = new SimpleCommandExceptionType(
            Text.literal("Block entity data is not supported by /glade find block"));

    private final BitSet matchingStateIds;

    private BlockStatePredicate(Predicate<BlockState> predicate) {
        matchingStateIds = new BitSet();
        for (int id = 0; ; id++) {
            BlockState state = Block.STATE_IDS.get(id);
            if (state == null) {
                break;
            }
            if (predicate.test(state)) {
                matchingStateIds.set(id);
            }
        }
    }

    @Override
    public boolean test(BlockState state) {
        return matchingStateIds.get(Block.STATE_IDS.getRawId(state));
    }

    public boolean canEverMatch(ChunkSection section) {
        return section.hasAny(this);
    }

    public static ArgumentType<Parsed> argument(CommandRegistryAccess registryAccess) {
        return new BlockPredicateArgument(registryAccess.getOrThrow(RegistryKeys.BLOCK));
    }

    public static BlockStatePredicate fromArgument(CommandContext<?> context, String name)
            throws CommandSyntaxException {
        Parsed parsed = context.getArgument(name, Parsed.class);
        if (parsed.result.map(BlockArgumentParser.BlockResult::nbt, BlockArgumentParser.TagResult::nbt) != null) {
            throw NBT_UNSUPPORTED.create();
        }
        return new BlockStatePredicate(toStatePredicate(parsed.result));
    }

    private static Predicate<BlockState> toStatePredicate(
            Either<BlockArgumentParser.BlockResult, BlockArgumentParser.TagResult> parsed) {
        return parsed.map(block -> state -> {
            if (!state.isOf(block.blockState().getBlock())) {
                return false;
            }
            for (Map.Entry<Property<?>, Comparable<?>> property : block.properties().entrySet()) {
                if (!propertyMatches(state, property.getKey(), property.getValue())) {
                    return false;
                }
            }
            return true;
        }, tag -> state -> {
            if (!state.isIn(tag.tag())) {
                return false;
            }
            for (Map.Entry<String, String> property : tag.vagueProperties().entrySet()) {
                Property<?> actualProperty = state.getBlock().getStateManager().getProperty(property.getKey());
                if (actualProperty == null || !propertyValueMatches(state, actualProperty, property.getValue())) {
                    return false;
                }
            }
            return true;
        });
    }

    private static <T extends Comparable<T>> boolean propertyMatches(
            BlockState state, Property<T> property, Object expected) {
        return state.get(property).equals(expected);
    }

    private static <T extends Comparable<T>> boolean propertyValueMatches(
            BlockState state, Property<T> property, String expectedName) {
        return property.parse(expectedName).map(value -> state.get(property).equals(value)).orElse(false);
    }

    public record Parsed(Either<BlockArgumentParser.BlockResult, BlockArgumentParser.TagResult> result) {
    }

    private static final class BlockPredicateArgument implements ArgumentType<Parsed> {
        private final RegistryWrapper<Block> blocks;

        private BlockPredicateArgument(RegistryWrapper<Block> blocks) {
            this.blocks = blocks;
        }

        @Override
        public Parsed parse(StringReader reader) throws CommandSyntaxException {
            return new Parsed(BlockArgumentParser.blockOrTag(blocks, reader, true));
        }

        @Override
        public <S> CompletableFuture<Suggestions> listSuggestions(
                CommandContext<S> context, SuggestionsBuilder builder) {
            return BlockArgumentParser.getSuggestions(blocks, builder, true, true);
        }
    }
}
