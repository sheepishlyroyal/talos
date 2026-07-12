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
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
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
    private final boolean allowMining;

    public AStarPathfinder(ClientWorld world) {
        this(world, null, true, DEFAULT_NODE_CAP, DEFAULT_TIME_BUDGET_NANOS);
    }

    public AStarPathfinder(ClientWorld world, ClientPlayerEntity player) {
        this(world, player, true, DEFAULT_NODE_CAP, DEFAULT_TIME_BUDGET_NANOS);
    }

    public AStarPathfinder(ClientWorld world, ClientPlayerEntity player, boolean allowMining) {
        this(world, player, allowMining, DEFAULT_NODE_CAP, DEFAULT_TIME_BUDGET_NANOS);
    }

    AStarPathfinder(ClientWorld world, ClientPlayerEntity player, boolean allowMining,
                    int nodeCap, long timeBudgetNanos) {
        this.world = world;
        this.player = player;
        this.nodeCap = nodeCap;
        this.timeBudgetNanos = timeBudgetNanos;
        this.allowMining = allowMining;
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
        List<Move> result = new ArrayList<>(16);
        BlockPos pillar = from.up();
        if (hasPlaceableBlocks && isLoaded(pillar.up()) && isPassable(pillar)
                && isPassable(pillar.up())) {
            result.add(new Move(pillar.toImmutable(), movementCost(from, pillar, MoveType.PLACE)));
        }
        Map<Direction, BlockPos> cardinalDestinations = new HashMap<>();
        for (Direction direction : CARDINALS) {
            BlockPos destination = resolveMove(from, direction.getOffsetX(), direction.getOffsetZ());
            if (destination != null) {
                cardinalDestinations.put(direction, destination);
                result.add(new Move(destination, movementCost(from, destination, classify(from, destination, false))));
            }
            addJumpMoves(result, from, direction);
        }

        int[][] diagonals = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] diagonal : diagonals) {
            Direction xDirection = diagonal[0] > 0 ? Direction.EAST : Direction.WEST;
            Direction zDirection = diagonal[1] > 0 ? Direction.SOUTH : Direction.NORTH;
            BlockPos destination = resolveMove(from, diagonal[0], diagonal[1]);
            boolean diagonalBridge = destination != null && destination.getY() == from.getY()
                    && !hasSafeSupport(destination.down());
            BlockPos xBridgeSupport = from.down().add(diagonal[0], 0, 0);
            // Normal diagonals retain the strict two-corridor corner check.  A bridge
            // may create its missing X-side support as the first half of an L-shaped
            // speedbridge cycle, but still requires open landing and head cells.
            boolean openBridgeLanding = diagonalBridge && hasPlaceableBlocks
                    && isPassable(destination) && isPassable(destination.up())
                    && (hasSafeSupport(xBridgeSupport) || isPassable(xBridgeSupport));
            boolean clearNormalDiagonal = cardinalDestinations.containsKey(xDirection)
                    && cardinalDestinations.containsKey(zDirection);
            if (destination != null && (clearNormalDiagonal || openBridgeLanding)) {
                result.add(new Move(destination,
                        movementCost(from, destination, classify(from, destination, true))));
            }
        }

        // Water is a volume rather than a floor: allow deliberate ascent and descent.
        if (isWater(from) || isWater(from.down())) {
            BlockPos up = from.up();
            if (isLegalSwimDestination(from, up)) result.add(new Move(up.toImmutable(), movementCost(from, up, MoveType.SWIM)));
            BlockPos down = from.down();
            if (isLegalSwimDestination(from, down)) result.add(new Move(down.toImmutable(), movementCost(from, down, MoveType.SWIM)));
        }
        return result;
    }

    private void addJumpMoves(List<Move> result, BlockPos from, Direction direction) {
        for (int distance = 2; distance <= 4; distance++) {
            BlockPos same = from.offset(direction, distance);
            BlockPos lower = same.down();
            BlockPos landing = isStandable(same) ? same : isStandable(lower) ? lower : null;
            if (landing == null || !hasGap(from, direction, distance)
                    || !jumpArcClear(from, direction, distance, landing)) continue;
            MoveType type = distance == 2 ? MoveType.JUMP : MoveType.SPRINT_JUMP;
            result.add(new Move(landing.toImmutable(), movementCost(from, landing, type)));
        }
    }

    private boolean hasGap(BlockPos from, Direction direction, int distance) {
        for (int step = 1; step < distance; step++) {
            if (!hasSafeSupport(from.offset(direction, step).down())) return true;
        }
        return false;
    }

    private boolean jumpArcClear(BlockPos from, Direction direction, int distance, BlockPos landing) {
        if (!isPassable(from.up())) return false;
        for (int step = 1; step < distance; step++) {
            BlockPos path = from.offset(direction, step);
            // The player rises during the middle of the arc, so reserve foot, body and head cells.
            if (!isLoaded(path) || !isPassable(path) || !isPassable(path.up())
                    || !isPassable(path.up(2))) return false;
        }
        return isPassable(landing) && isPassable(landing.up());
    }

    private BlockPos resolveMove(BlockPos from, int dx, int dz) {
        BlockPos horizontal = from.add(dx, 0, dz);
        if (!isLoaded(horizontal)) return null;
        if (isWater(horizontal)) {
            return isLegalSwimDestination(from, horizontal) ? horizontal.toImmutable() : null;
        }
        if (isStandable(horizontal)) return horizontal.toImmutable();

        // A two-block-high mine-through corridor is a legal edge. The closed-form
        // break estimate below makes A* prefer walking around whenever that is faster.
        if (allowMining && canMine(horizontal) && canMine(horizontal.up())
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
        if (isWater(pos) || isWater(pos.up())) return true;
        BlockState support = world.getBlockState(pos.down());
        if (support.isSolidBlock(world, pos.down())) return true;
        // Partial-height collision surfaces (notably stairs and slabs) are valid
        // floors even though their collision top is below the old full-block cutoff.
        // Open trapdoors/gates are handled as passages, never as dependable footing.
        if (support.getBlock() instanceof TrapdoorBlock || support.getBlock() instanceof FenceGateBlock) {
            if (support.contains(Properties.OPEN) && support.get(Properties.OPEN)) return false;
        }
        var shape = support.getCollisionShape(world, pos.down());
        return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) > 0.0;
    }

    private boolean isPassable(BlockPos pos) {
        if (!isLoaded(pos) || isHazard(pos)) return false;
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof TrapdoorBlock || state.getBlock() instanceof FenceGateBlock) {
            return state.contains(Properties.OPEN) && state.get(Properties.OPEN);
        }
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private boolean isSwimmable(BlockPos pos) {
        return isLoaded(pos) && isWater(pos) && isPassable(pos) && isPassable(pos.up());
    }

    private boolean isLegalSwimDestination(BlockPos from, BlockPos pos) {
        if (!isSwimmable(pos)) return false;
        boolean deepEnough = isLoaded(pos.down()) && isWater(pos.down());
        boolean continuing = isWater(from) && player != null && (player.isSwimming()
                || player.isSubmergedInWater() || player.isTouchingWater());
        return deepEnough || continuing;
    }

    private boolean isHazard(BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isOf(Blocks.LAVA) || state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE);
    }

    private MoveType classify(BlockPos from, BlockPos to, boolean diagonal) {
        if (isWater(to) && isLegalSwimDestination(from, to)) return MoveType.SWIM;
        if (!isPassable(to) || !isPassable(to.up())) return MoveType.BREAK;
        if (!hasSafeSupport(to.down())) return MoveType.PLACE;
        if (to.getY() > from.getY()) {
            // Supported one-block ascents are continuous spam-jump/step movement, not
            // discrete gap jumps with takeoff and landing recovery.
            return MoveType.STAIR_STEP;
        }
        return diagonal ? MoveType.DIAGONAL : MoveType.WALK;
    }

    private double movementCost(BlockPos from, BlockPos to, MoveType type) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double verticalDistance = Math.abs(to.getY() - from.getY());
        double cost = horizontalDistance / effectiveSpeed(type, to) + verticalDistance * 0.12;
        // Gap jumps include alignment/takeoff and landing recovery. Raw airborne speed
        // alone otherwise makes them look cheaper than an equally quick ground route.
        if (type == MoveType.JUMP) cost += 0.55;
        if (type == MoveType.SPRINT_JUMP) cost += 0.75;
        if (type == MoveType.PLACE) cost += estimatePlaceTimeSeconds(to.getY() > from.getY());
        if (!isPassable(to)) cost += estimateBreakTimeSeconds(to);
        if (!isPassable(to.up())) cost += estimateBreakTimeSeconds(to.up());
        return cost;
    }

    private double effectiveSpeed(MoveType type, BlockPos destination) {
        return switch (type) {
            case WALK, DIAGONAL -> 5.6;
            case BREAK, JUMP -> 4.317;
            case SPRINT_JUMP -> 6.0;
            case STAIR_STEP -> 5.2;
            case PLACE -> 1.3;
            case SWIM -> isWater(destination.down()) ? 3.9 : 1.8;
        };
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
        return pillaring ? 0.50 : 0.35;
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
                && state.getCollisionShape(world, pos).getMax(Direction.Axis.Y) > 0.0);
    }

    private boolean hasSafeSupport(BlockPos pos) {
        if (!hasSolidSupport(pos)) return false;
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof TrapdoorBlock || state.getBlock() instanceof FenceGateBlock) {
            return !(state.contains(Properties.OPEN) && state.get(Properties.OPEN));
        }
        return true;
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
        // Optimistic travel time at the fastest modeled horizontal speed.
        return octile / 6.0 + Math.abs(pos.getY() - target.getY()) * 0.12;
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
    private enum MoveType { WALK, DIAGONAL, JUMP, SPRINT_JUMP, SWIM, PLACE, BREAK, STAIR_STEP }

    public record SearchResult(List<BlockPos> path, boolean reachesGoal, String detail) { }

    // Elytra and interactive door opening remain outside the ground-navigation model.
}
