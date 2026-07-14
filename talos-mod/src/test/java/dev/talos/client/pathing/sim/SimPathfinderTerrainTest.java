package dev.talos.client.pathing.sim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Predicate;

import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Golden-terrain planning tests for the physics-rollout A*: flat ground, staircase hills,
 * uncrossable gaps (honest {@code reachedGoal=false} without a detour, detour taken when
 * one exists), water channels (SWIM edges) and mining tunnels (MINE edges). All run
 * against {@link FakeWorld} with {@code Options.defaults()}-shaped budgets sized to keep
 * every test comfortably under ~2 seconds.
 */
class SimPathfinderTerrainTest {
    private static final SimPathfinder.Options WALK_ONLY =
            new SimPathfinder.Options(false, false, 40_000, 1_500_000_000L, 20);
    private static final SimPathfinder.Options MINING =
            new SimPathfinder.Options(true, false, 40_000, 1_500_000_000L, 20);
    // Water rollouts are the slowest edges to simulate; a slightly larger slice of the
    // ~2s test budget keeps the channel crossing deterministic.
    private static final SimPathfinder.Options SWIMMING =
            new SimPathfinder.Options(false, false, 40_000, 1_800_000_000L, 20);

    @BeforeAll
    static void bootstrap() {
        Bootstraps.ensure();
    }

    /** A standing start at the bottom-center of a feet cell, at rest. */
    private static MotionState standingAt(int x, int y, int z) {
        return new MotionState(new Vec3d(x + 0.5, y, z + 0.5), Vec3d.ZERO, true,
                MotionState.Pose.STAND);
    }

    /** Goal predicate: within one cell horizontally of the goal at its exact height. */
    private static Predicate<BlockPos> near(BlockPos goal) {
        return pos -> pos.getY() == goal.getY()
                && Math.abs(pos.getX() - goal.getX()) <= 1
                && Math.abs(pos.getZ() - goal.getZ()) <= 1;
    }

    private static PlannedRoute find(FakeWorld world, MotionState start, BlockPos goal,
            Predicate<BlockPos> isGoal, SimPathfinder.Options opts) {
        return SimPathfinder.find(world, start, MovementProfile.vanilla(), goal, isGoal, opts);
    }

    private static boolean routeUses(PlannedRoute route, Primitive primitive) {
        return route.waypoints().stream().anyMatch(w -> w.via() == primitive);
    }

    @Test
    void flatWalkReachesGoalSixtyBlocksOut() {
        FakeWorld world = new FakeWorld();
        world.fill(-2, 63, -3, 65, 63, 3, Blocks.STONE.getDefaultState());
        BlockPos goal = new BlockPos(60, 64, 0);
        PlannedRoute route = find(world, standingAt(0, 64, 0), goal, near(goal), WALK_ONLY);
        assertTrue(route.reachedGoal(), route.detail());
        assertTrue(route.waypoints().size() > 10, "a real multi-edge route, got "
                + route.waypoints().size());
    }

    @Test
    void staircaseHillClimbsToTheTop() {
        // Steps up one block every two blocks of travel: floor at y=63+i for x=[2i, 2i+1].
        FakeWorld world = new FakeWorld();
        for (int step = 0; step <= 9; step++) {
            world.fill(2 * step, 63 + step, -2, 2 * step + 1, 63 + step, 2,
                    Blocks.STONE.getDefaultState());
        }
        BlockPos goal = new BlockPos(19, 73, 0);
        PlannedRoute route = find(world, standingAt(0, 64, 0), goal, near(goal), WALK_ONLY);
        assertTrue(route.reachedGoal(), route.detail());
    }

    @Test
    void uncrossableGapWithoutDetourFailsHonestly() {
        // A narrow bridge with a 5-wide gap: landing 6 cells out exceeds even a
        // full-momentum sprint-jump arc (4-wide gaps are crossable with a run-up).
        FakeWorld world = new FakeWorld();
        world.fill(0, 63, -1, 40, 63, 1, Blocks.STONE.getDefaultState());
        world.fill(19, 63, -1, 23, 63, 1, Blocks.AIR.getDefaultState());
        BlockPos goal = new BlockPos(38, 64, 0);
        PlannedRoute route = find(world, standingAt(2, 64, 0), goal, near(goal), WALK_ONLY);
        assertFalse(route.reachedGoal(), "gap must not be crossed: " + route.detail());
        // The lip cell (x=19) can host a node whose hitbox is still supported by the last
        // bridge block, but nothing may make it past the middle of the gap.
        for (PlannedRoute.Waypoint waypoint : route.waypoints()) {
            assertTrue(waypoint.position().x < 21.5,
                    "partial route stays on the near side, got " + waypoint.position());
        }
    }

    @Test
    void uncrossableGapWithDetourTakesIt() {
        // Same gap, but a side platform at z=2..6 spans it: the route must detour.
        FakeWorld world = new FakeWorld();
        world.fill(0, 63, -1, 40, 63, 1, Blocks.STONE.getDefaultState());
        world.fill(19, 63, -1, 23, 63, 1, Blocks.AIR.getDefaultState());
        world.fill(14, 63, 2, 28, 63, 6, Blocks.STONE.getDefaultState());
        BlockPos goal = new BlockPos(38, 64, 0);
        PlannedRoute route = find(world, standingAt(2, 64, 0), goal, near(goal), WALK_ONLY);
        assertTrue(route.reachedGoal(), route.detail());
        boolean detoured = route.waypoints().stream()
                .anyMatch(waypoint -> waypoint.position().z > 1.5);
        assertTrue(detoured, "route crossed via the side platform");
    }

    @Test
    void momentumGapCrossesWithSprintJumpArc() {
        // A 3-wide gap is a legitimate vanilla 4-block sprint jump — but only at speed.
        // The long runway lets chained SPRINT edges deliver momentum to the lip, where the
        // arc rollout (which inherits the node's velocity) clears it.
        FakeWorld world = new FakeWorld();
        world.fill(0, 63, -1, 40, 63, 1, Blocks.STONE.getDefaultState());
        world.fill(19, 63, -1, 21, 63, 1, Blocks.AIR.getDefaultState());
        BlockPos goal = new BlockPos(38, 64, 0);
        PlannedRoute route = find(world, standingAt(2, 64, 0), goal, near(goal), WALK_ONLY);
        assertTrue(route.reachedGoal(), route.detail());
        assertTrue(routeUses(route, Primitive.SPRINT_JUMP), "route jumps the gap: "
                + route.waypoints().stream().map(w -> String.valueOf(w.via())).toList());
    }

    @Test
    void fourWideGapClearsViaEdgeTakeoff() {
        // A 4-wide gap needs more than an immediate center-of-cell jump. The run-up arc
        // sprints INSIDE the lip cell to the last supported subpixel before launching —
        // the same edge-hugging takeoff human parkour uses — and that extra distance
        // plus approach speed is exactly what lands the far lip.
        FakeWorld world = new FakeWorld();
        world.fill(8, 63, -1, 40, 63, 1, Blocks.STONE.getDefaultState());
        world.fill(19, 63, -1, 22, 63, 1, Blocks.AIR.getDefaultState());
        BlockPos goal = new BlockPos(38, 64, 0);
        PlannedRoute route = find(world, standingAt(18, 64, 0), goal, near(goal), WALK_ONLY);
        assertTrue(route.reachedGoal(), route.detail());
        assertTrue(routeUses(route, Primitive.SPRINT_JUMP), "route jumps the gap: "
                + route.waypoints().stream().map(w -> String.valueOf(w.via())).toList());
    }

    @Test
    void fiveBlockJumpLandsViaMomentumHopsAndSnowLayers() {
        // The infamous 5-block jump: impossible flat (asserted by the uncrossable-gap
        // test's honesty), possible from a 7-layer snow runway. The plan must CHAIN
        // momentum — hop onto the snow, hop again to arrive at the lip carrying air
        // speed (kept alive as a distinct A* state by the speed-bucketed node key),
        // then launch from the elevated edge across all five cells.
        FakeWorld world = new FakeWorld();
        world.fill(0, 63, -1, 40, 63, 1, Blocks.STONE.getDefaultState());
        world.fill(19, 63, -1, 23, 63, 1, Blocks.AIR.getDefaultState());
        world.fill(14, 64, -1, 18, 64, 1,
                Blocks.SNOW.getDefaultState().with(net.minecraft.block.SnowBlock.LAYERS, 7));
        BlockPos goal = new BlockPos(38, 64, 0);
        PlannedRoute route = find(world, standingAt(2, 64, 0), goal, near(goal), WALK_ONLY);
        assertTrue(route.reachedGoal(), route.detail());
        long jumps = route.waypoints().stream()
                .filter(w -> w.via() == Primitive.SPRINT_JUMP).count();
        assertTrue(jumps >= 2, "momentum chain uses hop(s) + the long jump, got " + jumps);
        boolean crossed = route.waypoints().stream()
                .anyMatch(w -> w.position().x >= 23.5 && w.position().y <= 64.5);
        assertTrue(crossed, "the long jump lands on the far lip");
    }

    @Test
    void waterChannelCrossesWithSwimEdges() {
        // A deep water channel one block below both shores: the only way across is to
        // drop in, swim (deep water: water at feet AND head), and climb out the far side.
        FakeWorld world = new FakeWorld();
        world.fill(-2, 63, -1, 7, 63, 1, Blocks.STONE.getDefaultState());   // near shore
        world.fill(8, 58, -1, 13, 58, 1, Blocks.STONE.getDefaultState());   // basin floor
        world.fill(8, 59, -1, 13, 62, 1, Blocks.WATER.getDefaultState());   // water y=59..62
        world.fill(14, 62, -1, 24, 62, 1, Blocks.STONE.getDefaultState());  // far shore
        BlockPos goal = new BlockPos(20, 63, 0);
        PlannedRoute route = find(world, standingAt(4, 64, 0), goal, near(goal), SWIMMING);
        assertTrue(route.reachedGoal(), route.detail());
        assertTrue(routeUses(route, Primitive.SWIM), "route swims the channel: "
                + route.waypoints().stream().map(w -> String.valueOf(w.via())).toList());
    }

    @Test
    void wallRequiringMiningTunnelsThrough() {
        // A 3-high, 3-thick wall seals the corridor; no detour and no jump can top it.
        // Planner mining edges end INSIDE each dug cell (the world is never mutated), so
        // the goal accepts the tunnel's far cell — execution re-plans onward from there.
        FakeWorld world = new FakeWorld();
        world.fill(0, 63, -1, 20, 63, 1, Blocks.STONE.getDefaultState());
        world.fill(10, 64, -1, 12, 66, 1, Blocks.STONE.getDefaultState());
        BlockPos goal = new BlockPos(12, 64, 0);
        Predicate<BlockPos> isGoal = pos -> pos.getX() >= 12 && pos.getY() == 64;
        PlannedRoute route = find(world, standingAt(2, 64, 0), goal, isGoal, MINING);
        assertTrue(route.reachedGoal(), route.detail());
        assertTrue(routeUses(route, Primitive.MINE), "route tunnels via MINE edges: "
                + route.waypoints().stream().map(w -> String.valueOf(w.via())).toList());
    }
}
