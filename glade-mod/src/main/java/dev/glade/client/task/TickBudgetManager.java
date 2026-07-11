package dev.glade.client.task;

import java.util.function.LongSupplier;

/**
 * Shared wall-clock budget for all cooperative heavy work performed during a client tick.
 *
 * <p>The initial implementation intentionally uses a fixed 2 ms budget. Keeping time access and
 * budget selection isolated here allows a later phase to add frame/tick-time EMA coupling without
 * changing consumers.</p>
 */
public final class TickBudgetManager {
    public static final long MIN_BUDGET_NANOS = 1_000_000L;
    public static final long DEFAULT_BUDGET_NANOS = 2_000_000L;
    public static final long MAX_BUDGET_NANOS = 3_000_000L;

    private final LongSupplier nanoTime;
    private long tickStartNanos;
    private long budgetNanos = DEFAULT_BUDGET_NANOS;
    private boolean tickStarted;

    public TickBudgetManager() {
        this(System::nanoTime);
    }

    TickBudgetManager(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public void beginTick() {
        budgetNanos = Math.clamp(DEFAULT_BUDGET_NANOS, MIN_BUDGET_NANOS, MAX_BUDGET_NANOS);
        tickStartNanos = nanoTime.getAsLong();
        tickStarted = true;
    }

    public boolean hasBudgetRemaining() {
        return remainingNanos() > 0;
    }

    public long remainingNanos() {
        if (!tickStarted) {
            return 0;
        }
        long elapsed = nanoTime.getAsLong() - tickStartNanos;
        return Math.max(0L, budgetNanos - Math.max(0L, elapsed));
    }
}
