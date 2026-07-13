package dev.talos.client.bridge;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Lifecycle facade used by client initialization and commands. */
public final class TalosBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Talos Bridge");

    /** How many consecutive ports (starting at the default) to try before giving up. */
    private static final int PORT_ATTEMPTS = 4;

    private static volatile TalosWebSocketServer server;

    private TalosBridge() {}

    /**
     * Starts the VS Code bridge, tolerating a busy port (e.g. a stale instance from a
     * previous run holding 127.0.0.1:43077). We probe each candidate port synchronously
     * before starting the async WebSocket thread, so a bind failure is caught here and
     * NEVER propagates as a fatal error that would abort mod init — the bridge is simply
     * disabled for the session if no port is free.
     */
    public static void start() {
        for (int i = 0; i < PORT_ATTEMPTS; i++) {
            int port = TalosWebSocketServer.PORT + i;
            if (!isPortFree(port)) continue;
            try {
                TalosWebSocketServer created = new TalosWebSocketServer(new BridgeAuth(), port);
                created.start();
                server = created;
                if (port != TalosWebSocketServer.PORT) {
                    LOGGER.warn("Talos bridge bound fallback port 127.0.0.1:{} (default {} in use)",
                            port, TalosWebSocketServer.PORT);
                }
                return;
            } catch (IOException | RuntimeException error) {
                LOGGER.warn("Talos bridge could not start on 127.0.0.1:{}: {}", port, error.getMessage());
            }
        }
        LOGGER.warn("Talos bridge could not bind 127.0.0.1:{} (port in use); VS Code bridge disabled this session",
                TalosWebSocketServer.PORT);
    }

    /** True if a loopback socket can currently bind to {@code port} (best-effort probe). */
    private static boolean isPortFree(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(false);
            probe.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    public static void stop() {
        TalosWebSocketServer current = server;
        server = null;
        if (current == null) return;
        try { current.stop(1000); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }

    public static int allowSession() {
        TalosWebSocketServer current = server;
        return current == null ? 0 : current.allowSession();
    }

    public static String statusText() {
        TalosWebSocketServer current = server;
        return current == null ? "bridge is not running" : current.statusText();
    }
}
