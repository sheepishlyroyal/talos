package dev.talos.client.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/** Per-process authentication secret shared through the current user's home directory. */
public final class BridgeAuth {
    private final String token;
    private final Path tokenFile;

    public BridgeAuth() throws IOException {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        tokenFile = Path.of(System.getProperty("user.home"), ".talos", "token");
        Files.createDirectories(tokenFile.getParent());
        try {
            Files.setPosixFilePermissions(tokenFile.getParent(), PosixFilePermissions.fromString("rwx------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Best effort on non-POSIX file systems and restricted home directories.
        }
        Files.writeString(tokenFile, token + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Best effort on non-POSIX file systems and restricted home directories.
        }
    }

    public String token() { return token; }
    public Path tokenFile() { return tokenFile; }
}
