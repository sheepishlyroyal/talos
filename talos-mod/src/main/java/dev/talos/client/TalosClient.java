package dev.talos.client;

import dev.talos.client.humanize.Humanizer;
import dev.talos.client.pathing.PathingEngine;
import dev.talos.client.pathing.PathingEngineRegistry;
import dev.talos.client.task.TaskScheduler;
import dev.talos.client.task.TickBudgetManager;

/** Process-wide client services. */
public final class TalosClient {
    private static final TaskScheduler TASK_SCHEDULER = new TaskScheduler();
    private static final TickBudgetManager TICK_BUDGET = new TickBudgetManager();
    private static final Humanizer HUMANIZER = new Humanizer();

    private TalosClient() {
    }

    public static TaskScheduler taskScheduler() {
        return TASK_SCHEDULER;
    }

    public static TickBudgetManager tickBudget() {
        return TICK_BUDGET;
    }

    public static Humanizer humanizer() {
        return HUMANIZER;
    }

    public static PathingEngine pathingEngine() {
        return PathingEngineHolder.INSTANCE;
    }

    private static final class PathingEngineHolder {
        private static final PathingEngine INSTANCE = PathingEngineRegistry.discover();
    }
}
