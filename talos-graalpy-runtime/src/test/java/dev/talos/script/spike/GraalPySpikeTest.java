package dev.talos.script.spike;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

final class GraalPySpikeTest {
    @Test
    void provesClassloadingSandboxMarshallingStdlibAndStartup() throws Exception {
        ExecutorService game = namedExecutor("game-thread");
        ExecutorService worker = namedExecutor("script-worker");
        try {
            SpikeResult result = worker.submit(() -> {
                assertEquals("script-worker", Thread.currentThread().getName());
                long start = System.nanoTime();
                try (Context context = GraalPySpike.createContext()) {
                    long coldMs = elapsedMs(start);
                    assertTrue(context.getEngine().getLanguages().containsKey("python"));

                    TalosNativeBridge bridge = new TalosNativeBridge(game);
                    context.getBindings("python").putMember("bridge", bridge);
                    long importStart = System.nanoTime();
                    String json = context.eval("python", "import json\njson.dumps({'a': 1}, separators=(',', ':'))").asString();
                    long importMs = elapsedMs(importStart);
                    assertEquals("{\"a\":1}", json);

                    assertTrue(context.eval("python", "callable(bridge.submitAction)").asBoolean());
                    assertFalse(context.eval("python", "hasattr(bridge, 'nonExported')").asBoolean());
                    String value = context.eval("python", "bridge.submitAction('walk').get()").asString();
                    assertEquals("handled:walk", value);
                    assertEquals("game-thread", bridge.executionThread.get());
                    System.out.printf("GraalPy cold-start: %d ms; first stdlib import: %d ms%n", coldMs, importMs);
                    return new SpikeResult(coldMs, importMs);
                }
            }).get(30, TimeUnit.SECONDS);
            assertTrue(result.coldMs >= 0 && result.importMs >= 0);
        } finally {
            worker.shutdownNow();
            game.shutdownNow();
        }
    }

    @Test
    void closeTrueHardStopsBusyPythonFromAnotherThread() throws Exception {
        ExecutorService worker = namedExecutor("script-worker");
        AtomicReference<Context> contextRef = new AtomicReference<>();
        CountDownLatch running = new CountDownLatch(1);
        try {
            Future<?> evaluation = worker.submit(() -> {
                assertEquals("script-worker", Thread.currentThread().getName());
                Context context = GraalPySpike.createContext();
                contextRef.set(context);
                running.countDown();
                try {
                    context.eval("python", "while True: pass");
                    fail("Infinite Python unexpectedly completed");
                } catch (PolyglotException expected) {
                    assertTrue(expected.isCancelled(), "close(true) must cancel evaluation");
                }
            });
            assertTrue(running.await(10, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> contextRef.get().close(true));
            evaluation.get(5, TimeUnit.SECONDS);
        } finally {
            Context context = contextRef.get();
            if (context != null) {
                try { context.close(true); } catch (RuntimeException ignored) { }
            }
            worker.shutdownNow();
        }
    }

    private static ExecutorService namedExecutor(String name) {
        return Executors.newSingleThreadExecutor(r -> new Thread(r, name));
    }

    private static long elapsedMs(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private record SpikeResult(long coldMs, long importMs) {}

    public static final class TalosNativeBridge {
        private final ExecutorService game;
        private final AtomicReference<String> executionThread = new AtomicReference<>();

        TalosNativeBridge(ExecutorService game) { this.game = game; }

        @HostAccess.Export
        public AwaitableFuture submitAction(String action) {
            AwaitableFuture result = new AwaitableFuture();
            game.execute(() -> {
                executionThread.set(Thread.currentThread().getName());
                result.complete("handled:" + action);
            });
            return result;
        }

        public String nonExported() { return "sandbox breach"; }
    }

    public static final class AwaitableFuture extends CompletableFuture<String> {
        @Override
        @HostAccess.Export
        public String get() throws InterruptedException, ExecutionException {
            assertEquals("script-worker", Thread.currentThread().getName(), "Only worker may receive Python calls");
            return super.get();
        }
    }
}
