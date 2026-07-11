package dev.glade.client;

import dev.glade.client.task.TaskScheduler;
import dev.glade.client.task.TickBudgetManager;

/** Process-wide client services. */
public final class GladeClient {
    private static final TaskScheduler TASK_SCHEDULER = new TaskScheduler();
    private static final TickBudgetManager TICK_BUDGET = new TickBudgetManager();

    private GladeClient() {
    }

    public static TaskScheduler taskScheduler() {
        return TASK_SCHEDULER;
    }

    public static TickBudgetManager tickBudget() {
        return TICK_BUDGET;
    }
}
