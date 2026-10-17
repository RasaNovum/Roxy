package net.rasanovum.roxy.blend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class RoxyBlendConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Settings settings;

    private RoxyBlendConfig() {}

    public static synchronized Settings get() {
        if (settings == null) settings = read(FMLPaths.CONFIGDIR.get().resolve("roxy-blend.json"));
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
            org.slf4j.LoggerFactory.getLogger("Roxy").warn("Unable to read Roxy LOD transition options; using defaults", exception);
            return new Settings();
        }
    }

    public static synchronized void save() {
        write(FMLPaths.CONFIGDIR.get().resolve("roxy-blend.json"), get());
    }

    public static void write(Path path, Settings value) {
        value.normalize();
        Path temporary = null;
        try {
            Path parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "roxy-blend-", ".tmp");
            Files.writeString(temporary, GSON.toJson(value));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save Roxy LOD transition options", exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            }
        }
    }

    public static boolean enabled() {
        return get().enabled;
    }

    public static float transitionStartBlocks(float effectiveRenderDistanceBlocks) {
        if (!Float.isFinite(effectiveRenderDistanceBlocks) || effectiveRenderDistanceBlocks <= 0.0F) return 0.0F;
        float end = transitionEndBlocks(effectiveRenderDistanceBlocks);
        if (end <= 16.0F) return end;
        return Math.max(16.0F, end - get().widthChunks * 16.0F);
    }

    public static float transitionEndBlocks(float effectiveRenderDistanceBlocks) {
        if (!Float.isFinite(effectiveRenderDistanceBlocks) || effectiveRenderDistanceBlocks <= 16.0F) return 0.0F;
        return effectiveRenderDistanceBlocks - 16.0F;
    }

    public static final class Settings {
        public static final int DEFAULT_WIDTH_CHUNKS = 2;
        public boolean enabled = true;
        public int widthChunks = DEFAULT_WIDTH_CHUNKS;

        public void normalize() {
            widthChunks = Math.max(1, Math.min(8, widthChunks));
        }
    }
}
