package dev.talos.client.action;

import dev.talos.client.TalosClient;
import dev.talos.client.humanize.HumanizationProfile;
import dev.talos.client.humanize.SeededRng;
import dev.talos.client.log.TalosLog;
import dev.talos.client.scan.ScanTask;
import dev.talos.client.task.SimpleTask;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
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
        this.target = target.immutable();
        this.profile = profile == null ? TalosClient.humanizer().defaultProfile() : profile;
    }
    public CompletableFuture<ActionResult> future() { return result; }
    @Override public boolean condition() { return state != State.RELEASE; }
    @Override public Set<Object> getMutexKeys() { return MUTEX; }

    @Override protected void onTick() {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) { finish(false, "Action left the client thread"); return; }
        if (client.player == null || client.level == null || client.gameMode == null) {
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

    private void prepare(Minecraft client) {
        TalosLog.trace("actions", "break prepare target=" + target.toShortString());
        original = client.level.getBlockState(target);
        // The desired end state (block gone) already holds — that's success, not an error.
        if (original.isAir()) { finish(true, "Block was already clear"); return; }
        if (!withinReach(client)) { finish(false, "Block is out of reach"); return; }
        int slot = bestToolSlot(client, original);
        WeaponSelector.select(client, slot);
        selectedTool = client.player.getMainHandItem();
        aim = new AimController(client, TalosClient.humanizer().rotation(), profile, rng.nextInt(Integer.MAX_VALUE));
        state = State.ACQUIRE;
        TalosLog.trace("actions", "break prepare->acquire target=" + target.toShortString());
    }

    private void execute(Minecraft client) {
        if (client.player.getMainHandItem() != selectedTool) {
            finish(false, "Held tool changed during break"); return;
        }
        boolean accepted = client.gameMode.startDestroyBlock(target, side);
        client.player.swing(InteractionHand.MAIN_HAND);
        if (!accepted) { retry("Server rejected break start"); return; }
        state = State.OBSERVE;
        TalosLog.trace("actions", "break execute->verify target=" + target.toShortString());
    }

    private void observe(Minecraft client) {
        BlockState current = client.level.getBlockState(target);
        if (current.isAir()) { finish(true, "Broke " + target.toShortString()); return; }
        if (current != original && !current.equals(original)) {
            finish(false, "Target block changed unexpectedly"); return;
        }
        if (client.player.getMainHandItem() != selectedTool) {
            finish(false, "Held tool changed during break"); return;
        }
        if (!withinReach(client)) { finish(false, "Block moved out of reach"); return; }
        if (TalosClient.tickBudget().hasBudgetRemaining()) {
            client.gameMode.continueDestroyBlock(target, side);
            client.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private void retry(String message) {
        if (++attempts >= 3) { finish(false, message); return; }
        waitUntil = ticks + TalosClient.humanizer().timing().sampleDelayTicks(profile, 0.6, rng);
        state = State.BACKOFF;
    }

    private boolean withinReach(Minecraft client) {
        return client.player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target))
                <= Math.pow(client.player.blockInteractionRange(), 2);
    }

    private static int bestToolSlot(Minecraft client, BlockState state) {
        int best = client.player.getInventory().getSelectedSlot();
        float speed = client.player.getInventory().getItem(best).getDestroySpeed(state);
        for (int slot = 0; slot < 9; slot++) {
            float candidate = client.player.getInventory().getItem(slot).getDestroySpeed(state);
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
