package dev.talos.client.action;

import dev.talos.client.TalosClient;
import dev.talos.client.humanize.HumanizationProfile;
import dev.talos.client.humanize.SeededRng;
import dev.talos.client.scan.ScanTask;
import dev.talos.client.task.SimpleTask;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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
        this.target = target.immutable();
        this.selector = selector == null ? stack -> stack.getItem() instanceof BlockItem : selector;
        this.face = face;
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

    private void prepare(Minecraft client) {
        if (!client.level.getBlockState(target).canBeReplaced()) {
            finish(false, "Target is not replaceable"); return;
        }
        int slot = findSlot(client);
        if (slot < 0) { finish(false, "No matching block item in the hotbar"); return; }
        WeaponSelector.select(client, slot);
        ItemStack stack = client.player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            finish(false, "Selected item is not a block item"); return;
        }
        expectedBlock = blockItem.getBlock();
        if (!withinReach(client)) { finish(false, "Placement target is out of reach"); return; }
        aim = new AimController(client, TalosClient.humanizer().rotation(), profile,
                rng.nextInt(Integer.MAX_VALUE));
        state = State.ACQUIRE;
    }

    private void execute(Minecraft client) {
        BlockHitResult hit = new BlockHitResult(hitPosition(), face, target, false);
        net.minecraft.world.InteractionResult interaction =
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        client.player.swing(InteractionHand.MAIN_HAND);
        if (!interaction.consumesAction()) { retry("Placement interaction was rejected"); return; }
        waitUntil = ticks + TalosClient.humanizer().timing().sampleDelayTicks(profile, 0.35, rng);
        state = State.OBSERVE;
    }

    private void observe(Minecraft client) {
        var stateNow = client.level.getBlockState(target);
        if (!stateNow.isAir() && stateNow.is(expectedBlock)) {
            finish(true, "Placed block at " + target.toShortString()); return;
        }
        if (!stateNow.canBeReplaced()) {
            finish(false, "A different block appeared at the target"); return;
        }
        if (ticks >= waitUntil) retry("Server did not confirm placement");
    }

    private void retry(String message) {
        if (++attempts >= 3) { finish(false, message); return; }
        waitUntil = ticks + 1 + TalosClient.humanizer().timing().sampleDelayTicks(profile, 0.55, rng);
        state = State.BACKOFF;
    }

    private int findSlot(Minecraft client) {
        int selected = client.player.getInventory().getSelectedSlot();
        if (selector.test(client.player.getInventory().getItem(selected))) return selected;
        for (int slot = 0; slot < 9; slot++) {
            if (selector.test(client.player.getInventory().getItem(slot))) return slot;
        }
        return -1;
    }

    private Vec3 hitPosition() {
        return Vec3.atCenterOf(target).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
    }
    private boolean withinReach(Minecraft client) {
        return client.player.getEyePosition().distanceToSqr(hitPosition())
                <= Math.pow(client.player.blockInteractionRange(), 2);
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
