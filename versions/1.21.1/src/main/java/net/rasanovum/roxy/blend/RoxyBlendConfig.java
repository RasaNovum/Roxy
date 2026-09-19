package net.rasanovum.roxy.blend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Roxy-owned settings for the optional real/LoD terrain transition. */
public final class RoxyBlendConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "roxy-blend.json";
    private static final float MIN_TRANSITION_BAND_BLOCKS = 16.0F;
    private static final float MAX_TRANSITION_BAND_BLOCKS = 64.0F;
    private static Settings settings;

    private RoxyBlendConfig() {}

    public static synchronized Settings get() {
        if (settings == null) settings = read(configPath());
        return settings;
    }

    /** Stable render-side accessor for the real/LoD blending implementation. */
    public static boolean enabled() {
        return get().renderDistanceSmoothing;
    }

    /** Start of the transition band, relative to the effective vanilla terrain edge. */
    public static float transitionStartBlocks(float effectiveRenderDistanceBlocks) {
        if (!Float.isFinite(effectiveRenderDistanceBlocks)) return 0.0F;
        float edge = Math.max(0.0F, effectiveRenderDistanceBlocks);
        float band = Math.clamp(
                edge * 0.25F,
                MIN_TRANSITION_BAND_BLOCKS,
                MAX_TRANSITION_BAND_BLOCKS
        );
        return Math.min(edge, Math.max(16.0F, edge - band));
    }

    /** End of the transition band, relative to the effective vanilla terrain edge. */
    public static float transitionEndBlocks(float effectiveRenderDistanceBlocks) {
        if (!Float.isFinite(effectiveRenderDistanceBlocks)) return 0.0F;
        return Math.max(0.0F, effectiveRenderDistanceBlocks);
    }

    public static synchronized void save() {
        write(configPath(), get());
    }

    public static Settings read(Path path) {
        if (!Files.isRegularFile(path)) return new Settings();
        try (var reader = Files.newBufferedReader(path)) {
            Settings result = GSON.fromJson(reader, Settings.class);
            if (result == null) return new Settings();
            result.normalize();
            return result;
        } catch (IOException | RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger("Roxy").warn(
                    "Unable to read Roxy blend options; using defaults", exception);
            return new Settings();
        }
    }

    public static void write(Path path, Settings value) {
        value.normalize();
        Path temporary = null;
        try {
            Path absolute = path.toAbsolutePath();
            Files.createDirectories(absolute.getParent());
            temporary = Files.createTempFile(absolute.getParent(), "roxy-blend-", ".tmp");
            Files.writeString(temporary, GSON.toJson(value));
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save Roxy blend options", exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            }
        }
    }

    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    public static final class Settings {
        public static final boolean DEFAULT_RENDER_DISTANCE_SMOOTHING = true;

        public boolean renderDistanceSmoothing = DEFAULT_RENDER_DISTANCE_SMOOTHING;

        public void normalize() {
            // Reserved for future render-setting validation.
        }
    }
}
