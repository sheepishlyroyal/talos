package dev.talos.client.pathing.sim;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * One-time registry bootstrap so Blocks/Items/ItemStacks work in plain JUnit. Datapack tag
 * loading never runs headless, so the tags the simulator and mining-cost model depend on
 * are bound through a real tag reload; every unspecified tag binds empty (isIn -> false).
 *
 * <p>freeze() flips the frozen flag before validating required tags, so its "unbound tags"
 * error is swallowed deliberately — the reload right after it binds everything.
 */
final class Bootstraps {
    private static boolean done;

    private Bootstraps() {}

    static synchronized void ensure() {
        if (done) return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Map<TagKey<Block>, List<Holder<Block>>> blockTags = new HashMap<>();
        blockTags.put(BlockTags.CLIMBABLE,
                blocks(Blocks.LADDER, Blocks.VINE, Blocks.SCAFFOLDING));
        blockTags.put(BlockTags.MINEABLE_WITH_PICKAXE,
                blocks(Blocks.STONE, Blocks.COBBLESTONE, Blocks.OBSIDIAN, Blocks.IRON_ORE));
        blockTags.put(BlockTags.MINEABLE_WITH_SHOVEL,
                blocks(Blocks.DIRT, Blocks.SAND, Blocks.GRAVEL));
        blockTags.put(BlockTags.INCORRECT_FOR_WOODEN_TOOL,
                blocks(Blocks.OBSIDIAN, Blocks.IRON_ORE));
        blockTags.put(BlockTags.INCORRECT_FOR_STONE_TOOL, blocks(Blocks.OBSIDIAN));
        blockTags.put(BlockTags.INCORRECT_FOR_IRON_TOOL, blocks(Blocks.OBSIDIAN));
        blockTags.put(BlockTags.INCORRECT_FOR_GOLD_TOOL, blocks(Blocks.OBSIDIAN));

        Map<TagKey<Fluid>, List<Holder<Fluid>>> fluidTags = new HashMap<>();
        fluidTags.put(FluidTags.WATER, fluids(Fluids.WATER, Fluids.FLOWING_WATER));
        fluidTags.put(FluidTags.LAVA, fluids(Fluids.LAVA, Fluids.FLOWING_LAVA));

        freezeAndBind(BuiltInRegistries.BLOCK, blockTags);
        freezeAndBind(BuiltInRegistries.FLUID, fluidTags);
        freezeAndBind(BuiltInRegistries.ITEM, new HashMap<TagKey<Item>, List<Holder<Item>>>());
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup())
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        done = true;
    }

    private static <T> void freezeAndBind(Registry<T> registry,
            Map<TagKey<T>, List<Holder<T>>> tags) {
        try {
            registry.freeze();
        } catch (IllegalStateException requiredTagsUnbound) {
            // Expected headless: the flag is already flipped, the reload below binds tags.
        }
        registry.prepareTagReload(new TagLoader.LoadResult<>(registry.key(), tags))
                .apply();
    }

    private static List<Holder<Block>> blocks(Block... blocks) {
        return Arrays.stream(blocks)
                .map(block -> (Holder<Block>) BuiltInRegistries.BLOCK.wrapAsHolder(block))
                .toList();
    }

    private static List<Holder<Fluid>> fluids(Fluid... fluids) {
        return Arrays.stream(fluids)
                .map(fluid -> (Holder<Fluid>) BuiltInRegistries.FLUID.wrapAsHolder(fluid))
                .toList();
    }
}
