package net.rasanovum.roxy.fog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class RoxyFogConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Settings settings;

    private RoxyFogConfig() {}

    public static synchronized Settings get() {
        if (settings == null) settings = read(FMLPaths.CONFIGDIR.get().resolve("roxy-fog.json"));
        return settings;
    }

    public static Settings read(Path path) {
        if (!Files.isRegularFile(path)) return new Settings();
        try (var reader = Files.newBufferedReader(path)) {
            Settings result = GSON.fromJson(reader, Settings.class);
            if (result == null) return new Settings();
            result.normalize();
            return result;
        } catch (IOException | RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger("Roxy").warn("Unable to read Roxy fog options; using defaults", exception);
            return new Settings();
        }
    }

    public static synchronized void save() {
        write(FMLPaths.CONFIGDIR.get().resolve("roxy-fog.json"), get());
    }

    public static void write(Path path, Settings value) {
        value.normalize();
        Path temporary = null;
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "roxy-fog-", ".tmp");
            Files.writeString(temporary, GSON.toJson(value));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save Roxy fog options", exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            }
        }
    }

    public static final class Settings {
        public static final int DEFAULT_START_CHUNKS = 12;
        public static final int DEFAULT_WEATHER_ROLL_IN = 25;
        public boolean automatic = true;
        public int start = DEFAULT_START_CHUNKS * 16;
        public int weatherRollIn = DEFAULT_WEATHER_ROLL_IN;

        public void normalize() {
            start = Math.max(0, Math.min(8176, start)) / 16 * 16;
            weatherRollIn = Math.max(0, Math.min(100, weatherRollIn));
        }

        public RoxyFogRange apply(RoxyFogRange range, float voxyDistance) {
            if (range == null || !Float.isFinite(voxyDistance) || voxyDistance <= 0) return range;
            float fogStart = Math.min(start, voxyDistance - Math.min(16.0F, voxyDistance));
            return new RoxyFogRange(fogStart, voxyDistance);
        }
    }
}
