package de.cloudnetwork.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Reads and writes the {@code CloudConfig.json} file in the working directory.
 */
public class ConfigManager {

    public static final Path CONFIG_PATH = Paths.get("CloudConfig.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
     * Persists the given config to {@code CloudConfig.json}.
     *
     * @throws IOException if the file cannot be written
     */
    public static void save(CloudConfig config) throws IOException {
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
            GSON.toJson(config, writer);
        }
    }
}
