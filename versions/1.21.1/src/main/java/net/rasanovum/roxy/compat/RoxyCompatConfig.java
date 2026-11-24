package net.rasanovum.roxy.compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class RoxyCompatConfig {
    private static final String FILE_NAME = "roxy-compat.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");

    private RoxyCompatConfig() {
    }

    public static boolean terrainSlabsEnabled() {
        return Startup.ACTIVE.terrainSlabs;
    }

    public static boolean tfcEnabled() {
        return Startup.ACTIVE.tfc;
    }

    public static boolean copycatsEnabled() {
        return Startup.ACTIVE.copycats;
    }

    public static boolean powerGridEnabled() {
        return Startup.ACTIVE.powerGrid;
    }

    public static Settings optionsForRestart() {
        return Startup.PENDING;
    }

    public static synchronized void save() {
        if (Startup.PATH == null) throw new IllegalStateException("Roxy config directory is unavailable");
        try {
            write(Startup.PATH, Startup.PENDING);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save Roxy compatibility options", exception);
        }
    }

    private static Settings read(Path path) {
        if (path == null) return new Settings();
        try {
            if (Files.notExists(path)) {
                Settings defaults = new Settings();
                write(path, defaults);
                return defaults;
            }
            try (var reader = Files.newBufferedReader(path)) {
                Settings settings = GSON.fromJson(reader, Settings.class);
                if (settings != null) return settings;
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Unable to load Roxy compatibility options from {}; using defaults", path, exception);
        }
        return new Settings();
    }

    private static void write(Path path, Settings settings) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "roxy-compat-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(settings) + System.lineSeparator());
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path configPath() {
        try {
            Path directory = FMLPaths.CONFIGDIR.get();
            return directory == null ? null : directory.resolve(FILE_NAME);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static final class Startup {
        private static final Path PATH = configPath();
        private static final Settings ACTIVE = read(PATH);
        private static final Settings PENDING = new Settings(ACTIVE);
    }

    public static final class Settings {
        public boolean terrainSlabs = true;
        public boolean tfc = true;
        public boolean copycats = true;
        public boolean powerGrid = true;

        private Settings() {
        }

        private Settings(Settings active) {
            terrainSlabs = active.terrainSlabs;
            tfc = active.tfc;
            copycats = active.copycats;
            powerGrid = active.powerGrid;
        }
    }
}
