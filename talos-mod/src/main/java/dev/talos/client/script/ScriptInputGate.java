package dev.talos.client.script;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

/**
 * Chat-input capture for scripts. While at least one request is pending, the next plain
 * chat message the user sends is CONSUMED — cancelled before it reaches the server — and
 * delivered to the oldest waiting request instead. Commands (anything the chat screen
 * routes through sendChatCommand, i.e. "/...") are never captured, so /talos script stop
 * always works while a script is waiting for input.
 *
 * <p>All methods are thread-safe: requests arrive from the script worker, messages from
 * the client thread, cancellation from wherever the script future is failed.
 */
public final class ScriptInputGate {
    private static final Deque<Request> pending = new ArrayDeque<>();

    private record Request(String prompt, CompletableFuture<String> future) {}

    private ScriptInputGate() {}

    /** Queue a capture request. Completing/cancelling the future unregisters it. */
    public static CompletableFuture<String> request(String prompt) {
        CompletableFuture<String> future = new CompletableFuture<>();
        synchronized (pending) {
            pending.addLast(new Request(prompt, future));
        }
        future.whenComplete((result, error) -> {
            synchronized (pending) {
                pending.removeIf(r -> r.future() == future);
            }
        });
        return future;
    }

    /**
     * Offer an outgoing chat message. Returns true when a waiting request consumed it —
     * the caller must then cancel the send so the message never reaches the server.
     */
    public static boolean offer(String message) {
        while (true) {
            Request head;
            synchronized (pending) {
                head = pending.pollFirst();
            }
            if (head == null) return false;
            if (head.future().complete(message)) return true;
            // Already cancelled (script stopped): drop it and try the next waiter.
        }
    }

    /** True while any live request is waiting (used to hint the capture in the UI). */
    public static boolean awaiting() {
        synchronized (pending) {
            return pending.stream().anyMatch(r -> !r.future().isDone());
        }
    }
}
