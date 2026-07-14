package dev.talos.client.pathing.talos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Bounded client-world A* whose nodes are player stand positions. */
public final class AStarPathfinder {
    public static final int DEFAULT_NODE_CAP = 10_000;
    public static final long DEFAULT_TIME_BUDGET_NANOS = 8_000_000L;
    private static final int MAX_FALL = 4;
    private static final double SQRT_TWO = Math.sqrt(2.0);
    private static final Direction[] CARDINALS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private final ClientLevel world;
    private final int nodeCap;
    private final long timeBudgetNanos;
    private final LocalPlayer player;
    private final boolean hasPlaceableBlocks;
    private final boolean allowMining;

    public AStarPathfinder(ClientLevel world) {
        this(world, null, true, DEFAULT_NODE_CAP, DEFAULT_TIME_BUDGET_NANOS);
    }

    public AStarPathfinder(ClientLevel world, LocalPlayer player) {
        this(world, player, true, DEFAULT_NODE_CAP, DEFAULT_TIME_BUDGET_NANOS);
    }

    public AStarPathfinder(ClientLevel world, LocalPlayer player, boolean allowMining) {
        this(world, player, allowMining, DEFAULT_NODE_CAP, DEFAULT_TIME_BUDGET_NANOS);
    }

    AStarPathfinder(ClientLevel world, LocalPlayer player, boolean allowMining,
                    int nodeCap, long timeBudgetNanos) {
        this.world = world;
        this.player = player;
        this.nodeCap = nodeCap;
        this.timeBudgetNanos = timeBudgetNanos;
        this.allowMining = allowMining;
        this.hasPlaceableBlocks = player != null && player.getInventory().getNonEquipmentItems().stream()
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
            if (isStandable(pos.above(dy))) return pos.above(dy).immutable();
            if (dy > 0 && isStandable(pos.below(dy))) return pos.below(dy).immutable();
        }
        return null;
    }

    private List<Move> neighbors(BlockPos from) {
        List<Move> result = new ArrayList<>(16);
        BlockPos pillar = from.above();
        if (hasPlaceableBlocks && isLoaded(pillar.above()) && isPassable(pillar)
                && isPassable(pillar.above())) {
            result.add(new Move(pillar.immutable(), movementCost(from, pillar, MoveType.PLACE)));
        }
        Map<Direction, BlockPos> cardinalDestinations = new HashMap<>();
        for (Direction direction : CARDINALS) {
            BlockPos destination = resolveMove(from, direction.getStepX(), direction.getStepZ());
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
                    && !hasSafeSupport(destination.below());
            BlockPos xBridgeSupport = from.below().offset(diagonal[0], 0, 0);
            // Normal diagonals retain the strict two-corridor corner check.  A bridge
            // may create its missing X-side support as the first half of an L-shaped
            // speedbridge cycle, but still requires open landing and head cells.
            boolean openBridgeLanding = diagonalBridge && hasPlaceableBlocks
                    && isPassable(destination) && isPassable(destination.above())
                    && (hasSafeSupport(xBridgeSupport) || isPassable(xBridgeSupport));
            boolean clearNormalDiagonal = cardinalDestinations.containsKey(xDirection)
                    && cardinalDestinations.containsKey(zDirection);
            if (destination != null && (clearNormalDiagonal || openBridgeLanding)) {
                MoveType type = classify(from, destination, true);
                // Crawling diagonally can clip the low corner before the pose settles.
                if (type != MoveType.CRAWL) {
                    result.add(new Move(destination, movementCost(from, destination, type)));
                }
            }
        }

        // Fluids are volumes rather than floors: allow deliberate ascent and descent.
        if (isSwimFluid(from) || isSwimFluid(from.below())) {
            BlockPos up = from.above();
            if (isLegalSwimDestination(from, up)) result.add(new Move(up.immutable(), movementCost(from, up, MoveType.SWIM)));
            BlockPos down = from.below();
            if (isLegalSwimDestination(from, down)) result.add(new Move(down.immutable(), movementCost(from, down, MoveType.SWIM)));
        }
        return result;
    }

    private void addJumpMoves(List<Move> result, BlockPos from, Direction direction) {
        for (int distance = 2; distance <= 4; distance++) {
            BlockPos same = from.relative(direction, distance);
            BlockPos lower = same.below();
            BlockPos landing = isStandable(same) ? same : isStandable(lower) ? lower : null;
            if (landing == null || !hasGap(from, direction, distance)
                    || !jumpArcClear(from, direction, distance, landing)) continue;
            MoveType type = distance == 2 ? MoveType.JUMP : MoveType.SPRINT_JUMP;
            result.add(new Move(landing.immutable(), movementCost(from, landing, type)));
        }
    }

    private boolean hasGap(BlockPos from, Direction direction, int distance) {
        for (int step = 1; step < distance; step++) {
            if (!hasSafeSupport(from.relative(direction, step).below())) return true;
        }
        return false;
    }

    private boolean jumpArcClear(BlockPos from, Direction direction, int distance, BlockPos landing) {
        if (!isPassable(from.above())) return false;
        for (int step = 1; step < distance; step++) {
            BlockPos path = from.relative(direction, step);
            // The player rises during the middle of the arc, so reserve foot, body and head cells.
            if (!isLoaded(path) || !isPassable(path) || !isPassable(path.above())
                    || !isPassable(path.above(2))) return false;
        }
        return isPassable(landing) && isPassable(landing.above());
    }

    private BlockPos resolveMove(BlockPos from, int dx, int dz) {
        BlockPos horizontal = from.offset(dx, 0, dz);
        if (!isLoaded(horizontal)) return null;
        if (isSwimFluid(horizontal)) {
            return isLegalSwimDestination(from, horizontal) ? horizontal.immutable() : null;
        }
        if (isStandable(horizontal)) return horizontal.immutable();

        // Sneaking lets the follower enter a one-block-high corridor. Keep this a
        // cardinal grid edge; diagonal crawl corner-cutting is intentionally excluded.
        if (isCrawlStandable(horizontal)) return horizontal.immutable();

        // A two-block-high mine-through corridor is a legal edge. The closed-form
        // break estimate below makes A* prefer walking around whenever that is faster.
        if (allowMining && canMine(horizontal) && canMine(horizontal.above())
                && hasSolidSupport(horizontal.below())) return horizontal.immutable();

        // Bridge one block over a gap only when the inventory can actually supply it.
        if (hasPlaceableBlocks && isPassable(horizontal) && isPassable(horizontal.above())
                && !hasSolidSupport(horizontal.below())) return horizontal.immutable();

        BlockPos stepUp = horizontal.above();
        if (isStandable(stepUp)) {
            return stepUp.immutable();
        }
        for (int fall = 1; fall <= MAX_FALL; fall++) {
            BlockPos lower = horizontal.below(fall);
            if (!isLoaded(lower)) return null;
            if (isStandable(lower)) return lower.immutable();
            if (!isPassable(lower)) return null;
        }
        return null;
    }

    public boolean isStandable(BlockPos pos) {
        if (!isLoaded(pos) || !isLoaded(pos.below()) || !isLoaded(pos.above())) return false;
        if (isHazard(pos) || isHazard(pos.above()) || isHazard(pos.below())) return false;
        if (!isPassable(pos) || !isPassable(pos.above())) return false;
        if (isSwimFluid(pos) || isSwimFluid(pos.above())) return true;
        BlockState support = world.getBlockState(pos.below());
        if (support.isRedstoneConductor(world, pos.below())) return true;
        // Partial-height collision surfaces (notably stairs and slabs) are valid
        // floors even though their collision top is below the old full-block cutoff.
        // Open trapdoors/gates are handled as passages, never as dependable footing.
        if (support.getBlock() instanceof TrapDoorBlock || support.getBlock() instanceof FenceGateBlock) {
            if (support.hasProperty(BlockStateProperties.OPEN) && support.getValue(BlockStateProperties.OPEN)) return false;
        }
        var shape = support.getCollisionShape(world, pos.below());
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) > 0.0;
    }

    private boolean isPassable(BlockPos pos) {
        if (!isLoaded(pos) || isHazard(pos)) return false;
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof TrapDoorBlock || state.getBlock() instanceof FenceGateBlock) {
            return state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        }
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private boolean isSwimmable(BlockPos pos) {
        return isLoaded(pos) && isSwimFluid(pos) && isPassable(pos);
    }

    private boolean isLegalSwimDestination(BlockPos from, BlockPos pos) {
        if (!isSwimmable(pos)) return false;
        boolean deepEnough = isLoaded(pos.below()) && isSwimFluid(pos.below());
        boolean continuing = isSwimFluid(from) && player != null && (player.isSwimming()
                || player.isUnderWater() || player.isInWater() || player.isInLava());
        return deepEnough || continuing;
    }

    private boolean isHazard(BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE);
    }

    private MoveType classify(BlockPos from, BlockPos to, boolean diagonal) {
        if (isSwimFluid(to) && isLegalSwimDestination(from, to)) return MoveType.SWIM;
        if (isCrawlStandable(to)) return MoveType.CRAWL;
        if (!isPassable(to) || !isPassable(to.above())) return MoveType.BREAK;
        if (!hasSafeSupport(to.below())) return MoveType.PLACE;
        if (to.getY() > from.getY()) {
            // The destination still has the two body cells required to stand, but a
            // ceiling immediately above it prevents a normal jump arc. Treat this as
            // an intentional head-bump step rather than making A* shy away from it.
            if (!isPassable(to.above(2))) return MoveType.HEAD_HIT_STEP;
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
        BlockState support = world.getBlockState(to.below());
        BlockState through = world.getBlockState(to);
        double velocityMultiplier = Math.min(support.getBlock().getSpeedFactor(),
                through.getBlock().getSpeedFactor());
        // Keep every terrain edge finite even for unusual modded blocks reporting zero.
        double terrainMultiplier = 1.0 / Math.max(0.05, Math.min(1.0, velocityMultiplier));
        // Cobweb slowdown is applied by entity collision while moving through its cell;
        // its block velocity multiplier alone does not represent vanilla's severe drag.
        if (through.is(Blocks.COBWEB)) terrainMultiplier *= 8.0;

        double travelTime = (horizontalDistance / effectiveSpeed(type, to)
                + verticalDistance * (type == MoveType.HEAD_HIT_STEP ? 0.08 : 0.12))
                * terrainMultiplier;
        // Ice remains attractive for meaningfully shorter routes, but loses close ties.
        if (support.getBlock().getFriction() > 0.6F) travelTime *= 1.20;
        double cost = travelTime;
        // Gap jumps include alignment/takeoff and landing recovery. Raw airborne speed
        // alone otherwise makes them look cheaper than an equally quick ground route.
        if (type == MoveType.JUMP) cost += 0.55;
        if (type == MoveType.SPRINT_JUMP) cost += 0.75;
        if (type == MoveType.PLACE) cost += estimatePlaceTimeSeconds(to.getY() > from.getY());
        if (type != MoveType.CRAWL) {
            if (!isPassable(to)) cost += estimateBreakTimeSeconds(to);
            if (!isPassable(to.above())) cost += estimateBreakTimeSeconds(to.above());
        }
        return cost;
    }

    private double effectiveSpeed(MoveType type, BlockPos destination) {
        return switch (type) {
            case WALK, DIAGONAL -> 5.6;
            case CRAWL -> 1.3;
            case BREAK, JUMP -> 4.317;
            case SPRINT_JUMP -> 6.0;
            case STAIR_STEP -> 5.2;
            case HEAD_HIT_STEP -> 5.4;
            case PLACE -> 1.3;
            case SWIM -> isWater(destination.below()) ? 3.9 : 1.8;
        };
    }

    public double estimateBreakTimeSeconds(BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        float hardness = state.getDestroySpeed(world, pos);
        if (state.isAir()) return 0.0;
        if (hardness < 0.0F || player == null) return Double.POSITIVE_INFINITY;
        ItemStack best = ItemStack.EMPTY;
        double bestSpeed = 1.0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            double speed = effectiveMiningSpeed(stack, state);
            if (speed > bestSpeed) { bestSpeed = speed; best = stack; }
        }
        boolean harvestable = best.isCorrectToolForDrops(state) || !state.requiresCorrectToolForDrops();
        double damagePerTick = bestSpeed / hardness / (harvestable ? 30.0 : 100.0);
        return Math.ceil(1.0 / damagePerTick) / 20.0;
    }

    public double estimatePlaceTimeSeconds(boolean pillaring) {
        if (!hasPlaceableBlocks) return Double.POSITIVE_INFINITY;
        return pillaring ? 0.50 : 0.35;
    }

    public double effectiveMiningSpeed(ItemStack stack, BlockState state) {
        double speed = Math.max(1.0, stack.getDestroySpeed(state));
        int efficiency = 0;
        for (var entry : stack.getEnchantments().entrySet()) {
            if (entry.getKey().is(Enchantments.EFFICIENCY)) {
                efficiency = entry.getIntValue();
                break;
            }
        }
        if (speed > 1.0 && efficiency > 0) speed += efficiency * efficiency + 1.0;
        if (player != null) {
            var haste = player.getEffect(MobEffects.HASTE);
            if (haste != null) speed *= 1.0 + 0.2 * (haste.getAmplifier() + 1);
            var fatigue = player.getEffect(MobEffects.MINING_FATIGUE);
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
        return state.isRedstoneConductor(world, pos)
                || (!state.getCollisionShape(world, pos).isEmpty()
                && state.getCollisionShape(world, pos).max(Direction.Axis.Y) > 0.0);
    }

    private boolean hasSafeSupport(BlockPos pos) {
        if (!hasSolidSupport(pos)) return false;
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof TrapDoorBlock || state.getBlock() instanceof FenceGateBlock) {
            return !(state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN));
        }
        return true;
    }

    private boolean isWater(BlockPos pos) {
        return world.getBlockState(pos).getFluidState().is(FluidTags.WATER);
    }

    private boolean isLava(BlockPos pos) {
        return world.getBlockState(pos).getFluidState().is(FluidTags.LAVA);
    }

    private boolean isSwimFluid(BlockPos pos) { return isWater(pos) || isLava(pos); }

    private boolean isCrawlStandable(BlockPos pos) {
        return isLoaded(pos) && isLoaded(pos.above()) && isPassable(pos)
                && !isPassable(pos.above()) && hasSafeSupport(pos.below()) && !isHazard(pos.above());
    }

    private boolean isLoaded(BlockPos pos) {
        return world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
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
    private enum MoveType {
        WALK, DIAGONAL, JUMP, SPRINT_JUMP, SWIM, CRAWL, PLACE, BREAK, STAIR_STEP,
        HEAD_HIT_STEP
    }

    public record SearchResult(List<BlockPos> path, boolean reachesGoal, String detail) { }

    // Elytra and interactive door opening remain outside the ground-navigation model.
}
