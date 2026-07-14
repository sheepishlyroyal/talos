package dev.talos.client.pathing.sim;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.TagGroupLoader;
import net.minecraft.registry.tag.TagKey;

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
        SharedConstants.createGameVersion();
        Bootstrap.initialize();

        Map<TagKey<Block>, List<RegistryEntry<Block>>> blockTags = new HashMap<>();
        blockTags.put(BlockTags.CLIMBABLE,
                blocks(Blocks.LADDER, Blocks.VINE, Blocks.SCAFFOLDING));
        blockTags.put(BlockTags.PICKAXE_MINEABLE,
                blocks(Blocks.STONE, Blocks.COBBLESTONE, Blocks.OBSIDIAN, Blocks.IRON_ORE));
        blockTags.put(BlockTags.SHOVEL_MINEABLE,
                blocks(Blocks.DIRT, Blocks.SAND, Blocks.GRAVEL));
        blockTags.put(BlockTags.INCORRECT_FOR_WOODEN_TOOL,
                blocks(Blocks.OBSIDIAN, Blocks.IRON_ORE));
        blockTags.put(BlockTags.INCORRECT_FOR_STONE_TOOL, blocks(Blocks.OBSIDIAN));
        blockTags.put(BlockTags.INCORRECT_FOR_IRON_TOOL, blocks(Blocks.OBSIDIAN));
        blockTags.put(BlockTags.INCORRECT_FOR_GOLD_TOOL, blocks(Blocks.OBSIDIAN));

        Map<TagKey<Fluid>, List<RegistryEntry<Fluid>>> fluidTags = new HashMap<>();
        fluidTags.put(FluidTags.WATER, fluids(Fluids.WATER, Fluids.FLOWING_WATER));
        fluidTags.put(FluidTags.LAVA, fluids(Fluids.LAVA, Fluids.FLOWING_LAVA));

        freezeAndBind(Registries.BLOCK, blockTags);
        freezeAndBind(Registries.FLUID, fluidTags);
        freezeAndBind(Registries.ITEM, new HashMap<TagKey<Item>, List<RegistryEntry<Item>>>());
        done = true;
    }

    private static <T> void freezeAndBind(Registry<T> registry,
            Map<TagKey<T>, List<RegistryEntry<T>>> tags) {
        try {
            registry.freeze();
        } catch (IllegalStateException requiredTagsUnbound) {
            // Expected headless: the flag is already flipped, the reload below binds tags.
        }
        registry.startTagReload(new TagGroupLoader.RegistryTags<>(registry.getKey(), tags))
                .apply();
    }

    private static List<RegistryEntry<Block>> blocks(Block... blocks) {
        return Arrays.stream(blocks)
                .map(block -> (RegistryEntry<Block>) Registries.BLOCK.getEntry(block))
                .toList();
    }

    private static List<RegistryEntry<Fluid>> fluids(Fluid... fluids) {
        return Arrays.stream(fluids)
                .map(fluid -> (RegistryEntry<Fluid>) Registries.FLUID.getEntry(fluid))
                .toList();
    }
}
