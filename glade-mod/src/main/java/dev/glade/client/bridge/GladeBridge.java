package dev.glade.client.bridge;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Lifecycle facade used by client initialization and commands. */
public final class GladeBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Glade Bridge");
    private static volatile GladeWebSocketServer server;

    private GladeBridge() {}

    public static void start() {
        try {
            GladeWebSocketServer created = new GladeWebSocketServer(new BridgeAuth());
            server = created;
            created.start();
        } catch (IOException | RuntimeException error) {
            LOGGER.warn("Glade bridge did not start: {}", error.getMessage(), error);
        }
    }

    public static void stop() {
        GladeWebSocketServer current = server;
        server = null;
        if (current == null) return;
        try { current.stop(1000); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }

    public static int allowSession() {
        GladeWebSocketServer current = server;
        return current == null ? 0 : current.allowSession();
    }

    public static String statusText() {
        GladeWebSocketServer current = server;
        return current == null ? "bridge is not running" : current.statusText();
    }
}
