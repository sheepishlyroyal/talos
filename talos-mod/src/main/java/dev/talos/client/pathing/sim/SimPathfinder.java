package dev.talos.client.pathing.sim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Bounded A* over controls rather than blocks. Every ordinary edge is accepted only after
 * {@link PlayerMotion} has rolled it forward in the supplied world.
 */
public final class SimPathfinder {
    public static final int BUMP_PENALTY = 3;
    // Prefers walking around over digging/placing (an edit "costs" ~a second of detour),
    // without making edit chains so expensive that A* floods the map before trying one.
    public static final int EDIT_PENALTY = 16;
    private static final int MAX_DROP = 4;
    private static final double EPSILON = 1.0E-7;
    private static final int[][] DIRECTIONS = {
            {0, 1}, {1, 1}, {1, 0}, {1, -1},
            {0, -1}, {-1, -1}, {-1, 0}, {-1, 1}
    };

    private SimPathfinder() {}

    /** Search limits. A zero time budget disables expansion rather than being unbounded. */
    public record Options(boolean allowMining, boolean allowPlacing, int nodeCap,
            long timeBudgetNanos, int maxRolloutTicks) {
        public Options {
            if (nodeCap < 1 || timeBudgetNanos < 0L || maxRolloutTicks < 1) {
                throw new IllegalArgumentException("invalid pathfinder options");
            }
        }

        public static Options defaults() {
            return new Options(false, false, 40_000, 300_000_000L, 20);
        }
    }

    /**
     * Finds a route toward {@code goal}. The predicate decides success; {@code goal} remains the
     * heuristic anchor, allowing callers to accept a region while guiding toward its center.
     */
    public static PlannedRoute find(World world, MotionState start, MovementProfile profile,
            BlockPos goal, Predicate<BlockPos> isGoal, Options opts) {
        Search search = begin(world, start, profile, goal, isGoal, opts);
        while (!search.isFinished()) search.advance(opts.timeBudgetNanos(), () -> true);
        return search.route();
    }

    /** Creates resumable deterministic A*. Call {@link Search#advance} from successive ticks. */
    public static Search begin(World world, MotionState start, MovementProfile profile,
            BlockPos goal, Predicate<BlockPos> isGoal, Options opts) {
        return new Search(world, start, profile, goal, isGoal, opts);
    }

    public static final class Search {
        private final World world;
        private final MovementProfile profile;
        private final BlockPos goal;
        private final Predicate<BlockPos> isGoal;
        private final Options opts;
        private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator
                .comparingDouble(Node::f).thenComparingDouble(Node::h)
                .thenComparingLong(Node::sequence));
        private final Map<Key, Double> bestCost = new HashMap<>();
        private Node closest;
        private Node result;
        private long sequence;
        private long spentNanos;
        private int expanded;
        private String reason;

        private Search(World world, MotionState start, MovementProfile profile, BlockPos goal,
                Predicate<BlockPos> isGoal, Options opts) {
            if (world == null || start == null || profile == null || goal == null
                    || isGoal == null || opts == null) {
                throw new IllegalArgumentException("search arguments must not be null");
            }
            this.world = world;
            this.profile = profile;
            this.goal = goal;
            this.isGoal = isGoal;
            this.opts = opts;
            BlockPos startCell = cell(start.position());
            Node root = new Node(start, startCell, heading(start.velocity(), 0), null, null,
                    0, 0.0, heuristic(startCell, goal, profile), sequence++);
            open.add(root);
            bestCost.put(key(root), 0.0);
            closest = root;
        }

        /** Expands until this slice, the global search budget, or the client's tick budget ends. */
        public void advance(long sliceNanos, BooleanSupplier hasTickBudget) {
            if (isFinished() || sliceNanos <= 0L || !hasTickBudget.getAsBoolean()) return;
            long began = System.nanoTime();
            long sliceDeadline = saturatingAdd(began, sliceNanos);
            while (!open.isEmpty() && expanded < opts.nodeCap()
                    && spentNanos + (System.nanoTime() - began) < opts.timeBudgetNanos()
                    && System.nanoTime() < sliceDeadline && hasTickBudget.getAsBoolean()) {
                Node node = open.poll();
                if (node.g() > bestCost.getOrDefault(key(node), Double.POSITIVE_INFINITY) + EPSILON) continue;
                if (isGoal.test(node.cell())) {
                    result = node;
                    reason = "goal reached";
                    break;
                }
                expanded++;
                if (closer(node, closest)) closest = node;
                for (Edge edge : edges(world, node, profile, opts)) {
                    BlockPos edgeCell = cell(edge.state().position());
                    int edgeHeading = heading(edge.state().velocity(), edge.fallbackHeading());
                    // A small direction-change cost keeps equal-time routes from tying, so
                    // successive re-plans stop flip-flopping between mirrored zigzags and the
                    // chosen line stays straight wherever straight is possible.
                    double g = node.g() + edge.cost()
                            + 0.75 * headingSteps(node.heading(), edgeHeading);
                    Node next = new Node(edge.state(), edgeCell, edgeHeading, node,
                            edge.primitive(), edge.ticks(), g,
                            heuristic(edgeCell, goal, profile), sequence++);
                    Key key = key(next);
                    if (g + EPSILON < bestCost.getOrDefault(key, Double.POSITIVE_INFINITY)) {
                        bestCost.put(key, g);
                        open.add(next);
                        if (closer(next, closest)) closest = next;
                    }
                }
            }
            spentNanos = Math.min(Long.MAX_VALUE, spentNanos + (System.nanoTime() - began));
            if (result == null) {
                if (open.isEmpty()) reason = "search frontier exhausted";
                else if (expanded >= opts.nodeCap()) reason = "node cap exhausted";
                else if (spentNanos >= opts.timeBudgetNanos()) reason = "time budget exhausted";
            }
        }

        public boolean isFinished() { return result != null || reason != null; }
        public int expandedNodes() { return expanded; }

        public PlannedRoute route() {
            if (!isFinished()) throw new IllegalStateException("search is still running");
            boolean reached = result != null;
            Node end = reached ? result : closest;
            return SimPathfinder.route(end, reached, reason + "; expanded " + expanded
                    + (reached ? " nodes" : " nodes; returning best partial"));
        }
    }

    private static List<Edge> edges(World world, Node node, MovementProfile profile,
            Options opts) {
        List<Edge> result = new ArrayList<>(48);
        for (int direction = 0; direction < DIRECTIONS.length; direction++) {
            int dx = DIRECTIONS[direction][0];
            int dz = DIRECTIONS[direction][1];
            float yaw = yaw(dx, dz);

            // Sprint dominates walk on cost whenever both controls realize the same edge, so
            // the slower walk rollout runs only where sprinting failed (tight ledges, drops).
            Edge sprint = rollout(world, node, profile, opts, Primitive.SPRINT, direction,
                    new Input(1.0F, 0.0F, false, true, false, yaw), false, false);
            add(result, sprint);
            if (sprint == null) {
                add(result, rollout(world, node, profile, opts, Primitive.WALK, direction,
                        new Input(1.0F, 0.0F, false, false, false, yaw), false, false));
            }
            // Jump arcs are attempted ONLY where plain sprinting failed (a gap or ledge cut
            // the sprint rollout short). This is both the semantics we want — jumps appear
            // in plans only where they're required; flat-ground hops are the follower's
            // on-the-go decision — and the single biggest planning speedup: arc rollouts
            // are the longest simulations, and this skips all 8 of them on open ground.
            if (sprint == null) {
                Edge arc = rollout(world, node, profile, opts, Primitive.SPRINT_JUMP, direction,
                        new Input(1.0F, 0.0F, true, true, false, yaw), true, false);
                add(result, arc == null ? null : arc.withAddedCost(6.0));
            }

            MotionState fluidStart = withPose(node.state(), MotionState.Pose.SWIM);
            // Merely wet feet do not enable vanilla's compact swimming movement. A new swim
            // edge requires water at both feet and head in the destination; an already compact
            // swimming/crawling state is allowed to continue across a shallow boundary.
            boolean continuingCompactPose = node.state().pose() == MotionState.Pose.SWIM
                    || node.state().pose() == MotionState.Pose.CRAWL;
            if (continuingCompactPose || deepWater(world, node.cell().add(dx, 0, dz))) {
                add(result, rollout(world, node.withState(fluidStart), profile, opts,
                        Primitive.SWIM, direction,
                        new Input(1.0F, 0.0F, true, false, false, yaw), false, true));
            }

            // Crawling only matters where standing is impossible: continuing an existing
            // compact pose or escaping a sub-1.8 ceiling. Open terrain skips 8 rollouts/node.
            MotionState crawlStart = withPose(node.state(), MotionState.Pose.CRAWL);
            if ((continuingCompactPose
                    || !PlayerMotion.hitboxFits(world, MotionState.Pose.STAND, node.state().position()))
                    && PlayerMotion.hitboxFits(world, MotionState.Pose.CRAWL, crawlStart.position())) {
                add(result, rollout(world, node.withState(crawlStart), profile, opts,
                        Primitive.CRAWL, direction,
                        new Input(1.0F, 0.0F, false, false, true, yaw), false, false));
            }

            add(result, stepUp(world, node, profile, opts, dx, dz, direction, yaw));
            add(result, drop(world, node, profile, opts, dx, dz, direction, yaw));
            // Edits are cardinal-only: a diagonal doorway/bridge has no shared face to act on.
            if (dx * dz == 0) {
                if (opts.allowMining()) add(result, mine(world, node, dx, dz, direction));
                if (opts.allowPlacing()) add(result, place(world, node, dx, dz, direction));
            }
        }
        if (opts.allowMining()) add(result, mineDown(world, node));
        if (opts.allowPlacing()) add(result, placeUp(world, node));
        return result;
    }

    /** Roll until a new stable cell is reached, an arc lands, or swimming enters a new cell. */
    private static Edge rollout(World world, Node node, MovementProfile profile, Options opts,
            Primitive primitive, int direction, Input input, boolean arc, boolean swimming) {
        MotionState state = node.state();
        BlockPos origin = cell(state.position());
        // Collision resolution inside step() cannot push the box into a block, so a fitting
        // start implies every subsequent tick fits. One check validates pose changes only.
        if (!PlayerMotion.hitboxFits(world, state.pose(), state.position())) return null;
        int bumps = 0;
        boolean airborne = !state.onGround();
        for (int tick = 1; tick <= opts.maxRolloutTicks(); tick++) {
            state = PlayerMotion.step(world, state, input, profile);
            if (state.bumpedHorizontally()) bumps++;
            if (state.fluid() == MotionState.Fluid.LAVA) return null;
            airborne |= !state.onGround();
            BlockPos reached = cell(state.position());
            if (reached.getY() < origin.getY() - MAX_DROP) return null;
            if ((primitive == Primitive.WALK || primitive == Primitive.SPRINT
                    || primitive == Primitive.CRAWL) && reached.getY() < origin.getY()) {
                return null;
            }
            boolean newCell = !reached.equals(origin);
            boolean stable = swimming ? state.inFluid() : state.onGround() || state.inFluid();
            if (newCell && stable && (!arc || airborne)) {
                int horizontal = Math.max(Math.abs(reached.getX() - origin.getX()),
                        Math.abs(reached.getZ() - origin.getZ()));
                if (arc && (horizontal < 1 || horizontal > 4)) return null;
                if (!arc && primitive != Primitive.PLACE && horizontal > 1) return null;
                // A climbing edge presses against the step face by construction; billing
                // those inherent bumps made straight ascents look worse than slope zigzags.
                int billedBumps = reached.getY() > origin.getY() ? 0 : bumps;
                return new Edge(state, primitive, tick, tick + billedBumps * BUMP_PENALTY,
                        direction);
            }
        }
        return null;
    }

    /** A full-block rise is an actual held-jump rollout, never a topology teleport. */
    private static Edge stepUp(World world, Node node, MovementProfile profile, Options opts,
            int dx, int dz, int direction, float yaw) {
        BlockPos target = node.cell().add(dx, 1, dz);
        if (!standable(world, target, MotionState.Pose.STAND)) return null;
        MotionState state = node.state();
        int bumps = 0;
        Input jump = new Input(1.0F, 0.0F, true, false, false, yaw);
        for (int tick = 1; tick <= opts.maxRolloutTicks(); tick++) {
            state = PlayerMotion.step(world, state, jump, profile);
            if (state.bumpedHorizontally()) bumps++;
            if (state.fluid() == MotionState.Fluid.LAVA) return null;
            BlockPos reached = cell(state.position());
            if (state.onGround() && reached.equals(target)) {
                // Pressing into the climbed face is the maneuver itself, not lost speed.
                return new Edge(state, Primitive.STEP_UP, tick, tick, direction);
            }
            // Landing anywhere else means this control does not realize the requested edge.
            if (tick > 1 && state.onGround()) return null;
        }
        return null;
    }

    private static Edge drop(World world, Node node, MovementProfile profile, Options opts,
            int dx, int dz, int direction, float yaw) {
        BlockPos adjacent = node.cell().add(dx, 0, dz);
        if (standable(world, adjacent, MotionState.Pose.STAND)) return null;
        Edge edge = rollout(world, node, profile, opts, Primitive.DROP, direction,
                new Input(1.0F, 0.0F, false, false, false, yaw), false, false);
        return edge != null && edge.state().position().y < node.state().position().y - 0.25
                ? edge : null;
    }

    /*
     * Mining is represented without mutating World: the edge ends INSIDE the dug-out wall
     * cell, so successive MINE edges chain a tunnel through walls of any thickness. Each
     * blocked slot of the 1x2 doorway pays a full edit penalty.
     */
    private static Edge mine(World world, Node node, int dx, int dz, int direction) {
        BlockPos wall = node.cell().add(dx, 0, dz);
        BlockPos wallHead = wall.up();
        boolean feetBlocked = !empty(world, wall);
        boolean headBlocked = !empty(world, wallHead);
        if (!feetBlocked && !headBlocked) return null; // plain movement already handles it
        if (feetBlocked && !mineable(world, wall)) return null;
        if (headBlocked && !mineable(world, wallHead)) return null;
        if (empty(world, wall.down())) return null;    // the dug doorway needs a floor
        if (world.getFluidState(wall).isIn(FluidTags.LAVA)
                || world.getFluidState(wallHead).isIn(FluidTags.LAVA)) return null;
        int blocks = (feetBlocked ? 1 : 0) + (headBlocked ? 1 : 0);
        MotionState state = new MotionState(bottomCenter(wall), Vec3d.ZERO, true,
                MotionState.Pose.STAND);
        return new Edge(state, Primitive.MINE, 8, 8.0 + EDIT_PENALTY * blocks, direction);
    }

    /** Dig straight down one cell; chains for a vertical shaft. Never digs into a void drop. */
    private static Edge mineDown(World world, Node node) {
        if (!node.state().onGround()) return null;
        BlockPos below = node.cell().down();
        if (!mineable(world, below)) return null;
        if (empty(world, below.down())) return null;   // land on something solid
        if (world.getFluidState(below.down()).isIn(FluidTags.LAVA)) return null;
        MotionState state = new MotionState(bottomCenter(below), Vec3d.ZERO, true,
                MotionState.Pose.STAND);
        return new Edge(state, Primitive.MINE, 8, 8.0 + EDIT_PENALTY, node.heading());
    }

    /*
     * Placement likewise ends ON the newly placed support in the gap cell, so bridging
     * chains across a span of any width one block at a time.
     */
    private static Edge place(World world, Node node, int dx, int dz, int direction) {
        if (!node.state().onGround()) return null;
        BlockPos gap = node.cell().add(dx, 0, dz);
        if (!empty(world, gap.down()) || !empty(world, gap) || !empty(world, gap.up())) return null;
        MotionState state = new MotionState(bottomCenter(gap), Vec3d.ZERO, true,
                MotionState.Pose.STAND);
        return new Edge(state, Primitive.PLACE, 8, 8.0 + EDIT_PENALTY, direction);
    }

    /** Nerdpole: jump and place under your own feet. Requires head clearance two above. */
    private static Edge placeUp(World world, Node node) {
        if (!node.state().onGround()) return null;
        if (!empty(world, node.cell().up(2))) return null;
        // The anchor face below may be real, or the block this chain just virtually placed —
        // planner edges never mutate the world, so demanding a real block capped every
        // planned pillar at exactly one block tall ("nodes ended" on goto ~ ~10 ~).
        if (empty(world, node.cell().down()) && node.via() != Primitive.PLACE) return null;
        MotionState state = new MotionState(bottomCenter(node.cell().up()), Vec3d.ZERO, true,
                MotionState.Pose.STAND);
        // Nerdpoling is reliable and fast in practice; billed near its real tick cost (no
        // full edit surcharge) so pillaring straight up beats a long detour over hills.
        return new Edge(state, Primitive.PLACE, 12, 14.0, node.heading());
    }

    private static boolean standable(World world, BlockPos feet, MotionState.Pose pose) {
        Vec3d position = bottomCenter(feet);
        return PlayerMotion.hitboxFits(world, pose, position)
                && !world.getBlockState(feet.down()).getCollisionShape(world, feet.down()).isEmpty()
                && !world.getFluidState(feet).isIn(FluidTags.LAVA);
    }

    private static boolean mineable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(world, pos).isEmpty()
                && state.getHardness(world, pos) >= 0.0F;
    }

    private static boolean empty(World world, BlockPos pos) {
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static boolean deepWater(World world, BlockPos feet) {
        return world.getFluidState(feet).isIn(FluidTags.WATER)
                && world.getFluidState(feet.up()).isIn(FluidTags.WATER);
    }

    private static MotionState withPose(MotionState state, MotionState.Pose pose) {
        return new MotionState(state.position(), state.velocity(), state.onGround(), pose,
                state.fluid(), state.bumpedHorizontally());
    }

    private static void add(List<Edge> edges, Edge edge) {
        if (edge != null) edges.add(edge);
    }

    private static boolean closer(Node candidate, Node current) {
        return candidate.h() < current.h() - EPSILON
                || (Math.abs(candidate.h() - current.h()) <= EPSILON && candidate.g() < current.g());
    }

    private static PlannedRoute route(Node end, boolean reachedGoal, String detail) {
        List<PlannedRoute.Waypoint> reverse = new ArrayList<>();
        for (Node node = end; node != null; node = node.parent()) {
            double edgeCost = node.parent() == null ? 0.0 : node.g() - node.parent().g();
            reverse.add(new PlannedRoute.Waypoint(node.state().position(), node.state().pose(), node.via(),
                    node.edgeTicks(), edgeCost));
        }
        List<PlannedRoute.Waypoint> forward = new ArrayList<>(reverse.size());
        for (int i = reverse.size() - 1; i >= 0; i--) forward.add(reverse.get(i));
        return new PlannedRoute(forward, reachedGoal, detail);
    }

    /* Both distances use intentionally generous profile-derived speed ceilings. They exceed
       the progress of the bounded primitives, keeping this estimate below their tick cost. */
    private static double heuristic(BlockPos from, BlockPos goal, MovementProfile profile) {
        int dx = Math.abs(goal.getX() - from.getX());
        int dz = Math.abs(goal.getZ() - from.getZ());
        double octile = Math.max(dx, dz) + (Math.sqrt(2.0) - 1.0) * Math.min(dx, dz);
        // Speeds must be REALISTIC per-tick bounds (sprint-jump ~0.5 blocks/tick, climbing
        // ~1 block per 4+ ticks, falling fast). The old 8/20 blocks-per-tick figures made
        // the heuristic ~30x weaker than true cost, so A* flooded sideways instead of
        // heading for the goal — vertical goals never planned and long plans came out
        // partial. Costs are ticks; these are lower bounds on ticks needed.
        double horizontalTicks = octile / Math.max(0.6, profile.movementSpeed() * 3.0);
        int dy = goal.getY() - from.getY();
        double verticalTicks = dy > 0 ? dy * 4.0 : -dy * 0.5;
        return Math.max(horizontalTicks, verticalTicks);
    }

    private static Key key(Node node) {
        return new Key(node.cell(), node.state().pose(), node.heading());
    }

    private static BlockPos cell(Vec3d position) {
        return BlockPos.ofFloored(position.x, position.y + 1.0E-4, position.z);
    }

    private static Vec3d bottomCenter(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static float yaw(int dx, int dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Angular difference between two 8-way headings, in 45-degree steps (0..4). */
    private static int headingSteps(int a, int b) {
        int difference = Math.abs(a - b) % 8;
        return Math.min(difference, 8 - difference);
    }

    private static int heading(Vec3d velocity, int fallback) {
        if (velocity.horizontalLengthSquared() < 1.0E-4) return fallback;
        double angle = Math.atan2(velocity.x, velocity.z);
        return Math.floorMod((int) Math.round(angle / (Math.PI / 4.0)), 8);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private record Key(BlockPos cell, MotionState.Pose pose, int heading) {}

    private record Node(MotionState state, BlockPos cell, int heading, Node parent,
            Primitive via, int edgeTicks, double g, double h, long sequence) {
        // Weighted A*: goal-directedness matters more than provable optimality for a live
        // game bot. w=2 finds deep routes in a fraction of the expansions.
        double f() { return g + 2.0 * h; }

        Node withState(MotionState replacement) {
            return new Node(replacement, SimPathfinder.cell(replacement.position()), heading,
                    parent, via,
                    edgeTicks, g, h, sequence);
        }
    }

    private record Edge(MotionState state, Primitive primitive, int ticks, double cost,
            int fallbackHeading) {
        Edge withAddedCost(double extra) {
            return new Edge(state, primitive, ticks, cost + extra, fallbackHeading);
        }
    }
}
