package dev.talos.client.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/** JSON protocol shared with vscode-extension/src/protocol.ts. */
public final class BridgeProtocol {
    public static final int VERSION = 1;
    private static final Gson GSON = new Gson();

    private BridgeProtocol() {}

    public sealed interface ClientMessage permits Auth, PushScript, Run, Eval, Stop {}
    public record Auth(int v, String type, String token) implements ClientMessage {}
    public record PushScript(int v, String type, String name, String source) implements ClientMessage {}
    /** {@code args} is optional (absent → null → empty argv), exposed as {@code talos.args}. */
    public record Run(int v, String type, String name, java.util.List<String> args) implements ClientMessage {}
    /** Runs a one-liner via ScriptEngine.evalSnippet — same gating as run. */
    public record Eval(int v, String type, String code) implements ClientMessage {}
    public record Stop(int v, String type) implements ClientMessage {}

    public record AuthOk(int v, String type) {}
    public record AuthErr(int v, String type, String reason) {}
    public record Log(int v, String type, String level, String text) {}
    public record Status(int v, String type, String state) {}
    public record ScriptDone(int v, String type, boolean success, String message) {}

    public static ClientMessage parseClient(String json) throws ProtocolException {
        try {
            JsonObject object = GSON.fromJson(json, JsonObject.class);
            if (object == null || !object.has("v") || !object.has("type"))
                throw new ProtocolException("Missing protocol envelope");
            if (object.get("v").getAsInt() != VERSION)
                throw new ProtocolException("Unsupported protocol version");
            String type = object.get("type").getAsString();
            return switch (type) {
                case "auth" -> GSON.fromJson(object, Auth.class);
                case "push_script" -> GSON.fromJson(object, PushScript.class);
                case "run" -> GSON.fromJson(object, Run.class);
                case "eval" -> GSON.fromJson(object, Eval.class);
                case "stop" -> GSON.fromJson(object, Stop.class);
                default -> null;
            };
        } catch (JsonParseException | IllegalStateException | NumberFormatException error) {
            throw new ProtocolException("Invalid JSON message");
        }
    }

    public static String authOk() { return GSON.toJson(new AuthOk(VERSION, "auth_ok")); }
    public static String authErr(String reason) { return GSON.toJson(new AuthErr(VERSION, "auth_err", reason)); }
    public static String log(String level, String text) { return GSON.toJson(new Log(VERSION, "log", level, text)); }
    public static String status(String state) { return GSON.toJson(new Status(VERSION, "status", state)); }
    public static String done(boolean success, String message) {
        JsonObject object = new JsonObject();
        object.addProperty("v", VERSION);
        object.addProperty("type", "script_done");
        object.addProperty("success", success);
        if (message != null) object.addProperty("message", message);
        return GSON.toJson(object);
    }

    public static final class ProtocolException extends Exception {
        public ProtocolException(String message) { super(message); }
    }
}
