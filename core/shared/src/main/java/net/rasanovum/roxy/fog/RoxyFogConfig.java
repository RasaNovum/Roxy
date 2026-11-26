package net.rasanovum.roxy.fog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rasanovum.roxy.core.RoxyPlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class RoxyFogConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Settings settings;

    private RoxyFogConfig() {}

    public static Settings get() {
        Settings cached = settings;
        if (cached != null) return cached;
        synchronized (RoxyFogConfig.class) {
            cached = settings;
            if (cached == null) {
                Path configDir = configDirectory();
                cached = configDir == null ? new Settings() : read(configDir.resolve("roxy-fog.json"));
                settings = cached;
            }
            return cached;
        }
    }

    public static Settings read(Path path) {
        if (path == null || !Files.isRegularFile(path)) return new Settings();
        try (var reader = Files.newBufferedReader(path)) {
            JsonObject input = GSON.fromJson(reader, JsonObject.class);
            if (input == null) return new Settings();
            Settings result = new Settings();
            JsonElement automatic = input.get("automatic");
            if (automatic != null && automatic.isJsonPrimitive() && automatic.getAsJsonPrimitive().isBoolean()) {
                result.automatic = automatic.getAsBoolean();
            }
            JsonElement terrainEnabled = input.get("terrainEnabled");
            if (terrainEnabled != null && terrainEnabled.isJsonPrimitive() && terrainEnabled.getAsJsonPrimitive().isBoolean()) {
                result.terrainEnabled = terrainEnabled.getAsBoolean();
            }
            JsonElement start = input.get("start");
            if (start != null && start.isJsonPrimitive() && start.getAsJsonPrimitive().isNumber()) {
                result.start = start.getAsInt();
            } else {
                JsonElement terrainStart = input.get("terrainStart");
                if (terrainStart != null && terrainStart.isJsonPrimitive() && terrainStart.getAsJsonPrimitive().isNumber()) {
                    result.start = terrainStart.getAsInt();
                }
            }
            JsonElement weatherRollIn = input.get("weatherRollIn");
            if (weatherRollIn != null && weatherRollIn.isJsonPrimitive() && weatherRollIn.getAsJsonPrimitive().isNumber()) {
                result.weatherRollIn = weatherRollIn.getAsInt();
            }
            result.normalize();
            return result;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Unable to read Roxy fog options; using defaults", exception);
            return new Settings();
        }
    }

    public static synchronized void save() {
        Path configDir = configDirectory();
        if (configDir == null) return;
        write(configDir.resolve("roxy-fog.json"), get());
    }

    public static void write(Path path, Settings value) {
        if (path == null || value == null) return;
        value.normalize();
        Path temporary = null;
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                temporary = Files.createTempFile(parent, "roxy-fog-", ".tmp");
            } else {
                temporary = Files.createTempFile("roxy-fog-", ".tmp");
            }
            Files.writeString(temporary, GSON.toJson(value));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Unable to save Roxy fog options to {}", path, exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            }
        }
    }

    private static Path configDirectory() {
        return RoxyPlatform.configDir();
    }

    public static final class Settings {
        public static final int DEFAULT_START_CHUNKS = 12;
        public static final int DEFAULT_WEATHER_ROLL_IN = 25;
        public boolean automatic = true;
        public int start = DEFAULT_START_CHUNKS * 16;
        public int weatherRollIn = DEFAULT_WEATHER_ROLL_IN;
        public boolean terrainEnabled = true;

        public void normalize() {
            start = Math.max(0, Math.min(8176, start)) / 16 * 16;
            weatherRollIn = Math.max(0, Math.min(100, weatherRollIn));
        }

        public float computeStart(float voxyDistance) {
            return Math.max(0.0F, Math.min(start, voxyDistance - Math.min(16.0F, voxyDistance)));
        }

        public RoxyFogRange apply(RoxyFogRange range, float voxyDistance) {
            if (range == null || !Float.isFinite(voxyDistance) || voxyDistance <= 0) return range;
            return new RoxyFogRange(computeStart(voxyDistance), voxyDistance);
        }
    }
}
