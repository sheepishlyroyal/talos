package dev.talos.client.pathing.sim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.CollisionView;

/**
 * Cheap block-grid A* that sketches a global corridor for far goals. Where
 * {@link SimPathfinder} validates every edge with a physics rollout, this only asks "could a
 * player plausibly stand here and step there" — no velocities, no rollouts, no edits. The
 * corridor is never followed directly: the deep planner funnels toward cells along it, so
 * coarse mistakes cost a local detour, not a faceplant.
 */
public final class CoarsePathfinder {
    // 200k coarse cells cover a ~3000-block corridor with room for lakes and cliff walls;
    // past that the goal is better treated as unreachable than worth more planning stall.
    private static final int NODE_CAP = 200_000;
    private static final int MAX_DROP = 3;
    private static final double DIAGONAL_COST = Math.sqrt(2.0);
    // Climbing terrain is slower than skirting it, but only mildly: a hill line should lose
    // to a flat line, not to a three-times-longer beach walk.
    private static final double STEP_UP_PENALTY = 0.4;
    private static final int[][] DIRECTIONS = {
            {0, 1}, {1, 1}, {1, 0}, {1, -1},
            {0, -1}, {-1, -1}, {-1, 0}, {-1, 1}
    };

    private CoarsePathfinder() {}

    /** Ordered start-to-end corridor; a best-effort partial when the goal was not reached. */
    public record Result(List<BlockPos> corridor, boolean reachedGoal, String detail) {}

    // CollisionView itself cannot answer "is this chunk loaded", so loadedness arrives as
    // an explicit predicate: the engine passes the real chunk map, FakeWorld tests default
    // to "always loaded" and stay untouched.
    private static final Predicate<BlockPos> ALWAYS_LOADED = pos -> true;

    /** Runs the whole search inline. Callers on the client thread should slice via begin(). */
    public static Result find(CollisionView world, BlockPos start, BlockPos goal,
            Predicate<BlockPos> isGoal, Set<BlockPos> blacklist, long timeBudgetNanos) {
        return find(world, start, goal, isGoal, blacklist, timeBudgetNanos, ALWAYS_LOADED);
    }

    /** Inline variant with an explicit loaded-chunk predicate (see {@link #begin}). */
    public static Result find(CollisionView world, BlockPos start, BlockPos goal,
            Predicate<BlockPos> isGoal, Set<BlockPos> blacklist, long timeBudgetNanos,
            Predicate<BlockPos> loaded) {
        Search search = begin(world, start, goal, isGoal, blacklist, timeBudgetNanos, loaded);
        while (!search.isFinished()) search.advance(timeBudgetNanos);
        return search.result();
    }

    /** Creates a resumable search; call {@link Search#advance} from successive ticks. */
    public static Search begin(CollisionView world, BlockPos start, BlockPos goal,
            Predicate<BlockPos> isGoal, Set<BlockPos> blacklist, long timeBudgetNanos) {
        return begin(world, start, goal, isGoal, blacklist, timeBudgetNanos, ALWAYS_LOADED);
    }

    /**
     * Resumable search that treats cells outside {@code loaded} as non-walkable. Unloaded
     * client chunks read back as pure air — indistinguishable from a bottomless void — so
     * refusing them keeps the corridor honest about where the world is actually known.
     */
    public static Search begin(CollisionView world, BlockPos start, BlockPos goal,
            Predicate<BlockPos> isGoal, Set<BlockPos> blacklist, long timeBudgetNanos,
            Predicate<BlockPos> loaded) {
        return new Search(world, start, goal, isGoal, blacklist, timeBudgetNanos, loaded);
    }

    public static final class Search {
        private final CollisionView world;
        private final BlockPos goal;
        private final Predicate<BlockPos> isGoal;
        private final Set<BlockPos> blacklist;
        private final long timeBudgetNanos;
        private final Predicate<BlockPos> loaded;
        private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator
                .comparingDouble(Node::f).thenComparingDouble(Node::h)
                .thenComparingLong(Node::sequence));
        // Long-packed cell keys: one map lookup per neighbor, no BlockPos boxing per probe.
        private final Map<Long, Double> bestCost = new HashMap<>();
        private final Map<Long, Long> cameFrom = new HashMap<>();
        private Node closest;
        private Node result;
        private long sequence;
        private long spentNanos;
        private int expanded;
        private String reason;

        private Search(CollisionView world, BlockPos start, BlockPos goal,
                Predicate<BlockPos> isGoal, Set<BlockPos> blacklist, long timeBudgetNanos,
                Predicate<BlockPos> loaded) {
            if (world == null || start == null || goal == null || isGoal == null
                    || blacklist == null || timeBudgetNanos < 0L || loaded == null) {
                throw new IllegalArgumentException("invalid coarse search arguments");
            }
            this.world = world;
            this.goal = goal;
            this.isGoal = isGoal;
            this.blacklist = blacklist;
            this.timeBudgetNanos = timeBudgetNanos;
            this.loaded = loaded;
            // The start cell is exempt from walkability: the player is already there, and
            // rejecting a swimming or mid-fall start would make every wet goto plan nothing.
            Node root = new Node(start.asLong(), 0.0, heuristic(start, goal), sequence++);
            open.add(root);
            bestCost.put(root.key(), 0.0);
            closest = root;
        }

        /** Expands until this slice, the node cap, or the global time budget ends. */
        public void advance(long sliceNanos) {
            if (isFinished() || sliceNanos <= 0L) return;
            long began = System.nanoTime();
            long sliceDeadline = saturatingAdd(began, sliceNanos);
            while (!open.isEmpty() && expanded < NODE_CAP
                    && spentNanos + (System.nanoTime() - began) < timeBudgetNanos
                    && System.nanoTime() < sliceDeadline) {
                Node node = open.poll();
                if (node.g() > bestCost.getOrDefault(node.key(), Double.POSITIVE_INFINITY)) continue;
                BlockPos cell = BlockPos.fromLong(node.key());
                if (isGoal.test(cell)) {
                    result = node;
                    reason = "goal reached";
                    break;
                }
                expanded++;
                if (closer(node, closest)) closest = node;
                expand(node, cell);
            }
            spentNanos += System.nanoTime() - began;
            if (result == null) {
                if (open.isEmpty()) reason = "search frontier exhausted";
                else if (expanded >= NODE_CAP) reason = "node cap exhausted";
                else if (spentNanos >= timeBudgetNanos) reason = "time budget exhausted";
            }
        }

        public boolean isFinished() { return result != null || reason != null; }
        public int expandedNodes() { return expanded; }

        public Result result() {
            if (!isFinished()) throw new IllegalStateException("search is still running");
            boolean reached = result != null;
            Node end = reached ? result : closest;
            List<BlockPos> reverse = new ArrayList<>();
            for (Long key = end.key(); key != null; key = cameFrom.get(key)) {
                reverse.add(BlockPos.fromLong(key));
            }
            List<BlockPos> corridor = new ArrayList<>(reverse.size());
            for (int i = reverse.size() - 1; i >= 0; i--) corridor.add(reverse.get(i));
            return new Result(corridor, reached, reason + "; expanded " + expanded
                    + (reached ? " cells" : " cells; returning best partial"));
        }

        private void expand(Node node, BlockPos cell) {
            for (int[] direction : DIRECTIONS) {
                int dx = direction[0];
                int dz = direction[1];
                boolean diagonal = dx * dz != 0;
                double moveCost = diagonal ? DIAGONAL_COST : 1.0;
                // Diagonals never cut corners: both flanking cardinal columns must be open
                // at body height, or the corridor threads gaps the player cannot.
                if (diagonal && (!open2(cell.add(dx, 0, 0)) || !open2(cell.add(0, 0, dz)))) {
                    continue;
                }
                BlockPos flat = cell.add(dx, 0, dz);
                if (walkable(flat)) {
                    relax(node, flat, moveCost);
                    continue;
                }
                // Step up one: the jump needs headroom above the CURRENT cell too.
                BlockPos up = flat.up();
                if (walkable(up) && passable(cell.up(2))) {
                    relax(node, up, moveCost + STEP_UP_PENALTY);
                    continue;
                }
                // Drop up to MAX_DROP: the fall column must be clear the whole way down.
                if (!open2(flat)) continue;
                for (int depth = 1; depth <= MAX_DROP; depth++) {
                    BlockPos landing = flat.down(depth);
                    if (walkable(landing)) {
                        relax(node, landing, moveCost);
                        break;
                    }
                    // A solid or lava cell ends the fall without a landing worth standing on.
                    if (!passable(landing)
                            || PlayerMotion.isLava(world.getFluidState(landing))) break;
                }
            }
        }

        private void relax(Node from, BlockPos to, double cost) {
            if (blacklist.contains(to)) return;
            long key = to.asLong();
            double g = from.g() + cost;
            if (g < bestCost.getOrDefault(key, Double.POSITIVE_INFINITY)) {
                bestCost.put(key, g);
                cameFrom.put(key, from.key());
                Node next = new Node(key, g, heuristic(to, goal), sequence++);
                open.add(next);
                if (closer(next, closest)) closest = next;
            }
        }

        /** Feet+head passable, no lava, and either solid support below or a swimmable cell. */
        private boolean walkable(BlockPos feet) {
            // Unloaded cells read as air (see begin): never stand the corridor on fiction.
            if (!loaded.test(feet)) return false;
            if (!open2(feet)) return false;
            if (PlayerMotion.isLava(world.getFluidState(feet))
                    || PlayerMotion.isLava(world.getFluidState(feet.up()))) return false;
            if (PlayerMotion.isWater(world.getFluidState(feet))) return true; // swim leg
            BlockPos support = feet.down();
            return !passable(support) && !PlayerMotion.isLava(world.getFluidState(support));
        }

        /** Both body cells (feet and head) collision-free. */
        private boolean open2(BlockPos feet) {
            return passable(feet) && passable(feet.up());
        }

        private boolean passable(BlockPos pos) {
            return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
        }
    }

    /* Octile distance plus straight vertical: admissible for unit moves, and the same w=2
       weighting as the deep planner keeps expansion goal-directed over long distances. */
    private static double heuristic(BlockPos from, BlockPos goal) {
        int dx = Math.abs(goal.getX() - from.getX());
        int dz = Math.abs(goal.getZ() - from.getZ());
        double octile = Math.max(dx, dz) + (DIAGONAL_COST - 1.0) * Math.min(dx, dz);
        return octile + Math.abs(goal.getY() - from.getY());
    }

    private static boolean closer(Node candidate, Node current) {
        return candidate.h() < current.h()
                || (candidate.h() == current.h() && candidate.g() < current.g());
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private record Node(long key, double g, double h, long sequence) {
        double f() { return g + 2.0 * h; }
    }
}
