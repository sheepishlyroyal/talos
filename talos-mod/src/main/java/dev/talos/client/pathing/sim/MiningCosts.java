package dev.talos.client.pathing.sim;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Vanilla block-breaking time from hardness and the best available tool. Vanilla's per-tick
 * break progress is speed / hardness / 30 with a usable tool (or none required), and
 * speed / hardness / 100 when the block requires a tool the player lacks.
 */
public final class MiningCosts {
    /** Break times above this are treated as "effectively never mine this". */
    public static final int UNBREAKABLE_TICKS = 100_000;

    private MiningCosts() {}

    /** Ticks to break {@code state} with the fastest of {@code tools} (bare hand included). */
    public static int breakTicks(BlockState state, BlockGetter world, BlockPos pos,
            List<ItemStack> tools) {
        float hardness = state.getDestroySpeed(world, pos);
        if (hardness < 0.0F) return UNBREAKABLE_TICKS;          // bedrock-class
        if (hardness == 0.0F) return 1;                          // insta-break
        boolean toolRequired = state.requiresCorrectToolForDrops();
        double bestPerTick = 1.0 / hardness / (toolRequired ? 100.0 : 30.0); // bare hand
        if (tools != null) {
            for (ItemStack stack : tools) {
                if (stack == null || stack.isEmpty()) continue;
                float speed = stack.getDestroySpeed(state);
                boolean suitable = !toolRequired || stack.isCorrectToolForDrops(state);
                double perTick = speed / hardness / (suitable ? 30.0 : 100.0);
                if (perTick > bestPerTick) bestPerTick = perTick;
            }
        }
        if (bestPerTick >= 1.0) return 1;
        return (int) Math.min(UNBREAKABLE_TICKS, Math.ceil(1.0 / bestPerTick));
    }
}
