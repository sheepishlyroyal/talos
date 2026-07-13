package dev.talos.client.action;

import dev.talos.client.TalosClient;
import dev.talos.client.humanize.HumanizationProfile;
import dev.talos.client.humanize.SeededRng;
import dev.talos.client.scan.ScanTask;
import dev.talos.client.task.SimpleTask;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/** Humanized, verified block-placement state machine. */
public final class PlaceBlockAction extends SimpleTask {
    private enum State { PREPARE, ACQUIRE, EXECUTE, OBSERVE, BACKOFF, RELEASE }
    private static final Set<Object> MUTEX = Set.of(ScanTask.INTENSIVE_MUTEX);
    private static final int TIMEOUT_TICKS = 20 * 10;

    private final BlockPos target;
    private final Predicate<ItemStack> selector;
    private final Direction face;
    private final HumanizationProfile profile;
    private final CompletableFuture<ActionResult> result = new CompletableFuture<>();
    private final SeededRng rng = new SeededRng(System.nanoTime());
    private State state = State.PREPARE;
    private AimController aim;
    private Block expectedBlock;
    private int ticks;
    private int attempts;
    private long waitUntil;

    public PlaceBlockAction(BlockPos target) { this(target, null, Direction.UP, null); }
    public PlaceBlockAction(BlockPos target, @Nullable Predicate<ItemStack> selector,
                            Direction face, @Nullable HumanizationProfile profile) {
        this.target = target.toImmutable();
        this.selector = selector == null ? stack -> stack.getItem() instanceof BlockItem : selector;
        this.face = face;
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
        if (++ticks > TIMEOUT_TICKS) { finish(false, "Timed out placing at " + target.toShortString()); return; }
        switch (state) {
            case PREPARE -> prepare(client);
            case ACQUIRE -> { aim.aimAt(hitPosition()); aim.tick(); if (aim.isAimed()) state = State.EXECUTE; }
            case EXECUTE -> execute(client);
            case OBSERVE -> observe(client);
            case BACKOFF -> { if (ticks >= waitUntil) state = State.ACQUIRE; }
            case RELEASE -> { }
        }
    }

    private void prepare(MinecraftClient client) {
        if (!client.world.getBlockState(target).isReplaceable()) {
            finish(false, "Target is not replaceable"); return;
        }
        int slot = findSlot(client);
        if (slot < 0) { finish(false, "No matching block item in the hotbar"); return; }
        WeaponSelector.select(client, slot);
        ItemStack stack = client.player.getMainHandStack();
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            finish(false, "Selected item is not a block item"); return;
        }
        expectedBlock = blockItem.getBlock();
        if (!withinReach(client)) { finish(false, "Placement target is out of reach"); return; }
        aim = new AimController(client, TalosClient.humanizer().rotation(), profile,
                rng.nextInt(Integer.MAX_VALUE));
        state = State.ACQUIRE;
    }

    private void execute(MinecraftClient client) {
        BlockHitResult hit = new BlockHitResult(hitPosition(), face, target, false);
        net.minecraft.util.ActionResult interaction =
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);
        if (!interaction.isAccepted()) { retry("Placement interaction was rejected"); return; }
        waitUntil = ticks + TalosClient.humanizer().timing().sampleDelayTicks(profile, 0.35, rng);
        state = State.OBSERVE;
    }

    private void observe(MinecraftClient client) {
        var stateNow = client.world.getBlockState(target);
        if (!stateNow.isAir() && stateNow.isOf(expectedBlock)) {
            finish(true, "Placed block at " + target.toShortString()); return;
        }
        if (!stateNow.isReplaceable()) {
            finish(false, "A different block appeared at the target"); return;
        }
        if (ticks >= waitUntil) retry("Server did not confirm placement");
    }

    private void retry(String message) {
        if (++attempts >= 3) { finish(false, message); return; }
        waitUntil = ticks + 1 + TalosClient.humanizer().timing().sampleDelayTicks(profile, 0.55, rng);
        state = State.BACKOFF;
    }

    private int findSlot(MinecraftClient client) {
        int selected = client.player.getInventory().getSelectedSlot();
        if (selector.test(client.player.getInventory().getStack(selected))) return selected;
        for (int slot = 0; slot < 9; slot++) {
            if (selector.test(client.player.getInventory().getStack(slot))) return slot;
        }
        return -1;
    }

    private Vec3d hitPosition() {
        return Vec3d.ofCenter(target).add(Vec3d.of(face.getVector()).multiply(0.5));
    }
    private boolean withinReach(MinecraftClient client) {
        return client.player.getEyePos().squaredDistanceTo(hitPosition())
                <= Math.pow(client.player.getBlockInteractionRange(), 2);
    }
    private void finish(boolean success, String message) {
        if (state == State.RELEASE) return;
        state = State.RELEASE;
        result.complete(new ActionResult(success, message));
        _break();
    }
    @Override public void onCompleted() {
        if (!result.isDone()) result.complete(new ActionResult(false, "Place action cancelled"));
    }
}
