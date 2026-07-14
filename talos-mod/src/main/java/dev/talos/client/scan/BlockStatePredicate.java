package dev.talos.client.scan;

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
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** A block-state predicate compiled into the global raw-state-id space. */
public final class BlockStatePredicate implements Predicate<BlockState> {
    private static final SimpleCommandExceptionType NBT_UNSUPPORTED = new SimpleCommandExceptionType(
            Component.literal("Block entity data is not supported by /talos find block"));

    private final BitSet matchingStateIds;

    private BlockStatePredicate(Predicate<BlockState> predicate) {
        matchingStateIds = new BitSet();
        for (int id = 0; ; id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
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
        return matchingStateIds.get(Block.BLOCK_STATE_REGISTRY.getId(state));
    }

    public boolean canEverMatch(LevelChunkSection section) {
        return section.maybeHas(this);
    }

    public static ArgumentType<Parsed> argument(CommandBuildContext registryAccess) {
        return new BlockPredicateArgument(registryAccess.lookupOrThrow(Registries.BLOCK));
    }

    public static BlockStatePredicate fromArgument(CommandContext<?> context, String name)
            throws CommandSyntaxException {
        Parsed parsed = context.getArgument(name, Parsed.class);
        if (parsed.result.map(BlockStateParser.BlockResult::nbt, BlockStateParser.TagResult::nbt) != null) {
            throw NBT_UNSUPPORTED.create();
        }
        return new BlockStatePredicate(toStatePredicate(parsed.result));
    }

    private static Predicate<BlockState> toStatePredicate(
            Either<BlockStateParser.BlockResult, BlockStateParser.TagResult> parsed) {
        return parsed.map(block -> state -> {
            if (!state.is(block.blockState().getBlock())) {
                return false;
            }
            for (Map.Entry<Property<?>, Comparable<?>> property : block.properties().entrySet()) {
                if (!propertyMatches(state, property.getKey(), property.getValue())) {
                    return false;
                }
            }
            return true;
        }, tag -> state -> {
            if (!state.is(tag.tag())) {
                return false;
            }
            for (Map.Entry<String, String> property : tag.vagueProperties().entrySet()) {
                Property<?> actualProperty = state.getBlock().getStateDefinition().getProperty(property.getKey());
                if (actualProperty == null || !propertyValueMatches(state, actualProperty, property.getValue())) {
                    return false;
                }
            }
            return true;
        });
    }

    private static <T extends Comparable<T>> boolean propertyMatches(
            BlockState state, Property<T> property, Object expected) {
        return state.getValue(property).equals(expected);
    }

    private static <T extends Comparable<T>> boolean propertyValueMatches(
            BlockState state, Property<T> property, String expectedName) {
        return property.getValue(expectedName).map(value -> state.getValue(property).equals(value)).orElse(false);
    }

    public record Parsed(Either<BlockStateParser.BlockResult, BlockStateParser.TagResult> result) {
    }

    private static final class BlockPredicateArgument implements ArgumentType<Parsed> {
        private final HolderLookup<Block> blocks;

        private BlockPredicateArgument(HolderLookup<Block> blocks) {
            this.blocks = blocks;
        }

        @Override
        public Parsed parse(StringReader reader) throws CommandSyntaxException {
            return new Parsed(BlockStateParser.parseForTesting(blocks, reader, true));
        }

        @Override
        public <S> CompletableFuture<Suggestions> listSuggestions(
                CommandContext<S> context, SuggestionsBuilder builder) {
            return BlockStateParser.fillSuggestions(blocks, builder, true, true);
        }
    }
}
