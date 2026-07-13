package dev.talos.client.action;

import dev.talos.client.TalosClient;
import dev.talos.client.humanize.HumanizationProfile;
import dev.talos.client.humanize.SeededRng;
import dev.talos.client.scan.ScanTask;
import dev.talos.client.task.SimpleTask;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import org.jetbrains.annotations.Nullable;

/** Humanized melee loop that succeeds only after authoritative entity death/removal. */
public final class KillEntityAction extends SimpleTask {
    private enum State { PREPARE, ACQUIRE, EXECUTE, OBSERVE, BACKOFF, RELEASE }
    private static final Set<Object> MUTEX = Set.of(ScanTask.INTENSIVE_MUTEX);
    private static final int TIMEOUT_TICKS = 20 * 45;

    private final int entityId;
    private UUID entityUuid;
    private final HumanizationProfile profile;
    private final CompletableFuture<ActionResult> result = new CompletableFuture<>();
    private final SeededRng rng = new SeededRng(System.nanoTime());
    private State state = State.PREPARE;
    private AimController aim;
    private int ticks;
    private long nextAttackTick;

    public KillEntityAction(Entity entity) { this(entity, null); }
    public KillEntityAction(Entity entity, @Nullable HumanizationProfile profile) {
        this(entity.getId(), entity.getUuid(), profile);
    }
    public KillEntityAction(int entityId, @Nullable HumanizationProfile profile) {
        this(entityId, null, profile);
    }
    private KillEntityAction(int entityId, @Nullable UUID uuid, @Nullable HumanizationProfile profile) {
        this.entityId = entityId;
        this.entityUuid = uuid;
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
        if (++ticks > TIMEOUT_TICKS) { finish(false, "Timed out killing target"); return; }
        Entity entity = resolve(client);
        if (entity == null) { finish(false, "Target entity unloaded or was replaced"); return; }
        if (!entity.isAlive() || entity.isRemoved()) { finish(true, "Target killed"); return; }
        switch (state) {
            case PREPARE -> prepare(client, entity);
            case ACQUIRE -> acquire(client, entity);
            case EXECUTE -> execute(client, entity);
            case OBSERVE -> observe(client, entity);
            case BACKOFF -> { aim.aimAt(entity); aim.tick(); if (ticks >= nextAttackTick) state = State.ACQUIRE; }
            case RELEASE -> { }
        }
    }

    private void prepare(MinecraftClient client, Entity entity) {
        if (!withinReach(client, entity)) { finish(false, "Entity is out of attack reach"); return; }
        WeaponSelector.select(client, WeaponSelector.findBestMeleeHotbarSlot(client.player));
        aim = new AimController(client, TalosClient.humanizer().rotation(), profile,
                rng.nextInt(Integer.MAX_VALUE));
        state = State.ACQUIRE;
    }

    private void acquire(MinecraftClient client, Entity entity) {
        aim.aimAt(entity);
        aim.tick();
        if (!withinReach(client, entity)) { finish(false, "Entity moved out of attack reach"); return; }
        if (aim.isAimed()) state = State.EXECUTE;
    }

    private void execute(MinecraftClient client, Entity entity) {
        var point = entity.getBoundingBox().getCenter();
        if (profile.alwaysVisibilityChecked() && !aim.hasLineOfSight(point)) {
            finish(false, "Target is not visible"); return;
        }
        if (client.player.getAttackCooldownProgress(0.0F) < 0.99F) {
            state = State.OBSERVE; return;
        }
        client.interactionManager.attackEntity(client.player, entity);
        client.player.swingHand(Hand.MAIN_HAND);
        nextAttackTick = ticks + Math.max(1,
                TalosClient.humanizer().timing().sampleDelayTicks(profile, 0.7, rng));
        state = State.BACKOFF;
    }

    private void observe(MinecraftClient client, Entity entity) {
        aim.aimAt(entity);
        aim.tick();
        if (client.player.getAttackCooldownProgress(0.0F) >= 0.99F) state = State.EXECUTE;
    }

    private Entity resolve(MinecraftClient client) {
        Entity entity = client.world.getEntityById(entityId);
        if (entity == null) return null;
        if (entityUuid == null) entityUuid = entity.getUuid();
        return entityUuid == null || entityUuid.equals(entity.getUuid()) ? entity : null;
    }
    private boolean withinReach(MinecraftClient client, Entity entity) {
        return client.player.getEyePos().squaredDistanceTo(entity.getBoundingBox().getCenter())
                <= Math.pow(client.player.getEntityInteractionRange(), 2);
    }
    private void finish(boolean success, String message) {
        if (state == State.RELEASE) return;
        state = State.RELEASE;
        result.complete(new ActionResult(success, message));
        _break();
    }
    @Override public void onCompleted() {
        if (!result.isDone()) result.complete(new ActionResult(false, "Kill action cancelled"));
    }
}
