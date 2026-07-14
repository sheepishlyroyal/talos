package dev.talos.client.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistence for the first-run permission summary: which script contents (by SHA-256)
 * have already been summarized to the user. Stored as {@code talos-trusted.json} next to
 * {@code talos.json} in the Fabric config directory. Purely informational — nothing here
 * ever blocks execution, and every failure degrades to "summarize again next time".
 */
public final class ScriptTrust {
    private static final Logger LOGGER = LoggerFactory.getLogger("Talos");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "talos-trusted.json";

    private static Map<String, String> seen;

    private ScriptTrust() {}

    /** SHA-256 of the script source as lowercase hex. */
    public static String hash(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** True when this exact source of this script was already summarized. */
    public static synchronized boolean isKnown(String scriptName, String hash) {
        return hash.equals(load().get(scriptName));
    }

    /** Records the hash (replacing any previous one — edits re-summarize) and persists. */
    public static synchronized void remember(String scriptName, String hash) {
        load().put(scriptName, hash);
        save();
    }

    private static Map<String, String> load() {
        if (seen != null) return seen;
        seen = new HashMap<>();
        Path path = file();
        if (path == null || !Files.exists(path)) return seen;
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, String> loaded = GSON.fromJson(json,
                    new TypeToken<Map<String, String>>() {}.getType());
            if (loaded != null) seen.putAll(loaded);
        } catch (IOException | JsonSyntaxException error) {
            LOGGER.warn("Could not read {}; scripts will re-summarize", FILE_NAME, error);
        }
        return seen;
    }

    private static void save() {
        Path path = file();
        if (path == null) return;
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(seen), StandardCharsets.UTF_8);
        } catch (IOException error) {
            LOGGER.warn("Could not save {}", FILE_NAME, error);
        }
    }

    private static Path file() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (RuntimeException headless) {
            return null; // no Fabric loader (tests) — skip persistence entirely
        }
    }
}
