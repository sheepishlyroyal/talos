package dev.glade.client.pathing.glade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Bounded client-world A* whose nodes are player stand positions. */
public final class AStarPathfinder {
    public static final int DEFAULT_NODE_CAP = 10_000;
    public static final long DEFAULT_TIME_BUDGET_NANOS = 8_000_000L;
    private static final int MAX_FALL = 4;
    private static final double SQRT_TWO = Math.sqrt(2.0);
    private static final Direction[] CARDINALS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private final ClientWorld world;
    private final int nodeCap;
    private final long timeBudgetNanos;
    private final ClientPlayerEntity player;
    private final boolean hasPlaceableBlocks;

    public AStarPathfinder(ClientWorld world) {
        this(world, null, DEFAULT_NODE_CAP, DEFAULT_TIME_BUDGET_NANOS);
    }

    public AStarPathfinder(ClientWorld world, ClientPlayerEntity player) {
        this(world, player, DEFAULT_NODE_CAP, DEFAULT_TIME_BUDGET_NANOS);
    }

    AStarPathfinder(ClientWorld world, ClientPlayerEntity player, int nodeCap, long timeBudgetNanos) {
        this.world = world;
        this.player = player;
        this.nodeCap = nodeCap;
        this.timeBudgetNanos = timeBudgetNanos;
        this.hasPlaceableBlocks = player != null && player.getInventory().getMainStacks().stream()
                .anyMatch(stack -> !stack.isEmpty() && stack.getItem() instanceof BlockItem);
    }

    public SearchResult find(BlockPos requestedStart, Predicate<BlockPos> isGoal,
                             BlockPos heuristicTarget) {
        long deadline = System.nanoTime() + timeBudgetNanos;
        boolean targetLoaded = isLoaded(heuristicTarget);
        BlockPos start = normalizeStart(requestedStart);
        if (start == null) {
            return new SearchResult(List.of(), false, "No standable start position");
        }

        PriorityQueue<OpenNode> open = new PriorityQueue<>();
        Map<BlockPos, Double> costs = new HashMap<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        double startH = heuristic(start, heuristicTarget);
        open.add(new OpenNode(start, 0.0, startH));
        costs.put(start, 0.0);
        BlockPos best = start;
        double bestH = startH;
        int expanded = 0;

        while (!open.isEmpty() && expanded < nodeCap && System.nanoTime() < deadline) {
            OpenNode current = open.poll();
            if (current.cost() > costs.getOrDefault(current.pos(), Double.POSITIVE_INFINITY)) {
                continue;
            }
            expanded++;
            if (isGoal.test(current.pos())) {
                return new SearchResult(reconstruct(current.pos(), parents), true,
                        "Path found (" + expanded + " nodes)");
            }
            double currentH = heuristic(current.pos(), heuristicTarget);
            if (currentH < bestH) {
                bestH = currentH;
                best = current.pos();
            }

            for (Move move : neighbors(current.pos())) {
                double nextCost = current.cost() + move.cost();
                if (nextCost >= costs.getOrDefault(move.pos(), Double.POSITIVE_INFINITY)) {
                    continue;
                }
                costs.put(move.pos(), nextCost);
                parents.put(move.pos(), current.pos());
                open.add(new OpenNode(move.pos(), nextCost,
                        nextCost + heuristic(move.pos(), heuristicTarget)));
            }
        }

        List<BlockPos> partial = reconstruct(best, parents);
        String reason = !targetLoaded ? "goal is in unloaded terrain" :
                expanded >= nodeCap ? "node cap reached" :
                open.isEmpty() ? "goal unreachable in loaded terrain" : "time budget reached";
        return new SearchResult(partial, false,
                "Best partial path; " + reason + " (" + expanded + " nodes)");
    }

    private BlockPos normalizeStart(BlockPos pos) {
        for (int dy = 0; dy <= 2; dy++) {
            if (isStandable(pos.up(dy))) return pos.up(dy).toImmutable();
            if (dy > 0 && isStandable(pos.down(dy))) return pos.down(dy).toImmutable();
        }
        return null;
    }

    private List<Move> neighbors(BlockPos from) {
        List<Move> result = new ArrayList<>(8);
        BlockPos pillar = from.up();
        if (hasPlaceableBlocks && isLoaded(pillar.up()) && isPassable(pillar)
                && isPassable(pillar.up())) {
            result.add(new Move(pillar.toImmutable(), movementCost(from, pillar, false)));
        }
        Map<Direction, BlockPos> cardinalDestinations = new HashMap<>();
        for (Direction direction : CARDINALS) {
            BlockPos destination = resolveMove(from, direction.getOffsetX(), direction.getOffsetZ());
            if (destination != null) {
                cardinalDestinations.put(direction, destination);
                result.add(new Move(destination, movementCost(from, destination, false)));
            }
        }

        int[][] diagonals = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] diagonal : diagonals) {
            Direction xDirection = diagonal[0] > 0 ? Direction.EAST : Direction.WEST;
            Direction zDirection = diagonal[1] > 0 ? Direction.SOUTH : Direction.NORTH;
            // Both side corridors must be traversable, preventing diagonal corner clipping.
            if (!cardinalDestinations.containsKey(xDirection)
                    || !cardinalDestinations.containsKey(zDirection)) {
                continue;
            }
            BlockPos destination = resolveMove(from, diagonal[0], diagonal[1]);
            if (destination != null) {
                result.add(new Move(destination, movementCost(from, destination, true)));
            }
        }
        return result;
    }

    private BlockPos resolveMove(BlockPos from, int dx, int dz) {
        BlockPos horizontal = from.add(dx, 0, dz);
        if (!isLoaded(horizontal)) return null;
        if (isStandable(horizontal)) return horizontal.toImmutable();

        // A two-block-high mine-through corridor is a legal edge. The closed-form
        // break estimate below makes A* prefer walking around whenever that is faster.
        if (canMine(horizontal) && canMine(horizontal.up())
                && hasSolidSupport(horizontal.down())) return horizontal.toImmutable();

        // Bridge one block over a gap only when the inventory can actually supply it.
        if (hasPlaceableBlocks && isPassable(horizontal) && isPassable(horizontal.up())
                && !hasSolidSupport(horizontal.down())) return horizontal.toImmutable();

        BlockPos stepUp = horizontal.up();
        if (isStandable(stepUp)) {
            return stepUp.toImmutable();
        }
        for (int fall = 1; fall <= MAX_FALL; fall++) {
            BlockPos lower = horizontal.down(fall);
            if (!isLoaded(lower)) return null;
            if (isStandable(lower)) return lower.toImmutable();
            if (!isPassable(lower)) return null;
        }
        return null;
    }

    public boolean isStandable(BlockPos pos) {
        if (!isLoaded(pos) || !isLoaded(pos.down()) || !isLoaded(pos.up())) return false;
        if (isHazard(pos) || isHazard(pos.up()) || isHazard(pos.down())) return false;
        if (!isPassable(pos) || !isPassable(pos.up())) return false;
        BlockState support = world.getBlockState(pos.down());
        if (support.isSolidBlock(world, pos.down())) return true;
        var shape = support.getCollisionShape(world, pos.down());
        return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) >= 0.999;
    }

    private boolean isPassable(BlockPos pos) {
        return !isHazard(pos) && world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private boolean isHazard(BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isOf(Blocks.LAVA) || state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE);
    }

    private double movementCost(BlockPos from, BlockPos to, boolean diagonal) {
        double horizontal = diagonal ? SQRT_TWO : 1.0;
        double vertical = Math.abs(to.getY() - from.getY()) * 0.35;
        double water = isWater(to) || isWater(to.up()) ? 2.5 : 1.0;
        // Flat cardinal edges are the straight-line sprintable primitive.
        double sprintDiscount = !diagonal && to.getY() == from.getY() ? 0.95 : 1.0;
        double seconds = (horizontal / 4.317 + vertical * 0.25) * water * sprintDiscount;
        if (!isPassable(to)) seconds += estimateBreakTimeSeconds(to);
        if (!isPassable(to.up())) seconds += estimateBreakTimeSeconds(to.up());
        if (!hasSolidSupport(to.down())) seconds += estimatePlaceTimeSeconds(false);
        if (to.getY() > from.getY() && !hasSolidSupport(to.down())) {
            seconds += estimatePlaceTimeSeconds(true);
        }
        return seconds;
    }

    public double estimateBreakTimeSeconds(BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        float hardness = state.getHardness(world, pos);
        if (state.isAir()) return 0.0;
        if (hardness < 0.0F || player == null) return Double.POSITIVE_INFINITY;
        ItemStack best = ItemStack.EMPTY;
        double bestSpeed = 1.0;
        for (ItemStack stack : player.getInventory().getMainStacks()) {
            double speed = effectiveMiningSpeed(stack, state);
            if (speed > bestSpeed) { bestSpeed = speed; best = stack; }
        }
        boolean harvestable = best.isSuitableFor(state) || !state.isToolRequired();
        double damagePerTick = bestSpeed / hardness / (harvestable ? 30.0 : 100.0);
        return Math.ceil(1.0 / damagePerTick) / 20.0;
    }

    public double estimatePlaceTimeSeconds(boolean pillaring) {
        if (!hasPlaceableBlocks) return Double.POSITIVE_INFINITY;
        return 0.20 + (pillaring ? 0.50 : 0.0);
    }

    public double effectiveMiningSpeed(ItemStack stack, BlockState state) {
        double speed = Math.max(1.0, stack.getMiningSpeedMultiplier(state));
        int efficiency = 0;
        for (var entry : stack.getEnchantments().getEnchantmentEntries()) {
            if (entry.getKey().matchesKey(Enchantments.EFFICIENCY)) {
                efficiency = entry.getIntValue();
                break;
            }
        }
        if (speed > 1.0 && efficiency > 0) speed += efficiency * efficiency + 1.0;
        if (player != null) {
            var haste = player.getStatusEffect(StatusEffects.HASTE);
            if (haste != null) speed *= 1.0 + 0.2 * (haste.getAmplifier() + 1);
            var fatigue = player.getStatusEffect(StatusEffects.MINING_FATIGUE);
            if (fatigue != null) {
                int level = fatigue.getAmplifier() + 1;
                speed *= switch (level) { case 1 -> 0.3; case 2 -> 0.09; case 3 -> 0.0027; default -> 0.00081; };
            }
        }
        return speed;
    }

    private boolean canMine(BlockPos pos) {
        return isLoaded(pos) && !isHazard(pos) && estimateBreakTimeSeconds(pos) < 30.0;
    }

    private boolean hasSolidSupport(BlockPos pos) {
        if (!isLoaded(pos)) return false;
        BlockState state = world.getBlockState(pos);
        return state.isSolidBlock(world, pos)
                || (!state.getCollisionShape(world, pos).isEmpty()
                && state.getCollisionShape(world, pos).getMax(Direction.Axis.Y) >= 0.999);
    }

    private boolean isWater(BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.WATER);
    }

    private boolean isLoaded(BlockPos pos) {
        return world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static double heuristic(BlockPos pos, BlockPos target) {
        int dx = Math.abs(pos.getX() - target.getX());
        int dz = Math.abs(pos.getZ() - target.getZ());
        int min = Math.min(dx, dz);
        double octile = dx + dz + (SQRT_TWO - 2.0) * min;
        return octile / 4.317 + Math.abs(pos.getY() - target.getY()) * 0.25;
    }

    private static List<BlockPos> reconstruct(BlockPos end, Map<BlockPos, BlockPos> parents) {
        List<BlockPos> path = new ArrayList<>();
        for (BlockPos cursor = end; cursor != null; cursor = parents.get(cursor)) {
            path.add(cursor);
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }

    private record OpenNode(BlockPos pos, double cost, double score) implements Comparable<OpenNode> {
        @Override public int compareTo(OpenNode other) { return Double.compare(score, other.score); }
    }
    private record Move(BlockPos pos, double cost) { }

    public record SearchResult(List<BlockPos> path, boolean reachesGoal, String detail) { }

    // TODO v1: parkour jumps, elytra, upward swimming, and doors.
}
