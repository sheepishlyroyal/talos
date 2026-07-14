package dev.talos.client.pathing.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.block.Blocks;
import net.minecraft.block.BubbleColumnBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Physics fidelity tests: every case pins a vanilla 1.21.11 behavior the follower's
 * rollouts rely on. All run against {@link FakeWorld} — no client, no display.
 */
class PlayerMotionPhysicsTest {
    private static final Input WALK = new Input(1.0F, 0.0F, false, false, false, 0.0F);
    private static final Input SPRINT = new Input(1.0F, 0.0F, false, true, false, 0.0F);
    private static final Input SNEAK = new Input(1.0F, 0.0F, false, false, true, 0.0F);
    private static final Input IDLE = new Input(0.0F, 0.0F, false, false, false, 0.0F);
    private static final Input JUMP_ONLY = new Input(0.0F, 0.0F, true, false, false, 0.0F);

    @BeforeAll
    static void bootstrap() {
        Bootstraps.ensure();
    }

    private static FakeWorld flatStone() {
        FakeWorld world = new FakeWorld();
        world.fill(-24, 63, -24, 24, 63, 24, Blocks.STONE.getDefaultState());
        return world;
    }

    private static MotionState grounded(double x, double y, double z) {
        return new MotionState(new Vec3d(x, y, z), Vec3d.ZERO, true, MotionState.Pose.STAND);
    }

    private static MotionState run(FakeWorld world, MotionState state, Input input, int ticks) {
        for (int i = 0; i < ticks; i++) {
            state = PlayerMotion.step(world, state, input);
        }
        return state;
    }

    private static double perTickSpeed(FakeWorld world, Input input) {
        MotionState state = run(world, grounded(0.5, 64, 0.5), input, 60);
        MotionState next = PlayerMotion.step(world, state, input);
        return next.position().subtract(state.position()).horizontalLength();
    }

    @Test
    void sprintIsThirtyPercentFasterThanWalk() {
        FakeWorld world = flatStone();
        double walk = perTickSpeed(world, WALK);
        double sprint = perTickSpeed(world, SPRINT);
        assertTrue(walk > 0.15 && walk < 0.25, "walk speed sane: " + walk);
        // The 1.3 ratio is the regression guard for the sprint double-count bug (was ~1.69).
        assertEquals(1.3, sprint / walk, 0.05, "sprint/walk ratio");
    }

    @Test
    void jumpLaunchTickKeepsGroundFriction() {
        FakeWorld world = flatStone();
        MotionState state = new MotionState(new Vec3d(0.5, 64, 0.5), new Vec3d(0, 0, 0.2),
                true, MotionState.Pose.STAND);
        Input jumpForward = new Input(1.0F, 0.0F, true, false, false, 0.0F);
        MotionState next = PlayerMotion.step(world, state, jumpForward);
        assertTrue(next.velocity().y > 0.3, "jumped");
        // (0.2 + accel 0.1) * ground drag 0.546 — using air drag 0.91 here was the bug that
        // over-predicted every jump arc.
        assertEquals((0.2 + 0.1) * 0.6 * 0.91, next.velocity().z, 5.0E-3, "launch friction");
    }

    @Test
    void heldJumpHonorsTenTickCooldown() {
        FakeWorld world = flatStone();
        world.fill(-2, 66, -2, 2, 66, 2, Blocks.STONE.getDefaultState()); // low ceiling
        MotionState state = grounded(0.5, 64, 0.5);
        int launches = 0;
        for (int i = 0; i < 60; i++) {
            state = PlayerMotion.step(world, state, JUMP_ONLY);
            // The cooldown resets to 10 on the launch tick — the unambiguous jump marker
            // even when the low ceiling clamps the rise inside the same tick.
            if (state.jumpCooldown() == 10) launches++;
        }
        assertTrue(launches >= 2, "some jumps happened: " + launches);
        assertTrue(launches <= 7, "cooldown limits hop spam: " + launches + " in 60 ticks");
    }

    @Test
    void sneakCannotWalkOffALedge() {
        FakeWorld world = new FakeWorld();
        world.set(0, 63, 0, Blocks.STONE.getDefaultState()); // one lonely block
        MotionState state = grounded(0.5, 64, 0.5);
        state = run(world, state, SNEAK, 40);
        assertEquals(64.0, state.position().y, 1.0E-6, "never fell off");
        assertTrue(state.position().z < 1.31, "clamped at the ledge: z=" + state.position().z);
    }

    @Test
    void cobwebSmothersMovementAndZeroesVelocity() {
        FakeWorld world = flatStone();
        world.set(0, 64, 0, Blocks.COBWEB.getDefaultState());
        MotionState state = new MotionState(new Vec3d(0.5, 64, 0.5), new Vec3d(0, 0, 0.3),
                true, MotionState.Pose.STAND);
        MotionState next = PlayerMotion.step(world, state, SPRINT);
        double moved = next.position().z - state.position().z;
        assertTrue(moved < 0.13, "web-scaled movement, got " + moved);
        assertEquals(0.0, next.velocity().horizontalLength(), 1.0E-9, "velocity zeroed");
    }

    @Test
    void powderSnowIsPassableAndSlowing() {
        FakeWorld world = flatStone();
        world.set(0, 64, 0, Blocks.POWDER_SNOW.getDefaultState());
        world.set(0, 65, 0, Blocks.POWDER_SNOW.getDefaultState());
        MotionState state = new MotionState(new Vec3d(0.5, 64, 0.5), new Vec3d(0, 0, 0.3),
                true, MotionState.Pose.STAND);
        MotionState next = PlayerMotion.step(world, state, WALK);
        double moved = next.position().z - state.position().z;
        assertTrue(moved > 0.15, "passable (not solid), moved " + moved);
        assertTrue(moved < 0.40, "slowed by the 0.9 factor, moved " + moved);
        assertEquals(0.0, next.velocity().horizontalLength(), 1.0E-9, "velocity zeroed");
    }

    @Test
    void ladderClimbsWhenPressingIntoTheWall() {
        FakeWorld world = flatStone();
        world.fill(0, 64, 1, 0, 68, 1, Blocks.STONE.getDefaultState()); // wall ahead (+Z)
        world.fill(0, 64, 0, 0, 68, 0, Blocks.LADDER.getDefaultState()); // ladder column
        MotionState state = grounded(0.5, 64, 0.5);
        state = run(world, state, WALK, 25);
        assertTrue(state.position().y > 65.0,
                "climbed the ladder, y=" + state.position().y);
    }

    @Test
    void ladderDescentIsClampedSlow() {
        FakeWorld world = flatStone();
        world.fill(0, 64, 1, 0, 72, 1, Blocks.STONE.getDefaultState());
        world.fill(0, 64, 0, 0, 72, 0, Blocks.LADDER.getDefaultState());
        MotionState state = new MotionState(new Vec3d(0.5, 70, 0.5), Vec3d.ZERO, false,
                MotionState.Pose.STAND);
        for (int i = 0; i < 15; i++) {
            double before = state.position().y;
            state = PlayerMotion.step(world, state, IDLE);
            // Vanilla clamps the climbing MOVEMENT each tick; the stored velocity carries
            // gravity between ticks and legitimately reads below -0.15.
            assertTrue(before - state.position().y <= 0.16,
                    "descent clamped, fell " + (before - state.position().y));
        }
        assertTrue(state.position().y > 66.5, "slid slowly, y=" + state.position().y);
    }

    @Test
    void slimeBlockBouncesTheFall() {
        FakeWorld world = new FakeWorld();
        world.fill(-2, 63, -2, 2, 63, 2, Blocks.SLIME_BLOCK.getDefaultState());
        MotionState state = new MotionState(new Vec3d(0.5, 69, 0.5), Vec3d.ZERO, false,
                MotionState.Pose.STAND);
        boolean bounced = false;
        for (int i = 0; i < 40 && !bounced; i++) {
            MotionState next = PlayerMotion.step(world, state, IDLE);
            if (state.velocity().y < -0.1 && next.velocity().y > 0.1) bounced = true;
            state = next;
        }
        assertTrue(bounced, "fall inverted into a bounce");
    }

    @Test
    void slimeBouncesDecayAndSettle() {
        // Vanilla SlimeBlock.bounce is a full -vy inversion with no cap; the real-world
        // decay comes from the (vy - 0.08) * 0.98 gravity/drag applied to the bounced
        // velocity that same tick and on every airborne tick after. Peaks must therefore
        // shrink monotonically and the player must settle — never bounce forever.
        FakeWorld world = new FakeWorld();
        world.fill(-2, 63, -2, 2, 63, 2, Blocks.SLIME_BLOCK.getDefaultState());
        MotionState state = new MotionState(new Vec3d(0.5, 72, 0.5), Vec3d.ZERO, false,
                MotionState.Pose.STAND);
        java.util.List<Double> peaks = new java.util.ArrayList<>();
        boolean landedOnce = false; // ignore the initial drop arc — it is not a bounce
        double peak = Double.NEGATIVE_INFINITY;
        boolean settled = true;
        for (int i = 0; i < 400; i++) {
            state = PlayerMotion.step(world, state, IDLE);
            if (state.onGround()) {
                // A ground contact closes the current airborne arc; record its apex.
                if (landedOnce && peak > 64.01) peaks.add(peak);
                landedOnce = true;
                peak = Double.NEGATIVE_INFINITY;
            } else {
                peak = Math.max(peak, state.position().y);
            }
            if (i >= 350 && (state.position().y >= 64.7
                    || Math.abs(state.velocity().y) >= 0.15)) {
                settled = false;
            }
        }
        assertTrue(peaks.size() >= 3, "at least 3 bounces, got " + peaks.size());
        for (int i = 1; i < peaks.size(); i++) {
            assertTrue(peaks.get(i) < peaks.get(i - 1), "peaks decay: bounce " + i + " rose to "
                    + peaks.get(i) + " after " + peaks.get(i - 1));
        }
        assertTrue(settled, "at rest over the final 50 ticks");
    }

    @Test
    void slimeBounceIsCancelledBySneaking() {
        FakeWorld world = new FakeWorld();
        world.fill(-2, 63, -2, 2, 63, 2, Blocks.SLIME_BLOCK.getDefaultState());
        MotionState state = new MotionState(new Vec3d(0.5, 69, 0.5), Vec3d.ZERO, false,
                MotionState.Pose.STAND);
        Input sneakIdle = new Input(0.0F, 0.0F, false, false, true, 0.0F);
        for (int i = 0; i < 40; i++) {
            state = PlayerMotion.step(world, state, sneakIdle);
            assertTrue(state.velocity().y < 0.05, "no bounce while sneaking");
        }
    }

    @Test
    void bubbleColumnLiftsAndWhirlpoolSinks() {
        FakeWorld world = new FakeWorld();
        world.fill(0, 62, 0, 0, 70, 0, Blocks.BUBBLE_COLUMN.getDefaultState()
                .with(BubbleColumnBlock.DRAG, false));
        MotionState state = new MotionState(new Vec3d(0.5, 65, 0.5), Vec3d.ZERO, false,
                MotionState.Pose.SWIM);
        state = run(world, state, IDLE, 8);
        assertTrue(state.velocity().y > 0.02, "upward column lifts, vy=" + state.velocity().y);

        world.fill(0, 62, 0, 0, 70, 0, Blocks.BUBBLE_COLUMN.getDefaultState()
                .with(BubbleColumnBlock.DRAG, true));
        MotionState sinking = new MotionState(new Vec3d(0.5, 68, 0.5), Vec3d.ZERO, false,
                MotionState.Pose.SWIM);
        sinking = run(world, sinking, IDLE, 8);
        assertTrue(sinking.velocity().y < -0.02,
                "whirlpool drags down, vy=" + sinking.velocity().y);
    }

    @Test
    void stillWaterSinkRateMatchesVanillaApproximation() {
        FakeWorld world = new FakeWorld();
        world.fill(0, 58, 0, 0, 72, 0, Blocks.WATER.getDefaultState());
        MotionState state = new MotionState(new Vec3d(0.5, 66, 0.5), Vec3d.ZERO, false,
                MotionState.Pose.SWIM);
        state = run(world, state, IDLE, 40);
        // Terminal sink speed of vy*0.8 - 0.005 is -0.025; the old (vy-0.02)*0.8 gave -0.08.
        assertEquals(-0.025, state.velocity().y, 0.008, "gentle vanilla sink rate");
    }

    @Test
    void flowingWaterPushesTheSwimmer() {
        FakeWorld world = new FakeWorld();
        world.fill(0, 63, -1, 4, 63, 1, Blocks.STONE.getDefaultState());
        world.set(0, 64, 0, Blocks.WATER.getDefaultState());               // source
        world.set(1, 64, 0, Blocks.WATER.getDefaultState().with(FluidBlock.LEVEL, 2));
        world.set(2, 64, 0, Blocks.WATER.getDefaultState().with(FluidBlock.LEVEL, 5));
        MotionState state = new MotionState(new Vec3d(1.5, 64.0, 0.5), Vec3d.ZERO, true,
                MotionState.Pose.SWIM);
        state = run(world, state, IDLE, 5);
        assertTrue(state.velocity().x > 0.004,
                "current pushes downstream, vx=" + state.velocity().x);
    }

    @Test
    void diagonalSneakIsFasterThanCardinalSneak() {
        FakeWorld world = flatStone();
        Input diagonal = new Input(1.0F, 1.0F, false, false, true, 0.0F);
        MotionState cardinal = PlayerMotion.step(world, grounded(0.5, 64, 0.5), SNEAK);
        MotionState diag = PlayerMotion.step(world, grounded(0.5, 64, 0.5), diagonal);
        double cardinalMoved = cardinal.position().subtract(new Vec3d(0.5, 64, 0.5))
                .horizontalLength();
        double diagonalMoved = diag.position().subtract(new Vec3d(0.5, 64, 0.5))
                .horizontalLength();
        // Vanilla scales the input BEFORE normalization: 0.3/0.3 input is NOT normalized,
        // so its magnitude 0.424 beats the cardinal 0.3. The old code normalized first.
        assertTrue(diagonalMoved > cardinalMoved * 1.25,
                "diagonal " + diagonalMoved + " vs cardinal " + cardinalMoved);
    }

    @Test
    void honeyBlockSlowsAndStuntsJumps() {
        FakeWorld world = new FakeWorld();
        world.fill(-24, 63, -24, 24, 63, 24, Blocks.HONEY_BLOCK.getDefaultState());
        double honey = perTickSpeed(world, SPRINT);
        double stone = perTickSpeed(flatStone(), SPRINT);
        assertTrue(honey < stone * 0.6, "honey slows: " + honey + " vs " + stone);
        MotionState jump = PlayerMotion.step(world, grounded(0.5, 63.9375, 0.5),
                new Input(0.0F, 0.0F, true, false, false, 0.0F));
        assertTrue(jump.velocity().y < 0.3, "honey stunts the jump, vy=" + jump.velocity().y);
    }
}
