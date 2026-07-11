package dev.glade.client;

import dev.glade.client.humanize.Humanizer;
import dev.glade.client.pathing.PathingEngine;
import dev.glade.client.pathing.PathingEngineRegistry;
import dev.glade.client.task.TaskScheduler;
import dev.glade.client.task.TickBudgetManager;

/** Process-wide client services. */
public final class GladeClient {
    private static final TaskScheduler TASK_SCHEDULER = new TaskScheduler();
    private static final TickBudgetManager TICK_BUDGET = new TickBudgetManager();
    private static final Humanizer HUMANIZER = new Humanizer();

    private GladeClient() {
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
