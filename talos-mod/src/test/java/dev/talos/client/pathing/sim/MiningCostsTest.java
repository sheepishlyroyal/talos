package dev.talos.client.pathing.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Vanilla break-time math for the planner's tool-aware mining edge costs. */
class MiningCostsTest {
    private static final BlockPos ORIGIN = BlockPos.ORIGIN;

    @BeforeAll
    static void bootstrap() {
        Bootstraps.ensure();
    }

    private static int ticks(net.minecraft.block.BlockState state, ItemStack... tools) {
        return MiningCosts.breakTicks(state, new FakeWorld(), ORIGIN, List.of(tools));
    }

    @Test
    void stoneBareHandedIsPunitive() {
        // Stone requires a pickaxe: 1 / 1.5 / 100 per tick -> 150 ticks (7.5s), as in vanilla.
        assertEquals(150, ticks(Blocks.STONE.getDefaultState()));
    }

    @Test
    void stoneWithIronPickaxeIsQuick() {
        // Iron pickaxe speed 6, suitable: 6 / 1.5 / 30 -> 7.5 -> 8 ticks.
        assertEquals(8, ticks(Blocks.STONE.getDefaultState(),
                new ItemStack(Items.IRON_PICKAXE)));
    }

    @Test
    void dirtBareHandedMatchesVanilla() {
        // No tool required: 1 / 0.5 / 30 -> 15 ticks (0.75s).
        assertEquals(15, ticks(Blocks.DIRT.getDefaultState()));
    }

    @Test
    void bestToolWins() {
        int shovel = ticks(Blocks.DIRT.getDefaultState(), new ItemStack(Items.IRON_SHOVEL),
                new ItemStack(Items.WOODEN_PICKAXE));
        assertTrue(shovel <= 3, "iron shovel digs dirt fast: " + shovel);
    }

    @Test
    void obsidianBareHandedIsEffectivelyNever() {
        int bare = ticks(Blocks.OBSIDIAN.getDefaultState());
        assertTrue(bare >= 5000, "obsidian by hand: " + bare);
        int diamond = ticks(Blocks.OBSIDIAN.getDefaultState(),
                new ItemStack(Items.DIAMOND_PICKAXE));
        assertTrue(diamond < 200, "diamond pick makes it viable: " + diamond);
    }

    @Test
    void bedrockIsUnbreakable() {
        assertEquals(MiningCosts.UNBREAKABLE_TICKS, ticks(Blocks.BEDROCK.getDefaultState()));
    }
}
