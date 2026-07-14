package dev.talos.client.pathing.sim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Corridor-quality tests for the coarse block-grid layer. These pin the contract the
 * engine's funneling relies on: an ordered start-to-goal corridor, honest reachedGoal on
 * failure, and blacklisted cells genuinely routed around. All run against {@link FakeWorld}.
 */
class CoarsePathfinderTest {
    private static final long BUDGET = 2_000_000_000L;

    @BeforeAll
    static void bootstrap() {
        Bootstraps.ensure();
    }

    /** Stone floor at y=63; walkable feet cells are at y=64. */
    private static FakeWorld field(int x1, int z1, int x2, int z2) {
        FakeWorld world = new FakeWorld();
        world.fill(x1, 63, z1, x2, 63, z2, Blocks.STONE.defaultBlockState());
        return world;
    }

    private static CoarsePathfinder.Result find(FakeWorld world, BlockPos start, BlockPos goal,
            Set<BlockPos> blacklist) {
        Predicate<BlockPos> isGoal = goal::equals;
        return CoarsePathfinder.find(world, start, goal, isGoal, blacklist, BUDGET);
    }

    @Test
    void findsCorridorAcrossFlatField() {
        FakeWorld world = field(-5, -5, 210, 5);
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos goal = new BlockPos(200, 64, 0);
        CoarsePathfinder.Result result = find(world, start, goal, Set.of());
        assertTrue(result.reachedGoal(), result.detail());
        List<BlockPos> corridor = result.corridor();
        assertTrue(corridor.getFirst().equals(start), "corridor starts at the start cell");
        assertTrue(corridor.getLast().equals(goal), "corridor ends at the goal cell");
        // Diagonal-capable moves make the flat straight line exactly 200 cells of progress.
        assertTrue(corridor.size() >= 200 && corridor.size() <= 220,
                "near-straight corridor, got " + corridor.size());
    }

    @Test
    void routesAroundLongWall() {
        FakeWorld world = field(-5, -40, 110, 40);
        // A 3-high wall across x=50 with the only opening at the far +z end: too tall to
        // step over, so the corridor must detour and come back.
        world.fill(50, 64, -40, 50, 66, 30, Blocks.STONE.defaultBlockState());
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos goal = new BlockPos(100, 64, 0);
        CoarsePathfinder.Result result = find(world, start, goal, Set.of());
        assertTrue(result.reachedGoal(), result.detail());
        // Diagonal moves keep the CELL count at exactly the straight line's 101, so the
        // honest detour signal is walked path LENGTH, plus proof it used the far-z gap.
        double walked = 0.0;
        boolean throughGap = false;
        List<BlockPos> corridor = result.corridor();
        for (int i = 0; i < corridor.size(); i++) {
            BlockPos cell = corridor.get(i);
            assertTrue(cell.getX() != 50 || cell.getZ() > 30, "corridor crosses the wall at " + cell);
            throughGap |= cell.getZ() > 30;
            if (i > 0) walked += Math.sqrt(cell.distSqr(corridor.get(i - 1)));
        }
        assertTrue(throughGap, "corridor detoured through the gap past the wall");
        assertTrue(walked > 110.0, "detour longer than the 100-block straight line: " + walked);
    }

    @Test
    void avoidsBlacklistedCells() {
        FakeWorld world = field(-5, -10, 60, 10);
        // Blacklist a band across the direct line, leaving open floor on both flanks.
        Set<BlockPos> blacklist = new HashSet<>();
        for (int z = -4; z <= 4; z++) blacklist.add(new BlockPos(25, 64, z));
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos goal = new BlockPos(50, 64, 0);
        CoarsePathfinder.Result result = find(world, start, goal, blacklist);
        assertTrue(result.reachedGoal(), result.detail());
        for (BlockPos cell : result.corridor()) {
            assertFalse(blacklist.contains(cell), "corridor entered blacklisted cell " + cell);
        }
    }

    @Test
    void sealedBoxReportsUnreachable() {
        FakeWorld world = field(-10, -10, 10, 10);
        // Walls and a lid sealing the start in; the goal is outside on the same floor.
        world.fill(-3, 64, -3, 3, 66, 3, Blocks.STONE.defaultBlockState());
        world.fill(-2, 64, -2, 2, 65, 2, Blocks.AIR.defaultBlockState());
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos goal = new BlockPos(8, 64, 0);
        CoarsePathfinder.Result result = find(world, start, goal, Set.of());
        assertFalse(result.reachedGoal(), "sealed box must not reach: " + result.detail());
    }

    @Test
    void unloadedRegionConfinesCorridorToLoadedHalf() {
        // Same flat field everywhere, but only the x <= 40 half is "loaded": the corridor
        // must route freely inside it and never plant a cell beyond the frontier — unloaded
        // chunks read as air on a real client, and air over a plain looks like a void.
        FakeWorld world = field(-5, -10, 100, 10);
        Predicate<BlockPos> loaded = pos -> pos.getX() <= 40;
        BlockPos start = new BlockPos(0, 64, 0);

        BlockPos nearGoal = new BlockPos(30, 64, 0);
        CoarsePathfinder.Result near = CoarsePathfinder.find(world, start, nearGoal,
                nearGoal::equals, Set.of(), BUDGET, loaded);
        assertTrue(near.reachedGoal(), near.detail());
        for (BlockPos cell : near.corridor()) {
            assertTrue(cell.getX() <= 40, "corridor entered unloaded region at " + cell);
        }

        BlockPos farGoal = new BlockPos(90, 64, 0);
        CoarsePathfinder.Result far = CoarsePathfinder.find(world, start, farGoal,
                farGoal::equals, Set.of(), BUDGET, loaded);
        assertFalse(far.reachedGoal(), "goal beyond the loaded frontier must not be reached");
        for (BlockPos cell : far.corridor()) {
            assertTrue(cell.getX() <= 40, "partial corridor entered unloaded region at " + cell);
        }
    }

    @Test
    void stepsUpAndDropsAcrossTerraces() {
        // A one-block rise at x=10 and a two-block drop at x=20 on the way to the goal.
        FakeWorld world = new FakeWorld();
        world.fill(-2, 63, -2, 9, 63, 2, Blocks.STONE.defaultBlockState());
        world.fill(10, 64, -2, 19, 64, 2, Blocks.STONE.defaultBlockState());
        world.fill(20, 62, -2, 30, 62, 2, Blocks.STONE.defaultBlockState());
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos goal = new BlockPos(28, 63, 0);
        CoarsePathfinder.Result result = find(world, start, goal, Set.of());
        assertTrue(result.reachedGoal(), result.detail());
    }
}
