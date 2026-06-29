package de.cloudnetwork.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Reads and writes the {@code CloudConfig.json} file in the working directory.
 *
 * <p><b>Security note:</b> The config file contains database credentials in
 * plaintext.  {@link #save} sets POSIX permissions to {@code 600} (owner
 * read/write only) on platforms that support it.  On Windows the file is
 * created with default permissions.</p>
 */
public class ConfigManager {

    public static final Path CONFIG_PATH = Paths.get("CloudConfig.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** POSIX permissions applied to CloudConfig.json: owner read+write only. */
    private static final Set<PosixFilePermission> OWNER_RW =
            PosixFilePermissions.fromString("rw-------");

    private ConfigManager() {}

    /** Returns {@code true} when {@code CloudConfig.json} exists. */
    public static boolean configExists() {
        return Files.exists(CONFIG_PATH);
    }

    /**
     * Loads and returns the config.
     *
     * @throws IOException if the file cannot be read or is malformed
     */
    public static CloudConfig load() throws IOException {
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            CloudConfig config = GSON.fromJson(reader, CloudConfig.class);
            if (config == null) {
                throw new IOException("CloudConfig.json ist leer oder ungültig.");
            }
            return config;
        }
    }

    /**
     * Persists the given config to {@code CloudConfig.json} and restricts the
     * file to owner read/write (mode 600) on POSIX systems to protect the
     * credentials stored inside.
     *
     * @throws IOException if the file cannot be written
     */
    public static void save(CloudConfig config) throws IOException {
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
            GSON.toJson(config, writer);
        }
        applyRestrictivePermissions();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Applies owner-only (600) POSIX permissions to {@code CloudConfig.json}.
     * Silently skips on non-POSIX file systems (e.g. Windows).
     */
    private static void applyRestrictivePermissions() {
        try {
            Files.setPosixFilePermissions(CONFIG_PATH, OWNER_RW);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Not a POSIX filesystem – skip silently.
        }
    }
}
