package dev.talos.client.bridge;

import dev.talos.client.script.ScriptEngine;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loopback-only, authenticated bridge for the Talos VS Code extension. */
public final class TalosWebSocketServer extends WebSocketServer {
    public static final int PORT = 43077;
    private static final Logger LOGGER = LoggerFactory.getLogger("Talos Bridge");

    private final int port;
    private final BridgeAuth auth;
    private final Set<WebSocket> authenticated = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<WebSocket, Set<String>> pushed = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WebSocket, String> pendingRuns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WebSocket, CompletableFuture<Void>> activeRuns = new ConcurrentHashMap<>();
    // The bridge is allowed by default: it only ever binds 127.0.0.1, requires the local
    // token file, and rejects browser origins — the remaining risk is the local user, who
    // is exactly who is pressing Run in VS Code. /talos bridge allow stays as a no-op arm.
    private volatile boolean sessionAllowed = true;
    private volatile boolean confirmationRequested;

    public TalosWebSocketServer(BridgeAuth auth) throws IOException {
        this(auth, PORT);
    }

    public TalosWebSocketServer(BridgeAuth auth, int port) throws IOException {
        super(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        this.port = port;
        this.auth = auth;
        setReuseAddr(false);
    }

    public int port() {
        return port;
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        String origin = handshake.getFieldValue("Origin");
        if (origin != null && (origin.regionMatches(true, 0, "http://", 0, 7)
                || origin.regionMatches(true, 0, "https://", 0, 8))) {
            LOGGER.warn("Rejected browser WebSocket origin: {}", origin);
            connection.close(CloseFrame.POLICY_VALIDATION, "Browser origins are not allowed");
            return;
        }
        pushed.put(connection, ConcurrentHashMap.newKeySet());
    }

    @Override
    public void onMessage(WebSocket connection, String raw) {
        final BridgeProtocol.ClientMessage message;
        try {
            message = BridgeProtocol.parseClient(raw);
        } catch (BridgeProtocol.ProtocolException error) {
            if (!authenticated.contains(connection)) rejectAuth(connection, error.getMessage());
            else send(connection, BridgeProtocol.log("warn", error.getMessage()));
            return;
        }
        if (!authenticated.contains(connection)) {
            if (!(message instanceof BridgeProtocol.Auth request)) {
                rejectAuth(connection, "Authentication must be the first message");
                return;
            }
            byte[] expected = auth.token().getBytes(StandardCharsets.UTF_8);
            byte[] supplied = request.token() == null ? new byte[0] : request.token().getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, supplied)) {
                rejectAuth(connection, "Invalid token");
                return;
            }
            authenticated.add(connection);
            send(connection, BridgeProtocol.authOk());
            send(connection, BridgeProtocol.status("idle"));
            return;
        }
        if (message == null) return; // Additive/unknown v1 message.
        if (message instanceof BridgeProtocol.Auth) {
            send(connection, BridgeProtocol.log("warn", "Already authenticated"));
        } else if (message instanceof BridgeProtocol.PushScript request) {
            acceptPush(connection, request);
        } else if (message instanceof BridgeProtocol.Run request) {
            acceptRun(connection, request.name());
        } else if (message instanceof BridgeProtocol.Stop) {
            stopRun(connection);
        }
    }

    @Override
    public void onMessage(WebSocket connection, ByteBuffer bytes) {
        connection.close(CloseFrame.REFUSE, "Binary frames are not supported");
    }

    private void acceptPush(WebSocket connection, BridgeProtocol.PushScript request) {
        if (!validName(request.name()) || request.source() == null) {
            send(connection, BridgeProtocol.log("error", "Invalid script name or source"));
            return;
        }
        try {
            Path scripts = FabricLoader.getInstance().getGameDir().resolve("talos").resolve("scripts");
            Files.createDirectories(scripts);
            String filename = request.name().endsWith(".py") ? request.name() : request.name() + ".py";
            Path file = scripts.resolve(filename).normalize();
            if (!file.getParent().equals(scripts)) throw new IOException("Script path escapes scripts directory");
            Files.writeString(file, request.source(), StandardCharsets.UTF_8);
            pushed.computeIfAbsent(connection, ignored -> ConcurrentHashMap.newKeySet()).add(request.name());
            LOGGER.info("Accepted VS Code script push: {}", request.name());
            chat("Talos accepted script from VS Code: " + request.name());
            if (!sessionAllowed && !confirmationRequested) {
                confirmationRequested = true;
                chat("VS Code wants to run " + request.name() + " — /talos bridge allow to accept");
            }
        } catch (IOException error) {
            LOGGER.warn("Could not save pushed script {}", request.name(), error);
            send(connection, BridgeProtocol.log("error", "Could not save script: " + error.getMessage()));
        }
    }

    private void acceptRun(WebSocket connection, String name) {
        if (!validName(name) || !pushed.getOrDefault(connection, Set.of()).contains(name)) {
            send(connection, BridgeProtocol.done(false, "Script was not pushed on this connection"));
            return;
        }
        if (!sessionAllowed) {
            pendingRuns.put(connection, name);
            send(connection, BridgeProtocol.log("warn", "Waiting for /talos bridge allow in Minecraft"));
            return;
        }
        runScript(connection, name);
    }

    private void runScript(WebSocket connection, String name) {
        if (activeRuns.containsKey(connection)) {
            send(connection, BridgeProtocol.done(false, "A script is already running"));
            return;
        }
        send(connection, BridgeProtocol.status("running"));
        CompletableFuture<Void> future = ScriptEngine.instance().run(name,
                (level, text) -> send(connection, BridgeProtocol.log(level, text)));
        activeRuns.put(connection, future);
        future.whenComplete((ignored, error) -> {
            if (!activeRuns.remove(connection, future)) return;
            send(connection, BridgeProtocol.status("idle"));
            send(connection, BridgeProtocol.done(error == null, error == null ? null : rootMessage(error)));
        });
    }

    private void stopRun(WebSocket connection) {
        pendingRuns.remove(connection);
        CompletableFuture<Void> active = activeRuns.remove(connection);
        ScriptEngine.instance().stop();
        send(connection, BridgeProtocol.status("stopped"));
        if (active != null) send(connection, BridgeProtocol.done(false, "Stopped"));
    }

    /** Arms script execution until this Minecraft client process exits. */
    public int allowSession() {
        sessionAllowed = true;
        confirmationRequested = false;
        chat("Talos VS Code bridge allowed for this session");
        pendingRuns.forEach((connection, name) -> {
            if (pendingRuns.remove(connection, name) && authenticated.contains(connection)) runScript(connection, name);
        });
        return 1;
    }

    public String statusText() {
        return "bridge listening on 127.0.0.1:" + port + ", "
                + (sessionAllowed ? "allowed" : "awaiting /talos bridge allow");
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_.-]+") && !name.contains("..");
    }

    private static String rootMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void chat(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) client.player.sendMessage(Text.literal(message), false);
            else LOGGER.info(message);
        });
    }

    private static void send(WebSocket connection, String message) {
        if (connection != null && connection.isOpen()) connection.send(message);
    }

    private static void rejectAuth(WebSocket connection, String reason) {
        send(connection, BridgeProtocol.authErr(reason));
        connection.close(CloseFrame.POLICY_VALIDATION, reason);
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        authenticated.remove(connection);
        pushed.remove(connection);
        pendingRuns.remove(connection);
        activeRuns.remove(connection);
    }

    @Override
    public void onError(WebSocket connection, Exception error) {
        if (connection == null) LOGGER.warn("Talos bridge could not bind/start on 127.0.0.1:{}: {}", port, error.getMessage());
        else LOGGER.warn("Talos bridge connection error: {}", error.getMessage());
    }

    @Override
    public void onStart() {
        LOGGER.info("Talos bridge listening on 127.0.0.1:{}; token file {}", port, auth.tokenFile());
    }
}
