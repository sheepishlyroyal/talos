package dev.talos.pathing.baritone;

import dev.talos.client.TalosClient;
import dev.talos.client.pathing.Goal;
import dev.talos.client.pathing.GoalBlock;
import dev.talos.client.pathing.GoalEntity;
import dev.talos.client.pathing.GoalNear;
import dev.talos.client.pathing.GoalXZ;
import dev.talos.client.pathing.PathResult;
import dev.talos.client.pathing.PathingEngine;
import dev.talos.client.pathing.PathingOptions;
import dev.talos.client.pathing.PathingUnavailableException;
import dev.talos.client.task.TalosTask;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional Baritone-backed pathing adapter.
 *
 * <p>Baritone is a separately installed, LGPL mod that is never bundled with Talos. There is no
 * resolvable compile-time artifact for the 1.21.11 branch, so every Baritone call is made purely
 * via reflection against {@code baritone.api.*}. When the Baritone jar is absent the reflective
 * handles fail to resolve, {@link #isAvailable()} returns {@code false}, and the engine degrades
 * to a typed "not installed" failure without ever risking a {@link NoClassDefFoundError}.
 */
public final class BaritonePathingEngine implements PathingEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(BaritonePathingEngine.class);
    private static final int START_GRACE_TICKS = 20;
    private static final int TIMEOUT_TICKS = 20 * 60 * 5;

    public BaritonePathingEngine() {
        if (isAvailable()) {
            Api.applyHumanizationDefaults();
        }
    }

    @Override
    public boolean isAvailable() {
        return Api.USABLE;
    }

    @Override
    public CompletableFuture<PathResult> goTo(Goal goal, PathingOptions options) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(options, "options");
        if (!isAvailable()) {
            return CompletableFuture.failedFuture(new PathingUnavailableException("Baritone is not installed"));
        }

        CompletableFuture<PathResult> future = new CompletableFuture<>();
        try {
            Object baritoneGoal = toBaritoneGoal(goal);
            Api.setGoalAndPath(baritoneGoal);
            TalosClient.taskScheduler().addNonConflictingTask(
                    "baritone-path-monitor", new PathMonitorTask(baritoneGoal, future));
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    @Override
    public void cancel() {
        if (!isAvailable()) {
            throw new PathingUnavailableException("Baritone is not installed");
        }
        Api.cancelEverything();
    }

    @Override
    public boolean isPathing() {
        return isAvailable() && Api.isPathing();
    }

    private static Object toBaritoneGoal(Goal goal) {
        return switch (goal) {
            case GoalBlock block -> Api.newGoalBlock(block.x(), block.y(), block.z());
            case GoalNear near -> Api.newGoalNear(new BlockPos(near.x(), near.y(), near.z()), near.radius());
            case GoalXZ xz -> Api.newGoalXZ(xz.x(), xz.z());
            case GoalEntity entityGoal -> {
                Minecraft client = Minecraft.getInstance();
                Entity entity = client.level == null ? null : client.level.getEntity(entityGoal.entityId());
                if (entity == null) {
                    throw new IllegalArgumentException("Entity is not loaded: " + entityGoal.entityId());
                }
                yield Api.newGoalNear(entity.blockPosition(), 1);
            }
        };
    }

    private static final class PathMonitorTask extends TalosTask {
        private final Object goal;
        private final CompletableFuture<PathResult> future;
        private int ticks;
        private boolean seenActive;

        private PathMonitorTask(Object goal, CompletableFuture<PathResult> future) {
            this.goal = goal;
            this.future = future;
        }

        @Override public void initialize() { }

        @Override
        public boolean condition() {
            return !future.isDone();
        }

        @Override
        public void increment() {
            ticks++;
        }

        @Override
        public void body() {
            try {
                boolean active = Api.isActiveOrPathing();
                seenActive |= active;

                Minecraft client = Minecraft.getInstance();
                if (client.player != null) {
                    BlockPos playerPos = client.player.blockPosition();
                    if (Api.isInGoal(goal, playerPos.getX(), playerPos.getY(), playerPos.getZ())) {
                        future.complete(new PathResult(true, "Arrived"));
                        return;
                    }
                }
                if (!active && (seenActive || ticks >= START_GRACE_TICKS)) {
                    future.complete(new PathResult(false, "Baritone stopped before reaching the goal"));
                    return;
                }
                if (ticks >= TIMEOUT_TICKS) {
                    Api.cancelEverything();
                    future.complete(new PathResult(false, "Pathing timed out"));
                    return;
                }
                scheduleDelay();
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        }

        @Override
        public void onCompleted() {
            if (!future.isDone()) {
                future.complete(new PathResult(false, "Pathing was interrupted"));
            }
        }
    }

    /**
     * Reflective bridge to {@code baritone.api.*}. All handles resolve once at class-init; if any
     * are missing (Baritone not installed, or an API-shape mismatch) {@link #USABLE} is {@code false}
     * and every method is a guarded no-op.
     */
    private static final class Api {
        static final boolean USABLE;

        private static Method mGetProvider;
        private static Method mGetSettings;
        private static Method mGetPrimaryBaritone;
        private static Method mGetCustomGoalProcess;
        private static Method mGetPathingBehavior;
        private static Method mSetGoalAndPath;
        private static Method mProcessIsActive;
        private static Method mIsPathing;
        private static Method mCancelEverything;
        private static Method mIsInGoal;
        private static Constructor<?> cGoalBlock;
        private static Constructor<?> cGoalNear;
        private static Constructor<?> cGoalXZ;

        static {
            boolean ok = false;
            try {
                ClassLoader cl = BaritonePathingEngine.class.getClassLoader();
                Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI", true, cl);
                Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal", true, cl);
                Class<?> goalBlockClass = Class.forName("baritone.api.pathing.goals.GoalBlock", true, cl);
                Class<?> goalNearClass = Class.forName("baritone.api.pathing.goals.GoalNear", true, cl);
                Class<?> goalXzClass = Class.forName("baritone.api.pathing.goals.GoalXZ", true, cl);

                mGetProvider = apiClass.getMethod("getProvider");
                mGetSettings = apiClass.getMethod("getSettings");
                Class<?> providerClass = mGetProvider.getReturnType();
                mGetPrimaryBaritone = providerClass.getMethod("getPrimaryBaritone");
                Class<?> baritoneClass = mGetPrimaryBaritone.getReturnType();
                mGetCustomGoalProcess = baritoneClass.getMethod("getCustomGoalProcess");
                mGetPathingBehavior = baritoneClass.getMethod("getPathingBehavior");
                mSetGoalAndPath = mGetCustomGoalProcess.getReturnType().getMethod("setGoalAndPath", goalClass);
                mProcessIsActive = mGetCustomGoalProcess.getReturnType().getMethod("isActive");
                mIsPathing = mGetPathingBehavior.getReturnType().getMethod("isPathing");
                mCancelEverything = mGetPathingBehavior.getReturnType().getMethod("cancelEverything");
                mIsInGoal = goalClass.getMethod("isInGoal", int.class, int.class, int.class);

                cGoalBlock = goalBlockClass.getConstructor(int.class, int.class, int.class);
                cGoalNear = goalNearClass.getConstructor(BlockPos.class, int.class);
                cGoalXZ = goalXzClass.getConstructor(int.class, int.class);
                ok = true;
            } catch (Throwable absent) {
                LOGGER.info("Baritone API not available; /talos goto will report it as not installed ({})",
                        absent.toString());
            }
            USABLE = ok;
        }

        static void applyHumanizationDefaults() {
            try {
                Object settings = mGetSettings.invoke(null);
                setSettingValue(settings, "freeLook", Boolean.TRUE);
                setSettingValue(settings, "antiCheatCompatibility", Boolean.TRUE);
                setSettingValue(settings, "legitMine", Boolean.TRUE);
            } catch (Throwable error) {
                LOGGER.warn("Failed to apply Baritone humanization settings", error);
            }
        }

        private static void setSettingValue(Object settings, String name, Object value) throws Exception {
            Field settingField = settings.getClass().getField(name);
            Object setting = settingField.get(settings);
            Field valueField = setting.getClass().getField("value");
            valueField.set(setting, value);
        }

        private static Object goalProcess() throws Exception {
            Object primary = mGetPrimaryBaritone.invoke(mGetProvider.invoke(null));
            return mGetCustomGoalProcess.invoke(primary);
        }

        private static Object pathingBehavior() throws Exception {
            Object primary = mGetPrimaryBaritone.invoke(mGetProvider.invoke(null));
            return mGetPathingBehavior.invoke(primary);
        }

        static void setGoalAndPath(Object baritoneGoal) {
            try {
                mSetGoalAndPath.invoke(goalProcess(), baritoneGoal);
            } catch (Throwable error) {
                throw new RuntimeException("Baritone setGoalAndPath failed", error);
            }
        }

        static boolean isActiveOrPathing() {
            try {
                return (Boolean) mProcessIsActive.invoke(goalProcess())
                        || (Boolean) mIsPathing.invoke(pathingBehavior());
            } catch (Throwable error) {
                return false;
            }
        }

        static boolean isPathing() {
            try {
                return (Boolean) mIsPathing.invoke(pathingBehavior());
            } catch (Throwable error) {
                return false;
            }
        }

        static void cancelEverything() {
            try {
                mCancelEverything.invoke(pathingBehavior());
            } catch (Throwable error) {
                LOGGER.warn("Baritone cancelEverything failed", error);
            }
        }

        static boolean isInGoal(Object goal, int x, int y, int z) {
            try {
                return (Boolean) mIsInGoal.invoke(goal, x, y, z);
            } catch (Throwable error) {
                return false;
            }
        }

        static Object newGoalBlock(int x, int y, int z) {
            try {
                return cGoalBlock.newInstance(x, y, z);
            } catch (Throwable error) {
                throw new RuntimeException("Baritone GoalBlock construction failed", error);
            }
        }

        static Object newGoalNear(BlockPos pos, int radius) {
            try {
                return cGoalNear.newInstance(pos, radius);
            } catch (Throwable error) {
                throw new RuntimeException("Baritone GoalNear construction failed", error);
            }
        }

        static Object newGoalXZ(int x, int z) {
            try {
                return cGoalXZ.newInstance(x, z);
            } catch (Throwable error) {
                throw new RuntimeException("Baritone GoalXZ construction failed", error);
            }
        }

        private Api() {}
    }
}
