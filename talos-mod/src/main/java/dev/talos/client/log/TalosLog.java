package dev.talos.client.log;

import dev.talos.client.script.ScriptEngine;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared leveled logging facility for Talos scripts and engine traces. */
public final class TalosLog {
    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static final Logger LOGGER = LoggerFactory.getLogger("Talos Detailed Log");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Object LOCK = new Object();
    private static volatile boolean debug;
    private static boolean initialized;
    private static BufferedWriter writer;
    private static volatile Path logFile;

    private TalosLog() {}

    public static void init() {
        synchronized (LOCK) {
            if (initialized) return;
            initialized = true;
            try {
                Path directory = Path.of(System.getProperty("user.home"), ".talos", "logs");
                Files.createDirectories(directory);
                String timestamp = LocalDateTime.now().format(FILE_TIME);
                logFile = directory.resolve("session-" + timestamp + ".log");
                writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                rotate(directory);
            } catch (IOException | RuntimeException error) {
                writer = null;
                LOGGER.error("Unable to initialize detailed Talos logging", error);
            }
        }
    }

    private static void rotate(Path directory) {
        try (var files = Files.list(directory)) {
            List<Path> sessions = files
                    .filter(path -> path.getFileName().toString().matches("session-.*\\.log"))
                    .sorted((left, right) -> right.getFileName().toString()
                            .compareTo(left.getFileName().toString()))
                    .toList();
            for (int index = 10; index < sessions.size(); index++) {
                try {
                    Files.deleteIfExists(sessions.get(index));
                } catch (IOException error) {
                    LOGGER.warn("Unable to rotate old Talos log {}", sessions.get(index), error);
                }
            }
        } catch (IOException error) {
            LOGGER.warn("Unable to enumerate Talos logs for rotation", error);
        }
    }

    public static void setDebug(boolean enabled) { debug = enabled; }
    public static boolean isDebug() { return debug; }
    public static Path logFile() { return logFile; }

    public static void log(Level level, String category, String message) {
        log(level, category, message, true);
    }

    /**
     * @param chat forward the line to in-game chat via {@link ScriptEngine#CHAT}. Script logs
     *             pass {@code false} because the Python side already {@code print}s to the
     *             session's own sink (chat or the VS Code bridge) — forwarding here too would
     *             double every line.
     */
    public static void log(Level level, String category, String message, boolean chat) {
        Level actualLevel = level == null ? Level.INFO : level;
        String actualCategory = String.valueOf(category == null ? "general" : category);
        String actualMessage = String.valueOf(message);

        mirrorSlf4j(actualLevel, actualCategory, actualMessage);
        if (actualLevel != Level.DEBUG || debug) writeLine(actualLevel, actualCategory, actualMessage);
        if (chat && (actualLevel != Level.DEBUG || debug)) {
            String text = "[" + actualCategory + "] " + actualMessage;
            if (actualLevel == Level.DEBUG) text = "§7" + text;
            try {
                ScriptEngine.CHAT.log(actualLevel.name().toLowerCase(Locale.ROOT), text);
            } catch (RuntimeException error) {
                LOGGER.warn("Unable to forward Talos log line to chat", error);
            }
        }
    }

    private static void writeLine(Level level, String category, String message) {
        synchronized (LOCK) {
            if (!initialized) init();
            if (writer == null) return;
            try {
                writer.write(LocalDateTime.now().format(LINE_TIME));
                writer.write(" [" + level.name() + "] [" + category + "] " + message);
                writer.newLine();
                writer.flush();
            } catch (IOException error) {
                LOGGER.error("Unable to write Talos detailed log", error);
            }
        }
    }

    private static void mirrorSlf4j(Level level, String category, String message) {
        String line = "[" + category + "] " + message;
        switch (level) {
            case DEBUG -> LOGGER.debug(line);
            case INFO -> LOGGER.info(line);
            case WARN -> LOGGER.warn(line);
            case ERROR -> LOGGER.error(line);
        }
    }

    public static void trace(String category, String message) { log(Level.DEBUG, category, message); }
    public static void info(String category, String message) { log(Level.INFO, category, message); }
    public static void warn(String category, String message) { log(Level.WARN, category, message); }
    public static void error(String category, String message) { log(Level.ERROR, category, message); }
}
