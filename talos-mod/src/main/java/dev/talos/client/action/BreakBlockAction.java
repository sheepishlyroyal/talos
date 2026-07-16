package dev.talos.client.action;

import dev.talos.client.TalosClient;
import dev.talos.client.humanize.HumanizationProfile;
import dev.talos.client.humanize.SeededRng;
import dev.talos.client.log.TalosLog;
import dev.talos.client.scan.ScanTask;
import dev.talos.client.task.SimpleTask;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

/** Humanized, verified block-breaking state machine. */
public final class BreakBlockAction extends SimpleTask {
    private enum State { PREPARE, ACQUIRE, EXECUTE, OBSERVE, BACKOFF, RELEASE }
    private static final int TIMEOUT_TICKS = 20 * 30;
    private static final Set<Object> MUTEX = Set.of(ScanTask.INTENSIVE_MUTEX);

    private final BlockPos target;
    private final HumanizationProfile profile;
    private final CompletableFuture<ActionResult> result = new CompletableFuture<>();
    private final SeededRng rng = new SeededRng(System.nanoTime());
    private State state = State.PREPARE;
    private AimController aim;
    private BlockState original;
    private ItemStack selectedTool;
    private Direction side = Direction.UP;
    private int ticks;
    private long waitUntil;
    private int attempts;

    public BreakBlockAction(BlockPos target) { this(target, null); }
    public BreakBlockAction(BlockPos target, @Nullable HumanizationProfile profile) {
        this.target = target.toImmutable();
        this.profile = profile == null ? TalosClient.humanizer().defaultProfile() : profile;
    }
    public CompletableFuture<ActionResult> future() { return result; }
    @Override public boolean condition() { return state != State.RELEASE; }
    @Override public Set<Object> getMutexKeys() { return MUTEX; }

    @Override protected void onTick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) { finish(false, "Action left the client thread"); return; }
        if (client.player == null || client.world == null || client.interactionManager == null) {
            finish(false, "No active world/player"); return;
        }
        if (++ticks > TIMEOUT_TICKS) { finish(false, "Timed out breaking " + target.toShortString()); return; }
        switch (state) {
            case PREPARE -> prepare(client);
            case ACQUIRE -> { aim.aimAt(target); aim.tick(); if (aim.isAimed()) {
                TalosLog.trace("actions", "break acquire->execute target=" + target.toShortString());
                state = State.EXECUTE;
            } }
            case EXECUTE -> execute(client);
            case OBSERVE -> observe(client);
            case BACKOFF -> { if (ticks >= waitUntil) state = State.ACQUIRE; }
            case RELEASE -> { }
        }
    }

    private void prepare(MinecraftClient client) {
        TalosLog.trace("actions", "break prepare target=" + target.toShortString());
        original = client.world.getBlockState(target);
        // The desired end state (block gone) already holds — that's success, not an error.
        if (original.isAir()) { finish(true, "Block was already clear"); return; }
        if (!withinReach(client)) { finish(false, "Block is out of reach"); return; }
        int slot = bestToolSlot(client, original);
        WeaponSelector.select(client, slot);
        selectedTool = client.player.getMainHandStack();
        aim = new AimController(client, TalosClient.humanizer().rotation(), profile, rng.nextInt(Integer.MAX_VALUE));
        state = State.ACQUIRE;
        TalosLog.trace("actions", "break prepare->acquire target=" + target.toShortString());
    }

    private void execute(MinecraftClient client) {
        if (client.player.getMainHandStack() != selectedTool) {
            finish(false, "Held tool changed during break"); return;
        }
        boolean accepted = client.interactionManager.attackBlock(target, side);
        client.player.swingHand(Hand.MAIN_HAND);
        if (!accepted) { retry("Server rejected break start"); return; }
        state = State.OBSERVE;
        TalosLog.trace("actions", "break execute->verify target=" + target.toShortString());
    }

    private void observe(MinecraftClient client) {
        BlockState current = client.world.getBlockState(target);
        if (current.isAir()) { finish(true, "Broke " + target.toShortString()); return; }
        if (current != original && !current.equals(original)) {
            finish(false, "Target block changed unexpectedly"); return;
        }
        if (client.player.getMainHandStack() != selectedTool) {
            finish(false, "Held tool changed during break"); return;
        }
        if (!withinReach(client)) { finish(false, "Block moved out of reach"); return; }
        if (TalosClient.tickBudget().hasBudgetRemaining()) {
            client.interactionManager.updateBlockBreakingProgress(target, side);
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private void retry(String message) {
        if (++attempts >= 3) { finish(false, message); return; }
        waitUntil = ticks + TalosClient.humanizer().timing().sampleDelayTicks(profile, 0.6, rng);
        state = State.BACKOFF;
    }

    private boolean withinReach(MinecraftClient client) {
        return client.player.getEyePos().squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(target))
                <= Math.pow(client.player.getBlockInteractionRange(), 2);
    }

    private static int bestToolSlot(MinecraftClient client, BlockState state) {
        int best = client.player.getInventory().getSelectedSlot();
        float speed = client.player.getInventory().getStack(best).getMiningSpeedMultiplier(state);
        for (int slot = 0; slot < 9; slot++) {
            float candidate = client.player.getInventory().getStack(slot).getMiningSpeedMultiplier(state);
            if (candidate > speed) { speed = candidate; best = slot; }
        }
        return best;
    }

    private void finish(boolean success, String message) {
        if (state == State.RELEASE) return;
        state = State.RELEASE;
        TalosLog.trace("actions", "break " + (success ? "success: " : "fail: ") + message);
        result.complete(new ActionResult(success, message));
        _break();
    }
    @Override public void onCompleted() {
        if (!result.isDone()) result.complete(new ActionResult(false, "Break action cancelled"));
    }
}
