package dev.talos.client.script;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;

/** Bounded mailbox for work that must run on the Minecraft client tick thread. */
public final class GameThreadExecutor {
    private static final int CAPACITY = 256;
    private static final GameThreadExecutor INSTANCE = new GameThreadExecutor();
    private final ArrayBlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(CAPACITY);

    private GameThreadExecutor() {}

    public static GameThreadExecutor instance() { return INSTANCE; }

    public <T> CompletableFuture<T> submit(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!queue.offer(() -> {
            try { future.complete(supplier.get()); }
            catch (Throwable error) { future.completeExceptionally(error); }
        })) {
            future.completeExceptionally(new IllegalStateException(
                    "Talos game-thread queue is full (capacity " + CAPACITY + ")"));
        }
        return future;
    }

    public void drain(MinecraftClient client) {
        if (!client.isOnThread()) throw new IllegalStateException("GameThreadExecutor drained off client thread");
        Runnable work;
        while ((work = queue.poll()) != null) work.run();
    }

}
