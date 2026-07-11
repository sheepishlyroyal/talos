package dev.glade.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import dev.glade.client.GladeClient;
import dev.glade.client.humanize.HumanizationProfile;
import dev.glade.client.ui.theme.Theme;
import dev.glade.client.ui.theme.ThemeMode;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Static owner of the persisted {@link GladeConfig}. Loads/saves
 * {@code glade.json} in the Fabric config directory and keeps it in sync with
 * the live {@link Theme} and {@link dev.glade.client.humanize.Humanizer} state.
 *
 * <p>Load/save never throws to callers: any I/O or parse failure is logged and
 * falls back to defaults so a corrupt or missing config file can never crash
 * client init.
 */
public final class GladeConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Glade");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "glade.json";

    private static volatile GladeConfig config = new GladeConfig();

    private GladeConfigManager() {
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** Loads {@code glade.json}, falling back to (and persisting) defaults on any failure. */
    public static synchronized GladeConfig load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            LOGGER.info("No Glade config found at {}, writing defaults", path);
            config = new GladeConfig();
            save();
            return config;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            GladeConfig loaded = GSON.fromJson(json, GladeConfig.class);
            config = loaded != null ? loaded : new GladeConfig();
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.warn("Failed to load Glade config from {}, falling back to defaults", path, e);
            config = new GladeConfig();
            save();
        }
        return config;
    }

    public static GladeConfig get() {
        return config;
    }

    /** Writes the current config to disk atomically (temp file + move). Never throws. */
    public static synchronized void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, GSON.toJson(config), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save Glade config to {}", path, e);
        }
    }

    /**
     * Applies a theme mode by name to the live {@link Theme} and persists it.
     * Invalid names are logged and ignored (config + live theme left unchanged).
     */
    public static synchronized void setThemeMode(String name) {
        ThemeMode mode;
        try {
            mode = ThemeMode.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unknown theme mode '{}', ignoring", name);
            return;
        }
        Theme.setMode(mode);
        config.themeMode = mode.name();
        save();
    }

    /**
     * Applies a humanization profile by name to the live {@link dev.glade.client.humanize.Humanizer}
     * and persists it. Invalid names are logged and ignored.
     */
    public static synchronized void setActiveProfile(String name) {
        HumanizationProfile profile;
        try {
            profile = HumanizationProfile.byName(name);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unknown humanization profile '{}', ignoring", name);
            return;
        }
        GladeClient.humanizer().setDefaultProfile(profile);
        config.activeProfile = profile.name().toUpperCase(Locale.ROOT);
        save();
    }
}
